package com.luminagallery.drive.data.repository

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

enum class MotionPhotoKind(val badgeLabel: String) {
    SAMSUNG_MOTION("Motion"),
    XIAOMI_PIXEL_MOTION("Motion"),
    IPHONE_LIVE_PHOTO("Live")
}

@Immutable
data class MotionPhotoInfo(
    val kind: MotionPhotoKind,
    /** Either a companion content:// / file:// URI (iPhone Live Photo .MOV) or extracted cached MP4 file URI. */
    val videoUri: String? = null,
    /** Byte offset inside the photo file if video is embedded (Samsung / Xiaomi / Pixel). */
    val embeddedVideoOffset: Long = -1L,
    val embeddedVideoLength: Long = -1L
)

/**
 * Memory-safe, streaming Motion / Live Photo detector & extractor supporting:
 *  1. **Samsung Motion Photos** (`MotionPhoto_Data` trailer marker + One UI 6/7/8 XMP Container:Directory).
 *  2. **Xiaomi HyperOS / MIUI & Google Pixel Motion Photos** (XMP `GCamera:MicroVideoOffset`,
 *     Motion Photo V2 `Container:Directory` `Item:Semantic="MotionPhoto"`, plus streaming `ftyp` scan).
 *  3. **iPhone Live Photos** (`.HEIC` / `.JPG` paired with companion short `.MOV` / `.MP4` sharing the
 *     exact same filename stem in the same album).
 *
 * Strictly uses bounded buffers (<= 96 KB header + 64 KB sliding scan window) so 108MP/200MP
 * camera photos NEVER spike heap memory or OOM while swiping rapidly through the viewer.
 */
class MotionPhotoDetector(private val context: Context) {

    private val cache = ConcurrentHashMap<String, MotionPhotoInfo>()
    private val negativeCache = ConcurrentHashMap<String, Boolean>()

    private val extractDir: File
        get() = File(context.cacheDir, "motion_photos").apply { if (!exists()) mkdirs() }

