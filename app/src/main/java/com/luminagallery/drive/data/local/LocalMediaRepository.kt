package com.luminagallery.drive.data.local

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.luminagallery.drive.data.local.dao.GalleryExtrasDao
import com.luminagallery.drive.data.local.entity.LockedAlbumEntity
import com.luminagallery.drive.domain.model.CleanOutCategory
import com.luminagallery.drive.domain.model.CleanOutGroup
import com.luminagallery.drive.domain.model.DriveFolder
import com.luminagallery.drive.domain.model.DuplicateCluster
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.LocationPhotoGroup
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Queries on-device Android MediaStore albums, photos, videos, RAW files, Trash, Favorites,
 * Locations (EXIF GPS + Geocoder), and Smart Clean-Out insights in real time.
 */
class LocalMediaRepository(
    private val context: Context,
    private val extrasDao: GalleryExtrasDao? = null,
    private val hiddenFolderManager: HiddenLockedFolderManager = HiddenLockedFolderManager(context)
) {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permissionRefreshTrigger = MutableStateFlow(0)
    private val geocodeCache = ConcurrentHashMap<String, Pair<String, String>>() // roundedCoord -> (city, country)
    private val detailedAddressCache = ConcurrentHashMap<String, String>() // fineCoord -> full readable address

    // Pre-indexed in-memory snapshots for 0ms Frame-0 album opening (zero copy: shares exact MediaItem instances)
    @Volatile
    private var latestAllMedia: List<MediaItem> = emptyList()

    @Volatile
    private var latestVideosMedia: List<MediaItem> = emptyList()

    @Volatile
    private var latestMediaByBucket: Map<String, List<MediaItem>> = emptyMap()

    @Volatile
    private var latestLocalAlbums: List<DriveFolder> = emptyList()

    fun getCachedLocalAlbums(): List<DriveFolder> = latestLocalAlbums

    fun getCachedAllMedia(): List<MediaItem> = latestAllMedia

    fun getCachedAlbumMedia(bucketId: String): List<MediaItem> = when (bucketId) {
        LOCAL_BUCKET_ALL -> latestAllMedia
        LOCAL_BUCKET_VIDEOS -> latestVideosMedia
        else -> latestMediaByBucket[bucketId].orEmpty()
    }

    // Single shared ContentObserver & MediaStore change stream (immediate first tick, 250ms burst coalescing)
    private val sharedMediaStoreTriggers: Flow<Unit> = combine(
        observeMediaStoreChanges(),
        permissionRefreshTrigger
    ) { _, _ -> Unit }
        .conflate()
        .shareIn(
            scope = repoScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L, replayExpirationMillis = 0L),
            replay = 1
        )

    private data class IndexedLocalMedia(
        val allItems: List<MediaItem>,
        val videos: List<MediaItem>,
        val byBucket: Map<String, List<MediaItem>>,
        val albums: List<DriveFolder>
    )

    private val lockedAlbumsFlow: Flow<List<LockedAlbumEntity>> =
        extrasDao?.observeLockedAlbums() ?: flowOf(emptyList())

    private val sharedIndexedLocalMedia: Flow<IndexedLocalMedia> = combine(
        sharedMediaStoreTriggers,
        lockedAlbumsFlow
    ) { _, lockedAlbums -> lockedAlbums }
        .conflate()
        .map { lockedAlbums ->
            val allItems = if (!hasMediaPermission()) emptyList() else queryLocalMedia(trashedOnly = false)
            val diskHiddenAlbums = lockedAlbums.filter {
                it.isHiddenOnDisk && !it.hiddenDirPath.isNullOrBlank()
            }

            if (allItems.isEmpty() && diskHiddenAlbums.isEmpty()) {
                latestAllMedia = emptyList()
                latestVideosMedia = emptyList()
                latestMediaByBucket = emptyMap()
                latestLocalAlbums = emptyList()
                return@map IndexedLocalMedia(emptyList(), emptyList(), emptyMap(), emptyList())
            }

            val grouped = LinkedHashMap<String, MutableList<MediaItem>>()
            val videos = ArrayList<MediaItem>(64)
            for (item in allItems) {
                val bucketId = item.parentId ?: LOCAL_BUCKET_UNKNOWN
                grouped.getOrPut(bucketId) { mutableListOf() }.add(item)
                if (item.isVideo) videos.add(item)
            }

            val albumsById = LinkedHashMap<String, DriveFolder>()
            for ((bucketId, items) in grouped) {
                val newest = items.first()
                val fullStoragePath = newest.storagePath
                    ?.trim()
                    ?.trim('/')
                    ?.takeIf { it.isNotBlank() }
                val folderName = newest.relativePath
                    ?.trim()
                    ?.trim('/')
                    ?.substringAfterLast('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: fullStoragePath?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                    ?: "Device Folder"
                albumsById[bucketId] = DriveFolder(
                    id = bucketId,
                    parentId = null,
                    name = folderName,
                    subfolderCount = 0,
                    mediaCount = items.size,
                    coverThumbnailLink = newest.localContentUri,
                    coverItemId = newest.id,
                    modifiedTimeEpochMs = newest.createdTimeEpochMs,
                    isPinned = false,
                    isLocal = true,
                    coverIsVideo = newest.isVideo,
                    coverSizeBytes = newest.sizeBytes,
                    relativePath = fullStoragePath ?: folderName
                )
            }

            // Mount hidden locked .folders directly from disk (invisible to MediaStore & File Explorer,
            // kept out of `allItems`/`videos` so they never leak into Pictures, Search, or Stories!)
            for (entity in diskHiddenAlbums) {
                val hiddenItems = hiddenFolderManager.scanHiddenFolderMedia(entity)
                grouped[entity.albumId] = hiddenItems.toMutableList()
                albumsById[entity.albumId] = hiddenFolderManager.buildHiddenAlbumFolder(entity, hiddenItems)
            }

            val albums = albumsById.values.sortedWith(
                compareBy<DriveFolder> { albumPriority(it.name) }
                    .thenByDescending { it.modifiedTimeEpochMs }
            )

            latestAllMedia = allItems
            latestVideosMedia = videos
            latestMediaByBucket = grouped
            latestLocalAlbums = albums

            IndexedLocalMedia(
                allItems = allItems,
                videos = videos,
                byBucket = grouped,
                albums = albums
            )
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = repoScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L, replayExpirationMillis = 15_000L),
            replay = 1
        )

    private val sharedAllLocalMedia: Flow<List<MediaItem>> = sharedIndexedLocalMedia.map { it.allItems }

    private val sharedSystemFavoriteIds: Flow<Set<String>> = sharedMediaStoreTriggers
        .conflate()
        .map {
            if (!hasMediaPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                emptySet()
            } else {
                querySystemFavoriteIds()
            }
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = repoScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L, replayExpirationMillis = 15_000L),
            replay = 1
        )

    private val sharedLocalAlbums: Flow<List<DriveFolder>> = sharedIndexedLocalMedia.map { it.albums }

    fun notifyPermissionChanged() {
        permissionRefreshTrigger.value++
    }

    fun requiredMediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun hasMediaLocationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    fun hasPartialMediaPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val granted = PackageManager.PERMISSION_GRANTED
        val hasFullImages = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == granted
        val hasFullVideos = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == granted
        val hasSelected = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == granted
        return hasSelected && !hasFullImages && !hasFullVideos
    }

    fun hasMediaPermission(): Boolean {
        val granted = PackageManager.PERMISSION_GRANTED
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == granted ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == granted ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == granted
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == granted ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == granted
            }
            else -> {
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == granted
            }
        }
    }

    fun observeAllLocalMedia(): Flow<List<MediaItem>> = sharedAllLocalMedia

    fun observeTrashedLocalMedia(): Flow<List<MediaItem>> = sharedMediaStoreTriggers
        .conflate()
        .map {
            if (!hasMediaPermission() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                emptyList()
            } else {
                queryLocalMedia(trashedOnly = true)
            }
        }
        .flowOn(Dispatchers.IO)

    fun observeSystemFavoriteLocalIds(): Flow<Set<String>> = sharedSystemFavoriteIds

    /**
     * Samsung Gallery style: real folder albums ONLY (no artificial separate "Videos" album;
     * "Videos" lives cleanly inside the ≡ Menu sheet).
     */
    fun observeLocalAlbums(): Flow<List<DriveFolder>> = sharedLocalAlbums

    fun observeLocalAlbumMedia(bucketId: String): Flow<List<MediaItem>> =
        sharedIndexedLocalMedia.map { indexed ->
            when (bucketId) {
                LOCAL_BUCKET_ALL -> indexed.allItems
                LOCAL_BUCKET_VIDEOS -> indexed.videos
                else -> indexed.byBucket[bucketId].orEmpty()
            }
        }

    fun searchLocalMedia(
        query: String,
        mediaType: MediaType?,
        fromEpochMs: Long?,
        toEpochMs: Long?
    ): Flow<List<MediaItem>> = observeAllLocalMedia().map { allItems ->
        val q = query.trim()
        allItems.filter { item ->
            val matchesQuery = q.isEmpty() ||
                item.name.contains(q, ignoreCase = true) ||
                (item.relativePath?.contains(q, ignoreCase = true) == true)
            val matchesType = mediaType == null || item.mediaType == mediaType
            val matchesFrom = fromEpochMs == null || item.createdTimeEpochMs >= fromEpochMs
            val matchesTo = toEpochMs == null || item.createdTimeEpochMs <= toEpochMs
            matchesQuery && matchesType && matchesFrom && matchesTo
        }
    }.flowOn(Dispatchers.Default)

    suspend fun readLocalExifAndPath(
        item: MediaItem,
        resolveAddress: Boolean = false
    ): Pair<ExifMetadata, String> =
        withContext(Dispatchers.IO) {
            val displayPath = item.relativePath?.trimEnd('/')?.ifBlank { "Internal Storage" }
                ?: "Internal Storage"
            val fullPathLabel = "On Device / $displayPath"
            val uriStr = item.localContentUri
            if (uriStr.isNullOrBlank()) {
                return@withContext item.exif to fullPathLabel
            }

            if (item.isVideo) {
                val videoMeta = readLocalVideoMetadata(item, uriStr, resolveAddress)
                return@withContext videoMeta to fullPathLabel
            }

            val exifMeta = runCatching {
                val parsed = Uri.parse(uriStr)
                val originalUri = if (
                    parsed.scheme == "content" &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    hasMediaLocationPermission()
                ) {
                    runCatching { MediaStore.setRequireOriginal(parsed) }.getOrDefault(parsed)
                } else {
                    parsed
                }
                val stream = runCatching { context.contentResolver.openInputStream(originalUri) }.getOrNull()
                    ?: context.contentResolver.openInputStream(parsed)
                stream?.use { input ->
                    val exif = ExifInterface(input)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
                    val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
                    val lensMake = exif.getAttribute(ExifInterface.TAG_LENS_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
                    val lens = when {
                        lensModel != null && lensMake != null && !lensModel.startsWith(lensMake, ignoreCase = true) ->
                            "$lensMake $lensModel"
                        lensModel != null -> lensModel
                        else -> null
                    }
                    val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
                        .takeIf { it > 0.0 }
                        ?.toFloat()
                        ?: exif.getAttributeDouble(ExifInterface.TAG_APERTURE_VALUE, -1.0)
                            .takeIf { it > 0.0 }
                            ?.let { apex -> Math.pow(2.0, apex / 2.0).toFloat() }
                    val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
                        .takeIf { it > 0.0 }?.toFloat()
                    val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                        .takeIf { it > 0 }
                        ?: exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED, -1).takeIf { it > 0 }
                    val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                        .takeIf { it > 0.0 }?.toFloat()
                    val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, -1)
                        .takeIf { it > 0 }
                    val exposureBias = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Double.NaN)
                        .takeIf { it.isFinite() }?.toFloat()
                    val flashRaw = exif.getAttributeInt(ExifInterface.TAG_FLASH, -1)
                    val flashDesc = decodeFlashTag(flashRaw)
                    val wbRaw = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)
                    val whiteBalance = when (wbRaw) {
                        ExifInterface.WHITE_BALANCE_AUTO.toInt() -> "Auto"
                        ExifInterface.WHITE_BALANCE_MANUAL.toInt() -> "Manual"
                        else -> null
                    }
                    val meteringRaw = exif.getAttributeInt(ExifInterface.TAG_METERING_MODE, -1)
                    val meteringMode = decodeMeteringMode(meteringRaw)
                    val colorSpaceRaw = exif.getAttributeInt(ExifInterface.TAG_COLOR_SPACE, -1)
                    val colorSpace = when (colorSpaceRaw) {
                        ExifInterface.COLOR_SPACE_S_RGB -> "sRGB"
                        ExifInterface.COLOR_SPACE_UNCALIBRATED -> "Wide / P3"
                        else -> null
                    }
                    val digitalZoom = exif.getAttributeDouble(ExifInterface.TAG_DIGITAL_ZOOM_RATIO, -1.0)
                        .takeIf { it > 1.05 && it < 200.0 }?.toFloat()
                    val software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.takeIf { it.isNotEmpty() }
                    val dateOrig = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.trim()?.takeIf { it.isNotEmpty() }
                    val orientationLabel = inferOrientationLabel(item.effectiveWidth, item.effectiveHeight)

                    val coords = exif.latLong
                    val lat = coords?.getOrNull(0)?.takeIf { it.isFinite() && it in -90.0..90.0 }
                    val lon = coords?.getOrNull(1)?.takeIf { it.isFinite() && it in -180.0..180.0 }
                    val hasValidCoords = lat != null && lon != null && (lat != 0.0 || lon != 0.0)
                    val altitude = if (hasValidCoords && exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null) {
                        exif.getAltitude(Double.NaN).takeIf { it.isFinite() && it in -500.0..15_000.0 }
                    } else null
                    val address = if (lat != null && lon != null && hasValidCoords && resolveAddress) {
                        resolveDetailedAddress(lat, lon)
                    } else null

                    ExifMetadata(
                        cameraMake = make,
                        cameraModel = model,
                        lens = lens,
                        aperture = aperture,
                        exposureTime = exposure,
                        isoSpeed = iso,
                        focalLength = focal,
                        dateTakenDisplay = item.fullDateLabel,
                        focalLength35mm = focal35,
                        exposureBiasEv = exposureBias,
                        flashDescription = flashDesc,
                        whiteBalance = whiteBalance,
                        meteringMode = meteringMode,
                        colorSpace = colorSpace,
                        digitalZoomRatio = digitalZoom,
                        software = software,
                        orientationLabel = orientationLabel,
                        exifDateOriginal = dateOrig,
                        latitude = if (hasValidCoords) lat else null,
                        longitude = if (hasValidCoords) lon else null,
                        altitudeMeters = altitude,
                        locationAddress = address
                    )
                }
            }.getOrNull() ?: item.exif

            exifMeta to fullPathLabel
        }

    private suspend fun readLocalVideoMetadata(
        item: MediaItem,
        uriStr: String,
        resolveAddress: Boolean
    ): ExifMetadata {
        val parsed = Uri.parse(uriStr)
        val originalUri = if (
            parsed.scheme == "content" &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            hasMediaLocationPermission()
        ) {
            runCatching { MediaStore.setRequireOriginal(parsed) }.getOrDefault(parsed)
        } else {
            parsed
        }
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                if (originalUri.scheme == "file" && !originalUri.path.isNullOrBlank()) {
                    retriever.setDataSource(originalUri.path)
                } else {
                    retriever.setDataSource(context, originalUri)
                }
                val locStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                val latLon = locStr?.let(::parseIso6709)
                val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull()?.takeIf { it > 0L }
                val captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                    ?.toFloatOrNull()?.takeIf { it > 0f && it <= 480f }
                val computedFps = if (captureFps != null) {
                    captureFps
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val frames = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toLongOrNull()
                    val durMs = item.durationMillis
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    if (frames != null && frames > 0L && durMs != null && durMs >= 500L) {
                        (frames.toDouble() / (durMs / 1000.0)).toFloat().takeIf { it in 1f..480f }
                    } else null
                } else null
                val rawMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                val codecLabel = when {
                    rawMime?.contains("hevc", ignoreCase = true) == true -> "HEVC (H.265)"
                    rawMime?.contains("avc", ignoreCase = true) == true -> "H.264 / AVC"
                    rawMime?.contains("vp9", ignoreCase = true) == true -> "VP9"
                    rawMime?.contains("av01", ignoreCase = true) == true -> "AV1"
                    rawMime?.contains("mp4", ignoreCase = true) == true -> "MP4"
                    rawMime?.contains("webm", ignoreCase = true) == true -> "WebM"
                    rawMime?.contains("quicktime", ignoreCase = true) == true -> "QuickTime MOV"
                    rawMime?.contains("matroska", ignoreCase = true) == true -> "Matroska MKV"
                    !rawMime.isNullOrBlank() -> rawMime.substringAfter('/').uppercase(Locale.US)
                    else -> null
                }
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                val sampleRateHz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()
                } else null
                val audioSummary = when {
                    hasAudio.equals("yes", ignoreCase = true) && sampleRateHz != null && sampleRateHz > 0 ->
                        String.format(Locale.US, "%.1f kHz audio", sampleRateHz / 1000.0)
                    hasAudio.equals("yes", ignoreCase = true) -> "Audio included"
                    hasAudio != null -> "No audio"
                    else -> null
                }
                val address = if (latLon != null && resolveAddress) {
                    resolveDetailedAddress(latLon.first, latLon.second)
                } else null

                item.exif.copy(
                    dateTakenDisplay = item.fullDateLabel,
                    orientationLabel = inferOrientationLabel(item.effectiveWidth, item.effectiveHeight),
                    latitude = latLon?.first,
                    longitude = latLon?.second,
                    locationAddress = address,
                    videoFrameRate = computedFps,
                    videoBitrateBps = bitrate,
                    videoCodec = codecLabel,
                    audioSummary = audioSummary
                )
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(item.exif)
    }

    @Suppress("DEPRECATION")
    suspend fun resolveDetailedAddress(lat: Double, lon: Double): String? = withContext(Dispatchers.IO) {
        if (lat == 0.0 && lon == 0.0) return@withContext null
        val fineKey = "${String.format(Locale.US, "%.4f", lat)},${String.format(Locale.US, "%.4f", lon)}"
        detailedAddressCache[fineKey]?.let { return@withContext it }

        val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
        val addr = runCatching { geocoder?.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
        if (addr != null) {
            val street = listOfNotNull(
                addr.thoroughfare?.takeIf { it.isNotBlank() },
                addr.subLocality?.takeIf { it.isNotBlank() && it != addr.thoroughfare }
            ).firstOrNull()
            val city = addr.locality?.takeIf { it.isNotBlank() }
                ?: addr.subAdminArea?.takeIf { it.isNotBlank() }
                ?: addr.adminArea?.takeIf { it.isNotBlank() }
            val country = addr.countryName?.takeIf { it.isNotBlank() }
            val formatted = listOfNotNull(street, city, country)
                .distinct()
                .joinToString(", ")
                .trim()
            if (formatted.isNotEmpty()) {
                if (detailedAddressCache.size > 1_000) detailedAddressCache.clear()
                detailedAddressCache[fineKey] = formatted
                return@withContext formatted
            }
        }
        val (city, country, resolved) = resolveCityAndCountryWithStatus(lat, lon)
        if (resolved) {
            listOf(city, country).filter { it.isNotBlank() && it != "Other" }.distinct().joinToString(", ")
                .takeIf { it.isNotBlank() }
        } else {
            null
        }
    }

    private fun decodeFlashTag(flashRaw: Int): String? {
        if (flashRaw < 0) return null
        val fired = (flashRaw and 0x1) != 0
        val mode = (flashRaw ushr 3) and 0x3
        val noFlashFunction = (flashRaw and 0x20) != 0
        if (noFlashFunction) return "No flash"
        return when (mode) {
            1 -> if (fired) "Forced · Fired" else "Forced · Off"
            2 -> "Off"
            3 -> if (fired) "Auto · Fired" else "Auto · Off"
            else -> if (fired) "Fired" else "Off"
        }
    }

    private fun decodeMeteringMode(raw: Int): String? = when (raw) {
        ExifInterface.METERING_MODE_AVERAGE.toInt() -> "Average"
        ExifInterface.METERING_MODE_CENTER_WEIGHT_AVERAGE.toInt() -> "Center-weighted"
        ExifInterface.METERING_MODE_SPOT.toInt() -> "Spot"
        ExifInterface.METERING_MODE_MULTI_SPOT.toInt() -> "Multi-spot"
        ExifInterface.METERING_MODE_PATTERN.toInt() -> "Pattern"
        ExifInterface.METERING_MODE_PARTIAL.toInt() -> "Partial"
        else -> null
    }

    private fun inferOrientationLabel(width: Int?, height: Int?): String? {
        if (width == null || height == null || width <= 0 || height <= 0) return null
        return when {
            width > height -> "Landscape"
            height > width -> "Portrait"
            else -> "Square"
        }
    }

    /**
     * Scans EXIF GPS coordinates across recent photos and reverse-geocodes clusters into
     * clean city/region Location Groups (used by Locations screen and Trips in Stories!).
     */
    suspend fun scanLocationGroups(
        items: List<MediaItem>,
        maxPhotosToScan: Int = 350
    ): List<LocationPhotoGroup> = withContext(Dispatchers.IO) {
        val candidates = items.asSequence()
            .filter { it.isLocal && !it.isVideo && !it.localContentUri.isNullOrBlank() }
            .take(maxPhotosToScan)
            .toList()

        val clustered = LinkedHashMap<String, Triple<Double, Double, MutableList<MediaItem>>>()

        for (item in candidates) {
            val latLon = readLatLon(item.localContentUri!!) ?: continue
            val lat = latLon.first
            val lon = latLon.second
            if (lat == 0.0 && lon == 0.0) continue

            // ~15 km spatial clustering bucket (~0.15 deg)
            val bucketLat = (lat * 6.5).roundToInt() / 6.5
            val bucketLon = (lon * 6.5).roundToInt() / 6.5
            val key = "${String.format(Locale.US, "%.2f", bucketLat)},${String.format(Locale.US, "%.2f", bucketLon)}"

            val existing = clustered[key]
            if (existing != null) {
                existing.third.add(item)
            } else {
                clustered[key] = Triple(lat, lon, mutableListOf(item))
            }
        }

        val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null

        clustered.entries.map { (key, triple) ->
            val (lat, lon, groupItems) = triple
            val (city, country) = resolvePlaceName(geocoder, key, lat, lon)
            LocationPhotoGroup(
                locationKey = key,
                displayName = city,
                subtitle = country,
                latitude = lat,
                longitude = lon,
                coverItem = groupItems.first(),
                items = groupItems
            )
        }.sortedByDescending { it.items.size }
    }

    /**
     * Fast progressive per-day location resolver for the Samsung Pictures timeline:
     * Samples up to 4 non-screenshot photos per day, reads original EXIF GPS (via
     * `MediaStore.setRequireOriginal`), and reverse-geocodes using our spatial city cache.
     */
    fun resolveLocationsByDayFlow(
        items: List<MediaItem>,
        maxDaysToScan: Int = 180
    ): Flow<Map<String, String>> = flow {
        val localPhotosByDay = LinkedHashMap<String, MutableList<MediaItem>>()
        for (item in items) {
            if (!item.isLocal || item.isVideo || item.localContentUri.isNullOrBlank()) continue
            val list = localPhotosByDay.getOrPut(item.daySectionLabel) { mutableListOf() }
            if (list.size < 8) list.add(item)
            if (localPhotosByDay.size >= maxDaysToScan && list.size >= 8) {
                // Keep populating already-opened buckets only
                continue
            }
        }
        if (localPhotosByDay.isEmpty()) {
            emit(emptyMap())
            return@flow
        }

        val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
        val dayLocations = LinkedHashMap<String, String>()
        var newlyResolved = 0

        for ((dayLabel, dayPhotos) in localPhotosByDay) {
            // Prefer real Camera photos over screenshots/WhatsApp which strip EXIF GPS
            val orderedCandidates = dayPhotos.sortedBy { photo ->
                val p = photo.relativePath.orEmpty()
                when {
                    p.equals("Camera", ignoreCase = true) -> 0
                    p.contains("screenshot", ignoreCase = true) -> 3
                    p.contains("whatsapp", ignoreCase = true) -> 2
                    else -> 1
                }
            }.take(4)

            val citiesForDay = LinkedHashSet<String>(2)
            for (photo in orderedCandidates) {
                val latLon = readLatLon(photo.localContentUri!!) ?: continue
                val (lat, lon) = latLon
                if (lat == 0.0 && lon == 0.0) continue

                val bucketLat = (lat * 6.5).roundToInt() / 6.5
                val bucketLon = (lon * 6.5).roundToInt() / 6.5
                val coordKey = "${String.format(Locale.US, "%.2f", bucketLat)},${String.format(Locale.US, "%.2f", bucketLon)}"
                val (city, _) = resolvePlaceName(geocoder, coordKey, lat, lon)
                if (!city.startsWith("Location")) {
                    citiesForDay.add(city)
                    if (citiesForDay.size >= 2) break
                }
            }

            if (citiesForDay.isNotEmpty()) {
                dayLocations[dayLabel] = citiesForDay.joinToString(", ")
                newlyResolved++
                if (newlyResolved <= 3 || newlyResolved % 6 == 0) {
                    emit(HashMap(dayLocations))
                }
            }
        }

        emit(HashMap(dayLocations))
    }.flowOn(Dispatchers.IO)

    /**
     * Smart Samsung-style Clean Out scanner:
     *  1. Large videos & files (> 50 MB)
     *  2. True exact duplicate files (same size + SHA-256 content header/tail fingerprint)
     *  3. Similar burst shots taken within 2 seconds of each other
     *  4. Old screenshots (> 30 days old)
     */
    suspend fun scanCleanOutSuggestions(
        items: List<MediaItem>,
        ignoredIds: Set<String> = emptySet()
    ): List<CleanOutGroup> =
        withContext(Dispatchers.IO) {
            val localOnly = items.filter { it.isLocal && it.id !in ignoredIds }
            if (localOnly.isEmpty()) return@withContext emptyList()

            val groups = mutableListOf<CleanOutGroup>()

            // 1. Large files (> 50 MB)
            val largeFiles = localOnly
                .filter { it.sizeBytes >= LARGE_FILE_THRESHOLD_BYTES }
                .sortedByDescending { it.sizeBytes }
            if (largeFiles.isNotEmpty()) {
                groups.add(
                    CleanOutGroup(
                        category = CleanOutCategory.LARGE_FILES,
                        items = largeFiles,
                        reclaimableBytes = largeFiles.sumOf { it.sizeBytes },
                        preselectedIds = emptySet()
                    )
                )
            }

            // 2. Exact Duplicates (same byte size -> verified by FULL streaming SHA-256 hash so
            // container/EXIF headers can NEVER produce false-positive deletion suggestions)
            val duplicateItems = mutableListOf<MediaItem>()
            val duplicateClusters = mutableListOf<DuplicateCluster>()
            val preselectedDuplicates = LinkedHashSet<String>()
            val sizeBuckets = localOnly.filter { it.sizeBytes > 4096L }.groupBy { it.sizeBytes }
            for ((sizeBytes, sameSizeList) in sizeBuckets) {
                if (sameSizeList.size < 2) continue
                val byHash = sameSizeList.groupBy { item ->
                    computeExactContentSha256(item.localContentUri) ?: item.id
                }
                for ((hash, dupes) in byHash) {
                    if (dupes.size >= 2) {
                        val ordered = dupes.sortedBy { it.createdTimeEpochMs }
                        duplicateItems.addAll(ordered)
                        duplicateClusters.add(
                            DuplicateCluster(
                                clusterKey = "${sizeBytes}_$hash",
                                sizeBytes = sizeBytes,
                                items = ordered
                            )
                        )
                        // Keep the oldest original, pre-select redundant copies
                        ordered.drop(1).forEach { preselectedDuplicates.add(it.id) }
                    }
                }
            }
            if (duplicateItems.isNotEmpty()) {
                val sortedClusters = duplicateClusters.sortedByDescending { it.sizeBytes * (it.items.size - 1) }
                val savings = duplicateItems
                    .filter { it.id in preselectedDuplicates }
                    .sumOf { it.sizeBytes }
                groups.add(
                    CleanOutGroup(
                        category = CleanOutCategory.DUPLICATES,
                        items = sortedClusters.flatMap { it.items },
                        reclaimableBytes = savings,
                        preselectedIds = preselectedDuplicates,
                        duplicateClusters = sortedClusters
                    )
                )
            }

            // 3. Similar Burst Shots (photos taken within 2 seconds with same dimensions)
            val photosSorted = localOnly
                .filter { !it.isVideo && it.createdTimeEpochMs > 0L }
                .sortedByDescending { it.createdTimeEpochMs }
            val burstItems = mutableListOf<MediaItem>()
            val preselectedBursts = LinkedHashSet<String>()
            var idx = 0
            while (idx < photosSorted.size) {
                val cluster = mutableListOf(photosSorted[idx])
                var next = idx + 1
                while (next < photosSorted.size) {
                    val prev = cluster.last()
                    val candidate = photosSorted[next]
                    val withinWindow = abs(prev.createdTimeEpochMs - candidate.createdTimeEpochMs) <= 2_000L
                    val sameDims = prev.width == candidate.width && prev.height == candidate.height
                    if (withinWindow && sameDims) {
                        cluster.add(candidate)
                        next++
                    } else {
                        break
                    }
                }
                if (cluster.size >= 3) {
                    burstItems.addAll(cluster)
                    // Keep first shot in burst, suggest extras for review
                    cluster.drop(1).forEach { preselectedBursts.add(it.id) }
                }
                idx = next
            }
            if (burstItems.isNotEmpty()) {
                groups.add(
                    CleanOutGroup(
                        category = CleanOutCategory.BURST_SIMILAR,
                        items = burstItems,
                        reclaimableBytes = burstItems.filter { it.id in preselectedBursts }.sumOf { it.sizeBytes },
                        preselectedIds = preselectedBursts
                    )
                )
            }

            // 4. Old Screenshots (> 30 days)
            val thirtyDaysAgoMs = System.currentTimeMillis() - 30L * 86_400_000L
            val oldScreenshots = localOnly.filter { item ->
                val isScreenshot = item.relativePath?.contains("screenshot", ignoreCase = true) == true ||
                    item.name.contains("screenshot", ignoreCase = true)
                isScreenshot && item.createdTimeEpochMs in 1L..thirtyDaysAgoMs
            }
            if (oldScreenshots.isNotEmpty()) {
                groups.add(
                    CleanOutGroup(
                        category = CleanOutCategory.OLD_SCREENSHOTS,
                        items = oldScreenshots,
                        reclaimableBytes = oldScreenshots.sumOf { it.sizeBytes },
                        preselectedIds = oldScreenshots.mapTo(LinkedHashSet()) { it.id }
                    )
                )
            }

            groups
        }

    fun supportsManageMediaPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun canManageMediaDirectly(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(context)

    fun canDeleteDirectlyWithoutDialog(): Boolean =
        canManageMediaDirectly() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    fun supportsMediaStoreTrash(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun buildFavoriteIntentSender(item: MediaItem, favorite: Boolean): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!item.isLocal || item.localContentUri.isNullOrBlank()) return null
        if (HiddenLockedFolderManager.isHiddenFileUri(item.localContentUri)) return null
        val uri = Uri.parse(item.localContentUri)
        return runCatching {
            MediaStore.createFavoriteRequest(context.contentResolver, listOf(uri), favorite).intentSender
        }.getOrNull()
    }

    suspend fun setLocalFavoriteDirectly(item: MediaItem, favorite: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext false
            if (!item.isLocal || item.localContentUri.isNullOrBlank()) return@withContext false
            if (HiddenLockedFolderManager.isHiddenFileUri(item.localContentUri)) return@withContext false
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0)
                }
                context.contentResolver.update(Uri.parse(item.localContentUri), values, null, null) > 0
            }.getOrDefault(false)
        }

    fun buildManageMediaSettingsIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_MANAGE_MEDIA,
            Uri.parse("package:${context.packageName}")
        )
    }

    /**
     * Moves local items to/from MediaStore Trash directly without showing Android's system dialog
     * when the user has granted Android 12+ Media Management permission (`MANAGE_MEDIA`).
     */
    suspend fun trashLocalItemsDirectly(
        items: List<MediaItem>,
        toTrash: Boolean = true
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val (hiddenItems, mediaStoreItems) = items.partition {
                HiddenLockedFolderManager.isHiddenFileUri(it.localContentUri)
            }
            var count = 0
            if (hiddenItems.isNotEmpty() && toTrash) {
                count += hiddenFolderManager.deleteHiddenFilesDirectly(hiddenItems).getOrThrow()
            }
            if (mediaStoreItems.isNotEmpty()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_TRASHED, if (toTrash) 1 else 0)
                    }
                    for (item in mediaStoreItems) {
                        if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                        val uri = Uri.parse(item.localContentUri)
                        val updated = context.contentResolver.update(uri, values, null, null)
                        if (updated > 0) count++
                    }
                } else if (toTrash) {
                    // Android 8-10 has no system Trash bin — delete directly on confirmed request
                    for (item in mediaStoreItems) {
                        if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                        val uri = Uri.parse(item.localContentUri)
                        val deleted = context.contentResolver.delete(uri, null, null)
                        if (deleted > 0) count++
                    }
                }
            }
            if (count == 0) {
                throw IllegalStateException("Android did not allow direct media trash")
            }
            count
        }
    }

    /**
     * Permanently deletes local items directly without Android's system dialog when `MANAGE_MEDIA`
     * is granted (or when items reside in a disk-hidden locked `.folder`).
     */
    suspend fun deleteLocalItemsPermanentlyDirectly(
        items: List<MediaItem>
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val (hiddenItems, mediaStoreItems) = items.partition {
                HiddenLockedFolderManager.isHiddenFileUri(it.localContentUri)
            }
            var count = 0
            if (hiddenItems.isNotEmpty()) {
                count += hiddenFolderManager.deleteHiddenFilesDirectly(hiddenItems).getOrThrow()
            }
            for (item in mediaStoreItems) {
                if (!item.isLocal || item.localContentUri.isNullOrBlank()) continue
                val uri = Uri.parse(item.localContentUri)
                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0) count++
            }
            if (count == 0) {
                throw IllegalStateException("Android did not allow direct permanent delete")
            }
            count
        }
    }

    fun buildTrashIntentSender(items: List<MediaItem>, toTrash: Boolean = true): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = items.mapNotNull { item ->
            if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
                Uri.parse(item.localContentUri)
            } else null
        }
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createTrashRequest(context.contentResolver, uris, toTrash).intentSender
        }.getOrNull()
    }

    fun buildPermanentDeleteIntentSender(items: List<MediaItem>): IntentSender? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val uris = items.mapNotNull { item ->
            if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
                Uri.parse(item.localContentUri)
            } else null
        }
        if (uris.isEmpty()) return null
        return runCatching {
            MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
        }.getOrNull()
    }

    fun readMediaLatLon(item: MediaItem): Pair<Double, Double>? {
        val uriStr = item.localContentUri ?: return null
        return if (item.isVideo) {
            readVideoLatLon(uriStr)
        } else {
            readLatLon(uriStr)
        }
    }

    @Suppress("DEPRECATION")
    fun resolveCityAndCountry(lat: Double, lon: Double): Pair<String, String> {
        val (city, country, _) = resolveCityAndCountryWithStatus(lat, lon)
        return city to country
    }

    @Suppress("DEPRECATION")
    fun resolveCityAndCountryWithStatus(lat: Double, lon: Double): Triple<String, String, Boolean> {
        val bucketLat = (lat * 10.0).roundToInt() / 10.0
        val bucketLon = (lon * 10.0).roundToInt() / 10.0
        val key = "${String.format(Locale.US, "%.2f", bucketLat)},${String.format(Locale.US, "%.2f", bucketLon)}"
        geocodeCache[key]?.let { return Triple(it.first, it.second, true) }

        val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
        val addr = runCatching { geocoder?.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
        val city = addr?.locality
            ?: addr?.subAdminArea
            ?: addr?.adminArea
            ?: addr?.countryName
        val country = addr?.countryName?.takeIf { it.isNotBlank() }

        if (!city.isNullOrBlank()) {
            val pair = city to (country ?: "Other")
            if (geocodeCache.size > 2_000) geocodeCache.clear()
            geocodeCache[key] = pair
            return Triple(pair.first, pair.second, true)
        }
        // Do NOT poison geocodeCache when offline / throttled so it resolves cleanly once online!
        return Triple(String.format(Locale.US, "%.2f°, %.2f°", lat, lon), "Other", false)
    }

    private fun readVideoLatLon(contentUriStr: String): Pair<Double, Double>? = runCatching {
        val parsed = Uri.parse(contentUriStr)
        val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLocationPermission()) {
            runCatching { MediaStore.setRequireOriginal(parsed) }.getOrDefault(parsed)
        } else parsed
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, originalUri)
            val loc = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                ?: return@runCatching null
            parseIso6709(loc)
        } finally {
            runCatching { retriever.release() }
        }
    }.getOrNull()

    private fun parseIso6709(iso: String): Pair<Double, Double>? {
        val match = ISO_6709_REGEX.find(iso.trim()) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lon = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }

    private fun readLatLon(contentUriStr: String): Pair<Double, Double>? = runCatching {
        val parsed = Uri.parse(contentUriStr)
        val originalUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMediaLocationPermission()) {
            runCatching { MediaStore.setRequireOriginal(parsed) }.getOrDefault(parsed)
        } else {
            parsed
        }
        val stream = runCatching { context.contentResolver.openInputStream(originalUri) }.getOrNull()
            ?: context.contentResolver.openInputStream(parsed)
        stream?.use { input ->
            val exif = ExifInterface(input)
            val coords = exif.latLong ?: return@runCatching null
            coords[0] to coords[1]
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun resolvePlaceName(
        geocoder: Geocoder?,
        key: String,
        lat: Double,
        lon: Double
    ): Pair<String, String> {
        geocodeCache[key]?.let { return it }
        val addr = runCatching { geocoder?.getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
        val city = addr?.locality
            ?: addr?.subAdminArea
            ?: addr?.adminArea
            ?: addr?.countryName
        if (!city.isNullOrBlank()) {
            val country = listOfNotNull(addr?.adminArea?.takeIf { it != city }, addr?.countryName)
                .distinct()
                .joinToString(", ")
                .ifBlank { String.format(Locale.US, "%.2f°, %.2f°", lat, lon) }
            val resolved = city to country
            if (geocodeCache.size > 2_000) geocodeCache.clear()
            geocodeCache[key] = resolved
            return resolved
        }
        return "Location" to String.format(Locale.US, "%.2f°, %.2f°", lat, lon)
    }

    private fun computeExactContentSha256(contentUriStr: String?): String? {
        if (contentUriStr.isNullOrBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(contentUriStr))?.use { stream ->
                hashStreamSha256(stream)
            }
        }.getOrNull()
    }

    private fun observeMediaStoreChanges(): Flow<Unit> = callbackFlow {
        trySend(Unit)
        val mainHandler = Handler(Looper.getMainLooper())
        val coalescedEmit = Runnable { trySend(Unit) }
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.removeCallbacks(coalescedEmit)
                mainHandler.postDelayed(coalescedEmit, 250L)
            }
        }
        val resolver = context.contentResolver
        runCatching {
            resolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
            resolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, observer)
        }
        awaitClose {
            mainHandler.removeCallbacks(coalescedEmit)
            runCatching { resolver.unregisterContentObserver(observer) }
        }
    }

    private fun querySystemFavoriteIds(): Set<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptySet()
        val ids = HashSet<String>()
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.MediaColumns.IS_FAVORITE} = 1 AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        runCatching {
            context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                while (c.moveToNext()) {
                    ids.add("$LOCAL_ITEM_PREFIX${c.getLong(idCol)}")
                }
            }
        }
        return ids
    }

    private fun queryLocalMedia(trashedOnly: Boolean): List<MediaItem> {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.DISPLAY_NAME)
            add(MediaStore.Files.FileColumns.MIME_TYPE)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.Files.FileColumns.SIZE)
            add(MediaStore.Files.FileColumns.DATE_TAKEN)
            add(MediaStore.Files.FileColumns.DATE_MODIFIED)
            add(MediaStore.Files.FileColumns.WIDTH)
            add(MediaStore.Files.FileColumns.HEIGHT)
            add(MediaStore.Files.FileColumns.ORIENTATION)
            add(MediaStore.Video.VideoColumns.DURATION)
            add(MediaStore.Images.ImageColumns.BUCKET_ID)
            add(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()

        val fallbackExts = RAW_EXTS + com.luminagallery.drive.data.repository.VideoFormatSupport.MEDIASTORE_FALLBACK_VIDEO_EXTS
        val extraLikes = fallbackExts.joinToString(" OR ") { ext ->
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.$ext'"
        }
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) OR (${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} AND ($extraLikes))) AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val results = ArrayList<MediaItem>(256)
        val zone = ZoneId.systemDefault()
        val nowEpochMs = System.currentTimeMillis()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val dateLabelCache = HashMap<Long, Pair<String, String>>(128)

        val cursor = runCatching {
            if (trashedOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val queryArgs = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                }
                context.contentResolver.query(collection, projection, queryArgs, null)
            } else {
                context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            }
        }.getOrNull()

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val mediaTypeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val dateTakenCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_TAKEN)
            val dateModCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val widthCol = c.getColumnIndex(MediaStore.Files.FileColumns.WIDTH)
            val heightCol = c.getColumnIndex(MediaStore.Files.FileColumns.HEIGHT)
            val orientCol = c.getColumnIndex(MediaStore.Files.FileColumns.ORIENTATION)
            val durationCol = c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
            val bucketIdCol = c.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)
            val bucketNameCol = c.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            } else -1

            while (c.moveToNext()) {
                val mediaStoreId = c.getLong(idCol)
                val fileMediaType = c.getInt(mediaTypeCol)
                val name = c.getString(nameCol) ?: "Media_$mediaStoreId"
                val rawMime = c.getString(mimeCol)
                val isVideo = fileMediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                    com.luminagallery.drive.data.repository.VideoFormatSupport.isVideoFile(name, rawMime)
                val mime = if (isVideo) {
                    com.luminagallery.drive.data.repository.VideoFormatSupport.normalizeVideoMimeType(name, rawMime)
                } else {
                    rawMime ?: "image/jpeg"
                }
                val sizeBytes = c.getLong(sizeCol)

                val dateTaken = if (dateTakenCol >= 0) c.getLong(dateTakenCol) else 0L
                val dateModifiedMs = c.getLong(dateModCol) * 1000L
                val resolvedTs = MediaDateResolver.resolveEffectiveTimestamp(
                    fileName = name,
                    mediaStoreDateTakenMs = dateTaken,
                    fileModifiedMs = dateModifiedMs,
                    zone = zone,
                    nowEpochMs = nowEpochMs
                )
                val epochMs = resolvedTs.epochMs

                val width = if (widthCol >= 0) c.getInt(widthCol).takeIf { it > 0 } else null
                val height = if (heightCol >= 0) c.getInt(heightCol).takeIf { it > 0 } else null
                val rotation = if (orientCol >= 0) c.getInt(orientCol) else 0
                val durationMs = if (isVideo && durationCol >= 0) {
                    c.getLong(durationCol).takeIf { it > 0L }
                } else null

                val rawBucketId = if (bucketIdCol >= 0) c.getString(bucketIdCol) else null
                val bucketDisplay = if (bucketNameCol >= 0) c.getString(bucketNameCol) else null
                val relPath = if (relPathCol >= 0) c.getString(relPathCol) else null

                val contentUri = ContentUris.withAppendedId(
                    when (fileMediaType) {
                        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    },
                    mediaStoreId
                ).toString()

                val type = when {
                    isVideo -> MediaType.VIDEO
                    isRawExtension(name, mime) -> MediaType.RAW
                    else -> MediaType.PHOTO
                }

                val zdt = Instant.ofEpochMilli(epochMs.coerceAtLeast(0L)).atZone(zone)
                val date = zdt.toLocalDate()
                val (monthYear, dayLabel) = dateLabelCache.getOrPut(date.toEpochDay()) {
                    val m = MONTH_YEAR_FMT.format(zdt)
                    val d = when (date) {
                        today -> "Today"
                        yesterday -> "Yesterday"
                        else -> if (date.year == today.year) {
                            SAME_YEAR_DAY_FMT.format(zdt)
                        } else {
                            OTHER_YEAR_DAY_FMT.format(zdt)
                        }
                    }
                    m to d
                }
                val fullDate = FULL_DATE_FMT.format(zdt)

                val folderPathDisplay = relPath?.trimEnd('/')?.takeIf { it.isNotBlank() }
                    ?: bucketDisplay ?: "Device"

                results.add(
                    MediaItem(
                        id = "$LOCAL_ITEM_PREFIX$mediaStoreId",
                        parentId = "$LOCAL_BUCKET_PREFIX${rawBucketId ?: "default"}",
                        name = name,
                        mimeType = mime,
                        mediaType = type,
                        sizeBytes = sizeBytes,
                        createdTimeEpochMs = epochMs,
                        monthYearLabel = monthYear,
                        fullDateLabel = fullDate,
                        thumbnailLink = contentUri,
                        width = width,
                        height = height,
                        rotation = rotation,
                        durationMillis = durationMs,
                        exif = ExifMetadata(
                            cameraMake = null,
                            cameraModel = null,
                            lens = null,
                            aperture = null,
                            exposureTime = null,
                            isoSpeed = null,
                            focalLength = null,
                            dateTakenDisplay = fullDate
                        ),
                        isLocal = true,
                        localContentUri = contentUri,
                        relativePath = bucketDisplay?.takeIf { it.isNotBlank() } ?: folderPathDisplay,
                        storagePath = folderPathDisplay,
                        daySectionLabel = dayLabel,
                        dateRecoveredFromFilename = resolvedTs.correctedFromFilename,
                        fileModifiedEpochMs = dateModifiedMs.coerceAtLeast(0L)
                    )
                )
            }
        }

        results.sortByDescending { it.createdTimeEpochMs }
        return results
    }

    private fun albumPriority(name: String): Int = when {
        name.equals("Camera", ignoreCase = true) -> 0
        name.equals("Screenshots", ignoreCase = true) -> 1
        name.equals("Download", ignoreCase = true) || name.equals("Downloads", ignoreCase = true) -> 2
        name.contains("WhatsApp", ignoreCase = true) -> 3
        else -> 10
    }

    private fun isRawExtension(name: String, mime: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in RAW_EXTS || mime.contains("dng", ignoreCase = true) || mime.contains("raw", ignoreCase = true)
    }

    companion object {
        const val LOCAL_ITEM_PREFIX = "local_"
        const val LOCAL_BUCKET_PREFIX = "local_bucket_"
        const val LOCAL_BUCKET_ALL = "local_bucket_all_pictures"
        const val LOCAL_BUCKET_VIDEOS = "local_bucket_all_videos"
        private const val LOCAL_BUCKET_UNKNOWN = "local_bucket_unknown"
        private const val LARGE_FILE_THRESHOLD_BYTES = 50L * 1024L * 1024L

        fun isLocalFolderId(folderId: String): Boolean = folderId.startsWith(LOCAL_BUCKET_PREFIX)

        internal fun hashStreamSha256(stream: java.io.InputStream): String? {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var totalRead = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    digest.update(buffer, 0, read)
                    totalRead += read
                }
            }
            if (totalRead <= 0L) return null
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private val RAW_EXTS = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "pef", "srw", "raw")
        private val ISO_6709_REGEX = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")
        private val MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
        private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())
        private val SAME_YEAR_DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        private val OTHER_YEAR_DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    }
}
