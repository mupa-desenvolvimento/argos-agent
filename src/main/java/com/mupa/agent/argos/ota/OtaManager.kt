package com.mupa.agent.argos.ota

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import com.google.firebase.database.DatabaseReference
import com.mupa.agent.argos.BuildConfig
import com.mupa.agent.argos.firebase.FirebaseRuntimeMode
import com.mupa.agent.argos.managers.PlayerAppConstants
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.provisioning.ApkInstallManager
import com.mupa.agent.argos.provisioning.ApkInstallRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class OtaSpec(
    val app: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apkUrl: String,
    val checksumSha256: String,
    val mandatory: Boolean,
    val scheduledAtMs: Long,
    val releaseDate: String,
    val changelog: List<String>,
) {
    fun key(): String = app.trim().lowercase(Locale.ROOT).ifBlank { packageName.trim().lowercase(Locale.ROOT) }
}

class OtaManager(context: Context) {
    private val appContext = context.applicationContext
    private val dpm = DeviceOwnerPolicyManager(appContext)
    private val pm = appContext.packageManager
    private val install = ApkInstallManager(appContext)

    private val otaDir = File(appContext.filesDir, "ota").apply { mkdirs() }
    private val downloadsDir = File(otaDir, "downloads").apply { mkdirs() }
    private val backupsDir = File(otaDir, "backups").apply { mkdirs() }
    private val stateFile = File(otaDir, "state.json")
    private val historyFile = File(otaDir, "history.json")

    private var lastTickMs: Long = 0L

    private data class DownloadAttempt(
        val ok: Boolean,
        val httpCode: Int,
        val bytes: Long,
        val error: String,
    )

    suspend fun onRemoteOtaSnapshot(
        deviceId: String,
        deviceRootRef: DatabaseReference,
        updatedAt: Long,
        otaRoot: JSONObject,
    ) = withContext(Dispatchers.IO) {
        val specs = parseSpecs(otaRoot)
        if (specs.isEmpty()) return@withContext

        for (spec in specs) {
            runCatching { handleSpec(deviceId, deviceRootRef, updatedAt, spec) }
        }
    }

