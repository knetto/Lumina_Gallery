package com.luminagallery.drive.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.Dimension
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Marker model for local on-device MediaStore thumbnails (grids, album covers, filmstrip).
 * Full-screen viewer requests intentionally pass raw URI strings so they always decode full-res.
 */
@Immutable
data class LocalThumbnailRequest(
    val contentUri: String,
    val isVideo: Boolean = false,
    val retryAttempt: Int = 0
)

class LocalMediaThumbnailKeyer : Keyer<LocalThumbnailRequest> {
    override fun key(data: LocalThumbnailRequest, options: Options): String =
        if (data.retryAttempt == 0) {
            "local_thumb:${data.contentUri}:${options.size}"
        } else {
            "local_thumb:${data.contentUri}:${options.size}:r${data.retryAttempt}"
        }
}

/**
 * Concurrency-bounded, multi-stage Coil 3 Fetcher for local device thumbnails.
 *
 * Eliminates permanently gray tiles during fast 120Hz scrolling:
 *  1. Bounds concurrent MediaStore/ImageDecoder IPC calls via a shared `Semaphore(6)` and checks
 *     `coroutineContext.ensureActive()` before & after acquiring so off-screen scrolled tiles
 *     abort in <0.1 ms without flooding Android/HyperOS `MediaProvider`.
 *  2. Never swallows coroutine cancellation into heavy fallback streams.
 *  3. Falls back cleanly across `ContentResolver.loadThumbnail()` -> scaled `ImageDecoder` ->
 *     two-pass `BitmapFactory` (+ EXIF orientation) / `MediaMetadataRetriever` -> `SourceFetchResult`
 *     (for proprietary RAW files via `RawImageDecoder`). On retry attempts (`retryAttempt > 0`),
 *     bypasses a failed OS thumbnail cache and decodes directly from the file stream.
 */
