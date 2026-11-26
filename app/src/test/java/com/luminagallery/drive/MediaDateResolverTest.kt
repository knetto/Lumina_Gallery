package com.luminagallery.drive

import com.luminagallery.drive.data.local.MediaDateResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MediaDateResolverTest {

    private val zone = ZoneId.of("Europe/Amsterdam")
    private val nowMs = LocalDateTime.of(2026, 11, 26, 12, 0, 0).atZone(zone).toInstant().toEpochMilli()
    private val transferDayMs = LocalDateTime.of(2026, 11, 20, 15, 30, 0).atZone(zone).toInstant().toEpochMilli()

    private fun localEpoch(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Long =
        LocalDateTime.of(y, m, d, h, min, s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun parsesSamsungXiaomiScreenshotAndTelegramFilenames() {
        val expected = localEpoch(2023, 7, 15, 14, 23, 11)
        val samples = listOf(
            "20230715_142311.jpg",
            "20230715_142311(0).jpg",
            "IMG_20230715_142311.jpg",
            "VID_20230715_142311.mp4",
            "MVIMG_20230715_142311.jpg",
            "PANO_20230715_142311.jpg",
            "IMG20230715142311.jpg",
            "BURST20230715142311_COVER.jpg",
            "Screenshot_20230715_142311_Instagram.jpg",
            "Screenshot_2023-07-15-14-23-11-123_com.whatsapp.png",
            "photo_2023-07-15_14-23-11.jpg",
            "signal-2023-07-15-14-23-11-456.jpg",
            "signal-2023-07-15-142311_002.jpeg",
            "2023-07-15 14.23.11.jpg",
            "WhatsApp Image 2023-07-15 at 14.23.11.jpeg",
            "WhatsApp Video 2023-07-15 at 2.23.11 PM (1).mp4"
        )
        for (name in samples) {
            val parsed = MediaDateResolver.parseFilenameTimestamp(name, zone, nowMs)
            assertNotNull("Expected to parse $name", parsed)
            assertTrue("Expected time for $name", parsed!!.hasTime)
            assertEquals("Wrong timestamp for $name", expected, parsed.epochMs)
        }
    }

    @Test
    fun pixelFilename_isParsedAsUtc() {
        val parsed = MediaDateResolver.parseFilenameTimestamp("PXL_20230715_122311123.MP.jpg", zone, nowMs)
        assertNotNull(parsed)
        val expectedUtc = LocalDateTime.of(2023, 7, 15, 12, 23, 11).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(expectedUtc, parsed!!.epochMs)
    }

    @Test
    fun whatsappMobileFilenames_preserveDayAndSequenceOrder() {
        val wa1 = MediaDateResolver.parseFilenameTimestamp("IMG-20220814-WA0001.jpg", zone, nowMs)
        val wa12 = MediaDateResolver.parseFilenameTimestamp("IMG-20220814-WA0012.jpg", zone, nowMs)
        assertNotNull(wa1)
        assertNotNull(wa12)
        assertFalse(wa1!!.hasTime)
        assertEquals(LocalDate.of(2022, 8, 14), Instant.ofEpochMilli(wa1.epochMs).atZone(zone).toLocalDate())
        assertTrue("WA0012 should sort after WA0001", wa12!!.epochMs > wa1.epochMs)
    }

    @Test
    fun knownUnixMsPrefixes_parseWithoutFalsePositivesOnSnapchatOrRandomIds() {
        val fb = MediaDateResolver.parseFilenameTimestamp("FB_IMG_1689430991234.jpg", zone, nowMs)
        assertNotNull(fb)
        assertEquals(1689430991234L, fb!!.epochMs)

        assertNull(MediaDateResolver.parseFilenameTimestamp("Snapchat-1689430991234.jpg", zone, nowMs))
        assertNull(MediaDateResolver.parseFilenameTimestamp("received_168943099123456.jpeg", zone, nowMs))
        assertNull(MediaDateResolver.parseFilenameTimestamp("DSC_0412.JPG", zone, nowMs))
        assertNull(MediaDateResolver.parseFilenameTimestamp("19201080.jpg", zone, nowMs))
        assertNull(MediaDateResolver.parseFilenameTimestamp("20240230_120000.jpg", zone, nowMs))
        assertNull(MediaDateResolver.parseFilenameTimestamp("98765202307151234.jpg", zone, nowMs))
        assertNull(MediaDateResolver.parseFilenameTimestamp("IMG_20990101_120000.jpg", zone, nowMs))
    }

    @Test
    fun transferredPhotoWithoutDateTaken_recoversDateFromFilename() {
        val res = MediaDateResolver.resolveEffectiveTimestamp(
            fileName = "IMG-20220814-WA0012.jpg",
            mediaStoreDateTakenMs = 0L,
            fileModifiedMs = transferDayMs,
            zone = zone,
            nowEpochMs = nowMs
        )
        assertTrue(res.correctedFromFilename)
        assertEquals(LocalDate.of(2022, 8, 14), Instant.ofEpochMilli(res.epochMs).atZone(zone).toLocalDate())
    }

    @Test
    fun transferredPhotoWithCorruptedDateTaken_overridesTransferDate() {
        val res = MediaDateResolver.resolveEffectiveTimestamp(
            fileName = "20230715_142311.jpg",
            mediaStoreDateTakenMs = transferDayMs,
            fileModifiedMs = transferDayMs,
            zone = zone,
            nowEpochMs = nowMs
        )
        assertTrue(res.correctedFromFilename)
        assertEquals(localEpoch(2023, 7, 15, 14, 23, 11), res.epochMs)
    }

    @Test
    fun validExifDateTaken_isPreservedEvenWhenFilenameIsNewerOrSameDay() {
        // Same day within timezone tolerance -> keep MediaStore DATE_TAKEN
        val realTaken = localEpoch(2023, 7, 15, 14, 23, 15)
        val sameDay = MediaDateResolver.resolveEffectiveTimestamp(
            fileName = "IMG_20230715_142311.jpg",
            mediaStoreDateTakenMs = realTaken,
            fileModifiedMs = transferDayMs,
            zone = zone,
            nowEpochMs = nowMs
        )
        assertFalse(sameDay.correctedFromFilename)
        assertEquals(realTaken, sameDay.epochMs)

        // Edited/exported photo with older real EXIF DATE_TAKEN -> keep real EXIF DATE_TAKEN
        val olderExif = localEpoch(2020, 5, 1, 10, 0, 0)
        val exported = MediaDateResolver.resolveEffectiveTimestamp(
            fileName = "Edited_20240812_180000.jpg",
            mediaStoreDateTakenMs = olderExif,
            fileModifiedMs = transferDayMs,
            zone = zone,
            nowEpochMs = nowMs
        )
        assertFalse(exported.correctedFromFilename)
        assertEquals(olderExif, exported.epochMs)
    }

    @Test
    fun whatsappSameDayFileModified_keepsExactTimeOfDay() {
        val sameDayEvening = localEpoch(2022, 8, 14, 21, 15, 0)
        val res = MediaDateResolver.resolveEffectiveTimestamp(
            fileName = "IMG-20220814-WA0012.jpg",
            mediaStoreDateTakenMs = 0L,
            fileModifiedMs = sameDayEvening,
            zone = zone,
            nowEpochMs = nowMs
        )
        assertFalse(res.correctedFromFilename)
        assertEquals(sameDayEvening, res.epochMs)
    }
}
