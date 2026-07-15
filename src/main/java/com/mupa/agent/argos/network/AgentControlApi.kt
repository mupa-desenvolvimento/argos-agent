package com.mupa.agent.argos.network

import android.content.Context
import com.mupa.agent.argos.commands.ArgosApiClient
import com.mupa.agent.argos.commands.ArgosCommandResult
import com.mupa.agent.argos.commands.ArgosPendingCommand
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.models.AgentHeartbeatPayload
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ConfigurationResponse(
    val configurationHash: String,
    val payloadJson: String,
    val updatedAt: Long,
)

data class RegisterResult(
    val ok: Boolean,
    val deviceToken: String? = null,
    val apelidoInterno: String? = null,
    val error: String? = null,
)

class AgentControlApi(
    private val context: Context,
    private val settings: SettingsManager,
) {
    private val legacyApi = ArgosApiClient(context, settings)

    /**
     * Calls the `register-device` Supabase Edge Function once to create (or reuse) this
     * device's row in `public.devices` and obtain the `device_token` every other
     * authenticated call needs (heartbeat, commands/pending, commands/ack,
     * configuration/sync — all send it as `Authorization: Bearer <token>` via
     * getArgosDeviceTokenCached()).
     *
     * Deliberately bypasses `baseApiUrl()`/the `argos.mupa.app` TanStack deployment: that
     * domain's "/api/public/agent" routes were returning 500s in production (missing env
     * config), so registration goes straight to the Supabase Functions Gateway instead,
     * which only needs the project's anon key, not the (currently broken) web app deploy.
     *
     * Without ever calling this, nothing in the app populates the device token, so every
     * authenticated call is sent without one and the backend silently rejects it (401) —
     * which also means the device never appears in the Supabase `devices` table the web
     * console reads from. No-ops once a token is already cached.
     */
    fun ensureRegistered(serial: String): Boolean {
        if (settings.getArgosDeviceTokenCached().trim().isNotBlank()) return true
        return registerDevice(serial).ok
    }

    /**
     * Same call as [ensureRegistered], but always attempts the request (no early-return when
     * already cached) and returns a detailed result — used by the manual registration form
     * shown when a device doesn't have a token yet, so the technician sees a real error
     * message instead of a silent failure.
     */
    fun registerDevice(
        serial: String,
        apelidoInterno: String? = null,
        empresa: String? = null,
        numFilial: String? = null,
        cliente: String? = null,
    ): RegisterResult {
        val cleanSerial = serial.trim()
        if (cleanSerial.isBlank()) return RegisterResult(ok = false, error = "serial_empty")

        return runCatching {
            val conn = (URL(REGISTER_DEVICE_FUNCTION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
            }
            val body = JSONObject().apply {
                put("serial", cleanSerial)
                apelidoInterno?.trim()?.takeIf { it.isNotBlank() }?.let { put("apelido_interno", it) }
                empresa?.trim()?.takeIf { it.isNotBlank() }?.let { put("empresa", it) }
                numFilial?.trim()?.takeIf { it.isNotBlank() }?.let { put("num_filial", it) }
                cliente?.trim()?.takeIf { it.isNotBlank() }?.let { put("cliente", it) }
            }.toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299) {
                val serverError = runCatching { JSONObject(text).optString("error", "") }.getOrDefault("")
                return@runCatching RegisterResult(ok = false, error = serverError.ifBlank { "http_$code" })
            }

            val json = JSONObject(text)
            // register-device's response shape has changed over time — accept both a
            // nested {"device": {...}} object and the current flat shape.
            val deviceObj = json.optJSONObject("device")
            // org.json.JSONObject.optString() stringifies an explicit JSON null as the
            // literal text "null" instead of falling back to the default — every optString
            // read of a possibly-null backend field must filter that out explicitly.
            fun optStringOrNull(obj: JSONObject?, key: String): String? {
                if (obj == null || obj.isNull(key)) return null
                return obj.optString(key, "").trim().ifBlank { null }?.takeIf { it != "null" }
            }
            val token = optStringOrNull(deviceObj, "device_token") ?: optStringOrNull(json, "device_token").orEmpty()
            if (token.isBlank()) return@runCatching RegisterResult(ok = false, error = "device_token_missing")
            val apelido = optStringOrNull(deviceObj, "apelido_interno") ?: optStringOrNull(json, "apelido_interno")
            runBlocking {
                settings.setArgosDeviceToken(token)
                settings.setDeviceApelidoInterno(apelido.orEmpty())
            }
            RegisterResult(ok = true, deviceToken = token, apelidoInterno = apelido)
        }.getOrElse { t ->
            RegisterResult(ok = false, error = t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName })
        }
    }

    fun pollPendingCommands(deviceId: String): List<ArgosPendingCommand> {
        return legacyApi.fetchPendingCommands(deviceId)
    }

    fun ackCommand(deviceId: String, result: ArgosCommandResult): Boolean {
        return legacyApi.postCommandResult(deviceId, result)
    }

    fun fetchConfiguration(deviceId: String, currentHash: String?): ConfigurationResponse? {
        val base = baseApiUrl()
        if (base.isBlank()) return null
        // Real deployed routes (configuration.sync.ts / configuration.ts) tried first; /functions/v1/*
        // and bare /commands/* don't exist as Edge Functions yet — kept as forward-compatible fallbacks.
        val paths = listOf(
            "/public/agent/configuration/sync?device_id=${deviceId.trim()}&hash=${currentHash?.trim().orEmpty()}",
            "/public/agent/configuration?device_id=${deviceId.trim()}&hash=${currentHash?.trim().orEmpty()}",
            "/functions/v1/sync-configuration?device_id=${deviceId.trim()}&hash=${currentHash?.trim().orEmpty()}",
            "/commands/configuration?device_id=${deviceId.trim()}",
        )
        for (path in paths) {
            val resolved = requestConfiguration("$base$path")
            if (resolved.resolved) return resolved.response
        }
        return null
    }

    /**
     * The web console (console.tsx / OtaConsole.tsx) reads device info from Firebase RTDB at
     * m_argos/devices/{serial}/info — NOT from the Supabase device_heartbeat table that
     * sendHeartbeat() above writes to (that table has no reader at all in the web app).
     * Whatever keeps m_argos/devices/{serial}/info.updated_at fresh today doesn't carry
     * agent_version/agent_version_code, so the OTA "outdated device" detection in
     * OtaConsole.tsx silently never fires. This does a lightweight partial update (PATCH —
     * merges, doesn't replace the node) directly to that path so the version becomes visible
     * and comparable against the latest-version catalog. Best-effort: failures are swallowed,
     * this must never block or fail the real heartbeat.
     */
    /**
     * Guarantees `m_argos/devices/{serial}/info` exists with at least `id_device` +
     * `updated_at` — the bare minimum the web console needs to list the device at all
     * (getDeviceStatus() in argos-devices.ts treats a missing/stale updated_at as
     * "unregistered"/offline). A device that's registered in Supabase but has never
     * successfully sent a heartbeat (heartbeat has been flaky in production) had no Firebase
     * node whatsoever and was invisible in the web console despite having a valid token —
     * this call doesn't depend on the heartbeat pipeline succeeding at all, just on the
     * device having a serial, so it's safe to call on every sync cycle regardless of
     * heartbeat/network state elsewhere.
     */
    fun ensureFirebasePresence(serial: String) {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        val cleanSerial = serial.trim()
        if (dbUrl.isBlank() || cleanSerial.isBlank()) return
        val base = "$dbUrl/m_argos/devices/$cleanSerial/info"
        putJsonField("$base/id_device.json", "\"$cleanSerial\"")
        putJsonField("$base/updated_at.json", System.currentTimeMillis().toString())
    }

    /**
     * Publishes the device's launchable-app inventory to `m_argos/devices/{serial}/installed_apps`
     * — argos-devices.ts's device mapping already reads this path (`pick(val.installed_apps,
     * info.installed_apps)`), it just never had anything writing to it, so the web console's
     * "Apps" tab (open/uninstall buttons) always showed an empty list. Called once per
     * heartbeat cycle (same cadence as publishVersionInfoToFirebase) — not on every sync
     * cycle, since enumerating installed packages is heavier than the version/presence pings.
     */
    fun publishInstalledAppsToFirebase(serial: String, apps: org.json.JSONArray) {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        val cleanSerial = serial.trim()
        if (dbUrl.isBlank() || cleanSerial.isBlank()) return
        putJsonField("$dbUrl/m_argos/devices/$cleanSerial/installed_apps.json", apps.toString())
    }

    /**
     * Publishes manufacturer/model/device_owner/android_version + battery/ram/disk/wifi/ip/gps
     * to `m_argos/devices/{serial}/info` and `/telemetry` — argos-devices.ts already reads all
     * of these (pick(info.manufacturer,...), pick(telemetry.battery_level,...), etc.) but until
     * now nothing wrote them automatically: manufacturer/model only ever got sent by a manual
     * "send report" button in the launcher UI, and GPS only after an OTA install completed, so
     * a freshly-registered device's panel card showed every one of these fields blank.
     *
     * One PUT per FIELD, not one PUT per node — a node-level PUT replaces the entire node,
     * which silently wiped `info/id_device` and `info/updated_at` (written separately and far
     * more often by ensureFirebasePresence()'s 60s poll cycle) every single heartbeat. That
     * was the actual cause of devices going "offline" in the panel despite heartbeats running
     * fine: the panel's online check reads info.updated_at, and this call kept erasing it.
     * Same risk for telemetry/{latitude,longitude,...} written by OtaManager. Costs more
     * requests than a single node PUT, but correctness beats the saving here — each field
     * write is still bounded by the same heartbeat throttle as everything else in
     * HeartbeatService, no new periodic timer.
     */
    fun publishTelemetryToFirebase(serial: String, info: JSONObject, telemetry: JSONObject) {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        val cleanSerial = serial.trim()
        if (dbUrl.isBlank() || cleanSerial.isBlank()) return
        val infoBase = "$dbUrl/m_argos/devices/$cleanSerial/info"
        val telemetryBase = "$dbUrl/m_argos/devices/$cleanSerial/telemetry"
        info.keys().forEach { key ->
            putJsonField("$infoBase/$key.json", jsonScalarBody(info.get(key)))
        }
        telemetry.keys().forEach { key ->
            putJsonField("$telemetryBase/$key.json", jsonScalarBody(telemetry.get(key)))
        }
    }

    private fun jsonScalarBody(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Boolean, is Number -> value.toString()
        else -> "\"${value.toString().replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    fun publishVersionInfoToFirebase(serial: String, agentVersion: String, agentVersionCode: Long, mplayerVersion: String) {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        val cleanSerial = serial.trim()
        android.util.Log.i("AgentControlApi", "publishVersionInfo dbUrl=$dbUrl serial=$cleanSerial")
        if (dbUrl.isBlank() || cleanSerial.isBlank()) return
        val base = "$dbUrl/m_argos/devices/$cleanSerial/info"
        // PUT per child path instead of one PATCH to the parent — java.net.HttpURLConnection
        // rejects "PATCH" outright (ProtocolException: not in its method whitelist), while PUT
        // to a child .json path is RTDB's documented way to write a single field without
        // touching its siblings.
        putJsonField("$base/agent_version.json", "\"${agentVersion.replace("\"", "")}\"")
        putJsonField("$base/agent_version_code.json", agentVersionCode.toString())
        putJsonField("$base/mplayer_version.json", "\"${mplayerVersion.replace("\"", "")}\"")
    }

    /**
     * Publishes the packages currently pinned in Lock Task (kiosk) to
     * `m_argos/devices/{serial}/kiosk_packages` so the web console can compare the
     * configured whitelist against what is actually applied on the device.
     * Called once per heartbeat cycle alongside publishInstalledAppsToFirebase.
     */
    fun publishKioskPackagesToFirebase(serial: String, packages: Array<String>) {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        val cleanSerial = serial.trim()
        if (dbUrl.isBlank() || cleanSerial.isBlank()) return
        val arr = org.json.JSONArray().also { a -> packages.forEach { a.put(it) } }
        putJsonField("$dbUrl/m_argos/devices/$cleanSerial/kiosk_packages.json", arr.toString())
    }

    /**
     * One-shot GET of `argos_ota/latest_versions` — the same catalog node
     * argus-device-hub/src/lib/ota.ts already maintains (one entry per app, keyed by app id,
     * each with `package_name`/`apk_url`/`version_name`/`checksum`). Used by
     * ConfigurationEngine.reconcileWhitelistApps() to resolve a download URL for any
     * whitelisted package missing from the device — a single read, not one write per app
     * (unlike the old approach of the Web sending one `install_apk` command per missing app).
     */
    fun fetchOtaLatestVersions(): JSONObject? {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        if (dbUrl.isBlank()) return null
        return runCatching {
            val conn = (URL("$dbUrl/argos_ota/latest_versions.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (body.isBlank() || body == "null") null else JSONObject(body)
        }.getOrNull()
    }

    private fun putJsonField(urlValue: String, rawJsonBody: String) {
        runCatching {
            val conn = (URL(urlValue).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                doOutput = true
                connectTimeout = 6_000
                readTimeout = 8_000
                setRequestProperty("Content-Type", "application/json")
            }
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(rawJsonBody) }
            val code = conn.responseCode
            android.util.Log.i("AgentControlApi", "putJsonField url=$urlValue code=$code")
            conn.disconnect()
        }.onFailure { t -> android.util.Log.w("AgentControlApi", "putJsonField_failed url=$urlValue err=${t.message}") }
    }

    fun sendHeartbeat(deviceId: String, payload: AgentHeartbeatPayload): Boolean {
        // Tried first: the argos.mupa.app TanStack route (/public/agent/heartbeat) has been
        // returning 500 in production regardless of payload — same failure signature as the
        // register-device bug fixed earlier, just on a different route that never got the
        // same fix. Bypasses straight to the Supabase Functions Gateway instead, same
        // approach as ensureRegistered()/registerDevice().
        if (postHeartbeatToEdgeFunction(deviceId, payload)) return true

        val base = baseApiUrl()
        if (base.isBlank()) return false
        val paths = listOf(
            "/public/agent/heartbeat",
            "/functions/v1/heartbeat",
            "/device/heartbeat",
        )
        for (path in paths) {
            val ok = postHeartbeat("$base$path", deviceId, payload)
            if (ok != null) return ok
        }
        return false
    }

    private fun postHeartbeatToEdgeFunction(deviceId: String, payload: AgentHeartbeatPayload): Boolean {
        val token = settings.getArgosDeviceTokenCached().trim()
        if (token.isBlank()) return false
        return runCatching {
            val conn = (URL(HEARTBEAT_FUNCTION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                doInput = true
                connectTimeout = 8_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", "Bearer $SUPABASE_ANON_KEY")
                setRequestProperty("X-Device-Token", token)
            }
            val body = JSONObject()
                .put("device_id", deviceId.trim())
                .put("battery", payload.battery)
                .put("wifi", if (payload.wifi) "connected" else "disconnected")
                .put("memory", payload.ram)
                .put("storage", payload.storage)
                .put("ip", payload.ipAddress)
                .put("version_agent", payload.agentVersion)
                .put("version_agent_code", payload.agentVersionCode)
                .put("version_mplayer", payload.mplayerVersion)
                .put("version_remote", payload.remoteVersion)
                .toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        }.getOrDefault(false)
    }

    fun sendLogs(deviceId: String, logs: List<JSONObject>): Boolean {
        val base = baseApiUrl()
        if (base.isBlank() || logs.isEmpty()) return false
        // /public/agent/logs is the real, deployed route (logs.ts).
        val paths = listOf(
            "/public/agent/logs",
            "/functions/v1/logs",
        )
        for (path in paths) {
            val ok = postLogs("$base$path", deviceId, logs)
            if (ok != null) return ok
        }
        return false
    }

    private fun baseApiUrl(): String {
        val configured = settings.getArgosApiUrlCached().trim()
        if (configured.isBlank()) return ""
        val base = configured.trimEnd('/')
        return if (base.endsWith("/api", ignoreCase = true)) base else "$base/api"
    }

    private fun requestConfiguration(urlValue: String): ConfigurationRequestResult {
        val url = URL(urlValue)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            settings.getArgosDeviceTokenCached().trim().takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return runCatching {
            val code = conn.responseCode
            if (code == 404) return@runCatching ConfigurationRequestResult(false, null)
            if (code == 204) return@runCatching ConfigurationRequestResult(true, null)
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299 || text.isBlank()) return@runCatching ConfigurationRequestResult(true, null)
            val json = JSONObject(text)
            val payload = json.optJSONObject("configuration")
                ?: json.optJSONObject("config")
                ?: json
            val hash = json.optString("configuration_hash", payload.optString("configuration_hash", payload.optString("hash", ""))).trim()
            ConfigurationRequestResult(
                true,
                ConfigurationResponse(
                    configurationHash = hash,
                    payloadJson = payload.toString(),
                    updatedAt = json.optLong("updated_at", payload.optLong("updated_at", System.currentTimeMillis())),
                ),
            )
        }.getOrDefault(ConfigurationRequestResult(false, null)).also { conn.disconnect() }
    }

    private fun postHeartbeat(urlValue: String, deviceId: String, payload: AgentHeartbeatPayload): Boolean? {
        val url = URL(urlValue)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 15_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            settings.getArgosDeviceTokenCached().trim().takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return runCatching {
            val body = JSONObject()
                .put("device_id", deviceId.trim())
                .put("battery", payload.battery)
                .put("wifi", payload.wifi)
                .put("ram", payload.ram)
                .put("storage", payload.storage)
                .put("memory", payload.ram)
                .put("ip_address", payload.ipAddress)
                .put("ip", payload.ipAddress)
                .put("foreground_package", payload.foregroundPackage)
                .put("uptime", payload.uptime)
                .put("agent_version", payload.agentVersion)
                .put("version_agent", payload.agentVersion)
                .put("agent_version_code", payload.agentVersionCode)
                .put("version_agent_code", payload.agentVersionCode)
                .put("mplayer_version", payload.mplayerVersion)
                .put("version_mplayer", payload.mplayerVersion)
                .put("remote_version", payload.remoteVersion)
                .put("version_remote", payload.remoteVersion)
                .toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val code = conn.responseCode
            when {
                code == 404 -> null
                else -> code in 200..299
            }
        }.getOrDefault(false).also { conn.disconnect() }
    }

    private fun postLogs(urlValue: String, deviceId: String, logs: List<JSONObject>): Boolean? {
        val url = URL(urlValue)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 15_000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            settings.getArgosDeviceTokenCached().trim().takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return runCatching {
            val body = JSONObject()
                .put("device_id", deviceId.trim())
                .put("logs", org.json.JSONArray(logs))
                .toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val code = conn.responseCode
            when {
                code == 404 -> null
                else -> code in 200..299
            }
        }.getOrDefault(false).also { conn.disconnect() }
    }

    private data class ConfigurationRequestResult(
        val resolved: Boolean,
        val response: ConfigurationResponse?,
    )

    companion object {
        // Same Supabase project the web console (argus-device-hub) talks to.
        // The anon key is safe to embed client-side — it's the public key Supabase
        // expects from any caller; access is governed by RLS / the function's own logic,
        // not by keeping this secret.
        private const val SUPABASE_ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InB1YWJhdnJyb3NkdWVmeXlvc21uIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODE4NDUzODEsImV4cCI6MjA5NzQyMTM4MX0.G3IXASKAXJLbikKXalnuSy3tF8SGnMrf_m1bqzETWPg"
        private const val REGISTER_DEVICE_FUNCTION_URL =
            "https://puabavrrosduefyyosmn.functions.supabase.co/register-device"
        private const val HEARTBEAT_FUNCTION_URL =
            "https://puabavrrosduefyyosmn.functions.supabase.co/heartbeat"
    }
}
