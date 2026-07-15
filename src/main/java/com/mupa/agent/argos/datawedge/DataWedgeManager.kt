package com.mupa.agent.argos.datawedge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
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
