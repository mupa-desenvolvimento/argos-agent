package com.mupa.agent.argos.datawedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Controls Zebra DataWedge through its public broadcast Intent API
 * (com.symbol.datawedge.api.ACTION) — no EMDK/SDK needed, DataWedge ships pre-installed on
 * every Zebra collector (TC22, ET45, etc.) and just listens for these intents at the OS level.
 * Reference: https://techdocs.zebra.com/datawedge/latest/guide/api/
 */
class DataWedgeManager(context: Context) {
    private val appContext = context.applicationContext

    fun switchProfile(profileName: String): Boolean {
        if (profileName.isBlank()) return false
        sendCommand("com.symbol.datawedge.api.SWITCH_TO_PROFILE", profileName)
        return true
    }

    fun setEnabled(enabled: Boolean) {
        sendCommand("com.symbol.datawedge.api.ENABLE_DATAWEDGE", enabled)
    }

    fun softScanTrigger(start: Boolean) {
        sendCommand("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", if (start) "START_SCANNING" else "STOP_SCANNING")
    }

    /** Resets every DataWedge profile/plugin back to factory defaults. */
    fun restoreDefaults() {
        sendCommand("com.symbol.datawedge.api.RESET_DEFAULT", "all-plugins")
    }

    /** Creates a DataWedge profile (no-op inside DataWedge if it already exists). */
    fun createProfile(name: String): Boolean {
        if (name.isBlank()) return false
        val intent = Intent(API_ACTION)
        intent.putExtra("com.symbol.datawedge.api.CREATE_PROFILE", name)
        appContext.sendBroadcast(intent)
        return true
    }

    /**
     * Configures a DataWedge profile the same way the Zebra DataWedge app does: which
     * symbologies are decoded, the scan/aim mode (trigger, continuous, presentation…),
     * whether the scanner input is enabled ("leitor sempre ligado"), and the output plugin
     * (keystroke and/or intent). Driven by a generic [config] so the exact DataWedge param
     * names/values can be tuned from the web without recompiling the agent.
     *
     * Expected shape:
     * {
     *   "profile": "MupaScan",
     *   "create": true,                       // CREATE_IF_NOT_EXIST vs UPDATE
     *   "enabled": true,                      // PROFILE_ENABLED
     *   "barcode": { "scanner_input_enabled":"true", "decoder_ean13":"true", "aim_type":"0", ... },
     *   "keystroke_output": true,             // KEYSTROKE plugin on/off
     *   "intent_output": false,               // INTENT plugin on/off
     *   "intent_action": "com.mupa...SCAN",   // when intent_output
     *   "intent_delivery": "2",               // 0 start activity, 1 start service, 2 broadcast
     *   "associate": ["com.mupa.player.enterprise"]  // app packages bound to this profile
     * }
     */
    fun setConfig(config: JSONObject): Boolean {
        val profile = config.optString("profile").trim()
        if (profile.isBlank()) return false

        val profileConfig = Bundle().apply {
            putString("PROFILE_NAME", profile)
            putString("PROFILE_ENABLED", if (config.optBoolean("enabled", true)) "true" else "false")
            putString("CONFIG_MODE", if (config.optBoolean("create", true)) "CREATE_IF_NOT_EXIST" else "UPDATE")
        }

        val plugins = ArrayList<Bundle>()

        // BARCODE plugin — symbologies + scanner/reader params.
        config.optJSONObject("barcode")?.let { barcode ->
            val params = Bundle()
            for (key in barcode.keys()) {
                params.putString(key, barcode.get(key).toString())
            }
            plugins.add(pluginBundle("BARCODE", params))
        }

        // KEYSTROKE output plugin.
        if (config.has("keystroke_output")) {
            val params = Bundle().apply {
                putString("keystroke_output_enabled", if (config.optBoolean("keystroke_output", true)) "true" else "false")
            }
            plugins.add(pluginBundle("KEYSTROKE", params))
        }

        // INTENT output plugin.
        if (config.has("intent_output")) {
            val on = config.optBoolean("intent_output", false)
            val params = Bundle().apply {
                putString("intent_output_enabled", if (on) "true" else "false")
                if (on) {
                    config.optString("intent_action").trim().takeIf { it.isNotBlank() }?.let { putString("intent_action", it) }
                    putString("intent_delivery", config.optString("intent_delivery", "2"))
                }
            }
            plugins.add(pluginBundle("INTENT", params))
        }

        if (plugins.isNotEmpty()) {
            profileConfig.putParcelableArrayList("PLUGIN_CONFIG", plugins)
        }

        // Bind app packages to this profile (so scans reach them).
        config.optJSONArray("associate")?.let { arr ->
            val appList = ArrayList<Bundle>()
            for (i in 0 until arr.length()) {
                val pkg = arr.optString(i).trim()
                if (pkg.isBlank()) continue
                appList.add(Bundle().apply {
                    putString("PACKAGE_NAME", pkg)
                    putStringArray("ACTIVITY_LIST", arrayOf("*"))
                })
            }
            if (appList.isNotEmpty()) profileConfig.putParcelableArrayList("APP_LIST", appList)
        }

        val intent = Intent(API_ACTION)
        intent.putExtra("com.symbol.datawedge.api.SET_CONFIG", profileConfig)
        appContext.sendBroadcast(intent)
        return true
    }

