package com.luminagallery.drive.domain.model

import androidx.compose.runtime.Immutable

enum class MediaType {
    PHOTO,
    VIDEO,
    RAW
}

enum class FolderViewMode {
    HIERARCHY,
    FLATTENED
}

enum class ThemeMode {
    AMOLED_BLACK,
    DARK,
    LIGHT,
    SYSTEM
}

enum class CredentialSource {
    GOOGLE_SIGN_IN,
    ENCRYPTED_PREFS,
    BUNDLED_ASSETS
}

@Immutable
sealed interface ServiceAccountState {
    data object NotConfigured : ServiceAccountState

    @Immutable
    data class Configured(
        val clientEmail: String,
        val projectId: String,
        val source: CredentialSource
    ) : ServiceAccountState
}

@Immutable
data class NetworkState(
    val isOnline: Boolean = true,
    val isUnmeteredWifi: Boolean = true
)

enum class MainTab {
    PICTURES,
    ALBUMS,
    STORIES
}

enum class AlbumSortMode(val label: String) {
    CUSTOM("Custom order"),
    NAME_ASC("Name (A–Z)"),
    COUNT_DESC("Most items"),
    DATE_DESC("Recently updated")
}

enum class AlbumsTabFilter(val label: String) {
    ALL("All albums"),
    ON_DEVICE("On device"),
    GOOGLE_DRIVE("Google Drive"),
    COLLECTIONS("Collections")
}

enum class AlbumMediaFilter(val label: String) {
    ALL("All"),
    IMAGES("Images"),
    VIDEOS("Videos"),
    RAW("RAW"),
    FAVORITES("Favorites")
}

enum class AlbumMediaGroupBy(val label: String, val chipLabel: String) {
    NONE("No grouping", "Flat"),
    DAY("Date (Day)", "By day"),
    MONTH("Month", "By month")
}

enum class AlbumMediaSort(val label: String, val chipLabel: String) {
    DATE_DESC("Date created (Newest)", "Newest"),
    DATE_ASC("Date created (Oldest)", "Oldest"),
    NAME_ASC("Name (A–Z)", "A–Z"),
    SIZE_DESC("File size (Largest)", "Largest")
}

@Immutable
data class SidebarAlbumNode(
    val gridItem: AlbumGridItem,
    val children: List<SidebarAlbumNode> = emptyList()
)

@Immutable
data class DuplicateCluster(
    val clusterKey: String,
    val sizeBytes: Long,
    val items: List<MediaItem>
)

enum class SpecialGalleryFilter(val title: String) {
    VIDEOS("Videos"),
    FAVORITES("Favorites"),
    RECENT("Recent"),
    TRASH("Trash")
}

@Immutable
data class GalleryStory(
    val id: String,
    val title: String,
    val subtitle: String,
    val dateRangeLabel: String,
    val daySpanLabel: String?,
    val category: StoryCategory,
    val coverItem: MediaItem,
    val items: List<MediaItem>,
    val isNew: Boolean = false,
    val isFavorited: Boolean = false
)

enum class StoryCategory {
    FEATURED,
    TRIP,
    OTHER
}

@Immutable
data class GeoTaggedMediaItem(
    val item: MediaItem,
    val latitude: Double,
    val longitude: Double
)

@Immutable
data class LocationPhotoGroup(
    val locationKey: String,
    val displayName: String,
    val country: String = "",
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val coverItem: MediaItem,
    val items: List<MediaItem>,
    val geoItems: List<GeoTaggedMediaItem> = emptyList()
)

enum class CleanOutCategory(val title: String, val description: String) {
    LARGE_FILES("Large videos & files", "Files larger than 50 MB taking up space"),
    DUPLICATES("Duplicate files", "Exact copies found across your storage"),
    BURST_SIMILAR("Similar burst shots", "Rapid shots taken within 2 seconds"),
    OLD_SCREENSHOTS("Old screenshots", "Screenshots older than 30 days")
}

@Immutable
data class CleanOutGroup(
    val category: CleanOutCategory,
    val items: List<MediaItem>,
    val reclaimableBytes: Long,
    val preselectedIds: Set<String>,
    val duplicateClusters: List<DuplicateCluster> = emptyList()
)

@Immutable
data class DriveFolder(
    val id: String,
    val parentId: String?,
    val name: String,
    val subfolderCount: Int,
    val mediaCount: Int,
    val coverThumbnailLink: String?,
    val coverItemId: String?,
    val modifiedTimeEpochMs: Long,
    val isPinned: Boolean = false,
    val isLocal: Boolean = false,
    val coverIsVideo: Boolean = false,
    val coverSizeBytes: Long = 0L,
    val previewCovers: List<AlbumCoverPreview> = emptyList(),
    val relativePath: String? = null,
    val isLocked: Boolean = false
)

@Immutable
data class AlbumCoverPreview(
    val itemId: String,
    val thumbnailLink: String?,
    val isLocal: Boolean,
    val isVideo: Boolean = false,
    val sizeBytes: Long = 0L
)

@Immutable
sealed interface AlbumGridItem {
    val itemId: String
    val itemKind: String
    val displayName: String
    val sortOrder: Int

