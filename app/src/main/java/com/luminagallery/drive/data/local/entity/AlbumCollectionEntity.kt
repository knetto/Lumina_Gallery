package com.luminagallery.drive.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "album_collections",
    indices = [Index(value = ["parentCollectionId"])]
)
data class AlbumCollectionEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val parentCollectionId: String? = null,
    val createdAtEpochMs: Long
)
