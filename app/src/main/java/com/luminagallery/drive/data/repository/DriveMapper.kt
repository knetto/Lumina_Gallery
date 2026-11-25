package com.luminagallery.drive.data.repository

import com.luminagallery.drive.data.local.dao.FolderWithStatsRow
import com.luminagallery.drive.data.local.dao.PinnedRootWithStatsRow
import com.luminagallery.drive.data.local.entity.DriveItemEntity
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.remote.dto.DriveFileDto
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DriveMapper {

    private val RAW_EXTENSIONS = setOf(
        "dng", "cr2", "cr3", "nef", "nrw", "arw", "srf", "sr2",
        "orf", "rw2", "raf", "pef", "srw", "x3f", "raw", "iiq"
    )

    private val EXIF_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.US)

    private val MONTH_YEAR_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())

    private val FULL_DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())

    private const val TYPE_FOLDER = "FOLDER"

    // Computer roots appear as parentless, non-shareable folders in the owner's listing.
    // See https://issuetracker.google.com/issues/172775993
    internal fun isComputerRoot(folder: DriveFileDto, myDriveRootId: String): Boolean =
        folder.mimeType == DriveConstants.FOLDER_MIME_TYPE &&
            !folder.trashed &&
            folder.id != myDriveRootId &&
            folder.parents.isEmpty() &&
            folder.capabilities?.canShare == false

    fun isSupportedGalleryDto(dto: DriveFileDto): Boolean {
        if (dto.mimeType == DriveConstants.FOLDER_MIME_TYPE) return true
        if (dto.mimeType.startsWith("image/", ignoreCase = true)) return true
        if (VideoFormatSupport.isVideoFile(dto.name, dto.mimeType)) return true
        val ext = dto.name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in RAW_EXTENSIONS
    }

    fun dtoToEntity(dto: DriveFileDto, fallbackParentId: String? = null): DriveItemEntity {
        val isFolder = dto.mimeType == DriveConstants.FOLDER_MIME_TYPE
        val isVideo = !isFolder && VideoFormatSupport.isVideoFile(dto.name, dto.mimeType)
        val mediaType = when {
            isFolder -> TYPE_FOLDER
            isVideo -> MediaType.VIDEO.name
            isRawFile(dto.name, dto.mimeType) -> MediaType.RAW.name
            else -> MediaType.PHOTO.name
        }
        val effectiveMimeType = if (isVideo) {
            VideoFormatSupport.normalizeVideoMimeType(dto.name, dto.mimeType)
        } else {
            dto.mimeType
        }

        val imageMeta = dto.imageMediaMetadata
        val videoMeta = dto.videoMediaMetadata

        val exifEpochMs = parseExifTimestamp(imageMeta?.time)
        val createdEpochMs = parseIsoTimestamp(dto.createdTime)
        val modifiedEpochMs = parseIsoTimestamp(dto.modifiedTime)
        val effectiveCreatedMs = exifEpochMs ?: createdEpochMs ?: modifiedEpochMs ?: 0L
        val effectiveModifiedMs = modifiedEpochMs ?: createdEpochMs ?: 0L

        return DriveItemEntity(
            id = dto.id,
            parentId = dto.parents.firstOrNull() ?: fallbackParentId,
            name = dto.name.ifBlank { "Untitled" },
            mimeType = effectiveMimeType,
            isFolder = isFolder,
            mediaType = mediaType,
            sizeBytes = dto.size?.toLongOrNull() ?: 0L,
            createdTimeIso = dto.createdTime,
            createdTimeEpochMs = effectiveCreatedMs,
            modifiedTimeIso = dto.modifiedTime,
            modifiedTimeEpochMs = effectiveModifiedMs,
            thumbnailLink = dto.thumbnailLink,
            width = imageMeta?.width ?: videoMeta?.width,
            height = imageMeta?.height ?: videoMeta?.height,
            rotation = imageMeta?.rotation,
            durationMillis = videoMeta?.durationMillis?.toLongOrNull(),
            cameraMake = imageMeta?.cameraMake?.trim()?.takeIf { it.isNotEmpty() },
            cameraModel = imageMeta?.cameraModel?.trim()?.takeIf { it.isNotEmpty() },
            lens = imageMeta?.lens?.trim()?.takeIf { it.isNotEmpty() },
            aperture = imageMeta?.aperture,
            exposureTime = imageMeta?.exposureTime,
            isoSpeed = imageMeta?.isoSpeed,
            focalLength = imageMeta?.focalLength,
            dateTakenIso = imageMeta?.time ?: dto.createdTime,
            trashed = dto.trashed
        )
    }

    fun entityToMediaItem(entity: DriveItemEntity): MediaItem {
        val zone = ZoneId.systemDefault()
        val epoch = entity.createdTimeEpochMs
        val (monthYear, fullDate) = if (epoch > 0L) {
            val zdt = Instant.ofEpochMilli(epoch).atZone(zone)
            MONTH_YEAR_FORMATTER.format(zdt) to FULL_DATE_FORMATTER.format(zdt)
        } else {
            "Unknown Date" to "Unknown Date"
        }

        val resolvedType = runCatching { MediaType.valueOf(entity.mediaType) }
            .getOrDefault(MediaType.PHOTO)

        return MediaItem(
            id = entity.id,
            parentId = entity.parentId,
            name = entity.name,
            mimeType = entity.mimeType,
            mediaType = resolvedType,
            sizeBytes = entity.sizeBytes,
            createdTimeEpochMs = epoch,
            monthYearLabel = monthYear,
            fullDateLabel = fullDate,
            thumbnailLink = entity.thumbnailLink,
            width = entity.width,
            height = entity.height,
            rotation = entity.rotation,
            durationMillis = entity.durationMillis,
            exif = ExifMetadata(
                cameraMake = entity.cameraMake,
                cameraModel = entity.cameraModel,
                lens = entity.lens,
                aperture = entity.aperture,
                exposureTime = entity.exposureTime,
                isoSpeed = entity.isoSpeed,
                focalLength = entity.focalLength,
                dateTakenDisplay = fullDate
            )
        )
    }

    fun folderRowToDomain(row: FolderWithStatsRow): DriveFolder = DriveFolder(
        id = row.id,
        parentId = row.parentId,
        name = row.name,
        subfolderCount = row.subfolderCount,
        mediaCount = row.mediaCount,
        coverThumbnailLink = row.coverThumbnailLink,
        coverItemId = row.coverItemId,
        modifiedTimeEpochMs = row.modifiedTimeEpochMs,
        isPinned = row.isPinned
    )

    fun pinnedRootRowToDomain(row: PinnedRootWithStatsRow): DriveFolder = DriveFolder(
        id = row.folderId,
        parentId = null,
        name = row.displayName,
        subfolderCount = row.subfolderCount,
        mediaCount = row.mediaCount,
        coverThumbnailLink = row.coverThumbnailLink,
        coverItemId = row.coverItemId,
        modifiedTimeEpochMs = row.modifiedTimeEpochMs,
        isPinned = true
    )

    private fun isRawFile(name: String, mimeType: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext in RAW_EXTENSIONS) return true
        val lowerMime = mimeType.lowercase(Locale.US)
        return lowerMime.contains("dng") ||
            lowerMime.contains("raw") ||
            lowerMime.contains("cr2") ||
            lowerMime.contains("nef") ||
            lowerMime.contains("arw")
    }

    private fun parseIsoTimestamp(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
    }

    private fun parseExifTimestamp(exifTime: String?): Long? {
        if (exifTime.isNullOrBlank()) return null
        return runCatching {
            val ldt = LocalDateTime.parse(exifTime.trim(), EXIF_DATE_FORMATTER)
            ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
