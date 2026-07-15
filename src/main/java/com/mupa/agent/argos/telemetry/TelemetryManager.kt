package com.mupa.agent.argos.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.app.ActivityManager
import android.location.Location
import android.location.LocationManager
import com.mupa.agent.argos.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max
import kotlin.math.roundToInt

class TelemetryManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val replay = ArrayDeque<JSONObject>()

    fun recordReplaySample(sample: JSONObject) {
        val now = System.currentTimeMillis()
        sample.put("ts", now)
        replay.addLast(sample)
        val cutoff = now - 5 * 60_000L
        while (replay.isNotEmpty()) {
            val ts = replay.first().optLong("ts", 0L)
            if (ts <= 0L || ts >= cutoff) break
            replay.removeFirst()
        }
        while (replay.size > 40) replay.removeFirst()
    }

    fun buildReplaySnapshot(reason: String, details: String): JSONObject {
        val now = System.currentTimeMillis()
        val arr = org.json.JSONArray()
        replay.forEach { arr.put(JSONObject(it.toString())) }
        return JSONObject()
            .put("ts", now)
            .put("reason", reason)
            .put("details", details)
            .put("window_ms", 5 * 60_000L)
            .put("samples", arr)
    }

    fun collectReplaySample(
        foregroundPackage: String?,
        onlineState: String,
        uptimeMs: Long = SystemClock.elapsedRealtime(),
    ): JSONObject {
        val now = System.currentTimeMillis()
        val battery = readBattery()
        val cpu = readCpuPercent()
        val ram = readRam()
        val net = readNetwork()
        return JSONObject()
            .put("ts", now)
            .put("uptime_ms", uptimeMs)
            .put("foreground_package", foregroundPackage ?: JSONObject.NULL)
            .put("online_state", onlineState)
            .put("cpu_percent", cpu)
            .put("ram", ram)
            .put("network", net)
            .put("battery", battery)
    }

    fun collectHealthSnapshot(
        foregroundPackage: String?,
        onlineState: String,
        uptimeMs: Long = SystemClock.elapsedRealtime(),
    ): JSONObject {
        val now = System.currentTimeMillis()
        val battery = readBattery()
        val cpu = readCpuPercent()
        val ram = readRam()
        val storage = readStorage()
        val net = readNetwork()
        val loc = readBestLastKnownLocation()

        val batteryHealth = updateBatteryHealthEstimates(battery, now)
        val score = computeScore(cpuPercent = cpu, ram = ram, storage = storage, battery = battery, batteryHealth = batteryHealth)

        val out = JSONObject()
            .put("ts", now)
            .put("uptime_ms", uptimeMs)
            .put("foreground_package", foregroundPackage ?: JSONObject.NULL)
            .put("online_state", onlineState)
            .put("cpu_percent", cpu)
            .put("ram", ram)
            .put("storage", storage)
            .put("network", net)
            .put("battery", battery)
            .put("battery_health", batteryHealth)
            .put("device", JSONObject().put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT))
            .put("score", score)

        if (loc != null) {
            out.put(
                "location",
                JSONObject()
                    .put("lat", loc.latitude)
                    .put("lng", loc.longitude)
                    .put("accuracy_m", loc.accuracy.toDouble())
                    .put("time_ms", loc.time),
            )
        } else {
            out.put("location", JSONObject.NULL)
        }
        return out
    }

    fun recordCrash(threadName: String, throwable: Throwable) {
        val now = System.currentTimeMillis()
        val msg = throwable.message?.trim().orEmpty().ifBlank { throwable.javaClass.simpleName }
        val previous = prefs.getInt(KEY_CRASH_COUNT_24H, 0)
        prefs.edit()
            .putLong(KEY_LAST_CRASH_MS, now)
            .putString(KEY_LAST_CRASH_THREAD, threadName)
            .putString(KEY_LAST_CRASH_MESSAGE, msg.take(500))
            .putInt(KEY_CRASH_COUNT_24H, previous + 1)
            .apply()
    }

    fun shouldEmitBatteryAlert(batteryHealth: JSONObject): Boolean {
        val cap = batteryHealth.optDouble("estimated_capacity_percent", -1.0)
        return cap in 0.0..69.999
    }

    fun postToSupabaseIfConfigured(path: String, body: JSONObject): Boolean {
        val base = BuildConfig.SUPABASE_URL.trim().trimEnd('/')
        val key = BuildConfig.SUPABASE_ANON_KEY.trim()
        if (base.isBlank() || key.isBlank()) return false
        val url = URL("$base/$path".trimStart('/'))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 12000
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Prefer", "return=minimal")
            setRequestProperty("apikey", key)
            setRequestProperty("Authorization", "Bearer $key")
        }
        return runCatching {
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = conn.responseCode
            code in 200..299
        }.getOrDefault(false).also {
            conn.disconnect()
        }
    }

    private fun readCpuPercent(): Double? {
        val stat = readProcStat() ?: return null
        val prevTotal = prefs.getLong(KEY_CPU_TOTAL, -1L)
        val prevIdle = prefs.getLong(KEY_CPU_IDLE, -1L)
        prefs.edit().putLong(KEY_CPU_TOTAL, stat.total).putLong(KEY_CPU_IDLE, stat.idle).apply()
        if (prevTotal <= 0L || prevIdle < 0L) return null
        val dTotal = stat.total - prevTotal
        val dIdle = stat.idle - prevIdle
        if (dTotal <= 0L) return null
        val used = (dTotal - dIdle).toDouble().coerceAtLeast(0.0)
        return ((used / dTotal.toDouble()) * 100.0).coerceIn(0.0, 100.0)
    }

    private data class ProcCpu(val total: Long, val idle: Long)

    private fun readProcStat(): ProcCpu? {
        return runCatching {
            val p = ProcessBuilder("sh", "-c", "cat /proc/stat | head -n 1").start()
            val line = BufferedReader(InputStreamReader(p.inputStream)).use { it.readLine().orEmpty() }.trim()
            if (!line.startsWith("cpu")) return@runCatching null
            val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size < 5) return@runCatching null
            val nums = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) return@runCatching null
            val idle = nums.getOrNull(3) ?: 0L
            val iowait = nums.getOrNull(4) ?: 0L
            val total = nums.sum()
            ProcCpu(total = total, idle = idle + iowait)
        }.getOrNull()
    }

    private fun readRam(): JSONObject {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        runCatching { am?.getMemoryInfo(mi) }
        val total = mi.totalMem.coerceAtLeast(0L)
        val avail = mi.availMem.coerceAtLeast(0L)
        val used = (total - avail).coerceAtLeast(0L)
        return JSONObject()
            .put("total_mb", (total / (1024.0 * 1024.0)).roundToInt())
            .put("used_mb", (used / (1024.0 * 1024.0)).roundToInt())
            .put("avail_mb", (avail / (1024.0 * 1024.0)).roundToInt())
            .put("low_memory", mi.lowMemory)
    }

    private fun readStorage(): JSONObject {
        val dir = Environment.getDataDirectory()
        val stat = StatFs(dir.absolutePath)
        val total = stat.totalBytes.coerceAtLeast(0L)
        val free = stat.availableBytes.coerceAtLeast(0L)
        val used = (total - free).coerceAtLeast(0L)
        val usedPct = if (total > 0L) ((used.toDouble() / total.toDouble()) * 100.0).coerceIn(0.0, 100.0) else 0.0
        return JSONObject()
            .put("total_bytes", total)
            .put("free_bytes", free)
            .put("used_bytes", used)
            .put("used_percent", usedPct)
    }

    private fun readNetwork(): JSONObject {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = cm?.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } else {
            null
        }
        return JSONObject()
            .put("type", type)
            .put("has_internet", hasInternet)
            .put("validated", validated ?: JSONObject.NULL)
    }

    private fun readBattery(): JSONObject {
        val intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level.toDouble() * 100.0 / scale.toDouble()).coerceIn(0.0, 100.0) else -1.0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val tempC = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
            ?.let { it.toDouble() / 10.0 }
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val currentUa = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } else {
            null
        }
        val chargeCounterUaH = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } else {
            null
        }
        return JSONObject()
            .put("level_percent", if (pct >= 0) pct else JSONObject.NULL)
            .put("status", status)
            .put("health", health)
            .put("plugged", plugged)
            .put("temperature_c", tempC ?: JSONObject.NULL)
            .put("voltage_mv", voltageMv.takeIf { it >= 0 } ?: JSONObject.NULL)
            .put("current_ua", currentUa ?: JSONObject.NULL)
            .put("charge_counter_uah", chargeCounterUaH ?: JSONObject.NULL)
    }

    private fun updateBatteryHealthEstimates(battery: JSONObject, nowMs: Long): JSONObject {
        val level = battery.opt("level_percent")
        val levelPct = if (level is Number) level.toDouble() else -1.0
        val status = battery.optInt("status", -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val ccUah = battery.opt("charge_counter_uah")
        val chargeCounterUah = if (ccUah is Number) ccUah.toLong() else null

        val baselineFullUah = prefs.getLong(KEY_BASELINE_FULL_UAH, 0L).takeIf { it > 0L }
        val maxFullUahStored = prefs.getLong(KEY_MAX_FULL_UAH, 0L).takeIf { it > 0L }

        var maxFullUah = maxFullUahStored ?: 0L
        if (chargeCounterUah != null && levelPct >= 95.0 && isCharging) {
            if (chargeCounterUah > maxFullUah) maxFullUah = chargeCounterUah
            if (baselineFullUah == null && maxFullUah > 0L) {
                prefs.edit().putLong(KEY_BASELINE_FULL_UAH, maxFullUah).apply()
            }
            prefs.edit().putLong(KEY_MAX_FULL_UAH, maxFullUah).apply()
        }

        val lastCc = prefs.getLong(KEY_LAST_CC_UAH, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        val lastAt = prefs.getLong(KEY_LAST_CC_AT_MS, 0L).takeIf { it > 0L }
        if (chargeCounterUah != null) {
            prefs.edit().putLong(KEY_LAST_CC_UAH, chargeCounterUah).putLong(KEY_LAST_CC_AT_MS, nowMs).apply()
        }

        var dischargeRateMa: Double? = null
        var cumulativeDischargeMah = prefs.getLong(KEY_CUM_DISCHARGE_MAH_X10, 0L).coerceAtLeast(0L) / 10.0
        if (chargeCounterUah != null && lastCc != null && lastAt != null && nowMs > lastAt) {
            val dUah = chargeCounterUah - lastCc
            val dtH = (nowMs - lastAt).toDouble() / 3600000.0
            if (dtH > 0.0) {
                dischargeRateMa = (dUah.toDouble() / 1000.0) / dtH
            }
            if (dUah < 0) {
                cumulativeDischargeMah += (-dUah.toDouble() / 1000.0)
                prefs.edit().putLong(KEY_CUM_DISCHARGE_MAH_X10, (cumulativeDischargeMah * 10.0).roundToInt().toLong()).apply()
            }
        }

        val baseline = baselineFullUah ?: maxFullUah.takeIf { it > 0L }
        val estimatedCapacityPercent =
            if (baseline != null && baseline > 0L && maxFullUah > 0L) ((maxFullUah.toDouble() / baseline.toDouble()) * 100.0).coerceIn(0.0, 200.0) else null

        val estimatedCycles =
            if (baseline != null && baseline > 0L) {
                val baselineMah = baseline.toDouble() / 1000.0
                if (baselineMah > 0.0) (cumulativeDischargeMah / baselineMah).coerceAtLeast(0.0) else null
            } else {
                null
            }

        return JSONObject()
            .put("baseline_full_uah", baseline ?: JSONObject.NULL)
            .put("max_full_uah", maxFullUah.takeIf { it > 0L } ?: JSONObject.NULL)
            .put("estimated_capacity_percent", estimatedCapacityPercent ?: JSONObject.NULL)
            .put("estimated_cycles", estimatedCycles ?: JSONObject.NULL)
            .put("cumulative_discharge_mah", cumulativeDischargeMah)
            .put("discharge_rate_ma", dischargeRateMa ?: JSONObject.NULL)
    }

    private fun computeScore(
        cpuPercent: Double?,
        ram: JSONObject,
        storage: JSONObject,
        battery: JSONObject,
        batteryHealth: JSONObject,
    ): JSONObject {
        var score = 100
        val penalties = JSONObject()

        val cap = batteryHealth.optDouble("estimated_capacity_percent", -1.0)
        if (cap in 0.0..69.999) {
            score -= 25
            penalties.put("battery_capacity", 25)
        }

        val health = battery.optInt("health", -1)
        if (health != BatteryManager.BATTERY_HEALTH_GOOD && health != -1) {
            score -= 10
            penalties.put("battery_health", 10)
        }

        val usedPct = storage.optDouble("used_percent", 0.0)
        if (usedPct >= 90.0) {
            score -= 15
            penalties.put("storage", 15)
        } else if (usedPct >= 80.0) {
            score -= 8
            penalties.put("storage", 8)
        }

        val temp = battery.optDouble("temperature_c", -1.0)
        if (temp >= 45.0) {
            score -= 15
            penalties.put("temperature", 15)
        } else if (temp >= 40.0) {
            score -= 8
            penalties.put("temperature", 8)
        }

        val lowMem = ram.optBoolean("low_memory", false)
        if (lowMem) {
            score -= 10
            penalties.put("ram_low", 10)
        }

        val crashes = prefs.getInt(KEY_CRASH_COUNT_24H, 0).coerceAtLeast(0)
        if (crashes > 0) {
            val p = minOf(20, crashes * 5)
            score -= p
            penalties.put("crashes_24h", p)
        }

        val cpu = cpuPercent ?: 0.0
        if (cpu >= 90.0) {
            score -= 10
            penalties.put("cpu", 10)
        } else if (cpu >= 80.0) {
            score -= 5
            penalties.put("cpu", 5)
        }

        score = score.coerceIn(0, 100)
        return JSONObject()
            .put("value", score)
            .put("penalties", penalties)
    }

    data class IpGeoLocation(
        val latitude: Double,
        val longitude: Double,
        val city: String,
        val region: String,
        val country: String,
        val isp: String,
        val ip: String,
    )

    fun fetchIpGeoLocation(): IpGeoLocation? {
        val now = System.currentTimeMillis()
        val cachedAt = prefs.getLong(KEY_IP_GEO_CACHED_AT, 0L)
        if (now - cachedAt < IP_GEO_TTL_MS) {
            val lat = prefs.getFloat(KEY_IP_GEO_LAT, Float.MIN_VALUE)
            val lng = prefs.getFloat(KEY_IP_GEO_LNG, Float.MIN_VALUE)
            if (lat != Float.MIN_VALUE && lng != Float.MIN_VALUE) {
                return IpGeoLocation(
                    latitude = lat.toDouble(),
                    longitude = lng.toDouble(),
                    city = prefs.getString(KEY_IP_GEO_CITY, "") ?: "",
                    region = prefs.getString(KEY_IP_GEO_REGION, "") ?: "",
                    country = prefs.getString(KEY_IP_GEO_COUNTRY, "") ?: "",
                    isp = prefs.getString(KEY_IP_GEO_ISP, "") ?: "",
                    ip = prefs.getString(KEY_IP_GEO_IP, "") ?: "",
                )
            }
        }
        return runCatching {
            val conn = URL("https://ipapi.co/json/").openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "ArgosAgent/1.0")
            conn.connect()
            if (conn.responseCode != 200) return@runCatching null
            val body = conn.inputStream.bufferedReader().readText()
            val j = JSONObject(body)
            if (j.optString("error").isNotEmpty()) return@runCatching null
            val lat = j.optDouble("latitude", Double.NaN)
            val lng = j.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lng.isNaN()) return@runCatching null
            val geo = IpGeoLocation(
                latitude = lat,
                longitude = lng,
                city = j.optString("city"),
                region = j.optString("region"),
                country = j.optString("country_name"),
                isp = j.optString("org"),
                ip = j.optString("ip"),
            )
            prefs.edit()
                .putLong(KEY_IP_GEO_CACHED_AT, now)
                .putFloat(KEY_IP_GEO_LAT, lat.toFloat())
                .putFloat(KEY_IP_GEO_LNG, lng.toFloat())
                .putString(KEY_IP_GEO_CITY, geo.city)
                .putString(KEY_IP_GEO_REGION, geo.region)
                .putString(KEY_IP_GEO_COUNTRY, geo.country)
                .putString(KEY_IP_GEO_ISP, geo.isp)
                .putString(KEY_IP_GEO_IP, geo.ip)
                .apply()
            geo
        }.getOrNull()
    }

    // Exposed (not private) for HeartbeatService's periodic Firebase telemetry publish —
    // avoids duplicating this multi-provider last-known-location lookup.
    fun readBestLastKnownLocation(): Location? {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null) {
                best = loc
                continue
            }
            val newer = loc.time > best.time
            val betterAcc = loc.accuracy < best.accuracy
            if (newer && betterAcc) best = loc
            else if (newer && (best.time + 120_000L) < loc.time) best = loc
        }
        return best
    }

    companion object {
        private const val PREFS_NAME = "argos_agent_intelligence"
        private const val KEY_CPU_TOTAL = "cpu_total"
        private const val KEY_CPU_IDLE = "cpu_idle"

        private const val KEY_BASELINE_FULL_UAH = "battery_baseline_full_uah"
        private const val KEY_MAX_FULL_UAH = "battery_max_full_uah"
        private const val KEY_LAST_CC_UAH = "battery_last_cc_uah"
        private const val KEY_LAST_CC_AT_MS = "battery_last_cc_at_ms"
        private const val KEY_CUM_DISCHARGE_MAH_X10 = "battery_cum_discharge_mah_x10"

        private const val IP_GEO_TTL_MS = 6 * 60 * 60 * 1000L // 6 hours
        private const val KEY_IP_GEO_CACHED_AT = "ip_geo_cached_at"
        private const val KEY_IP_GEO_LAT = "ip_geo_lat"
        private const val KEY_IP_GEO_LNG = "ip_geo_lng"
        private const val KEY_IP_GEO_CITY = "ip_geo_city"
        private const val KEY_IP_GEO_REGION = "ip_geo_region"
        private const val KEY_IP_GEO_COUNTRY = "ip_geo_country"
        private const val KEY_IP_GEO_ISP = "ip_geo_isp"
        private const val KEY_IP_GEO_IP = "ip_geo_ip"

        private const val KEY_LAST_CRASH_MS = "last_crash_ms"
        private const val KEY_LAST_CRASH_THREAD = "last_crash_thread"
        private const val KEY_LAST_CRASH_MESSAGE = "last_crash_message"
        private const val KEY_CRASH_COUNT_24H = "crash_count_24h"
    }
}
