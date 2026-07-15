package com.mupa.agent.argos.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager.BadTokenException
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.view.WindowManager
import com.mupa.agent.argos.BuildConfig
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import androidx.core.app.NotificationCompat
import com.mupa.agent.argos.R
import com.mupa.agent.argos.accessibility.ArgosAccessibilityService
import com.mupa.agent.argos.admin.WifiManager
import com.mupa.agent.argos.apps.ApplicationManager
import com.mupa.agent.argos.commands.LocalCommandStore
import com.mupa.agent.argos.launcher.AutoStartManager
import com.mupa.agent.argos.commands.ArgosCommandScheduler
import com.mupa.agent.argos.commands.RealtimeCommandManager
import com.mupa.agent.argos.firebase.FirebaseRuntimeMode
import com.mupa.agent.argos.launcher.PersistenceManager
import com.mupa.agent.argos.launcher.WatchdogManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.ota.OtaManager
import com.mupa.agent.argos.provisioning.ApkInstallManager
import com.mupa.agent.argos.provisioning.ApkInstallRequest
import com.mupa.agent.argos.provisioning.ProvisioningManager
import com.mupa.agent.argos.telemetry.TelemetryManager
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import com.mupa.agent.argos.ui.compose.Routes
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

class ArgosForegroundService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var loopJob: Job? = null
    private var persistMonitorJob: Job? = null
    private var realtime: RealtimeCommandManager? = null
    private var wifiAssistLastOpenMs: Long = 0L
    private var wifiAssistNeedsRelock: Boolean = false
    private var firebaseInfoListener: ValueEventListener? = null
    private var firebaseConnected: Boolean = false
    private var offlineLastApiCheckMs: Long = 0L
    private var offlineApiOk: Boolean = true
    private var overlayTapView: View? = null
    private var overlayDialogView: View? = null
    private var registrationOverlayView: View? = null
    private var geofenceLockOverlayView: View? = null
    private val recoveryTapTimes = ArrayDeque<Long>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastEnsureGpsMs: Long = 0L
    private var lastEnsurePowerMs: Long = 0L
    private var lastAppliedKeepAwake: Boolean? = null
    private var lastEnsureRemoteSupportMs: Long = 0L
    private var remoteSupportInstalled: Boolean = false
    private val telemetry by lazy { TelemetryManager(applicationContext) }
    private val apps by lazy { ApplicationManager(applicationContext) }
    private var lastHealthSnapshotMs: Long = 0L
    private var lastReplaySampleMs: Long = 0L
    private var lastAutoHealMs: Long = 0L
    private var lastSyncRestartMs: Long = 0L
    private var lastBatteryAlertMs: Long = 0L
    private var lastForegroundPlayerMs: Long = 0L
    private var lastForegroundLauncherMs: Long = 0L
    private var lastGeofenceCheckMs: Long = 0L
    private var lastGeofenceEventMs: Long = 0L
    private var geofenceWasInside: Boolean? = null
    private var lastMainHeartbeatMs: Long = 0L
    private var mainHeartbeatStarted: Boolean = false
    private var lastExitCheckMs: Long = 0L
    private var lastAdaptivePollMs: Long = 0L
    private var lastHeartbeatCheckMs: Long = 0L
    private var lastOperationalSweepMs: Long = 0L
    private var lastProvisioningSweepMs: Long = 0L
    private var recoveryOverlayEnabled: Boolean = false
    private var bootAutostartDueAtMs: Long = 0L
    private var initialAutostartDone: Boolean = false
    private val pollingService by lazy { PollingService(applicationContext) }
    private val heartbeatService by lazy { HeartbeatService(applicationContext) }
    private val logFlushService by lazy { LogFlushService(applicationContext) }
    private val localCommandStore by lazy { LocalCommandStore(applicationContext) }
    private val otaManager by lazy { OtaManager(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        // Self-grant runtime permissions (READ_PHONE_STATE, location) BEFORE anything resolves
        // the device ID or promotes the FGS type — Build.getSerial() needs READ_PHONE_STATE even
        // as Device Owner, and the "location" FGS type needs location granted on Android 14.
        runCatching {
            com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager(applicationContext)
                .selfGrantRuntimePermissions(packageName)
        }
        // Auto-connect to saved WiFi, if configured
        runCatching {
            com.mupa.agent.argos.wifi.WifiConfig(applicationContext).autoConnectIfConfigured()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_BOOT_COMPLETED_STARTUP) {
            bootAutostartDueAtMs = System.currentTimeMillis() + SettingsManager(applicationContext).getAutostartDelayMsCached()
            initialAutostartDone = false
        }
        startForegroundCompat()
        ensureMainHeartbeat()
        if (intent?.action == ACTION_RECOVERY_OVERLAY_STATE) {
            val enabled = intent.getBooleanExtra(EXTRA_RECOVERY_OVERLAY_ENABLED, false)
            updateRecoveryOverlayEnabled(enabled)
        }
        if (intent?.action == ACTION_FORCE_PRESENCE_SYNC) {
            realtime?.triggerImmediatePresencePush()
        }
        if (realtime == null && shouldUseLegacyRealtime()) {
            realtime = RealtimeCommandManager(applicationContext).also { it.start() }
        }
        ensureFirebaseConnectedListener()
        scope.launch {
            runCatching { ProvisioningManager(applicationContext).runNow() }
        }
        if (loopJob == null) {
            loopJob = scope.launch {
                while (isActive) {
                    val now = System.currentTimeMillis()
                    // Same gate as ensurePersistTargetIfIdle() below — this is the *other*,
                    // independent autostart trigger (cold/boot launch of the kiosk app),
                    // which was covering the registration wizard within seconds of startup
                    // since it never checked apelido_interno at all.
                    if (!initialAutostartDone && bootAutostartDueAtMs > 0L && now >= bootAutostartDueAtMs) {
                        runCatching { WatchdogManager(applicationContext).runNow() }
                        initialAutostartDone = true
                    } else if (!initialAutostartDone && bootAutostartDueAtMs == 0L) {
                        runCatching { WatchdogManager(applicationContext).runNow() }
                        initialAutostartDone = true
                    }
                    runCatching { runAdaptivePollingAndHeartbeat(now) }
                    if (now - lastOperationalSweepMs >= OPERATIONAL_SWEEP_INTERVAL_MS) {
                        lastOperationalSweepMs = now
                        runCatching { recordIntelligenceSampleAndHeal() }
                        runCatching { ensureMaintenanceOrTemporaryUnlock() }
                        runCatching { updateOfflineSnapshot() }
                        runCatching { ensureFloatingRecoveryButton() }
                        // Registration overlay removed — GroupLinkWizard handles enrollment+naming now
                        dismissRegistrationOverlay()
                        runCatching { com.mupa.agent.argos.geofence.GeofenceEngine(applicationContext).check() }
                        runCatching { com.mupa.agent.argos.geofence.OperatingHoursEngine(applicationContext).check() }
                        runCatching { ensureGeofenceLockOverlay() }
                        runCatching { ensureBaselineRestrictions() }
                        runCatching { ensureGpsEnabled() }
                        runCatching { ensurePowerPolicy() }
                        runCatching { ensureArgosLauncherIfFinalized() }
                        runCatching { ensureWifiSetupIfOffline() }
                        runCatching { ensureRemoteSupportAppsInstalled() }
                        runCatching { ensureArgosRemoteHealthy() }
                        val settings = SettingsManager(applicationContext)
                        val maintenance = settings.getMaintenanceModeEnabledCached()
                        val tempUnlockActive = settings.getTemporaryUnlockUntilMsCached() > System.currentTimeMillis()
                        val bootAutostartPending = !initialAutostartDone && bootAutostartDueAtMs > 0L
                        if (!maintenance && !tempUnlockActive && !bootAutostartPending) {
                            val pkgs = PersistenceManager(applicationContext).getPersistencePackages()
                            WatchdogManager(applicationContext).ensureRunning(pkgs)
                        }
                    }
                    if (now - lastProvisioningSweepMs >= PROVISIONING_SWEEP_INTERVAL_MS) {
                        lastProvisioningSweepMs = now
                        runCatching { ProvisioningManager(applicationContext).runIfDue() }
                    }
                    delay(computeOperationalDelayMs(now))
                }
            }
        }
        if (persistMonitorJob == null) {
            persistMonitorJob = scope.launch {
                while (isActive) {
                    runCatching { ensurePersistTargetIfIdle() }
                    delay(PERSIST_CHECK_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        persistMonitorJob?.cancel()
        realtime?.stop()
        realtime = null
        if (FirebaseRuntimeMode.allowFirebaseConnectivityTracking) {
            firebaseInfoListener?.let { FirebaseDatabase.getInstance().getReference(".info/connected").removeEventListener(it) }
        }
        firebaseInfoListener = null
        removeFloatingRecoveryButton()
        dismissRegistrationOverlay()
        serviceJob.cancel()
        super.onDestroy()
    }

    private fun hasFirebaseRuntimeConfig(): Boolean {
        return BuildConfig.FIREBASE_DATABASE_URL.trim().isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.trim().isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.trim().isNotBlank() &&
            BuildConfig.FIREBASE_API_KEY.trim().isNotBlank()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasLocation = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val fgsType = if (hasLocation) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, ArgosLauncherActivity::class.java)
        val pending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Ativo")
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureGpsEnabled() {
        val now = System.currentTimeMillis()
        if (now - lastEnsureGpsMs < 5 * 60_000L) return
        lastEnsureGpsMs = now

        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ||
                runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        }
        if (enabled) return

        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return
        runCatching { dpm.setLocationEnabledError(packageName, enabled = true) }
    }

    private fun ensurePowerPolicy() {
        val now = System.currentTimeMillis()
        if (now - lastEnsurePowerMs < 20_000L && lastAppliedKeepAwake != null) return
        lastEnsurePowerMs = now

        val settings = SettingsManager(applicationContext)

        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) {
            lastAppliedKeepAwake = null
            return
        }

        val keepAwake = settings.shouldKeepAwakeNow(now)
        val changed = lastAppliedKeepAwake == null || lastAppliedKeepAwake != keepAwake
        if (!changed) return

        if (keepAwake) {
            runCatching { dpm.setScreenOffTimeoutMsError(packageName, 24 * 60 * 60 * 1000L) }
            runCatching { dpm.setStayOnWhilePluggedInError(packageName, enabled = true) }
        } else {
            runCatching { dpm.setScreenOffTimeoutMsError(packageName, settings.getRestScreenTimeoutMsCached()) }
            runCatching { dpm.setStayOnWhilePluggedInError(packageName, enabled = false) }
        }
        lastAppliedKeepAwake = keepAwake
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Argos Agent",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun ensureArgosLauncherIfFinalized() {
        val settings = SettingsManager(applicationContext)
        val tempUnlockActive = settings.getTemporaryUnlockUntilMsCached() > System.currentTimeMillis()
        if (tempUnlockActive) return
        if (settings.getMaintenanceModeEnabledCached()) return
        val finalized = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (!finalized) return
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return
        dpm.setLauncherAsHome(packageName, enabled = true)

        val allowed = settings.getAllowedPackagesCached() + packageName
        val fg = currentForegroundPackage()?.trim().orEmpty()
        if (fg.isBlank()) return
        if (allowed.contains(fg)) return
        if (fg == "com.android.systemui") return
        val i = Intent(applicationContext, ArgosLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(i) }
    }

    private fun currentForegroundPackage(): String? {
        return currentForegroundState()?.packageName
    }

    private data class ForegroundState(
        val packageName: String,
        val lastResumedAtMs: Long,
    )

    private fun currentForegroundState(): ForegroundState? {
        val now = System.currentTimeMillis()
        val accessibilityPkg = ArgosAccessibilityService.getLastForegroundPackage(applicationContext).trim()
        val accessibilityAt = ArgosAccessibilityService.getLastForegroundAt(applicationContext)
        if (accessibilityPkg.isNotBlank() && now - accessibilityAt <= 2 * 60_000L) {
            return ForegroundState(accessibilityPkg, accessibilityAt)
        }
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val events = usm.queryEvents(now - 2 * 60_000L, now)
        val ev = UsageEvents.Event()
        var lastPkg: String? = null
        var lastAt = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPkg = ev.packageName?.trim()?.ifBlank { null }
                lastAt = ev.timeStamp
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = ev.packageName?.trim()?.ifBlank { null }
                lastAt = ev.timeStamp
            }
        }
        return lastPkg?.let { ForegroundState(it, lastAt) }
    }

    private suspend fun ensurePersistTargetIfIdle() {
        val settings = SettingsManager(applicationContext)
        if (!settings.getPersistModeEnabledCached()) return
        if (settings.getMaintenanceModeEnabledCached()) return
        if (settings.getTemporaryUnlockUntilMsCached() > System.currentTimeMillis()) return
        // Registered but apelido_interno still blank — the step-by-step registration
        // wizard needs to stay on screen instead of being immediately covered by the
        // autostart/persist target (otherwise the device sits unnamed in the fleet forever,
        // since nothing else ever prompts for it again).
        if (settings.getArgosDeviceTokenCached().isNotBlank() && settings.getDeviceApelidoInternoCached().isBlank()) return

        val targetPkg = AutoStartManager(applicationContext).getAutostartPackagesInPriorityOrder()
            .firstOrNull()
            ?.trim()
            ?.ifBlank { null }
            ?: settings.getAutostartPackageCached().trim().ifBlank { "com.mupa.player.enterprise" }
        if (targetPkg.isBlank() || targetPkg.equals(packageName, ignoreCase = true)) return

        val state = currentForegroundState() ?: return
        val foregroundPkg = state.packageName.trim()
        if (foregroundPkg.equals(targetPkg, ignoreCase = true)) return
        if (foregroundPkg.equals("com.android.systemui", ignoreCase = true)) return
        if (foregroundPkg.equals("com.google.android.permissioncontroller", ignoreCase = true)) return
        if (foregroundPkg.equals("com.android.permissioncontroller", ignoreCase = true)) return

        val timeoutMs = settings.getPersistModeTimeoutMsCached()
        val lastInteractionAt = ArgosAccessibilityService.getLastUserInteractionAt(applicationContext)
            .takeIf { it > 0L }
            ?: state.lastResumedAtMs
        if (System.currentTimeMillis() - lastInteractionAt < timeoutMs) return

        WatchdogManager(applicationContext).launch(targetPkg)
    }

    private fun recordIntelligenceSampleAndHeal() {
        val now = System.currentTimeMillis()
        val settings = SettingsManager(applicationContext)
        val onlineState = settings.getOfflineStateCached().trim().ifBlank { "unknown" }
        val fg = currentForegroundPackage()?.trim().orEmpty()

        if (fg == "com.mupa.player.enterprise") lastForegroundPlayerMs = now
        if (fg == packageName) lastForegroundLauncherMs = now

        val hb = lastMainHeartbeatMs
        if (hb > 0L && now - hb > 20_000L && now - lastAutoHealMs > 60_000L) {
            lastAutoHealMs = now
            emitIncidentSnapshot(reason = "anr_suspected", details = "main_heartbeat_stale_ms=${now - hb}")
        }

        if (now - lastReplaySampleMs >= 20_000L) {
            lastReplaySampleMs = now
            val sample = telemetry.collectReplaySample(foregroundPackage = fg.ifBlank { null }, onlineState = onlineState)
            telemetry.recordReplaySample(sample)

            val lowMem = sample.optJSONObject("ram")?.optBoolean("low_memory", false) == true
            if (lowMem && now - lastAutoHealMs > 60_000L) {
                lastAutoHealMs = now
                val dpm = DeviceOwnerPolicyManager(applicationContext)
                if (dpm.isDeviceOwner(packageName)) {
                    val r = dpm.clearAppUserDataError(packageName, "com.mupa.player.enterprise")
                    emitIncidentSnapshot(reason = "memory_critical", details = r ?: "cleared_player_data")
                } else {
                    emitIncidentSnapshot(reason = "memory_critical", details = "not_device_owner")
                }
            }
        }

        ensureGeofence(settings, now)

        val maintenance = settings.getMaintenanceModeEnabledCached()
        val tempUnlockActive = settings.getTemporaryUnlockUntilMsCached() > now
        if (maintenance || tempUnlockActive) return

        val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (locked && settings.isDeviceBoundCached()) {
            if (now - lastForegroundPlayerMs > 60_000L && now - lastAutoHealMs > 30_000L) {
                lastAutoHealMs = now
                val opened = apps.open("com.mupa.player.enterprise")
                emitIncidentSnapshot(reason = "player_missing", details = if (opened) "reopened" else "open_failed")
            }
        }

        val internetOk = hasInternet(applicationContext)
        // firebaseConnected is only ever updated by ensureFirebaseConnectedListener(), which is
        // gated off by FirebaseRuntimeMode.allowFirebaseConnectivityTracking — with that flag
        // disabled (current architecture), firebaseConnected is permanently stuck at its default
        // `false`, making `!firebaseOk` always true. That previously forced a full Firebase RTDB
        // reconnect (stop + restart the seq listener) every 5 minutes for no reason, generating
        // unnecessary connection churn. Without a real connectivity signal, don't "self-heal" —
        // the Firebase SDK already reconnects on its own when the network actually drops.
        val firebaseOk = firebaseConnected
        val hasReliableFirebaseSignal = FirebaseRuntimeMode.allowFirebaseConnectivityTracking
        if (shouldUseLegacyRealtime() && hasReliableFirebaseSignal && internetOk && !firebaseOk && now - lastSyncRestartMs > 5 * 60_000L) {
            lastSyncRestartMs = now
            runCatching {
                realtime?.stop()
                realtime = if (shouldUseLegacyRealtime()) {
                    RealtimeCommandManager(applicationContext).also { it.start() }
                } else {
                    null
                }
            }
            emitIncidentSnapshot(reason = "sync_restart", details = "firebase_disconnected")
        }
    }

    private suspend fun runAdaptivePollingAndHeartbeat(now: Long) {
        val pollIntervalMs = computeCommandPollIntervalMs(now)
        if (now - lastAdaptivePollMs >= pollIntervalMs) {
            lastAdaptivePollMs = now
            pollingService.tick()
        }
        if (now - lastHeartbeatCheckMs >= HEARTBEAT_CHECK_INTERVAL_MS) {
            lastHeartbeatCheckMs = now
            heartbeatService.send()
            logFlushService.flushIfDue()
        }
        runCatching { checkCriticalAppExits(now) }
    }

    private fun computeOperationalDelayMs(now: Long): Long {
        return computeCommandPollIntervalMs(now)
            .coerceAtMost(OPERATIONAL_SWEEP_INTERVAL_MS)
            .coerceAtLeast(MIN_LOOP_DELAY_MS)
    }

    private fun computeCommandPollIntervalMs(now: Long): Long {
        if (hasCriticalCommandPending(now)) return COMMAND_POLL_INTERVAL_CRITICAL_MS
        if (otaManager.hasPendingOrActiveUpdate()) return COMMAND_POLL_INTERVAL_UPDATE_MS
        if (isRemoteSessionActive()) return COMMAND_POLL_INTERVAL_REMOTE_MS
        if (isLongIdle(now)) return COMMAND_POLL_INTERVAL_IDLE_MS
        return COMMAND_POLL_INTERVAL_NORMAL_MS
    }

    private fun hasCriticalCommandPending(now: Long): Boolean {
        val command = runCatching { localCommandStore.listRunnable(now = now, limit = 1).firstOrNull() }.getOrNull() ?: return false
        return command.priority >= CRITICAL_COMMAND_PRIORITY
    }

    private fun isRemoteSessionActive(): Boolean {
        return remoteHealthManager.getLastHealthSnapshot()?.optBoolean("session_active", false) == true
    }

    private fun isLongIdle(now: Long): Boolean {
        val lastInteractionAt = ArgosAccessibilityService.getLastUserInteractionAt(applicationContext)
        if (lastInteractionAt <= 0L) return false
        return now - lastInteractionAt >= LONG_IDLE_THRESHOLD_MS
    }

    private fun checkCriticalAppExits(now: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (now - lastExitCheckMs < 5 * 60_000L) return
        lastExitCheckMs = now

        val prefs = applicationContext.getSharedPreferences("argos_agent_intelligence", Context.MODE_PRIVATE)
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val packages = listOf("com.mupa.player.enterprise", packageName)
        for (pkg in packages) {
            val lastTs = prefs.getLong("exit_last_ts_$pkg", 0L).coerceAtLeast(0L)
            val exits = runCatching { am.getHistoricalProcessExitReasons(pkg, 0, 5) }.getOrDefault(emptyList())
            var maxSeen = lastTs
            for (e in exits) {
                val ts = e.timestamp
                if (ts <= lastTs) continue
                if (ts > maxSeen) maxSeen = ts
                val reason = e.reason
                val r = when (reason) {
                    android.app.ApplicationExitInfo.REASON_ANR -> "anr"
                    android.app.ApplicationExitInfo.REASON_CRASH -> "crash"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "native_crash"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "user"
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
                    else -> "other"
                }
                val detail = "pkg=$pkg reason=$r status=${e.status} pss=${e.pss} rss=${e.rss} ts=$ts"
                emitIncidentSnapshot(reason = "app_exit_$r", details = detail)
            }
            if (maxSeen > lastTs) prefs.edit().putLong("exit_last_ts_$pkg", maxSeen).apply()
        }
    }

    private fun ensureGeofence(settings: SettingsManager, now: Long) {
        if (now - lastGeofenceCheckMs < 60_000L) return
        lastGeofenceCheckMs = now

        val cfg = settings.getGeofenceConfigCached() ?: return
        val loc = readBestLastKnownLocation() ?: return
        val results = FloatArray(1)
        Location.distanceBetween(loc.latitude, loc.longitude, cfg.lat, cfg.lng, results)
        val distanceM = results[0].toDouble()
        val inside = distanceM <= cfg.radiusM
        val wasInside = geofenceWasInside
        geofenceWasInside = inside
        if (wasInside == null) return
        if (inside) return
        if (now - lastGeofenceEventMs < 5 * 60_000L) return
        lastGeofenceEventMs = now

        val deviceId = DeviceIdentityManager.getPersistentDeviceId(applicationContext)
        val event = JSONObject()
            .put("ts", now)
            .put("type", "geofence_exit")
            .put("device_id", deviceId)
            .put("distance_m", distanceM)
            .put("radius_m", cfg.radiusM)
            .put("center_lat", cfg.lat)
            .put("center_lng", cfg.lng)
            .put(
                "location",
                JSONObject()
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("accuracy_m", loc.accuracy.toDouble())
                    .put("time_ms", loc.time),
            )
            .put("actions", org.json.JSONArray(cfg.actions.toList()))

        if (FirebaseRuntimeMode.allowAgentPublish && deviceId.isNotBlank()) {
            val ref = FirebaseDatabase.getInstance().getReference("m_argos/devices").child(deviceId)
            runCatching { ref.child("alerts").push().setValue(jsonToFirebaseValue(event)) }
            runCatching { ref.child("intelligence").child("geofence").child("events").push().setValue(jsonToFirebaseValue(event)) }
        }

        if (cfg.actions.contains("intensive_tracking") || cfg.actions.contains("lost_mode")) {
            runCatching { kotlinx.coroutines.runBlocking { settings.setLocationTelemetryIntervalMin(1) } }
        }
        if (cfg.actions.contains("lock") || cfg.actions.contains("lost_mode")) {
            val dpm = DeviceOwnerPolicyManager(applicationContext)
            if (dpm.isDeviceOwner(packageName)) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        settings.setMdmLocked(true)
                        settings.setKioskMode(true)
                    }
                }
                runCatching { dpm.applyLocked(packageName, settings.getAllowedPackagesCached()) }
                requestLockTaskStart()
            }
        }

        emitIncidentSnapshot(reason = "geofence_exit", details = "distance_m=$distanceM")
    }

    private fun ensureMainHeartbeat() {
        if (mainHeartbeatStarted) return
        mainHeartbeatStarted = true
        mainHandler.post(object : Runnable {
            override fun run() {
                lastMainHeartbeatMs = System.currentTimeMillis()
                mainHandler.postDelayed(this, 2000L)
            }
        })
    }

    private fun readBestLastKnownLocation(): Location? {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val l = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null) {
                best = l
                continue
            }
            val newer = l.time > best.time
            val betterAcc = l.accuracy < best.accuracy
            if (newer && betterAcc) best = l
            else if (newer && (best.time + 120_000L) < l.time) best = l
        }
        return best
    }

    private fun emitIncidentSnapshot(reason: String, details: String) {
        val now = System.currentTimeMillis()
        val deviceId = DeviceIdentityManager.getPersistentDeviceId(applicationContext)
        val snapshot = telemetry.buildReplaySnapshot(reason = reason, details = details)
            .put("device_id", deviceId)
        if (FirebaseRuntimeMode.allowAgentPublish && deviceId.isNotBlank()) {
            val ref = FirebaseDatabase.getInstance().getReference("m_argos/devices").child(deviceId)
            runCatching { ref.child("intelligence").child("incidents").push().setValue(jsonToFirebaseValue(snapshot)) }
        }
        val supa = JSONObject()
            .put("device_id", deviceId)
            .put("created_at_epoch_ms", now)
            .put("reason", reason)
            .put("details", details)
            .put("payload_json", snapshot.toString())
        telemetry.postToSupabaseIfConfigured("rest/v1/agent_incidents", supa)
    }

    private fun jsonToFirebaseValue(any: Any?): Any? {
        return when (any) {
            null -> null
            JSONObject.NULL -> null
            is JSONObject -> {
                val map = HashMap<String, Any?>()
                val it = any.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    map[k] = jsonToFirebaseValue(any.opt(k))
                }
                map
            }
            is org.json.JSONArray -> {
                val list = ArrayList<Any?>()
                for (i in 0 until any.length()) list.add(jsonToFirebaseValue(any.opt(i)))
                list
            }
            is Number, is Boolean, is String -> any
            else -> any.toString()
        }
    }

    private fun ensureRemoteSupportAppsInstalled() {
        val now = System.currentTimeMillis()
        if (remoteSupportInstalled) return
        if (now - lastEnsureRemoteSupportMs < 60_000L) return
        lastEnsureRemoteSupportMs = now

        if (!hasInternet(applicationContext)) return

        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return

        val anyDeskPkg = "com.anydesk.anydeskandroid"
        val pluginPkg = "com.anydesk.adcontrol.ad1"

        val installer = ApkInstallManager(applicationContext)

        if (!isInstalled(anyDeskPkg)) {
            val res = installer.install(
                ApkInstallRequest(
                    url = ANYDESK_URL,
                    expectedPackage = anyDeskPkg,
                    expectedVersion = null,
                    force = false,
                    silent = true,
                    autoOpen = false,
                    sha256 = null,
                ),
            )
            if (res.status != "success" && res.status != "ignored") return
        }

        if (!isInstalled(pluginPkg)) {
            val res = installer.install(
                ApkInstallRequest(
                    url = ANYDESK_PLUGIN_AD1_URL,
                    expectedPackage = pluginPkg,
                    expectedVersion = null,
                    force = false,
                    silent = true,
                    autoOpen = false,
                    sha256 = null,
                ),
            )
            if (res.status != "success" && res.status != "ignored") return
        }

        remoteSupportInstalled = isInstalled(anyDeskPkg) && isInstalled(pluginPkg)
    }

    private fun isInstalled(packageName: String): Boolean {
        val pm = applicationContext.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        }.getOrDefault(false)
    }

    private val remoteHealthManager by lazy { com.mupa.agent.argos.remote.RemoteHealthManager(applicationContext) }

    private fun ensureArgosRemoteHealthy() {
        try {
            remoteHealthManager.runIfDue()
        } catch (e: Exception) {
            Log.w("ArgosForegroundSvc", "Remote health check error: ${e.message}")
        }
    }

    private fun ensureWifiSetupIfOffline() {
        val online = hasInternet(applicationContext)
        val settings = SettingsManager(applicationContext)
        if (settings.getMaintenanceModeEnabledCached()) return
        if (settings.getTemporaryUnlockUntilMsCached() > System.currentTimeMillis()) return
        val finalized = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (!finalized) return

        val dpm = DeviceOwnerPolicyManager(applicationContext)
        val isOwner = dpm.isDeviceOwner(packageName)
        if (online) {
            if (wifiAssistNeedsRelock && isOwner) {
                dpm.updateLockTaskPackages(packageName, settings.getAllowedPackagesCached() + "com.android.settings")
                requestLockTaskStart()
            }
            wifiAssistNeedsRelock = false
            return
        }

        val now = System.currentTimeMillis()
        if (now - wifiAssistLastOpenMs < WIFI_ASSIST_COOLDOWN_MS) return
        wifiAssistLastOpenMs = now
        if (isOwner) {
            dpm.updateLockTaskPackages(packageName, settings.getAllowedPackagesCached() + "com.android.settings")
            requestLockTaskStop()
            wifiAssistNeedsRelock = true
        }
        startActivity(WifiManager(applicationContext).openWifiSettings())
    }

    private fun ensureBaselineRestrictions() {
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        if (!dpm.isDeviceOwner(packageName)) return
        dpm.applyBaselineRestrictions(packageName)
    }

    private fun ensureMaintenanceOrTemporaryUnlock() {
        val settings = SettingsManager(applicationContext)
        val now = System.currentTimeMillis()
        val until = settings.getTemporaryUnlockUntilMsCached()
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        val isOwner = dpm.isDeviceOwner(packageName)

        val maintenanceEnabled = settings.getMaintenanceModeEnabledCached()
        val maintenanceUntil = settings.getMaintenanceModeUntilMsCached()
        if (maintenanceEnabled) {
            if (maintenanceUntil > 0L && now > maintenanceUntil) {
                runCatching { kotlinx.coroutines.runBlocking { settings.setMaintenanceMode(false, 0L) } }
            } else if (isOwner) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        settings.setMdmLocked(false)
                        settings.setKioskMode(false)
                    }
                }
                runCatching { dpm.applyUnlocked(packageName) }
                runCatching { dpm.setAndroidLauncherAsHome(packageName) }
                requestLockTaskStop()
            }
            return
        }

        if (until > 0L && now > until) {
            val restoreLocked = settings.getTemporaryUnlockPrevMdmLockedCached()
            val restoreKiosk = settings.getTemporaryUnlockPrevKioskModeCached()
            runCatching { kotlinx.coroutines.runBlocking { settings.clearTemporaryUnlock() } }
            if (isOwner && (restoreLocked || restoreKiosk)) {
                runCatching {
                    kotlinx.coroutines.runBlocking {
                        settings.setMdmLocked(restoreLocked)
                        settings.setKioskMode(restoreKiosk)
                    }
                }
                runCatching { dpm.setLauncherAsHome(packageName, enabled = true) }
                runCatching { dpm.updateLockTaskPackages(packageName, settings.getAllowedPackagesCached()) }
                runCatching { dpm.applyLocked(packageName, settings.getAllowedPackagesCached()) }
                requestLockTaskStart()
            }
        } else if (until > now && isOwner) {
            runCatching { dpm.setAndroidLauncherAsHome(packageName) }
            requestLockTaskStop()
        }
    }

    private fun requestLockTaskStart() {
        val i = Intent("com.mupa.agent.argos.action.LOCKTASK_START")
            .setClassName(packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(i) }
    }

    private fun requestLockTaskStop() {
        val i = Intent("com.mupa.agent.argos.action.LOCKTASK_STOP")
            .setClassName(packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { startActivity(i) }
    }

    private fun ensureFloatingRecoveryButton() {
        removeFloatingRecoveryButton()
        return
        @Suppress("UNREACHABLE_CODE")
        if (!recoveryOverlayEnabled) {
            removeFloatingRecoveryButton()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(applicationContext)) {
                removeFloatingRecoveryButton()
                return
            }
        }
        if (overlayTapView != null) return

        mainHandler.post {
            if (overlayTapView != null) return@post
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val sizePx = (48f * resources.displayMetrics.density).toInt().coerceAtLeast(32)
            val marginPx = (12f * resources.displayMetrics.density).toInt().coerceAtLeast(0)

            val view = ImageView(this).apply {
                alpha = 0.85f
                setImageResource(android.R.drawable.ic_menu_manage)
                setPadding(marginPx / 2, marginPx / 2, marginPx / 2, marginPx / 2)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#B91C1C"))
                    setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), Color.parseColor("#FFFFFF"))
                }
                setOnClickListener { onRecoveryTap() }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = marginPx
                y = marginPx
            }

            runCatching {
                wm.addView(view, params)
                overlayTapView = view
            }
        }
    }

    private fun updateRecoveryOverlayEnabled(enabled: Boolean) {
        if (recoveryOverlayEnabled == enabled) return
        recoveryOverlayEnabled = enabled
        if (!enabled) {
            removeFloatingRecoveryButton()
        } else {
            ensureFloatingRecoveryButton()
        }
    }

    private fun removeFloatingRecoveryButton() {
        val tap = overlayTapView
        overlayTapView = null
        val dialog = overlayDialogView
        overlayDialogView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (dialog != null) runCatching { wm.removeView(dialog) }
            if (tap != null) runCatching { wm.removeView(tap) }
        }
    }

    private fun onRecoveryTap() {
        val now = System.currentTimeMillis()
        recoveryTapTimes.addLast(now)
        while (recoveryTapTimes.isNotEmpty() && now - recoveryTapTimes.first() > 3_000L) {
            recoveryTapTimes.removeFirst()
        }
        if (recoveryTapTimes.size >= 5) {
            recoveryTapTimes.clear()
            showRecoveryPasswordOverlay()
        }
    }

    private fun showRecoveryPasswordOverlay() {
        if (overlayDialogView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val pad = (16f * density).toInt()
        val cardPad = (14f * density).toInt()
        val spacing = (10f * density).toInt()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#88000000"))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(cardPad, cardPad, cardPad, cardPad)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(Color.parseColor("#FF0B1220"))
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
            }
        }

        val title = TextView(this).apply {
            text = "Área Administrativa Agent Argos"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        val subtitle = TextView(this).apply {
            text = "Digite sua senha"
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 14f
        }
        val input = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#94A3B8"))
            hint = "Senha"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(Color.parseColor("#141B2D"))
                setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
            }
            setPadding(pad, pad, pad, pad)
        }
        val error = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#FCA5A5"))
            textSize = 13f
            visibility = View.GONE
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = Button(this).apply {
            text = "Cancelar"
            setOnClickListener { dismissRecoveryPasswordOverlay() }
        }
        val ok = Button(this).apply {
            text = "Desbloquear"
            setOnClickListener {
                val pwd = input.text?.toString().orEmpty()
                scope.launch(Dispatchers.IO) {
                    val serial = runCatching { com.mupa.agent.argos.managers.DeviceIdentityManager(applicationContext).getPersistentId().trim() }
                        .getOrDefault("")
                    val auth = com.mupa.agent.argos.security.SecurityAccessManager(applicationContext)
                    val result = auth.authenticate(password = pwd, deviceSerial = serial, nowMs = System.currentTimeMillis())
                    mainHandler.post {
                        when (result) {
                            is com.mupa.agent.argos.security.SecurityAccessResult.Success -> {
                                dismissRecoveryPasswordOverlay()
                                val intent = Intent(this@ArgosForegroundService, ArgosLauncherActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    .putExtra(ArgosLauncherActivity.EXTRA_NAV_ROUTE, Routes.DeviceSettings)
                                runCatching { startActivity(intent) }
                            }
                            is com.mupa.agent.argos.security.SecurityAccessResult.LockedOut -> {
                                error.text = "Bloqueado por ${result.secondsRemaining}s"
                                error.visibility = View.VISIBLE
                            }
                            is com.mupa.agent.argos.security.SecurityAccessResult.Failure -> {
                                error.text = "Senha incorreta (${result.failedAttempts}/3)"
                                error.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }
        }
        row.addView(cancel)
        row.addView(ok)

        fun addSpace(px: Int) {
            card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) })
        }

        card.addView(title)
        addSpace((6f * density).toInt())
        card.addView(subtitle)
        addSpace(spacing)
        card.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addSpace((8f * density).toInt())
        card.addView(error)
        addSpace(spacing)
        card.addView(row)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = (18f * density).toInt()
            rightMargin = (18f * density).toInt()
        }
        root.addView(card, cardParams)
        root.setOnClickListener { }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            wm.addView(root, params)
            overlayDialogView = root
            input.requestFocus()
        } catch (_: BadTokenException) {
            overlayDialogView = null
        }
    }

    private fun dismissRecoveryPasswordOverlay() {
        val view = overlayDialogView ?: return
        overlayDialogView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
        }
    }

    /**
     * Devices in kiosk/lock-task mode keep the pinned content app (MPlayer) forced back to
     * the foreground at the OS level — the agent's own Activity can never just "stay on
     * screen" there, no matter how autostart is delayed. Same TYPE_APPLICATION_OVERLAY
     * technique as showRecoveryPasswordOverlay() above is the only way to put the
     * registration wizard in front of a locked kiosk app. Shown/dismissed from the same
     * periodic sweep that already calls ensureFloatingRecoveryButton().
     */
    private fun ensureRegistrationOverlay() {
        val settings = SettingsManager(applicationContext)
        val needed = settings.getArgosDeviceTokenCached().isNotBlank() && settings.getDeviceApelidoInternoCached().isBlank()
        if (!needed) {
            dismissRegistrationOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(applicationContext)) return
        if (registrationOverlayView != null) return

        mainHandler.post {
            if (registrationOverlayView != null) return@post
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            val pad = (16f * density).toInt()
            val cardPad = (16f * density).toInt()
            val spacing = (10f * density).toInt()

            val serial = runCatching {
                com.mupa.agent.argos.managers.DeviceIdentityManager.getPersistentDeviceId(applicationContext).trim()
            }.getOrDefault("")

            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#CC000000"))
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(cardPad, cardPad, cardPad, cardPad)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(Color.parseColor("#FF0B1220"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
            }
            val title = TextView(this).apply {
                text = "Cadastro do terminal"
                setTextColor(Color.WHITE)
                textSize = 18f
            }
            val subtitle = TextView(this).apply {
                text = "Este aparelho ainda não foi nomeado. Preencha pra aparecer certo no ARGOS Web."
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 13f
            }
            fun field(hintText: String) = EditText(this).apply {
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#94A3B8"))
                hint = hintText
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(Color.parseColor("#141B2D"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
                setPadding(pad, pad, pad, pad)
            }
            val apelidoInput = field("Apelido (ex: Caixa 01)")
            val empresaInput = field("Empresa")
            val filialInput = field("Número da filial")
            val error = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
                visibility = View.GONE
            }
            val save = Button(this).apply {
                text = "Salvar"
                setOnClickListener {
                    val apelido = apelidoInput.text?.toString()?.trim().orEmpty()
                    if (apelido.isBlank()) {
                        error.text = "Informe pelo menos o apelido."
                        error.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    isEnabled = false
                    scope.launch(Dispatchers.IO) {
                        val result = com.mupa.agent.argos.network.AgentControlApi(applicationContext, settings).registerDevice(
                            serial = serial,
                            apelidoInterno = apelido,
                            empresa = empresaInput.text?.toString()?.trim().orEmpty(),
                            numFilial = filialInput.text?.toString()?.trim().orEmpty(),
                        )
                        mainHandler.post {
                            if (result.ok) {
                                dismissRegistrationOverlay()
                            } else {
                                isEnabled = true
                                error.text = result.error ?: "Falha ao salvar."
                                error.visibility = View.VISIBLE
                            }
                        }
                    }
                }
            }

            fun addSpace(px: Int) {
                card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) })
            }
            card.addView(title)
            addSpace((6f * density).toInt())
            card.addView(subtitle)
            addSpace(spacing)
            card.addView(apelidoInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(empresaInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(filialInput, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(error)
            addSpace(spacing)
            card.addView(save)

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = (18f * density).toInt()
                rightMargin = (18f * density).toInt()
            }
            root.addView(card, cardParams)
            root.setOnClickListener { }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }
            try {
                wm.addView(root, params)
                registrationOverlayView = root
                apelidoInput.requestFocus()
            } catch (_: BadTokenException) {
                registrationOverlayView = null
            }
        }
    }

    private fun dismissRegistrationOverlay() {
        val view = registrationOverlayView ?: return
        registrationOverlayView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
        }
    }

    private fun ensureGeofenceLockOverlay() {
        val settings = SettingsManager(applicationContext)
        val geofenceLocked = settings.isGeofenceLockedCached()
        val hoursLocked = settings.isOperatingHoursLockedCached()
        if (!geofenceLocked && !hoursLocked) {
            dismissGeofenceLockOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(applicationContext)) return
        if (geofenceLockOverlayView != null) return

        mainHandler.post {
            if (geofenceLockOverlayView != null) return@post
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val density = resources.displayMetrics.density
            val pad = (16f * density).toInt()
            val cardPad = (16f * density).toInt()
            val spacing = (10f * density).toInt()

            val fenceName = settings.getGeofenceLockedFenceNameCached().ifBlank { "Perímetro" }
            val lockReason = if (settings.isOperatingHoursLockedCached() && !settings.isGeofenceLockedCached()) {
                val start = settings.getOperatingHoursStartCached()
                val end = settings.getOperatingHoursEndCached()
                "Fora do horário de funcionamento ($start - $end)"
            } else {
                "Fora do perímetro: $fenceName"
            }

            val root = FrameLayout(this).apply {
                setBackgroundColor(Color.parseColor("#EE000000"))
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(cardPad, cardPad, cardPad, cardPad)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 16f * density
                    setColor(Color.parseColor("#FF0B1220"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
            }

            val iconText = TextView(this).apply {
                text = "🔒"
                textSize = 36f
                gravity = Gravity.CENTER
            }
            val title = TextView(this).apply {
                text = "Dispositivo Bloqueado"
                setTextColor(Color.WHITE)
                textSize = 18f
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val subtitle = TextView(this).apply {
                text = lockReason
                setTextColor(Color.parseColor("#CBD5E1"))
                textSize = 14f
                gravity = Gravity.CENTER
            }
            val input = EditText(this).apply {
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#94A3B8"))
                hint = "Senha de desbloqueio"
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 12f * density
                    setColor(Color.parseColor("#141B2D"))
                    setStroke((1f * density).toInt().coerceAtLeast(1), Color.parseColor("#334155"))
                }
                setPadding(pad, pad, pad, pad)
            }
            val error = TextView(this).apply {
                text = ""
                setTextColor(Color.parseColor("#FCA5A5"))
                textSize = 13f
                gravity = Gravity.CENTER
                visibility = View.GONE
            }
            val ok = Button(this).apply {
                text = "Desbloquear"
                setOnClickListener {
                    val typed = input.text?.toString().orEmpty()
                    val expected = settings.getGeofenceUnlockPasswordCached()
                    val masterOk = com.mupa.agent.argos.managers.MaintenanceUnlock.validate(typed)
                    if (typed == expected || masterOk) {
                        scope.launch(Dispatchers.IO) {
                            settings.setGeofenceLocked(false)
                            settings.setOperatingHoursLocked(false)
                        }
                        dismissGeofenceLockOverlay()
                    } else {
                        error.text = "Senha incorreta"
                        error.visibility = View.VISIBLE
                    }
                }
            }

            fun addSpace(px: Int) {
                card.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, px) })
            }
            card.addView(iconText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(spacing)
            card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((4f * density).toInt())
            card.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(spacing)
            card.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace((8f * density).toInt())
            card.addView(error, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addSpace(spacing)
            card.addView(ok, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val cardParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                leftMargin = (24f * density).toInt()
                rightMargin = (24f * density).toInt()
            }
            root.addView(card, cardParams)
            root.setOnClickListener { }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
            }
            try {
                wm.addView(root, params)
                geofenceLockOverlayView = root
                input.requestFocus()
            } catch (_: BadTokenException) {
                geofenceLockOverlayView = null
            }
        }
    }

    private fun dismissGeofenceLockOverlay() {
        val view = geofenceLockOverlayView ?: return
        geofenceLockOverlayView = null
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
        }
    }

    private fun ensureFirebaseConnectedListener() {
        if (!shouldUseLegacyRealtime() || !FirebaseRuntimeMode.allowFirebaseConnectivityTracking) {
            firebaseConnected = true
            return
        }
        if (firebaseInfoListener != null) return
        val ref = FirebaseDatabase.getInstance().getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                firebaseConnected = snapshot.getValue(Boolean::class.java) == true
            }

            override fun onCancelled(error: DatabaseError) {
                firebaseConnected = false
            }
        }
        firebaseInfoListener = listener
        ref.addValueEventListener(listener)
    }

    private fun shouldUseLegacyRealtime(): Boolean {
        // Only Firebase runtime config is required. The realtime manager's legacy command
        // listener (m_argos/devices/{id}/commands) executes commands directly and reports
        // back to Firebase logs — it does NOT need argos_api_url. Devices enrolled via group
        // linking (TV boxes) never get an argos_api_url, so gating on the HTTP API here left
        // them unable to receive any Firebase command (terminal, reboot, WiFi push, etc.).
        return hasFirebaseRuntimeConfig()
    }

    private fun updateOfflineSnapshot() {
        val settings = SettingsManager(applicationContext)
        val now = System.currentTimeMillis()
        val internetOk = hasInternet(applicationContext)
        if (internetOk && now - offlineLastApiCheckMs > 30_000L) {
            offlineLastApiCheckMs = now
            offlineApiOk = checkApiQuick(settings.getArgosApiUrlCached())
        } else if (!internetOk) {
            offlineApiOk = false
        }
        val firebaseOk = firebaseConnected
        val apiOk = if (settings.getArgosApiUrlCached().trim().isBlank()) true else offlineApiOk
        val state = when {
            !internetOk -> "offline"
            internetOk && firebaseOk && apiOk -> "online"
            else -> "unstable"
        }
        kotlinx.coroutines.runBlocking {
            settings.setOfflineSnapshot(
                state = state,
                internetOk = internetOk,
                firebaseOk = firebaseOk,
                apiOk = apiOk,
                nowMs = now,
            )
        }
    }

    private fun checkApiQuick(argosApiUrl: String): Boolean {
        val base = argosApiUrl.trim().trimEnd('/')
        if (base.isBlank()) return true
        val url = if (base.endsWith("/api", ignoreCase = true)) base else "$base/api"
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 2000
                readTimeout = 2000
                setRequestProperty("User-Agent", "ArgosAgent/1.0")
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..399
        }.getOrDefault(false)
    }

    private fun hasInternet(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val CHANNEL_ID = "argos_agent"
        private const val NOTIFICATION_ID = 1101
        private const val PERSIST_CHECK_INTERVAL_MS = 10_000L
        private const val MIN_LOOP_DELAY_MS = 1_000L
        private const val OPERATIONAL_SWEEP_INTERVAL_MS = 60_000L
        private const val PROVISIONING_SWEEP_INTERVAL_MS = 60_000L
        private const val HEARTBEAT_CHECK_INTERVAL_MS = 60_000L
        private const val COMMAND_POLL_INTERVAL_NORMAL_MS = 60_000L
        private const val COMMAND_POLL_INTERVAL_REMOTE_MS = 15_000L
        private const val COMMAND_POLL_INTERVAL_UPDATE_MS = 5_000L
        private const val COMMAND_POLL_INTERVAL_CRITICAL_MS = 1_000L
        private const val COMMAND_POLL_INTERVAL_IDLE_MS = 120_000L
        private const val LONG_IDLE_THRESHOLD_MS = 30 * 60_000L
        private const val CRITICAL_COMMAND_PRIORITY = 4
        private const val WIFI_ASSIST_COOLDOWN_MS = 5 * 60_000L
        private const val ANYDESK_URL = "https://pub-51a41b32f55f47d180e51ed6679732af.r2.dev/anydesk.apk"
        private const val ANYDESK_PLUGIN_AD1_URL = "https://pub-51a41b32f55f47d180e51ed6679732af.r2.dev/adcontrol-ad1.apk"
        const val ACTION_BOOT_COMPLETED_STARTUP = "com.mupa.agent.argos.action.BOOT_COMPLETED_STARTUP"
        const val ACTION_RECOVERY_OVERLAY_STATE = "com.mupa.agent.argos.action.RECOVERY_OVERLAY_STATE"
        const val EXTRA_RECOVERY_OVERLAY_ENABLED = "enabled"
        const val ACTION_FORCE_PRESENCE_SYNC = "com.mupa.agent.argos.action.FORCE_PRESENCE_SYNC"
    }
}
