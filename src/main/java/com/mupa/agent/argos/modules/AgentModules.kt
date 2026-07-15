package com.mupa.agent.argos.modules

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import com.mupa.agent.argos.BuildConfig
import com.mupa.agent.argos.accessibility.ArgosAccessibilityService
import com.mupa.agent.argos.apps.ApplicationManager
import com.mupa.agent.argos.database.AppInventoryEntity
import com.mupa.agent.argos.database.ArgosDatabaseProvider
import com.mupa.agent.argos.database.HealthSnapshotEntity
import com.mupa.agent.argos.database.LocationHistoryEntity
import com.mupa.agent.argos.launcher.KioskPolicyManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.models.AgentAppInventoryItem
import com.mupa.agent.argos.models.AgentHealthSnapshot
import com.mupa.agent.argos.models.AgentLocationPoint
import com.mupa.agent.argos.remote.RemoteHealthManager as LegacyRemoteHealthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KioskManager(private val context: Context) {
    private val kioskPolicyManager = KioskPolicyManager(context.applicationContext)
    private val settings = SettingsManager(context.applicationContext)

    suspend fun applyConfiguredState() {
        if (settings.getKioskModeCached()) {
            kioskPolicyManager.applyNow()
        }
    }
}

class AppManager(private val context: Context) {
    private val apps = ApplicationManager(context.applicationContext)
    private val db = ArgosDatabaseProvider.get(context.applicationContext)

    suspend fun collectInventory(): List<AgentAppInventoryItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .map { app ->
                val pkgInfo = runCatching { pm.getPackageInfo(app.packageName, 0) }.getOrNull()
                AgentAppInventoryItem(
                    appName = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName,
                    versionName = pkgInfo?.versionName.orEmpty(),
                    versionCode = pkgInfo?.longVersionCode ?: 0L,
                    lastUsedAt = null,
                )
            }
        db.appInventoryDao().clear()
        db.appInventoryDao().upsertAll(
            installed.map {
                AppInventoryEntity(
                    packageName = it.packageName,
                    appName = it.appName,
                    versionName = it.versionName,
                    versionCode = it.versionCode,
                    lastUsedAt = it.lastUsedAt,
                    updatedAt = System.currentTimeMillis(),
                )
            },
        )
        installed
    }

    fun open(packageName: String): Boolean = apps.open(packageName)
}

class OtaManager(private val context: Context) {
    fun triggerIfDue() {
        // OTA 3.0 orchestration starts here; legacy OTA continues through the existing realtime pipeline.
    }
}

class RemoteManager(private val context: Context) {
    private val legacy = LegacyRemoteHealthManager(context.applicationContext)
    private val apps = ApplicationManager(context.applicationContext)

    fun isInstalled(): Boolean = apps.isInstalled("com.mupa.argos.remote")

    fun ensureHealthy() {
        legacy.runIfDue()
    }
}

class LocationManager(private val context: Context) {
    private val db = ArgosDatabaseProvider.get(context.applicationContext)

    suspend fun record(point: AgentLocationPoint) {
        db.locationHistoryDao().insert(
            LocationHistoryEntity(
                latitude = point.latitude,
                longitude = point.longitude,
                accuracyMeters = point.accuracyMeters,
                capturedAt = point.capturedAt,
            ),
        )
    }
}

class GeofenceManager(private val context: Context) {
    fun evaluate(lat: Double, lng: Double): Boolean {
        return lat != 0.0 || lng != 0.0
    }
}

class HealthMonitor(private val context: Context) {
    private val db = ArgosDatabaseProvider.get(context.applicationContext)

    suspend fun capture(): AgentHealthSnapshot = withContext(Dispatchers.IO) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val ramPercent = ((1f - (memoryInfo.availMem.toFloat() / memoryInfo.totalMem.toFloat())) * 100f).toInt().coerceIn(0, 100)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val linkProperties = activeNetwork?.let { connectivityManager.getLinkProperties(it) }
        val ipAddress = linkProperties?.linkAddresses
            ?.firstOrNull { address -> address.address?.hostAddress?.contains(':') == false }
            ?.address
            ?.hostAddress
            ?.trim()
            ?.ifBlank { null }
        val foregroundPackage = ArgosAccessibilityService.getLastForegroundPackage(context.applicationContext)
            .trim()
            .ifBlank { null }
        val filesDir = context.filesDir
        val statFs = StatFs(filesDir.absolutePath)
        val totalBytes = statFs.totalBytes.coerceAtLeast(1L)
        val usedBytes = (totalBytes - statFs.availableBytes).coerceAtLeast(0L)
        val storagePercent = ((usedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)

        val snapshot = AgentHealthSnapshot(
            ramPercent = ramPercent,
            cpuPercent = 0,
            storagePercent = storagePercent,
            batteryPercent = battery,
            temperatureCelsius = null,
            wifiConnected = wifi,
            ipAddress = ipAddress,
            foregroundPackage = foregroundPackage,
            uptimeSeconds = SystemClock.elapsedRealtime() / 1000L,
            agentVersion = BuildConfig.VERSION_NAME,
            agentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            mplayerVersion = installedVersion("com.mupa.player.enterprise"),
            remoteVersion = installedVersion("com.mupa.argos.remote"),
        )
        db.healthSnapshotDao().insert(
            HealthSnapshotEntity(
                ramPercent = snapshot.ramPercent,
                cpuPercent = snapshot.cpuPercent,
                storagePercent = snapshot.storagePercent,
                batteryPercent = snapshot.batteryPercent,
                temperatureCelsius = snapshot.temperatureCelsius,
                wifiConnected = snapshot.wifiConnected,
                ipAddress = snapshot.ipAddress,
                foregroundPackage = snapshot.foregroundPackage,
                uptimeSeconds = snapshot.uptimeSeconds,
                agentVersion = snapshot.agentVersion,
                agentVersionCode = snapshot.agentVersionCode,
                mplayerVersion = snapshot.mplayerVersion,
                remoteVersion = snapshot.remoteVersion,
                capturedAt = System.currentTimeMillis(),
            ),
        )
        snapshot
    }

    private fun installedVersion(packageName: String): String? {
        return runCatching { context.packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
    }
}

class AutoHealingManager(private val context: Context) {
    private val dpm = DeviceOwnerPolicyManager(context.applicationContext)

    fun ensureBaseline() {
        if (dpm.isDeviceOwner(context.packageName)) {
            dpm.applyBaselineRestrictions(context.packageName)
        }
    }
}
