package com.mupa.agent.argos.provisioning

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.mupa.agent.argos.mdm.DeviceOwnerPolicyManager
import com.mupa.agent.argos.receivers.PackageInstallReceiver
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ApkInstallRequest(
    val url: String,
    val expectedPackage: String?,
    val expectedVersion: String?,
    val force: Boolean,
    val silent: Boolean,
    val autoOpen: Boolean,
    val sha256: String?,
)

data class ApkInstallResult(
    val status: String,
    val message: String,
    val packageName: String?,
    val versionName: String?,
    val opened: Boolean,
)

data class ApkUninstallResult(
    val status: String,
    val message: String,
)

class ApkInstallManager(private val context: Context) {
    private val appContext = context.applicationContext
    // Only the agent's own package belongs here — this set exists purely to stop the agent
    // from uninstalling/misrouting *itself*. "com.mupa.player.enterprise" (MPlayer) used to
    // be listed too as a workaround for a since-fixed web-side bug where argos_agent and
    // mplayer had their packages swapped (see ota.ts in argus-device-hub). With that fixed,
    // treating MPlayer's real package as a self-alias made normalizeExpectedPackage()
    // silently rewrite every MPlayer install request to the agent's own package, so every
    // MPlayer APK install failed with "package_mismatch" while only the agent ever updated.
    private val selfPackageAliases = setOf(
        appContext.packageName.lowercase(),
    )

    fun install(request: ApkInstallRequest, timeoutMs: Long = 180_000L): ApkInstallResult {
        if (request.url.isBlank()) return ApkInstallResult("failed", "url_empty", null, null, opened = false)

        val file = downloadApk(request.url) ?: return ApkInstallResult("failed", "download_failed", null, null, opened = false)
        return installDownloaded(file = file, request = request, allowDowngrade = false, timeoutMs = timeoutMs)
    }

    /** Downloads [url] and launches the system installer UI (no device owner required). */
    fun installViaUi(url: String, sha256: String? = null): ApkInstallResult {
        if (url.isBlank()) return ApkInstallResult("failed", "url_empty", null, null, opened = false)
        val file = downloadApk(url) ?: return ApkInstallResult("failed", "download_failed", null, null, opened = false)
        if (!sha256.isNullOrBlank()) {
            val sum = sha256Hex(file)
            if (!sum.equals(sha256.trim(), ignoreCase = true)) {
                return ApkInstallResult("failed", "checksum_mismatch", null, null, opened = false)
            }
        }
        val outcome = uiInstall(file)
        return ApkInstallResult(outcome.status, outcome.message, null, null, opened = false)
    }

    fun installLocal(
        file: File,
        request: ApkInstallRequest,
        allowDowngrade: Boolean,
        timeoutMs: Long = 180_000L,
    ): ApkInstallResult {
        return installDownloaded(file = file, request = request, allowDowngrade = allowDowngrade, timeoutMs = timeoutMs)
    }

    fun uninstall(packageName: String, silent: Boolean, timeoutMs: Long = 120_000L): ApkUninstallResult {
        val pkg = packageName.trim()
        if (pkg.isBlank()) return ApkUninstallResult("failed", "package_empty")
        if (isSelfPackage(pkg)) return ApkUninstallResult("failed", "package_protected")

        val installed = getInstalledPackageInfo(pkg)
        if (installed == null) return ApkUninstallResult("success", "already_uninstalled")

        val dpm = DeviceOwnerPolicyManager(appContext)
        val isDo = dpm.isDeviceOwner(appContext.packageName)
        val canSilent = isDo && silent

        val outcome = if (canSilent) {
            silentUninstall(pkg, timeoutMs = timeoutMs)
        } else {
            if (!isDo && silent) {
                return ApkUninstallResult("failed", "silent_unavailable:not_device_owner")
            }
            uiUninstall(pkg)
        }

        return ApkUninstallResult(outcome.status, outcome.message)
    }

