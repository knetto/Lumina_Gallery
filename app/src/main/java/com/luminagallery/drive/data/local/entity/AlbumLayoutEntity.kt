package com.luminagallery.drive.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "album_layout",
    indices = [
        Index(value = ["parentCollectionId"]),
        Index(value = ["sortOrder"])
    ]
)
data class AlbumLayoutEntity(
    @PrimaryKey
    val itemId: String,
    val itemKind: String,
    val parentCollectionId: String? = null,
    val sortOrder: Int = Int.MAX_VALUE
)
