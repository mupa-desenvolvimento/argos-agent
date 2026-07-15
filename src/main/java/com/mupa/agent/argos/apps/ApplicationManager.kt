package com.mupa.agent.argos.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Base64
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ApplicationManager(private val context: Context) {
    fun isInstalled(packageName: String): Boolean {
        return runCatching {
            context.packageManager.getPackageInfo(packageName.trim(), 0)
            true
        }.getOrDefault(false)
    }

    /**
     * Apps you can actually open from a launcher — excludes system services/libraries with
     * no UI. Shared by CommandDispatcher's `list_installed_apps` command and the periodic
     * Firebase publish in HeartbeatService, so the on-demand command and the
     * always-fresh inventory the web console reads use the exact same enumeration.
     */
    fun listLaunchableApps(includeSystem: Boolean = false): JSONArray {
        val pm = context.packageManager
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        val items = apps.mapNotNull { ai ->
            val pkg = ai.packageName?.trim().orEmpty()
            if (pkg.isBlank()) return@mapNotNull null
            val isSystem = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            val launchable = pm.getLaunchIntentForPackage(pkg) != null
            if (!launchable) return@mapNotNull null
            if (isSystem && !includeSystem) return@mapNotNull null
            val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(pkg)
            val versionName = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty()
            val versionCode = runCatching {
                val info = pm.getPackageInfo(pkg, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
            }.getOrDefault(0L)
            val iconB64 = runCatching {
                val drawable = pm.getApplicationIcon(ai)
                val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, 48, 48)
                drawable.draw(canvas)
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                bmp.recycle()
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            }.getOrNull()
            JSONObject()
                .put("package", pkg)
                .put("label", label)
                .put("version", versionName)
                .put("versionName", versionName)
                .put("versionCode", versionCode)
                .put("is_system", isSystem)
                .put("icon", iconB64 ?: JSONObject.NULL)
        }.sortedBy { it.optString("label").lowercase() }
        return JSONArray(items)
    }

    fun open(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return false
        DeviceOwnerPolicyManager(context).tryUnsuspendApp(context.packageName, pkg)
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun close(packageName: String): String? {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return "package_empty"
        return DeviceOwnerPolicyManager(context).tryStopApp(context.packageName, pkg)
    }
}

