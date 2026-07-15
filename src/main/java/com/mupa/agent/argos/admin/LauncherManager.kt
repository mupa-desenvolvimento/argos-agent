package com.mupa.agent.argos.admin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.ui.ArgosLauncherActivity

class LauncherManager(private val context: Context) {
    fun currentHomePackage(): String {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolve?.activityInfo?.packageName.orEmpty()
    }

    fun isArgosDefaultHome(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolve = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        val pkg = resolve?.activityInfo?.packageName.orEmpty()
        val cls = resolve?.activityInfo?.name.orEmpty()
        val expected = ComponentName(context, ArgosLauncherActivity::class.java)
        return pkg == expected.packageName && cls == expected.className
    }

    fun setArgosAsDefaultHomeIfDeviceOwner(): String? {
        val dpm = DeviceOwnerPolicyManager(context)
        if (!dpm.isDeviceOwner(context.packageName)) return "not_device_owner"
        dpm.setLauncherAsHome(context.packageName, enabled = true)
        return null
    }

    fun restoreAndroidHomeIfDeviceOwner(): String? {
        val dpm = DeviceOwnerPolicyManager(context)
        if (!dpm.isDeviceOwner(context.packageName)) return "not_device_owner"
        return dpm.setAndroidLauncherAsHome(context.packageName)
    }
}
