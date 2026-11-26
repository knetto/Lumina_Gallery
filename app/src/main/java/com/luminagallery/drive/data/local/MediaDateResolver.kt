package com.luminagallery.drive.data.local

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs

/**
 * Recovers original capture dates from standard camera/app filenames (Samsung, Pixel, Xiaomi,
 * WhatsApp, Telegram, Signal, Screenshots, Dropbox, Facebook, WeChat, etc.) whenever a phone
 * transfer, copy, or cloud upload overwrites `DATE_MODIFIED` / `DATE_TAKEN` with the transfer date.
 */
object MediaDateResolver {

    data class ParsedFilenameDate(
        val epochMs: Long,
        val hasTime: Boolean
    )

    data class ResolvedMediaTimestamp(
        val epochMs: Long,
        val correctedFromFilename: Boolean
    )

    private const val MIN_VALID_YEAR = 1995
    private const val MIN_VALID_EPOCH_MS = 788_918_400_000L // 1995-01-01T00:00:00Z
    private const val MIN_UNIX_EPOCH_MS = 1_104_537_600_000L // 2005-01-01T00:00:00Z
    private const val FUTURE_TOLERANCE_MS = 36L * 3_600_000L
    private const val TRANSFER_OVERRIDE_THRESHOLD_MS = 36L * 3_600_000L

    // 1. Google Pixel UTC: PXL_20230715_142311123(.MP/.NIGHT/.PORTRAIT/.TS)
    private val PIXEL_UTC_REGEX = Regex(
        """(?i)(?:^|[^A-Za-z0-9])PXL_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})(?:\d{1,3})?(?!\d)"""
    )

    // 2. WhatsApp Web / Desktop: "WhatsApp Image 2023-07-15 at 14.23.11" or "at 2.23.11 PM"
    private val WHATSAPP_WEB_REGEX = Regex(
        """(?i)WhatsApp\s+(?:Image|Video|Audio)\s+(\d{4})[-_.](\d{2})[-_.](\d{2})\s+at\s+(\d{1,2})[.:_-](\d{2})[.:_-](\d{2})(?:\s*(AM|PM))?(?!\d)"""
    )

    // 3. Separated Date + Time:
    //    Screenshot_2023-07-15-14-23-11-123_app, photo_2023-07-15_14-23-11,
    //    2023-07-15 14.23.11, Screenshot 2023-07-15 at 14.23.11, signal-2023-07-15-14-23-11
    private val SEPARATED_DATETIME_REGEX = Regex(
        """(?<!\d)((?:19|20)\d{2})[-_.](\d{2})[-_.](\d{2})(?:[-_ T]|(?i:\s+at\s+))(\d{2})[-_.:](\d{2})[-_.:](\d{2})(?!\d)"""
    )

    // 4. Separated Date + Compact Time: signal-2023-07-15-142311_002, Screenshot 2023-07-15 142311
    private val SEPARATED_DATE_COMPACT_TIME_REGEX = Regex(
        """(?<!\d)((?:19|20)\d{2})[-_.](\d{2})[-_.](\d{2})[-_ T](\d{2})(\d{2})(\d{2})(?!\d)"""
    )

    // 5. Compact Date + Separated Time: 20230715_142311, IMG_20230715_142311_HDR, Screenshot_20230715-142311_App
    private val COMPACT_DATETIME_REGEX = Regex(
        """(?<!\d)((?:19|20)\d{2})(\d{2})(\d{2})[-_ T](\d{2})(\d{2})(\d{2})(?:\d{1,3})?(?!\d)"""
    )

    // 6. Contiguous 14-digit timestamp: IMG20230715142311, VID20230715142311, BURST20230715142311_COVER
    private val CONTIGUOUS_14_REGEX = Regex(
        """(?<!\d)((?:19|20)\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})(?!\d)"""
    )

    // 7. WhatsApp Mobile: IMG-20230715-WA0012, VID-20230715-WA0003, PTT-20230715-WA0001
    private val WHATSAPP_MOBILE_REGEX = Regex(
        """(?i)(?:^|[^A-Za-z0-9])(?:IMG|VID|AUD|PTT|DOC)-((?:19|20)\d{2})(\d{2})(\d{2})-WA(\d{1,6})(?!\d)"""
    )

    // 8. Separated Date-Only: 2023-07-15, IMG_2023_07_15_vacation
    private val SEPARATED_DATE_ONLY_REGEX = Regex(
        """(?<!\d)((?:19|20)\d{2})[-_.](\d{2})[-_.](\d{2})(?!\d)"""
    )

    // 9. Compact Date-Only: IMG_20230715, 20230715_001, 20230715
    private val COMPACT_DATE_ONLY_REGEX = Regex(
        """(?<!\d)((?:19|20)\d{2})(\d{2})(\d{2})(?!\d)"""
    )

