package com.luminagallery.drive.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.luminagallery.drive.data.local.dao.AlbumCollectionDao
import com.luminagallery.drive.data.local.dao.DriveItemDao
import com.luminagallery.drive.data.local.dao.GalleryExtrasDao
import com.luminagallery.drive.data.local.dao.PinnedRootDao
import com.luminagallery.drive.data.local.dao.SmartRecognitionDao
import com.luminagallery.drive.data.local.dao.SyncStateDao
import com.luminagallery.drive.data.local.entity.AlbumCollectionEntity
import com.luminagallery.drive.data.local.entity.AlbumLayoutEntity
import com.luminagallery.drive.data.local.entity.AlbumViewPrefEntity
import com.luminagallery.drive.data.local.entity.CustomAlbumCoverEntity
import com.luminagallery.drive.data.local.entity.DetectedFaceEntity
import com.luminagallery.drive.data.local.entity.DriveItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteStoryEntity
import com.luminagallery.drive.data.local.entity.HiddenAlbumEntity
import com.luminagallery.drive.data.local.entity.IgnoredCleanOutEntity
import com.luminagallery.drive.data.local.entity.LockedAlbumEntity
import com.luminagallery.drive.data.local.entity.MediaAnnotationEntity
import com.luminagallery.drive.data.local.entity.OfflinePinnedEntity
import com.luminagallery.drive.data.local.entity.PersonClusterEntity
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.data.local.entity.PhotoOcrEntity
import com.luminagallery.drive.data.local.entity.PhotoSmartIndexEntity
import com.luminagallery.drive.data.local.entity.PinnedRootEntity
import com.luminagallery.drive.data.local.entity.SavedPersonAnchorEntity
import com.luminagallery.drive.data.local.entity.SavedPersonMemoryEntity
import com.luminagallery.drive.data.local.entity.SavedRejectedMergeEntity
import com.luminagallery.drive.data.local.entity.SyncStateEntity

