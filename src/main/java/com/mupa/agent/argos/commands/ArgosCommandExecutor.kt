package com.mupa.agent.argos.commands

import android.content.Context
import com.mupa.agent.argos.managers.SettingsManager
import org.json.JSONObject

data class ExecOutcome(
    val status: String,
    val message: String,
)

class ArgosCommandExecutor(
    private val appContext: Context,
    private val settingsManager: SettingsManager,
) {
    fun execute(deviceId: String, cmd: LocalQueuedCommand): ExecOutcome {
        val params = runCatching { JSONObject(cmd.paramsJson) }.getOrDefault(JSONObject())
        val result = CommandDispatcher(appContext).execute(cmd.commandId, cmd.command.trim(), params)
        return ExecOutcome(result.status, result.message)
    }
}