    // 10. Known-prefix 13-digit Unix ms timestamps (Facebook, WeChat, generic Android download)
    //     Explicitly avoids Snapchat/received random numeric IDs.
    private val KNOWN_UNIX_MS_REGEX = Regex(
        """(?i)^(?:FB_IMG_|IMG_|VID_|mmexport|wx_camera_)?(1[1-8]\d{11})(?:[^0-9].*)?$"""
    )

    fun resolveEffectiveTimestamp(
        fileName: String,
        mediaStoreDateTakenMs: Long,
        fileModifiedMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): ResolvedMediaTimestamp {
        val maxAllowedMs = nowEpochMs + FUTURE_TOLERANCE_MS
        val validTaken = mediaStoreDateTakenMs.takeIf { it in MIN_VALID_EPOCH_MS..maxAllowedMs }
        val validModified = fileModifiedMs.takeIf { it in MIN_VALID_EPOCH_MS..maxAllowedMs }
        val parsed = parseFilenameTimestamp(fileName, zone, nowEpochMs)

        if (parsed == null) {
            val fallback = validTaken
                ?: validModified
                ?: mediaStoreDateTakenMs.takeIf { it > 0L }
                ?: fileModifiedMs.takeIf { it > 0L }
                ?: 0L
            return ResolvedMediaTimestamp(epochMs = fallback, correctedFromFilename = false)
        }

        if (validTaken != null) {
            // If MediaStore DATE_TAKEN is more than 36h AFTER the filename's capture date,
            // it was almost certainly overwritten during phone transfer / copy / re-indexing.
            return if (validTaken - parsed.epochMs > TRANSFER_OVERRIDE_THRESHOLD_MS) {
                ResolvedMediaTimestamp(epochMs = parsed.epochMs, correctedFromFilename = true)
            } else {
                ResolvedMediaTimestamp(epochMs = validTaken, correctedFromFilename = false)
            }
        }

        // MediaStore has no valid DATE_TAKEN -> it would otherwise fall back to fileModifiedMs (transfer date).
        if (validModified == null) {
            return ResolvedMediaTimestamp(epochMs = parsed.epochMs, correctedFromFilename = true)
        }

        if (!parsed.hasTime) {
            // Date-only filename (e.g. WhatsApp IMG-20230715-WA0012.jpg):
            // If fileModifiedMs is already on that exact calendar day, keep its time-of-day!
            val modDate = Instant.ofEpochMilli(validModified).atZone(zone).toLocalDate()
            val fnDate = Instant.ofEpochMilli(parsed.epochMs).atZone(zone).toLocalDate()
            if (modDate == fnDate) {
                return ResolvedMediaTimestamp(epochMs = validModified, correctedFromFilename = false)
            }
            // Never push an older file forward in time if mtime is legitimately much earlier than filename.
            if (parsed.epochMs - validModified > TRANSFER_OVERRIDE_THRESHOLD_MS) {
                return ResolvedMediaTimestamp(epochMs = validModified, correctedFromFilename = false)
            }
            return ResolvedMediaTimestamp(epochMs = parsed.epochMs, correctedFromFilename = true)
        }

        // Filename has full date + time, and MediaStore had no valid DATE_TAKEN:
        // Never move a file forward if its fileModifiedMs is already legitimately >36h older.
        if (parsed.epochMs - validModified > TRANSFER_OVERRIDE_THRESHOLD_MS) {
            return ResolvedMediaTimestamp(epochMs = validModified, correctedFromFilename = false)
        }
        val materiallyDifferent = abs(validModified - parsed.epochMs) > 60_000L
        return ResolvedMediaTimestamp(
            epochMs = parsed.epochMs,
            correctedFromFilename = materiallyDifferent
        )
    }

