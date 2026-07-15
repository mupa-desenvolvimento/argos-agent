package com.mupa.agent.argos.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mupa.agent.argos.commands.CommandDispatcher
import org.json.JSONObject

class AdbCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_ADB_COMMAND) return

        val command = intent.getStringExtra(EXTRA_COMMAND)?.trim().orEmpty()
        if (command.isBlank()) {
            resultCode = 1
            resultData = "failed:command_empty"
            return
        }

        val params = JSONObject()
        intent.extras?.keySet()?.forEach { key ->
            if (key == EXTRA_COMMAND) return@forEach
            params.put(key, intent.extras?.get(key))
        }

        val result = runCatching {
            CommandDispatcher(context.applicationContext).execute(
                commandId = "adb_broadcast",
                command = command,
                params = params,
            )
        }.getOrElse { t ->
            resultCode = 1
            resultData = "failed:${t.message?.trim().orEmpty().ifBlank { t.javaClass.simpleName }}"
            return
        }

        resultCode = if (result.status.equals("success", ignoreCase = true)) 0 else 1
        resultData = "${result.status}:${result.message}"
    }

    companion object {
        const val ACTION_ADB_COMMAND = "com.mupa.agent.argos.action.ADB_COMMAND"
        const val EXTRA_COMMAND = "command"
    }
}
