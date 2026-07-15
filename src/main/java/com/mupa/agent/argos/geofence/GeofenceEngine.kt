package com.mupa.agent.argos.geofence

import android.content.Context
import android.util.Log
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.telemetry.TelemetryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "GeofenceEngine"

class GeofenceEngine(context: Context) {

    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val telemetry = TelemetryManager(appContext)

    suspend fun check() = withContext(Dispatchers.IO) {
        val location = runCatching { telemetry.readBestLastKnownLocation() }.getOrNull()
        if (location == null) {
            Log.w(TAG, "no_location_skip — getLastKnownLocation returned null for all providers")
            return@withContext
        }
        Log.i(TAG, "location_ok lat=${location.latitude} lng=${location.longitude} acc=${location.accuracy}")

        val fetchResult = fetchActiveFences()
        val fences: List<FenceData>
        when (fetchResult) {
            is FetchResult.Success -> {
                fences = fetchResult.fences
                // Persist successful fetch to survive offline reboots
                settings.saveFencesJson(fencesToJson(fences))
                if (fences.isEmpty()) {
                    // Confirmed from server: no active fences → safe to clear lock
                    if (settings.isGeofenceLockedCached()) {
                        settings.setGeofenceLocked(false)
                        Log.i(TAG, "lock_cleared_no_fences")
                    }
                    return@withContext
                }
            }
            FetchResult.Failure -> {
                // Network/Firebase unavailable — fall back to locally cached fences
                val cached = settings.loadFencesJson()?.let { parseFencesJson(it) }
                if (cached.isNullOrEmpty()) {
                    Log.w(TAG, "fetch_failed_no_cache_skip")
                    return@withContext
                }
                Log.w(TAG, "fetch_failed_using_cache size=${cached.size}")
                fences = cached
            }
        }

        val lat = location.latitude
        val lng = location.longitude

        // Check if device is inside ANY active fence
        val insideFence = fences.firstOrNull { isInsideFence(it, lat, lng) }

        if (insideFence != null) {
            // Device is inside — clear the lock if it was set
            if (settings.isGeofenceLockedCached()) {
                settings.setGeofenceLocked(false)
                Log.i(TAG, "lock_cleared_inside fence=${insideFence.name}")
            }
        } else {
            // Device is outside ALL active fences — lock it using the first fence's config
            val triggerFence = fences.first()
            val alreadyLocked = settings.isGeofenceLockedCached()
            if (!alreadyLocked) {
                settings.setGeofenceLocked(
                    locked = true,
                    fenceId = triggerFence.id,
                    fenceName = triggerFence.name,
                    unlockPassword = triggerFence.unlockPassword,
                )
                Log.w(TAG, "lock_applied fence=${triggerFence.name} lat=$lat lng=$lng")
            }
        }
    }

    // ── Firebase REST read ───────────────────────────────────────────────────

    private sealed class FetchResult {
        data class Success(val fences: List<FenceData>) : FetchResult()
        object Failure : FetchResult()
    }

