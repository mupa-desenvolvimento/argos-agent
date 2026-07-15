package com.mupa.agent.argos.admin

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager

/**
 * Headless activity that triggers DevicePolicyManager.wipeData(0).
 * Called from the ARGOS Installer via:
 *   adb shell am start -n com.mupa.agent.argos/.admin.FactoryResetActivity --ez confirm true
 *
 * Requires Device Owner. Bypasses the ADB broadcast security block because
 * starting an exported activity with ADB shell requires physical USB access
 * (same trust level as `adb shell dpm wipe-data`).
 */
class FactoryResetActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val confirm = intent?.getBooleanExtra("confirm", false) ?: false
        if (!confirm) {
            Log.w(TAG, "factory_reset_aborted: missing confirm=true extra")
            finish()
            return
        }
        val dpm = DeviceOwnerPolicyManager(this)
        val err = dpm.factoryResetError(packageName)
        if (err != null) {
            Log.e(TAG, "factory_reset_failed: $err")
            finish()
            return
        }
        Log.i(TAG, "factory_reset_executing")
        // wipeData doesn't return — device reboots immediately
    }

    companion object {
        private const val TAG = "FactoryResetActivity"
    }
}