    fun parseFilenameTimestamp(
        fileName: String,
        zone: ZoneId = ZoneId.systemDefault(),
        nowEpochMs: Long = System.currentTimeMillis()
    ): ParsedFilenameDate? {
        val raw = fileName.trim()
        if (raw.isEmpty()) return null
        val stem = raw.substringBeforeLast('.', raw).trim()
        if (stem.length < 8) return null
        val maxAllowedMs = nowEpochMs + FUTURE_TOLERANCE_MS
        val maxYear = Instant.ofEpochMilli(maxAllowedMs).atZone(zone).year

        // 1. Google Pixel UTC filenames (PXL_YYYYMMDD_HHMMSSmmm)
        PIXEL_UTC_REGEX.find(stem)?.let { m ->
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toInt(),
                zone = ZoneOffset.UTC,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { return ParsedFilenameDate(it, hasTime = true) }
        }

        // 2. WhatsApp Web / Desktop filenames ("WhatsApp Image 2023-07-15 at 14.23.11" / "2.23.11 PM")
        WHATSAPP_WEB_REGEX.find(stem)?.let { m ->
            val rawHour = m.groupValues[4].toInt()
            val amPm = m.groupValues[7].uppercase(Locale.US)
            val hour24 = when (amPm) {
                "AM" -> if (rawHour == 12) 0 else if (rawHour in 1..11) rawHour else -1
                "PM" -> if (rawHour == 12) 12 else if (rawHour in 1..11) rawHour + 12 else -1
                else -> rawHour
            }
            if (hour24 in 0..23) {
                buildEpochMs(
                    year = m.groupValues[1].toInt(),
                    month = m.groupValues[2].toInt(),
                    day = m.groupValues[3].toInt(),
                    hour = hour24,
                    minute = m.groupValues[5].toInt(),
                    second = m.groupValues[6].toInt(),
                    zone = zone,
                    maxYear = maxYear,
                    maxAllowedMs = maxAllowedMs
                )?.let { return ParsedFilenameDate(it, hasTime = true) }
            }
        }

        // 3. Separated Date + Time
        SEPARATED_DATETIME_REGEX.find(stem)?.let { m ->
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toInt(),
                zone = zone,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { return ParsedFilenameDate(it, hasTime = true) }
        }

        // 4. Separated Date + Compact Time
        SEPARATED_DATE_COMPACT_TIME_REGEX.find(stem)?.let { m ->
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toInt(),
                zone = zone,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { return ParsedFilenameDate(it, hasTime = true) }
        }

        // 5. Compact Date + Time (Samsung, MIUI, AOSP, Google Camera, Screenshots)
        COMPACT_DATETIME_REGEX.find(stem)?.let { m ->
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toInt(),
                zone = zone,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { return ParsedFilenameDate(it, hasTime = true) }
        }

        // 6. Contiguous 14-digit YYYYMMDDHHMMSS
        CONTIGUOUS_14_REGEX.find(stem)?.let { m ->
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = m.groupValues[4].toInt(),
                minute = m.groupValues[5].toInt(),
                second = m.groupValues[6].toInt(),
                zone = zone,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { return ParsedFilenameDate(it, hasTime = true) }
        }

        // 7. WhatsApp Mobile (IMG-YYYYMMDD-WAxxxx / VID-YYYYMMDD-WAxxxx)
        //    Adds sequence offset in seconds after noon so same-day WA items keep chronological order.
        WHATSAPP_MOBILE_REGEX.find(stem)?.let { m ->
            val seq = m.groupValues[4].toIntOrNull()?.coerceIn(0, 40_000) ?: 0
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = 12,
                minute = 0,
                second = 0,
                zone = zone,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { noonMs ->
                return ParsedFilenameDate((noonMs + seq * 1_000L).coerceAtMost(maxAllowedMs), hasTime = false)
            }
        }

        // 8. Separated Date-Only (YYYY-MM-DD / YYYY_MM_DD)
        SEPARATED_DATE_ONLY_REGEX.find(stem)?.let { m ->
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = 12,
                minute = 0,
                second = 0,
                zone = zone,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { return ParsedFilenameDate(it, hasTime = false) }
        }

        // 9. Compact Date-Only (YYYYMMDD)
        COMPACT_DATE_ONLY_REGEX.find(stem)?.let { m ->
            buildEpochMs(
                year = m.groupValues[1].toInt(),
                month = m.groupValues[2].toInt(),
                day = m.groupValues[3].toInt(),
                hour = 12,
                minute = 0,
                second = 0,
                zone = zone,
                maxYear = maxYear,
                maxAllowedMs = maxAllowedMs
            )?.let { return ParsedFilenameDate(it, hasTime = false) }
        }

        // 10. Known-prefix 13-digit Unix ms timestamps (FB_IMG_, mmexport, bare 13-digit epoch)
        KNOWN_UNIX_MS_REGEX.find(stem)?.let { m ->
            val ms = m.groupValues[1].toLongOrNull()
            if (ms != null && ms in MIN_UNIX_EPOCH_MS..maxAllowedMs) {
                return ParsedFilenameDate(ms, hasTime = true)
            }
        }

        return null
    }

    private fun buildEpochMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        zone: ZoneId,
        maxYear: Int,
        maxAllowedMs: Long
    ): Long? {
        if (year !in MIN_VALID_YEAR..maxYear) return null
        if (month !in 1..12 || day !in 1..31) return null
        if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val epochMs = runCatching {
            val date = LocalDate.of(year, month, day)
            LocalDateTime.of(date.year, date.monthValue, date.dayOfMonth, hour, minute, second)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: return null
        if (epochMs < MIN_VALID_EPOCH_MS || epochMs > maxAllowedMs) return null
        return epochMs
    }
}
