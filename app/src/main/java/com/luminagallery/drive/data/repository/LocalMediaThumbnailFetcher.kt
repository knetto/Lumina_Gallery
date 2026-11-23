package com.luminagallery.drive.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import androidx.compose.runtime.Immutable
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
import okio.buffer
import okio.source
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Marker model for local on-device MediaStore thumbnails (grids, album covers, filmstrip).
 * Full-screen viewer requests intentionally pass raw URI strings so they always decode full-res.
 */
@Immutable
data class LocalThumbnailRequest(
    val contentUri: String,
    val isVideo: Boolean = false
)

class LocalMediaThumbnailKeyer : Keyer<LocalThumbnailRequest> {
    override fun key(data: LocalThumbnailRequest, options: Options): String =
        "local_thumb:${data.contentUri}:${options.size}"
}

/**
 * Fast-path Coil 3 Fetcher backed by Android 10+ `ContentResolver.loadThumbnail()`.
 * Reads directly from the hardware-cached OS MediaStore thumbnail store (~1-4 ms for both
 * 200MP camera JPEGs/HEICs and 4K60 videos without spinning up MediaMetadataRetriever),
 * cancels immediately via `CancellationSignal` during fast 120Hz flings, and falls back
 * seamlessly to Coil's standard stream decoders (including `RawImageDecoder`) when needed.
 */
class LocalMediaThumbnailFetcher(
    private val context: Context,
    private val data: LocalThumbnailRequest,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val uri = Uri.parse(data.contentUri)

        // Direct file:// thumbnails (used for hidden locked .folders bypassed by MediaStore)
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) } ?: return null
            if (!file.exists()) return null
            val targetSize = resolveTargetSize(options)
            val signal = CancellationSignal()
            val completionHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) signal.cancel()
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bmp = runCatching {
                        if (data.isVideo) {
                            ThumbnailUtils.createVideoThumbnail(file, targetSize, signal)
                        } else {
                            ThumbnailUtils.createImageThumbnail(file, targetSize, signal)
                        }
                    }.getOrNull()
                    if (bmp != null) {
                        return ImageFetchResult(
                            image = bmp.asImage(),
                            isSampled = true,
                            dataSource = DataSource.DISK
                        )
                    }
                } else if (data.isVideo) {
                    val bmp = runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            retriever.frameAtTime
                        } finally {
                            runCatching { retriever.release() }
                        }
                    }.getOrNull()
                    if (bmp != null) {
                        return ImageFetchResult(
                            image = bmp.asImage(),
                            isSampled = true,
                            dataSource = DataSource.DISK
                        )
                    }
                }
            } catch (canceled: OperationCanceledException) {
                throw CancellationException("Thumbnail load canceled", canceled)
            } finally {
                completionHandle?.dispose()
            }
            val stream = runCatching { file.inputStream() }.getOrNull() ?: return null
            return SourceFetchResult(
                source = ImageSource(
                    source = stream.source().buffer(),
                    fileSystem = options.fileSystem
                ),
                mimeType = null,
                dataSource = DataSource.DISK
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val targetSize = resolveTargetSize(options)
            val signal = CancellationSignal()
            val completionHandle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    signal.cancel()
                }
            }
            try {
                val bitmap = context.contentResolver.loadThumbnail(uri, targetSize, signal)
                return ImageFetchResult(
                    image = bitmap.asImage(),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            } catch (canceled: OperationCanceledException) {
                throw CancellationException("Thumbnail load canceled", canceled)
            } catch (_: Throwable) {
                // Fall back to MediaMetadataRetriever for non-standard videos (e.g. MP2P / VOB / MOD)
                // or direct ContentResolver stream for proprietary RAW formats.
            } finally {
                completionHandle?.dispose()
            }
        }

        if (data.isVideo) {
            val targetSize = resolveTargetSize(options)
            val bmp = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            -1L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            targetSize.width,
                            targetSize.height
                        )
                    } else {
                        retriever.frameAtTime
                    }
                } finally {
                    runCatching { retriever.release() }
                }
            }.getOrNull()
            if (bmp != null) {
                return ImageFetchResult(
                    image = bmp.asImage(),
                    isSampled = true,
                    dataSource = DataSource.DISK
                )
            }
        }

        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
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

    private companion object {
        const val MIN_THUMB_PX = 160
        const val DEFAULT_THUMB_PX = 384
        const val MAX_THUMB_PX = 512
    }
}
