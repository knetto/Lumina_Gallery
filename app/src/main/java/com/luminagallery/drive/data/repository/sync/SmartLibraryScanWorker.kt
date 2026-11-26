package com.luminagallery.drive.data.repository.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.luminagallery.drive.MainActivity
import com.luminagallery.drive.R
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.repository.ai.SmartIndexProgress
import com.luminagallery.drive.data.repository.ai.SmartRecognitionManager
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat

/**
 * Foreground-service WorkManager worker that scans People & Objects across the user's local
 * and Google Drive libraries even when the user switches apps or locks the phone screen.
 *
 * Key guarantees for 30,000+ photo libraries:
 *  - Only scans NEW or modified items (already-indexed IDs in Room are skipped in milliseconds).
 *  - Persists checkpoints every 20 items so pausing, losing Wi-Fi, or rebooting never loses work.
 *  - When started on Wi-Fi, stays constrained to `NetworkType.UNMETERED` so it continues while
 *    locked on Wi-Fi, pauses automatically if Wi-Fi drops, and resumes cleanly when Wi-Fi returns.
 *  - Shows a silent live progress notification with a 1-tap Pause action.
 */
class SmartLibraryScanWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val smartRecognitionManager: SmartRecognitionManager,
    private val networkMonitor: NetworkMonitor
) : CoroutineWorker(appContext, workerParams) {

    private val numberFormat = NumberFormat.getIntegerInstance()
    private var lastNotificationUpdateMs: Long = 0L

    override suspend fun doWork(): Result {
        val requireWifi = inputData.getBoolean(KEY_REQUIRE_WIFI, false)
        if (requireWifi && !networkMonitor.networkState.value.isUnmeteredWifi) {
            return Result.retry()
        }

        ensureNotificationChannel()

        // Promote to a dataSync Foreground Service so Android / HyperOS keeps scanning while locked
        runCatching {
            setForeground(
                buildForegroundInfo(
                    indexed = 0,
                    total = 0,
                    statusText = if (requireWifi) {
                        "Scanning on Wi-Fi in background…"
                    } else {
                        "Scanning local photos in background…"
                    },
                    indeterminate = true
                )
            )
        }

        val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = runCatching {
            powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "LuminaGallery:SmartLibraryScan"
            )?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_SLICE_MS)
            }
        }.getOrNull()

        var lastWakeLockRenewMs = System.currentTimeMillis()

        return try {
            val outcome = smartRecognitionManager.syncSmartIndex(
                maxNewItemsPerRun = Int.MAX_VALUE,
                includeDrive = true,
                respectWifiOnlyForDrive = true,
                requireWifiThroughout = requireWifi,
                runningInBackgroundService = true,
                shouldStop = { isStopped },
                onProgressUpdate = { prog ->
                    val now = System.currentTimeMillis()
                    if (wakeLock != null && (now - lastWakeLockRenewMs) >= WAKE_LOCK_RENEW_INTERVAL_MS) {
                        runCatching { wakeLock.acquire(WAKE_LOCK_SLICE_MS) }
                        lastWakeLockRenewMs = now
                    }
                    if ((now - lastNotificationUpdateMs) >= NOTIFICATION_UPDATE_INTERVAL_MS) {
                        lastNotificationUpdateMs = now
                        updateProgressNotification(prog, requireWifi)
                    }
                }
            )

            when (outcome) {
                SmartRecognitionManager.ScanRunOutcome.COMPLETED_ALL,
                SmartRecognitionManager.ScanRunOutcome.PAUSED_BY_USER,
                SmartRecognitionManager.ScanRunOutcome.PARTIAL_BATCH -> Result.success()

                SmartRecognitionManager.ScanRunOutcome.WAITING_FOR_WIFI,
                SmartRecognitionManager.ScanRunOutcome.MODEL_DOWNLOADING,
                SmartRecognitionManager.ScanRunOutcome.ALREADY_BUSY -> {
                    if (isStopped) Result.success() else Result.retry()
                }
            }
        } catch (ce: CancellationException) {
            smartRecognitionManager.pauseActiveScan()
            throw ce
        } catch (_: Throwable) {
            if (runAttemptCount >= 4) Result.failure() else Result.retry()
        } finally {
            runCatching {
                if (wakeLock?.isHeld == true) wakeLock.release()
            }
        }
    }

    private suspend fun updateProgressNotification(
        progress: SmartIndexProgress,
        requireWifi: Boolean
    ) {
        val total = progress.totalEligiblePhotos.coerceAtLeast(progress.totalInRun)
        val indexed = progress.totalIndexedPhotos.coerceIn(0, total.coerceAtLeast(1))
        val text = if (total > 0) {
            val suffix = if (requireWifi) " · Wi-Fi" else ""
            "${numberFormat.format(indexed)} / ${numberFormat.format(total)} scanned$suffix"
        } else {
            progress.statusText ?: "Scanning photos & videos…"
        }
        runCatching {
            setForeground(
                buildForegroundInfo(
                    indexed = indexed,
                    total = total,
                    statusText = text,
                    indeterminate = total <= 0 || progress.isDownloadingModels
                )
            )
        }
    }

    private fun buildForegroundInfo(
        indexed: Int,
        total: Int,
        statusText: String,
        indeterminate: Boolean
    ): ForegroundInfo {
        val notification = buildNotification(
            indexed = indexed,
            total = total,
            statusText = statusText,
            indeterminate = indeterminate
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(
        indexed: Int,
        total: Int,
        statusText: String,
        indeterminate: Boolean
    ): Notification {
        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pausePendingIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Scanning People & Objects")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentPendingIntent)
            .setProgress(
                total.coerceAtLeast(1),
                indexed.coerceIn(0, total.coerceAtLeast(1)),
                indeterminate
            )
            .addAction(
                android.R.drawable.ic_media_pause,
                "Pause",
                pausePendingIntent
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "People & Objects Background Scan",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live progress while scanning photos on Wi-Fi in the background"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val UNIQUE_WORK_NAME = "lumina_smart_ai_library_scan"
        const val KEY_REQUIRE_WIFI = "require_wifi"
        private const val CHANNEL_ID = "lumina_ai_library_scan"
        private const val NOTIFICATION_ID = 4108
        private const val NOTIFICATION_UPDATE_INTERVAL_MS = 1_500L
        private const val WAKE_LOCK_SLICE_MS = 25L * 60L * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 15L * 60L * 1000L
    }
}

object SmartScanScheduler {

    /**
     * Enqueues a background/locked-screen full library scan via WorkManager Foreground Service.
     *  - When [wifiOnly] is true (user clicked Scan while connected to Wi-Fi), keeps running in
     *    the background & while locked on Wi-Fi, pauses if Wi-Fi drops, and automatically resumes
     *    from the exact next unscanned photo when Wi-Fi reconnects.
     *  - Already-scanned photos are permanently persisted in Room and skipped in milliseconds.
     */
    fun startBackgroundLibraryScan(context: Context, wifiOnly: Boolean) {
        val constraintsBuilder = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
        if (wifiOnly) {
            constraintsBuilder.setRequiredNetworkType(NetworkType.UNMETERED)
        }
        val input = Data.Builder()
            .putBoolean(SmartLibraryScanWorker.KEY_REQUIRE_WIFI, wifiOnly)
            .build()
        val request = OneTimeWorkRequestBuilder<SmartLibraryScanWorker>()
            .setConstraints(constraintsBuilder.build())
            .setInputData(input)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            SmartLibraryScanWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelActiveScan(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(SmartLibraryScanWorker.UNIQUE_WORK_NAME)
    }
}
