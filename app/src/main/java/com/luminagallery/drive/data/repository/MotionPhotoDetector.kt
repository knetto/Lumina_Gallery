package com.luminagallery.drive.data.repository

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
 * Universal Motion / Live Photo detector & extractor supporting:
 *  1. **Samsung Motion Photos** (`MotionPhoto_Data` trailer marker + One UI 6/7/8 XMP Container:Directory).
 *  2. **Xiaomi HyperOS / MIUI & Google Pixel Motion Photos** (XMP `GCamera:MicroVideoOffset`,
 *     Motion Photo V2 `Container:Directory` `Item:Semantic="MotionPhoto"`, plus binary MP4 `ftyp` scan).
 *  3. **iPhone Live Photos** (`.HEIC` / `.JPG` paired with companion short `.MOV` / `.MP4` sharing the
 *     exact same filename stem in the same album).
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
        if (item.isVideo) return@withContext null

        cache[item.id]?.let { return@withContext it }
        if (negativeCache.containsKey(item.id)) return@withContext null

        // 1. Check for iPhone Live Photo companion .MOV / .MP4 in the same album/folder
        val companion = findIphoneLiveCompanion(item, siblingItems)
        if (companion != null) {
            val uri = companion.localContentUri
            if (!uri.isNullOrBlank()) {
                val info = MotionPhotoInfo(
                    kind = MotionPhotoKind.IPHONE_LIVE_PHOTO,
                    videoUri = uri
                )
                cache[item.id] = info
                return@withContext info
            }
        }

        // 2. Inspect embedded MP4 inside local photo or offline-pinned Drive photo
        val bytes = readPhotoBytesIfEligible(item, offlineFilePath)
        if (bytes == null || bytes.size < 4096) {
            negativeCache[item.id] = true
            return@withContext null
        }

        val detected = detectEmbeddedMotionVideo(bytes)
        if (detected != null) {
            cache[item.id] = detected
            return@withContext detected
        }

        negativeCache[item.id] = true
        null
    }

    /**
     * Resolves a directly playable URI for Media3 ExoPlayer:
     * - For iPhone Live Photos: returns the companion `.MOV` URI immediately.
     * - For Samsung / Xiaomi / Pixel embedded MP4s: extracts the embedded MP4 bytes to a
     *   cached temp file (auto-pruned) so hardware decoders play cleanly without offset quirks.
     */
    suspend fun resolvePlayableVideoUri(
        item: MediaItem,
        info: MotionPhotoInfo,
        offlineFilePath: String? = null
    ): Uri? = withContext(Dispatchers.IO) {
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

        val bytes = readPhotoBytesIfEligible(item, offlineFilePath) ?: return@withContext null
        val start = info.embeddedVideoOffset.toInt()
        if (start < 0 || start >= bytes.size) return@withContext null
        val length = if (info.embeddedVideoLength > 0L && start + info.embeddedVideoLength <= bytes.size) {
            info.embeddedVideoLength.toInt()
        } else {
            bytes.size - start
        }
        if (length < 1024) return@withContext null

        pruneCacheIfNeeded()
        runCatching {
            FileOutputStream(outFile).use { out ->
                out.write(bytes, start, length)
            }
            Uri.fromFile(outFile)
        }.getOrNull()
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

    private fun readPhotoBytesIfEligible(item: MediaItem, offlineFilePath: String?): ByteArray? {
        // Guard against reading 100MB RAW DNG files into RAM
        if (item.isRaw || item.sizeBytes > MAX_SCAN_BYTES) return null

        if (!offlineFilePath.isNullOrBlank()) {
            val file = File(offlineFilePath)
            if (file.exists() && file.length() in 4096..MAX_SCAN_BYTES) {
                return runCatching { file.readBytes() }.getOrNull()
            }
        }
        if (!item.isLocal || item.localContentUri.isNullOrBlank()) return null

        return runCatching {
            val uri = Uri.parse(item.localContentUri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                readCapped(stream, MAX_SCAN_BYTES.toInt())
            }
        }.getOrNull()
    }

    private fun readCapped(stream: InputStream, maxBytes: Int): ByteArray? {
        val raw = stream.readBytes()
        return if (raw.size <= maxBytes) raw else null
    }

    private fun detectEmbeddedMotionVideo(bytes: ByteArray): MotionPhotoInfo? {
        val total = bytes.size

        // Strategy A: Samsung classic Motion Photo marker "MotionPhoto_Data"
        val samsungMarker = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)
        val samsungIdx = indexOfBytes(bytes, samsungMarker, 0)
        if (samsungIdx >= 0) {
            val videoStart = findFtypBoxStart(bytes, samsungIdx + samsungMarker.size)
                ?: (samsungIdx + samsungMarker.size)
            if (videoStart in 1 until (total - 1024)) {
                return MotionPhotoInfo(
                    kind = MotionPhotoKind.SAMSUNG_MOTION,
                    embeddedVideoOffset = videoStart.toLong(),
                    embeddedVideoLength = (total - videoStart).toLong()
                )
            }
        }

        // Strategy B: Parse XMP header (first 96 KB) for Motion Photo V2 (Samsung One UI 6+, Xiaomi HyperOS, Pixel)
        // or legacy GCamera:MicroVideoOffset
        val xmpWindowSize = total.coerceAtMost(98_304)
        val headerStr = String(bytes, 0, xmpWindowSize, Charsets.ISO_8859_1)

        // Motion Photo V2: Container:Directory -> Item:Semantic="MotionPhoto" ... Item:Length="1234567"
        val v2Match = XMP_MOTION_V2_LEN_REGEX.find(headerStr)
            ?: XMP_MOTION_V2_LEN_ALT_REGEX.find(headerStr)
        if (v2Match != null) {
            val mp4Len = v2Match.groupValues[1].toLongOrNull() ?: 0L
            if (mp4Len in 4096L until total.toLong()) {
                val rawOffset = total - mp4Len.toInt()
                val verifiedOffset = findFtypBoxStart(bytes, (rawOffset - 64).coerceAtLeast(0)) ?: rawOffset
                val isSamsung = headerStr.contains("Samsung", ignoreCase = true) ||
                    headerStr.contains("MotionPhoto", ignoreCase = true) && headerStr.contains("sec", ignoreCase = true)
                return MotionPhotoInfo(
                    kind = if (isSamsung) MotionPhotoKind.SAMSUNG_MOTION else MotionPhotoKind.XIAOMI_PIXEL_MOTION,
                    embeddedVideoOffset = verifiedOffset.toLong(),
                    embeddedVideoLength = (total - verifiedOffset).toLong()
                )
            }
        }

        // Legacy GCamera:MicroVideoOffset (Xiaomi MIUI / Google Pixel)
        val microMatch = XMP_MICRO_OFFSET_REGEX.find(headerStr)
        if (microMatch != null) {
            val offsetFromEnd = microMatch.groupValues[1].toLongOrNull() ?: 0L
            if (offsetFromEnd in 4096L until total.toLong()) {
                val rawOffset = total - offsetFromEnd.toInt()
                val verifiedOffset = findFtypBoxStart(bytes, (rawOffset - 64).coerceAtLeast(0)) ?: rawOffset
                return MotionPhotoInfo(
                    kind = MotionPhotoKind.XIAOMI_PIXEL_MOTION,
                    embeddedVideoOffset = verifiedOffset.toLong(),
                    embeddedVideoLength = (total - verifiedOffset).toLong()
                )
            }
        }

        // Strategy C: Binary MP4 `ftyp` box fallback (catches Xiaomi / OPPO / Vivo / Huawei / Samsung HEIC+JPG motion variants)
        // Skip first 16 bytes so HEIC's own file-level `ftypheic` box is NOT mistaken for an embedded video!
        val ftypStart = findVideoFtypAfterStill(bytes)
        if (ftypStart != null && (total - ftypStart) >= 8192) {
            return MotionPhotoInfo(
                kind = MotionPhotoKind.XIAOMI_PIXEL_MOTION,
                embeddedVideoOffset = ftypStart.toLong(),
                embeddedVideoLength = (total - ftypStart).toLong()
            )
        }

        return null
    }

    private fun findFtypBoxStart(bytes: ByteArray, fromIndex: Int): Int? {
        val ftyp = "ftyp".toByteArray(Charsets.US_ASCII)
        val limit = (bytes.size - 12).coerceAtLeast(0)
        var pos = fromIndex.coerceAtLeast(4)
        while (pos < limit) {
            val idx = indexOfBytes(bytes, ftyp, pos)
            if (idx < 4 || idx + 8 > bytes.size) return null
            if (isVideoBrand(bytes, idx + 4)) {
                return idx - 4
            }
            pos = idx + 4
        }
        return null
    }

    private fun findVideoFtypAfterStill(bytes: ByteArray): Int? {
        // For JPEG (starts with FF D8), scan for an embedded MP4 ftyp box past the first 12KB
        // For HEIC (starts with ftypheic/ftypmif1), scan past byte 4096 for a second video ftyp box
        val startOffset = 4096.coerceAtMost(bytes.size)
        return findFtypBoxStart(bytes, startOffset)
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

    private fun indexOfBytes(haystack: ByteArray, needle: ByteArray, start: Int): Int {
        if (needle.isEmpty() || start < 0) return -1
        val max = haystack.size - needle.size
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
        const val MAX_SCAN_BYTES = 35L * 1024L * 1024L
        const val MAX_EXTRACT_CACHE_BYTES = 28L * 1024L * 1024L

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
