package com.mupa.agent.argos.provisioning

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import com.mupa.agent.argos.apps.ApplicationManager
import com.mupa.agent.argos.commands.LocalCommandStore
import com.mupa.agent.argos.managers.DeviceProfileManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.models.AgentDeviceFamily
import com.mupa.agent.argos.remote.RemoteAccessManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ProvisioningManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val apps = ApplicationManager(appContext)
    private val store = LocalCommandStore(appContext)
    private val remote = RemoteAccessManager(appContext)

    suspend fun runIfDue(): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val last = settings.getProvisioningLastAttemptMsCached()
        if (now - last < MIN_INTERVAL_MS) return@withContext false
        runNowInternal(now)
        true
    }

    suspend fun runNow(): Unit = withContext(Dispatchers.IO) {
        runNowInternal(System.currentTimeMillis())
    }

    private suspend fun runNowInternal(now: Long) {
        settings.setProvisioningLastAttemptMs(now)
        val t0 = SystemClock.elapsedRealtime()

        val anyDeskPkg = "com.anydesk.anydeskandroid"
        val mplayerPkg = "com.mupa.player.enterprise"
        val zebraDataWedgePkgs = listOf("com.symbol.datawedge", "com.zebra.datawedge")
        val deviceProfile = DeviceProfileManager().resolve()

        val anyDeskUrl = settings.getAnyDeskApkUrlCached().ifBlank { DEFAULT_ANYDESK_URL }
        val mplayerUrl = settings.getMplayerApkUrlCached().trim()
        val pluginSpecs = settings.getAnyDeskPluginSpecsCached()
        val pluginPkgsFromSpec = pluginSpecs.mapNotNull { it.packageName.trim().takeIf { s -> s.isNotBlank() } }
        val expectedPluginPkgs = when {
            pluginPkgsFromSpec.isNotEmpty() -> pluginPkgsFromSpec
            pluginSpecs.isEmpty() -> DEFAULT_ANYDESK_PLUGIN_PACKAGES
            else -> emptyList()
        }

        val results = JSONObject()
        val owner = appContext.packageName
        Log.i(TAG, "start snapshot=${settings.provisioningSnapshotJson()}")

        val finalized = settings.getMdmLockedCached() || settings.getKioskModeCached()
        if (!finalized) {
            val payload = JSONObject()
                .put("status", "skipped")
                .put("reason", "not_finalized")
                .put("snapshot", settings.provisioningSnapshotJson())
            store.insertCommandLog(
                source = "provisioning",
                commandId = null,
                command = "ensure_apps",
                status = "skipped",
                message = "not_finalized",
                durationMs = SystemClock.elapsedRealtime() - t0,
                payloadJson = payload.toString(),
                createdAt = System.currentTimeMillis(),
            )
            Log.i(TAG, "skipped reason=not_finalized")
            return
        }

        // Don't override the group-configured whitelist here — ConfigurationEngine manages it.
        // We only install infrastructure apps (AnyDesk, MPlayer) without touching the whitelist.

        val installedAnyDesk = apps.isInstalled(anyDeskPkg)
        val installedMplayer = apps.isInstalled(mplayerPkg)

        results.put("anydesk_installed_before", installedAnyDesk)
        results.put("anydesk_plugins_expected", JSONArray(expectedPluginPkgs))
        results.put(
            "anydesk_plugins_installed_before",
            JSONArray(expectedPluginPkgs.filter { apps.isInstalled(it) }),
        )
        results.put("mplayer_installed_before", installedMplayer)

        if (!installedAnyDesk) {
            results.put("anydesk_install", tryInstallFromUrl(owner, anyDeskPkg, anyDeskUrl))
            waitForInstall(anyDeskPkg, timeoutMs = 120_000L)
        }

        val pluginRes = JSONObject()
        val pluginAttempts = ArrayList<Any>()
        if (pluginSpecs.isNotEmpty()) {
            for (spec in pluginSpecs) {
                val pkg = spec.packageName.trim()
                val url = spec.url.trim()
                if (pkg.isNotBlank() && apps.isInstalled(pkg)) continue
                if (url.isNotBlank()) {
                    pluginAttempts += tryInstallFromUrl(owner, pkg.ifBlank { null }, url)
                    if (pkg.isNotBlank()) waitForInstall(pkg, timeoutMs = 60_000L) else delay(30_000L)
                    delay(2_000L)
                } else if (pkg.isNotBlank()) {
                    pluginAttempts += JSONObject()
                        .put("expected_package", pkg)
                        .put("status", "missing_url")
                        .put("store_fallback", "disabled")
                }
            }
        } else {
            val out = ArrayList<Any>()
            for (pkg in expectedPluginPkgs) {
                if (apps.isInstalled(pkg)) continue
                out += JSONObject()
                    .put("expected_package", pkg)
                    .put("status", "missing_plugins_config")
                    .put("store_fallback", "disabled")
            }
            pluginAttempts += JSONObject().put("status", "missing_plugins_config").put("attempts", JSONArray(out))
        }
        pluginRes.put("attempts", pluginAttempts)
        results.put("anydesk_plugin_install", pluginRes)

        if (!installedMplayer) {
            results.put(
                "mplayer_install",
                if (mplayerUrl.isBlank()) {
                    JSONObject()
                        .put("status", "missing_url")
                        .put("store_fallback", "disabled")
                }
                else {
                    val r = tryInstallFromUrl(owner, mplayerPkg, mplayerUrl)
                    waitForInstall(mplayerPkg, timeoutMs = 120_000L)
                    r
                },
            )
        }

        val anyDeskOk = apps.isInstalled(anyDeskPkg)
        val mplayerOk = apps.isInstalled(mplayerPkg)
        val missingPlugins = expectedPluginPkgs.filterNot { apps.isInstalled(it) }
        val pluginsOk = missingPlugins.isEmpty()

        results.put("anydesk_installed_after", anyDeskOk)
        results.put("anydesk_plugins_installed_after", JSONArray(expectedPluginPkgs.filter { apps.isInstalled(it) }))
        results.put("anydesk_plugins_missing_after", JSONArray(missingPlugins))
        results.put("mplayer_installed_after", apps.isInstalled(mplayerPkg))

        val ok = anyDeskOk && pluginsOk && mplayerOk
        if (ok) settings.setProvisioningLastSuccessMs(System.currentTimeMillis())

        results.put("remote_access", remote.anyDeskSnapshot(anyDeskPkg, expectedPluginPkgs.toSet()))

        store.insertCommandLog(
            source = "provisioning",
            commandId = null,
            command = "ensure_apps",
            status = if (ok) "success" else "failed",
            message = if (ok) "ok" else "partial",
            durationMs = SystemClock.elapsedRealtime() - t0,
            payloadJson = results.toString(),
            createdAt = System.currentTimeMillis(),
        )
        Log.i(TAG, "done status=${if (ok) "success" else "partial"} results=$results")
    }

    private suspend fun waitForInstall(packageName: String, timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (SystemClock.elapsedRealtime() < deadline) {
            if (apps.isInstalled(packageName)) return
            delay(1_000L)
        }
    }

    private fun tryInstallFromUrl(ownerPackage: String, expectedPackage: String?, url: String): JSONObject {
        val result = JSONObject()
        result.put("expected_package", expectedPackage.orEmpty())
        result.put("url", url)
        val file = runCatching { downloadApk(url) }.getOrNull()
        if (file == null || !file.exists() || file.length() <= 0L) {
            result.put("status", "download_failed")
            return result
        }
        val uri: Uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val installIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val err = runCatching {
            appContext.startActivity(installIntent)
            null
        }.getOrElse { t -> t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName } }
        result.put("status", if (err == null) "install_ui_started" else "install_ui_failed")
        if (err != null) result.put("error", err)
        Log.i(TAG, "install expectedPackage=${expectedPackage.orEmpty()} status=${result.optString("status")} err=${result.optString("error")}")
        return result
    }

    private fun downloadApk(url: String): File {
        val dir = File(appContext.filesDir, "apks").apply { mkdirs() }
        val safeName = url.trim().takeLast(80).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val out = File(dir, safeName.ifBlank { "download.apk" })
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
        }
        conn.inputStream.use { input ->
            out.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return out
    }

    companion object {
        private const val TAG = "ArgosProvisioning"
        private const val MIN_INTERVAL_MS = 10 * 60_000L
        private const val DEFAULT_ANYDESK_URL = "https://download.anydesk.com/anydesk.apk"
        private val DEFAULT_ANYDESK_PLUGIN_PACKAGES = listOf(
            "com.anydesk.adcontrol.ad1",
        )
    }
}
