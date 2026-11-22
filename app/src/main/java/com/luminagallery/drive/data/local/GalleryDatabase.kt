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
import com.luminagallery.drive.data.local.dao.SyncStateDao
import com.luminagallery.drive.data.local.entity.AlbumCollectionEntity
import com.luminagallery.drive.data.local.entity.AlbumLayoutEntity
import com.luminagallery.drive.data.local.entity.DriveItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteStoryEntity
import com.luminagallery.drive.data.local.entity.HiddenAlbumEntity
import com.luminagallery.drive.data.local.entity.IgnoredCleanOutEntity
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.data.local.entity.PinnedRootEntity
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
        PhotoLocationEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun driveItemDao(): DriveItemDao
    abstract fun pinnedRootDao(): PinnedRootDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun albumCollectionDao(): AlbumCollectionDao
    abstract fun galleryExtrasDao(): GalleryExtrasDao

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

        fun build(context: Context): GalleryDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                GalleryDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
