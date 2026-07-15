package com.mupa.agent.argos.services

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.mupa.agent.argos.core.CommandEngine
import com.mupa.agent.argos.core.ConfigurationEngine
import com.mupa.agent.argos.database.ArgosDatabaseProvider
import com.mupa.agent.argos.database.LastHeartbeatEntity
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.models.AgentHeartbeatPayload
import com.mupa.agent.argos.models.AgentLogCategory
import com.mupa.agent.argos.modules.AutoHealingManager
import com.mupa.agent.argos.modules.HealthMonitor
import com.mupa.agent.argos.network.AgentControlApi
import com.mupa.agent.argos.telemetry.TelemetryManager
import com.mupa.agent.argos.ui.SyncStateHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PollingService(context: Context) {
    private val commandEngine = CommandEngine(context.applicationContext)
    private val configurationEngine = ConfigurationEngine(context.applicationContext)

    suspend fun tick(): Int = withContext(Dispatchers.IO) {
        SyncStateHolder.begin()
        try {
            configurationEngine.syncAndApply()
            commandEngine.runCycle()
        } finally {
            SyncStateHolder.end()
        }
    }
}

class HeartbeatService(context: Context) {
    private val appContext = context.applicationContext
    private val healthMonitor = HealthMonitor(appContext)
    private val settings = SettingsManager(appContext)
    private val network = AgentControlApi(appContext, settings)
    private val identity = DeviceIdentityManager(appContext)
    private val db = ArgosDatabaseProvider.get(appContext)