    suspend fun tickIfDue(
        deviceId: String,
        deviceRootRef: DatabaseReference,
        now: Long,
    ) = withContext(Dispatchers.IO) {
        if (now - lastTickMs < 15_000L) return@withContext
        lastTickMs = now

        val state = readState()
        val apps = state.optJSONObject("apps") ?: JSONObject()
        val keys = apps.keys().asSequence().toList()
        for (k in keys) {
            val a = apps.optJSONObject(k) ?: continue
            val status = a.optString("status", "").trim()
            if (status == "installing") {
                val pkg = a.optString("package", "").trim()
                val targetVc = a.optLong("version_code", -1L)
                if (pkg.isNotBlank() && targetVc > 0L) {
                    val installedVc = getInstalledVersionCode(pkg)
                    if (installedVc >= targetVc) {
                        a.put("status", "success")
                            .put("installed_at", now)
                            .put("installed_version_code", installedVc)
                            .put("installed_version_name", getInstalledVersionName(pkg))
                            .put("rollback_available", a.optString("backup_apk_path", "").trim().isNotBlank())
                        apps.put(k, a)
                        writeState(state.put("apps", apps))
                        writeOtaState(deviceId, deviceRootRef, k, a)
                        pushLocationTelemetryNow(deviceId, deviceRootRef, now)
                        log(deviceId, deviceRootRef, "INFO", "ota", "install_completed_after_restart", JSONObject().put("app", k).put("package", pkg).put("version_code", installedVc))
                    }
                }
                continue
            }
            if (status != "downloaded" && status != "scheduled") continue
            val scheduledAt = a.optLong("scheduled_at_ms", 0L).coerceAtLeast(0L)
            if (scheduledAt > 0L && now < scheduledAt) continue

            val apkPath = a.optString("apk_path", "").trim()
            if (apkPath.isBlank()) continue
            val file = File(apkPath)
            if (!file.exists() || file.length() <= 0L) {
                a.put("status", "failed").put("error", "apk_missing")
                apps.put(k, a)
                writeState(state.put("apps", apps))
                writeOtaState(deviceId, deviceRootRef, k, a)
                continue
            }

            val pkg = a.optString("package", "").trim()
            val targetVc = a.optLong("version_code", -1L)
            val targetVn = a.optString("version_name", "").trim()
            val checksum = a.optString("checksum", "").trim()

            val isOwner = dpm.isDeviceOwner(appContext.packageName)
            if (!isOwner) {
                a.put("status", "failed").put("error", "not_device_owner")
                apps.put(k, a)
                writeState(state.put("apps", apps))
                writeOtaState(deviceId, deviceRootRef, k, a)
                log(deviceId, deviceRootRef, "CRITICAL", "ota", "device_owner_removed", JSONObject().put("app", k))
                continue
            }

            a.put("status", "installing").put("install_started_at", now)
            apps.put(k, a)
            writeState(state.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, k, a)
            log(deviceId, deviceRootRef, "INFO", "ota", "install_started", JSONObject().put("app", k).put("package", pkg).put("version_code", targetVc).put("version_name", targetVn))

            val res = install.installLocal(
                file = file,
                request = ApkInstallRequest(
                    url = "file://$apkPath",
                    expectedPackage = pkg.ifBlank { null },
                    expectedVersion = targetVn.ifBlank { null },
                    force = true,
                    silent = true,
                    autoOpen = false,
                    sha256 = checksum.ifBlank { null },
                ),
                allowDowngrade = false,
            )

            val installedVc = getInstalledVersionCode(pkg)
            if (res.status == "success" && installedVc >= targetVc && targetVc > 0L) {
                a.put("status", "success")
                    .put("installed_at", now)
                    .put("installed_version_code", installedVc)
                    .put("installed_version_name", getInstalledVersionName(pkg))
                    .put("rollback_available", a.optString("backup_apk_path", "").trim().isNotBlank())
                apps.put(k, a)
                writeState(state.put("apps", apps))
                writeOtaState(deviceId, deviceRootRef, k, a)
                pushLocationTelemetryNow(deviceId, deviceRootRef, now)
                appendHistory(
                    app = k,
                    packageName = pkg,
                    fromVersionCode = a.optLong("previous_version_code", -1L),
                    toVersionCode = targetVc,
                    status = "success",
                    now = now,
                    source = "ota",
                )
                log(deviceId, deviceRootRef, "INFO", "ota", "install_completed", JSONObject().put("app", k).put("package", pkg).put("version_code", installedVc))
            } else {
                a.put("status", "failed")
                    .put("error", res.message)
                    .put("failed_at", now)
                apps.put(k, a)
                writeState(state.put("apps", apps))
                writeOtaState(deviceId, deviceRootRef, k, a)
                appendHistory(
                    app = k,
                    packageName = pkg,
                    fromVersionCode = a.optLong("previous_version_code", -1L),
                    toVersionCode = targetVc,
                    status = "failed",
                    now = now,
                    source = "ota",
                )
                log(deviceId, deviceRootRef, "ERROR", "ota", "install_failed", JSONObject().put("app", k).put("package", pkg).put("error", res.message))
                attemptRollbackIfPossible(deviceId, deviceRootRef, k, a, apps, state, now)
            }
        }
    }

    /**
     * Always-on player auto-update: resolves which player package this device should run
     * (com.mupa.player.x96 on X96/Amlogic boxes, com.mupa.player.enterprise otherwise via
     * PlayerAppConstants), checks argos_ota/latest_versions for that app's newest published
     * version, and — if it's newer than what's installed — enqueues it through the same
     * download -> checksum -> backup pipeline handleSpec() already implements for admin-pushed
     * OTA specs. tickIfDue() then drives the actual install (+ rollback on failure) on its own
     * schedule. Unlike onRemoteOtaSnapshot(), this needs no per-device spec to be published by
     * an admin — it always targets whatever the catalog currently considers latest.
     */
    suspend fun checkForPlayerUpdate(
        deviceId: String,
        deviceRootRef: DatabaseReference,
    ) = withContext(Dispatchers.IO) {
        val appKey = if (PlayerAppConstants.isX96()) "mplayer_x96" else "mplayer"
        val pkg = PlayerAppConstants.resolvePackage()
        val entry = fetchLatestCatalogEntry(appKey) ?: return@withContext

        val versionCode = entry.optLong("version_code", entry.optLong("versionCode", -1L))
        val versionName = entry.optString("version_name", entry.optString("versionName", "")).trim()
        val apkUrl = entry.optString("apk_url", entry.optString("url", "")).trim()
        val checksum = entry.optString("checksum", entry.optString("sha256", "")).trim()
        if (versionCode <= 0L || versionName.isBlank() || apkUrl.isBlank() || checksum.isBlank()) return@withContext

        val installedVc = getInstalledVersionCode(pkg)
        if (installedVc >= versionCode) return@withContext

        val existing = readState().optJSONObject("apps")?.optJSONObject(appKey)
        val existingStatus = existing?.optString("status", "").orEmpty()
        if (existingStatus in ACTIVE_OTA_STATUSES && existing?.optLong("version_code", -1L) == versionCode) {
            return@withContext
        }

        val spec = OtaSpec(
            app = appKey,
            packageName = pkg,
            versionName = versionName,
            versionCode = versionCode,
            apkUrl = apkUrl,
            checksumSha256 = checksum,
            mandatory = true,
            scheduledAtMs = 0L,
            releaseDate = entry.optString("created_at", "").trim(),
            changelog = emptyList(),
        )
        runCatching { handleSpec(deviceId, deviceRootRef, System.currentTimeMillis(), spec) }
    }

