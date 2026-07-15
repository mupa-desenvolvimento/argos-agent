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

/**
 * Receives MEFERI ScanWizard scan events (android.intent.action.MEF_ACTION, extra "data").
 *
 * Declared as a separate class from MaintenanceBarcodeReceiver because Android's manifest
 * merger rejects duplicate receiver entries for the same class. This receiver is intentionally
 * exported WITHOUT a custom permission so the ScanWizard system service can deliver intents
 * to it — system apps cannot hold app-defined permissions.
 *
 * Mirrors the maintenance-barcode logic: if the scanned value matches the maintenance
 * barcode sentinel, kiosk/lock-task is released and maintenance mode is enabled.
 */
class MeferiScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != MEF_ACTION) return
        val barcode = intent.getStringExtra("data")?.trim().orEmpty()
        if (barcode.isBlank()) return

        Log.d(TAG, "meferi_scan barcode=${barcode.take(32)}")

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
                Log.i(TAG, "maintenance_enabled_from_meferi_scan")
            }.onFailure { e ->
                Log.w(TAG, "meferi_scan_maintenance_failed ${e.javaClass.simpleName}:${e.message.orEmpty()}")
            }
            pending.finish()
        }
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
        private const val TAG = "MeferiScanReceiver"
        private const val MEF_ACTION = "android.intent.action.MEF_ACTION"
        private const val MAINTENANCE_BARCODE = MaintenanceBarcodeReceiver.MAINTENANCE_BARCODE
    }
}