    private fun installDownloaded(
        file: File,
        request: ApkInstallRequest,
        allowDowngrade: Boolean,
        timeoutMs: Long,
    ): ApkInstallResult {
        val archiveInfo = parseArchive(file)
            ?: return ApkInstallResult("failed", "apk_parse_failed", null, null, opened = false)
        val apkPkg = archiveInfo.packageName?.trim().orEmpty()
        if (apkPkg.isBlank()) return ApkInstallResult("failed", "apk_package_empty", null, null, opened = false)
        val expectedPackage = normalizeExpectedPackage(request.expectedPackage, apkPkg)
        if (!expectedPackage.isNullOrBlank() && !apkPkg.equals(expectedPackage, ignoreCase = true)) {
            return ApkInstallResult("failed", "package_mismatch:$apkPkg", apkPkg, archiveInfo.versionName, opened = false)
        }

        if (!request.sha256.isNullOrBlank()) {
            val sum = sha256Hex(file)
            if (!sum.equals(request.sha256.trim(), ignoreCase = true)) {
                return ApkInstallResult("failed", "checksum_mismatch", apkPkg, archiveInfo.versionName, opened = false)
            }
        }


        val installed = getInstalledPackageInfo(apkPkg)
        if (installed != null && !request.force) {
            val installedVer = installed.versionName?.trim().orEmpty()
            val targetVer = request.expectedVersion?.trim().orEmpty()
            if (targetVer.isNotBlank() && installedVer.equals(targetVer, ignoreCase = true)) {
                return ApkInstallResult("ignored", "version_already_installed", apkPkg, installedVer, opened = false)
            }
            if (targetVer.isBlank() && archiveInfo.versionName?.trim().orEmpty().equals(installedVer, ignoreCase = true)) {
                return ApkInstallResult("ignored", "version_already_installed", apkPkg, installedVer, opened = false)
            }
        }

        val dpm = DeviceOwnerPolicyManager(appContext)
        val isDo = dpm.isDeviceOwner(appContext.packageName)
        val canSilent = isDo && request.silent

        val installOutcome = if (canSilent) {
            silentInstall(file, expectedPackage = apkPkg, timeoutMs = timeoutMs, allowDowngrade = allowDowngrade)
        } else {
            if (!isDo && request.silent) {
                return ApkInstallResult("failed", "silent_unavailable:not_device_owner", apkPkg, archiveInfo.versionName, opened = false)
            }
            uiInstall(file)
        }

        if (installOutcome.status != "success") {
            return ApkInstallResult(installOutcome.status, installOutcome.message, apkPkg, archiveInfo.versionName, opened = false)
        }

        val opened = if (request.autoOpen) openApp(apkPkg) else false
        val installedAfter = getInstalledPackageInfo(apkPkg)
        return ApkInstallResult(
            status = "success",
            message = if (opened) "installed_and_opened" else "installed",
            packageName = apkPkg,
            versionName = installedAfter?.versionName ?: archiveInfo.versionName,
            opened = opened,
        )
    }

