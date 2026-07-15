package com.mupa.agent.argos.core

import android.content.Context
import com.mupa.agent.argos.commands.ArgosCommandExecutor
import com.mupa.agent.argos.commands.ArgosCommandResult
import com.mupa.agent.argos.commands.LocalCommandStore
import com.mupa.agent.argos.database.ArgosDatabaseProvider
import com.mupa.agent.argos.database.CommandEntity
import com.mupa.agent.argos.database.ExecutedCommandEntity
import com.mupa.agent.argos.database.LogEntity
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.SettingsManager
import com.mupa.agent.argos.models.AgentCommandState
import com.mupa.agent.argos.models.AgentLogCategory
import com.mupa.agent.argos.network.AgentControlApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class CommandEngine(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val settings = SettingsManager(appContext)
    private val localStore = LocalCommandStore(appContext)
    private val network = AgentControlApi(appContext, settings)
    private val executor = ArgosCommandExecutor(appContext, settings)
    private val db = ArgosDatabaseProvider.get(appContext)
    private val identity = DeviceIdentityManager(appContext)

    suspend fun runCycle(limit: Int = 10): Int = withContext(Dispatchers.IO) {
        val deviceId = identity.getPersistentId().trim()
        if (deviceId.isBlank()) return@withContext 0

        // No-ops once a token is cached — see ensureRegistered()'s doc comment for why this
        // is required before any of the calls below can succeed. Uses the bare Android serial
        // (matching public.devices.serial / what the web console displays), falling back to
        // the agent's own persistent id if the serial can't be resolved for some reason.
        runCatching {
            val serial = identity.getDeviceFingerprint().serial?.trim().orEmpty().ifBlank { deviceId }
            network.ensureRegistered(serial)
            // Independent of whether the Supabase registration/heartbeat pipeline is
            // healthy right now — guarantees this device shows up in the web console
            // (which lists from Firebase, not Supabase) even if heartbeat is down.
            network.ensureFirebasePresence(serial)
        }

        val now = System.currentTimeMillis()
        val pending = network.pollPendingCommands(deviceId)
        if (pending.isNotEmpty()) {
            localStore.upsertPending(pending, now)
            pending.forEach { remote ->
                val token = buildExecutionToken(deviceId, remote.commandId, remote.command, remote.timestamp)
                db.commandDao().upsert(
                    CommandEntity(
                        commandId = remote.commandId,
                        command = remote.command,
                        state = AgentCommandState.queued.name,
                        priority = remote.priority,
                        attempts = 0,
                        paramsJson = remote.params.toString(),
                        executionToken = token,
                        updatedAt = now,
                    ),
                )
            }
        }

        val runnable = localStore.listRunnable(now = now, limit = limit)
        var executedCount = 0
        for (cmd in runnable) {
            val executionToken = buildExecutionToken(deviceId, cmd.commandId, cmd.command, cmd.createdAt)
            if (db.executedCommandDao().findByToken(executionToken) != null) {
                localStore.markSuccess(cmd.commandId, System.currentTimeMillis())
                db.commandDao().upsert(
                    CommandEntity(
                        commandId = cmd.commandId,
                        command = cmd.command,
                        state = AgentCommandState.success.name,
                        priority = cmd.priority,
                        attempts = cmd.attempts,
                        paramsJson = cmd.paramsJson,
                        executionToken = executionToken,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                continue
            }

            val startedAt = System.currentTimeMillis()
            network.ackCommand(
                deviceId,
                ArgosCommandResult(
                    commandId = cmd.commandId,
                    status = AgentCommandState.received.name,
                    message = "agent_received",
                    executedAt = startedAt,
                ),
            )
            localStore.markProcessing(cmd.commandId, startedAt)
            db.commandDao().upsert(
                CommandEntity(
                    commandId = cmd.commandId,
                    command = cmd.command,
                    state = AgentCommandState.executing.name,
                    priority = cmd.priority,
                    attempts = cmd.attempts,
                    paramsJson = cmd.paramsJson,
                    executionToken = executionToken,
                    updatedAt = startedAt,
                ),
            )
            network.ackCommand(
                deviceId,
                ArgosCommandResult(
                    commandId = cmd.commandId,
                    status = AgentCommandState.executing.name,
                    message = "command_executing",
                    executedAt = startedAt,
                ),
            )

            val outcome = runCatching { executor.execute(deviceId, cmd) }
                .getOrElse { error ->
                    com.mupa.agent.argos.commands.ExecOutcome(
                        status = AgentCommandState.failed.name,
                        message = error.message?.trim().orEmpty().ifBlank { error.javaClass.simpleName },
                    )
                }
            val finishedAt = System.currentTimeMillis()
            val ack = ArgosCommandResult(
                commandId = cmd.commandId,
                status = outcome.status,
                message = outcome.message,
                executedAt = finishedAt,
            )
            val ackPosted = network.ackCommand(deviceId, ack)
            val success = ackPosted && outcome.status.equals(AgentCommandState.success.name, ignoreCase = true)

            if (success) {
                localStore.markSuccess(cmd.commandId, finishedAt)
                db.executedCommandDao().upsert(
                    ExecutedCommandEntity(
                        executionToken = executionToken,
                        commandId = cmd.commandId,
                        command = cmd.command,
                        status = AgentCommandState.success.name,
                        executedAt = finishedAt,
                    ),
                )
                db.commandDao().upsert(
                    CommandEntity(
                        commandId = cmd.commandId,
                        command = cmd.command,
                        state = AgentCommandState.success.name,
                        priority = cmd.priority,
                        attempts = cmd.attempts,
                        paramsJson = cmd.paramsJson,
                        executionToken = executionToken,
                        updatedAt = finishedAt,
                    ),
                )
            } else {
                val terminal = cmd.attempts >= 4
                localStore.markFailed(cmd.commandId, finishedAt, outcome.message, final = terminal)
                if (!terminal) {
                    localStore.bumpRetry(cmd.commandId, finishedAt, if (ackPosted) outcome.message else "ack_failed")
                }
                db.commandDao().upsert(
                    CommandEntity(
                        commandId = cmd.commandId,
                        command = cmd.command,
                        state = if (terminal) AgentCommandState.failed.name else AgentCommandState.retrying.name,
                        priority = cmd.priority,
                        attempts = cmd.attempts + 1,
                        paramsJson = cmd.paramsJson,
                        executionToken = executionToken,
                        updatedAt = finishedAt,
                    ),
                )
            }

            db.logDao().insert(
                LogEntity(
                    category = AgentLogCategory.COMMANDS.name,
                    level = if (success) AgentLogCategory.INFO.name else AgentLogCategory.ERROR.name,
                    message = "${cmd.command}:${outcome.message}",
                    payloadJson = cmd.paramsJson,
                    createdAt = finishedAt,
                ),
            )
            executedCount++
        }

        settings.setLastCommandSyncMs(System.currentTimeMillis())
        executedCount
    }

    private fun buildExecutionToken(deviceId: String, commandId: String, command: String, timestamp: Long): String {
        val raw = "${deviceId.trim()}|${commandId.trim()}|${command.trim()}|$timestamp"
        return UUID.nameUUIDFromBytes(raw.toByteArray()).toString()
    }
}
