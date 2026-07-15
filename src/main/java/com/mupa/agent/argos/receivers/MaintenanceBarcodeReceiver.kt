package com.mupa.agent.argos.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MaintenanceBarcodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_BARCODE_TRIGGER) return
        val barcode = extractBarcode(intent)
        if (!barcode.equals(MAINTENANCE_BARCODE, ignoreCase = true)) return

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val appContext = context.applicationContext
            val settings = SettingsManager(appContext)
            val dpm = DeviceOwnerPolicyManager(appContext)
            runCatching {
                settings.clearTemporaryUnlock()
                settings.setMaintenanceMode(true, 0L)
                settings.setMdmLocked(false)
                settings.setKioskMode(false)

                if (dpm.isDeviceOwner(appContext.packageName)) {
                    dpm.applyUnlocked(appContext.packageName)
                    runCatching { dpm.setAndroidLauncherAsHome(appContext.packageName) }
                    requestLockTaskStop(appContext)
                    goAndroidHome(appContext)
                }
                Log.i(TAG, "maintenance_enabled_from_barcode")
            }.onFailure { error ->
                Log.w(TAG, "maintenance_barcode_failed ${error.javaClass.simpleName}:${error.message.orEmpty()}")
            }
            pending.finish()
        }
    }

    private fun extractBarcode(intent: Intent): String {
        val extras = listOf(
            EXTRA_BARCODE,
            "data",                              // MEFERI ScanWizard (MEF_ACTION)
            "code",
            "scan_data",
            "data_string",
            "com.symbol.datawedge.data_string",
        )
        extras.firstNotNullOfOrNull { key ->
            intent.getStringExtra(key)?.trim()?.takeIf { it.isNotBlank() }
        }?.let { return it }
        return intent.dataString?.trim().orEmpty()
    }

    private fun requestLockTaskStop(context: Context) {
        val intent = Intent("com.mupa.agent.argos.action.LOCKTASK_STOP")
            .setClassName(context.packageName, "com.mupa.agent.argos.ui.LockTaskBridgeActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    private fun goAndroidHome(context: Context) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    companion object {
        private const val TAG = "MaintBarcodeRcvr"
        const val ACTION_BARCODE_TRIGGER = "com.mupa.agent.argos.action.BARCODE_TRIGGER"
        const val EXTRA_BARCODE = "barcode"
        const val MAINTENANCE_BARCODE = "settings_mupa"
    }
}
