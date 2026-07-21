package com.mupa.agent.argos.core

import android.content.Context
import android.os.Build
import com.mupa.agent.argos.BuildConfig
import com.mupa.agent.argos.apps.ApplicationManager
import com.mupa.agent.argos.database.ArgosDatabaseProvider
import com.mupa.agent.argos.database.ConfigurationEntity
import com.mupa.agent.argos.database.LogEntity
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.models.AgentLogCategory
import com.mupa.agent.argos.network.AgentControlApi
import com.mupa.agent.argos.launcher.WhitelistManager
import com.mupa.agent.argos.provisioning.ApkInstallManager
import com.mupa.agent.argos.provisioning.ApkInstallRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ConfigurationEngine(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val whitelist = WhitelistManager(appContext)
    private val network = AgentControlApi(appContext, settings)
    private val db = ArgosDatabaseProvider.get(appContext)
    private val identity = DeviceIdentityManager(appContext)
    private val dpm = DeviceOwnerPolicyManager(appContext)
    private val apkInstaller by lazy { ApkInstallManager(appContext) }

    suspend fun syncAndApply(scope: String = "device"): Boolean = withContext(Dispatchers.IO) {
        val deviceId = identity.getPersistentId().trim()
        if (deviceId.isBlank()) return@withContext false

        val current = db.configurationDao().findByScope(scope)
        val remote = network.fetchConfiguration(deviceId, current?.configurationHash)

        // Fallback: read device_config directly from Firebase RTDB when the API is unavailable.
        // This lets ConfigurationEngine apply whitelist + reconcile installs even on devices
        // that don't have argos_api_url configured (e.g. TV boxes enrolled via group linking).
        if (remote == null) {
            return@withContext syncFromFirebase(firebaseDeviceKeys())
        }

        if (remote.configurationHash.isNotBlank() && remote.configurationHash == current?.configurationHash) {
            return@withContext false
        }

        val payload = JSONObject(remote.payloadJson)
        applyKnownSettings(payload)
        maybeSelfUpdate(payload)
        db.configurationDao().upsert(
            ConfigurationEntity(
                scope = scope,
                configurationHash = remote.configurationHash,
                payloadJson = remote.payloadJson,
                updatedAt = remote.updatedAt,
            ),
        )
        db.logDao().insert(
            LogEntity(
                category = AgentLogCategory.INFO.name,
                level = AgentLogCategory.INFO.name,
                message = "configuration_applied:$scope",
                payloadJson = remote.payloadJson,
                createdAt = System.currentTimeMillis(),
            ),
        )
        true
    }

    /**
     * The Firebase device node is keyed by the BARE hardware serial (e.g. "DER4BT125115002178")
     * — that's what the web console writes device_config under, and what the agent's own
     * heartbeat/presence/telemetry writes are keyed by (AgentControlApi uses fingerprint.serial).
     * But getPersistentId() returns the PREFIXED id ("SN:...", "IMEI:...", "GEN:..."), so reading
     * device_config by getPersistentId() misses the node entirely and the whitelist never applies.
     * Return the bare serial first (the real node key), then the prefixed id as a fallback for
     * older devices that were enrolled under the prefixed key.
     */
    private fun firebaseDeviceKeys(): List<String> {
        val keys = LinkedHashSet<String>()
        runCatching { identity.getDeviceFingerprint().serial?.trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        runCatching { identity.generateIfMissing().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { prefixed ->
                keys.add(prefixed)
                // Also try the bare form of the prefixed id (strip SN:/IMEI:/MFR:/GEN:/N:).
                val bare = prefixed.substringAfter(':', prefixed).trim()
                if (bare.isNotBlank()) keys.add(bare)
            }
        return keys.toList()
    }

    private fun syncFromFirebase(deviceKeys: List<String>): Boolean {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        if (dbUrl.isBlank()) return false
        for (deviceId in deviceKeys) {
            val applied = runCatching {
                val encodedId = java.net.URLEncoder.encode(deviceId, "UTF-8")
                val conn = (java.net.URL("$dbUrl/m_argos/devices/$encodedId/device_config.json")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                }
                if (conn.responseCode !in 200..299) return@runCatching false
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (body.isBlank() || body == "null") return@runCatching false
                val deviceConfig = JSONObject(body)
                // Normalize: map Firebase field names to the same schema applyKnownSettings expects
                if (!deviceConfig.has("whitelist") && deviceConfig.has("allowed_packages")) {
                    deviceConfig.put("whitelist", deviceConfig.optJSONArray("allowed_packages"))
                }
                android.util.Log.i("ConfigurationEngine", "firebase_fallback_sync deviceId=$deviceId keys=${deviceConfig.keys().asSequence().toList()}")
                applyKnownSettings(deviceConfig)
                true
            }.getOrElse { e ->
                android.util.Log.w("ConfigurationEngine", "firebase_fallback_sync_failed key=$deviceId: ${e.message}")
                false
            }
            if (applied) return true
        }
        return false
    }

    private fun applyKnownSettings(payload: JSONObject) {
        val deviceConfig = payload.optJSONObject("device_config") ?: payload
        var whitelistChanged = false
        var whitelistForReconcile: Set<String>? = null
        runBlocking {
            // Apply whitelist whenever the key is present in the payload — including when it's
            // an empty array, which means "no apps allowed" (kiosk with zero packages). The old
            // takeIf { it.isNotEmpty() } guard silently discarded empty whitelists and left the
            // previous package set active, causing apps to persist after the operator cleared them.
            if (deviceConfig.has("whitelist")) {
                val wlSet = deviceConfig.optJSONArray("whitelist")?.toStringSet() ?: emptySet()
                // Must update WhitelistManager (SQLite-backed) not just the flat settings cache —
                // WhitelistManager.resolveAllowedApps() reads SQLite first and ignores the cache
                // whenever SQLite has entries, so calling settings.setAllowedPackages() here was
                // silently discarded. WhitelistManager.setAllowedPackages() writes to SQLite and
                // then calls persistCache() which syncs settings too.
                whitelist.setAllowedPackages(wlSet + appContext.packageName)
                whitelistChanged = true
                whitelistForReconcile = wlSet
            }
            run {
                // Mark specific packages as hidden in the launcher (still in lock task, but not
                // shown to the end user). Requires a separate field so the operator can keep, e.g.,
                // AnyDesk or the Argos agent in kiosk without exposing them on-screen.
                val hiddenSet = deviceConfig.optJSONArray("kiosk_hidden_packages")?.toStringSet()
                    ?: emptySet()
                for (pkg in hiddenSet) {
                    runCatching { whitelist.update(pkg) { it.copy(hidden = true) } }
                }
                // Un-hide packages no longer in the hidden list (in case operator removed them).
                // Skip protected apps — those are managed by ensureFixedApps (AnyDesk, Settings)
                // and must stay hidden regardless of operator kiosk_hidden_packages config.
                whitelist.getAllowedApps()
                    .filter { it.hidden && !it.protected && !hiddenSet.contains(it.packageName) && it.packageName != appContext.packageName }
                    .forEach { app -> runCatching { whitelist.update(app.packageName) { it.copy(hidden = false) } } }
            }

            val autostart = deviceConfig.optJSONArray("app_autostart")
                ?.optString(0)
                ?.trim()
                .orEmpty()
            if (autostart.isNotBlank()) {
                settings.setAutostartPackage(autostart)
            }

            if (deviceConfig.has("kiosk_enabled")) {
                settings.setKioskMode(deviceConfig.optBoolean("kiosk_enabled", false))
            }
            if (deviceConfig.has("autostart_delay_ms")) {
                settings.setAutostartDelayMs(deviceConfig.optLong("autostart_delay_ms", 0L))
            }
            if (deviceConfig.has("device_locked")) {
                settings.setMdmLocked(deviceConfig.optBoolean("device_locked", false))
            }
            if (deviceConfig.has("argos_api_url")) {
                settings.setArgosApiUrl(deviceConfig.optString("argos_api_url", ""))
            }

            if (deviceConfig.has("operating_hours_enabled")) {
                val ohEnabled = deviceConfig.optBoolean("operating_hours_enabled", false)
                val ohStart = deviceConfig.optString("operating_hours_start", "")
                val ohEnd = deviceConfig.optString("operating_hours_end", "")
                val ohDays = deviceConfig.optString("operating_hours_days", "")
                settings.setOperatingHours(ohEnabled, ohStart, ohEnd, ohDays)
            }
        }

        applyWifiFromConfig(deviceConfig)
        applyDataWedgeFromConfig(deviceConfig)

        // Without this, an updated whitelist is only persisted locally — LockTask keeps
        // enforcing whatever package set was active when kiosk mode was last (re)applied.
        // Mirrors the same re-apply step CommandDispatcher's "set_whitelist" branch does.
        if (whitelistChanged && (settings.getMdmLockedCached() || settings.getKioskModeCached())) {
            runCatching { dpm.applyLocked(appContext.packageName, settings.getAllowedPackagesCached()) }
        }

        val autoInstall = deviceConfig.optBoolean("auto_install", false)
        whitelistForReconcile?.let { runCatching { reconcileWhitelistApps(it, autoInstall) } }

        // "Aplicativos" tab (Groups refactor) — package_name -> "not_install"|"install"|
        // "required". Unlike the whitelist above (gated by the global auto_install switch),
        // "required" packages always get installed: that's the whole point of marking one
        // required versus merely "install" (metadata-only in this phase, no automatic action).
        val requiredPackages = deviceConfig.optJSONObject("app_policies")?.let { policies ->
            policies.keys().asSequence().filter { policies.optString(it) == "required" }.toSet()
        }.orEmpty()
        if (requiredPackages.isNotEmpty()) {
            runCatching { reconcileWhitelistApps(requiredPackages, autoInstall = true) }
        }
    }

    /**
     * Installs whatever's missing from the whitelist locally, instead of the Web sending one
     * `install_apk` Firebase command per missing app (the old approach in
     * argus-device-hub/src/lib/group-setup.ts's applyGroupSetup) — those commands all land on
     * the same `m_argos/devices/{id}/commands` node, which holds a single object, not a queue,
     * so sending N commands in a row only ever delivers the last one (same bug fixed earlier
     * for MPlayer deploys). Resolving and installing right here, from a single catalog read,
     * sidesteps that race entirely and also runs on every config sync (not just once when an
     * operator applies a group setup), so a device that's missing a whitelist app after, say,
     * a factory reset heals itself on its own next sync instead of staying broken until someone
     * notices and re-applies the group setup by hand.
     */
    private fun reconcileWhitelistApps(allowedPackages: Set<String>, autoInstall: Boolean) {
        if (!autoInstall) return
        val apps = ApplicationManager(appContext)
        val missing = allowedPackages.filterNot { it == appContext.packageName || apps.isInstalled(it) }
        if (missing.isEmpty()) return

        val catalog = network.fetchOtaLatestVersions() ?: return
        for (pkg in missing) {
            val entry = catalog.keys().asSequence()
                .mapNotNull { catalog.optJSONObject(it) }
                .firstOrNull { it.optString("package_name").trim() == pkg }
                ?: continue
            val url = entry.optString("apk_url").trim()
            if (url.isBlank()) continue
            runCatching {
                apkInstaller.install(
                    ApkInstallRequest(
                        url = url,
                        expectedPackage = pkg,
                        expectedVersion = entry.optString("version_name").trim().ifBlank { null },
                        force = false,
                        silent = true,
                        autoOpen = false,
                        sha256 = entry.optString("checksum").trim().ifBlank { null },
                    ),
                )
            }
        }
    }

    /**
     * Automatic agent self-update: if the synced device_config carries a newer
     * `agent_latest_version_code` than this build (BuildConfig.VERSION_CODE), silently
     * install it via the same ApkInstallManager flow already used by the manual
     * "atualizar_agent" command — no operator action required.
     *
     * Source of `agent_latest_version_code`/`agent_update_url`/etc.: the
     * `public.agent_releases` table, merged into the configuration/sync response by
     * the backend (see src/routes/api/public/agent/configuration.sync.ts).
     */
    private fun maybeSelfUpdate(payload: JSONObject) {
        val deviceConfig = payload.optJSONObject("device_config") ?: payload
        val latestVersionCode = deviceConfig.optLong("agent_latest_version_code", -1L)
        val updateUrl = deviceConfig.optString("agent_update_url", "").trim()
        if (latestVersionCode <= 0L || updateUrl.isBlank()) return
        if (latestVersionCode <= BuildConfig.VERSION_CODE) return
        // Silent install via PackageInstaller requires Device Owner; without it we'd have
        // to show an install confirmation dialog, which defeats "automatic".
        if (!dpm.isDeviceOwner(appContext.packageName)) return

        val checksum = deviceConfig.optString("agent_update_checksum", "").trim().ifBlank { null }
        val versionName = deviceConfig.optString("agent_latest_version_name", "").trim().ifBlank { null }

        runCatching {
            apkInstaller.install(
                ApkInstallRequest(
                    url = updateUrl,
                    expectedPackage = appContext.packageName,
                    expectedVersion = versionName,
                    force = true,
                    silent = true,
                    autoOpen = false,
                    sha256 = checksum,
                ),
            )
        }
    }

    /**
     * Applies WiFi credentials coming from the ARGOS web policies (group default or
     * device-specific override). The web writes either a nested object
     * `default_wifi: { ssid, password }` or the flat `default_wifi_ssid`/`default_wifi_password`
     * fields — accept both. Connecting is delegated to WifiConfig, which only reconnects when
     * the credentials actually changed.
     */
    /**
     * Applies DataWedge configuration from the group setup. Only runs on Zebra devices
     * (where DataWedge is pre-installed). The web writes `datawedge_config` into device_config
     * with the same shape that DataWedgeManager.setConfig() expects.
     */
    private fun applyDataWedgeFromConfig(deviceConfig: JSONObject) {
        val dwConfig = deviceConfig.optJSONObject("datawedge_config") ?: return
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        if ("zebra" !in manufacturer && "symbol" !in manufacturer) return
        runCatching {
            com.mupa.agent.argos.datawedge.DataWedgeManager(appContext).setConfig(dwConfig)
            android.util.Log.i("ConfigurationEngine", "datawedge_config_applied profile=${dwConfig.optString("profile")}")
        }.onFailure { android.util.Log.w("ConfigurationEngine", "datawedge_config_failed: ${it.message}") }
    }

    private fun applyWifiFromConfig(deviceConfig: JSONObject) {
        val wifiObj = deviceConfig.optJSONObject("default_wifi")
        val ssid = (wifiObj?.optString("ssid") ?: deviceConfig.optString("default_wifi_ssid")).trim()
        val password = wifiObj?.optString("password") ?: deviceConfig.optString("default_wifi_password")
        if (ssid.isBlank()) return
        runCatching {
            com.mupa.agent.argos.wifi.WifiConfig(appContext).applyRemoteCredentials(ssid, password)
        }.onFailure { android.util.Log.w("ConfigurationEngine", "wifi_apply_failed: ${it.message}") }
    }

    private fun JSONArray.toStringSet(): Set<String> {
        val out = LinkedHashSet<String>(length())
        for (i in 0 until length()) {
            optString(i)?.trim()?.takeIf { it.isNotBlank() }?.let { out += it }
        }
        return out
    }
}
