package com.luminagallery.drive.data.local

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
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
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
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
class LocalMediaRepository(private val context: Context) {

    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permissionRefreshTrigger = MutableStateFlow(0)
    private val geocodeCache = ConcurrentHashMap<String, Pair<String, String>>() // roundedCoord -> (city, country)

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

    private val sharedIndexedLocalMedia: Flow<IndexedLocalMedia> = sharedMediaStoreTriggers
        .conflate()
        .map {
            val allItems = if (!hasMediaPermission()) emptyList() else queryLocalMedia(trashedOnly = false)
            if (allItems.isEmpty()) {
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

            val albums = grouped.map { (bucketId, items) ->
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
                DriveFolder(
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
            }.sortedWith(
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

    suspend fun readLocalExifAndPath(item: MediaItem): Pair<ExifMetadata, String> =
        withContext(Dispatchers.IO) {
            val displayPath = item.relativePath?.trimEnd('/')?.ifBlank { "Internal Storage" }
                ?: "Internal Storage"
            if (item.isVideo || item.localContentUri.isNullOrBlank()) {
                return@withContext item.exif to "On Device / $displayPath"
            }

            val exifMeta = runCatching {
                val uri = Uri.parse(item.localContentUri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() }
                    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
                    val lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
                    val aperture = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
                        .takeIf { it > 0.0 }?.toFloat()
                    val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
                        .takeIf { it > 0.0 }?.toFloat()
                    val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
                        .takeIf { it > 0 }
                    val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                        .takeIf { it > 0.0 }?.toFloat()

                    ExifMetadata(
                        cameraMake = make,
                        cameraModel = model,
                        lens = lens,
                        aperture = aperture,
                        exposureTime = exposure,
                        isoSpeed = iso,
                        focalLength = focal,
                        dateTakenDisplay = item.fullDateLabel
                    )
                }
            }.getOrNull() ?: item.exif

            exifMeta to "On Device / $displayPath"
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

            // 2. Exact Duplicates (grouped by identical byte size, verified via fast 8KB SHA-256 hash)
            val duplicateItems = mutableListOf<MediaItem>()
            val duplicateClusters = mutableListOf<DuplicateCluster>()
            val preselectedDuplicates = LinkedHashSet<String>()
            val sizeBuckets = localOnly.filter { it.sizeBytes > 4096L }.groupBy { it.sizeBytes }
            for ((sizeBytes, sameSizeList) in sizeBuckets) {
                if (sameSizeList.size < 2) continue
                val byHash = sameSizeList.groupBy { item ->
                    computeQuickContentHash(item.localContentUri) ?: item.id
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
        val bucketLat = (lat * 10.0).roundToInt() / 10.0
        val bucketLon = (lon * 10.0).roundToInt() / 10.0
        val key = "${String.format(Locale.US, "%.2f", bucketLat)},${String.format(Locale.US, "%.2f", bucketLon)}"
        geocodeCache[key]?.let { return it }

        val geocoder = if (Geocoder.isPresent()) Geocoder(context, Locale.getDefault()) else null
        val resolved = runCatching {
            val addr = geocoder?.getFromLocation(lat, lon, 1)?.firstOrNull()
            val city = addr?.locality
                ?: addr?.subAdminArea
                ?: addr?.adminArea
                ?: addr?.countryName
                ?: String.format(Locale.US, "%.2f°, %.2f°", lat, lon)
            val country = addr?.countryName?.takeIf { it.isNotBlank() } ?: "Other"
            city to country
        }.getOrNull() ?: (String.format(Locale.US, "%.2f°, %.2f°", lat, lon) to "Other")

        geocodeCache[key] = resolved
        return resolved
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
        val resolved = runCatching {
            val addresses = geocoder?.getFromLocation(lat, lon, 1)
            val addr = addresses?.firstOrNull()
            val city = addr?.locality
                ?: addr?.subAdminArea
                ?: addr?.adminArea
                ?: addr?.countryName
                ?: "Location ($key)"
            val country = listOfNotNull(addr?.adminArea?.takeIf { it != city }, addr?.countryName)
                .distinct()
                .joinToString(", ")
                .ifBlank { String.format(Locale.US, "%.2f°, %.2f°", lat, lon) }
            city to country
        }.getOrNull() ?: ("Location" to String.format(Locale.US, "%.2f°, %.2f°", lat, lon))

        geocodeCache[key] = resolved
        return resolved
    }

    private fun computeQuickContentHash(contentUriStr: String?): String? {
        if (contentUriStr.isNullOrBlank()) return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(contentUriStr))?.use { stream ->
                val buffer = ByteArray(8192)
                val read = stream.read(buffer)
                if (read <= 0) return@runCatching null
                val digest = MessageDigest.getInstance("SHA-256").digest(buffer.copyOf(read))
                digest.joinToString("") { "%02x".format(it) }
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

        val rawLikes = RAW_EXTS.joinToString(" OR ") { ext ->
            "LOWER(${MediaStore.Files.FileColumns.DISPLAY_NAME}) LIKE '%.$ext'"
        }
        val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) OR (${MediaStore.Files.FileColumns.MEDIA_TYPE} = ${MediaStore.Files.FileColumns.MEDIA_TYPE_NONE} AND ($rawLikes))) AND ${MediaStore.Files.FileColumns.SIZE} > 0"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

        val results = ArrayList<MediaItem>(256)
        val zone = ZoneId.systemDefault()
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
                val isVideo = fileMediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val name = c.getString(nameCol) ?: "Media_$mediaStoreId"
                val mime = c.getString(mimeCol) ?: if (isVideo) "video/mp4" else "image/jpeg"
                val sizeBytes = c.getLong(sizeCol)

                val dateTaken = if (dateTakenCol >= 0) c.getLong(dateTakenCol) else 0L
                val dateModifiedMs = c.getLong(dateModCol) * 1000L
                val epochMs = if (dateTaken > 0L) dateTaken else dateModifiedMs

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
                        daySectionLabel = dayLabel
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

        private val RAW_EXTS = setOf("dng", "cr2", "cr3", "nef", "nrw", "arw", "orf", "rw2", "raf", "pef", "srw", "raw")
        private val ISO_6709_REGEX = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")
        private val MONTH_YEAR_FMT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())
        private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())
        private val SAME_YEAR_DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())
        private val OTHER_YEAR_DAY_FMT = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())
    }
}
