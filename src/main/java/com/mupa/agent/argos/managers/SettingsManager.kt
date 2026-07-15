package com.mupa.agent.argos.managers

import android.content.Context
import com.mupa.agent.argos.BuildConfig
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mupa.agent.argos.storage.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

data class AgentSettings(
    val argosApiUrl: String,
    val argosDeviceToken: String,
    val autostartPackage: String,
    val kioskMode: Boolean,
    val mdmLocked: Boolean,
    val allowedPackages: Set<String>,
    val mplayerDemoMode: Boolean,
)

class SettingsManager(private val context: Context) {
    private val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)

    data class AnyDeskPluginSpec(
        val packageName: String,
        val url: String,
    )

    data class GeofenceConfig(
        val enabled: Boolean,
        val lat: Double,
        val lng: Double,
        val radiusM: Double,
        val actions: Set<String>,
    )

    private object Keys {
        val argosApiUrl = stringPreferencesKey("argos_api_url")
        val argosDeviceToken = stringPreferencesKey("argos_device_token")
        val deviceApelidoInterno = stringPreferencesKey("device_apelido_interno")
        val autostartPackage = stringPreferencesKey("autostart_package")
        val kioskMode = booleanPreferencesKey("kiosk_mode")
        val mdmLocked = booleanPreferencesKey("mdm_locked")
        val allowedPackages = stringSetPreferencesKey("allowed_packages")
        val mplayerDemoMode = booleanPreferencesKey("mplayer_demo_mode")

        val appPoliciesJson = stringPreferencesKey("app_policies_json")
        val lastCommandSyncMs = longPreferencesKey("last_command_sync_ms")

        val adminPasswordSalt = stringPreferencesKey("admin_password_salt")
        val adminPasswordHash = stringPreferencesKey("admin_password_hash")
        val adminFailedAttempts = intPreferencesKey("admin_failed_attempts")
        val adminLockUntilMs = longPreferencesKey("admin_lock_until_ms")
        val adminLastSuccessMs = longPreferencesKey("admin_last_success_ms")

        val recoveryFailedAttempts = intPreferencesKey("recovery_failed_attempts")
        val recoveryLockUntilMs = longPreferencesKey("recovery_lock_until_ms")
        val recoveryTotalFailures = intPreferencesKey("recovery_total_failures")

        val temporaryUnlockUntilMs = longPreferencesKey("temporary_unlock_until_ms")
        val temporaryUnlockPrevMdmLocked = booleanPreferencesKey("temporary_unlock_prev_mdm_locked")
        val temporaryUnlockPrevKioskMode = booleanPreferencesKey("temporary_unlock_prev_kiosk_mode")

        val maintenanceModeEnabled = booleanPreferencesKey("maintenance_mode_enabled")
        val maintenanceModeUntilMs = longPreferencesKey("maintenance_mode_until_ms")

        val offlineState = stringPreferencesKey("offline_state")
        val offlineInternetOk = booleanPreferencesKey("offline_internet_ok")
        val offlineFirebaseOk = booleanPreferencesKey("offline_firebase_ok")
        val offlineApiOk = booleanPreferencesKey("offline_api_ok")
        val offlineLastUpdatedMs = longPreferencesKey("offline_last_updated_ms")

        val provisioningLastAttemptMs = longPreferencesKey("provisioning_last_attempt_ms")
        val provisioningLastSuccessMs = longPreferencesKey("provisioning_last_success_ms")
        val provisioningAnyDeskUrl = stringPreferencesKey("provisioning_anydesk_url")
        val provisioningMplayerUrl = stringPreferencesKey("provisioning_mplayer_url")
        val provisioningAnyDeskPluginUrls = stringPreferencesKey("provisioning_anydesk_plugin_urls_json")

        val launcherBgPortraitUrl = stringPreferencesKey("launcher_bg_portrait_url")
        val launcherBgLandscapeUrl = stringPreferencesKey("launcher_bg_landscape_url")

        val launcherWallpaperBlurPx = intPreferencesKey("launcher_wallpaper_blur_px")
        val launcherWallpaperOverlay = stringPreferencesKey("launcher_wallpaper_overlay")

        val locationTelemetryIntervalMin = intPreferencesKey("location_telemetry_interval_min")

        val geofenceEnabled = booleanPreferencesKey("geofence_enabled")
        val geofenceLat = stringPreferencesKey("geofence_lat")
        val geofenceLng = stringPreferencesKey("geofence_lng")
        val geofenceRadiusM = stringPreferencesKey("geofence_radius_m")
        val geofenceActionsJson = stringPreferencesKey("geofence_actions_json")

        val geofenceLocked = booleanPreferencesKey("geofence_locked")
        val geofenceLockedFenceId = stringPreferencesKey("geofence_locked_fence_id")
        val geofenceLockedFenceName = stringPreferencesKey("geofence_locked_fence_name")
        val geofenceUnlockPassword = stringPreferencesKey("geofence_unlock_password")

        val operatingHoursEnabled = booleanPreferencesKey("operating_hours_enabled")
        val operatingHoursStart = stringPreferencesKey("operating_hours_start")
        val operatingHoursEnd = stringPreferencesKey("operating_hours_end")
        val operatingHoursDays = stringPreferencesKey("operating_hours_days")
        val operatingHoursLocked = booleanPreferencesKey("operating_hours_locked")

        val uiFontMode = stringPreferencesKey("ui_font_mode")
        val uiIconMode = stringPreferencesKey("ui_icon_mode")

        val keepAwakeEnabled = booleanPreferencesKey("keep_awake_enabled")
        val awakeScheduleEnabled = booleanPreferencesKey("awake_schedule_enabled")
        val awakeScheduleStartMin = intPreferencesKey("awake_schedule_start_min")
        val awakeScheduleEndMin = intPreferencesKey("awake_schedule_end_min")
        val restModeUntilMs = longPreferencesKey("rest_mode_until_ms")
        val restScreenTimeoutMs = longPreferencesKey("rest_screen_timeout_ms")

        val appDistributionMode = stringPreferencesKey("app_distribution_mode")
        val autostartDelayMs = longPreferencesKey("autostart_delay_ms")
        val persistModeEnabled = booleanPreferencesKey("persist_mode_enabled")
        val persistModeTimeoutMs = longPreferencesKey("persist_mode_timeout_ms")

        val boundCompanyId = stringPreferencesKey("bound_company_id")
        val boundCompanyName = stringPreferencesKey("bound_company_name")
        val boundCompanyCode = stringPreferencesKey("bound_company_code")
        val boundDeviceId = stringPreferencesKey("bound_device_id")
        val boundDeviceGroup = stringPreferencesKey("bound_device_group")
        val boundDeviceToken = stringPreferencesKey("bound_device_token")

        val boundDeviceNickname = stringPreferencesKey("bound_device_nickname")
        val boundDeviceBranch = stringPreferencesKey("bound_device_branch")
        val boundDeviceType = stringPreferencesKey("bound_device_type")

        val technicalModeEnabled = booleanPreferencesKey("technical_mode_enabled")
    }

    suspend fun getSettings(): AgentSettings {
        return context.settingsDataStore.data.map { prefs ->
            AgentSettings(
                argosApiUrl = prefs[Keys.argosApiUrl]
                    ?: legacyPrefs.getString(LEGACY_KEY_ARGOS_API_URL, "")
                    ?: "",
                argosDeviceToken = prefs[Keys.argosDeviceToken]
                    ?: legacyPrefs.getString(LEGACY_KEY_ARGOS_DEVICE_TOKEN, "")
                    ?: "",
                autostartPackage = prefs[Keys.autostartPackage]
                    ?: legacyPrefs.getString(LEGACY_KEY_AUTOSTART_PACKAGE, "")
                    ?: "",
                kioskMode = prefs[Keys.kioskMode]
                    ?: legacyPrefs.getBoolean(LEGACY_KEY_KIOSK_MODE, false),
                mdmLocked = prefs[Keys.mdmLocked]
                    ?: legacyPrefs.getBoolean(LEGACY_KEY_MDM_LOCKED, false),
                allowedPackages = prefs[Keys.allowedPackages]
                    ?: legacyPrefs.getStringSet(LEGACY_KEY_ALLOWED_PACKAGES, null)
                    ?: emptySet(),
                mplayerDemoMode = prefs[Keys.mplayerDemoMode]
                    ?: legacyPrefs.getBoolean(LEGACY_KEY_MPLAYER_DEMO_MODE, false),
            )
        }.first()
    }

    suspend fun setArgosApiUrl(value: String) {
        val normalized = value.trim().trimEnd('/')
        persistString(Keys.argosApiUrl, LEGACY_KEY_ARGOS_API_URL, normalized)
    }

    /**
     * Falls back to [BuildConfig.ARGOS_WEB_URL] when no server-provided URL is stored yet.
     *
     * Without this fallback there's a deadlock: [com.mupa.agent.argos.core.ConfigurationEngine]
     * only ever calls [setArgosApiUrl] from a server config response, but fetching that
     * response requires a non-blank API URL in the first place — a freshly-enrolled device
     * (via GroupLinkWizard, which only persists the group binding, not the API URL) would
     * never be able to make its first call and would silently never heartbeat or appear
     * in the web console, even with network connectivity and a valid group enrollment.
     */
    fun getArgosApiUrlCached(): String {
        val stored = legacyPrefs.getString(LEGACY_KEY_ARGOS_API_URL, "") ?: ""
        return stored.ifBlank { BuildConfig.ARGOS_WEB_URL.trim() }
    }

    suspend fun setArgosDeviceToken(value: String) {
        persistString(Keys.argosDeviceToken, LEGACY_KEY_ARGOS_DEVICE_TOKEN, value.trim())
    }

    fun getArgosDeviceTokenCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_ARGOS_DEVICE_TOKEN, "") ?: ""
    }

    /**
     * Local mirror of the backend's `devices.apelido_interno` — refreshed whenever
     * registerDevice() runs. Used by ArgosForegroundService to decide whether
     * autostart/persist-mode is allowed to force the kiosk app back to the foreground, and
     * whether the registration overlay should be shown: while this is blank, the overlay
     * needs to stay visible instead of being immediately covered by MPlayer.
     */
    suspend fun setDeviceApelidoInterno(value: String) {
        persistString(Keys.deviceApelidoInterno, LEGACY_KEY_DEVICE_APELIDO_INTERNO, value.trim())
    }

    fun getDeviceApelidoInternoCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_DEVICE_APELIDO_INTERNO, "") ?: ""
    }

    suspend fun setBoundCompany(companyId: String, companyName: String, companyCode: String) {
        persistString(Keys.boundCompanyId, LEGACY_KEY_BOUND_COMPANY_ID, companyId.trim())
        persistString(Keys.boundCompanyName, LEGACY_KEY_BOUND_COMPANY_NAME, companyName.trim())
        persistString(Keys.boundCompanyCode, LEGACY_KEY_BOUND_COMPANY_CODE, companyCode.trim())
    }

    suspend fun setBoundDevice(deviceId: String, group: String, token: String) {
        persistString(Keys.boundDeviceId, LEGACY_KEY_BOUND_DEVICE_ID, deviceId.trim())
        persistString(Keys.boundDeviceGroup, LEGACY_KEY_BOUND_DEVICE_GROUP, group.trim())
        persistString(Keys.boundDeviceToken, LEGACY_KEY_BOUND_DEVICE_TOKEN, token.trim())
        if (token.isNotBlank()) setArgosDeviceToken(token.trim())
    }

    suspend fun setBoundDeviceDetails(nickname: String, branch: String, type: String) {
        persistString(Keys.boundDeviceNickname, LEGACY_KEY_BOUND_DEVICE_NICKNAME, nickname.trim())
        persistString(Keys.boundDeviceBranch, LEGACY_KEY_BOUND_DEVICE_BRANCH, branch.trim())
        persistString(Keys.boundDeviceType, LEGACY_KEY_BOUND_DEVICE_TYPE, type.trim())
    }

    fun getBoundCompanyIdCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_COMPANY_ID, "") ?: ""
    fun getBoundCompanyNameCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_COMPANY_NAME, "") ?: ""
    fun getBoundCompanyCodeCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_COMPANY_CODE, "") ?: ""
    fun getBoundDeviceIdCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_DEVICE_ID, "") ?: ""
    fun getBoundDeviceGroupCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_DEVICE_GROUP, "") ?: ""
    fun getBoundDeviceTokenCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_DEVICE_TOKEN, "") ?: ""
    fun getBoundDeviceNicknameCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_DEVICE_NICKNAME, "") ?: ""
    fun getBoundDeviceBranchCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_DEVICE_BRANCH, "") ?: ""
    fun getBoundDeviceTypeCached(): String = legacyPrefs.getString(LEGACY_KEY_BOUND_DEVICE_TYPE, "") ?: ""

    fun isDeviceBoundCached(): Boolean {
        return getBoundCompanyIdCached().isNotBlank() && getBoundDeviceIdCached().isNotBlank()
    }

    suspend fun clearDeviceBinding() {
        setBoundCompany(companyId = "", companyName = "", companyCode = "")
        setBoundDevice(deviceId = "", group = "", token = "")
        setBoundDeviceDetails(nickname = "", branch = "", type = "")
    }

    suspend fun setTechnicalModeEnabled(enabled: Boolean) {
        persistBoolean(Keys.technicalModeEnabled, LEGACY_KEY_TECHNICAL_MODE_ENABLED, enabled)
    }

    fun getTechnicalModeEnabledCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_TECHNICAL_MODE_ENABLED, false)
    }

    suspend fun setAutostartPackage(value: String) {
        persistString(Keys.autostartPackage, LEGACY_KEY_AUTOSTART_PACKAGE, value.trim())
    }

    fun getAutostartPackageCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_AUTOSTART_PACKAGE, "") ?: ""
    }

    suspend fun setKioskMode(enabled: Boolean) {
        persistBoolean(Keys.kioskMode, LEGACY_KEY_KIOSK_MODE, enabled)
    }

    fun getKioskModeCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_KIOSK_MODE, false)
    }

    suspend fun setMdmLocked(enabled: Boolean) {
        persistBoolean(Keys.mdmLocked, LEGACY_KEY_MDM_LOCKED, enabled)
    }

    fun getMdmLockedCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_MDM_LOCKED, false)
    }

    suspend fun setAllowedPackages(packages: Set<String>) {
        val normalized = packages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        legacyPrefs.edit().putStringSet(LEGACY_KEY_ALLOWED_PACKAGES, normalized).apply()
        context.settingsDataStore.edit { it[Keys.allowedPackages] = normalized }
    }

    fun getAllowedPackagesCached(): Set<String> {
        return legacyPrefs.getStringSet(LEGACY_KEY_ALLOWED_PACKAGES, null) ?: emptySet()
    }

    suspend fun setMplayerDemoMode(enabled: Boolean) {
        persistBoolean(Keys.mplayerDemoMode, LEGACY_KEY_MPLAYER_DEMO_MODE, enabled)
    }

    fun getMplayerDemoModeCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_MPLAYER_DEMO_MODE, false)
    }

    suspend fun setAppPoliciesJson(value: String) {
        persistString(Keys.appPoliciesJson, LEGACY_KEY_APP_POLICIES_JSON, value)
    }

    fun getAppPoliciesJsonCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_APP_POLICIES_JSON, "") ?: ""
    }

    suspend fun setLastCommandSyncMs(value: Long) {
        val normalized = value.coerceAtLeast(0L)
        legacyPrefs.edit().putLong(LEGACY_KEY_LAST_COMMAND_SYNC_MS, normalized).apply()
        context.settingsDataStore.edit { it[Keys.lastCommandSyncMs] = normalized }
    }

    fun getLastCommandSyncMsCached(): Long {
        return legacyPrefs.getLong(LEGACY_KEY_LAST_COMMAND_SYNC_MS, 0L)
    }

    suspend fun setProvisioningLastAttemptMs(value: Long) {
        val normalized = value.coerceAtLeast(0L)
        legacyPrefs.edit().putLong(LEGACY_KEY_PROVISIONING_LAST_ATTEMPT_MS, normalized).apply()
        context.settingsDataStore.edit { it[Keys.provisioningLastAttemptMs] = normalized }
    }

    fun getProvisioningLastAttemptMsCached(): Long {
        return legacyPrefs.getLong(LEGACY_KEY_PROVISIONING_LAST_ATTEMPT_MS, 0L)
    }

    suspend fun setProvisioningLastSuccessMs(value: Long) {
        val normalized = value.coerceAtLeast(0L)
        legacyPrefs.edit().putLong(LEGACY_KEY_PROVISIONING_LAST_SUCCESS_MS, normalized).apply()
        context.settingsDataStore.edit { it[Keys.provisioningLastSuccessMs] = normalized }
    }

    fun getProvisioningLastSuccessMsCached(): Long {
        return legacyPrefs.getLong(LEGACY_KEY_PROVISIONING_LAST_SUCCESS_MS, 0L)
    }

    suspend fun setAnyDeskApkUrl(value: String) {
        persistString(Keys.provisioningAnyDeskUrl, LEGACY_KEY_PROVISIONING_ANYDESK_URL, value.trim())
    }

    fun getAnyDeskApkUrlCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_PROVISIONING_ANYDESK_URL, "") ?: ""
    }

    suspend fun setMplayerApkUrl(value: String) {
        persistString(Keys.provisioningMplayerUrl, LEGACY_KEY_PROVISIONING_MPLAYER_URL, value.trim())
    }

    fun getMplayerApkUrlCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_PROVISIONING_MPLAYER_URL, "") ?: ""
    }

    suspend fun setAnyDeskPluginUrls(urls: List<String>) {
        setAnyDeskPluginSpecs(urls.map { AnyDeskPluginSpec(packageName = "", url = it) })
    }

    fun getAnyDeskPluginUrlsCached(): List<String> {
        return getAnyDeskPluginSpecsCached().mapNotNull { it.url.trim().takeIf { u -> u.isNotBlank() } }
    }

    suspend fun setAnyDeskPluginSpecs(specs: List<AnyDeskPluginSpec>) {
        val arr = JSONArray()
        for (s in specs) {
            val pkg = s.packageName.trim()
            val url = s.url.trim()
            if (pkg.isBlank() && url.isBlank()) continue
            if (pkg.isBlank()) {
                arr.put(url)
            } else {
                arr.put(JSONObject().put("package", pkg).put("url", url))
            }
        }
        persistString(Keys.provisioningAnyDeskPluginUrls, LEGACY_KEY_PROVISIONING_ANYDESK_PLUGIN_URLS, arr.toString())
    }

    fun getAnyDeskPluginSpecsCached(): List<AnyDeskPluginSpec> {
        val raw = legacyPrefs.getString(LEGACY_KEY_PROVISIONING_ANYDESK_PLUGIN_URLS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<AnyDeskPluginSpec>(arr.length())
        for (i in 0 until arr.length()) {
            val any = arr.opt(i) ?: continue
            when (any) {
                is String -> {
                    val url = any.trim()
                    if (url.isNotBlank()) out.add(AnyDeskPluginSpec(packageName = "", url = url))
                }
                is JSONObject -> {
                    val pkg = any.optString("package", any.optString("package_name", "")).trim()
                    val url = any.optString("url", any.optString("apk_url", "")).trim()
                    if (pkg.isNotBlank() || url.isNotBlank()) out.add(AnyDeskPluginSpec(packageName = pkg, url = url))
                }
            }
        }
        return out
    }

    fun provisioningSnapshotJson(): JSONObject {
        return JSONObject()
            .put("lastAttemptMs", getProvisioningLastAttemptMsCached())
            .put("lastSuccessMs", getProvisioningLastSuccessMsCached())
            .put("anyDeskUrl", getAnyDeskApkUrlCached())
            .put("mplayerUrl", getMplayerApkUrlCached())
            .put("anyDeskPlugins", JSONArray().apply {
                for (s in getAnyDeskPluginSpecsCached()) {
                    if (s.packageName.isBlank()) put(s.url)
                    else put(JSONObject().put("package", s.packageName).put("url", s.url))
                }
            })
    }

    suspend fun setLauncherBgPortraitUrl(value: String) {
        persistString(Keys.launcherBgPortraitUrl, LEGACY_KEY_LAUNCHER_BG_PORTRAIT_URL, value.trim())
    }

    fun getLauncherBgPortraitUrlCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_LAUNCHER_BG_PORTRAIT_URL, "") ?: ""
    }

    suspend fun setLauncherBgLandscapeUrl(value: String) {
        persistString(Keys.launcherBgLandscapeUrl, LEGACY_KEY_LAUNCHER_BG_LANDSCAPE_URL, value.trim())
    }

    fun getLauncherBgLandscapeUrlCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_LAUNCHER_BG_LANDSCAPE_URL, "") ?: ""
    }

    suspend fun setLauncherWallpaperBlurPx(value: Int) {
        val normalized = value.coerceIn(0, 60)
        persistInt(Keys.launcherWallpaperBlurPx, LEGACY_KEY_LAUNCHER_WALLPAPER_BLUR_PX, normalized)
    }

    fun getLauncherWallpaperBlurPxCached(): Int {
        val fromLegacy = legacyPrefs.getInt(LEGACY_KEY_LAUNCHER_WALLPAPER_BLUR_PX, DEFAULT_LAUNCHER_WALLPAPER_BLUR_PX)
        return fromLegacy.coerceIn(0, 60)
    }

    suspend fun setLauncherWallpaperOverlay(value: String) {
        persistString(Keys.launcherWallpaperOverlay, LEGACY_KEY_LAUNCHER_WALLPAPER_OVERLAY, value.trim())
    }

    fun getLauncherWallpaperOverlayCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_LAUNCHER_WALLPAPER_OVERLAY, DEFAULT_LAUNCHER_WALLPAPER_OVERLAY) ?: DEFAULT_LAUNCHER_WALLPAPER_OVERLAY
    }

    suspend fun setLocationTelemetryIntervalMin(value: Int) {
        val normalized = value.coerceIn(1, 24 * 60)
        persistInt(Keys.locationTelemetryIntervalMin, LEGACY_KEY_LOCATION_TELEMETRY_INTERVAL_MIN, normalized)
    }

    fun getLocationTelemetryIntervalMinCached(): Int {
        val fromLegacy = legacyPrefs.getInt(LEGACY_KEY_LOCATION_TELEMETRY_INTERVAL_MIN, DEFAULT_LOCATION_TELEMETRY_INTERVAL_MIN)
        return fromLegacy.coerceIn(1, 24 * 60)
    }

    suspend fun setGeofenceConfig(enabled: Boolean, lat: Double, lng: Double, radiusM: Double, actions: Set<String>) {
        persistBoolean(Keys.geofenceEnabled, LEGACY_KEY_GEOFENCE_ENABLED, enabled)
        persistString(Keys.geofenceLat, LEGACY_KEY_GEOFENCE_LAT, lat.toString())
        persistString(Keys.geofenceLng, LEGACY_KEY_GEOFENCE_LNG, lng.toString())
        persistString(Keys.geofenceRadiusM, LEGACY_KEY_GEOFENCE_RADIUS_M, radiusM.toString())
        val arr = JSONArray()
        actions.map { it.trim() }.filter { it.isNotBlank() }.distinct().forEach { arr.put(it) }
        persistString(Keys.geofenceActionsJson, LEGACY_KEY_GEOFENCE_ACTIONS_JSON, arr.toString())
    }

    fun getGeofenceConfigCached(): GeofenceConfig? {
        val enabled = legacyPrefs.getBoolean(LEGACY_KEY_GEOFENCE_ENABLED, false)
        if (!enabled) return null
        val lat = legacyPrefs.getString(LEGACY_KEY_GEOFENCE_LAT, "")?.trim()?.toDoubleOrNull() ?: return null
        val lng = legacyPrefs.getString(LEGACY_KEY_GEOFENCE_LNG, "")?.trim()?.toDoubleOrNull() ?: return null
        val radius = legacyPrefs.getString(LEGACY_KEY_GEOFENCE_RADIUS_M, "")?.trim()?.toDoubleOrNull() ?: return null
        val actionsRaw = legacyPrefs.getString(LEGACY_KEY_GEOFENCE_ACTIONS_JSON, "")?.trim().orEmpty()
        val actions = runCatching {
            val a = JSONArray(actionsRaw.ifBlank { "[]" })
            (0 until a.length()).mapNotNull { i -> a.optString(i).trim().takeIf { it.isNotBlank() } }.toSet()
        }.getOrDefault(emptySet())
        return GeofenceConfig(enabled = true, lat = lat, lng = lng, radiusM = radius.coerceAtLeast(10.0), actions = actions)
    }

    suspend fun setGeofenceLocked(locked: Boolean, fenceId: String = "", fenceName: String = "", unlockPassword: String = "") {
        persistBoolean(Keys.geofenceLocked, "geofenceLocked", locked)
        persistString(Keys.geofenceLockedFenceId, "geofenceLockedFenceId", fenceId)
        persistString(Keys.geofenceLockedFenceName, "geofenceLockedFenceName", fenceName)
        persistString(Keys.geofenceUnlockPassword, "geofenceUnlockPassword", unlockPassword)
    }

    fun isGeofenceLockedCached(): Boolean = legacyPrefs.getBoolean("geofenceLocked", false)
    fun getGeofenceLockedFenceNameCached(): String = legacyPrefs.getString("geofenceLockedFenceName", "").orEmpty()
    fun getGeofenceUnlockPasswordCached(): String = legacyPrefs.getString("geofenceUnlockPassword", "").orEmpty()

    fun saveFencesJson(json: String) {
        legacyPrefs.edit().putString("geofenceFencesJson", json).apply()
    }
    fun loadFencesJson(): String? = legacyPrefs.getString("geofenceFencesJson", null)
    fun clearFencesJson() {
        legacyPrefs.edit().remove("geofenceFencesJson").apply()
    }

    // ── Operating Hours ──────────────────────────────────────────────────
    suspend fun setOperatingHours(enabled: Boolean, start: String, end: String, days: String) {
        persistBoolean(Keys.operatingHoursEnabled, "operatingHoursEnabled", enabled)
        persistString(Keys.operatingHoursStart, "operatingHoursStart", start)
        persistString(Keys.operatingHoursEnd, "operatingHoursEnd", end)
        persistString(Keys.operatingHoursDays, "operatingHoursDays", days)
    }

    suspend fun setOperatingHoursLocked(locked: Boolean) {
        persistBoolean(Keys.operatingHoursLocked, "operatingHoursLocked", locked)
    }

    fun isOperatingHoursEnabledCached(): Boolean = legacyPrefs.getBoolean("operatingHoursEnabled", false)
    fun getOperatingHoursStartCached(): String = legacyPrefs.getString("operatingHoursStart", "").orEmpty()
    fun getOperatingHoursEndCached(): String = legacyPrefs.getString("operatingHoursEnd", "").orEmpty()
    fun getOperatingHoursDaysCached(): String = legacyPrefs.getString("operatingHoursDays", "").orEmpty()
    fun isOperatingHoursLockedCached(): Boolean = legacyPrefs.getBoolean("operatingHoursLocked", false)

    suspend fun setUiFontMode(value: String) {
        persistString(Keys.uiFontMode, LEGACY_KEY_UI_FONT_MODE, normalizeUiMode(value))
    }

    fun getUiFontModeCached(): String {
        return normalizeUiMode(legacyPrefs.getString(LEGACY_KEY_UI_FONT_MODE, DEFAULT_UI_MODE) ?: DEFAULT_UI_MODE)
    }

    fun uiFontModeFlow(): Flow<String> {
        return context.settingsDataStore.data.map { prefs ->
            normalizeUiMode(prefs[Keys.uiFontMode] ?: getUiFontModeCached())
        }
    }

    suspend fun setUiIconMode(value: String) {
        persistString(Keys.uiIconMode, LEGACY_KEY_UI_ICON_MODE, normalizeUiMode(value))
    }

    fun getUiIconModeCached(): String {
        return normalizeUiMode(legacyPrefs.getString(LEGACY_KEY_UI_ICON_MODE, DEFAULT_UI_MODE) ?: DEFAULT_UI_MODE)
    }

    fun uiIconModeFlow(): Flow<String> {
        return context.settingsDataStore.data.map { prefs ->
            normalizeUiMode(prefs[Keys.uiIconMode] ?: getUiIconModeCached())
        }
    }

    suspend fun setKeepAwakeEnabled(enabled: Boolean) {
        persistBoolean(Keys.keepAwakeEnabled, LEGACY_KEY_KEEP_AWAKE_ENABLED, enabled)
    }

    fun getKeepAwakeEnabledCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_KEEP_AWAKE_ENABLED, DEFAULT_KEEP_AWAKE_ENABLED)
    }

    suspend fun setAwakeSchedule(enabled: Boolean, startMinOfDay: Int, endMinOfDay: Int) {
        persistBoolean(Keys.awakeScheduleEnabled, LEGACY_KEY_AWAKE_SCHEDULE_ENABLED, enabled)
        persistInt(Keys.awakeScheduleStartMin, LEGACY_KEY_AWAKE_SCHEDULE_START_MIN, startMinOfDay.coerceIn(0, 1439))
        persistInt(Keys.awakeScheduleEndMin, LEGACY_KEY_AWAKE_SCHEDULE_END_MIN, endMinOfDay.coerceIn(0, 1440))
    }

    fun getAwakeScheduleEnabledCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_AWAKE_SCHEDULE_ENABLED, DEFAULT_AWAKE_SCHEDULE_ENABLED)
    }

    fun getAwakeScheduleStartMinCached(): Int {
        return legacyPrefs.getInt(LEGACY_KEY_AWAKE_SCHEDULE_START_MIN, DEFAULT_AWAKE_SCHEDULE_START_MIN).coerceIn(0, 1439)
    }

    fun getAwakeScheduleEndMinCached(): Int {
        return legacyPrefs.getInt(LEGACY_KEY_AWAKE_SCHEDULE_END_MIN, DEFAULT_AWAKE_SCHEDULE_END_MIN).coerceIn(0, 1440)
    }

    suspend fun setRestModeUntilMs(untilMs: Long) {
        persistLong(Keys.restModeUntilMs, LEGACY_KEY_REST_MODE_UNTIL_MS, untilMs.coerceAtLeast(0L))
    }

    fun getRestModeUntilMsCached(): Long {
        return legacyPrefs.getLong(LEGACY_KEY_REST_MODE_UNTIL_MS, 0L)
    }

    suspend fun setRestScreenTimeoutMs(valueMs: Long) {
        persistLong(Keys.restScreenTimeoutMs, LEGACY_KEY_REST_SCREEN_TIMEOUT_MS, valueMs.coerceIn(15_000L, 30 * 60_000L))
    }

    fun getRestScreenTimeoutMsCached(): Long {
        val v = legacyPrefs.getLong(LEGACY_KEY_REST_SCREEN_TIMEOUT_MS, DEFAULT_REST_SCREEN_TIMEOUT_MS)
        return v.coerceIn(15_000L, 30 * 60_000L)
    }

    fun shouldKeepAwakeNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!getKeepAwakeEnabledCached()) return false
        val restUntil = getRestModeUntilMsCached()
        if (restUntil > nowMs) return false
        if (!getAwakeScheduleEnabledCached()) return true
        return isWithinAwakeWindow(nowMs, getAwakeScheduleStartMinCached(), getAwakeScheduleEndMinCached())
    }

    suspend fun setAppDistributionMode(value: String) {
        persistString(Keys.appDistributionMode, LEGACY_KEY_APP_DISTRIBUTION_MODE, normalizeAppDistributionMode(value))
    }

    fun getAppDistributionModeCached(): String {
        return normalizeAppDistributionMode(legacyPrefs.getString(LEGACY_KEY_APP_DISTRIBUTION_MODE, DEFAULT_APP_DISTRIBUTION_MODE) ?: DEFAULT_APP_DISTRIBUTION_MODE)
    }

    fun appDistributionModeFlow(): Flow<String> {
        return context.settingsDataStore.data.map { prefs ->
            normalizeAppDistributionMode(prefs[Keys.appDistributionMode] ?: getAppDistributionModeCached())
        }
    }

    suspend fun setAutostartDelayMs(valueMs: Long) {
        persistLong(Keys.autostartDelayMs, LEGACY_KEY_AUTOSTART_DELAY_MS, valueMs.coerceIn(0L, 5 * 60_000L))
    }

    fun getAutostartDelayMsCached(): Long {
        val value = legacyPrefs.getLong(LEGACY_KEY_AUTOSTART_DELAY_MS, DEFAULT_AUTOSTART_DELAY_MS)
        return value.coerceIn(0L, 5 * 60_000L)
    }

    suspend fun setPersistModeEnabled(enabled: Boolean) {
        persistBoolean(Keys.persistModeEnabled, LEGACY_KEY_PERSIST_MODE_ENABLED, enabled)
    }

    fun getPersistModeEnabledCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_PERSIST_MODE_ENABLED, DEFAULT_PERSIST_MODE_ENABLED)
    }

    suspend fun setPersistModeTimeoutMs(valueMs: Long) {
        persistLong(Keys.persistModeTimeoutMs, LEGACY_KEY_PERSIST_MODE_TIMEOUT_MS, valueMs.coerceIn(5_000L, 30 * 60_000L))
    }

    fun getPersistModeTimeoutMsCached(): Long {
        val value = legacyPrefs.getLong(LEGACY_KEY_PERSIST_MODE_TIMEOUT_MS, DEFAULT_PERSIST_MODE_TIMEOUT_MS)
        return value.coerceIn(5_000L, 30 * 60_000L)
    }

    fun parseTimeToMinutesOfDay(raw: String): Int? {
        val s = raw.trim()
        if (s.isBlank()) return null
        val parts = s.split(':')
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23) return null
        if (m !in 0..59) return null
        return h * 60 + m
    }

    private fun isWithinAwakeWindow(nowMs: Long, startMin: Int, endMin: Int): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (startMin == endMin) return true
        return if (startMin < endMin) nowMin in startMin until endMin else (nowMin >= startMin || nowMin < endMin)
    }

    suspend fun getAdminPasswordSalt(): String {
        return context.settingsDataStore.data.first()[Keys.adminPasswordSalt].orEmpty()
    }

    suspend fun getAdminPasswordHash(): String {
        return context.settingsDataStore.data.first()[Keys.adminPasswordHash].orEmpty()
    }

    suspend fun setAdminPasswordSecret(salt: String, hash: String) {
        context.settingsDataStore.edit {
            it[Keys.adminPasswordSalt] = salt
            it[Keys.adminPasswordHash] = hash
        }
    }

    suspend fun getAdminFailedAttempts(): Int {
        return context.settingsDataStore.data.first()[Keys.adminFailedAttempts] ?: 0
    }

    suspend fun setAdminFailedAttempts(value: Int) {
        context.settingsDataStore.edit { it[Keys.adminFailedAttempts] = value.coerceAtLeast(0) }
    }

    suspend fun getAdminLockUntilMs(): Long {
        return context.settingsDataStore.data.first()[Keys.adminLockUntilMs] ?: 0L
    }

    suspend fun setAdminLockUntilMs(value: Long) {
        context.settingsDataStore.edit { it[Keys.adminLockUntilMs] = value.coerceAtLeast(0L) }
    }

    suspend fun setAdminLastSuccessMs(value: Long) {
        context.settingsDataStore.edit { it[Keys.adminLastSuccessMs] = value.coerceAtLeast(0L) }
    }

    suspend fun getRecoveryFailedAttempts(): Int {
        return context.settingsDataStore.data.first()[Keys.recoveryFailedAttempts] ?: 0
    }

    suspend fun setRecoveryFailedAttempts(value: Int) {
        persistInt(Keys.recoveryFailedAttempts, LEGACY_KEY_RECOVERY_FAILED_ATTEMPTS, value.coerceAtLeast(0))
    }

    suspend fun getRecoveryLockUntilMs(): Long {
        return context.settingsDataStore.data.first()[Keys.recoveryLockUntilMs] ?: 0L
    }

    suspend fun setRecoveryLockUntilMs(value: Long) {
        persistLong(Keys.recoveryLockUntilMs, LEGACY_KEY_RECOVERY_LOCK_UNTIL_MS, value.coerceAtLeast(0L))
    }

    suspend fun getRecoveryTotalFailures(): Int {
        return context.settingsDataStore.data.first()[Keys.recoveryTotalFailures] ?: 0
    }

    suspend fun setRecoveryTotalFailures(value: Int) {
        persistInt(Keys.recoveryTotalFailures, LEGACY_KEY_RECOVERY_TOTAL_FAILURES, value.coerceAtLeast(0))
    }

    suspend fun setTemporaryUnlock(untilMs: Long, prevMdmLocked: Boolean, prevKioskMode: Boolean) {
        persistLong(Keys.temporaryUnlockUntilMs, LEGACY_KEY_TEMP_UNLOCK_UNTIL_MS, untilMs.coerceAtLeast(0L))
        persistBoolean(Keys.temporaryUnlockPrevMdmLocked, LEGACY_KEY_TEMP_UNLOCK_PREV_MDM_LOCKED, prevMdmLocked)
        persistBoolean(Keys.temporaryUnlockPrevKioskMode, LEGACY_KEY_TEMP_UNLOCK_PREV_KIOSK_MODE, prevKioskMode)
    }

    fun getTemporaryUnlockUntilMsCached(): Long {
        return legacyPrefs.getLong(LEGACY_KEY_TEMP_UNLOCK_UNTIL_MS, 0L)
    }

    fun getTemporaryUnlockPrevMdmLockedCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_TEMP_UNLOCK_PREV_MDM_LOCKED, false)
    }

    fun getTemporaryUnlockPrevKioskModeCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_TEMP_UNLOCK_PREV_KIOSK_MODE, false)
    }

    suspend fun clearTemporaryUnlock() {
        setTemporaryUnlock(0L, prevMdmLocked = false, prevKioskMode = false)
    }

    suspend fun setMaintenanceMode(enabled: Boolean, untilMs: Long = 0L) {
        persistBoolean(Keys.maintenanceModeEnabled, LEGACY_KEY_MAINTENANCE_MODE_ENABLED, enabled)
        persistLong(Keys.maintenanceModeUntilMs, LEGACY_KEY_MAINTENANCE_MODE_UNTIL_MS, untilMs.coerceAtLeast(0L))
    }

    fun getMaintenanceModeEnabledCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_MAINTENANCE_MODE_ENABLED, false)
    }

    fun getMaintenanceModeUntilMsCached(): Long {
        return legacyPrefs.getLong(LEGACY_KEY_MAINTENANCE_MODE_UNTIL_MS, 0L)
    }

    suspend fun setOfflineSnapshot(state: String, internetOk: Boolean, firebaseOk: Boolean, apiOk: Boolean, nowMs: Long) {
        persistString(Keys.offlineState, LEGACY_KEY_OFFLINE_STATE, state.trim())
        persistBoolean(Keys.offlineInternetOk, LEGACY_KEY_OFFLINE_INTERNET_OK, internetOk)
        persistBoolean(Keys.offlineFirebaseOk, LEGACY_KEY_OFFLINE_FIREBASE_OK, firebaseOk)
        persistBoolean(Keys.offlineApiOk, LEGACY_KEY_OFFLINE_API_OK, apiOk)
        persistLong(Keys.offlineLastUpdatedMs, LEGACY_KEY_OFFLINE_LAST_UPDATED_MS, nowMs.coerceAtLeast(0L))
    }

    fun getOfflineStateCached(): String {
        return legacyPrefs.getString(LEGACY_KEY_OFFLINE_STATE, "") ?: ""
    }

    fun getOfflineInternetOkCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_OFFLINE_INTERNET_OK, false)
    }

    fun getOfflineFirebaseOkCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_OFFLINE_FIREBASE_OK, false)
    }

    fun getOfflineApiOkCached(): Boolean {
        return legacyPrefs.getBoolean(LEGACY_KEY_OFFLINE_API_OK, false)
    }

    fun getOfflineLastUpdatedMsCached(): Long {
        return legacyPrefs.getLong(LEGACY_KEY_OFFLINE_LAST_UPDATED_MS, 0L)
    }

    private suspend fun persistString(key: Preferences.Key<String>, legacyKey: String, value: String) {
        legacyPrefs.edit().putString(legacyKey, value).apply()
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun persistBoolean(
        key: Preferences.Key<Boolean>,
        legacyKey: String,
        value: Boolean,
    ) {
        legacyPrefs.edit().putBoolean(legacyKey, value).apply()
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun persistLong(key: Preferences.Key<Long>, legacyKey: String, value: Long) {
        legacyPrefs.edit().putLong(legacyKey, value).apply()
        context.settingsDataStore.edit { it[key] = value }
    }

    private suspend fun persistInt(key: Preferences.Key<Int>, legacyKey: String, value: Int) {
        legacyPrefs.edit().putInt(legacyKey, value).apply()
        context.settingsDataStore.edit { it[key] = value }
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "argos_settings_legacy"
        private const val LEGACY_KEY_ARGOS_API_URL = "argosApiUrl"
        private const val LEGACY_KEY_ARGOS_DEVICE_TOKEN = "argosDeviceToken"
        private const val LEGACY_KEY_DEVICE_APELIDO_INTERNO = "deviceApelidoInterno"
        private const val LEGACY_KEY_AUTOSTART_PACKAGE = "autostartPackage"
        private const val LEGACY_KEY_KIOSK_MODE = "kiosk_mode"
        private const val LEGACY_KEY_MDM_LOCKED = "mdmLocked"
        private const val LEGACY_KEY_ALLOWED_PACKAGES = "allowedPackages"
        private const val LEGACY_KEY_APP_POLICIES_JSON = "appPoliciesJson"
        private const val LEGACY_KEY_LAST_COMMAND_SYNC_MS = "lastCommandSyncMs"
        private const val LEGACY_KEY_MPLAYER_DEMO_MODE = "mplayerDemoMode"

        private const val LEGACY_KEY_PROVISIONING_LAST_ATTEMPT_MS = "provisioningLastAttemptMs"
        private const val LEGACY_KEY_PROVISIONING_LAST_SUCCESS_MS = "provisioningLastSuccessMs"
        private const val LEGACY_KEY_PROVISIONING_ANYDESK_URL = "provisioningAnyDeskUrl"
        private const val LEGACY_KEY_PROVISIONING_MPLAYER_URL = "provisioningMplayerUrl"
        private const val LEGACY_KEY_PROVISIONING_ANYDESK_PLUGIN_URLS = "provisioningAnyDeskPluginUrlsJson"

        private const val LEGACY_KEY_LAUNCHER_BG_PORTRAIT_URL = "launcherBgPortraitUrl"
        private const val LEGACY_KEY_LAUNCHER_BG_LANDSCAPE_URL = "launcherBgLandscapeUrl"
        private const val LEGACY_KEY_LAUNCHER_WALLPAPER_BLUR_PX = "launcherWallpaperBlurPx"
        private const val LEGACY_KEY_LAUNCHER_WALLPAPER_OVERLAY = "launcherWallpaperOverlay"
        private const val LEGACY_KEY_LOCATION_TELEMETRY_INTERVAL_MIN = "locationTelemetryIntervalMin"
        private const val LEGACY_KEY_GEOFENCE_ENABLED = "geofenceEnabled"
        private const val LEGACY_KEY_GEOFENCE_LAT = "geofenceLat"
        private const val LEGACY_KEY_GEOFENCE_LNG = "geofenceLng"
        private const val LEGACY_KEY_GEOFENCE_RADIUS_M = "geofenceRadiusM"
        private const val LEGACY_KEY_GEOFENCE_ACTIONS_JSON = "geofenceActionsJson"

        private const val DEFAULT_LAUNCHER_WALLPAPER_BLUR_PX = 28
        private const val DEFAULT_LAUNCHER_WALLPAPER_OVERLAY = "rgba(0,0,0,0.25)"
        private const val DEFAULT_LOCATION_TELEMETRY_INTERVAL_MIN = 15

        private const val LEGACY_KEY_UI_FONT_MODE = "uiFontMode"
        private const val LEGACY_KEY_UI_ICON_MODE = "uiIconMode"
        private const val DEFAULT_UI_MODE = "auto"

        private const val LEGACY_KEY_KEEP_AWAKE_ENABLED = "keepAwakeEnabled"
        private const val DEFAULT_KEEP_AWAKE_ENABLED = true

        private const val LEGACY_KEY_AWAKE_SCHEDULE_ENABLED = "awakeScheduleEnabled"
        private const val LEGACY_KEY_AWAKE_SCHEDULE_START_MIN = "awakeScheduleStartMin"
        private const val LEGACY_KEY_AWAKE_SCHEDULE_END_MIN = "awakeScheduleEndMin"
        private const val DEFAULT_AWAKE_SCHEDULE_ENABLED = false
        private const val DEFAULT_AWAKE_SCHEDULE_START_MIN = 0
        private const val DEFAULT_AWAKE_SCHEDULE_END_MIN = 1440

        private const val LEGACY_KEY_REST_MODE_UNTIL_MS = "restModeUntilMs"
        private const val LEGACY_KEY_REST_SCREEN_TIMEOUT_MS = "restScreenTimeoutMs"
        private const val DEFAULT_REST_SCREEN_TIMEOUT_MS = 60_000L

        private const val LEGACY_KEY_APP_DISTRIBUTION_MODE = "appDistributionMode"
        private const val DEFAULT_APP_DISTRIBUTION_MODE = "argos_apk"
        private const val LEGACY_KEY_AUTOSTART_DELAY_MS = "autostartDelayMs"
        private const val DEFAULT_AUTOSTART_DELAY_MS = 5_000L
        private const val LEGACY_KEY_PERSIST_MODE_ENABLED = "persistModeEnabled"
        private const val DEFAULT_PERSIST_MODE_ENABLED = false
        private const val LEGACY_KEY_PERSIST_MODE_TIMEOUT_MS = "persistModeTimeoutMs"
        private const val DEFAULT_PERSIST_MODE_TIMEOUT_MS = 30_000L

        private const val LEGACY_KEY_RECOVERY_FAILED_ATTEMPTS = "recoveryFailedAttempts"
        private const val LEGACY_KEY_RECOVERY_LOCK_UNTIL_MS = "recoveryLockUntilMs"
        private const val LEGACY_KEY_RECOVERY_TOTAL_FAILURES = "recoveryTotalFailures"

        private const val LEGACY_KEY_TEMP_UNLOCK_UNTIL_MS = "temporaryUnlockUntilMs"
        private const val LEGACY_KEY_TEMP_UNLOCK_PREV_MDM_LOCKED = "temporaryUnlockPrevMdmLocked"
        private const val LEGACY_KEY_TEMP_UNLOCK_PREV_KIOSK_MODE = "temporaryUnlockPrevKioskMode"

        private const val LEGACY_KEY_MAINTENANCE_MODE_ENABLED = "maintenanceModeEnabled"
        private const val LEGACY_KEY_MAINTENANCE_MODE_UNTIL_MS = "maintenanceModeUntilMs"

        private const val LEGACY_KEY_OFFLINE_STATE = "offlineState"
        private const val LEGACY_KEY_OFFLINE_INTERNET_OK = "offlineInternetOk"
        private const val LEGACY_KEY_OFFLINE_FIREBASE_OK = "offlineFirebaseOk"
        private const val LEGACY_KEY_OFFLINE_API_OK = "offlineApiOk"
        private const val LEGACY_KEY_OFFLINE_LAST_UPDATED_MS = "offlineLastUpdatedMs"

        private const val LEGACY_KEY_BOUND_COMPANY_ID = "boundCompanyId"
        private const val LEGACY_KEY_BOUND_COMPANY_NAME = "boundCompanyName"
        private const val LEGACY_KEY_BOUND_COMPANY_CODE = "boundCompanyCode"
        private const val LEGACY_KEY_BOUND_DEVICE_ID = "boundDeviceId"
        private const val LEGACY_KEY_BOUND_DEVICE_GROUP = "boundDeviceGroup"
        private const val LEGACY_KEY_BOUND_DEVICE_TOKEN = "boundDeviceToken"
        private const val LEGACY_KEY_BOUND_DEVICE_NICKNAME = "boundDeviceNickname"
        private const val LEGACY_KEY_BOUND_DEVICE_BRANCH = "boundDeviceBranch"
        private const val LEGACY_KEY_BOUND_DEVICE_TYPE = "boundDeviceType"

        private const val LEGACY_KEY_TECHNICAL_MODE_ENABLED = "technicalModeEnabled"
    }

    private fun normalizeUiMode(raw: String): String {
        return when (raw.trim().lowercase()) {
            "auto", "automatic", "automatica", "automático" -> "auto"
            "small", "pequena", "pequeno" -> "small"
            "medium", "media", "média" -> "medium"
            "large", "grande" -> "large"
            else -> "auto"
        }
    }

    private fun normalizeAppDistributionMode(raw: String): String {
        return when (raw.trim().lowercase()) {
            "argos_apk", "apk", "argos" -> "argos_apk"
            "managed_play", "managed_google_play", "android_enterprise", "enterprise", "play" -> "managed_play"
            "auto", "automatic", "automatica", "automático" -> "auto"
            else -> DEFAULT_APP_DISTRIBUTION_MODE
        }
    }
}