    private fun downloadApk(url: String): File? {
        val dir = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "apks").apply { mkdirs() }
        val name = url.substringAfterLast('/').takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "update.apk"
        val out = File(dir, name)
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (!out.exists() || out.length() <= 0L) null else out
        }.getOrNull()
    }

    private fun parseArchive(file: File): PackageInfo? {
        val pm = appContext.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(file.absolutePath, 0)
            }
        }.getOrNull()
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

    private fun getInstalledPackageInfo(packageName: String): PackageInfo? {
        val pm = appContext.packageManager
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
    }

    private data class SessionOutcome(val status: String, val message: String)

    private fun silentInstall(file: File, expectedPackage: String, timeoutMs: Long, allowDowngrade: Boolean): SessionOutcome {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(expectedPackage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = runCatching { installer.createSession(params) }.getOrNull() ?: return SessionOutcome("failed", "create_session_failed")
        val action = "com.mupa.agent.argos.action.PACKAGE_INSTALL_COMMIT"
        val deferred = PackageInstallReceiver.registerWaiter(sessionId)
        val pi = PendingIntent.getBroadcast(
            appContext,
            sessionId,
            Intent(action).setPackage(appContext.packageName).putExtra("session_id", sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        var session: PackageInstaller.Session? = null
        return runCatching {
            session = installer.openSession(sessionId)
            FileInputStream(file).use { input ->
                session!!.openWrite("base.apk", 0, file.length()).use { output ->
                    input.copyTo(output)
                    session!!.fsync(output)
                }
            }
            session!!.commit(pi.intentSender)

            val t0 = SystemClock.elapsedRealtime()
            val res = deferred.get(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            val elapsed = SystemClock.elapsedRealtime() - t0
            if (res.status == PackageInstaller.STATUS_SUCCESS) {
                SessionOutcome("success", "installed:${elapsed}ms")
            } else {
                SessionOutcome("failed", "install_failed:${res.status}:${res.message}")
            }
        }.getOrElse { t ->
            SessionOutcome("failed", t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName })
        }.also {
            runCatching { session?.close() }
            PackageInstallReceiver.unregisterWaiter(sessionId)
        }
    }

    private fun silentUninstall(packageName: String, timeoutMs: Long): SessionOutcome {
        val installer = appContext.packageManager.packageInstaller
        val action = "com.mupa.agent.argos.action.PACKAGE_UNINSTALL_COMMIT"
        val uninstallId = ((SystemClock.elapsedRealtime() and 0x7fffffff).toInt()).coerceAtLeast(1)
        val deferred = PackageInstallReceiver.registerUninstallWaiter(uninstallId)
        val pi = PendingIntent.getBroadcast(
            appContext,
            uninstallId,
            Intent(action).setPackage(appContext.packageName).putExtra("uninstall_id", uninstallId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return runCatching {
            installer.uninstall(packageName, pi.intentSender)
            val t0 = SystemClock.elapsedRealtime()
            val res = deferred.get(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            val elapsed = SystemClock.elapsedRealtime() - t0
            if (res.status == PackageInstaller.STATUS_SUCCESS) {
                SessionOutcome("success", "uninstalled:${elapsed}ms")
            } else {
                SessionOutcome("failed", "uninstall_failed:${res.status}:${res.message}")
            }
        }.getOrElse { t ->
            SessionOutcome("failed", t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName })
        }.also {
            PackageInstallReceiver.unregisterUninstallWaiter(uninstallId)
        }
    }

    private fun uiInstall(file: File): SessionOutcome {
        val uri: Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val err = runCatching { appContext.startActivity(i); null }
            .getOrElse { it.message?.trim().orEmpty().ifBlank { it.javaClass.simpleName } }
        return if (err == null) SessionOutcome("success", "install_ui_started") else SessionOutcome("failed", err)
    }

    private fun uiUninstall(packageName: String): SessionOutcome {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val err = runCatching { appContext.startActivity(i); null }
            .getOrElse { it.message?.trim().orEmpty().ifBlank { it.javaClass.simpleName } }
        return if (err == null) SessionOutcome("success", "uninstall_ui_started") else SessionOutcome("failed", err)
    }

    private fun openApp(packageName: String): Boolean {
        val i = appContext.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { appContext.startActivity(i); true }.getOrDefault(false)
    }

    private fun isSelfPackage(packageName: String): Boolean {
        return selfPackageAliases.contains(packageName.trim().lowercase())
    }

    private fun normalizeExpectedPackage(expectedPackage: String?, apkPackageName: String): String? {
        val expected = expectedPackage?.trim().orEmpty()
        if (expected.isBlank()) return null
        val normalizedExpected = if (isSelfPackage(expected)) appContext.packageName else expected
        return if (isSelfPackage(apkPackageName)) appContext.packageName else normalizedExpected
    }
}
