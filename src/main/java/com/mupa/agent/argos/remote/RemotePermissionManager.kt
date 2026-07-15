package com.mupa.agent.argos.remote

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.mupa.agent.argos.mdm.ArgosDeviceAdminReceiver
import org.json.JSONObject

class RemotePermissionManager(private val context: Context) {
    private val appContext = context.applicationContext

    companion object {
        private const val TAG = "RemotePermMgr"
        const val REMOTE_PKG = "com.mupa.argos.remote"
        const val REMOTE_ACCESSIBILITY_SERVICE =
            "$REMOTE_PKG/com.mupa.argos.remote.control.RemoteAccessibilityService"
    }

    private fun isDeviceOwner(): Boolean {
        val dpm = appContext.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return runCatching { dpm.isDeviceOwnerApp(appContext.packageName) }.getOrDefault(false)
    }

    // ── Accessibility Service ────────────────────────────────────────────────

    fun isRemoteAccessibilityEnabled(): Boolean {
        val enabled = runCatching {
            Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!enabled) return false

        val services = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?.lowercase().orEmpty()
        }.getOrDefault("")

        return services.contains(REMOTE_ACCESSIBILITY_SERVICE.lowercase())
    }

    fun enableRemoteAccessibility(): Boolean {
        if (isRemoteAccessibilityEnabled()) return true

        if (isDeviceOwner()) {
            return enableAccessibilityViaSecureSettings()
        }

        // Fallback: try shell command (works if we have WRITE_SECURE_SETTINGS via ADB)
        return tryShellEnableAccessibility()
    }

    private fun enableAccessibilityViaSecureSettings(): Boolean {
        return try {
            val existing = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val components = existing.split(':').map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            components += REMOTE_ACCESSIBILITY_SERVICE

            Settings.Secure.putString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                components.joinToString(":")
            )
            Settings.Secure.putInt(
                appContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "Accessibility enabled via secure settings")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to enable accessibility via settings: ${e.message}")
            false
        }
    }

    private fun tryShellEnableAccessibility(): Boolean {
        return try {
            val existing = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val components = existing.split(':').map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            components += REMOTE_ACCESSIBILITY_SERVICE
            val newValue = components.joinToString(":")

            Runtime.getRuntime().exec(arrayOf(
                "sh", "-c",
                "settings put secure enabled_accessibility_services '$newValue' && settings put secure accessibility_enabled 1"
            )).waitFor()

            // Verify
            Thread.sleep(500)
            val check = isRemoteAccessibilityEnabled()
            Log.i(TAG, "Shell enable accessibility result=$check")
            check
        } catch (e: Exception) {
            Log.w(TAG, "Shell enable accessibility failed: ${e.message}")
            false
        }
    }

    fun recoverCrashedAccessibility(): Boolean {
        if (!isRemoteAccessibilityEnabled()) return false
        try {
            val existing = Settings.Secure.getString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val without = existing.split(':')
                .filter { !it.equals(REMOTE_ACCESSIBILITY_SERVICE, ignoreCase = true) }
                .joinToString(":")
            Settings.Secure.putString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                without
            )
            Thread.sleep(2000)
            Settings.Secure.putString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "$without:$REMOTE_ACCESSIBILITY_SERVICE"
            )
            Thread.sleep(1000)
            Log.i(TAG, "Accessibility toggled to recover from crash")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Crashed accessibility recovery failed: ${e.message}")
            return false
        }
    }

    fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open accessibility settings: ${e.message}")
        }
    }

    // ── Battery Optimization ─────────────────────────────────────────────────

    fun isRemoteBatteryOptimized(): Boolean {
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !runCatching { pm.isIgnoringBatteryOptimizations(REMOTE_PKG) }.getOrDefault(false)
    }

    fun exemptRemoteFromBattery(): Boolean {
        if (!isRemoteBatteryOptimized()) return true

        // Device Owner: try via shell
        if (isDeviceOwner()) {
            try {
                Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c", "dumpsys deviceidle whitelist +$REMOTE_PKG"
                )).waitFor()
                Thread.sleep(300)
                if (!isRemoteBatteryOptimized()) {
                    Log.i(TAG, "Battery exemption granted via shell")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shell battery exempt failed: ${e.message}")
            }
        }

        Log.d(TAG, "Battery exemption not granted via shell, skipping UI prompt to avoid loop")
        return false
    }

    // ── Overlay (SYSTEM_ALERT_WINDOW) ────────────────────────────────────────

    fun isRemoteOverlayGranted(): Boolean {
        return try {
            val appOps = appContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val uid = appContext.packageManager.getApplicationInfo(REMOTE_PKG, 0).uid
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, REMOTE_PKG)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, uid, REMOTE_PKG)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun grantRemoteOverlay(): Boolean {
        if (isRemoteOverlayGranted()) return true

        if (isDeviceOwner()) {
            try {
                Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c", "appops set $REMOTE_PKG SYSTEM_ALERT_WINDOW allow"
                )).waitFor()
                Thread.sleep(300)
                if (isRemoteOverlayGranted()) {
                    Log.i(TAG, "Overlay granted via shell")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Shell overlay grant failed: ${e.message}")
            }
        }

        Log.d(TAG, "Overlay not granted, skipping UI prompt to avoid loop")
        return false
    }

    // ── Auto-Consent (via broadcast to Remote) ───────────────────────────────

    fun enableAutoConsent() {
        try {
            val intent = Intent("com.mupa.argos.remote.action.SET_AUTO_CONSENT")
                .setPackage(REMOTE_PKG)
                .putExtra("enabled", true)
            appContext.sendBroadcast(intent)
            Log.i(TAG, "Auto-consent enable broadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send auto-consent broadcast: ${e.message}")
        }
    }

    // ── Restart Remote Service ───────────────────────────────────────────────

    fun restartRemoteService() {
        try {
            val intent = Intent("com.mupa.argos.remote.action.RESTART_SERVICE")
                .setPackage(REMOTE_PKG)
            appContext.sendBroadcast(intent)
            Log.i(TAG, "Restart broadcast sent")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send restart broadcast: ${e.message}")
        }
    }

    fun launchRemote() {
        try {
            val launchIntent = appContext.packageManager.getLaunchIntentForPackage(REMOTE_PKG)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                appContext.startActivity(launchIntent)
                Log.i(TAG, "Remote launched")
            } else {
                // Try broadcast
                val intent = Intent("com.mupa.argos.remote.action.LAUNCH")
                    .setPackage(REMOTE_PKG)
                appContext.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch Remote: ${e.message}")
        }
    }

    // ── OEM-specific battery/autostart ───────────────────────────────────────

    fun applyManufacturerFixes() {
        if (ManufacturerCompat.isEnterpriseDevice()) {
            Log.i(TAG, "Enterprise device detected, standard Android battery management")
            return
        }

        val batteryIntents = ManufacturerCompat.getBatteryOptimizationIntents(appContext)
        val autoStartIntents = ManufacturerCompat.getAutoStartIntents(appContext)

        if (batteryIntents.isNotEmpty() || autoStartIntents.isNotEmpty()) {
            Log.i(TAG, "OEM-specific intents available: battery=${batteryIntents.size} autostart=${autoStartIntents.size}")
        }
    }

    // ── Snapshot ─────────────────────────────────────────────────────────────

    fun toJsonSnapshot(): JSONObject {
        return JSONObject()
            .put("accessibility_enabled", isRemoteAccessibilityEnabled())
            .put("battery_exempt", !isRemoteBatteryOptimized())
            .put("overlay_granted", isRemoteOverlayGranted())
            .put("enterprise_device", ManufacturerCompat.isEnterpriseDevice())
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
    }
}
