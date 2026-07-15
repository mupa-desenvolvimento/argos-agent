package com.mupa.agent.argos.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.mupa.agent.argos.services.ArgosForegroundService
import com.mupa.agent.argos.ui.ArgosLauncherActivity
import androidx.core.content.ContextCompat

class ArgosDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Called by the system right after Device Owner provisioning finishes — whether
     * triggered by `dpm set-device-owner` (ADB) or by the QR-code/zero-touch managed
     * provisioning flow during initial device setup. Without this, the device sits as a
     * freshly-provisioned Device Owner with nothing running until someone manually opens
     * the app; this kicks the agent's existing first-run setup (ProvisioningManager, via
     * ArgosForegroundService) immediately instead of waiting for that.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ArgosForegroundService::class.java))
        }
        runCatching {
            val launch = Intent(context, ArgosLauncherActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launch)
        }
    }

    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, ArgosDeviceAdminReceiver::class.java)
    }
}
