package com.luminagallery.drive.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorite_items")
data class FavoriteItemEntity(
    @PrimaryKey
    val itemId: String,
    val favoritedAtEpochMs: Long
)

@Entity(tableName = "hidden_albums")
data class HiddenAlbumEntity(
    @PrimaryKey
    val albumId: String,
    val hiddenAtEpochMs: Long
)

@Entity(tableName = "favorite_stories")
data class FavoriteStoryEntity(
    @PrimaryKey
    val storyId: String,
    val favoritedAtEpochMs: Long
)

@Entity(tableName = "ignored_cleanout_items")
data class IgnoredCleanOutEntity(
    @PrimaryKey
    val itemId: String,
    val ignoredAtEpochMs: Long
)

@Entity(tableName = "photo_location_cache")
data class PhotoLocationEntity(
    @PrimaryKey
    val itemId: String,
    val dateModifiedMs: Long,
    val hasLocation: Boolean,
    val latitude: Double,
    val longitude: Double,
    val city: String,
    val country: String
)

@Entity(tableName = "custom_album_covers")
data class CustomAlbumCoverEntity(
    @PrimaryKey
    val albumId: String,
    val coverItemId: String,
    val coverThumbnailLink: String?,
    val isLocal: Boolean,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "media_annotations")
data class MediaAnnotationEntity(
    @PrimaryKey
    val itemId: String,
    val tagsCsv: String,
    val note: String,
    val updatedAtEpochMs: Long
)

@Entity(tableName = "offline_pinned_items")
data class OfflinePinnedEntity(
    @PrimaryKey
    val itemId: String,
    val folderId: String?,
    val localFilePath: String,
    val sizeBytes: Long,
    val pinnedAtEpochMs: Long
)

@Entity(tableName = "photo_ocr_cache")
data class PhotoOcrEntity(
    @PrimaryKey
    val itemId: String,
    val dateModifiedMs: Long,
    val extractedText: String,
    val hasText: Boolean
)

@Entity(tableName = "album_view_prefs")
data class AlbumViewPrefEntity(
    @PrimaryKey
    val albumId: String,
    val sortName: String,
    val groupByName: String,
    val updatedAtEpochMs: Long
)