    private fun fetchLatestCatalogEntry(appKey: String): JSONObject? {
        val dbUrl = BuildConfig.FIREBASE_DATABASE_URL.trim().trimEnd('/')
        if (dbUrl.isBlank()) return null
        return runCatching {
            val conn = (URL("$dbUrl/argos_ota/latest_versions/$appKey.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (body.isBlank() || body == "null") null else JSONObject(body)
        }.getOrNull()
    }

    private fun parseSpecs(root: JSONObject): List<OtaSpec> {
        val list = ArrayList<OtaSpec>()

        fun fromObj(obj: JSONObject) {
            val app = obj.optString("app", obj.optString("app_name", "")).trim()
            val pkg = resolvePackageName(app, obj.optString("package_name", obj.optString("package", "")).trim())
            if (pkg.isBlank()) return
            val vc = obj.optLong("version_code", obj.optLong("versionCode", -1L)).coerceAtLeast(-1L)
            val vn = obj.optString("version_name", obj.optString("versionName", "")).trim()
            val url = obj.optString("apk_url", obj.optString("url", obj.optString("apkUrl", ""))).trim()
            val checksum = obj.optString("checksum", obj.optString("sha256", "")).trim()
            if (vc <= 0L || vn.isBlank() || url.isBlank() || checksum.isBlank()) return
            val mandatory = obj.optBoolean("mandatory", obj.optBoolean("required", true))
            val scheduled = parseScheduledAtMs(obj.opt("scheduled_install") ?: obj.opt("scheduled_at") ?: obj.opt("scheduledAt"))
            val releaseDate = obj.optString("release_date", obj.optString("created_at", "")).trim()
            val changelogArr = obj.optJSONArray("changelog") ?: JSONArray()
            val changelog = (0 until changelogArr.length()).mapNotNull { i ->
                changelogArr.optString(i).trim().takeIf { it.isNotBlank() }
            }
            list.add(
                OtaSpec(
                    app = if (app.isBlank()) pkg else app,
                    packageName = pkg,
                    versionName = vn,
                    versionCode = vc,
                    apkUrl = url,
                    checksumSha256 = checksum,
                    mandatory = mandatory,
                    scheduledAtMs = scheduled,
                    releaseDate = releaseDate,
                    changelog = changelog,
                ),
            )
        }

        when {
            root.has("app") || root.has("apk_url") || root.has("version_code") -> fromObj(root)
            else -> {
                val arr = root.optJSONArray("items") ?: root.optJSONArray("updates")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        fromObj(o)
                    }
                } else {
                    val keys = root.keys().asSequence().toList()
                    for (k in keys) {
                        val o = root.optJSONObject(k) ?: continue
                        val withApp = JSONObject(o.toString())
                        if (!withApp.has("app")) withApp.put("app", k)
                        fromObj(withApp)
                    }
                }
            }
        }

        return list
    }

    private fun resolvePackageName(app: String, provided: String): String {
        val a = app.trim().lowercase(Locale.ROOT)
        val p = provided.trim()
        if (p.isNotBlank()) return p
        if (a == "argos_agent" || a == "agent" || a == "argos") return appContext.packageName
        if (a == "mplayer" || a == "mplayer_x96" || a == "mupa_player" || a == "com.mupa.mplayer") {
            return PlayerAppConstants.resolvePackage()
        }
        return ""
    }

