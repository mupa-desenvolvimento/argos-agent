package com.mupa.agent.argos.managers

import android.net.Uri
import android.util.Log
import com.mupa.agent.argos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GroupEnrollResult(
    val groupId: String,
    val groupName: String,
    val companyId: String,
    val type: String,
    val linkingCode: String,
)

/**
 * Talks to the ARGOS web enrollment endpoint:
 *   GET  {base}/api/groups/enroll?code=ABCD-1234   → resolve a code (no side effects)
 *   POST {base}/api/groups/enroll                  → enroll this device into the group
 *
 * Base URL comes from BuildConfig.ARGOS_WEB_URL, but can be overridden at runtime
 * by passing an explicit baseOverride (e.g. read from the QR payload).
 */
class GroupEnrollClient(private val baseOverride: String? = null) {
    @Volatile
    var lastErrorMessage: String? = null
        private set

    private fun base(): String {
        val b = (baseOverride?.takeIf { it.isNotBlank() } ?: BuildConfig.ARGOS_WEB_URL).trim().trimEnd('/')
        return b
    }

    /** Resolve a linking code without enrolling. Returns the group, or null if not found. */
    suspend fun resolve(code: String): GroupEnrollResult? = withContext(Dispatchers.IO) {
        lastErrorMessage = null
        val clean = code.trim()
        if (clean.isBlank()) {
            lastErrorMessage = "Codigo de vinculacao vazio"
            return@withContext null
        }
        val url = URL("${base()}/api/groups/enroll?code=${URLEncoder.encode(clean, "UTF-8")}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("Accept", "application/json")
        }
        try {
            val codeStatus = conn.responseCode
            val text = (if (codeStatus in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
            if (!obj.optBoolean("ok", false)) {
                lastErrorMessage = extractErrorMessage(obj, codeStatus)
                return@withContext null
            }
            parseGroup(obj.optJSONObject("group"))
        } catch (e: Exception) {
            lastErrorMessage = e.message?.trim().orEmpty().ifBlank { e.javaClass.simpleName }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Enroll [deviceId] into the group identified by [code]. Returns the group on success. */
    suspend fun enroll(code: String, deviceId: String, deviceInfo: JSONObject? = null): GroupEnrollResult? =
        withContext(Dispatchers.IO) {
            lastErrorMessage = null
            val clean = code.trim()
            if (clean.isBlank() || deviceId.isBlank()) {
                lastErrorMessage = "Codigo de vinculacao ou device_id invalido"
                return@withContext null
            }

            val body = JSONObject().apply {
                put("code", clean)
                put("device_id", deviceId)
                if (deviceInfo != null) put("device_info", deviceInfo)
            }

            val baseUrl = base()
            val url = URL("$baseUrl/api/groups/enroll")
            Log.d("GroupEnrollClient", "Enrolling device $deviceId to group $clean, URL: $url, baseUrl: $baseUrl")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 25000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            try {
                BufferedWriter(OutputStreamWriter(conn.outputStream, "UTF-8")).use { it.write(body.toString()) }
                val status = conn.responseCode
                Log.d("GroupEnrollClient", "Enrollment response: HTTP $status")
                val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.d("GroupEnrollClient", "Response body: $text")
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
                if (!obj.optBoolean("ok", false)) {
                    lastErrorMessage = extractErrorMessage(obj, status)
                    Log.w("GroupEnrollClient", "Enrollment failed: $lastErrorMessage")
                    return@withContext null
                }
                parseGroup(obj.optJSONObject("group"))
            } catch (e: Exception) {
                lastErrorMessage = e.message?.trim().orEmpty().ifBlank { e.javaClass.simpleName }
                Log.e("GroupEnrollClient", "Enrollment error: $lastErrorMessage - ${e.javaClass.name}, URL: $baseUrl", e)
                // Log full stack trace for SSL errors
                if (e.javaClass.name.contains("SSL") || e.javaClass.name.contains("TLS") ||
                    e.message?.contains("Handshake", ignoreCase = true) == true) {
                    Log.e("GroupEnrollClient", "SSL/TLS Error Details:", e)
                }
                null
            } finally {
                runCatching { conn.disconnect() }
            }
        }

    private fun parseGroup(g: JSONObject?): GroupEnrollResult? {
        if (g == null) return null
        val id = g.optString("id").trim()
        if (id.isBlank()) return null
        return GroupEnrollResult(
            groupId = id,
            groupName = g.optString("name").trim(),
            companyId = g.optString("company_id").trim(),
            type = g.optString("type").trim(),
            linkingCode = g.optString("linking_code").trim(),
        )
    }

    companion object {
        /**
         * Extracts a usable linking code from a scanned value. The group QR payload is
         * JSON ({"c":"ABCD-1234","g":"...","t":"argos_group_link"}); the barcode and manual
         * entry are the raw code. Returns Pair(code, baseUrlOverrideOrNull).
         */
        fun parseScanned(raw: String): Pair<String, String?> {
            val value = raw.trim()
            // Try JSON payload first
            runCatching { JSONObject(value) }.getOrNull()?.let { obj ->
                val code = obj.optString("c").trim().ifBlank { obj.optString("code").trim() }
                val url = obj.optString("url").trim().ifBlank { obj.optString("base").trim() }
                if (code.isNotBlank()) return code to url.ifBlank { null }
            }
            // Accept direct URLs with query params like ?code=ABCD-1234 or ?c=ABCD-1234
            runCatching { Uri.parse(value) }.getOrNull()?.let { uri ->
                val code = listOf("code", "c", "group_code", "linking_code")
                    .firstNotNullOfOrNull { key -> uri.getQueryParameter(key)?.trim()?.takeIf { it.isNotBlank() } }
                if (code != null) {
                    val base = if (!uri.scheme.isNullOrBlank() && !uri.host.isNullOrBlank()) {
                        buildString {
                            append(uri.scheme)
                            append("://")
                            append(uri.host)
                            if (uri.port > 0) append(":").append(uri.port)
                        }
                    } else {
                        null
                    }
                    return code to base
                }
            }
            // Otherwise treat the whole value as the code
            return value to null
        }
    }

    private fun extractErrorMessage(obj: JSONObject, status: Int): String {
        val message = obj.optString("message").trim()
        if (message.isNotBlank()) return message
        val error = obj.optString("error").trim()
        if (error.isNotBlank()) return error
        return "Falha ao vincular (HTTP $status)"
    }
}