@Database(
    entities = [
        DriveItemEntity::class,
        PinnedRootEntity::class,
        SyncStateEntity::class,
        AlbumCollectionEntity::class,
        AlbumLayoutEntity::class,
        FavoriteItemEntity::class,
        HiddenAlbumEntity::class,
        FavoriteStoryEntity::class,
        IgnoredCleanOutEntity::class,
        PhotoLocationEntity::class,
        CustomAlbumCoverEntity::class,
        MediaAnnotationEntity::class,
        OfflinePinnedEntity::class,
        PhotoOcrEntity::class,
        AlbumViewPrefEntity::class,
        LockedAlbumEntity::class,
        PersonClusterEntity::class,
        DetectedFaceEntity::class,
        PhotoSmartIndexEntity::class,
        SavedPersonMemoryEntity::class,
        SavedPersonAnchorEntity::class,
        SavedRejectedMergeEntity::class
    ],
    version = 13,
    exportSchema = true
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun driveItemDao(): DriveItemDao
    abstract fun pinnedRootDao(): PinnedRootDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun albumCollectionDao(): AlbumCollectionDao
    abstract fun galleryExtrasDao(): GalleryExtrasDao
    abstract fun smartRecognitionDao(): SmartRecognitionDao

    companion object {
        private const val DATABASE_NAME = "lumina_drive_gallery.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_collections` (
                        `id` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `parentCollectionId` TEXT,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_collections_parentCollectionId` ON `album_collections` (`parentCollectionId`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_layout` (
                        `itemId` TEXT NOT NULL,
                        `itemKind` TEXT NOT NULL,
                        `parentCollectionId` TEXT,
                        `sortOrder` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_layout_parentCollectionId` ON `album_layout` (`parentCollectionId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_album_layout_sortOrder` ON `album_layout` (`sortOrder`)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_items` (
                        `itemId` TEXT NOT NULL,
                        `favoritedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `hidden_albums` (
                        `albumId` TEXT NOT NULL,
                        `hiddenAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`albumId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `favorite_stories` (
                        `storyId` TEXT NOT NULL,
                        `favoritedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`storyId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ignored_cleanout_items` (
                        `itemId` TEXT NOT NULL,
                        `ignoredAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_location_cache` (
                        `itemId` TEXT NOT NULL,
                        `dateModifiedMs` INTEGER NOT NULL,
                        `hasLocation` INTEGER NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `city` TEXT NOT NULL,
                        `country` TEXT NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_drive_items_parentId_isFolder_trashed_createdTimeEpochMs` ON `drive_items` (`parentId`, `isFolder`, `trashed`, `createdTimeEpochMs`)"
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `custom_album_covers` (
                        `albumId` TEXT NOT NULL,
                        `coverItemId` TEXT NOT NULL,
                        `coverThumbnailLink` TEXT,
                        `isLocal` INTEGER NOT NULL,
                        `isVideo` INTEGER NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`albumId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_annotations` (
                        `itemId` TEXT NOT NULL,
                        `tagsCsv` TEXT NOT NULL,
                        `note` TEXT NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `offline_pinned_items` (
                        `itemId` TEXT NOT NULL,
                        `folderId` TEXT,
                        `localFilePath` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `pinnedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_ocr_cache` (
                        `itemId` TEXT NOT NULL,
                        `dateModifiedMs` INTEGER NOT NULL,
                        `extractedText` TEXT NOT NULL,
                        `hasText` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `album_view_prefs` (
                        `albumId` TEXT NOT NULL,
                        `sortName` TEXT NOT NULL,
                        `groupByName` TEXT NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`albumId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `locked_albums` (
                        `albumId` TEXT NOT NULL,
                        `lockedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`albumId`)
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `locked_albums` ADD COLUMN `isHiddenOnDisk` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `locked_albums` ADD COLUMN `hiddenDirPath` TEXT")
                db.execSQL("ALTER TABLE `locked_albums` ADD COLUMN `originalDirPath` TEXT")
                db.execSQL("ALTER TABLE `locked_albums` ADD COLUMN `displayName` TEXT")
                db.execSQL("ALTER TABLE `locked_albums` ADD COLUMN `originalRelativePath` TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_offline_pinned_items_folderId` ON `offline_pinned_items` (`folderId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_location_cache_hasLocation` ON `photo_location_cache` (`hasLocation`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_ocr_cache_hasText` ON `photo_ocr_cache` (`hasText`)")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `person_clusters` (
                        `personId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isHidden` INTEGER NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `coverFaceId` TEXT,
                        `coverPhotoId` TEXT,
                        `coverCropPath` TEXT,
                        `centroidBlob` BLOB NOT NULL,
                        `sampleCount` INTEGER NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`personId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_clusters_isHidden` ON `person_clusters` (`isHidden`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_clusters_isFavorite` ON `person_clusters` (`isFavorite`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `detected_faces` (
                        `faceId` TEXT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `personId` TEXT,
                        `embeddingBlob` BLOB NOT NULL,
                        `bboxLeft` REAL NOT NULL,
                        `bboxTop` REAL NOT NULL,
                        `bboxRight` REAL NOT NULL,
                        `bboxBottom` REAL NOT NULL,
                        `qualityScore` REAL NOT NULL,
                        `cropPath` TEXT,
                        `isExcludedByUser` INTEGER NOT NULL,
                        `detectedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`faceId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_faces_itemId` ON `detected_faces` (`itemId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_detected_faces_personId` ON `detected_faces` (`personId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `photo_smart_index` (
                        `itemId` TEXT NOT NULL,
                        `dateModifiedMs` INTEGER NOT NULL,
                        `faceCount` INTEGER NOT NULL,
                        `labelsCsv` TEXT NOT NULL,
                        `labelSearchTokens` TEXT NOT NULL,
                        `isDriveItem` INTEGER NOT NULL,
                        `indexedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`itemId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_smart_index_faceCount` ON `photo_smart_index` (`faceCount`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_photo_smart_index_isDriveItem` ON `photo_smart_index` (`isDriveItem`)")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `person_clusters` ADD COLUMN `memoryId` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_clusters_memoryId` ON `person_clusters` (`memoryId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_person_memory` (
                        `memoryId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isFavorite` INTEGER NOT NULL,
                        `isHidden` INTEGER NOT NULL,
                        `isUserMerged` INTEGER NOT NULL,
                        `coverItemId` TEXT,
                        `coverBboxLeft` REAL,
                        `coverBboxTop` REAL,
                        `coverBboxRight` REAL,
                        `coverBboxBottom` REAL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`memoryId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_person_memory_name` ON `saved_person_memory` (`name`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_person_anchors` (
                        `anchorId` TEXT NOT NULL,
                        `memoryId` TEXT NOT NULL,
                        `itemId` TEXT NOT NULL,
                        `bboxLeft` REAL NOT NULL,
                        `bboxTop` REAL NOT NULL,
                        `bboxRight` REAL NOT NULL,
                        `bboxBottom` REAL NOT NULL,
                        `originGroupId` TEXT NOT NULL,
                        `isUserAssigned` INTEGER NOT NULL,
                        `isExcluded` INTEGER NOT NULL,
                        `qualityScore` REAL NOT NULL,
                        `updatedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`anchorId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_person_anchors_memoryId` ON `saved_person_anchors` (`memoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_person_anchors_itemId` ON `saved_person_anchors` (`itemId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_rejected_merges` (
                        `pairKey` TEXT NOT NULL,
                        `memoryIdA` TEXT NOT NULL,
                        `memoryIdB` TEXT NOT NULL,
                        `createdAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`pairKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun build(context: Context): GalleryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GalleryDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13
                )
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
