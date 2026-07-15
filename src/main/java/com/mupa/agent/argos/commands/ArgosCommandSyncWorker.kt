package com.mupa.agent.argos.commands

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mupa.agent.argos.managers.DeviceIdentityManager
import com.mupa.agent.argos.managers.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ArgosCommandSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SettingsManager(applicationContext)
        val baseUrl = settings.getArgosApiUrlCached().trim()
        if (baseUrl.isBlank()) return@withContext Result.success()

        val deviceId = DeviceIdentityManager(applicationContext).getPersistentId().trim()
        if (deviceId.isBlank()) return@withContext Result.retry()

        val api = ArgosApiClient(applicationContext, settings)
        val store = LocalCommandStore(applicationContext)
        val now = System.currentTimeMillis()

        val pending = runCatching { api.fetchPendingCommands(deviceId) }.getOrDefault(emptyList())
        if (pending.isNotEmpty()) {
            store.upsertPending(pending, now)
        }

        val executor = ArgosCommandExecutor(applicationContext, settings)
        val runnable = store.listRunnable(now = now, limit = 10)
        for (cmd in runnable) {
            val t0 = System.currentTimeMillis()
            store.markProcessing(cmd.commandId, t0)
            val outcome = runCatching { executor.execute(deviceId, cmd) }
                .getOrElse { ExecOutcome("failed", it.message?.trim().orEmpty().ifBlank { it.javaClass.simpleName }) }

            val executedAt = System.currentTimeMillis()
            val resultPayload = ArgosCommandResult(
                commandId = cmd.commandId,
                status = outcome.status,
                message = outcome.message,
                executedAt = executedAt,
            )

            val posted = runCatching { api.postCommandResult(deviceId, resultPayload) }.getOrDefault(false)
            if (posted) {
                if (outcome.status == "success") {
                    store.markSuccess(cmd.commandId, executedAt)
                } else {
                    val terminal = cmd.attempts >= 4
                    store.markFailed(cmd.commandId, executedAt, outcome.message, final = terminal)
                    if (!terminal) store.bumpRetry(cmd.commandId, executedAt, outcome.message)
                }
            } else {
                store.bumpRetry(cmd.commandId, executedAt, "api_unreachable")
                return@withContext Result.retry()
            }
        }

        settings.setLastCommandSyncMs(System.currentTimeMillis())
        Result.success()
    }
}
