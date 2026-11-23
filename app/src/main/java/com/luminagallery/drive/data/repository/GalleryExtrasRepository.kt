package com.luminagallery.drive.data.repository

import androidx.compose.runtime.Immutable
import com.luminagallery.drive.data.local.GalleryDatabase
import com.luminagallery.drive.data.local.HiddenLockedFolderManager
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.entity.AlbumViewPrefEntity
import com.luminagallery.drive.data.local.entity.CustomAlbumCoverEntity
import com.luminagallery.drive.data.local.entity.FavoriteItemEntity
import com.luminagallery.drive.data.local.entity.FavoriteStoryEntity
import com.luminagallery.drive.data.local.entity.HiddenAlbumEntity
import com.luminagallery.drive.data.local.entity.IgnoredCleanOutEntity
import com.luminagallery.drive.data.local.entity.LockedAlbumEntity
import com.luminagallery.drive.data.local.entity.MediaAnnotationEntity
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import com.luminagallery.drive.data.local.entity.PhotoOcrEntity
import com.luminagallery.drive.domain.model.AlbumMediaGroupBy
import com.luminagallery.drive.domain.model.AlbumMediaSort
import com.luminagallery.drive.domain.model.GalleryStory
import com.luminagallery.drive.domain.model.GeoTaggedMediaItem
import com.luminagallery.drive.domain.model.LocationPhotoGroup
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.StoryCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

@Immutable
data class LocationScanProgress(
    val groups: List<LocationPhotoGroup> = emptyList(),
    val dayLocations: Map<String, String> = emptyMap(),
    val isScanning: Boolean = true,
    val scannedCount: Int = 0,
    val totalCount: Int = 0
)

/**
 * Manages Favorites (local + Drive), Hidden Albums, and Samsung-style auto-curated Stories
 * (Photo Journals, Trips from Locations, and Monthly / Highlight Memories).
 */