    suspend fun detect(
        item: MediaItem,
        siblingItems: List<MediaItem>,
        offlineFilePath: String? = null
    ): MotionPhotoInfo? = withContext(Dispatchers.IO) {
        if (item.isVideo || item.isRaw) return@withContext null

        cache[item.id]?.let { return@withContext it }

        try {
            // 1. Always check for iPhone Live Photo companion .MOV / .MP4 BEFORE consulting
            //    embedded-stream negativeCache, because Viewer boots with 1 tapped item before
            //    siblingItems finishes loading!
            val companion = findIphoneLiveCompanion(item, siblingItems)
            if (companion != null) {
                val uri = companion.localContentUri
                if (!uri.isNullOrBlank()) {
                    val info = MotionPhotoInfo(
                        kind = MotionPhotoKind.IPHONE_LIVE_PHOTO,
                        videoUri = uri
                    )
                    if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
                    cache[item.id] = info
                    negativeCache.remove(item.id)
                    return@withContext info
                }
            }

            if (negativeCache.containsKey(item.id)) return@withContext null

            // 2. Determine total file size without loading the file into RAM
            val totalSize = resolveFileSize(item, offlineFilePath)
            if (totalSize < 4096L || totalSize > MAX_SCAN_BYTES) {
                if (negativeCache.size > MAX_CACHE_ENTRIES) negativeCache.clear()
                negativeCache[item.id] = true
                return@withContext null
            }

            // 3. Fast path (< 1 ms, <= 96 KB RAM): inspect XMP header for V2 Motion Photo / MicroVideoOffset
            val headerBytes = openMediaStream(item, offlineFilePath)?.use { stream ->
                readUpTo(stream, XMP_HEADER_WINDOW_BYTES)
            }
            if (headerBytes == null || headerBytes.size < 1024) {
                if (negativeCache.size > MAX_CACHE_ENTRIES) negativeCache.clear()
                negativeCache[item.id] = true
                return@withContext null
            }

            coroutineContext.ensureActive()

            val xmpDetected = detectFromXmpHeader(headerBytes, totalSize)
            if (xmpDetected != null) {
                if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
                cache[item.id] = xmpDetected
                return@withContext xmpDetected
            }

            // 4. Streaming 64 KB sliding-window scan for classic Samsung `MotionPhoto_Data`
            //    or embedded MP4 `ftyp` box (never allocates > 64 KB heap!)
            val streamDetected = openMediaStream(item, offlineFilePath)?.use { stream ->
                scanStreamForEmbeddedVideo(stream, totalSize)
            }
            if (streamDetected != null) {
                if (cache.size > MAX_CACHE_ENTRIES) cache.clear()
                cache[item.id] = streamDetected
                return@withContext streamDetected
            }

            if (negativeCache.size > MAX_CACHE_ENTRIES) negativeCache.clear()
            negativeCache[item.id] = true
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves a directly playable URI for Media3 ExoPlayer without loading the full photo into RAM:
     * - For iPhone Live Photos: returns the companion `.MOV` URI immediately.
     * - For Samsung / Xiaomi / Pixel embedded MP4s: stream-copies only the embedded MP4 byte range
     *   in 32 KB chunks into a cached temp file (auto-pruned).
     */
    suspend fun resolvePlayableVideoUri(
        item: MediaItem,
        info: MotionPhotoInfo,
        offlineFilePath: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            if (!info.videoUri.isNullOrBlank()) {
                return@withContext Uri.parse(info.videoUri)
            }
            if (info.embeddedVideoOffset < 0L) return@withContext null

            val safeKey = item.id.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val outFile = File(extractDir, "mp_$safeKey.mp4")
            if (outFile.exists() && outFile.length() > 1024L) {
                outFile.setLastModified(System.currentTimeMillis())
                return@withContext Uri.fromFile(outFile)
            }

            val totalSize = resolveFileSize(item, offlineFilePath)
            val start = info.embeddedVideoOffset
            if (start < 0L || (totalSize > 0L && start >= totalSize)) return@withContext null

            val expectedLength = if (info.embeddedVideoLength > 0L) {
                info.embeddedVideoLength
            } else if (totalSize > start) {
                totalSize - start
            } else {
                MAX_EXTRACT_SINGLE_BYTES
            }
            if (expectedLength < 1024L || expectedLength > MAX_EXTRACT_SINGLE_BYTES) return@withContext null

            pruneCacheIfNeeded()

            val written = openMediaStream(item, offlineFilePath)?.use { input ->
                if (!skipFully(input, start)) return@use 0L
                val partFile = File(extractDir, "mp_$safeKey.part")
                var copied = 0L
                try {
                    FileOutputStream(partFile).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (copied < expectedLength) {
                            coroutineContext.ensureActive()
                            val toRead = minOf(buf.size.toLong(), expectedLength - copied).toInt()
                            val read = input.read(buf, 0, toRead)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            copied += read
                        }
                    }
                    if (copied >= 1024L) {
                        if (outFile.exists()) outFile.delete()
                        if (!partFile.renameTo(outFile)) {
                            partFile.copyTo(outFile, overwrite = true)
                            partFile.delete()
                        }
                        copied
                    } else {
                        partFile.delete()
                        0L
                    }
                } catch (e: Throwable) {
                    partFile.delete()
                    throw e
                }
            } ?: 0L

            if (written >= 1024L && outFile.exists()) Uri.fromFile(outFile) else null
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        }
    }

    private fun findIphoneLiveCompanion(item: MediaItem, siblings: List<MediaItem>): MediaItem? {
        val stem = item.name.substringBeforeLast('.', "").lowercase(Locale.US)
        if (stem.isEmpty()) return null
        return siblings.firstOrNull { cand ->
            cand.id != item.id &&
                cand.isVideo &&
                cand.parentId == item.parentId &&
                cand.name.substringBeforeLast('.', "").lowercase(Locale.US) == stem &&
                (cand.durationMillis == null || cand.durationMillis <= 6_500L)
        }
    }

    private fun resolveFileSize(item: MediaItem, offlineFilePath: String?): Long {
        if (!offlineFilePath.isNullOrBlank()) {
            val f = File(offlineFilePath)
            if (f.exists()) return f.length()
        }
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            val afdLen = runCatching {
                val uri = Uri.parse(item.localContentUri)
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
            if (afdLen > 0L) return afdLen
        }
        return item.sizeBytes
    }