    private fun pluginBundle(pluginName: String, params: Bundle): Bundle {
        return Bundle().apply {
            putString("PLUGIN_NAME", pluginName)
            putString("RESET_CONFIG", "false")
            putBundle("PARAM_LIST", params)
        }
    }

    /**
     * Synchronously asks DataWedge for its version info + active profile, blocking up to
     * [timeoutMs] for the async `RESULT_ACTION` broadcast DataWedge sends back (GET_* commands
     * are never answered inline — they always reply on a separate broadcast). Returns null on
     * timeout, e.g. on a non-Zebra device with no DataWedge installed.
     */
    fun getVersionInfo(timeoutMs: Long = 4_000L): JSONObject? {
        val latch = CountDownLatch(1)
        var resultJson: JSONObject? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val bundle = intent.extras ?: return
                val json = JSONObject()
                for (key in bundle.keySet()) {
                    runCatching { json.put(key, bundle.get(key)?.toString()) }
                }
                resultJson = json
                latch.countDown()
            }
        }
        val filter = IntentFilter(RESULT_ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(receiver, filter)
            }
        }
        try {
            // GET_VERSION_INFO predates the generic COMMAND/EXTRA_PARAMETER pair format and
            // some DataWedge builds only recognize it as a direct extra key (legacy form) —
            // logcat showed "Unknown intent type" when sent via the modern pair format used
            // by every other command here, so this one needs its own direct-extra intent.
            val intent = Intent(API_ACTION)
            intent.putExtra("com.symbol.datawedge.api.GET_VERSION_INFO", "")
            intent.putExtra("SEND_RESULT", "true")
            appContext.sendBroadcast(intent)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
        }
        return resultJson
    }

    private fun sendCommand(command: String, parameter: Any, expectResult: Boolean = false) {
        val intent = Intent(API_ACTION)
        intent.putExtra("COMMAND", command)
        when (parameter) {
            is Boolean -> intent.putExtra("EXTRA_PARAMETER", parameter)
            else -> intent.putExtra("EXTRA_PARAMETER", parameter.toString())
        }
        if (expectResult) {
            intent.putExtra("SEND_RESULT", "true")
        }
        appContext.sendBroadcast(intent)
    }

    companion object {
        private const val API_ACTION = "com.symbol.datawedge.api.ACTION"
        private const val RESULT_ACTION = "com.symbol.datawedge.api.RESULT_ACTION"
    }
}
