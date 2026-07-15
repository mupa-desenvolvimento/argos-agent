package com.mupa.agent.argos.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mupa.agent.argos.ArgosApplication
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.services.ArgosForegroundService
import com.mupa.agent.argos.ui.compose.ArgosApp
import com.mupa.agent.argos.ui.compose.Routes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.content.Intent
import android.view.WindowManager
import androidx.core.content.ContextCompat

class ArgosLauncherActivity : ComponentActivity() {
    private var pendingRoute: String? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // If the process holding the locked task died (e.g. killed for memory while MPlayer,
        // not this activity, was the visible foreground app within the lock-task group),
        // requestLockTaskStop()'s intent recreates this activity via onCreate, not onNewIntent
        // — must handle the stop request here too, otherwise it's silently dropped and the
        // device stays in kiosk mode forever.
        if (intent?.action == ACTION_LOCKTASK_STOP) {
            runCatching { stopLockTask() }
            // fall through to the normal launcher UI below instead of finishing — there's no
            // reason to leave the screen blank just because this instance happened to be
            // (re)created by the unlock request rather than a normal launch.
        }
        runCatching { ArgosApplication.applyTemporaryManagedDefaults(applicationContext) }
        val settings = SettingsManager(applicationContext)
        if (!settings.isDeviceBoundCached() || settings.getBoundCompanyIdCached() == "local-bypass") {
            runCatching {
                startActivity(
                    Intent(this, SetupWizardActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            }
            finish()
            return
        }
        pendingRoute = resolveRequestedRoute(intent)
        setContent {
            ArgosApp(
                initialRoute = pendingRoute ?: Routes.Launcher,
                pendingRoute = pendingRoute,
                onRouteHandled = { pendingRoute = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // requestLockTaskStop() (CommandDispatcher) targets this activity directly instead of
        // a separate bridge activity — stopLockTask() only ends lock task mode for the task
        // it's called from, and the actually-locked task root is this activity (singleTask),
        // not whatever new/separate task a bridge activity would've landed in. That mismatch
        // was the real reason "Desativar Kiosk" silently did nothing: the call ran, just on
        // the wrong task.
        if (intent.action == ACTION_LOCKTASK_STOP) {
            runCatching { stopLockTask() }
            return
        }
        pendingRoute = resolveRequestedRoute(intent)
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            val i =
                Intent(this, ArgosForegroundService::class.java)
                    .setAction(ArgosForegroundService.ACTION_RECOVERY_OVERLAY_STATE)
                    .putExtra(ArgosForegroundService.EXTRA_RECOVERY_OVERLAY_ENABLED, true)
            ContextCompat.startForegroundService(this, i)
        }
        val settings = SettingsManager(applicationContext)
        val keepAwake = settings.shouldKeepAwakeNow()
        if (keepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
        applySystemBarsHidden(locked)
        if (!locked) return
        val isDeviceOwner = DeviceOwnerPolicyManager(applicationContext).isDeviceOwner(packageName)
        if (!isDeviceOwner) return
        val dpm = DeviceOwnerPolicyManager(applicationContext)
        dpm.updateLockTaskPackages(packageName, settings.getAllowedPackagesCached())
        dpm.tryUnsuspendApp(packageName, "com.mupa.player.enterprise")
        runCatching { startLockTask() }
    }

    override fun onPause() {
        super.onPause()
        runCatching {
            val i =
                Intent(this, ArgosForegroundService::class.java)
                    .setAction(ArgosForegroundService.ACTION_RECOVERY_OVERLAY_STATE)
                    .putExtra(ArgosForegroundService.EXTRA_RECOVERY_OVERLAY_ENABLED, false)
            ContextCompat.startForegroundService(this, i)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val settings = SettingsManager(applicationContext)
        val locked = settings.getMdmLockedCached() || settings.getKioskModeCached()
        applySystemBarsHidden(locked)
    }

    private fun applySystemBarsHidden(hidden: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun resolveRequestedRoute(intent: Intent?): String? {
        val i = intent ?: return null
        return i.getStringExtra(EXTRA_NAV_ROUTE)?.trim()?.ifBlank { null }
    }

    companion object {
        const val EXTRA_NAV_ROUTE = "argos_nav_route"
        const val ACTION_LOCKTASK_STOP = "com.mupa.agent.argos.action.LOCKTASK_STOP"
    }
}