    private fun openMediaStream(item: MediaItem, offlineFilePath: String?): InputStream? {
        if (!offlineFilePath.isNullOrBlank()) {
            val f = File(offlineFilePath)
            if (f.exists() && f.canRead()) {
                return runCatching { FileInputStream(f) }.getOrNull()
            }
        }
        if (!item.isLocal || item.localContentUri.isNullOrBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(item.localContentUri))
        }.getOrNull()
    }

    private fun readUpTo(stream: InputStream, maxBytes: Int): ByteArray {
        val buf = ByteArray(maxBytes)
        var offset = 0
        while (offset < maxBytes) {
            val read = stream.read(buf, offset, maxBytes - offset)
            if (read <= 0) break
            offset += read
        }
        return if (offset == maxBytes) buf else buf.copyOf(offset)
    }

    private fun skipFully(stream: InputStream, bytesToSkip: Long): Boolean {
        var remaining = bytesToSkip
        val scratch = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val toRead = minOf(scratch.size.toLong(), remaining).toInt()
                val read = stream.read(scratch, 0, toRead)
                if (read <= 0) return false
                remaining -= read
            }
        }
        return true
    }

    private fun detectFromXmpHeader(headerBytes: ByteArray, totalSize: Long): MotionPhotoInfo? {
        val headerStr = String(headerBytes, 0, headerBytes.size, Charsets.ISO_8859_1)

        // Motion Photo V2: Container:Directory -> Item:Semantic="MotionPhoto" ... Item:Length="1234567"
        val v2Match = XMP_MOTION_V2_LEN_REGEX.find(headerStr)
            ?: XMP_MOTION_V2_LEN_ALT_REGEX.find(headerStr)
        if (v2Match != null) {
            val mp4Len = v2Match.groupValues[1].toLongOrNull() ?: 0L
            if (mp4Len in 4096L until totalSize) {
                val offset = totalSize - mp4Len
                val isSamsung = headerStr.contains("Samsung", ignoreCase = true) ||
                    (headerStr.contains("MotionPhoto", ignoreCase = true) && headerStr.contains("sec", ignoreCase = true))
                return MotionPhotoInfo(
                    kind = if (isSamsung) MotionPhotoKind.SAMSUNG_MOTION else MotionPhotoKind.XIAOMI_PIXEL_MOTION,
                    embeddedVideoOffset = offset,
                    embeddedVideoLength = mp4Len
                )
            }
        }

        // Legacy GCamera:MicroVideoOffset (Xiaomi MIUI / Google Pixel)
        val microMatch = XMP_MICRO_OFFSET_REGEX.find(headerStr)
        if (microMatch != null) {
            val offsetFromEnd = microMatch.groupValues[1].toLongOrNull() ?: 0L
            if (offsetFromEnd in 4096L until totalSize) {
                val offset = totalSize - offsetFromEnd
                return MotionPhotoInfo(
                    kind = MotionPhotoKind.XIAOMI_PIXEL_MOTION,
                    embeddedVideoOffset = offset,
                    embeddedVideoLength = offsetFromEnd
                )
            }
        }

        return null
    }

    /**
     * Streams through the media file using a fixed 64 KB sliding window (with 32-byte overlap)
     * looking for Samsung's `MotionPhoto_Data` marker or an embedded MP4 `ftyp` video box
     * past the still-image header — strictly O(1) memory!
     */
    private suspend fun scanStreamForEmbeddedVideo(
        stream: InputStream,
        totalSize: Long
    ): MotionPhotoInfo? {
        val samsungMarker = SAMSUNG_MARKER
        val ftypMarker = FTYP_MARKER
        val overlap = 32
        val chunkSize = 64 * 1024
        val buf = ByteArray(chunkSize + overlap)

        var filePos = 0L
        var carry = 0
        var samsungMarkerEndPos = -1L

        while (filePos < totalSize) {
            coroutineContext.ensureActive()
            val read = stream.read(buf, carry, chunkSize)
            if (read <= 0) break
            val windowLen = carry + read
            val windowStartFilePos = filePos - carry

            // 1. Look for Samsung `MotionPhoto_Data` marker
            if (samsungMarkerEndPos < 0L) {
                val sIdx = indexOfBytes(buf, windowLen, samsungMarker, 0)
                if (sIdx >= 0) {
                    samsungMarkerEndPos = windowStartFilePos + sIdx + samsungMarker.size
                }
            }

            // 2. Look for an embedded MP4 video `ftyp` box (past byte 4096 so HEIC's `ftypheic` is skipped)
            var scanFrom = 4
            while (scanFrom < windowLen - 8) {
                val fIdx = indexOfBytes(buf, windowLen, ftypMarker, scanFrom)
                if (fIdx < 0 || fIdx + 8 > windowLen) break
                val boxStartAbs = windowStartFilePos + fIdx - 4
                if (boxStartAbs >= 4096L && isVideoBrand(buf, fIdx + 4)) {
                    val remaining = totalSize - boxStartAbs
                    if (remaining >= 8192L) {
                        val kind = if (samsungMarkerEndPos in 0L..boxStartAbs) {
                            MotionPhotoKind.SAMSUNG_MOTION
                        } else {
                            MotionPhotoKind.XIAOMI_PIXEL_MOTION
                        }
                        return MotionPhotoInfo(
                            kind = kind,
                            embeddedVideoOffset = boxStartAbs,
                            embeddedVideoLength = remaining
                        )
                    }
                }
                scanFrom = fIdx + 4
            }

            filePos += read
            if (windowLen >= overlap) {
                System.arraycopy(buf, windowLen - overlap, buf, 0, overlap)
                carry = overlap
            } else {
                carry = windowLen
            }
        }

        if (samsungMarkerEndPos in 1L until (totalSize - 4096L)) {
            return MotionPhotoInfo(
                kind = MotionPhotoKind.SAMSUNG_MOTION,
                embeddedVideoOffset = samsungMarkerEndPos,
                embeddedVideoLength = totalSize - samsungMarkerEndPos
            )
        }

        return null
    }

    private fun isVideoBrand(bytes: ByteArray, brandOffset: Int): Boolean {
        if (brandOffset + 4 > bytes.size) return false
        val b0 = bytes[brandOffset].toInt().toChar()
        val b1 = bytes[brandOffset + 1].toInt().toChar()
        val b2 = bytes[brandOffset + 2].toInt().toChar()
        val b3 = bytes[brandOffset + 3].toInt().toChar()
        val brand = "$b0$b1$b2$b3"
        return brand == "mp42" || brand == "mp41" || brand == "isom" ||
            brand == "iso2" || brand == "qt  " || brand == "M4V " ||
            brand == "3gp4" || brand == "3gp5" || brand == "avc1"
    }

    private fun indexOfBytes(haystack: ByteArray, haystackLen: Int, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty() || start < 0) return -1
        val max = haystackLen - needle.size
        var i = start
        val first = needle[0]
        while (i <= max) {
            if (haystack[i] == first) {
                var matched = true
                for (j in 1 until needle.size) {
                    if (haystack[i + j] != needle[j]) {
                        matched = false
                        break
                    }
                }
                if (matched) return i
            }
            i++
        }
        return -1
    }

    private fun pruneCacheIfNeeded() {
        val files = extractDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_EXTRACT_CACHE_BYTES) break
            total -= f.length()
            runCatching { f.delete() }
        }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 1_500
        const val XMP_HEADER_WINDOW_BYTES = 98_304
        const val MAX_SCAN_BYTES = 25L * 1024L * 1024L
        const val MAX_EXTRACT_SINGLE_BYTES = 20L * 1024L * 1024L
        const val MAX_EXTRACT_CACHE_BYTES = 28L * 1024L * 1024L

        val SAMSUNG_MARKER = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
        val FTYP_MARKER = "ftyp".toByteArray(Charsets.US_ASCII)

        val XMP_MICRO_OFFSET_REGEX = Regex("""MicroVideoOffset="(\d+)"""")
        val XMP_MOTION_V2_LEN_REGEX = Regex(
            """Item:Semantic="MotionPhoto"[^>]{0,220}?Item:Length="(\d+)"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val XMP_MOTION_V2_LEN_ALT_REGEX = Regex(
            """Item:Length="(\d+)"[^>]{0,220}?Item:Semantic="MotionPhoto"""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
    }
}