    private fun parseScheduledAtMs(any: Any?): Long {
        if (any == null) return 0L
        when (any) {
            is Number -> return any.toLong().let { if (it < 1_000_000_000_000L) it * 1000L else it }.coerceAtLeast(0L)
            is String -> {
                val s = any.trim()
                if (s.isBlank()) return 0L
                s.toLongOrNull()?.let { n ->
                    return (if (n < 1_000_000_000_000L) n * 1000L else n).coerceAtLeast(0L)
                }
                val patterns = listOf(
                    "yyyy-MM-dd HH:mm:ss",
                    "yyyy-MM-dd'T'HH:mm:ss",
                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                )
                for (pat in patterns) {
                    val sdf = SimpleDateFormat(pat, Locale.US).apply {
                        timeZone = if (pat.endsWith("'Z'")) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
                        isLenient = true
                    }
                    val d = runCatching { sdf.parse(s) }.getOrNull() ?: continue
                    return d.time.coerceAtLeast(0L)
                }
            }
        }
        return 0L
    }

    private fun handleSpec(
        deviceId: String,
        deviceRootRef: DatabaseReference,
        updatedAt: Long,
        spec: OtaSpec,
    ) {
        val appKey = spec.key()
        val now = System.currentTimeMillis()
        val installedVc = getInstalledVersionCode(spec.packageName)
        val state = readState()
        val apps = state.optJSONObject("apps") ?: JSONObject()
        val current = apps.optJSONObject(appKey) ?: JSONObject()

        current.put("app", appKey)
        current.put("package", spec.packageName)
        current.put("version_name", spec.versionName)
        current.put("version_code", spec.versionCode)
        current.put("apk_url", spec.apkUrl)
        current.put("checksum", spec.checksumSha256)
        current.put("mandatory", spec.mandatory)
        current.put("scheduled_at_ms", spec.scheduledAtMs)
        current.put("release_date", spec.releaseDate)
        current.put("updated_at", maxOf(updatedAt, now))
        current.put("installed_version_code_before", installedVc)
        current.put("installed_version_name_before", getInstalledVersionName(spec.packageName))
        current.put("error", "")
        current.put("error_detail", "")
        current.put("download_http_code", 0)
        current.put("download_bytes", 0L)
        current.remove("failed_at")
        current.remove("install_started_at")
        current.remove("installed_at")

        if (installedVc >= spec.versionCode) {
            current.put("status", "ignored").put("reason", "already_up_to_date")
            apps.put(appKey, current)
            writeState(state.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, appKey, current)
            return
        }

        val isOwner = dpm.isDeviceOwner(appContext.packageName)
        if (!isOwner) {
            current.put("status", "failed").put("error", "not_device_owner")
            apps.put(appKey, current)
            writeState(state.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, appKey, current)
            log(deviceId, deviceRootRef, "CRITICAL", "ota", "not_device_owner", JSONObject().put("app", appKey).put("package", spec.packageName))
            return
        }

        current.put("status", "downloading").put("download_started_at", now)
        apps.put(appKey, current)
        writeState(state.put("apps", apps))
        writeOtaState(deviceId, deviceRootRef, appKey, current)
        log(deviceId, deviceRootRef, "INFO", "ota", "download_started", JSONObject().put("app", appKey).put("url", spec.apkUrl))

        val dlFile = File(File(downloadsDir, appKey).apply { mkdirs() }, "${spec.versionCode}.apk")
        val attempt = downloadToFile(spec.apkUrl, dlFile)
        if (!attempt.ok) {
            current.put("status", "failed")
                .put("error", "download_failed")
                .put("error_detail", attempt.error)
                .put("download_http_code", attempt.httpCode)
                .put("download_bytes", attempt.bytes)
                .put("failed_at", System.currentTimeMillis())
            apps.put(appKey, current)
            writeState(state.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, appKey, current)
            log(
                deviceId,
                deviceRootRef,
                "ERROR",
                "ota",
                "download_failed",
                JSONObject()
                    .put("app", appKey)
                    .put("url", spec.apkUrl)
                    .put("http_code", attempt.httpCode)
                    .put("bytes", attempt.bytes)
                    .put("error", attempt.error),
            )
            return
        }

        val sum = sha256Hex(dlFile)
        if (!sum.equals(spec.checksumSha256.trim(), ignoreCase = true)) {
            runCatching { dlFile.delete() }
            current.put("status", "failed").put("error", "checksum_mismatch").put("failed_at", System.currentTimeMillis())
            apps.put(appKey, current)
            writeState(state.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, appKey, current)
            log(deviceId, deviceRootRef, "ERROR", "ota", "checksum_mismatch", JSONObject().put("app", appKey))
            return
        }

        val archive = parseArchive(dlFile)
        val apkPkg = archive?.packageName?.trim().orEmpty()
        if (!apkPkg.equals(spec.packageName, ignoreCase = true)) {
            runCatching { dlFile.delete() }
            current.put("status", "failed").put("error", "package_mismatch:$apkPkg").put("failed_at", System.currentTimeMillis())
            apps.put(appKey, current)
            writeState(state.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, appKey, current)
            log(deviceId, deviceRootRef, "ERROR", "ota", "package_mismatch", JSONObject().put("app", appKey).put("apk_pkg", apkPkg).put("expected", spec.packageName))
            return
        }

        val backupPath = backupInstalledApk(spec.packageName, installedVc)
        if (backupPath.isNotBlank()) {
            current.put("backup_apk_path", backupPath)
            current.put("previous_version_code", installedVc)
            current.put("previous_version_name", getInstalledVersionName(spec.packageName))
        }

        current.put("apk_path", dlFile.absolutePath)
        current.put("download_completed_at", System.currentTimeMillis())
        current.put("error", "")
        current.put("error_detail", "")
        current.put("download_http_code", attempt.httpCode)
        current.put("download_bytes", attempt.bytes)
        current.put("status", if (spec.scheduledAtMs > 0L && spec.scheduledAtMs > now) "scheduled" else "downloaded")
        apps.put(appKey, current)
        writeState(state.put("apps", apps))
        writeOtaState(deviceId, deviceRootRef, appKey, current)
        appendHistory(
            app = appKey,
            packageName = spec.packageName,
            fromVersionCode = installedVc,
            toVersionCode = spec.versionCode,
            status = "downloaded",
            now = System.currentTimeMillis(),
            source = "ota",
        )
        log(deviceId, deviceRootRef, "INFO", "ota", "download_completed", JSONObject().put("app", appKey).put("apk_path", dlFile.absolutePath))
    }

