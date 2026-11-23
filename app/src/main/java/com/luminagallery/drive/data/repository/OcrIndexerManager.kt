package com.luminagallery.drive.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.entity.PhotoOcrEntity
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@Immutable
data class OcrIndexProgress(
    val isIndexing: Boolean = false,
    val indexedCount: Int = 0,
    val totalCount: Int = 0,
    val textPhotoCount: Int = 0
)

@Immutable
data class ExtractedPhotoText(
    val fullText: String,
    val blocks: List<String>
)

/**
 * 100% offline, zero-server On-Device OCR engine powered by Google ML Kit Text Recognition:
 *  - Indexes text inside Screenshots, Documents, Downloads, WhatsApp, and Photos into Room
 *    `photo_ocr_cache` so users can search receipts, Wi-Fi passwords, notes, and screenshots.
 *  - Provides instant single-photo Live Text extraction in the Viewer for 1-tap text copying.
 */
class OcrIndexerManager(
    private val context: Context,
    database: GalleryDatabase,
    private val driveApi: DriveApiService,
    private val offlineMediaManager: OfflineMediaManager
) {
    private val extrasDao = database.galleryExtrasDao()
    private val indexMutex = Mutex()
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val _progress = MutableStateFlow(OcrIndexProgress())
    val progress: StateFlow<OcrIndexProgress> = _progress.asStateFlow()

    /**
     * Progressively scans unindexed local images (prioritizing Screenshots & Documents first)
     * and persists extracted text in Room so each photo is only scanned once.
     */
    suspend fun syncOcrIndex(
        items: List<MediaItem>,
        maxNewItemsPerRun: Int = 220
    ) = withContext(Dispatchers.IO) {
        if (!indexMutex.tryLock()) return@withContext
        try {
            val localPhotos = items.filter { it.isLocal && !it.isVideo && !it.isRaw && !it.localContentUri.isNullOrBlank() }
            if (localPhotos.isEmpty()) {
                _progress.value = OcrIndexProgress()
                return@withContext
            }

            val existing = extrasDao.getAllOcrEntries().associateByTo(HashMap()) { it.itemId }
            var textHits = existing.values.count { it.hasText }

            val uncached = localPhotos.filter { item ->
                val prev = existing[item.id]
                prev == null || prev.dateModifiedMs != item.createdTimeEpochMs
            }.sortedWith(
                compareBy<MediaItem> { ocrPriority(it) }
                    .thenByDescending { it.createdTimeEpochMs }
            ).take(maxNewItemsPerRun)

            if (uncached.isEmpty()) {
                _progress.value = OcrIndexProgress(
                    isIndexing = false,
                    indexedCount = existing.size,
                    totalCount = localPhotos.size,
                    textPhotoCount = textHits
                )
                return@withContext
            }

            val totalToScan = uncached.size
            var done = 0
            _progress.value = OcrIndexProgress(
                isIndexing = true,
                indexedCount = 0,
                totalCount = totalToScan,
                textPhotoCount = textHits
            )

            // Small 2-way parallel batches keep CPU cool and memory flat on 120Hz phones
            for (batch in uncached.chunked(12)) {
                val results = coroutineScope {
                    batch.chunked(6).map { slice ->
                        async(Dispatchers.IO) {
                            slice.mapNotNull { item -> scanSingleLocalItem(item) }
                        }
                    }.awaitAll().flatten()
                }
                if (results.isNotEmpty()) {
                    extrasDao.upsertOcrEntries(results)
                    textHits += results.count { it.hasText }
                }
                done += batch.size
                _progress.value = OcrIndexProgress(
                    isIndexing = done < totalToScan,
                    indexedCount = done,
                    totalCount = totalToScan,
                    textPhotoCount = textHits
                )
            }
        } finally {
            indexMutex.unlock()
        }
    }

    /**
     * Instant on-demand OCR for the Viewer's "Scan text (T)" Live Text feature.
     * Supports both local MediaStore photos and Google Drive photos, caching results to Room.
     */
    suspend fun extractTextForViewer(item: MediaItem): Result<ExtractedPhotoText> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(!item.isVideo) { "Text recognition is available for photos only" }
                val bitmap = decodeOcrBitmap(item)
                    ?: throw IllegalStateException("Could not decode image for text recognition")
                try {
                    val result = Tasks.await(
                        recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                        OCR_TIMEOUT_SEC,
                        TimeUnit.SECONDS
                    )
                    val blocks = result.textBlocks
                        .map { it.text.trim() }
                        .filter { it.isNotBlank() }
                    val fullText = blocks.joinToString("\n\n")
                    // Cache in Room so this photo becomes instantly searchable too!
                    extrasDao.upsertOcrEntry(
                        PhotoOcrEntity(
                            itemId = item.id,
                            dateModifiedMs = item.createdTimeEpochMs,
                            extractedText = normalizeSearchText(fullText),
                            hasText = fullText.isNotBlank()
                        )
                    )
                    ExtractedPhotoText(fullText = fullText, blocks = blocks)
                } finally {
                    bitmap.recycle()
                }
            }
        }

    private fun scanSingleLocalItem(item: MediaItem): PhotoOcrEntity? {
        // Return null on transient decode/timeout errors instead of permanently caching `hasText = false`
        val text = runCatching {
            val bitmap = decodeLocalOcrBitmap(Uri.parse(item.localContentUri ?: return@runCatching null))
                ?: return@runCatching null
            try {
                val result = Tasks.await(
                    recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                    OCR_TIMEOUT_SEC,
                    TimeUnit.SECONDS
                )
                normalizeSearchText(result.text)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull() ?: return null

        return PhotoOcrEntity(
            itemId = item.id,
            dateModifiedMs = item.createdTimeEpochMs,
            extractedText = text,
            hasText = text.isNotBlank()
        )
    }

    private suspend fun decodeOcrBitmap(item: MediaItem): Bitmap? {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            return decodeLocalOcrBitmap(Uri.parse(item.localContentUri))
        }
        val offlinePath = offlineMediaManager.getOfflineFilePath(item.id)
        if (!offlinePath.isNullOrBlank()) {
            val f = File(offlinePath)
            if (f.exists()) return decodeFileScaled(f)
        }
        val tmp = File(context.cacheDir, "ocr_tmp_${item.id.take(10)}_${System.currentTimeMillis()}.jpg")
        return try {
            driveApi.downloadFile(item.id).use { body ->
                tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            }
            decodeFileScaled(tmp)
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun decodeLocalOcrBitmap(uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > MAX_OCR_DIMENSION_PX) {
                    val scale = MAX_OCR_DIMENSION_PX.toFloat() / maxDim
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_OCR_DIMENSION_PX)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            val degrees = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).rotationDegrees
                } ?: 0
            }.getOrDefault(0)
            if (degrees != 0) {
                val m = Matrix().apply { postRotate(degrees.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                if (rotated !== raw) raw.recycle()
                rotated
            } else {
                raw
            }
        }
    }.getOrNull()

    private fun decodeFileScaled(file: File): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val src = ImageDecoder.createSource(file)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val maxDim = maxOf(info.size.width, info.size.height)
                if (maxDim > MAX_OCR_DIMENSION_PX) {
                    val scale = MAX_OCR_DIMENSION_PX.toFloat() / maxDim
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1)
                    )
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val sample = computeInSampleSize(bounds.outWidth, bounds.outHeight, MAX_OCR_DIMENSION_PX)
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }.getOrNull()

    private fun computeInSampleSize(w: Int, h: Int, maxDim: Int): Int {
        var sample = 1
        var curMax = maxOf(w, h)
        while (curMax / 2 >= maxDim) {
            sample *= 2
            curMax /= 2
        }
        return sample
    }

    private fun ocrPriority(item: MediaItem): Int {
        val path = (item.storagePath ?: item.relativePath).orEmpty()
        return when {
            path.contains("screenshot", ignoreCase = true) ||
                item.name.contains("screenshot", ignoreCase = true) -> 0
            path.contains("document", ignoreCase = true) ||
                path.contains("scan", ignoreCase = true) -> 1
            path.contains("download", ignoreCase = true) ||
                path.contains("whatsapp", ignoreCase = true) -> 2
            else -> 3
        }
    }

    companion object {
        private const val MAX_OCR_DIMENSION_PX = 1280
        private const val OCR_TIMEOUT_SEC = 10L
        private const val MAX_STORED_CHARS = 2400

        internal fun normalizeSearchText(raw: String): String =
            raw.replace(Regex("\\s+"), " ").trim().take(MAX_STORED_CHARS)
    }
}
