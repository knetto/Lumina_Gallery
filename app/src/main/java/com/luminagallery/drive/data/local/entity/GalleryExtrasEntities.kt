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
