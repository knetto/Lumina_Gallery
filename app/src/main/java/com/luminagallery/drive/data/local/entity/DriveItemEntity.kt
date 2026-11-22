package com.luminagallery.drive.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "drive_items",
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["isFolder"]),
        Index(value = ["parentId", "isFolder", "trashed"]),
        Index(value = ["createdTimeEpochMs"]),
        Index(value = ["mediaType"]),
        Index(value = ["name"])
    ]
)
data class DriveItemEntity(
    @PrimaryKey
    val id: String,
    val parentId: String?,
    val name: String,
    val mimeType: String,
    val isFolder: Boolean,
    val mediaType: String,
    val sizeBytes: Long,
    val createdTimeIso: String?,
    val createdTimeEpochMs: Long,
    val modifiedTimeIso: String?,
    val modifiedTimeEpochMs: Long,
    val thumbnailLink: String?,
    val width: Int?,
    val height: Int?,
    val rotation: Int?,
    val durationMillis: Long?,
    val cameraMake: String?,
    val cameraModel: String?,
    val lens: String?,
    val aperture: Float?,
    val exposureTime: Float?,
    val isoSpeed: Int?,
    val focalLength: Float?,
    val dateTakenIso: String?,
    val trashed: Boolean = false
)
