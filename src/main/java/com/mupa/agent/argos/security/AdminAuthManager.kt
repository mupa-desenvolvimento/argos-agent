package com.mupa.agent.argos.security

import android.content.Context
import com.mupa.agent.argos.managers.SettingsManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject

sealed class AdminAuthResult {
    data class Success(val nowMs: Long) : AdminAuthResult()
    data class LockedOut(val secondsRemaining: Int) : AdminAuthResult()
    data class Failure(val failedAttempts: Int, val remainingBeforeLock: Int) : AdminAuthResult()
}

class AdminAuthManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)

    suspend fun authenticate(password: String, nowMs: Long = System.currentTimeMillis()): AdminAuthResult {
        ensureInitialized()
        val lockUntil = settings.getAdminLockUntilMs()
        if (lockUntil > nowMs) {
            val sec = ((lockUntil - nowMs) / 1000L).toInt().coerceAtLeast(1)
            return AdminAuthResult.LockedOut(sec)
        }

        val salt = settings.getAdminPasswordSalt()
        val expected = settings.getAdminPasswordHash()
        val computed = sha256Hex("${salt.trim()}|${password.trim()}")
        if (computed == expected) {
            settings.setAdminFailedAttempts(0)
            settings.setAdminLockUntilMs(0L)
            settings.setAdminLastSuccessMs(nowMs)
            return AdminAuthResult.Success(nowMs)
        }

        val currentFails = settings.getAdminFailedAttempts() + 1
        val remaining = (MAX_ATTEMPTS - currentFails).coerceAtLeast(0)
        if (currentFails >= MAX_ATTEMPTS) {
            settings.setAdminFailedAttempts(0)
            settings.setAdminLockUntilMs(nowMs + LOCKOUT_MS)
            return AdminAuthResult.LockedOut((LOCKOUT_MS / 1000L).toInt())
        }
        settings.setAdminFailedAttempts(currentFails)
        return AdminAuthResult.Failure(failedAttempts = currentFails, remainingBeforeLock = remaining)
    }

    suspend fun ensureInitialized() {
        val existingHash = settings.getAdminPasswordHash().trim()
        val existingSalt = settings.getAdminPasswordSalt().trim()
        if (existingHash.isNotBlank() && existingSalt.isNotBlank()) return

        settings.setAdminPasswordSecret(DEFAULT_SALT, DEFAULT_HASH)
        settings.setAdminFailedAttempts(0)
        settings.setAdminLockUntilMs(0L)
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(((b.toInt() shr 4) and 0x0F).toString(16))
            sb.append((b.toInt() and 0x0F).toString(16))
        }
        return sb.toString()
    }

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        SecureRandom().nextBytes(bytes)
        val sb = StringBuilder(byteCount * 2)
        for (b in bytes) {
            sb.append(((b.toInt() shr 4) and 0x0F).toString(16))
            sb.append((b.toInt() and 0x0F).toString(16))
        }
        return sb.toString()
    }

    companion object {
        private const val DEFAULT_SALT = "argos_admin_default_v1"
        private const val DEFAULT_HASH = "645cd69c15f291536c2519cef599b77b1dfe3e938adb64a2e3cf173335c5ff24"
        private const val MAX_ATTEMPTS = 3
        private const val LOCKOUT_MS = 30_000L
    }
}

sealed class SecurityAccessResult {
    data class Success(val nowMs: Long, val method: String) : SecurityAccessResult()
    data class LockedOut(val secondsRemaining: Int) : SecurityAccessResult()
    data class Failure(val failedAttempts: Int, val remainingBeforeLock: Int) : SecurityAccessResult()
}

class SecurityAccessManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val audit = AdminAuditLogStore(appContext)
    private val dynamic = DynamicPasswordManager()

    suspend fun authenticate(
        password: String,
        deviceSerial: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SecurityAccessResult {
        val lockUntil = settings.getRecoveryLockUntilMs()
        if (lockUntil > nowMs) {
            val sec = ((lockUntil - nowMs) / 1000L).toInt().coerceAtLeast(1)
            logAttempt(success = false, method = "locked_out", deviceSerial = deviceSerial, nowMs = nowMs, extra = JSONObject().put("seconds", sec))
            return SecurityAccessResult.LockedOut(sec)
        }

        val p = password.trim()
        val serial = deviceSerial.trim()
        val masterOk = sha256Hex("${MASTER_SALT}|$p") == MASTER_HASH
        val dynamicOk = serial.isNotBlank() && dynamic.isValid(password = p, deviceSerial = serial, nowMs = nowMs)

        if (masterOk || dynamicOk) {
            settings.setRecoveryFailedAttempts(0)
            settings.setRecoveryLockUntilMs(0L)
            logAttempt(success = true, method = if (masterOk) "master" else "dynamic", deviceSerial = serial, nowMs = nowMs, extra = JSONObject())
            return SecurityAccessResult.Success(nowMs, method = if (masterOk) "master" else "dynamic")
        }

        val currentFails = settings.getRecoveryFailedAttempts() + 1
        val remaining = (MAX_ATTEMPTS - currentFails).coerceAtLeast(0)
        val total = settings.getRecoveryTotalFailures() + 1
        settings.setRecoveryTotalFailures(total)

        if (total >= SUSPICIOUS_THRESHOLD && total % SUSPICIOUS_THRESHOLD == 0) {
            audit.add(
                action = "suspicious_recovery_unlock",
                success = false,
                payload = JSONObject()
                    .put("action", "suspicious_recovery_unlock")
                    .put("device", serial)
                    .put("failures_total", total)
                    .put("ts", nowMs),
            )
        }

        if (currentFails >= MAX_ATTEMPTS) {
            settings.setRecoveryFailedAttempts(0)
            settings.setRecoveryLockUntilMs(nowMs + LOCKOUT_MS)
            logAttempt(success = false, method = "invalid_password", deviceSerial = serial, nowMs = nowMs, extra = JSONObject().put("lockout_ms", LOCKOUT_MS))
            return SecurityAccessResult.LockedOut((LOCKOUT_MS / 1000L).toInt())
        }
        settings.setRecoveryFailedAttempts(currentFails)
        logAttempt(success = false, method = "invalid_password", deviceSerial = serial, nowMs = nowMs, extra = JSONObject().put("failed_attempts", currentFails))
        return SecurityAccessResult.Failure(failedAttempts = currentFails, remainingBeforeLock = remaining)
    }

    private fun logAttempt(success: Boolean, method: String, deviceSerial: String, nowMs: Long, extra: JSONObject) {
        audit.add(
            action = "recovery_unlock",
            success = success,
            payload = JSONObject()
                .put("action", "recovery_unlock")
                .put("success", success)
                .put("method", method)
                .put("device", deviceSerial.trim())
                .put("ts", nowMs)
                .put("extra", extra),
        )
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(((b.toInt() shr 4) and 0x0F).toString(16))
            sb.append((b.toInt() and 0x0F).toString(16))
        }
        return sb.toString()
    }

    companion object {
        private const val MASTER_SALT = "argos_master_v1"
        private const val MASTER_HASH = "0ed1c00603083d1b073dafc6ecd4377cd9465e465910b69d5ff84274eae32a0a"
        private const val MAX_ATTEMPTS = 3
        private const val LOCKOUT_MS = 30_000L
        private const val SUSPICIOUS_THRESHOLD = 10
    }
}

class DynamicPasswordManager {
    fun generate(deviceSerial: String, nowMs: Long, secret: String = DEFAULT_SECRET): String {
        val serial = deviceSerial.trim().lowercase(Locale.ROOT)
        val bucket = (nowMs / WINDOW_MS)
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        val hour = SimpleDateFormat("HH", Locale.US).format(Date(nowMs))
        val msg = "$serial|$date|$hour|$bucket"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(msg.toByteArray(Charsets.UTF_8))
        var acc = 0L
        for (i in 0 until 8) {
            acc = (acc shl 8) or (bytes[i].toLong() and 0xFF)
        }
        val code = (acc % 1_000_000L).toInt().coerceIn(0, 999_999)
        return code.toString().padStart(6, '0')
    }

    fun isValid(password: String, deviceSerial: String, nowMs: Long, secret: String = DEFAULT_SECRET): Boolean {
        val p = password.trim()
        if (p.length !in 4..12) return false
        val current = generate(deviceSerial, nowMs, secret)
        if (p == current) return true
        val prev = generate(deviceSerial, nowMs - WINDOW_MS, secret)
        return p == prev
    }

    companion object {
        private const val WINDOW_MS = 5 * 60_000L
        private const val DEFAULT_SECRET = "ARGOS_SECRET"
    }
}
