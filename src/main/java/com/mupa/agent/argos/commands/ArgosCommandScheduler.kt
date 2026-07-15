package com.mupa.agent.argos.commands

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ArgosCommandScheduler {
    private const val UNIQUE_PERIODIC = "argos_command_sync_periodic"
    private const val UNIQUE_ONCE = "argos_command_sync_once"

    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<ArgosCommandSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun triggerOnce(context: Context) {
        val req = OneTimeWorkRequestBuilder<ArgosCommandSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_ONCE, ExistingWorkPolicy.KEEP, req)
    }
}

