package com.luminagallery.drive

import com.luminagallery.drive.ui.components.AlbumTransitionOriginTracker
import com.luminagallery.drive.ui.components.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class GalleryCoreLogicTest {

    @Test
    fun albumTransitionOriginTracker_clampsExtremesSafely() {
        val tracker = AlbumTransitionOriginTracker()
        tracker.record("album_edge", -2.5f, 3.8f)
        val origin = tracker.originFor("album_edge")
        assertTrue(origin.pivotFractionX in 0.12f..0.88f)
        assertTrue(origin.pivotFractionY in 0.12f..0.88f)
    }

    @Test
    fun formatDurationPadded_handlesZeroAndLongVideos() {
        assertEquals("00:00", FormatUtils.formatDurationPadded(0L))
        assertEquals("00:05", FormatUtils.formatDurationPadded(5_000L))
        assertEquals("01:23", FormatUtils.formatDurationPadded(83_000L))
        assertEquals("1:01:05", FormatUtils.formatDurationPadded(3_665_000L))
    }

    @Test
    fun formatFileSize_formatsBytesKbMbGb() {
        assertEquals("0 B", FormatUtils.formatFileSize(0L))
        assertEquals("512 B", FormatUtils.formatFileSize(512L))
        assertTrue(FormatUtils.formatFileSize(2_048L).contains("KB"))
        assertTrue(FormatUtils.formatFileSize(5L * 1024L * 1024L).contains("MB"))
    }

    @Test
    fun gitBlobSha1Formula_matchesStandardGitHashObject() {
        // Known git hash-object value for "hello world\n" is 3b18e512dba79e4c8300dd08aeb37f8e728b8dad
        val payload = "hello world\n".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${payload.size}\u0000".toByteArray(Charsets.US_ASCII))
        digest.update(payload)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", hex)
    }
}
