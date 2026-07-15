package com.mupa.agent.argos.remote

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.content.pm.PackageManager
import org.json.JSONObject

class RemoteHealthManager(context: Context) {
    private val appContext = context.applicationContext
    private val permissions = RemotePermissionManager(appContext)

    companion object {
        private const val TAG = "RemoteHealthMgr"
        const val REMOTE_PKG = "com.mupa.argos.remote"
        private const val HEALTH_URI = "content://com.mupa.argos.remote.status/health"
        private const val CHECK_INTERVAL_MS = 60_000L

        @Volatile
        private var lastCheckMs = 0L

        @Volatile
        private var lastHealthSnapshot: JSONObject? = null

        @Volatile
        private var consecutiveFailures = 0
    }

    fun runIfDue(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCheckMs < CHECK_INTERVAL_MS) return false
        lastCheckMs = now
        return runNow()
    }

    fun runNow(): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        val result = JSONObject()
        var allOk = true

        try {
            // 1. Check installed
            val installed = try {
                appContext.packageManager.getPackageInfo(REMOTE_PKG, 0); true
            } catch (_: PackageManager.NameNotFoundException) { false }
            result.put("installed", installed)
            if (!installed) {
                Log.w(TAG, "Remote not installed, provisioning will handle")
                result.put("status", "not_installed")
                logResult("health_check", "not_installed", result, t0)
                return false
            }

            // 2. Query health via ContentProvider
            val health = queryHealthProvider()
            result.put("provider_reachable", health != null)
            if (health != null) {
                lastHealthSnapshot = health
                consecutiveFailures = 0
                result.put("remote_health", health)
            } else {
                consecutiveFailures++
                result.put("consecutive_failures", consecutiveFailures)
            }

            // 3. Check & fix accessibility
            val accessOk = permissions.isRemoteAccessibilityEnabled()
            result.put("accessibility_enabled", accessOk)
            if (!accessOk) {
                allOk = false
                val fixed = permissions.enableRemoteAccessibility()
                result.put("accessibility_fix_attempted", true)
                result.put("accessibility_fix_result", fixed)
                if (fixed) {
                    Log.i(TAG, "Accessibility enabled automatically")
                } else {
                    Log.w(TAG, "Accessibility could not be enabled automatically, opening settings")
                    permissions.openAccessibilitySettings()
                }
            } else if (health != null && !health.optBoolean("accessibility_active", true)) {
                Log.w(TAG, "Accessibility enabled in settings but not active (crashed?), toggling")
                val recovered = permissions.recoverCrashedAccessibility()
                result.put("accessibility_crash_recovery", recovered)
                allOk = false
            }

            // 4. Check & fix battery optimization
            val batteryOptimized = permissions.isRemoteBatteryOptimized()
            result.put("battery_optimized", batteryOptimized)
            if (batteryOptimized) {
                allOk = false
                val exempted = permissions.exemptRemoteFromBattery()
                result.put("battery_fix_attempted", true)
                result.put("battery_fix_result", exempted)
            }

            // 5. Enable auto-consent
            val autoConsent = health?.optBoolean("auto_consent", false) ?: false
            if (!autoConsent) {
                permissions.enableAutoConsent()
                result.put("auto_consent_sent", true)
            }

            // 7. Check service running & socket connected
            val socketConnected = health?.optBoolean("socket_connected", false) ?: false
            val serviceRunning = health?.optBoolean("service_running", false) ?: false
            result.put("socket_connected", socketConnected)
            result.put("service_running", serviceRunning)

            if (!serviceRunning || (!socketConnected && consecutiveFailures >= 3)) {
                Log.w(TAG, "Remote service unhealthy, restarting (failures=$consecutiveFailures)")
                permissions.restartRemoteService()
                result.put("restart_triggered", true)
            }

            // 8. If provider unreachable for 5+ checks, force launch
            if (health == null && consecutiveFailures >= 5) {
                Log.w(TAG, "Remote unreachable for $consecutiveFailures checks, force launching")
                permissions.launchRemote()
                result.put("force_launch", true)
                consecutiveFailures = 0
            }

            // 9. Apply OEM fixes (runs once, idempotent checks inside)
            permissions.applyManufacturerFixes()

            result.put("status", if (allOk) "healthy" else "recovering")

        } catch (e: Exception) {
            Log.e(TAG, "Health check error: ${e.message}")
            result.put("status", "error")
            result.put("error", e.message)
            allOk = false
        }

        logResult("health_check", if (allOk) "success" else "partial", result, t0)
        return allOk
    }

    fun getLastHealthSnapshot(): JSONObject? = lastHealthSnapshot

    fun getHealthSummary(): JSONObject {
        val summary = JSONObject()
        val installed = try { appContext.packageManager.getPackageInfo(REMOTE_PKG, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        summary.put("installed", installed)
        summary.put("permissions", permissions.toJsonSnapshot())

        val health = lastHealthSnapshot
        if (health != null) {
            summary.put("version", health.optString("version_name", ""))
            summary.put("version_code", health.optInt("version_code", 0))
            summary.put("socket_connected", health.optBoolean("socket_connected", false))
            summary.put("accessibility_active", health.optBoolean("accessibility_active", false))
            summary.put("projection_granted", health.optBoolean("projection_granted", false))
            summary.put("auto_consent", health.optBoolean("auto_consent", false))
            summary.put("session_active", health.optBoolean("session_active", false))
        }

        summary.put("consecutive_failures", consecutiveFailures)
        summary.put("last_check_ms", lastCheckMs)
        return summary
    }

    private fun queryHealthProvider(): JSONObject? {
        return try {
            val uri = Uri.parse(HEALTH_URI)
            val cursor: Cursor? = appContext.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val json = it.getString(0)
                    JSONObject(json)
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Health provider query failed: ${e.message}")
            null
        }
    }

    private fun logResult(command: String, status: String, payload: JSONObject, startElapsed: Long) {
        val durationMs = SystemClock.elapsedRealtime() - startElapsed
        Log.i(TAG, "$command -> $status (${durationMs}ms)")
    }
}
