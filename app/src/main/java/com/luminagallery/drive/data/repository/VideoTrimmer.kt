package com.luminagallery.drive.data.repository

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.MediaScannerConnection
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

                // Detect compatible container & tracks before opening MediaMuxer
                val videoTracks = mutableListOf<Pair<Int, MediaFormat>>()
                val audioTracks = mutableListOf<Pair<Int, MediaFormat>>()
                var maxBufferSize = 2 * 1024 * 1024
                var isWebmSource = false

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty().lowercase()
                    if (mime.startsWith("video/")) {
                        if (mime in WEBM_VIDEO_MIMES) isWebmSource = true
                        videoTracks.add(i to format)
                    } else if (mime.startsWith("audio/")) {
                        if (mime in WEBM_AUDIO_MIMES) isWebmSource = true
                        audioTracks.add(i to format)
                    }
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxBufferSize = maxOf(maxBufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }

                val allowedVideo = if (isWebmSource) WEBM_VIDEO_MIMES else MP4_VIDEO_MIMES
                val allowedAudio = if (isWebmSource) WEBM_AUDIO_MIMES else MP4_AUDIO_MIMES
                val selectedVideo = videoTracks.firstOrNull { (_, fmt) ->
                    fmt.getString(MediaFormat.KEY_MIME).orEmpty().lowercase() in allowedVideo
                }
                val selectedAudio = audioTracks.firstOrNull { (_, fmt) ->
                    fmt.getString(MediaFormat.KEY_MIME).orEmpty().lowercase() in allowedAudio
                }
                if (selectedVideo == null && selectedAudio == null) {
                    throw IOException("This video codec cannot be losslessly trimmed on-device")
                }

                val muxerFormat = if (isWebmSource) {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                } else {
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                }
                val outExt = if (isWebmSource) "webm" else "mp4"
                val outMime = if (isWebmSource) "video/webm" else "video/mp4"

                val muxer = MediaMuxer(tempOutputFile.absolutePath, muxerFormat)
                val trackIndexMap = HashMap<Int, Int>()
                val videoSrcTrack = selectedVideo?.first

                try {
                    selectedVideo?.let { (idx, fmt) ->
                        extractor.selectTrack(idx)
                        trackIndexMap[idx] = muxer.addTrack(fmt)
                    }
                    selectedAudio?.let { (idx, fmt) ->
                        extractor.selectTrack(idx)
                        trackIndexMap[idx] = muxer.addTrack(fmt)
                    }

                    muxer.setOrientationHint(rotation)
                    muxer.start()

                    val startUs = startMs * 1000L
                    val endUs = endMs * 1000L
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                    var buffer = ByteBuffer.allocate(maxBufferSize.coerceIn(1024 * 1024, 16 * 1024 * 1024))
                    val bufferInfo = MediaCodec.BufferInfo()
                    var basePtsUs = -1L
                    var seenVideoSync = (videoSrcTrack == null)

                    while (true) {
                        var sampleSize = try {
                            extractor.readSampleData(buffer, 0)
                        } catch (_: IllegalArgumentException) {
                            // Grow buffer dynamically for ultra-high-bitrate 4K/8K keyframes
                            val nextCap = (buffer.capacity() * 2).coerceAtMost(32 * 1024 * 1024)
                            if (nextCap <= buffer.capacity()) throw IOException("Video frame exceeds trim buffer limit")
                            buffer = ByteBuffer.allocate(nextCap)
                            extractor.readSampleData(buffer, 0)
                        }
                        if (sampleSize < 0) break

                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs > endUs) break

                        val srcTrack = extractor.sampleTrackIndex
                        val dstTrack = trackIndexMap[srcTrack]
                        if (dstTrack != null) {
                            val rawFlags = extractor.sampleFlags
                            val isSync = (rawFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                            // Always start the video stream on a real IDR keyframe (never drop it!)
                            // so trimmed clips never begin with green/frozen/corrupted frames.
                            if (!seenVideoSync) {
                                if (srcTrack == videoSrcTrack && isSync) {
                                    seenVideoSync = true
                                    basePtsUs = sampleTimeUs
                                } else {
                                    extractor.advance()
                                    continue
                                }
                            } else if (basePtsUs < 0L) {
                                basePtsUs = sampleTimeUs
                            }

                            if (sampleTimeUs >= basePtsUs) {
                                bufferInfo.offset = 0
                                bufferInfo.size = sampleSize
                                bufferInfo.presentationTimeUs = (sampleTimeUs - basePtsUs).coerceAtLeast(0L)
                                bufferInfo.flags = mapExtractorFlagsToMuxerFlags(rawFlags)
                                muxer.writeSampleData(dstTrack, buffer, bufferInfo)
                            }
                        }
                        extractor.advance()
                    }
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                }

                // Publish trimmed clip cleanly into Movies/LuminaGallery
                val stem = item.name.substringBeforeLast('.').take(80).ifBlank { "video" }
                val outName = "${stem}_trimmed_${System.currentTimeMillis() % 100000}.$outExt"
                saveVideoToMediaStore(tempOutputFile, outName, outMime)
                outName
            } finally {
                runCatching { extractor.release() }
                runCatching { retriever.release() }
                runCatching { tempOutputFile.delete() }
                runCatching { tempInputFile?.delete() }
            }
        }
    }

    private fun saveVideoToMediaStore(sourceFile: File, displayName: String, mimeType: String) {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
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
            val outFile = File(dir, displayName)
            sourceFile.copyTo(outFile, overwrite = true)
            MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf(mimeType), null)
        }
    }

    companion object {
        private val MP4_VIDEO_MIMES = setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_MPEG4,
            MediaFormat.MIMETYPE_VIDEO_H263,
            "video/av01"
        )
        private val MP4_AUDIO_MIMES = setOf(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            MediaFormat.MIMETYPE_AUDIO_AMR_WB,
            MediaFormat.MIMETYPE_AUDIO_MPEG
        )
        private val WEBM_VIDEO_MIMES = setOf(
            MediaFormat.MIMETYPE_VIDEO_VP8,
            MediaFormat.MIMETYPE_VIDEO_VP9
        )
        private val WEBM_AUDIO_MIMES = setOf(
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaFormat.MIMETYPE_AUDIO_VORBIS
        )

        /**
         * Converts `MediaExtractor.sampleFlags` into `MediaCodec.BufferInfo.flags`.
         * Never pass raw extractor flags directly because `SAMPLE_FLAG_PARTIAL_FRAME` (4)
         * collides with `MediaCodec.BUFFER_FLAG_END_OF_STREAM` (4).
         */
        internal fun mapExtractorFlagsToMuxerFlags(sampleFlags: Int): Int {
            var flags = 0
            if ((sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            return flags
        }
    }
}
