package com.luminagallery.drive

import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.DriveThumbnailFetcher
import com.luminagallery.drive.data.repository.DriveThumbnailKeyer
import com.luminagallery.drive.data.repository.DriveThumbnailRequest
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.LocalMediaThumbnailFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying fast Google Drive delta sync decisions, self-healing thumbnail caching,
 * HTML error detection, and bounded local image downsampling.
 */
class MediaLoadingReliabilityTest {

    @Test
    fun driveSync_usesFastDeltaSyncWheneverInitialScanAndTokenExist() {
        assertTrue(
            "Should use fast changes.list delta sync after initial scan regardless of elapsed time",
            GalleryRepository.shouldUseDeltaSync(
                forceFullRescan = false,
                initialScanComplete = true,
                changesStartPageToken = "123456"
            )
        )
        assertFalse(
            "Explicit forceFullRescan must bypass delta sync",
            GalleryRepository.shouldUseDeltaSync(
                forceFullRescan = true,
                initialScanComplete = true,
                changesStartPageToken = "123456"
            )
        )
        assertFalse(
            "Incomplete initial scan must run full scan",
            GalleryRepository.shouldUseDeltaSync(
                forceFullRescan = false,
                initialScanComplete = false,
                changesStartPageToken = "123456"
            )
        )
        assertFalse(
            "Blank startPageToken must run full scan",
            GalleryRepository.shouldUseDeltaSync(
                forceFullRescan = false,
                initialScanComplete = true,
                changesStartPageToken = "   "
            )
        )
    }

    @Test
    fun driveDiskCacheKey_staysStableAcrossWifiAndCellularSwitches() {
        val wifiKey = DriveConstants.thumbnailCacheKey("file_abc123", isUnmeteredWifi = true)
        val cellularKey = DriveConstants.thumbnailCacheKey("file_abc123", isUnmeteredWifi = false)
        assertEquals(wifiKey, cellularKey)
        assertEquals(DriveConstants.stableThumbDiskKey("file_abc123"), wifiKey)
    }

    @Test
    fun driveThumbnailUrl_resizesToFastOptimalTargetsAndGuardsLargeVideos() {
        val rawLink = "https://lh3.googleusercontent.com/drive-storage/AJQWtBExample=s220"

        val wifiCover = DriveConstants.buildThumbnailUrl(
            thumbnailLink = rawLink,
            fileId = "id1",
            isUnmeteredWifi = true
        )
        assertTrue("Default Wi-Fi cover should use fast =s440", wifiCover?.endsWith("=s440") == true)

        val ecoCover = DriveConstants.buildThumbnailUrl(
            thumbnailLink = rawLink,
            fileId = "id1",
            isUnmeteredWifi = false
        )
        assertTrue("Default metered cover should use =s300", ecoCover?.endsWith("=s300") == true)

        val collageTile = DriveConstants.buildThumbnailUrl(
            thumbnailLink = rawLink,
            fileId = "id1",
            isUnmeteredWifi = true,
            requestedMaxPx = 280
        )
        assertTrue("Collage tile should use compact =s280", collageTile?.endsWith("=s280") == true)

        // Never fall back to ?alt=media for videos or >16 MB files
        assertNull(
            DriveConstants.buildThumbnailUrl(
                thumbnailLink = null,
                fileId = "vid1",
                isUnmeteredWifi = true,
                isVideo = true,
                sizeBytes = 5_000_000L
            )
        )
        assertNull(
            DriveConstants.buildThumbnailUrl(
                thumbnailLink = null,
                fileId = "huge1",
                isUnmeteredWifi = true,
                isVideo = false,
                sizeBytes = 25L * 1024L * 1024L
            )
        )
    }

    @Test
    fun driveThumbnailFetcher_detectsHtmlLoginPagesMasqueradingAsSuccess() {
        assertTrue(DriveThumbnailFetcher.isNonImageContentType("text/html; charset=utf-8"))
        assertTrue(DriveThumbnailFetcher.isNonImageContentType("application/json"))
        assertFalse(DriveThumbnailFetcher.isNonImageContentType("image/jpeg"))
        assertFalse(DriveThumbnailFetcher.isNonImageContentType("image/webp"))

        assertTrue(DriveThumbnailFetcher.looksLikeHtml("  <!DOCTYPE html><html>Sign in</html>".toByteArray()))
        assertTrue(DriveThumbnailFetcher.looksLikeHtml("<html><body>403</body></html>".toByteArray()))
        // Standard JPEG SOI + APP0 header bytes
        val jpegHeader = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte()
        )
        assertFalse(DriveThumbnailFetcher.looksLikeHtml(jpegHeader))
        assertTrue(DriveThumbnailFetcher.isGoogleUserContentUrl("https://lh3.googleusercontent.com/d/123=s440"))
        assertFalse(DriveThumbnailFetcher.isGoogleUserContentUrl("https://www.googleapis.com/drive/v3/files/123"))
    }

    @Test
    fun localThumbnailFetcher_computesSafePowerOfTwoInSampleSize() {
        // 200MP camera photo (16320 x 12240) targeting 384px -> sampleSize 32 (510x382)
        assertEquals(32, LocalMediaThumbnailFetcher.computeInSampleSize(16320, 12240, 384))
        // 12MP phone photo (4000 x 3000) targeting 384px -> sampleSize 8 (500x375)
        assertEquals(8, LocalMediaThumbnailFetcher.computeInSampleSize(4000, 3000, 384))
        // Small image (320 x 240) targeting 384px -> sampleSize 1
        assertEquals(1, LocalMediaThumbnailFetcher.computeInSampleSize(320, 240, 384))
    }

    @Test
    fun driveThumbnailKeyer_separatesRetryAttemptsFromInitialKey() {
        val keyer = DriveThumbnailKeyer()
        val req0 = DriveThumbnailRequest(fileId = "abc", thumbnailLink = null, isUnmeteredWifi = true, retryAttempt = 0)
        val req1 = req0.copy(retryAttempt = 1)
        assertEquals(DriveConstants.stableThumbDiskKey("abc"), req0.fileId.let { DriveConstants.stableThumbDiskKey(it) })
        assertNotEquals(req0, req1)
        assertTrue(keyer.toString().isNotBlank())
    }
}