class GalleryExtrasRepository(
    database: GalleryDatabase,
    private val localMediaRepository: LocalMediaRepository,
    private val hiddenFolderManager: HiddenLockedFolderManager
) {
    private val extrasDao = database.galleryExtrasDao()
    private val locationScanMutex = Mutex()
    private val _locationProgressFlow = MutableStateFlow(LocationScanProgress(isScanning = true))
    val locationProgressFlow: StateFlow<LocationScanProgress> = _locationProgressFlow.asStateFlow()

    @Volatile
    var latestFavoriteItemIds: Set<String> = emptySet()
        private set

    val favoriteItemIdsFlow: Flow<Set<String>> = combine(
        extrasDao.observeFavoriteItemIds(),
        localMediaRepository.observeSystemFavoriteLocalIds()
    ) { dbFavs, sysFavs ->
        (dbFavs + sysFavs).toSet().also { latestFavoriteItemIds = it }
    }.flowOn(Dispatchers.Default)

    @Volatile
    var latestLockedAlbumIds: Set<String> = emptySet()
        private set

    val hiddenAlbumIdsFlow: Flow<Set<String>> = extrasDao.observeHiddenAlbumIds()
        .map { it.toSet() }

    val lockedAlbumIdsFlow: Flow<Set<String>> = extrasDao.observeLockedAlbumIds()
        .map { list -> list.toSet().also { latestLockedAlbumIds = it } }

    val favoriteStoryIdsFlow: Flow<Set<String>> = extrasDao.observeFavoriteStoryIds()
        .map { it.toSet() }

    val ignoredCleanOutIdsFlow: Flow<Set<String>> = extrasDao.observeIgnoredCleanOutIds()
        .map { it.toSet() }

    val customAlbumCoversFlow: Flow<Map<String, CustomAlbumCoverEntity>> =
        extrasDao.observeCustomAlbumCovers()
            .map { list -> list.associateBy { it.albumId } }
            .flowOn(Dispatchers.Default)

    val mediaAnnotationsFlow: Flow<Map<String, MediaAnnotationEntity>> =
        extrasDao.observeAllAnnotations()
            .map { list -> list.associateBy { it.itemId } }
            .flowOn(Dispatchers.Default)

    val cachedLocationsMapFlow: Flow<Map<String, PhotoLocationEntity>> =
        extrasDao.observeCachedLocations()
            .map { list -> list.associateBy { it.itemId } }
            .flowOn(Dispatchers.Default)

    val ocrTextMapFlow: Flow<Map<String, PhotoOcrEntity>> =
        extrasDao.observeOcrTextEntries()
            .map { list -> list.associateBy { it.itemId } }
            .flowOn(Dispatchers.Default)

    fun observeAlbumViewPref(albumId: String): Flow<AlbumViewPrefEntity?> =
        extrasDao.observeAlbumViewPref(albumId)

    suspend fun getAlbumViewPref(albumId: String): AlbumViewPrefEntity? =
        withContext(Dispatchers.IO) { extrasDao.getAlbumViewPref(albumId) }

    suspend fun saveAlbumViewPref(
        albumId: String,
        sort: AlbumMediaSort,
        groupBy: AlbumMediaGroupBy
    ) = withContext(Dispatchers.IO) {
        extrasDao.upsertAlbumViewPref(
            AlbumViewPrefEntity(
                albumId = albumId,
                sortName = sort.name,
                groupByName = groupBy.name,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    fun observeAnnotation(itemId: String): Flow<MediaAnnotationEntity?> =
        extrasDao.observeAnnotation(itemId)

    suspend fun setCustomAlbumCover(albumId: String, item: MediaItem) = withContext(Dispatchers.IO) {
        extrasDao.upsertCustomAlbumCover(
            CustomAlbumCoverEntity(
                albumId = albumId,
                coverItemId = item.id,
                coverThumbnailLink = if (item.isLocal) item.localContentUri else item.thumbnailLink,
                isLocal = item.isLocal,
                isVideo = item.isVideo,
                sizeBytes = item.sizeBytes,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearCustomAlbumCover(albumId: String) = withContext(Dispatchers.IO) {
        extrasDao.deleteCustomAlbumCover(albumId)
    }

    suspend fun saveAnnotation(itemId: String, tags: List<String>, note: String) =
        withContext(Dispatchers.IO) {
            val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val cleanNote = note.trim()
            if (cleanTags.isEmpty() && cleanNote.isEmpty()) {
                extrasDao.deleteAnnotation(itemId)
            } else {
                extrasDao.upsertAnnotation(
                    MediaAnnotationEntity(
                        itemId = itemId,
                        tagsCsv = cleanTags.joinToString(","),
                        note = cleanNote,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }

    suspend fun ignoreCleanOutItems(itemIds: Collection<String>) = withContext(Dispatchers.IO) {
        if (itemIds.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        extrasDao.upsertIgnoredCleanOutItems(itemIds.map { IgnoredCleanOutEntity(it, now) })
    }

    suspend fun clearIgnoredCleanOut() = withContext(Dispatchers.IO) {
        extrasDao.clearAllIgnoredCleanOut()
    }

    /**
     * Single shared, mutex-guarded, Room-cached GPS location scanner powering:
     *  - Samsung-style Country -> City Locations screen + Interactive Street Map
     *  - Pictures timeline day + city headers
     *  - Stories Trips carousel
     *
     * Publishes instant Room-cached groups on frame 0, then scans any uncached local media in
     * fast 4-way parallel IO batches and persists results so subsequent opens take <15ms!
     */
    suspend fun syncLocationsIndex(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        locationScanMutex.withLock {
            val localItems = items.filter { it.isLocal && !it.localContentUri.isNullOrBlank() }
            if (localItems.isEmpty()) {
                _locationProgressFlow.value = LocationScanProgress(isScanning = false)
                return@withLock
            }

            val cachedMap = extrasDao.getAllCachedLocations().associateByTo(HashMap()) { it.itemId }

            fun buildProgress(isScanning: Boolean, scanned: Int, total: Int): LocationScanProgress {
                val byCityKey = LinkedHashMap<String, MutableList<Pair<GeoTaggedMediaItem, PhotoLocationEntity>>>()
                val citiesByDay = LinkedHashMap<String, LinkedHashSet<String>>()

                for (item in localItems) {
                    val cached = cachedMap[item.id] ?: continue
                    if (!cached.hasLocation) continue
                    val geo = GeoTaggedMediaItem(item, cached.latitude, cached.longitude)
                    val cityName = cached.city.ifBlank { "Unknown" }
                    val countryName = cached.country.ifBlank { "Other" }
                    val key = "${countryName.lowercase(Locale.US)}|${cityName.lowercase(Locale.US)}"
                    byCityKey.getOrPut(key) { mutableListOf() }.add(geo to cached)

                    val daySet = citiesByDay.getOrPut(item.daySectionLabel) { LinkedHashSet(2) }
                    if (daySet.size < 2 && !cityName.contains("°")) {
                        daySet.add(cityName)
                    }
                }

                val groups = byCityKey.entries.map { (key, entries) ->
                    val firstMeta = entries.first().second
                    val geoItems = entries.map { it.first }
                    val avgLat = geoItems.sumOf { it.latitude } / geoItems.size
                    val avgLon = geoItems.sumOf { it.longitude } / geoItems.size
                    LocationPhotoGroup(
                        locationKey = key,
                        displayName = firstMeta.city,
                        country = firstMeta.country,
                        subtitle = firstMeta.country,
                        latitude = avgLat,
                        longitude = avgLon,
                        coverItem = geoItems.first().item,
                        items = geoItems.map { it.item },
                        geoItems = geoItems
                    )
                }.sortedByDescending { it.items.size }

                val dayMap = citiesByDay.mapValues { (_, set) -> set.joinToString(", ") }
                return LocationScanProgress(
                    groups = groups,
                    dayLocations = dayMap,
                    isScanning = isScanning,
                    scannedCount = scanned,
                    totalCount = total
                )
            }

            val uncached = localItems.filter { item ->
                val prev = cachedMap[item.id]
                prev == null || prev.dateModifiedMs != item.createdTimeEpochMs
            }.sortedBy { item ->
                val album = item.relativePath.orEmpty()
                when {
                    album.equals("Camera", ignoreCase = true) && !item.isVideo -> 0
                    album.contains("screenshot", ignoreCase = true) -> 4
                    album.contains("whatsapp", ignoreCase = true) -> 3
                    item.isVideo -> 2
                    else -> 1
                }
            }

            val totalToScan = uncached.size
            _locationProgressFlow.value = buildProgress(
                isScanning = totalToScan > 0 && localMediaRepository.hasMediaLocationPermission(),
                scanned = 0,
                total = totalToScan
            )

            if (totalToScan == 0 || !localMediaRepository.hasMediaLocationPermission()) {
                return@withLock
            }

            var scannedSoFar = 0
            val batchSize = 48
            for (batch in uncached.chunked(batchSize)) {
                val newEntities = coroutineScope {
                    batch.chunked(12).map { slice ->
                        async(Dispatchers.IO) {
                            slice.map { item ->
                                val latLon = localMediaRepository.readMediaLatLon(item)
                                if (latLon != null && (latLon.first != 0.0 || latLon.second != 0.0)) {
                                    val (city, country) = localMediaRepository.resolveCityAndCountry(
                                        lat = latLon.first,
                                        lon = latLon.second
                                    )
                                    PhotoLocationEntity(
                                        itemId = item.id,
                                        dateModifiedMs = item.createdTimeEpochMs,
                                        hasLocation = true,
                                        latitude = latLon.first,
                                        longitude = latLon.second,
                                        city = city,
                                        country = country
                                    )
                                } else {
                                    PhotoLocationEntity(
                                        itemId = item.id,
                                        dateModifiedMs = item.createdTimeEpochMs,
                                        hasLocation = false,
                                        latitude = 0.0,
                                        longitude = 0.0,
                                        city = "",
                                        country = ""
                                    )
                                }
                            }
                        }
                    }.awaitAll().flatten()
                }

                extrasDao.upsertPhotoLocations(newEntities)
                newEntities.forEach { cachedMap[it.itemId] = it }
                scannedSoFar += batch.size
                _locationProgressFlow.value = buildProgress(
                    isScanning = scannedSoFar < totalToScan,
                    scanned = scannedSoFar,
                    total = totalToScan
                )
            }
        }
    }

    suspend fun toggleFavoriteItem(itemId: String, currentlyFavorited: Boolean) =
        withContext(Dispatchers.IO) {
            if (currentlyFavorited) {
                extrasDao.removeFavoriteItem(itemId)
            } else {
                extrasDao.upsertFavoriteItem(FavoriteItemEntity(itemId, System.currentTimeMillis()))
            }
        }

    suspend fun toggleHiddenAlbum(albumId: String, currentlyHidden: Boolean) =
        withContext(Dispatchers.IO) {
            if (currentlyHidden) {
                extrasDao.removeHiddenAlbum(albumId)
            } else {
                extrasDao.upsertHiddenAlbum(HiddenAlbumEntity(albumId, System.currentTimeMillis()))
            }
        }

    suspend fun getLockedAlbum(albumId: String): LockedAlbumEntity? =
        withContext(Dispatchers.IO) { extrasDao.getLockedAlbum(albumId) }

    suspend fun setAlbumLocked(albumId: String, locked: Boolean) =
        withContext(Dispatchers.IO) {
            if (locked) {
                val existing = extrasDao.getLockedAlbum(albumId)
                extrasDao.upsertLockedAlbum(
                    existing ?: LockedAlbumEntity(albumId = albumId, lockedAtEpochMs = System.currentTimeMillis())
                )
                latestLockedAlbumIds = latestLockedAlbumIds + albumId
            } else {
                extrasDao.removeLockedAlbum(albumId)
                latestLockedAlbumIds = latestLockedAlbumIds - albumId
            }
        }

    /**
     * Locks an album and (for local albums when [hideOnDisk] is true & All files access is granted)
     * renames its folder to a dot-prefixed hidden folder with `.nomedia` so File Explorer and other
     * gallery apps stop showing it. Returns `true` if hidden on disk, `false` if in-app lock only.
     */
    suspend fun lockAlbum(
        albumId: String,
        albumName: String,
        isLocal: Boolean,
        items: List<MediaItem>,
        hideOnDisk: Boolean
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            if (isLocal && hideOnDisk && hiddenFolderManager.hasAllFilesAccess()) {
                val relPath = items.firstOrNull { it.isLocal }?.storagePath
                val moveRes = hiddenFolderManager.hideFolderOnDisk(
                    albumId = albumId,
                    albumName = albumName,
                    originalRelativePath = relPath,
                    items = items
                )
                if (moveRes.isSuccess) {
                    val moved = moveRes.getOrThrow()
                    extrasDao.upsertLockedAlbum(
                        LockedAlbumEntity(
                            albumId = albumId,
                            lockedAtEpochMs = now,
                            isHiddenOnDisk = true,
                            hiddenDirPath = moved.hiddenDirPath,
                            originalDirPath = moved.originalDirPath,
                            displayName = moved.displayName,
                            originalRelativePath = moved.originalRelativePath
                        )
                    )
                    latestLockedAlbumIds = latestLockedAlbumIds + albumId
                    localMediaRepository.notifyPermissionChanged()
                    return@runCatching true
                }
            }
            extrasDao.upsertLockedAlbum(
                LockedAlbumEntity(
                    albumId = albumId,
                    lockedAtEpochMs = now,
                    isHiddenOnDisk = false,
                    displayName = albumName
                )
            )
            latestLockedAlbumIds = latestLockedAlbumIds + albumId
            localMediaRepository.notifyPermissionChanged()
            false
        }
    }

    /**
     * Unlocks an album and (if it was hidden on disk via `.folder` + `.nomedia`) restores its
     * original folder name, removes `.nomedia`, and triggers MediaScanner so File Explorer &
     * other galleries see it immediately.
     */
    suspend fun unlockAlbum(albumId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val existing = extrasDao.getLockedAlbum(albumId)
            var restoredFromDisk = false
            if (existing != null && existing.isHiddenOnDisk) {
                hiddenFolderManager.unhideFolderOnDisk(existing).getOrThrow()
                restoredFromDisk = true
            }
            extrasDao.removeLockedAlbum(albumId)
            latestLockedAlbumIds = latestLockedAlbumIds - albumId
            localMediaRepository.notifyPermissionChanged()
            restoredFromDisk
        }
    }

    /**
     * Rediscovers any `.lumina_locked` dot-folders on storage (e.g. after app reinstall or data wipe)
     * and re-registers them in `locked_albums` so locked folders automatically reappear.
     */
    suspend fun discoverAndRegisterHiddenLockedFolders() = withContext(Dispatchers.IO) {
        val discovered = hiddenFolderManager.discoverHiddenFoldersOnDisk()
        if (discovered.isEmpty()) return@withContext
        val existingIds = extrasDao.getAllLockedAlbumIds().toHashSet()
        val toAdd = discovered.filter { it.albumId !in existingIds }
        if (toAdd.isNotEmpty()) {
            extrasDao.upsertLockedAlbums(toAdd)
            latestLockedAlbumIds = latestLockedAlbumIds + toAdd.map { it.albumId }
            localMediaRepository.notifyPermissionChanged()
        }
    }

    suspend fun toggleFavoriteStory(storyId: String, currentlyFavorited: Boolean) =
        withContext(Dispatchers.IO) {
            if (currentlyFavorited) {
                extrasDao.removeFavoriteStory(storyId)
            } else {
                extrasDao.upsertFavoriteStory(FavoriteStoryEntity(storyId, System.currentTimeMillis()))
            }
        }

    /**
     * Generates curated Samsung Gallery-style Stories:
     *  - Featured Photo Journals (multi-day or high-activity photo clusters)
     *  - Trips (spatial Location groups with rich photo counts)
     *  - Other Stories (monthly chapters, recent highlights, RAW & video reels)
     */
    fun generateStories(
        allMedia: List<MediaItem>,
        locations: List<LocationPhotoGroup>,
        favoriteStoryIds: Set<String>
    ): List<GalleryStory> {
        if (allMedia.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val stories = mutableListOf<GalleryStory>()
        val photos = allMedia.filter { !it.isVideo && it.createdTimeEpochMs > 0L }

        // 1. Featured Photo Journals (cluster consecutive active photo days into cute journals!)
        val byDate = photos.groupBy { item ->
            Instant.ofEpochMilli(item.createdTimeEpochMs).atZone(zone).toLocalDate()
        }.toSortedMap(compareByDescending { it })

        val activeDates = byDate.entries.filter { it.value.size >= 4 }
            .ifEmpty { byDate.entries.filter { it.value.size >= 2 } }
            .ifEmpty { byDate.entries.take(1) }
        val journals = mutableListOf<List<Pair<java.time.LocalDate, List<MediaItem>>>>()
        for ((date, dayItems) in activeDates) {
            val current = journals.lastOrNull()
            if (current != null && ChronoUnit.DAYS.between(date, current.last().first) <= 2L) {
                journals[journals.lastIndex] = current + (date to dayItems)
            } else {
                journals.add(listOf(date to dayItems))
            }
        }

        journals.take(4).forEachIndexed { idx, cluster ->
            val clusterItems = cluster.flatMap { it.second }.take(40)
            val newestDate = cluster.first().first
            val oldestDate = cluster.last().first
            val daySpan = (ChronoUnit.DAYS.between(oldestDate, newestDate) + 1).toInt().coerceAtLeast(1)
            val storyId = "journal_${newestDate}"
            stories.add(
                GalleryStory(
                    id = storyId,
                    title = "Photo journal: ${MONTH_DAY_FMT.format(newestDate)}",
                    subtitle = newestDate.year.toString(),
                    dateRangeLabel = if (daySpan > 1) {
                        "${SHORT_DATE_FMT.format(oldestDate)} – ${SHORT_DATE_FMT.format(newestDate)}, ${newestDate.year}"
                    } else {
                        "${MONTH_DAY_FMT.format(newestDate)}, ${newestDate.year}"
                    },
                    daySpanLabel = if (daySpan > 1) "$daySpan days" else "${clusterItems.size} photos",
                    category = StoryCategory.FEATURED,
                    coverItem = clusterItems.first(),
                    items = clusterItems,
                    isNew = idx == 0,
                    isFavorited = storyId in favoriteStoryIds
                )
            )
        }

        // 2. Trips from Geotagged Locations
        locations.filter { it.items.size >= 3 }.take(12).forEachIndexed { idx, loc ->
            val dates = loc.items.mapNotNull { item ->
                if (item.createdTimeEpochMs > 0L) {
                    Instant.ofEpochMilli(item.createdTimeEpochMs).atZone(zone).toLocalDate()
                } else null
            }
            val minDate = dates.minOrNull()
            val maxDate = dates.maxOrNull()
            val dateRange = when {
                minDate != null && maxDate != null && minDate != maxDate ->
                    "${SHORT_DATE_FMT.format(minDate)} – ${SHORT_DATE_FMT.format(maxDate)}, ${maxDate.year}"
                maxDate != null -> "${SHORT_DATE_FMT.format(maxDate)}, ${maxDate.year}"
                else -> loc.subtitle
            }
            val storyId = "trip_${loc.locationKey}"
            stories.add(
                GalleryStory(
                    id = storyId,
                    title = loc.displayName,
                    subtitle = loc.subtitle,
                    dateRangeLabel = dateRange,
                    daySpanLabel = "${loc.items.size} items",
                    category = StoryCategory.TRIP,
                    coverItem = loc.coverItem,
                    items = loc.items,
                    isNew = idx < 2,
                    isFavorited = storyId in favoriteStoryIds
                )
            )
        }

        // 3. Other Stories: Monthly Chapters + Highlights
        val byMonth = photos.groupBy { it.monthYearLabel }
        byMonth.entries.take(12).forEachIndexed { idx, (monthLabel, monthItems) ->
            if (monthItems.size >= 3) {
                val storyId = "month_$monthLabel"
                stories.add(
                    GalleryStory(
                        id = storyId,
                        title = monthLabel,
                        subtitle = "${monthItems.size} moments",
                        dateRangeLabel = monthLabel,
                        daySpanLabel = "${monthItems.size} photos",
                        category = StoryCategory.OTHER,
                        coverItem = monthItems.first(),
                        items = monthItems.take(50),
                        isNew = idx == 0,
                        isFavorited = storyId in favoriteStoryIds
                    )
                )
            }
        }

        // Video Reel Story if user has videos
        val videos = allMedia.filter { it.isVideo }
        if (videos.size >= 2) {
            val storyId = "story_video_highlights"
            stories.add(
                GalleryStory(
                    id = storyId,
                    title = "Video Memories",
                    subtitle = "${videos.size} clips",
                    dateRangeLabel = videos.first().monthYearLabel,
                    daySpanLabel = "${videos.size} videos",
                    category = StoryCategory.OTHER,
                    coverItem = videos.first(),
                    items = videos.take(30),
                    isNew = false,
                    isFavorited = storyId in favoriteStoryIds
                )
            )
        }

        return stories
    }

    private companion object {
        val MONTH_DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())
        val SHORT_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    }
}
