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
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.remote.auth.DriveAuthException
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.OcrIndexerManager
import kotlinx.coroutines.flow.first
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
        if (result.isSuccess) return Result.success()
        val err = result.exceptionOrNull()
        // Never retry endlessly when signed out or missing Google OAuth consent
        if (err is DriveAuthException || runAttemptCount >= 4) {
            return Result.failure()
        }
        return Result.retry()
    }
}

/**
 * Battery-aware background media indexer for GPS location & offline OCR caches.
 * Runs only while battery is not low (and prefers charging/idle windows) so cold app launch
 * stays snappy and foreground sessions never burn battery indexing thousands of older photos.
 */
class MediaIndexWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val ocrIndexerManager: OcrIndexerManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!localMediaRepository.hasMediaPermission()) return Result.success()
        val localItems = runCatching {
            localMediaRepository.observeAllLocalMedia().first()
        }.getOrDefault(emptyList())
        if (localItems.isEmpty()) return Result.success()

        runCatching { extrasRepository.syncLocationsIndex(localItems) }
        runCatching { ocrIndexerManager.syncOcrIndex(localItems, maxNewItemsPerRun = 150) }
        return Result.success()
    }
}

class LuminaWorkerFactory(
    private val repository: GalleryRepository,
    private val localMediaRepository: LocalMediaRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val ocrIndexerManager: OcrIndexerManager
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
        MediaIndexWorker::class.java.name -> MediaIndexWorker(
            appContext = appContext,
            workerParams = workerParameters,
            localMediaRepository = localMediaRepository,
            extrasRepository = extrasRepository,
            ocrIndexerManager = ocrIndexerManager
        )
        else -> null
    }
}

object MediaIndexScheduler {
    private const val UNIQUE_WORK_NAME = "lumina_media_background_indexer"

    fun ensureScheduled(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(true)
            .build()
        val request = PeriodicWorkRequestBuilder<MediaIndexWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 4,
            flexTimeIntervalUnit = TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
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
