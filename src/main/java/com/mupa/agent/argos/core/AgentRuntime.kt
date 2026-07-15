package com.mupa.agent.argos.core

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mupa.agent.argos.workers.AppInventoryWorker
import com.mupa.agent.argos.workers.ConfigWorker
import com.mupa.agent.argos.workers.HealthWorker
import com.mupa.agent.argos.workers.HeartbeatWorker
import com.mupa.agent.argos.workers.LocationWorker
import com.mupa.agent.argos.workers.SyncWorker
import java.util.concurrent.TimeUnit

object AgentRuntime {
    private const val SYNC_WORK = "argos3-sync"
    private const val HEARTBEAT_WORK = "argos3-heartbeat"
    private const val CONFIG_WORK = "argos3-config"
    private const val APP_INVENTORY_WORK = "argos3-app-inventory"
    private const val LOCATION_WORK = "argos3-location"
    private const val HEALTH_WORK = "argos3-health"

    fun bootstrap(context: Context) {
        val appContext = context.applicationContext
        val wm = WorkManager.getInstance(appContext)
        wm.enqueueUniqueWork(
            "$SYNC_WORK-immediate",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().build(),
        )
        wm.enqueueUniqueWork(
            "$CONFIG_WORK-immediate",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ConfigWorker>().build(),
        )
        wm.enqueueUniqueWork(
            "$HEARTBEAT_WORK-immediate",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HeartbeatWorker>().build(),
        )
        wm.enqueueUniqueWork(
            "$HEALTH_WORK-immediate",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthWorker>().build(),
        )
        wm.enqueueUniqueWork(
            "$APP_INVENTORY_WORK-immediate",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AppInventoryWorker>().build(),
        )
        wm.enqueueUniquePeriodicWork(
            SYNC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            HEARTBEAT_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(15, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            CONFIG_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ConfigWorker>(15, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            APP_INVENTORY_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AppInventoryWorker>(12, TimeUnit.HOURS).build(),
        )
        wm.enqueueUniqueWork(
            "$LOCATION_WORK-immediate",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<LocationWorker>().build(),
        )
        wm.enqueueUniquePeriodicWork(
            LOCATION_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<LocationWorker>(15, TimeUnit.MINUTES).build(),
        )
        wm.enqueueUniquePeriodicWork(
            HEALTH_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<HealthWorker>(15, TimeUnit.MINUTES).build(),
        )
    }
}
