package com.mupa.agent.argos.managers

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

object MeferiRemoteAuthorizer {

    private const val TAG = "MeferiRemoteAuth"

    private const val OP_PROJECT_MEDIA = 46
    private const val MODE_ALLOWED = 0

    private val REMOTE_PACKAGES = listOf(
        "com.mupa.argos.remote",
        "com.anydesk.anydeskandroid",
    )

    private val PERMISSIONS_TO_GRANT = listOf(
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.POST_NOTIFICATIONS",
    )

    fun isMeferiMC45(): Boolean {
        val model = Build.MODEL.orEmpty().lowercase()
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        return model.contains("mc45") || manufacturer.contains("meferi")
    }

    fun authorize(context: Context) {
        if (!isMeferiMC45()) {
            Log.d(TAG, "Not a Meferi MC45 — skipping")
            return
        }
        Log.i(TAG, "Meferi MC45 detected (${Build.MANUFACTURER} ${Build.MODEL}). Pre-authorizing remote packages…")

        val installed = REMOTE_PACKAGES.filter { isPackageInstalled(context, it) }
        Log.i(TAG, "Installed remote packages: $installed")
        if (installed.isEmpty()) return

        authorizeViaAppOpsManager(context, installed)
        authorizeViaMeferiSdkService(context, installed)
        grantPermissionsViaDpm(context, installed)
    }

    private fun authorizeViaAppOpsManager(context: Context, packages: List<String>) {
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val setMode = appOps.javaClass.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType,
            )

            for (pkg in packages) {
                try {
                    val uid = context.packageManager.getApplicationInfo(pkg, 0).uid
                    setMode.invoke(appOps, OP_PROJECT_MEDIA, uid, pkg, MODE_ALLOWED)
                    Log.i(TAG, "AppOpsManager.setMode PROJECT_MEDIA → ALLOWED for $pkg (uid=$uid)")
                } catch (e: Exception) {
                    Log.w(TAG, "AppOpsManager.setMode failed for $pkg: ${e.javaClass.simpleName} ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AppOpsManager reflection failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    private fun authorizeViaMeferiSdkService(context: Context, packages: List<String>) {
        try {
            val intent = android.content.Intent("com.meferi.sdkservice.ACTION_EXECUTE_COMMAND")
            intent.setPackage("com.meferi.sdkservice")
            for (pkg in packages) {
                try {
                    val cmd = "appops set $pkg PROJECT_MEDIA allow"
                    intent.putExtra("command", cmd)
                    context.sendBroadcast(intent)
                    Log.i(TAG, "Sent Meferi SDK broadcast for $pkg: $cmd")
                } catch (e: Exception) {
                    Log.w(TAG, "Meferi SDK broadcast failed for $pkg: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Meferi SDK broadcast setup failed: ${e.message}")
        }
    }

    private fun grantPermissionsViaDpm(context: Context, packages: List<String>) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, Class.forName("com.mupa.agent.argos.mdm.ArgosDeviceAdminReceiver"))

            if (!dpm.isDeviceOwnerApp(context.packageName)) {
                Log.w(TAG, "Not device owner, skipping DPM permission grants")
                return
            }

            for (pkg in packages) {
                for (perm in PERMISSIONS_TO_GRANT) {
                    try {
                        val granted = dpm.setPermissionGrantState(
                            admin, pkg, perm,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                        )
                        if (granted) Log.i(TAG, "DPM granted $perm to $pkg")
                        else Log.d(TAG, "DPM grant $perm to $pkg returned false")
                    } catch (e: Exception) {
                        Log.d(TAG, "DPM grant $perm to $pkg failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DPM permission grants failed: ${e.message}")
        }
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: Exception) {
            false
        }
    }
}
