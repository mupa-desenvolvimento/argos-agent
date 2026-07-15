package com.mupa.agent.argos.managers

import com.mupa.agent.argos.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ArgosCompanyInfo(
    val companyCode: String,
    val companyName: String,
)

data class ArgosCompanyDetails(
    val id: String,
    val code: String,
    val name: String,
    val city: String,
    val deviceCount: Int,
    val logoUrl: String,
)

data class ArgosDeviceRegistrationResult(
    val deviceId: String,
    val token: String,
    val group: String,
)

class SupabaseDirectoryClient {
    suspend fun resolveCompanyBySerial(serial: String): ArgosCompanyInfo? = withContext(Dispatchers.IO) {
        val normalizedSerial = serial.trim()
        if (normalizedSerial.isBlank()) return@withContext null
        val device = fetchDeviceBySerial(normalizedSerial) ?: return@withContext null
        val code = device.optString("code").trim()
            .ifBlank { device.optString("company_code").trim() }
            .ifBlank { device.optString("companyCode").trim() }
        if (code.isBlank()) return@withContext null

        val company = fetchCompanyByCodeAnyCase(code) ?: return@withContext null
        val name = company.optString("name").trim()
            .ifBlank { company.optString("nome").trim() }
            .ifBlank { company.optString("company_name").trim() }
            .ifBlank { company.optString("razao_social").trim() }
        if (name.isBlank()) return@withContext ArgosCompanyInfo(companyCode = code, companyName = code)
        ArgosCompanyInfo(companyCode = code, companyName = name)
    }

    suspend fun fetchCompanyDetailsByCode(code: String): ArgosCompanyDetails? = withContext(Dispatchers.IO) {
        val company = fetchCompanyByCodeAnyCase(code.trim()) ?: return@withContext null

        val id = company.optString("id").trim()
            .ifBlank { company.optString("company_id").trim() }
            .ifBlank { company.optString("empresa_id").trim() }
        val safeCode = company.optString("code").trim().ifBlank { code.trim() }
        val name = company.optString("name").trim()
            .ifBlank { company.optString("nome").trim() }
            .ifBlank { company.optString("company_name").trim() }
            .ifBlank { company.optString("razao_social").trim() }
            .ifBlank { safeCode }
        val city = company.optString("city").trim()
            .ifBlank { company.optString("cidade").trim() }
            .ifBlank { company.optString("municipio").trim() }
        val deviceCount = company.optInt("device_count", company.optInt("devices_count", company.optInt("qtd_dispositivos", 0)))
        val logoUrl = company.optString("logo_url").trim()
            .ifBlank { company.optString("logo").trim() }
            .ifBlank { company.optString("logoUrl").trim() }
            .ifBlank { company.optString("logo_svg_url").trim() }

        ArgosCompanyDetails(
            id = id,
            code = safeCode,
            name = name,
            city = city,
            deviceCount = deviceCount.coerceAtLeast(0),
            logoUrl = logoUrl,
        )
    }

    suspend fun registerDevice(payload: JSONObject): ArgosDeviceRegistrationResult? = withContext(Dispatchers.IO) {
        val url = URL("${baseUrl()}/rest/v1/rpc/create_dispositivo")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 20000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            val key = BuildConfig.SUPABASE_ANON_KEY.trim().ifBlank { BuildConfig.SUPABASE_COMPANY_KEY.trim() }
            setRequestProperty("apikey", key)
            setRequestProperty("Authorization", "Bearer $key")
        }

        return@withContext runCatching {
            val body = JSONObject().put("payload", payload).toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val codeResp = conn.responseCode
            val stream = if (codeResp in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (codeResp !in 200..299) return@runCatching null
            val obj = decodeFirstObject(text) ?: return@runCatching null

            val deviceId = obj.optString("device_id").trim()
                .ifBlank { obj.optString("id").trim() }
                .ifBlank { obj.optString("serial").trim() }
            val token = obj.optString("token").trim()
                .ifBlank { obj.optString("device_token").trim() }
                .ifBlank { obj.optString("argos_device_token").trim() }
            val group = obj.optString("grupo").trim()
                .ifBlank { obj.optString("group").trim() }
                .ifBlank { obj.optString("grupo_dispositivos").trim() }

            ArgosDeviceRegistrationResult(
                deviceId = deviceId,
                token = token,
                group = group,
            )
        }.getOrNull().also {
            conn.disconnect()
        }
    }

    private fun baseUrl(): String = BuildConfig.SUPABASE_URL.trimEnd('/')

    private fun fetchDeviceBySerial(serial: String): JSONObject? {
        val url = URL("${baseUrl()}/rest/v1/rpc/get_dispositivo_por_serial")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            val key = BuildConfig.SUPABASE_ANON_KEY.trim()
            setRequestProperty("apikey", key)
            setRequestProperty("Authorization", "Bearer $key")
        }
        return runCatching {
            val body = JSONObject().put("p_serial", serial).toString()
            BufferedWriter(OutputStreamWriter(conn.outputStream)).use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (code !in 200..299) return@runCatching null
            decodeFirstObject(text)
        }.getOrNull().also {
            conn.disconnect()
        }
    }

    private fun fetchCompanyByCodeAnyCase(code: String): JSONObject? {
        val raw = code.trim()
        if (raw.isBlank()) return null
        val candidates = LinkedHashSet<String>()
        candidates.add(raw)
        candidates.add(raw.uppercase())
        candidates.add(raw.lowercase())
        for (c in candidates) {
            val obj = fetchCompanyByCodeExact(c)
            if (obj != null) return obj
        }
        return null
    }

    private fun fetchCompanyByCodeExact(code: String): JSONObject? {
        val safeCode = code.trim()
        if (safeCode.isBlank()) return null
        val encoded = URLEncoder.encode(safeCode, "UTF-8")
        val url = URL("${baseUrl()}/rest/v1/companies?select=*&code=eq.${encoded}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Range", "99")
            val key = BuildConfig.SUPABASE_COMPANY_KEY.trim().ifBlank { BuildConfig.SUPABASE_ANON_KEY.trim() }
            setRequestProperty("apikey", key)
            setRequestProperty("Authorization", "Bearer $key")
        }
        return runCatching {
            val codeResp = conn.responseCode
            val stream = if (codeResp in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (codeResp !in 200..299) return@runCatching null
            decodeFirstObject(text)
        }.getOrNull().also {
            conn.disconnect()
        }
    }

    private fun decodeFirstObject(text: String): JSONObject? {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == "null") return null
        return when {
            trimmed.startsWith("[") -> (JSONArray(trimmed).optJSONObject(0) ?: return null)
            trimmed.startsWith("{") -> JSONObject(trimmed)
            else -> null
        }
    }
}