    private fun attemptRollbackIfPossible(
        deviceId: String,
        deviceRootRef: DatabaseReference,
        appKey: String,
        appState: JSONObject,
        apps: JSONObject,
        root: JSONObject,
        now: Long,
    ) {
        val backup = appState.optString("backup_apk_path", "").trim()
        val pkg = appState.optString("package", "").trim()
        if (backup.isBlank() || pkg.isBlank()) return
        val backupFile = File(backup)
        if (!backupFile.exists() || backupFile.length() <= 0L) return
        if (!dpm.isDeviceOwner(appContext.packageName)) return

        appState.put("rollback_status", "installing").put("rollback_started_at", now)
        apps.put(appKey, appState)
        writeState(root.put("apps", apps))
        writeOtaState(deviceId, deviceRootRef, appKey, appState)
        log(deviceId, deviceRootRef, "WARNING", "ota", "rollback_started", JSONObject().put("app", appKey).put("backup", backup))

        val res = install.installLocal(
            file = backupFile,
            request = ApkInstallRequest(
                url = "file://$backup",
                expectedPackage = pkg,
                expectedVersion = null,
                force = true,
                silent = true,
                autoOpen = false,
                sha256 = null,
            ),
            allowDowngrade = true,
        )
        if (res.status == "success") {
            appState.put("rollback_status", "success").put("rollback_completed_at", System.currentTimeMillis())
            apps.put(appKey, appState)
            writeState(root.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, appKey, appState)
            appendHistory(
                app = appKey,
                packageName = pkg,
                fromVersionCode = appState.optLong("version_code", -1L),
                toVersionCode = appState.optLong("previous_version_code", -1L),
                status = "rollback_success",
                now = System.currentTimeMillis(),
                source = "ota",
            )
            log(deviceId, deviceRootRef, "WARNING", "ota", "rollback_completed", JSONObject().put("app", appKey))
        } else {
            appState.put("rollback_status", "failed").put("rollback_error", res.message).put("rollback_failed_at", System.currentTimeMillis())
            apps.put(appKey, appState)
            writeState(root.put("apps", apps))
            writeOtaState(deviceId, deviceRootRef, appKey, appState)
            log(deviceId, deviceRootRef, "ERROR", "ota", "rollback_failed", JSONObject().put("app", appKey).put("error", res.message))
        }
    }