    private fun fetchActiveFences(): FetchResult {
        val dbUrl = com.mupa.agent.argos.BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        if (dbUrl.isBlank()) {
            Log.w(TAG, "fetch_failed_no_db_url")
            return FetchResult.Failure
        }
        return runCatching {
            val url = "$dbUrl/argos_geofences.json"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "fetch_http_error code=$code url=$url")
                conn.disconnect()
                return FetchResult.Failure
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (body == "null" || body.isBlank()) {
                Log.i(TAG, "fetch_empty_no_fences")
                return FetchResult.Success(emptyList())
            }

            val root = JSONObject(body)
            val result = mutableListOf<FenceData>()
            for (key in root.keys()) {
                val raw = runCatching { root.getJSONObject(key) }.getOrNull() ?: continue
                val active = raw.optBoolean("active", true)
                if (!active) continue
                val id = raw.optString("id").ifBlank { key }
                val name = raw.optString("name").trim()
                if (name.isBlank()) continue
                val type = raw.optString("type").ifBlank { "circle" }
                val unlockPwd = raw.optString("unlock_password")

                if (type == "polygon") {
                    val rawPoly = raw.optJSONArray("polygon") ?: continue
                    val polygon = (0 until rawPoly.length()).mapNotNull { i ->
                        val pt = runCatching { rawPoly.getJSONObject(i) }.getOrNull() ?: return@mapNotNull null
                        val plat = pt.optDouble("lat").takeIf { !it.isNaN() } ?: return@mapNotNull null
                        val plng = pt.optDouble("lng").takeIf { !it.isNaN() } ?: return@mapNotNull null
                        LatLng(plat, plng)
                    }
                    if (polygon.size >= 3) result += FenceData(id, name, type, 0.0, 0.0, 0.0, polygon, unlockPwd)
                } else {
                    val lat = raw.optDouble("lat").takeIf { !it.isNaN() } ?: continue
                    val lng = raw.optDouble("lng").takeIf { !it.isNaN() } ?: continue
                    val radius = raw.optDouble("radius_m").takeIf { !it.isNaN() && it > 0 } ?: continue
                    result += FenceData(id, name, type, lat, lng, radius, emptyList(), unlockPwd)
                }
            }
            Log.i(TAG, "fetch_ok fences=${result.size}")
            FetchResult.Success(result)
        }.getOrElse {
            Log.w(TAG, "fetch_exception: ${it.message}")
            FetchResult.Failure
        }
    }

    // ── Cache serialisation ───────────────────────────────────────────────────

    private fun fencesToJson(fences: List<FenceData>): String {
        val arr = JSONArray()
        for (f in fences) {
            val obj = JSONObject()
            obj.put("id", f.id)
            obj.put("name", f.name)
            obj.put("type", f.type)
            obj.put("lat", f.lat)
            obj.put("lng", f.lng)
            obj.put("radius_m", f.radiusM)
            obj.put("unlock_password", f.unlockPassword)
            val poly = JSONArray()
            for (p in f.polygon) {
                val pt = JSONObject()
                pt.put("lat", p.lat)
                pt.put("lng", p.lng)
                poly.put(pt)
            }
            obj.put("polygon", poly)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseFencesJson(json: String): List<FenceData>? = runCatching {
        val arr = JSONArray(json)
        val result = mutableListOf<FenceData>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = obj.optString("name").takeIf { it.isNotBlank() } ?: continue
            val type = obj.optString("type").ifBlank { "circle" }
            val unlockPwd = obj.optString("unlock_password")
            if (type == "polygon") {
                val rawPoly = obj.optJSONArray("polygon") ?: continue
                val polygon = (0 until rawPoly.length()).mapNotNull { j ->
                    val pt = rawPoly.getJSONObject(j)
                    val plat = pt.optDouble("lat").takeIf { !it.isNaN() } ?: return@mapNotNull null
                    val plng = pt.optDouble("lng").takeIf { !it.isNaN() } ?: return@mapNotNull null
                    LatLng(plat, plng)
                }
                if (polygon.size >= 3) result += FenceData(id, name, type, 0.0, 0.0, 0.0, polygon, unlockPwd)
            } else {
                val lat = obj.optDouble("lat").takeIf { !it.isNaN() } ?: continue
                val lng = obj.optDouble("lng").takeIf { !it.isNaN() } ?: continue
                val radius = obj.optDouble("radius_m").takeIf { !it.isNaN() && it > 0 } ?: continue
                result += FenceData(id, name, type, lat, lng, radius, emptyList(), unlockPwd)
            }
        }
        result
    }.getOrNull()

    // ── Geometry ─────────────────────────────────────────────────────────────

    private fun isInsideFence(fence: FenceData, lat: Double, lng: Double): Boolean {
        return if (fence.type == "polygon") {
            pointInPolygon(lat, lng, fence.polygon)
        } else {
            haversineMeters(fence.lat, fence.lng, lat, lng) <= fence.radiusM
        }
    }

    private fun pointInPolygon(lat: Double, lng: Double, poly: List<LatLng>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val xi = poly[i].lng; val yi = poly[i].lat
            val xj = poly[j].lng; val yj = poly[j].lat
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun haversineMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val R = 6_371_000.0
        val dLat = (bLat - aLat) * PI / 180
        val dLng = (bLng - aLng) * PI / 180
        val la1 = aLat * PI / 180
        val la2 = bLat * PI / 180
        val h = sin(dLat / 2).let { it * it } + cos(la1) * cos(la2) * sin(dLng / 2).let { it * it }
        return 2 * R * asin(min(1.0, sqrt(h)))
    }

    private data class LatLng(val lat: Double, val lng: Double)

    private data class FenceData(
        val id: String,
        val name: String,
        val type: String,
        val lat: Double,
        val lng: Double,
        val radiusM: Double,
        val polygon: List<LatLng>,
        val unlockPassword: String,
    )
}
