package com.luminagallery.drive.data.repository.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.luminagallery.drive.data.repository.GalleryRepository
import java.util.concurrent.TimeUnit

/**
 * Optional zero-drain background delta sync worker.
 * NEVER scheduled unless explicitly opted-in via Settings, and strictly constrained to:
 *  - Device charging (`requiresCharging = true`)
 *  - Unmetered Wi-Fi (`NetworkType.UNMETERED`)
 *  - Battery not low (`requiresBatteryNotLow = true`)
 */
class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val repository: GalleryRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val result = repository.performSmartSync(forceFullRescan = false)
        return if (result.isSuccess) Result.success() else Result.retry()
    }
}

class LuminaWorkerFactory(
    private val repository: GalleryRepository
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? = when (workerClassName) {
        DriveSyncWorker::class.java.name -> DriveSyncWorker(
            appContext = appContext,
            workerParams = workerParameters,
            repository = repository
        )
        else -> null
    }
}

object DriveSyncScheduler {
    private const val UNIQUE_WORK_NAME = "lumina_drive_charging_wifi_delta_sync"

    fun setBackgroundSyncEnabled(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }

        val strictConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(
            repeatInterval = 12,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 2,
            flexTimeIntervalUnit = TimeUnit.HOURS
        )
            .setConstraints(strictConstraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
