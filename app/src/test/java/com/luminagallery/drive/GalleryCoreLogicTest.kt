package com.luminagallery.drive

import androidx.compose.ui.geometry.Rect
import com.luminagallery.drive.ui.components.AlbumTransitionOriginTracker
import com.luminagallery.drive.ui.components.FormatUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun albumTransitionOriginTracker_recordsAndResolvesExactTileBounds() {
        val tracker = AlbumTransitionOriginTracker()
        val rect = Rect(left = 300f, top = 600f, right = 600f, bottom = 900f)
        tracker.recordBounds("media_42", rect, rootWidth = 1080f, rootHeight = 2400f, markAsLast = true)
        val resolved = tracker.boundsFor("media_42")
        assertNotNull(resolved)
        assertEquals(rect, resolved)
        val fallback = tracker.boundsFor("unknown_key", fallbackToLast = true)
        assertEquals(rect, fallback)

        // Ensure per-entry morph gating defaults off and toggles cleanly for open/close vs background push
        assertFalse(tracker.isMorphEnabled("entry_folder_1"))
        tracker.setMorphEnabled("entry_folder_1", true)
        assertTrue(tracker.isMorphEnabled("entry_folder_1"))
        tracker.setMorphEnabled("entry_folder_1", false)
        assertFalse(tracker.isMorphEnabled("entry_folder_1"))
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