    suspend fun send(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        SyncStateHolder.begin()
        try {
        val now = System.currentTimeMillis()
        val previous = db.lastHeartbeatDao().find()
        android.util.Log.i("HeartbeatService", "send_start force=$force previous=${previous != null} sentAt=${previous?.sentAt} sinceMs=${previous?.let { now - it.sentAt }}")
        if (!force && previous != null && now - previous.sentAt < HEARTBEAT_INTERVAL_MS) {
            android.util.Log.i("HeartbeatService", "send_skip_throttled")
            return@withContext true
        }

        val snapshot = healthMonitor.capture()
        val payload = AgentHeartbeatPayload(
            battery = snapshot.batteryPercent,
            wifi = snapshot.wifiConnected,
            ram = snapshot.ramPercent,
            storage = snapshot.storagePercent,
            ipAddress = snapshot.ipAddress,
            foregroundPackage = snapshot.foregroundPackage,
            uptime = snapshot.uptimeSeconds,
            agentVersion = snapshot.agentVersion,
            agentVersionCode = snapshot.agentVersionCode,
            mplayerVersion = snapshot.mplayerVersion.orEmpty(),
            remoteVersion = snapshot.remoteVersion.orEmpty(),
        )
        val deviceId = identity.getPersistentId().trim()
        if (deviceId.isBlank()) return@withContext false

        // Deliberately runs regardless of the Supabase heartbeat outcome/dedupe below — the
        // Supabase heartbeat pipeline has been flaky in production (missing Edge Function
        // deploy at times), and these are independent, best-effort Firebase writes the web
        // console's UI depends on directly (version display, "Apps" tab open/uninstall
        // buttons). They only need to respect the outer 5-minute throttle above, not the
        // heartbeat-fingerprint "anything changed" check, since installed apps can change
        // without any of the heartbeat fields changing.
        val serial = identity.getDeviceFingerprint().serial?.trim().orEmpty().ifBlank { deviceId }

        // Must run BEFORE publishVersionInfoToFirebase below: publishTelemetryToFirebase does
        // one full-node PUT to .../info (cheaper than one PUT per field), so if it ran after
        // the per-field agent_version/mplayer_version PUTs it would wipe them. Running first
        // means those per-field PUTs land on top and nothing is lost either way.
        runCatching {
            val isDeviceOwner = DeviceOwnerPolicyManager(appContext).isDeviceOwner(appContext.packageName)
            val wifiInfo = runCatching {
                (appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo
            }.getOrNull()
            val telemetryManager = TelemetryManager(appContext)
            val location = runCatching { telemetryManager.readBestLastKnownLocation() }.getOrNull()
            val ipGeo = if (location == null) runCatching { telemetryManager.fetchIpGeoLocation() }.getOrNull() else null

            val apelido = settings.getDeviceApelidoInternoCached().trim()
            val info = JSONObject()
                .put("manufacturer", Build.MANUFACTURER.orEmpty())
                .put("model", Build.MODEL.orEmpty())
                .put("android_version", Build.VERSION.RELEASE.orEmpty())
                .put("device_owner", isDeviceOwner)
                .also { if (apelido.isNotBlank()) it.put("apelido_interno", apelido) }

            val kioskActive = settings.getKioskModeCached() || settings.getMdmLockedCached()
            val telemetry = JSONObject()
                .put("ip", snapshot.ipAddress ?: JSONObject.NULL)
                .put("battery_level", snapshot.batteryPercent)
                .put("ram_usage", snapshot.ramPercent)
                .put("disk_usage", snapshot.storagePercent)
                .put("wifi_connected", snapshot.wifiConnected)
                .put("wifi_signal", wifiInfo?.rssi ?: JSONObject.NULL)
                .put("wifi_ssid", wifiInfo?.ssid?.trim('"') ?: JSONObject.NULL)
                .put("gps_available", location != null)
                .put("kiosk_active", kioskActive)
            if (location != null) {
                telemetry
                    .put("latitude", location.latitude)
                    .put("longitude", location.longitude)
                    .put("accuracy_m", location.accuracy.toDouble())
                    .put("location_source", "gps")
            } else if (ipGeo != null) {
                telemetry
                    .put("latitude", ipGeo.latitude)
                    .put("longitude", ipGeo.longitude)
                    .put("location_source", "ip")
                    .put("ip_geo_city", ipGeo.city)
                    .put("ip_geo_region", ipGeo.region)
                    .put("ip_geo_country", ipGeo.country)
                    .put("ip_geo_isp", ipGeo.isp)
            }

            network.publishTelemetryToFirebase(serial, info, telemetry)
        }

        runCatching {
            network.publishVersionInfoToFirebase(
                serial = serial,
                agentVersion = payload.agentVersion,
                agentVersionCode = payload.agentVersionCode,
                mplayerVersion = payload.mplayerVersion,
            )
        }
        runCatching {
            val apps = com.mupa.agent.argos.apps.ApplicationManager(appContext).listLaunchableApps(includeSystem = true)
            network.publishInstalledAppsToFirebase(serial, apps)
        }
        runCatching {
            val dpm = appContext.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            val admin = android.content.ComponentName(appContext, com.mupa.agent.argos.mdm.ArgosDeviceAdminReceiver::class.java)
            val locked = if (android.os.Build.VERSION.SDK_INT >= 21) dpm?.getLockTaskPackages(admin) ?: emptyArray() else emptyArray()
            network.publishKioskPackagesToFirebase(serial, locked)
        }

        val payloadJson = payloadToJson(payload)
        if (previous != null && !hasMeaningfulHeartbeatChange(previous.payloadJson, payloadJson)) {
            android.util.Log.i("HeartbeatService", "send_skip_no_change")
            return@withContext true
        }

        val sent = network.sendHeartbeat(deviceId, payload)
        android.util.Log.i("HeartbeatService", "send_result sent=$sent agentVersion=${payload.agentVersion} code=${payload.agentVersionCode}")
        if (sent) {
            db.lastHeartbeatDao().upsert(
                LastHeartbeatEntity(
                    fingerprint = buildHeartbeatFingerprint(payloadJson),
                    payloadJson = payloadJson,
                    sentAt = now,
                ),
            )
        }
        sent
        } finally {
            SyncStateHolder.end()
        }
    }

    private fun payloadToJson(payload: AgentHeartbeatPayload): String {
        return JSONObject()
            .put("battery", payload.battery)
            .put("wifi", payload.wifi)
            .put("ram", payload.ram)
            .put("storage", payload.storage)
            .put("ip_address", payload.ipAddress)
            .put("foreground_package", payload.foregroundPackage)
            .put("uptime", payload.uptime)
            .put("agent_version", payload.agentVersion)
            .put("agent_version_code", payload.agentVersionCode)
            .put("mplayer_version", payload.mplayerVersion)
            .put("remote_version", payload.remoteVersion)
            .toString()
    }

    private fun buildHeartbeatFingerprint(payloadJson: String): String {
        val json = JSONObject(payloadJson)
        val batteryBucket = (json.optInt("battery", 0) / 5) * 5
        return listOf(
            batteryBucket.toString(),
            json.optBoolean("wifi", false).toString(),
            json.optString("ip_address", "").trim(),
            json.optString("foreground_package", "").trim(),
            json.optString("agent_version", "").trim(),
            json.optString("mplayer_version", "").trim(),
            json.optString("remote_version", "").trim(),
        ).joinToString("|")
    }

    private fun hasMeaningfulHeartbeatChange(previousJson: String, currentJson: String): Boolean {
        val previous = JSONObject(previousJson)
        val current = JSONObject(currentJson)
        if (kotlin.math.abs(previous.optInt("battery", 0) - current.optInt("battery", 0)) >= 5) return true
        if (previous.optBoolean("wifi", false) != current.optBoolean("wifi", false)) return true
        if (previous.optString("ip_address", "").trim() != current.optString("ip_address", "").trim()) return true
        if (previous.optString("foreground_package", "").trim() != current.optString("foreground_package", "").trim()) return true
        if (previous.optString("agent_version", "").trim() != current.optString("agent_version", "").trim()) return true
        if (previous.optLong("agent_version_code", 0L) != current.optLong("agent_version_code", 0L)) return true
        if (previous.optString("mplayer_version", "").trim() != current.optString("mplayer_version", "").trim()) return true
        if (previous.optString("remote_version", "").trim() != current.optString("remote_version", "").trim()) return true
        return false
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L
    }
}

class LogFlushService(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val network = AgentControlApi(appContext, settings)
    private val identity = DeviceIdentityManager(appContext)
    private val db = ArgosDatabaseProvider.get(appContext)

    suspend fun flushIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val deviceId = identity.getPersistentId().trim()
        if (deviceId.isBlank()) return@withContext false

        val pendingCount = db.logDao().count()
        if (pendingCount <= 0) return@withContext true

        val now = System.currentTimeMillis()
        val previous = db.lastHeartbeatDao().find("logs")
        val dueByTime = previous == null || now - previous.sentAt >= LOG_FLUSH_INTERVAL_MS
        val dueByVolume = pendingCount >= LOG_BATCH_SIZE
        if (!force && !dueByTime && !dueByVolume) return@withContext true

        val batch = db.logDao().listOldest(LOG_BATCH_SIZE)
        if (batch.isEmpty()) return@withContext true

        val payload = batch.map { entry ->
            JSONObject()
                .put("level", entry.level)
                .put("message", entry.message)
                .put("context", JSONObject()
                    .put("category", entry.category)
                    .put("payload", parseJsonOrNull(entry.payloadJson))
                    .put("created_at", entry.createdAt))
        }

        val sent = network.sendLogs(deviceId, payload)
        if (sent) {
            db.logDao().deleteIds(batch.map { it.id })
            db.lastHeartbeatDao().upsert(
                LastHeartbeatEntity(
                    slot = "logs",
                    fingerprint = AgentLogCategory.INFO.name,
                    payloadJson = JSONArray(payload).toString(),
                    sentAt = now,
                ),
            )
        }
        sent
    }

    private fun parseJsonOrNull(raw: String): JSONObject? {
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    companion object {
        private const val LOG_FLUSH_INTERVAL_MS = 10 * 60_000L
        private const val LOG_BATCH_SIZE = 100
    }
}

class WatchdogService(context: Context) {
    private val autoHealing = AutoHealingManager(context.applicationContext)

    fun tick() {
        autoHealing.ensureBaseline()
    }
}
