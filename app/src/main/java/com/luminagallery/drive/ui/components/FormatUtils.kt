package com.luminagallery.drive.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.luminagallery.drive.domain.model.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object FormatUtils {

    private val SHORT_TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun formatDuration(durationMillis: Long?): String {
        val totalSeconds = ((durationMillis ?: 0L) / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Zero-padded Samsung Gallery video player timestamp: e.g. "00:01" or "1:02:05". */
    fun formatDurationPadded(durationMillis: Long?): String {
        val totalSeconds = ((durationMillis ?: 0L) / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatShutterSpeed(exposureTime: Float?): String? {
        if (exposureTime == null || exposureTime <= 0f) return null
        return if (exposureTime >= 1f) {
            String.format(Locale.US, "%.1fs", exposureTime)
        } else {
            val reciprocal = (1f / exposureTime).roundToInt().coerceAtLeast(1)
            "1/${reciprocal}s"
        }
    }

    fun formatAperture(aperture: Float?): String? {
        if (aperture == null || aperture <= 0f) return null
        return String.format(Locale.US, "f/%.1f", aperture)
    }

    fun formatFocalLength(focalLength: Float?): String? {
        if (focalLength == null || focalLength <= 0f) return null
        return "${focalLength.roundToInt()} mm"
    }

    fun formatMegapixels(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        val mp = (width.toLong() * height.toLong()) / 1_000_000.0
        return String.format(Locale.US, "%.1f MP", mp)
    }

    fun formatExposureBias(ev: Float?): String? {
        if (ev == null || !ev.isFinite()) return null
        if (kotlin.math.abs(ev) < 0.05f) return "0 EV"
        return String.format(Locale.US, "%+.1f EV", ev)
    }

    fun formatBitrate(bps: Long?): String? {
        if (bps == null || bps <= 0L) return null
        return when {
            bps >= 1_000_000L -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
            bps >= 1_000L -> String.format(Locale.US, "%.0f kbps", bps / 1_000.0)
            else -> "$bps bps"
        }
    }

    fun formatFrameRate(fps: Float?): String? {
        if (fps == null || !fps.isFinite() || fps <= 0f) return null
        val rounded = fps.roundToInt()
        return if (kotlin.math.abs(fps - rounded) < 0.15f) {
            "$rounded fps"
        } else {
            String.format(Locale.US, "%.1f fps", fps)
        }
    }

    fun formatCoordinates(lat: Double, lon: Double): String {
        val ns = if (lat >= 0.0) "N" else "S"
        val ew = if (lon >= 0.0) "E" else "W"
        return String.format(
            Locale.US,
            "%.4f° %s, %.4f° %s",
            kotlin.math.abs(lat),
            ns,
            kotlin.math.abs(lon),
            ew
        )
    }

    fun formatAltitude(altitudeMeters: Double?): String? {
        if (altitudeMeters == null || !altitudeMeters.isFinite()) return null
        return "${altitudeMeters.roundToInt()} m"
    }

    fun formatAspectRatio(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        val longSide = maxOf(width, height).toDouble()
        val shortSide = minOf(width, height).toDouble()
        val ratio = longSide / shortSide
        val isPortrait = height > width
        val named = when {
            kotlin.math.abs(ratio - 1.0) < 0.03 -> "1:1"
            kotlin.math.abs(ratio - 4.0 / 3.0) < 0.04 -> if (isPortrait) "3:4" else "4:3"
            kotlin.math.abs(ratio - 3.0 / 2.0) < 0.04 -> if (isPortrait) "2:3" else "3:2"
            kotlin.math.abs(ratio - 16.0 / 9.0) < 0.05 -> if (isPortrait) "9:16" else "16:9"
            kotlin.math.abs(ratio - 16.0 / 10.0) < 0.05 -> if (isPortrait) "10:16" else "16:10"
            kotlin.math.abs(ratio - 20.0 / 9.0) < 0.06 -> if (isPortrait) "9:20" else "20:9"
            kotlin.math.abs(ratio - 21.0 / 9.0) < 0.06 -> if (isPortrait) "9:21" else "21:9"
            else -> null
        }
        return named
    }

    private val COMPACT_DATE_FMT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())

    fun formatCalendarDate(epochMs: Long): String {
        if (epochMs <= 0L) return ""
        return COMPACT_DATE_FMT.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
    }

    fun formatFolderSummary(subfolderCount: Int, mediaCount: Int): String = when {
        subfolderCount > 0 && mediaCount > 0 ->
            "$subfolderCount ${if (subfolderCount == 1) "folder" else "folders"} · $mediaCount"
        subfolderCount > 0 ->
            "$subfolderCount ${if (subfolderCount == 1) "folder" else "folders"}"
        mediaCount > 0 ->
            "$mediaCount ${if (mediaCount == 1) "item" else "items"}"
        else -> "Empty"
    }

    /**
     * Samsung Gallery-style breakdown directly under album title:
     * e.g. "165 images 7 videos", "42 images", "1 video", or "12 images · 3 folders".
     */
    fun formatMediaBreakdown(items: List<MediaItem>, subfolderCount: Int = 0): String {
        val videoCount = items.count { it.isVideo }
        val imageCount = items.size - videoCount
        val mediaParts = buildList {
            if (imageCount > 0) add("$imageCount ${if (imageCount == 1) "image" else "images"}")
            if (videoCount > 0) add("$videoCount ${if (videoCount == 1) "video" else "videos"}")
        }.joinToString(" ")

        return when {
            mediaParts.isNotEmpty() && subfolderCount > 0 ->
                "$mediaParts · $subfolderCount ${if (subfolderCount == 1) "folder" else "folders"}"
            mediaParts.isNotEmpty() -> mediaParts
            subfolderCount > 0 -> "$subfolderCount ${if (subfolderCount == 1) "folder" else "folders"}"
            else -> "0 items"
        }
    }

    /**
     * Formats an on-device file's folder source clearly for Duplicate Files review.
     */
    fun formatReadableSourcePath(albumName: String?, storagePath: String?): String {
        val rawPath = storagePath?.trim('/')?.takeIf { it.isNotBlank() }
        if (rawPath != null) {
            val cleaned = rawPath
                .replace("Android/media/com.whatsapp/WhatsApp/Media/", "WhatsApp/")
                .replace("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/", "WhatsApp Business/")
            return cleaned
        }
        return albumName?.takeIf { it.isNotBlank() } ?: "Internal storage"
    }

    fun formatLastSync(epochMs: Long?): String {
        if (epochMs == null || epochMs <= 0L) return "Never synced"
        val deltaMs = (System.currentTimeMillis() - epochMs).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1L -> "Just now"
            minutes < 60L -> "${minutes}m ago"
            minutes < 24 * 60L -> "${minutes / 60}h ago"
            else -> SHORT_TIMESTAMP_FORMATTER.format(
                Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
            )
        }
    }

    fun launchShareSheet(context: Context, uris: List<Uri>, mimeType: String = "*/*") {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val chooser = Intent.createChooser(intent, "Share media").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
