package com.mupa.agent.argos.services

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mupa.agent.argos.launcher.WatchdogManager
import com.mupa.agent.argos.permissions.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WatchdogWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val pm = PermissionManager(applicationContext)
        if (!pm.criticalReady()) return@withContext Result.success()

        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, ArgosForegroundService::class.java),
        )
        WatchdogManager(applicationContext).runNow()
        Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "argos_watchdog_worker"
    }
}
