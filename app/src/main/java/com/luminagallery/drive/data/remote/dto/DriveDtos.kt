package com.luminagallery.drive.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class DriveFileListResponse(
    val nextPageToken: String? = null,
    val files: List<DriveFileDto> = emptyList()
)

@Serializable
data class DriveFileDto(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    val parents: List<String> = emptyList(),
    val size: String? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
    val thumbnailLink: String? = null,
    val trashed: Boolean = false,
    val imageMediaMetadata: ImageMediaMetadataDto? = null,
    val videoMediaMetadata: VideoMediaMetadataDto? = null,
    val capabilities: DriveFileCapabilitiesDto? = null
)

@Serializable
data class DriveFileCapabilitiesDto(
    val canShare: Boolean? = null
)

@Serializable
data class ImageMediaMetadataDto(
    val width: Int? = null,
    val height: Int? = null,
    val rotation: Int? = null,
    val time: String? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val exposureTime: Float? = null,
    val aperture: Float? = null,
    val isoSpeed: Int? = null,
    val focalLength: Float? = null,
    val lens: String? = null
)

@Serializable
data class VideoMediaMetadataDto(
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: String? = null
)

@Serializable
data class StartPageTokenResponse(
    val startPageToken: String
)

@Serializable
data class DriveChangesResponse(
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null,
    val changes: List<DriveChangeDto> = emptyList()
)

@Serializable
data class DriveChangeDto(
    val fileId: String,
    val removed: Boolean = false,
    val file: DriveFileDto? = null
)

@Serializable
data class DriveAboutResponse(
    val user: DriveUserDto? = null
)

@Serializable
data class DriveUserDto(
    val displayName: String? = null,
    val emailAddress: String? = null
)
