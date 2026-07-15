package com.mupa.agent.argos.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mupa.agent.argos.core.ConfigurationEngine
import com.mupa.agent.argos.geofence.GeofenceEngine
import com.mupa.agent.argos.modules.AppManager
import com.mupa.agent.argos.modules.HealthMonitor
import com.mupa.agent.argos.services.HeartbeatService
import com.mupa.agent.argos.services.PollingService
import com.mupa.agent.argos.services.WatchdogService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { PollingService(applicationContext).tick() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

class HeartbeatWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (HeartbeatService(applicationContext).send()) Result.success() else Result.retry()
    }
}

class ConfigWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { ConfigurationEngine(applicationContext).syncAndApply() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

class AppInventoryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { AppManager(applicationContext).collectInventory() }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}

class LocationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { GeofenceEngine(applicationContext).check() }
        Result.success()
    }
}

class HealthWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            HealthMonitor(applicationContext).capture()
            WatchdogService(applicationContext).tick()
        }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }
}
