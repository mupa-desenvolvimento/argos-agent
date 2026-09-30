package com.mupa.agent.argos

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mupa.agent.argos.argoscore.ArgosCoreClient
import com.mupa.agent.argos.commands.ArgosCommandScheduler
import com.mupa.agent.argos.core.AgentRuntime
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.DeviceProfileManager
import com.mupa.agent.argos.managers.MeferiRemoteAuthorizer
import com.mupa.agent.argos.managers.PlayerAppConstants
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.permissions.PermissionManager
import com.mupa.agent.argos.services.WatchdogWorker
import com.mupa.agent.argos.telemetry.TelemetryManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.runBlocking
import com.mupa.agent.argos.models.AgentDeviceFamily
import java.util.concurrent.TimeUnit

class ArgosApplication : Application() {
    override fun attachBaseContext(base: Context) {
        val cfg = base.resources.configuration
        val clamped = Configuration(cfg).apply { fontScale = cfg.fontScale.coerceAtMost(1.0f) }
        super.attachBaseContext(base.createConfigurationContext(clamped))
    }

    override fun onCreate() {
        super.onCreate()
        // Enable TLS 1.3 on old devices: Android 9 X96 boxes only ship TLS 1.2, but the
        // ARGOS web edge (Lovable/Cloudflare) requires TLS 1.3 minimum, so enrollment and
        // any HTTPS call to argos.mupa.app fail with SSLHandshakeException without this.
        runCatching {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
        }.onFailure { Log.w("ArgosApplication", "Conscrypt init failed, TLS 1.3 unavailable", it) }
        ensureFirebaseInitialized()
        runCatching { FirebaseDatabase.getInstance().setPersistenceEnabled(true) }
        runCatching { applyTemporaryManagedDefaults(this) }
        runCatching {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                runCatching { TelemetryManager(this).recordCrash(t.name, e) }
                previous?.uncaughtException(t, e) ?: runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
            }
        }
        Thread {
            val pm = PermissionManager(this)
            val settings = SettingsManager(this)
            runCatching {
                val profile = DeviceProfileManager().resolve()
                Log.i("ArgosApplication", "device_profile=${profile.family} manufacturer=${profile.manufacturer} model=${profile.model}")
            }

            runCatching { applyTemporaryManagedDefaults(this) }

            // Pre-authorize MediaProjection for remote-access packages on Meferi MC45
            runCatching { MeferiRemoteAuthorizer.authorize(this) }

            val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
            val dpm = DeviceOwnerPolicyManager(this)
            val selfPkg = packageName
            if (dpm.isDeviceOwner(selfPkg)) {
                dpm.applyBaselineRestrictions(selfPkg)
                dpm.tryUnsuspendApp(selfPkg, PlayerAppConstants.resolveInstalledPackage(this))
                if (locked) dpm.updateLockTaskPackages(selfPkg, settings.getAllowedPackagesCached())
            }

            if (pm.criticalReady()) {
                ArgosCommandScheduler.ensurePeriodic(this)
                AgentRuntime.bootstrap(this)
                // Fase 0 do ARGOS Core — no-op em qualquer dispositivo onde
                // ArgosCoreConfig não foi explicitamente ligado via ADB.
                runCatching { ArgosCoreClient.startIfEnabled(this) }
                // Acesso remoto (ver remoteaccess/) — no-op se o aparelho
                // ainda não tiver um token (ver GroupLinkScreen, que o obtém
                // sozinho ao vincular a um grupo); em reinícios seguintes,
                // isto reconecta sem precisar vincular de novo.
                runCatching { com.mupa.agent.argos.remoteaccess.net.RemoteConnectionService.startIfEnrolled(this) }
                val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES).build()
                WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                    WatchdogWorker.UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    workRequest,
                )
            }
        }.start()
    }

    private fun ensureFirebaseInitialized() {
        val existing = runCatching { FirebaseApp.getApps(this).firstOrNull() }.getOrNull()
        if (existing != null) return

        val defaultInit = runCatching { FirebaseApp.initializeApp(this) }.getOrNull()
        if (defaultInit != null) return

        val appId = BuildConfig.FIREBASE_APP_ID.trim()
        val apiKey = BuildConfig.FIREBASE_API_KEY.trim()
        val projectId = BuildConfig.FIREBASE_PROJECT_ID.trim()
        val databaseUrl = BuildConfig.FIREBASE_DATABASE_URL.trim()
        if (appId.isBlank() || apiKey.isBlank() || projectId.isBlank() || databaseUrl.isBlank()) return

        val options = FirebaseOptions.Builder()
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setProjectId(projectId)
            .setDatabaseUrl(databaseUrl)
            .build()

        FirebaseApp.initializeApp(this, options)
    }

    companion object {
        private const val DEFAULT_COMPANY_ID = "local-bypass"
        private const val DEFAULT_COMPANY_NAME = "ARGOS Local"
        private const val DEFAULT_COMPANY_CODE = "BYPASS"
        private const val DEFAULT_GROUP_NAME = "default"
        private const val DEFAULT_DEVICE_TYPE = "mplayer"
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val ANYDESK_PACKAGE = "com.anydesk.anydeskandroid"
private val ZEBRA_DATAWEDGE_PACKAGES = setOf("com.symbol.datawedge", "com.zebra.datawedge")
        private const val PERSIST_TIMEOUT_MS = 30_000L

        fun applyTemporaryManagedDefaults(context: Context) {
            val appContext = context.applicationContext
            val settings = SettingsManager(appContext)
            val selfPkg = appContext.packageName
            val deviceProfile = DeviceProfileManager().resolve()
            val deviceId = DeviceIdentityManager.getPersistentDeviceId(appContext).trim()
            val manufacturer = android.os.Build.MANUFACTURER?.trim().orEmpty()
            val model = android.os.Build.MODEL?.trim().orEmpty()
            val nickname = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ")
                .ifBlank { "Terminal ${deviceId.takeLast(6)}" }

            val installedOptional = listOf(ANYDESK_PACKAGE).filter { pkg ->
                runCatching { appContext.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }.toSet()

            runBlocking {
                if (!settings.isDeviceBoundCached() && deviceId.isNotBlank()) {
                    settings.setBoundCompany(
                        companyId = DEFAULT_COMPANY_ID,
                        companyName = DEFAULT_COMPANY_NAME,
                        companyCode = DEFAULT_COMPANY_CODE,
                    )
                    settings.setBoundDevice(
                        deviceId = deviceId,
                        group = DEFAULT_GROUP_NAME,
                        token = "",
                    )
                    settings.setBoundDeviceDetails(
                        nickname = nickname,
                        branch = "",
                        type = DEFAULT_DEVICE_TYPE,
                    )
                }

                // Whitelist and autostart are managed by ConfigurationEngine (Firebase group config).
                // Don't inject hardcoded apps here — WhitelistManager.ensureFixedApps() handles
                // mandatory entries (Argos + hidden AnyDesk/Settings for lock task).
                settings.setAutostartDelayMs(0L)
                settings.setPersistModeEnabled(true)
                settings.setPersistModeTimeoutMs(PERSIST_TIMEOUT_MS)
            }
        }
    }
}
