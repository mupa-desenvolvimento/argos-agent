package com.mupa.agent.argos.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mupa.agent.argos.commands.ArgosCommandScheduler
import com.mupa.agent.argos.launcher.WatchdogManager
import com.mupa.agent.argos.permissions.PermissionManager
import com.mupa.agent.argos.services.ArgosForegroundService
import com.mupa.agent.argos.ui.ArgosLauncherActivity

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pm = PermissionManager(context.applicationContext)
        if (!pm.criticalReady()) return

        ArgosCommandScheduler.ensurePeriodic(context.applicationContext)
        val serviceIntent = Intent(context.applicationContext, ArgosForegroundService::class.java)
            .setAction(ArgosForegroundService.ACTION_BOOT_COMPLETED_STARTUP)
        ContextCompat.startForegroundService(context.applicationContext, serviceIntent)
        val i = Intent(context.applicationContext, ArgosLauncherActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.applicationContext.startActivity(i) }
        WatchdogManager(context.applicationContext).runNow()
    }
}
