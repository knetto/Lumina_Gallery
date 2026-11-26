package com.luminagallery.drive

import android.media.MediaCodec
import android.media.MediaExtractor
import androidx.compose.ui.geometry.Rect
import com.google.android.gms.common.api.CommonStatusCodes
import com.luminagallery.drive.data.local.HiddenLockedFolderManager
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.remote.DriveScopes
import com.luminagallery.drive.data.remote.auth.GoogleAuthHelper
import com.luminagallery.drive.data.remote.dto.DriveFileCapabilitiesDto
import com.luminagallery.drive.data.remote.dto.DriveFileDto
import com.luminagallery.drive.data.repository.CloudAlbumCollection
import com.luminagallery.drive.data.repository.CloudAlbumLayout
import com.luminagallery.drive.data.repository.CloudAlbumViewPref
import com.luminagallery.drive.data.repository.CloudCustomAlbumCover
import com.luminagallery.drive.data.repository.CloudHiddenAlbum
import com.luminagallery.drive.data.repository.CloudMediaAnnotation
import com.luminagallery.drive.data.repository.CloudPersonFaceAnchor
import com.luminagallery.drive.data.repository.CloudPinnedRoot
import com.luminagallery.drive.data.repository.CloudPreferencesPayload
import com.luminagallery.drive.data.repository.CloudProfileSnapshot
import com.luminagallery.drive.data.repository.CloudProfileSyncManager
import com.luminagallery.drive.data.repository.CloudRejectedPersonPair
import com.luminagallery.drive.data.repository.CloudSavedPerson
import com.luminagallery.drive.data.repository.CollageLayoutMath
import com.luminagallery.drive.data.repository.CollageLayoutMode
import com.luminagallery.drive.data.repository.DriveMapper
import com.luminagallery.drive.data.repository.LocalAlbumCloudMatcher
import com.luminagallery.drive.data.repository.LocalFileOperationsManager
import com.luminagallery.drive.data.repository.MediaExportManager
import com.luminagallery.drive.data.repository.OcrIndexerManager
import com.luminagallery.drive.data.repository.VideoFormatSupport
import com.luminagallery.drive.data.repository.VideoTrimmer
import com.luminagallery.drive.domain.model.AlbumGridItem
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.ui.components.AlbumTransitionOriginTracker
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.SecureWindowCounter
import com.luminagallery.drive.ui.screens.editor.computeStraightenFillScale
import com.luminagallery.drive.ui.screens.viewer.shouldDismissViewer
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
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class GalleryCoreLogicTest {

    @Test
    fun viewerDismissDistinguishesShortDragsFlingsAndReversals() {
        assertFalse(shouldDismissViewer(50f, 0f, 700f))
        assertTrue(shouldDismissViewer(120f, 0f, 700f))
        assertTrue(shouldDismissViewer(35f, 1_000f, 700f))
        assertFalse(shouldDismissViewer(12f, 1_000f, 700f))
        assertFalse(shouldDismissViewer(120f, -1_000f, 700f))
    }

    @Test
    fun driveMapper_identifiesComputerRootsFromFolderMetadata() {
        val computer = Json.decodeFromString<DriveFileDto>(
            """
            {
              "id": "computer-id",
              "name": "Laptop",
              "mimeType": "application/vnd.google-apps.folder",
              "capabilities": {"canShare": false}
            }
            """.trimIndent()
        )
        val myDriveRootId = "my-drive-id"

        assertTrue(DriveMapper.isComputerRoot(computer, myDriveRootId))
        assertFalse(DriveMapper.isComputerRoot(computer.copy(id = myDriveRootId), myDriveRootId))
        assertFalse(DriveMapper.isComputerRoot(computer.copy(parents = listOf(myDriveRootId)), myDriveRootId))
        assertFalse(DriveMapper.isComputerRoot(computer.copy(capabilities = null), myDriveRootId))
        assertFalse(
            DriveMapper.isComputerRoot(
                computer.copy(capabilities = DriveFileCapabilitiesDto(canShare = true)),
                myDriveRootId
            )
        )
    }

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
    fun cameraExifFormatters_formatSettingsAndCleanRedundantPrefixes() {
        // Aperture drops trailing .0 on whole stops while preserving fractional stops
        assertEquals("f/5.6", FormatUtils.formatAperture(5.6f))
        assertEquals("f/1.8", FormatUtils.formatAperture(1.78f))
        assertEquals("f/8", FormatUtils.formatAperture(8.0f))
        assertEquals("f/11", FormatUtils.formatAperture(11.0f))
        assertNull(FormatUtils.formatAperture(0f))

        // Shutter speed handles sub-second decimals cleanly without "1/1s" glitches
        assertEquals("1/420s", FormatUtils.formatShutterSpeed(1f / 420f))
        assertEquals("1/2s", FormatUtils.formatShutterSpeed(0.5f))
        assertEquals("0.6s", FormatUtils.formatShutterSpeed(0.6f))
        assertEquals("0.8s", FormatUtils.formatShutterSpeed(0.8f))
        assertEquals("2s", FormatUtils.formatShutterSpeed(2.0f))
        assertEquals("1.6s", FormatUtils.formatShutterSpeed(1.6f))

        // Focal length keeps precision for smartphone short lenses and rounds camera lenses cleanly
        assertEquals("278 mm", FormatUtils.formatFocalLength(278.0f))
        assertEquals("6.9 mm", FormatUtils.formatFocalLength(6.86f))
        assertEquals("4 mm", FormatUtils.formatFocalLength(4.0f))

        // Lens name strips redundant camera brand/model prefix while preserving third-party brands
        assertEquals(
            "XF70-300mmF4-5.6 R LM OIS WR",
            FormatUtils.formatLensName("FUJIFILM XF70-300mmF4-5.6 R LM OIS WR", "FUJIFILM", "X-T5")
        )
        assertEquals(
            "SIGMA 18-50mm F2.8 DC DN",
            FormatUtils.formatLensName("SIGMA 18-50mm F2.8 DC DN", "FUJIFILM", "X-T5")
        )
        assertEquals(
            "Back triple camera 6.765mm f/1.78",
            FormatUtils.formatLensName("iPhone 15 Pro back triple camera 6.765mm f/1.78", "Apple", "iPhone 15 Pro")
        )

        // Shooting mode decoding favors meaningful camera modes and suppresses generic Auto
        assertEquals("Aperture priority", FormatUtils.decodeExifExposureMode(programRaw = 3, modeRaw = 0))
        assertEquals("Manual", FormatUtils.decodeExifExposureMode(programRaw = 1, modeRaw = 1))
        assertEquals("Shutter priority", FormatUtils.decodeExifExposureMode(programRaw = 4, modeRaw = 0))
        assertEquals("Auto bracket", FormatUtils.decodeExifExposureMode(programRaw = 3, modeRaw = 2))
        assertNull(FormatUtils.decodeExifExposureMode(programRaw = 0, modeRaw = 0))

        // Folder path strips redundant storage origin prefixes
        assertEquals("Duitsland-2026", FormatUtils.formatDisplayFolderPath("On Device / Duitsland-2026"))
        assertEquals("Trips / Berlin", FormatUtils.formatDisplayFolderPath("Google Drive / Trips / Berlin"))
        assertEquals("Google Drive", FormatUtils.formatDisplayFolderPath("Google Drive"))

        // Flash formatter cleans double-dot EXIF descriptions and hides Flash Off
        assertEquals("Fired (auto)", FormatUtils.formatFlashSetting("Auto · Fired"))
        assertEquals("Fired (forced)", FormatUtils.formatFlashSetting("Forced · Fired"))
        assertEquals("Fired", FormatUtils.formatFlashSetting("Fired"))
        assertNull(FormatUtils.formatFlashSetting("Off"))
        assertNull(FormatUtils.formatFlashSetting("Auto · Off"))

        // Storage copy path builder shows exact local or Drive path
        assertEquals(
            "/storage/emulated/0/DCIM/Duitsland-2026/DSCF8066.JPG",
            FormatUtils.buildMediaCopyPath(
                isLocal = true,
                storagePath = "DCIM/Duitsland-2026",
                driveFolderPath = "On Device / Duitsland-2026",
                fileName = "DSCF8066.JPG"
            )
        )
        assertEquals(
            "Google Drive / Trips / IMG_1.JPG",
            FormatUtils.buildMediaCopyPath(
                isLocal = false,
                storagePath = null,
                driveFolderPath = "Google Drive / Trips",
                fileName = "IMG_1.JPG"
            )
        )
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
            lockedAlbums = listOf(
                CloudHiddenAlbum(
                    albumId = "local_bucket_private",
                    isLocal = true,
                    localRelativePath = "DCIM/Private",
                    localFolderName = "Private"
                )
            ),
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

    @Test
    fun googleAuthHelper_classifiesScopesAndApiErrorsCleanly() {
        assertTrue(
            GoogleAuthHelper.hasGrantedAppDataScope(
                listOf(DriveScopes.DRIVE_READONLY, DriveScopes.DRIVE_APPDATA)
            )
        )
        assertFalse(GoogleAuthHelper.hasGrantedAppDataScope(listOf(DriveScopes.DRIVE_READONLY)))
        assertFalse(GoogleAuthHelper.hasGrantedAppDataScope(emptyList()))

        val devErr = GoogleAuthHelper.formatAuthErrorFromParts(
            statusCode = CommonStatusCodes.DEVELOPER_ERROR,
            rawMessage = "10: "
        )
        assertTrue(devErr.contains("com.luminagallery.drive"))
        assertTrue(devErr.contains("B3:74:4C:48"))

        val scopeUpgradeErr = GoogleAuthHelper.formatAuthErrorFromParts(
            statusCode = 28444,
            rawMessage = "28444: Developer console is not set up correctly.",
            appDataUpgradeOnly = true
        )
        assertTrue(scopeUpgradeErr.contains("drive.appdata"))

        val apiErr17 = GoogleAuthHelper.formatAuthErrorFromParts(
            statusCode = CommonStatusCodes.API_NOT_CONNECTED,
            rawMessage = "17: API unavailable"
        )
        assertTrue(apiErr17.contains("17"))
    }

    @Test
    fun hiddenLockedFolderManager_protectsSystemRootsAndAllowsAlbumFolders() {
        val root = "/storage/emulated/0"
        assertFalse(HiddenLockedFolderManager.isSafeFolderPathToHide("/storage/emulated/0", root))
        assertFalse(HiddenLockedFolderManager.isSafeFolderPathToHide("/storage/emulated/0/DCIM", root))
        assertFalse(HiddenLockedFolderManager.isSafeFolderPathToHide("/storage/emulated/0/Pictures", root))
        assertFalse(HiddenLockedFolderManager.isSafeFolderPathToHide("/storage/emulated/0/Android/media", root))
        assertTrue(HiddenLockedFolderManager.isSafeFolderPathToHide("/storage/emulated/0/DCIM/Private", root))
        assertTrue(HiddenLockedFolderManager.isSafeFolderPathToHide("/storage/emulated/0/Pictures/Secret", root))
        assertTrue(HiddenLockedFolderManager.isHiddenFileUri("file:///storage/emulated/0/DCIM/.Private/IMG_1.jpg"))
        assertFalse(HiddenLockedFolderManager.isHiddenFileUri("content://media/external/images/media/42"))
    }

    @Test
    fun localMediaRepository_hashStreamSha256_hashesFullStreamNotJustPrefix() {
        val sharedPrefix = ByteArray(20 * 1024) { (it % 251).toByte() }
        val streamA = sharedPrefix + "FRAME_A_END".toByteArray()
        val streamB = sharedPrefix + "FRAME_B_END".toByteArray()
        val streamACopy = sharedPrefix + "FRAME_A_END".toByteArray()

        val hashA = LocalMediaRepository.hashStreamSha256(ByteArrayInputStream(streamA))
        val hashB = LocalMediaRepository.hashStreamSha256(ByteArrayInputStream(streamB))
        val hashACopy = LocalMediaRepository.hashStreamSha256(ByteArrayInputStream(streamACopy))

        assertNotNull(hashA)
        assertNotNull(hashB)
        assertEquals(64, hashA!!.length)
        assertEquals(hashA, hashACopy)
        assertNotEquals("Files sharing >16KB header must not collide", hashA, hashB)
    }

    @Test
    fun hiddenLockedFolderManager_mergeDirectoryContents_preservesNestedSubdirsAndCollisions() {
        val tempRoot = Files.createTempDirectory("lumina_merge_test").toFile()
        try {
            val hiddenSrc = File(tempRoot, ".Secret").apply { mkdirs() }
            val destDir = File(tempRoot, "Secret").apply { mkdirs() }

            File(hiddenSrc, ".nomedia").writeText("")
            File(hiddenSrc, ".lumina_locked").writeText("{}")
            File(hiddenSrc, "photo.jpg").writeText("hidden_photo")
            val nestedSrc = File(hiddenSrc, "nested/deeper").apply { mkdirs() }
            File(nestedSrc, "clip.mp4").writeText("nested_video")

            // Pre-existing conflicting file & folder in destination
            File(destDir, "photo.jpg").writeText("existing_photo")
            File(destDir, "nested").mkdirs()
            File(destDir, "nested/keep.jpg").writeText("keep_me")

            HiddenLockedFolderManager.mergeDirectoryContents(hiddenSrc, destDir)

            assertEquals("existing_photo", File(destDir, "photo.jpg").readText())
            assertEquals("hidden_photo", File(destDir, "photo_1.jpg").readText())
            assertEquals("keep_me", File(destDir, "nested/keep.jpg").readText())
            assertEquals("nested_video", File(destDir, "nested/deeper/clip.mp4").readText())
            assertFalse(File(destDir, ".nomedia").exists())
            assertFalse(File(destDir, ".lumina_locked").exists())
            assertTrue(HiddenLockedFolderManager.isDirectoryEffectivelyEmpty(hiddenSrc))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun secureWindowCounter_refCountsNestedProtectedScreens() {
        val fakeWindow = Any()
        assertEquals(0, SecureWindowCounter.currentCount(fakeWindow))
        assertEquals(1, SecureWindowCounter.acquire(fakeWindow))
        assertEquals(2, SecureWindowCounter.acquire(fakeWindow))
        // Closing child viewer while locked album stays underneath must keep count > 0
        assertEquals(1, SecureWindowCounter.release(fakeWindow))
        assertEquals(1, SecureWindowCounter.currentCount(fakeWindow))
        // Closing locked album drops count cleanly to 0 (and extra releases never go negative)
        assertEquals(0, SecureWindowCounter.release(fakeWindow))
        assertEquals(0, SecureWindowCounter.release(fakeWindow))
    }

    @Test
    fun videoTrimmer_mapExtractorFlagsToMuxerFlags_avoidsEosCollision() {
        assertEquals(
            MediaCodec.BUFFER_FLAG_KEY_FRAME,
            VideoTrimmer.mapExtractorFlagsToMuxerFlags(MediaExtractor.SAMPLE_FLAG_SYNC)
        )
        val partialMapped = VideoTrimmer.mapExtractorFlagsToMuxerFlags(MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME)
        assertEquals(0, partialMapped and MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        assertEquals(0, partialMapped)

        val combinedMapped = VideoTrimmer.mapExtractorFlagsToMuxerFlags(
            MediaExtractor.SAMPLE_FLAG_SYNC or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
        )
        assertEquals(MediaCodec.BUFFER_FLAG_KEY_FRAME, combinedMapped)
    }

    @Test
    fun cloudProfileSyncManager_mergeSnapshots_preservesBothLocalAndRemoteAdditions() {
        val local = CloudProfileSnapshot(
            updatedAtEpochMs = 2_000L,
            preferences = CloudPreferencesPayload(gridColumns = 3),
            pinnedDriveRoots = listOf(CloudPinnedRoot("drive_local", "Local Pin", 100L, 0)),
            collections = listOf(CloudAlbumCollection("col_local", "Local Col", null, 100L)),
            driveFavoriteItemIds = listOf("fav_local"),
            mediaAnnotations = listOf(
                CloudMediaAnnotation(itemId = "item_1", isLocal = false, tagsCsv = "local_tag", note = "local note")
            )
        )
        val remote = CloudProfileSnapshot(
            updatedAtEpochMs = 2_500L,
            preferences = CloudPreferencesPayload(gridColumns = 5),
            pinnedDriveRoots = listOf(CloudPinnedRoot("drive_remote", "Remote Pin", 200L, 1)),
            collections = listOf(CloudAlbumCollection("col_remote", "Remote Col", null, 200L)),
            driveFavoriteItemIds = listOf("fav_remote"),
            mediaAnnotations = listOf(
                CloudMediaAnnotation(itemId = "item_1", isLocal = false, tagsCsv = "remote_tag", note = "remote note"),
                CloudMediaAnnotation(itemId = "item_2", isLocal = false, tagsCsv = "other_tag", note = "other note")
            )
        )

        val preferLocal = CloudProfileSyncManager.mergeSnapshots(local, remote, preferLocalConflicts = true)
        assertEquals(3, preferLocal.preferences.gridColumns)
        assertEquals(setOf("drive_local", "drive_remote"), preferLocal.pinnedDriveRoots.map { it.folderId }.toSet())
        assertEquals(setOf("col_local", "col_remote"), preferLocal.collections.map { it.id }.toSet())
        assertEquals(setOf("fav_local", "fav_remote"), preferLocal.driveFavoriteItemIds.toSet())
        assertEquals("local_tag", preferLocal.mediaAnnotations.first { it.itemId == "item_1" }.tagsCsv)
        assertEquals("other_tag", preferLocal.mediaAnnotations.first { it.itemId == "item_2" }.tagsCsv)

        val preferRemote = CloudProfileSyncManager.mergeSnapshots(local, remote, preferLocalConflicts = false)
        assertEquals(5, preferRemote.preferences.gridColumns)
        assertEquals("remote_tag", preferRemote.mediaAnnotations.first { it.itemId == "item_1" }.tagsCsv)
    }

    @Test
    fun localFileOperationsManager_sanitizeFolderName_stripsTraversalAndIllegalChars() {
        assertEquals("LuminaGallery", LocalFileOperationsManager.sanitizeFolderName(""))
        assertEquals("LuminaGallery", LocalFileOperationsManager.sanitizeFolderName(".."))
        assertEquals("LuminaGallery", LocalFileOperationsManager.sanitizeFolderName("..."))
        assertEquals("Private", LocalFileOperationsManager.sanitizeFolderName(".Private"))
        assertEquals("Trip_2026_Japan", LocalFileOperationsManager.sanitizeFolderName("Trip/2026:Japan"))
    }

    @Test
    fun albumTransitionOriginTracker_boundsTrackedEntriesWithLru() {
        val tracker = AlbumTransitionOriginTracker()
        val rect = Rect(0f, 0f, 100f, 100f)
        for (i in 0 until 320) {
            tracker.recordBounds("media_$i", rect, 1080f, 2400f)
        }
        assertTrue(
            "Tracked bounds must stay bounded at <= ${AlbumTransitionOriginTracker.MAX_TRACKED_ENTRIES}",
            tracker.trackedBoundsCount() <= AlbumTransitionOriginTracker.MAX_TRACKED_ENTRIES
        )
        assertNotNull(tracker.boundsFor("media_319"))
        assertNull(tracker.boundsFor("media_0"))
    }

    @Test
    fun videoFormatSupport_andDriveMapper_handleMp2pVideosEndToEnd() {
        assertTrue(VideoFormatSupport.isMp2pVideo("camcorder_clip.mp2p", "application/octet-stream"))
        assertTrue(VideoFormatSupport.isMp2pVideo("clip.m2p", null))
        assertTrue(VideoFormatSupport.isMp2pVideo("movie.mpg", "video/mpeg"))
        assertTrue(VideoFormatSupport.isMp2pVideo("VTS_01_1.VOB", "application/octet-stream"))
        assertTrue(VideoFormatSupport.isMp2pVideo("stream_no_ext", "video/mp2p"))
        assertFalse(VideoFormatSupport.isMp2pVideo("vacation.mp4", "video/mp4"))

        assertEquals("video/mp2p", VideoFormatSupport.normalizeVideoMimeType("clip.mp2p", "application/octet-stream"))
        assertEquals("video/mp2p", VideoFormatSupport.normalizeVideoMimeType("MOV001.MOD", null))
        assertEquals("video/mp2p", VideoFormatSupport.resolveExoPlayerMimeType("clip.mp2p", "application/octet-stream"))
        assertEquals("video/mp2p", VideoFormatSupport.resolveExoPlayerMimeType("clip.mpg", "video/mpeg"))

        val driveMp2pOctet = DriveFileDto(
            id = "drive_mp2p_1",
            name = "home_video.mp2p",
            mimeType = "application/octet-stream",
            parents = listOf("folder_1")
        )
        assertTrue(DriveMapper.isSupportedGalleryDto(driveMp2pOctet))
        val entity = DriveMapper.dtoToEntity(driveMp2pOctet)
        assertEquals(MediaType.VIDEO.name, entity.mediaType)
        assertEquals("video/mp2p", entity.mimeType)

        val driveMp2pMime = DriveFileDto(
            id = "drive_mp2p_2",
            name = "archive_clip.mpg",
            mimeType = "video/mp2p",
            parents = listOf("folder_1")
        )
        assertTrue(DriveMapper.isSupportedGalleryDto(driveMp2pMime))
        assertEquals(MediaType.VIDEO.name, DriveMapper.dtoToEntity(driveMp2pMime).mediaType)
    }

    @Test
    fun cloudProfileSnapshotV3_roundTripsAndMergesSavedPeopleAndRejections() {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val anchor1 = CloudPersonFaceAnchor(
            itemId = "drive_p1",
            isLocal = false,
            bboxLeft = 0.1f,
            bboxTop = 0.2f,
            bboxRight = 0.4f,
            bboxBottom = 0.5f,
            originGroupId = "g1"
        )
        val anchor2 = CloudPersonFaceAnchor(
            itemId = "local_42",
            isLocal = true,
            localFileName = "IMG_0042.jpg",
            localRelativePath = "DCIM/Camera",
            localSizeBytes = 2_400_000L,
            bboxLeft = 0.2f,
            bboxTop = 0.2f,
            bboxRight = 0.5f,
            bboxBottom = 0.6f,
            originGroupId = "g2"
        )
        val localSnap = CloudProfileSnapshot(
            savedPeople = listOf(
                CloudSavedPerson(
                    memoryId = "mem_alex",
                    name = "Alex",
                    isFavorite = true,
                    isUserMerged = true,
                    coverAnchor = anchor1,
                    anchors = listOf(anchor1)
                )
            ),
            savedPersonRejections = listOf(CloudRejectedPersonPair("mem_alex", "mem_other"))
        )
        val remoteSnap = CloudProfileSnapshot(
            savedPeople = listOf(
                CloudSavedPerson(
                    memoryId = "mem_alex",
                    name = "Alex",
                    isFavorite = false,
                    isUserMerged = true,
                    anchors = listOf(anchor2)
                ),
                CloudSavedPerson(
                    memoryId = "mem_mom",
                    name = "Mom",
                    anchors = listOf(anchor1)
                )
            ),
            savedPersonRejections = listOf(CloudRejectedPersonPair("mem_other", "mem_alex"))
        )

        val decoded = codec.decodeFromString<CloudProfileSnapshot>(codec.encodeToString(localSnap))
        assertEquals(localSnap, decoded)
        assertEquals(localSnap.contentFingerprint(codec), decoded.contentFingerprint(codec))
        assertNotEquals(
            localSnap.contentFingerprint(codec),
            localSnap.copy(savedPeople = listOf(localSnap.savedPeople.first().copy(name = "Alexander")))
                .contentFingerprint(codec)
        )

        val merged = CloudProfileSyncManager.mergeSnapshots(localSnap, remoteSnap, preferLocalConflicts = true)
        assertEquals(setOf("mem_alex", "mem_mom"), merged.savedPeople.map { it.memoryId }.toSet())
        val mergedAlex = merged.savedPeople.first { it.memoryId == "mem_alex" }
        assertTrue(mergedAlex.isFavorite)
        assertEquals(setOf("drive_p1", "local_42"), mergedAlex.anchors.map { it.itemId }.toSet())
        assertEquals(1, merged.savedPersonRejections.size)
    }
}
