package com.luminagallery.drive.data.remote

object DriveScopes {
    const val DRIVE_READONLY: String = "https://www.googleapis.com/auth/drive.readonly"
}

object DriveConstants {
    const val BASE_URL: String = "https://www.googleapis.com/"
    const val FOLDER_MIME_TYPE: String = "application/vnd.google-apps.folder"
    const val DEFAULT_PAGE_SIZE: Int = 1000
    const val MAX_DIRECT_MEDIA_THUMB_BYTES: Long = 16L * 1024L * 1024L

    private const val FILE_FIELDS_BODY: String =
        "id,name,mimeType,parents,size,createdTime,modifiedTime,thumbnailLink,trashed," +
            "imageMediaMetadata(width,height,rotation,time,cameraMake,cameraModel,exposureTime,aperture,isoSpeed,focalLength,lens)," +
            "videoMediaMetadata(width,height,durationMillis)"

    const val SINGLE_FILE_FIELDS: String = FILE_FIELDS_BODY
    const val FILES_LIST_FIELDS: String = "nextPageToken,files($FILE_FIELDS_BODY)"
    const val CHANGES_LIST_FIELDS: String =
        "nextPageToken,newStartPageToken,changes(fileId,removed,file($FILE_FIELDS_BODY))"

    private val THUMB_SIZE_REGEX = Regex("=s\\d+(-[a-zA-Z0-9]+)?$")
    private val THUMB_WH_REGEX = Regex("=w\\d+-h\\d+(-[a-zA-Z0-9]+)?$")
    private val DRIVE_FOLDER_URL_REGEX = Regex("/folders/([A-Za-z0-9_-]{10,128})")
    private val DRIVE_OPEN_ID_REGEX = Regex("[?&]id=([A-Za-z0-9_-]{10,128})")
    private val RAW_ID_REGEX = Regex("^[A-Za-z0-9_-]{10,128}$")

    fun buildFullMediaUrl(fileId: String): String =
        "${BASE_URL}drive/v3/files/$fileId?alt=media&supportsAllDrives=true"

    /**
     * Builds a crisp thumbnail URL for grids & album covers.
     * NEVER falls back to `?alt=media` for videos or huge >16 MB files (preventing
     * 200+ MB video downloads from trying to render as album cover images!).
     */
    fun buildThumbnailUrl(
        thumbnailLink: String?,
        fileId: String,
        isUnmeteredWifi: Boolean,
        isVideo: Boolean = false,
        sizeBytes: Long = 0L
    ): String? {
        val targetSize = if (isUnmeteredWifi) "=s600" else "=s360"
        if (!thumbnailLink.isNullOrBlank()) {
            return resizeThumbnailLink(thumbnailLink, targetSize)
        }
        if (isVideo || sizeBytes > MAX_DIRECT_MEDIA_THUMB_BYTES) {
            return null
        }
        return buildFullMediaUrl(fileId)
    }

    /**
     * For Google Drive RAW photos (.DNG, .CR2, .CR3, .NEF, .ARW, .RAF, etc.) in the
     * full-screen viewer: upgrades Google Drive's server-rendered high-res JPEG preview
     * to `=s2400` (fast & compatible with every camera RAW codec), falling back to
     * `?alt=media` decoded by `RawImageDecoder` if no server thumbnail exists.
     */
    fun buildRawViewerUrl(thumbnailLink: String?, fileId: String, isUnmeteredWifi: Boolean): String {
        if (!thumbnailLink.isNullOrBlank()) {
            val targetSize = if (isUnmeteredWifi) "=s2400" else "=s1440"
            return resizeThumbnailLink(thumbnailLink, targetSize)
        }
        return buildFullMediaUrl(fileId)
    }

    private fun resizeThumbnailLink(thumbnailLink: String, targetSize: String): String = when {
        THUMB_SIZE_REGEX.containsMatchIn(thumbnailLink) ->
            thumbnailLink.replace(THUMB_SIZE_REGEX, targetSize)
        THUMB_WH_REGEX.containsMatchIn(thumbnailLink) ->
            thumbnailLink.replace(THUMB_WH_REGEX, targetSize)
        thumbnailLink.contains("googleusercontent.com", ignoreCase = true) &&
            !thumbnailLink.contains("=") ->
            "$thumbnailLink$targetSize"
        else -> thumbnailLink
    }

    fun thumbnailCacheKey(fileId: String, isUnmeteredWifi: Boolean): String =
        "thumb_${fileId}_${if (isUnmeteredWifi) "hd" else "eco"}"

    fun stableThumbMemoryKey(fileId: String): String = "thumb_mem_$fileId"

    fun fullResMemoryKey(fileId: String): String = "full_mem_$fileId"

    fun extractFolderId(rawInput: String): String? {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return null

        DRIVE_FOLDER_URL_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        DRIVE_OPEN_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        if (RAW_ID_REGEX.matches(trimmed)) return trimmed
        return null
    }
}