    @Immutable
    data class AlbumEntry(
        val folder: DriveFolder,
        override val sortOrder: Int
    ) : AlbumGridItem {
        override val itemId: String get() = folder.id
        override val itemKind: String get() = if (folder.isLocal) KIND_LOCAL else KIND_DRIVE
        override val displayName: String get() = folder.name
    }

    @Immutable
    data class CollectionEntry(
        val id: String,
        val name: String,
        val parentCollectionId: String?,
        val childCount: Int,
        val totalMediaCount: Int,
        val previewCovers: List<AlbumCoverPreview>,
        val hasDriveChild: Boolean,
        override val sortOrder: Int
    ) : AlbumGridItem {
        override val itemId: String get() = id
        override val itemKind: String get() = KIND_COLLECTION
        override val displayName: String get() = name
    }

    companion object {
        const val KIND_LOCAL = "LOCAL_ALBUM"
        const val KIND_DRIVE = "DRIVE_ROOT"
        const val KIND_COLLECTION = "COLLECTION"
    }
}

@Immutable
data class ExifMetadata(
    val cameraMake: String?,
    val cameraModel: String?,
    val lens: String?,
    val aperture: Float?,
    val exposureTime: Float?,
    val isoSpeed: Int?,
    val focalLength: Float?,
    val dateTakenDisplay: String?
) {
    val hasCameraDetails: Boolean
        get() = !cameraMake.isNullOrBlank() ||
            !cameraModel.isNullOrBlank() ||
            !lens.isNullOrBlank() ||
            aperture != null ||
            exposureTime != null ||
            isoSpeed != null ||
            focalLength != null

    val cameraDisplayName: String?
        get() = when {
            !cameraMake.isNullOrBlank() && !cameraModel.isNullOrBlank() -> {
                if (cameraModel.startsWith(cameraMake, ignoreCase = true)) {
                    cameraModel
                } else {
                    "$cameraMake $cameraModel"
                }
            }
            !cameraModel.isNullOrBlank() -> cameraModel
            !cameraMake.isNullOrBlank() -> cameraMake
            else -> null
        }
}

@Immutable
data class MediaItem(
    val id: String,
    val parentId: String?,
    val name: String,
    val mimeType: String,
    val mediaType: MediaType,
    val sizeBytes: Long,
    val createdTimeEpochMs: Long,
    val monthYearLabel: String,
    val fullDateLabel: String,
    val thumbnailLink: String?,
    val width: Int?,
    val height: Int?,
    val rotation: Int?,
    val durationMillis: Long?,
    val exif: ExifMetadata,
    val isLocal: Boolean = false,
    val localContentUri: String? = null,
    val relativePath: String? = null,
    val storagePath: String? = null,
    val daySectionLabel: String = monthYearLabel
) {
    val isVideo: Boolean get() = mediaType == MediaType.VIDEO
    val isRaw: Boolean get() = mediaType == MediaType.RAW

    val effectiveWidth: Int?
        get() = if (rotation == 90 || rotation == 270) height else width

    val effectiveHeight: Int?
        get() = if (rotation == 90 || rotation == 270) width else height

    val aspectRatio: Float
        get() {
            val w = effectiveWidth ?: return 1f
            val h = effectiveHeight ?: return 1f
            return if (w > 0 && h > 0) (w.toFloat() / h.toFloat()).coerceIn(0.4f, 2.5f) else 1f
        }
}

@Immutable
data class BreadcrumbItem(
    val id: String,
    val name: String
)

@Immutable
data class DateSectionHeader(
    val label: String,
    val itemCount: Int
)

@Immutable
data class StorageStats(
    val diskCacheUsedBytes: Long,
    val maxCacheLimitMb: Int,
    val cachedMediaCount: Int,
    val cachedFolderCount: Int
)

@Immutable
data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastSyncEpochMs: Long? = null,
    val initialScanComplete: Boolean = false,
    val errorMessage: String? = null
)

@Immutable
data class UserPreferences(
    val maxCacheSizeMb: Int = 500,
    val themeMode: ThemeMode = ThemeMode.AMOLED_BLACK,
    val dynamicColor: Boolean = true,
    val gridColumns: Int = 4,
    val albumGridColumns: Int = 3,
    val backgroundSyncEnabled: Boolean = false,
    val albumSortMode: AlbumSortMode = AlbumSortMode.CUSTOM,
    val defaultStartTab: MainTab = MainTab.ALBUMS,
    val showDayLocationHeaders: Boolean = true,
    val showAlbumSidebarDefault: Boolean = true,
    val showCloudBadges: Boolean = true,
    val autoPlayVideos: Boolean = true,
    val startVideosMuted: Boolean = false,
    val loopVideos: Boolean = false,
    val showViewerFilmstrip: Boolean = true,
    val maxViewerBrightness: Boolean = false,
    val keepScreenOnViewer: Boolean = true,
    val swipeDownToDismiss: Boolean = true,
    val prefetchAdjacentMedia: Boolean = true,
    val wifiOnlyHighRes: Boolean = true,
    val externalGalleryPackage: String = "",
    val externalGalleryLabel: String = "",
    val autoCheckUpdates: Boolean = true,
    val stripLocationOnShare: Boolean = true,
    val lockHiddenAlbums: Boolean = false
)