class LocalMediaThumbnailFetcher(
    private val context: Context,
    private val data: LocalThumbnailRequest,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        coroutineContext.ensureActive()
        val uri = Uri.parse(data.contentUri)
        val targetSize = resolveTargetSize(options)

        // Direct file:// thumbnails (hidden locked .folders bypassed by MediaStore)
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
                ?: throw IOException("Invalid file URI: ${data.contentUri}")
            if (!file.exists()) {
                throw IOException("Local file no longer exists: ${file.name}")
            }

            val decoded = decodeSemaphore.withPermit {
                coroutineContext.ensureActive()
                decodeLocalFileThumbnail(file, targetSize, skipOsCache = data.retryAttempt > 0)
            }
            if (decoded.isValidThumbnail()) {
                return ImageFetchResult(
                    image = decoded!!.asImage(),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            }

            coroutineContext.ensureActive()
            val stream = runCatching { file.inputStream() }.getOrNull()
                ?: throw IOException("Unable to open stream for ${file.name}")
            return SourceFetchResult(
                source = ImageSource(
                    source = stream.source().buffer(),
                    fileSystem = options.fileSystem
                ),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        }

        // Standard content:// MediaStore thumbnails
        val decodedBitmap = decodeSemaphore.withPermit {
            coroutineContext.ensureActive()
            decodeContentUriThumbnail(uri, targetSize, skipOsCache = data.retryAttempt > 0)
        }
        if (decodedBitmap.isValidThumbnail()) {
            return ImageFetchResult(
                image = decodedBitmap!!.asImage(),
                isSampled = true,
                dataSource = DataSource.DISK
            )
        }

        coroutineContext.ensureActive()

        // Final stream fallback (lets RawImageDecoder handle CR3/ARW/NEF/RAF or VideoFrameDecoder handle edge codecs)
        val inputStream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
            ?: throw IOException("Unable to open local thumbnail stream for ${data.contentUri}")
        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return SourceFetchResult(
            source = ImageSource(
                source = inputStream.source().buffer(),
                fileSystem = options.fileSystem
            ),
            mimeType = mimeType,
            dataSource = DataSource.DISK
        )
    }

    private suspend fun decodeLocalFileThumbnail(
        file: File,
        targetSize: Size,
        skipOsCache: Boolean
    ): Bitmap? {
        if (!skipOsCache && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) signal.cancel()
            }
            try {
                val bmp = if (data.isVideo) {
                    ThumbnailUtils.createVideoThumbnail(file, targetSize, signal)
                } else {
                    ThumbnailUtils.createImageThumbnail(file, targetSize, signal)
                }
                if (bmp.isValidThumbnail()) return bmp
            } catch (ce: CancellationException) {
                throw ce
            } catch (canceled: OperationCanceledException) {
                throw CancellationException("File thumbnail canceled", canceled)
            } catch (_: Throwable) {
                coroutineContext.ensureActive()
            } finally {
                handle?.dispose()
            }
        }

        coroutineContext.ensureActive()
        return if (data.isVideo) {
            decodeVideoFrameFromFile(file, targetSize)
        } else {
            decodeImageFromFile(file, targetSize)
        }
    }

    private suspend fun decodeContentUriThumbnail(
        uri: Uri,
        targetSize: Size,
        skipOsCache: Boolean
    ): Bitmap? {
        val isGenericFileUri = data.contentUri.contains("/file/", ignoreCase = true)

        // Stage 1: Hardware-cached Android 10+ MediaStore thumbnail store (~1-4 ms)
        if (!skipOsCache && !isGenericFileUri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val signal = CancellationSignal()
            val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) signal.cancel()
            }
            try {
                val bitmap = context.contentResolver.loadThumbnail(uri, targetSize, signal)
                if (bitmap.isValidThumbnail()) return bitmap
            } catch (ce: CancellationException) {
                throw ce
            } catch (canceled: OperationCanceledException) {
                throw CancellationException("Thumbnail load canceled", canceled)
            } catch (_: Throwable) {
                coroutineContext.ensureActive()
            } finally {
                handle?.dispose()
            }
        }

        coroutineContext.ensureActive()

        // Stage 2: Direct scaled decode (never leaves a tile gray when MediaStore cache misses/throttles)
        return if (data.isVideo) {
            decodeVideoFrameFromUri(uri, targetSize)
        } else {
            decodeImageFromUri(uri, targetSize)
        }
    }

    private fun decodeImageFromUri(uri: Uri, targetSize: Size): Bitmap? {
        val maxSide = max(targetSize.width, targetSize.height)

        // Stage 2A: Native Android 9+ ImageDecoder (handles JPEG, HEIC, WebP, AVIF, PNG, DNG + auto-EXIF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val src = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    decoder.isMutableRequired = false
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val largest = max(srcW, srcH)
                    if (largest > maxSide && srcW > 0 && srcH > 0) {
                        val scale = maxSide.toFloat() / largest.toFloat()
                        decoder.setTargetSize(
                            (srcW * scale).roundToInt().coerceAtLeast(1),
                            (srcH * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
            if (decoded.isValidThumbnail()) return decoded
        }

        // Stage 2B: Two-pass downsampled BitmapFactory + EXIF orientation
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val rawBmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return@runCatching null

            val rotation = runCatching {
                context.contentResolver.openInputStream(uri)?.use { exifStream ->
                    ExifInterface(exifStream).rotationDegrees
                } ?: 0
            }.getOrDefault(0)
            applyExifRotation(rawBmp, rotation)
        }.getOrNull()
    }

    private fun decodeImageFromFile(file: File, targetSize: Size): Bitmap? {
        val maxSide = max(targetSize.width, targetSize.height)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val src = ImageDecoder.createSource(file)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
                    decoder.isMutableRequired = false
                    val srcW = info.size.width
                    val srcH = info.size.height
                    val largest = max(srcW, srcH)
                    if (largest > maxSide && srcW > 0 && srcH > 0) {
                        val scale = maxSide.toFloat() / largest.toFloat()
                        decoder.setTargetSize(
                            (srcW * scale).roundToInt().coerceAtLeast(1),
                            (srcH * scale).roundToInt().coerceAtLeast(1)
                        )
                    }
                }
            }.getOrNull()
            if (decoded.isValidThumbnail()) return decoded
        }

        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxSide)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val rawBmp = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: return@runCatching null
            val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
            applyExifRotation(rawBmp, rotation)
        }.getOrNull()
    }

    private fun decodeVideoFrameFromUri(uri: Uri, targetSize: Size): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            extractBestVideoFrame(retriever, targetSize)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun decodeVideoFrameFromFile(file: File, targetSize: Size): Bitmap? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            extractBestVideoFrame(retriever, targetSize)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun extractBestVideoFrame(retriever: MediaMetadataRetriever, targetSize: Size): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            for (timeUs in VIDEO_FRAME_TIMES_US) {
                val frame = runCatching {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetSize.width,
                        targetSize.height
                    )
                }.getOrNull()
                if (frame.isValidThumbnail()) return frame
            }
            val closest = runCatching {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    targetSize.width,
                    targetSize.height
                )
            }.getOrNull()
            if (closest.isValidThumbnail()) return closest
        }
        return runCatching { retriever.frameAtTime }.getOrNull()
    }

    private fun applyExifRotation(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun Bitmap?.isValidThumbnail(): Boolean =
        this != null && !isRecycled && width > 0 && height > 0

    private fun resolveTargetSize(options: Options): Size {
        val w = (options.size.width as? Dimension.Pixels)?.px ?: DEFAULT_THUMB_PX
        val h = (options.size.height as? Dimension.Pixels)?.px ?: DEFAULT_THUMB_PX
        return Size(
            w.coerceIn(MIN_THUMB_PX, MAX_THUMB_PX),
            h.coerceIn(MIN_THUMB_PX, MAX_THUMB_PX)
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<LocalThumbnailRequest> {
        override fun create(
            data: LocalThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher = LocalMediaThumbnailFetcher(
            context = context.applicationContext,
            data = data,
            options = options
        )
    }

    internal companion object {
        private const val MIN_THUMB_PX = 160
        private const val DEFAULT_THUMB_PX = 384
        private const val MAX_THUMB_PX = 512
        private const val MAX_CONCURRENT_LOCAL_DECODES = 6
        private val VIDEO_FRAME_TIMES_US = longArrayOf(500_000L, -1L, 0L)

        // Shared bounded semaphore prevents 30-60 simultaneous MediaProvider IPC calls during 120Hz flings
        private val decodeSemaphore = Semaphore(MAX_CONCURRENT_LOCAL_DECODES)

        internal fun computeInSampleSize(srcWidth: Int, srcHeight: Int, targetMaxSide: Int): Int {
            val largest = max(srcWidth, srcHeight)
            if (largest <= targetMaxSide || targetMaxSide <= 0) return 1
            var sampleSize = 1
            while ((largest / (sampleSize * 2)) >= targetMaxSide) {
                sampleSize *= 2
            }
            return sampleSize.coerceAtLeast(1)
        }
    }
}
