package com.luminagallery.drive

import androidx.compose.ui.geometry.Rect
import com.luminagallery.drive.data.repository.CloudAlbumCollection
import com.luminagallery.drive.data.repository.CloudAlbumLayout
import com.luminagallery.drive.data.repository.CloudAlbumViewPref
import com.luminagallery.drive.data.repository.CloudCustomAlbumCover
import com.luminagallery.drive.data.repository.CloudMediaAnnotation
import com.luminagallery.drive.data.repository.CloudPinnedRoot
import com.luminagallery.drive.data.repository.CloudPreferencesPayload
import com.luminagallery.drive.data.repository.CloudProfileSnapshot
import com.luminagallery.drive.data.repository.CloudProfileSyncManager
import com.luminagallery.drive.data.repository.CollageLayoutMath
import com.luminagallery.drive.data.repository.CollageLayoutMode
import com.luminagallery.drive.data.repository.LocalAlbumCloudMatcher
import com.luminagallery.drive.data.repository.MediaExportManager
import com.luminagallery.drive.data.repository.OcrIndexerManager
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.ui.components.AlbumTransitionOriginTracker
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.screens.editor.computeStraightenFillScale
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun localAlbumCloudMatcher_matchesAcrossPhonesByIdPathAndUniqueName() {
        fun localFolder(id: String, name: String, relPath: String) = DriveFolder(
            id = id,
            parentId = null,
            name = name,
            subfolderCount = 0,
            mediaCount = 12,
            coverThumbnailLink = null,
            coverItemId = null,
            modifiedTimeEpochMs = 1_000L,
            isPinned = false,
            isLocal = true,
            relativePath = relPath
        )

        val newPhoneAlbums = listOf(
            localFolder("local_bucket_100", "Camera", "DCIM/Camera"),
            localFolder("local_bucket_200", "Vacations", "DCIM/Vacations"),
            localFolder("local_bucket_300", "Screenshots", "Pictures/Screenshots"),
            localFolder("local_bucket_400", "Work", "DCIM/Work"),
            localFolder("local_bucket_500", "Work", "Download/Work")
        )

        // 1. Exact bucket ID match
        assertEquals(
            "local_bucket_100",
            LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = "local_bucket_100",
                savedRelativePath = "DCIM/Camera",
                savedFolderName = "Camera",
                localAlbums = newPhoneAlbums
            )
        )

        // 2. Different bucket ID on new phone, matched by normalized DCIM relativePath (case-insensitive)
        assertEquals(
            "local_bucket_200",
            LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = "local_bucket_old_999",
                savedRelativePath = "dcim/Vacations/",
                savedFolderName = "Vacations",
                localAlbums = newPhoneAlbums
            )
        )

        // 3. Cross-OEM folder move (Samsung DCIM/Screenshots -> Xiaomi Pictures/Screenshots) via unique name
        assertEquals(
            "local_bucket_300",
            LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = "local_bucket_samsung_777",
                savedRelativePath = "DCIM/Screenshots",
                savedFolderName = "Screenshots",
                localAlbums = newPhoneAlbums
            )
        )

        // 4. Disambiguates duplicate folder names using relativePath
        assertEquals(
            "local_bucket_500",
            LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = "local_bucket_old_work",
                savedRelativePath = "Download/Work",
                savedFolderName = "Work",
                localAlbums = newPhoneAlbums
            )
        )

        // 5. Ambiguous folder name with non-matching path returns null rather than guessing wrong album
        assertNull(
            LocalAlbumCloudMatcher.resolveTargetBucketId(
                savedBucketId = "local_bucket_other_work",
                savedRelativePath = "Pictures/Work",
                savedFolderName = "Work",
                localAlbums = newPhoneAlbums
            )
        )
    }

    @Test
    fun cloudProfileSnapshot_serializesAndDeserializesRoundTrip() {
        val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val original = CloudProfileSnapshot(
            updatedAtEpochMs = 1_760_000_000_000L,
            preferences = CloudPreferencesPayload(
                gridColumns = 3,
                startVideosMuted = true,
                themeMode = "DARK"
            ),
            pinnedDriveRoots = listOf(
                CloudPinnedRoot(
                    folderId = "drive_folder_abc",
                    displayName = "Japan Trip",
                    pinnedAtEpochMs = 1_750_000_000_000L,
                    sortOrder = 0
                )
            ),
            collections = listOf(
                CloudAlbumCollection(
                    id = "collection_travel",
                    name = "Travel",
                    parentCollectionId = null,
                    createdAtEpochMs = 1_750_000_000_000L
                )
            ),
            layouts = listOf(
                CloudAlbumLayout(
                    itemId = "local_bucket_42",
                    itemKind = AlbumGridItem.KIND_LOCAL,
                    parentCollectionId = "collection_travel",
                    sortOrder = 1,
                    localRelativePath = "DCIM/Vacations",
                    localFolderName = "Vacations"
                ),
                CloudAlbumLayout(
                    itemId = "drive_folder_abc",
                    itemKind = AlbumGridItem.KIND_DRIVE,
                    parentCollectionId = "collection_travel",
                    sortOrder = 2
                )
            ),
            driveFavoriteItemIds = listOf("drive_photo_1", "drive_photo_2")
        )

        val encoded = codec.encodeToString(original)
        val decoded = codec.decodeFromString<CloudProfileSnapshot>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun cloudProfileSnapshot_contentFingerprintIgnoresTimestampsAndOrderButDetectsRealChanges() {
        val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val base = CloudProfileSnapshot(
            updatedAtEpochMs = 1_000L,
            preferences = CloudPreferencesPayload(gridColumns = 4),
            pinnedDriveRoots = listOf(
                CloudPinnedRoot("drive_b", "Trips", pinnedAtEpochMs = 100L, sortOrder = 1),
                CloudPinnedRoot("drive_a", "Family", pinnedAtEpochMs = 200L, sortOrder = 0)
            ),
            collections = listOf(
                CloudAlbumCollection("col_1", "Travel", null, createdAtEpochMs = 50L)
            ),
            driveFavoriteItemIds = listOf("f2", "f1")
        )
        // Same logical state with different timestamps & list order
        val equivalent = base.copy(
            updatedAtEpochMs = 9_999L,
            pinnedDriveRoots = base.pinnedDriveRoots.reversed().map { it.copy(pinnedAtEpochMs = 999L) },
            collections = base.collections.map { it.copy(createdAtEpochMs = 888L) },
            driveFavoriteItemIds = listOf("f1", "f2", "f1")
        )
        assertEquals(base.contentFingerprint(codec), equivalent.contentFingerprint(codec))

        // Unpinning a Drive album or changing a setting MUST alter the fingerprint
        val afterUnpin = base.copy(pinnedDriveRoots = base.pinnedDriveRoots.take(1))
        val afterPrefChange = base.copy(preferences = base.preferences.copy(gridColumns = 3))
        assertNotEquals(base.contentFingerprint(codec), afterUnpin.contentFingerprint(codec))
        assertNotEquals(base.contentFingerprint(codec), afterPrefChange.contentFingerprint(codec))
    }

    @Test
    fun cloudProfileSyncManager_distinguishesScopeDenied403FromRateLimit403() {
        val scopeError = """{"error":{"code":403,"message":"Request had insufficient authentication scopes.","status":"PERMISSION_DENIED","details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}"""
        val legacyScopeError = """{"error":{"errors":[{"reason":"insufficientPermissions"}],"code":403}}"""
        val rateLimitError = """{"error":{"errors":[{"reason":"userRateLimitExceeded"}],"code":403,"message":"User rate limit exceeded."}}"""
        val quotaError = """{"error":{"errors":[{"reason":"storageQuotaExceeded"}],"code":403}}"""

        assertTrue(CloudProfileSyncManager.isScopeDeniedErrorBody(scopeError))
        assertTrue(CloudProfileSyncManager.isScopeDeniedErrorBody(legacyScopeError))
        assertFalse(CloudProfileSyncManager.isScopeDeniedErrorBody(rateLimitError))
        assertFalse(CloudProfileSyncManager.isScopeDeniedErrorBody(quotaError))
    }

    @Test
    fun localMediaCloudMatcher_matchesCoversAndAnnotationsAcrossPhones() {
        fun photo(id: String, name: String, path: String, size: Long) = MediaItem(
            id = id,
            parentId = "local_bucket_1",
            name = name,
            mimeType = "image/jpeg",
            mediaType = MediaType.PHOTO,
            sizeBytes = size,
            createdTimeEpochMs = 1_000L,
            monthYearLabel = "Nov 2026",
            fullDateLabel = "Nov 23, 2026",
            thumbnailLink = null,
            width = 4000,
            height = 3000,
            rotation = 0,
            durationMillis = null,
            exif = ExifMetadata(null, null, null, null, null, null, null, null),
            isLocal = true,
            localContentUri = "content://media/external/images/media/$id",
            relativePath = path.substringAfterLast('/'),
            storagePath = path
        )

        val newPhoneMedia = listOf(
            photo("local_901", "IMG_2026.jpg", "DCIM/Vacations", 4_200_000L),
            photo("local_902", "receipt.png", "Pictures/Screenshots", 310_000L)
        )

        val matchedByPath = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
            savedItemId = "local_old_11",
            savedFileName = "img_2026.jpg",
            savedRelativePath = "dcim/vacations/",
            savedSizeBytes = 4_200_000L,
            localMedia = newPhoneMedia
        )
        assertEquals("local_901", matchedByPath?.id)

        val matchedByUniqueNameAndSize = LocalAlbumCloudMatcher.resolveTargetLocalMediaItem(
            savedItemId = "local_old_22",
            savedFileName = "receipt.png",
            savedRelativePath = "DCIM/Screenshots",
            savedSizeBytes = 310_000L,
            localMedia = newPhoneMedia
        )
        assertEquals("local_902", matchedByUniqueNameAndSize?.id)
    }

    @Test
    fun cloudProfileSnapshotV2_preservesCoversAnnotationsViewPrefsAndPrivacyFlags() {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val snap = CloudProfileSnapshot(
            preferences = CloudPreferencesPayload(stripLocationOnShare = true, lockHiddenAlbums = true),
            favoriteStoryIds = listOf("story_trip_1"),
            customAlbumCovers = listOf(
                CloudCustomAlbumCover(
                    albumId = "local_bucket_1",
                    isAlbumLocal = true,
                    albumRelativePath = "DCIM/Vacations",
                    albumFolderName = "Vacations",
                    coverItemId = "local_901",
                    coverIsLocal = true,
                    coverFileName = "IMG_2026.jpg",
                    coverRelativePath = "DCIM/Vacations",
                    coverSizeBytes = 4_200_000L
                )
            ),
            mediaAnnotations = listOf(
                CloudMediaAnnotation(
                    itemId = "drive_photo_1",
                    isLocal = false,
                    tagsCsv = "passport,travel",
                    note = "Renew in 2029"
                )
            ),
            albumViewPrefs = listOf(
                CloudAlbumViewPref(
                    albumId = "local_bucket_1",
                    isLocal = true,
                    localRelativePath = "DCIM/Vacations",
                    localFolderName = "Vacations",
                    sortName = "DATE_ASC",
                    groupByName = "DAY"
                )
            )
        )
        val decoded = codec.decodeFromString<CloudProfileSnapshot>(codec.encodeToString(snap))
        assertEquals(snap, decoded)
        assertEquals(snap.contentFingerprint(codec), decoded.contentFingerprint(codec))
    }

    @Test
    fun collageLayoutMath_straightenScale_ocrNormalize_andExifStripList_areValid() {
        for (mode in CollageLayoutMode.entries) {
            val cells = CollageLayoutMath.computeCells(4, mode, 1000f, 1000f, 20f)
            assertEquals(4, cells.size)
            cells.forEach { c ->
                assertTrue(c.left >= 0f && c.right <= 1000.5f && c.right > c.left)
                assertTrue(c.top >= 0f && c.bottom <= 1000.5f && c.bottom > c.top)
            }
        }
        assertEquals(1f, computeStraightenFillScale(0f, 1600f, 900f), 0.001f)
        assertTrue(computeStraightenFillScale(15f, 1600f, 900f) > 1.2f)
        assertEquals("WiFi Pass 1234", OcrIndexerManager.normalizeSearchText("  WiFi\n\tPass   1234  "))
        assertTrue(MediaExportManager.SENSITIVE_EXIF_TAGS.contains("GPSLatitude"))
        assertTrue(MediaExportManager.SENSITIVE_EXIF_TAGS.contains("GPSLongitude"))
    }
}
