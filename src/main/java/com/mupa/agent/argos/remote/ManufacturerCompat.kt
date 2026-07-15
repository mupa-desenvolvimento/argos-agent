package com.mupa.agent.argos.remote

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

object ManufacturerCompat {
    private const val TAG = "ManufacturerCompat"

    data class OemIntent(
        val label: String,
        val intent: Intent,
    )

    fun getBatteryOptimizationIntents(context: Context): List<OemIntent> {
        val mfg = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<OemIntent>()

        when {
            mfg.contains("samsung") -> {
                intents += OemIntent("Samsung Battery", Intent().setComponent(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                ))
                intents += OemIntent("Samsung Sleep", Intent().setComponent(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.AppSleepSettingActivity")
                ))
            }
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") -> {
                intents += OemIntent("Xiaomi Battery Saver", Intent().setComponent(
                    ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                ))
                intents += OemIntent("Xiaomi Security", Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                ))
            }
            mfg.contains("huawei") || mfg.contains("honor") -> {
                intents += OemIntent("Huawei Startup", Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                ))
                intents += OemIntent("Huawei Battery", Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                ))
            }
            mfg.contains("oppo") || mfg.contains("realme") || mfg.contains("oneplus") -> {
                intents += OemIntent("ColorOS AutoStart", Intent().setComponent(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                ))
                intents += OemIntent("ColorOS Battery", Intent().setComponent(
                    ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
                ))
            }
            mfg.contains("vivo") -> {
                intents += OemIntent("Vivo Battery", Intent().setComponent(
                    ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                ))
            }
            mfg.contains("motorola") || mfg.contains("lenovo") -> {
                // Standard Android — no special OEM battery killer
            }
        }

        return intents.filter { it.intent.resolveActivity(context.packageManager) != null }
    }

    fun getAutoStartIntents(context: Context): List<OemIntent> {
        val mfg = Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<OemIntent>()

        when {
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") -> {
                intents += OemIntent("Xiaomi AutoStart", Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                ))
            }
            mfg.contains("huawei") || mfg.contains("honor") -> {
                intents += OemIntent("Huawei Startup", Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                ))
            }
            mfg.contains("oppo") || mfg.contains("realme") || mfg.contains("oneplus") -> {
                intents += OemIntent("ColorOS AutoStart", Intent().setComponent(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                ))
            }
            mfg.contains("vivo") -> {
                intents += OemIntent("Vivo AutoStart", Intent().setComponent(
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                ))
            }
            mfg.contains("samsung") -> {
                intents += OemIntent("Samsung Sleeping Apps", Intent().setComponent(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.AppSleepSettingActivity")
                ))
            }
        }

        return intents.filter { it.intent.resolveActivity(context.packageManager) != null }
    }

    fun isEnterpriseDevice(): Boolean {
        val mfg = Build.MANUFACTURER.lowercase()
        val model = Build.MODEL.lowercase()
        return mfg.contains("zebra") ||
            mfg.contains("honeywell") ||
            mfg.contains("bluebird") ||
            mfg.contains("urovo") ||
            mfg.contains("gertec") ||
            mfg.contains("newland") ||
            mfg.contains("datalogic") ||
            mfg.contains("meferi") ||
            model.contains("mc45") ||
            model.contains("et4") ||
            model.contains("tc2") ||
            model.contains("sk100") ||
            model.contains("sk210")
    }

    fun logDeviceProfile() {
        Log.i(TAG, "Manufacturer=${Build.MANUFACTURER} Model=${Build.MODEL} Brand=${Build.BRAND} " +
            "SDK=${Build.VERSION.SDK_INT} Enterprise=${isEnterpriseDevice()}")
    }
}
