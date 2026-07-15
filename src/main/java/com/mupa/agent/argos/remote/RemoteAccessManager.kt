package com.mupa.agent.argos.remote

import android.content.Context
import android.provider.Settings
import com.mupa.agent.argos.apps.ApplicationManager
import org.json.JSONArray
import org.json.JSONObject

class RemoteAccessManager(private val context: Context) {
    private val appContext = context.applicationContext
    private val apps = ApplicationManager(appContext)

    private val healthManager = RemoteHealthManager(appContext)

    fun ensureRunning() {
        try {
            healthManager.runIfDue()
        } catch (e: Exception) {
            android.util.Log.w("RemoteAccess", "Health check error: ${e.message}")
        }
    }

    fun getRemoteHealthSummary(): JSONObject = healthManager.getHealthSummary()

    fun anyDeskSnapshot(
        anyDeskPackage: String,
        pluginPackages: Set<String>,
    ): JSONObject {
        val enabledFlag = runCatching {
            Settings.Secure.getInt(appContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        val enabledServicesRaw = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        }.getOrDefault("")

        val enabledComponents = enabledServicesRaw
            .split(':')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        fun pkgEnabled(pkg: String): Boolean {
            val prefix = pkg.trim().lowercase() + "/"
            return enabledFlag && enabledComponents.any { it.lowercase().startsWith(prefix) }
        }

        val anyDeskInstalled = apps.isInstalled(anyDeskPackage)
        val installedPlugins = pluginPackages.filter { apps.isInstalled(it) }.toSet()
        val missingPlugins = pluginPackages.filterNot { apps.isInstalled(it) }.toSet()

        val anyDeskAccess = pkgEnabled(anyDeskPackage)
        val pluginAccessByPkg = JSONObject().apply {
            for (pkg in pluginPackages) put(pkg, pkgEnabled(pkg))
        }

        val anyPluginAccess = pluginPackages.any { pkgEnabled(it) }
        val ok = anyDeskInstalled && (pluginPackages.isEmpty() || installedPlugins.isNotEmpty()) && (anyDeskAccess || anyPluginAccess)

        return JSONObject()
            .put("ok", ok)
            .put("accessibility_enabled", enabledFlag)
            .put("enabled_accessibility_services", enabledServicesRaw)
            .put("anydesk_installed", anyDeskInstalled)
            .put("anydesk_accessibility_enabled", anyDeskAccess)
            .put("plugins_expected", JSONArray(pluginPackages.toList()))
            .put("plugins_installed", JSONArray(installedPlugins.toList()))
            .put("plugins_missing", JSONArray(missingPlugins.toList()))
            .put("plugin_accessibility_enabled_by_pkg", pluginAccessByPkg)
    }
}
