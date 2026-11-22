package com.luminagallery.drive.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.luminagallery.drive.data.remote.DriveApiService
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Lossless, ultra-fast MP4 video trimmer powered by Android `MediaExtractor` + `MediaMuxer`.
 * Copies compressed video & audio packets directly with zero re-encoding (trims a 4K clip in <1s)
 * and saves the trimmed copy cleanly into `Movies/LuminaGallery` via MediaStore.
 */
class VideoTrimmer(
    private val context: Context,
    private val driveApi: DriveApiService,
    private val offlineMediaManager: OfflineMediaManager
) {
    suspend fun trimAndSaveCopy(
        item: MediaItem,
        startMs: Long,
        endMs: Long
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(endMs > startMs + 300L) { "Trim selection must be at least 0.3 seconds" }

            var tempInputFile: File? = null
            val extractor = MediaExtractor()
            val retriever = MediaMetadataRetriever()
            val tempOutputFile = File(context.cacheDir, "trim_out_${System.currentTimeMillis()}.mp4")

            try {
                // Resolve source (local MediaStore URI, offline-pinned Drive file, or temp Drive download)
                val offlinePath = offlineMediaManager.getOfflineFilePath(item.id)
                when {
                    item.isLocal && !item.localContentUri.isNullOrBlank() -> {
                        val uri = Uri.parse(item.localContentUri)
                        extractor.setDataSource(context, uri, null)
                        retriever.setDataSource(context, uri)
                    }
                    !offlinePath.isNullOrBlank() -> {
                        extractor.setDataSource(offlinePath)
                        retriever.setDataSource(offlinePath)
                    }
                    else -> {
                        val dl = File(context.cacheDir, "trim_src_${System.currentTimeMillis()}.mp4")
                        tempInputFile = dl
                        driveApi.downloadFile(item.id).use { body ->
                            FileOutputStream(dl).use { out -> body.byteStream().copyTo(out) }
                        }
                        extractor.setDataSource(dl.absolutePath)
                        retriever.setDataSource(dl.absolutePath)
                    }
                }

                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0

                val muxer = MediaMuxer(tempOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val trackIndexMap = HashMap<Int, Int>()
                var maxBufferSize = 1024 * 1024

                try {
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            extractor.selectTrack(i)
                            val dstIndex = muxer.addTrack(format)
                            trackIndexMap[i] = dstIndex
                            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                                maxBufferSize = maxOf(maxBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                            }
                        }
                    }

                    if (trackIndexMap.isEmpty()) {
                        throw IOException("No compatible audio/video tracks found")
                    }

                    muxer.setOrientationHint(rotation)
                    muxer.start()

                    val startUs = startMs * 1000L
                    val endUs = endMs * 1000L
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    val buffer = ByteBuffer.allocate(maxBufferSize.coerceAtMost(4 * 1024 * 1024))
                    val bufferInfo = MediaCodec.BufferInfo()
                    var basePtsUs = -1L

                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs > endUs) break

                        val srcTrack = extractor.sampleTrackIndex
                        val dstTrack = trackIndexMap[srcTrack]
                        if (dstTrack != null && sampleTimeUs >= startUs - 500_000L) {
                            if (basePtsUs < 0L) basePtsUs = sampleTimeUs
                            bufferInfo.offset = 0
                            bufferInfo.size = sampleSize
                            bufferInfo.presentationTimeUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                            @Suppress("DEPRECATION")
                            bufferInfo.flags = extractor.sampleFlags
                            muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                        }
                        extractor.advance()
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                }

                // Publish trimmed clip cleanly into Movies/LuminaGallery
                val stem = item.name.substringBeforeLast('.').take(80).ifBlank { "video" }
                val outName = "${stem}_trimmed_${System.currentTimeMillis() % 100000}.mp4"
                saveMp4ToMediaStore(tempOutputFile, outName)
                outName
            } finally {
                runCatching { extractor.release() }
                runCatching { retriever.release() }
                runCatching { tempOutputFile.delete() }
                runCatching { tempInputFile?.delete() }
            }
        }
    }

    private fun saveMp4ToMediaStore(sourceFile: File, displayName: String) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/LuminaGallery")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values
            ) ?: throw IOException("Failed to create MediaStore entry for trimmed video")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input -> input.copyTo(out) }
                } ?: throw IOException("Unable to open output stream")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "LuminaGallery")
            if (!dir.exists()) dir.mkdirs()
            sourceFile.copyTo(File(dir, displayName), overwrite = true)
        }
    }
}