    private fun getInstalledVersionCode(packageName: String): Long {
        val pi = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull() ?: return -1L
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
    }

    private fun getInstalledVersionName(packageName: String): String {
        return runCatching {
            val pi =
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, 0)
                }
            pi.versionName?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun parseArchive(file: File): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            }
        }.getOrNull()
    }

    private fun downloadToFile(url: String, out: File): DownloadAttempt {
        fun formatThrowable(t: Throwable): String {
            val msg = "${t.javaClass.simpleName}:${t.message.orEmpty()}"
            val st = runCatching { t.stackTraceToString() }.getOrNull().orEmpty()
            val outText = if (st.isBlank()) msg else "$msg\n$st"
            return outText.take(1800)
        }

        return runCatching {
            out.parentFile?.mkdirs()
            runCatching { if (out.exists()) out.delete() }

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("User-Agent", "ArgosAgent/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
            }

            var bytes = 0L
            try {
                val codeRes = runCatching { conn.responseCode }
                if (codeRes.isFailure) {
                    return@runCatching DownloadAttempt(false, -1, 0L, "responseCode_exception:${formatThrowable(codeRes.exceptionOrNull()!!)}")
                }
                val code = codeRes.getOrThrow()
                if (code !in 200..299) {
                    runCatching { conn.errorStream?.close() }
                    val msg = runCatching { conn.responseMessage?.trim().orEmpty() }.getOrDefault("")
                    return@runCatching DownloadAttempt(false, code, 0L, "http_$code:${msg.take(120)}")
                }

                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(1024 * 64)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            output.write(buf, 0, r)
                            bytes += r.toLong()
                        }
                        output.flush()
                    }
                }
                val ok = out.exists() && out.length() > 0L
                DownloadAttempt(ok, code, bytes, if (ok) "" else "empty_file")
            } catch (e: Throwable) {
                DownloadAttempt(false, -1, bytes, formatThrowable(e))
            } finally {
                runCatching { conn.disconnect() }
            }
        }.getOrElse { e ->
            DownloadAttempt(false, -1, 0L, formatThrowable(e))
        }
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(1024 * 64)
            while (true) {
                val r = fis.read(buf)
                if (r <= 0) break
                md.update(buf, 0, r)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun backupInstalledApk(packageName: String, installedVersionCode: Long): String {
        if (installedVersionCode <= 0L) return ""
        val ai = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
        }.getOrNull() ?: return ""
        val src = ai.sourceDir?.trim().orEmpty()
        if (src.isBlank()) return ""
        val out = File(File(backupsDir, packageName).apply { mkdirs() }, "${installedVersionCode}.apk")
        if (out.exists() && out.length() > 0L) return out.absolutePath
        return runCatching {
            File(src).inputStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            if (out.exists() && out.length() > 0L) out.absolutePath else ""
        }.getOrDefault("")
    }

    fun hasPendingOrActiveUpdate(): Boolean {
        val apps = readState().optJSONObject("apps") ?: return false
        val keys = apps.keys().asSequence().toList()
        for (key in keys) {
            val appState = apps.optJSONObject(key) ?: continue
            val status = appState.optString("status", "").trim().lowercase(Locale.ROOT)
            if (status in ACTIVE_OTA_STATUSES) return true
            val rollbackStatus = appState.optString("rollback_status", "").trim().lowercase(Locale.ROOT)
            if (rollbackStatus == "installing") return true
        }
        return false
    }

    private fun readState(): JSONObject {
        if (!stateFile.exists()) return JSONObject().put("apps", JSONObject())
        val text = runCatching { stateFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty().trim()
        if (text.isBlank()) return JSONObject().put("apps", JSONObject())
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject().put("apps", JSONObject()))
    }

    private fun writeState(obj: JSONObject) {
        runCatching { stateFile.writeText(obj.toString(), Charsets.UTF_8) }
    }

    private fun readHistory(): JSONArray {
        if (!historyFile.exists()) return JSONArray()
        val text = runCatching { historyFile.readText(Charsets.UTF_8) }.getOrNull().orEmpty().trim()
        if (text.isBlank()) return JSONArray()
        return runCatching { JSONArray(text) }.getOrDefault(JSONArray())
    }

    private fun writeHistory(arr: JSONArray) {
        runCatching { historyFile.writeText(arr.toString(), Charsets.UTF_8) }
    }

    private fun appendHistory(
        app: String,
        packageName: String,
        fromVersionCode: Long,
        toVersionCode: Long,
        status: String,
        now: Long,
        source: String,
    ) {
        val h = readHistory()
        val entry = JSONObject()
            .put("timestamp", now)
            .put("app", app)
            .put("package", packageName)
            .put("from_version_code", fromVersionCode)
            .put("to_version_code", toVersionCode)
            .put("status", status)
            .put("source", source)
            .put("agent_version", BuildConfig.VERSION_NAME)
            .put("android_sdk", Build.VERSION.SDK_INT)
        h.put(entry)
        while (h.length() > 200) {
            val trimmed = JSONArray()
            for (i in (h.length() - 200) until h.length()) trimmed.put(h.opt(i))
            writeHistory(trimmed)
            return
        }
        writeHistory(h)
    }

    private fun writeOtaState(deviceId: String, deviceRootRef: DatabaseReference, appKey: String, state: JSONObject) {
        if (!FirebaseRuntimeMode.allowAgentPublish) return
        val out = HashMap<String, Any>()
        out["updated_at"] = System.currentTimeMillis()
        out["app"] = appKey
        out["package"] = state.optString("package", "")
        out["version_code"] = state.optLong("version_code", 0L)
        out["version_name"] = state.optString("version_name", "")
        out["status"] = state.optString("status", "")
        out["mandatory"] = state.optBoolean("mandatory", true)
        out["scheduled_at_ms"] = state.optLong("scheduled_at_ms", 0L)
        out["download_started_at"] = state.optLong("download_started_at", 0L)
        out["download_completed_at"] = state.optLong("download_completed_at", 0L)
        out["install_started_at"] = state.optLong("install_started_at", 0L)
        out["installed_at"] = state.optLong("installed_at", 0L)
        out["error"] = state.optString("error", "")
        out["error_detail"] = state.optString("error_detail", "")
        out["download_http_code"] = state.optInt("download_http_code", 0)
        out["download_bytes"] = state.optLong("download_bytes", 0L)
        runCatching { deviceRootRef.child("ota_state").child(appKey).updateChildren(out) }
    }

    private fun pushLocationTelemetryNow(deviceId: String, deviceRootRef: DatabaseReference, now: Long) {
        if (!FirebaseRuntimeMode.allowAgentPublish) return
        val loc = readBestLastKnownLocation()
        val out = HashMap<String, Any>()
        out["location_updated_at"] = now
        if (loc != null) {
            out["gps_available"] = true
            out["latitude"] = loc.latitude
            out["longitude"] = loc.longitude
            out["accuracy_m"] = loc.accuracy.toDouble()
            out["speed_mps"] = loc.speed.toDouble()
            out["provider"] = loc.provider?.trim().orEmpty()
            out["location_timestamp"] = loc.time
        } else {
            out["gps_available"] = false
        }
        runCatching { deviceRootRef.child("telemetry").updateChildren(out) }
    }

    private fun readBestLastKnownLocation(): Location? {
        val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        var best: Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            if (best == null || loc.time > best!!.time) best = loc
        }
        return best
    }

    private fun log(deviceId: String, deviceRootRef: DatabaseReference, level: String, category: String, message: String, details: JSONObject) {
        if (!FirebaseRuntimeMode.allowAgentPublish) return
        val now = System.currentTimeMillis()
        val entry = hashMapOf<String, Any>(
            "timestamp" to now,
            "level" to level,
            "category" to category,
            "message" to message,
            "details" to details.toString(),
            "app_version" to BuildConfig.VERSION_NAME,
            "android_version" to (Build.VERSION.RELEASE?.trim().orEmpty()),
        )
        runCatching { deviceRootRef.child("logs").push().setValue(entry) }
    }

    companion object {
        private val ACTIVE_OTA_STATUSES = setOf("downloading", "downloaded", "scheduled", "installing", "rollback")
    }
}
