package com.mupa.agent.argos.commands

import android.content.Context
import com.mupa.agent.argos.managers.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ArgosPendingCommand(
    val commandId: String,
    val command: String,
    val priority: Int,
    val timestamp: Long,
    val params: JSONObject,
)

data class ArgosCommandResult(
    val commandId: String,
    val status: String,
    val message: String,
    val executedAt: Long,
)

class ArgosApiClient(
    private val context: Context,
    private val settingsManager: SettingsManager,
) {
    private fun baseApiUrl(): String {
        val configured = settingsManager.getArgosApiUrlCached().trim()
        if (configured.isBlank()) return ""
        val base = configured.trimEnd('/')
        return if (base.endsWith("/api", ignoreCase = true)) base else "$base/api"
    }

    fun fetchPendingCommands(deviceId: String): List<ArgosPendingCommand> {
        val base = baseApiUrl()
        if (base.isBlank()) return emptyList()
        // /public/agent/commands/pending is the real, deployed route (src/routes/api/public/agent/commands.pending.ts).
        // The /functions/v1/* and bare /commands/* paths don't exist as Supabase Edge Functions yet — kept last
        // as forward-compatible fallbacks in case they're added later, so we don't pay for a guaranteed-failed
        // request on every single poll cycle.
        val paths = listOf(
            "/public/agent/commands/pending?device_id=${deviceId.trim()}",
            "/commands/pending?device_id=${deviceId.trim()}",
            "/functions/v1/get-pending-commands?device_id=${deviceId.trim()}",
        )
        for (path in paths) {
            val result = requestPending("$base$path", deviceId)
            if (result.resolved) return result.commands
        }
        return emptyList()
    }

    fun postCommandResult(deviceId: String, result: ArgosCommandResult): Boolean {
        val base = baseApiUrl()
        if (base.isBlank()) return false
        // Same ordering rationale as fetchPendingCommands(): real route first.
        val paths = listOf(
            "/public/agent/commands/ack",
            "/commands/ack",
            "/functions/v1/ack-command",
        )
        for (path in paths) {
            val resultOk = postAck("$base$path", deviceId, result)
            if (resultOk != null) return resultOk
        }
        return false
    }

    private fun requestPending(urlValue: String, deviceId: String): PendingRequestResult {
        val url = URL(urlValue)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            settingsManager.getArgosDeviceTokenCached().trim().takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
            setRequestProperty("X-Device-Id", deviceId.trim())
        }
        return runCatching {
            val code = conn.responseCode
            if (code == 404) return@runCatching PendingRequestResult(false, emptyList())
            if (code == 204) return@runCatching PendingRequestResult(true, emptyList())
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return@runCatching PendingRequestResult(true, emptyList())
            PendingRequestResult(true, parsePendingCommands(text))
        }.getOrDefault(PendingRequestResult(false, emptyList())).also {
            conn.disconnect()
        }
    }

    private fun postAck(urlValue: String, deviceId: String, result: ArgosCommandResult): Boolean? {
        val url = URL(urlValue)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            settingsManager.getArgosDeviceTokenCached().trim().takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
            setRequestProperty("X-Device-Id", deviceId.trim())
        }
        return runCatching {
            val normalizedStatus = normalizeAckStatus(result.status)
            val isFailure = normalizedStatus in setOf("failed", "timeout", "retrying", "cancelled", "rollback", "expired")
            val body = JSONObject()
                .put("command_id", result.commandId)
                .put("device_id", deviceId.trim())
                .put("status", normalizedStatus)
                .put(
                    "result",
                    JSONObject()
                        .put("message", result.message)
                        .put("executed_at", result.executedAt),
                )
                .put("error", if (isFailure) result.message else JSONObject.NULL)
                .toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val code = conn.responseCode
            when {
                code == 404 -> null
                else -> code in 200..299
            }
        }.getOrDefault(false).also {
            conn.disconnect()
        }
    }

    private fun normalizeAckStatus(status: String): String {
        return when (status.trim().lowercase()) {
            "queued" -> "received"
            "processing", "executing" -> "executing"
            "success" -> "success"
            "timeout" -> "timeout"
            "retrying" -> "retrying"
            "cancelled" -> "cancelled"
            "rollback" -> "rollback"
            "expired" -> "expired"
            "delegated", "partial_success" -> "success"
            else -> "failed"
        }
    }

    private fun parsePendingCommands(raw: String): List<ArgosPendingCommand> {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed == "null") return emptyList()
        val arr = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("commands")
                    ?: obj.optJSONArray("data")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }
        val out = ArrayList<ArgosPendingCommand>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val commandId = obj.optString("command_id", obj.optString("commandId", obj.optString("id", ""))).trim()
            val command = obj.optString("command", obj.optString("comando", "")).trim()
            if (commandId.isBlank() || command.isBlank()) continue
            val priority = obj.optInt("priority", 5)
            val ts = when (val t = obj.opt("timestamp")) {
                is Number -> t.toLong()
                is String -> t.trim().toLongOrNull() ?: 0L
                else -> 0L
            }
            val params = obj.optJSONObject("params")
                ?: obj.optJSONObject("parameters")
                ?: obj.optJSONObject("payload")
                ?: JSONObject()
            out.add(
                ArgosPendingCommand(
                    commandId = commandId,
                    command = command,
                    priority = priority,
                    timestamp = ts,
                    params = params,
                ),
            )
        }
        return out
    }

    private data class PendingRequestResult(
        val resolved: Boolean,
        val commands: List<ArgosPendingCommand>,
    )
}
