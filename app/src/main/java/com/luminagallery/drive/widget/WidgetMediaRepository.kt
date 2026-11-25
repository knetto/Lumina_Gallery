package com.luminagallery.drive.widget

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.luminagallery.drive.LuminaApplication
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.local.entity.PhotoLocationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.random.Random

internal data class WidgetAlbumOption(
    val id: String,
    val name: String,
    val photoCount: Int,
    val coverUri: String?,
    val isCollection: Boolean = false
)

internal data class WidgetResolvedSelection(
    val photos: List<WidgetPhoto>,
    val caption: WidgetCaptionData?,
    val groupKey: String? = null,
    val totalChoices: Int = 1
)

internal data class WidgetFrameResolution(
    val frames: List<WidgetResolvedSelection>,
    val privacyVerified: Boolean
)

/**
 * Privacy-safe MediaStore + Room query layer for all Lumina Home Screen widgets and the
 * Widget Configuration photo/album pickers. Strictly excludes hidden & locked albums.
 */
internal object WidgetMediaRepository {

    private const val TAG = "WidgetMediaRepository"
    private const val MAX_GROUP_SCAN_PHOTOS = 2400
    private const val RANDOM_RESERVOIR_SIZE = 72

    private data class PrivacyContext(
        val blockedBucketIds: Set<String>,
        val favoriteIds: Set<String>,
        val locations: Map<String, PhotoLocationEntity>,
        val collectionToBuckets: Map<String, Set<String>>
    )

    /**
     * Loads all visible (non-hidden, non-locked) local albums and Lumina collections for the widget picker.
     */
    suspend fun loadPickerAlbums(context: Context): List<WidgetAlbumOption> = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? LuminaApplication
        val extrasDao = app?.container?.database?.galleryExtrasDao()
        val collectionDao = app?.container?.database?.albumCollectionDao()
        val blockedBucketIds = runCatching {
            if (extrasDao != null) {
                (extrasDao.getAllLockedAlbumIds() + extrasDao.getAllHiddenAlbumIds()).toHashSet()
            } else emptySet()
        }.getOrDefault(emptySet())

        val buckets = LinkedHashMap<String, MutableAlbumAcc>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.MediaColumns.SIZE} > 0",
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketIdCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val rawBucket = if (bucketIdCol >= 0) c.getString(bucketIdCol) else null
                    val luminaBucketId = "${LocalMediaRepository.LOCAL_BUCKET_PREFIX}${rawBucket ?: "default"}"
                    if (luminaBucketId in blockedBucketIds) continue
                    val acc = buckets.getOrPut(luminaBucketId) {
                        val name = if (bucketNameCol >= 0) c.getString(bucketNameCol)?.takeIf { it.isNotBlank() } else null
                        val coverUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            c.getLong(idCol)
                        ).toString()
                        MutableAlbumAcc(name ?: "Photos", coverUri, 0)
                    }
                    acc.count++
                }
            }
        }.onFailure { Log.w(TAG, "Unable to query albums for widget picker", it) }

        val folderOptions = buckets.entries.map { (id, acc) ->
            WidgetAlbumOption(
                id = id,
                name = acc.name,
                photoCount = acc.count,
                coverUri = acc.coverUri,
                isCollection = false
            )
        }.sortedWith(
            compareBy<WidgetAlbumOption> { albumPriority(it.name) }
                .thenByDescending { it.photoCount }
        )

        // Include non-empty local Lumina collections as selectable albums too!
        val collectionOptions = runCatching {
            if (collectionDao == null) emptyList() else {
                val cols = collectionDao.getAllCollections()
                val layouts = collectionDao.getAllLayouts()
                val colToBuckets = expandCollectionsToBuckets(cols.map { it.id to it.parentCollectionId }, layouts.map { it.itemId to it.parentCollectionId }, blockedBucketIds)
                cols.mapNotNull { col ->
                    val memberBuckets = colToBuckets[col.id].orEmpty()
                    val matching = memberBuckets.mapNotNull { buckets[it] }
                    val total = matching.sumOf { it.count }
                    if (total <= 0) null else WidgetAlbumOption(
                        id = col.id,
                        name = col.name,
                        photoCount = total,
                        coverUri = matching.firstOrNull()?.coverUri,
                        isCollection = true
                    )
                }
            }
        }.getOrDefault(emptyList())

        collectionOptions + folderOptions
    }

    /**
     * Loads visible photos for the visual multi-photo picker (optionally filtered to one album/collection).
     */
    suspend fun loadPickerPhotos(
        context: Context,
        filterAlbumId: String? = null,
        limit: Int = 600
    ): List<WidgetPhoto> = withContext(Dispatchers.IO) {
        val privacy = loadPrivacyContext(context, needFavorites = false, needLocations = false, needCollections = filterAlbumId != null)
            ?: return@withContext emptyList()
        val allowedBuckets = filterAlbumId?.let { id ->
            if (id in privacy.collectionToBuckets) privacy.collectionToBuckets[id].orEmpty()
            else setOf(id)
        }
        val out = ArrayList<WidgetPhoto>(minOf(limit, 256))
        queryMediaStoreImages(context) { photo, _ ->
            if (photo.bucketId in privacy.blockedBucketIds) return@queryMediaStoreImages true
            if (allowedBuckets != null && photo.bucketId !in allowedBuckets) return@queryMediaStoreImages true
            out.add(photo)
            out.size < limit
        }
        out
    }

    /**
     * Loads exact photos currently shown on a widget by their Lumina item IDs,
     * still enforcing hidden/locked album privacy exclusions.
     * Returns null if privacy exclusions could not be verified (e.g. transient boot/DB failure).
     */
    suspend fun loadVerifiedPhotosByIds(
        context: Context,
        photoIds: List<String>
    ): List<WidgetPhoto>? = withContext(Dispatchers.IO) {
        if (photoIds.isEmpty()) return@withContext emptyList()
        val privacy = loadPrivacyContext(
            context = context,
            needFavorites = false,
            needLocations = false,
            needCollections = false
        ) ?: return@withContext null
        querySpecificPhotos(context, privacy, photoIds)
    }

    /**
     * Loads exact photos currently shown on a widget by their Lumina item IDs,
     * still enforcing hidden/locked album privacy exclusions.
     */
    suspend fun loadPhotosByIds(
        context: Context,
        photoIds: List<String>
    ): List<WidgetPhoto> = loadVerifiedPhotosByIds(context, photoIds).orEmpty()

    /**
     * Resolves widget frames while reporting whether privacy exclusions were verified so callers
     * can distinguish a truly empty source from a transient boot/storage/DB hiccup.
     */
    suspend fun resolveWidgetFramesChecked(
        context: Context,
        widgetId: Int,
        kind: PhotoWidgetKind,
        settings: PhotoWidgetSettings,
        slotsPerFrame: Int,
        frameCount: Int,
        advance: Boolean
    ): WidgetFrameResolution = withContext(Dispatchers.IO) {
        val needFavs = settings.source == WidgetPhotoSource.FAVORITES_ONLY ||
            settings.source == WidgetPhotoSource.FAVORITES_AND_RECENT
        val needLocations = settings.source == WidgetPhotoSource.SMART_GROUPS ||
            settings.source == WidgetPhotoSource.LOCATION_GROUPS ||
            settings.source == WidgetPhotoSource.MEMORIES_MIX
        val needCollections = settings.source == WidgetPhotoSource.SELECTED_ALBUMS &&
            settings.selectedAlbumIds.isNotEmpty()

        val privacy = loadPrivacyContext(context, needFavs, needLocations, needCollections)
            ?: return@withContext WidgetFrameResolution(emptyList(), privacyVerified = false)

        val frames = resolveWidgetFramesWithPrivacy(
            context = context,
            widgetId = widgetId,
            kind = kind,
            settings = settings,
            privacy = privacy,
            slotsPerFrame = slotsPerFrame,
            frameCount = frameCount,
            advance = advance
        )
        WidgetFrameResolution(frames = frames, privacyVerified = true)
    }

    /**
     * Resolves a batch of [frameCount] frames for rendering (1 frame for static/scheduled updates,
     * or up to 4 frames for live fast ViewFlipper slideshows!).
     */
    suspend fun resolveWidgetFrames(
        context: Context,
        widgetId: Int,
        kind: PhotoWidgetKind,
        settings: PhotoWidgetSettings,
        slotsPerFrame: Int,
        frameCount: Int,
        advance: Boolean
    ): List<WidgetResolvedSelection> = resolveWidgetFramesChecked(
        context = context,
        widgetId = widgetId,
        kind = kind,
        settings = settings,
        slotsPerFrame = slotsPerFrame,
        frameCount = frameCount,
        advance = advance
    ).frames

    private fun resolveWidgetFramesWithPrivacy(
        context: Context,
        widgetId: Int,
        kind: PhotoWidgetKind,
        settings: PhotoWidgetSettings,
        privacy: PrivacyContext,
        slotsPerFrame: Int,
        frameCount: Int,
        advance: Boolean
    ): List<WidgetResolvedSelection> {
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val indexKey = "idx_$widgetId"
        val prevIdx = prefs.getInt(indexKey, -1)
        val prevGroupKey = prefs.getString("group_key_$widgetId", null)

        return when (settings.source) {
            WidgetPhotoSource.SMART_GROUPS,
            WidgetPhotoSource.LOCATION_GROUPS,
            WidgetPhotoSource.DAY_GROUPS,
            WidgetPhotoSource.MEMORIES_MIX -> {
                val groups = buildCoherentGroups(context, privacy, settings)
                if (groups.isEmpty()) {
                    // Graceful fallback to random phone photos if no groups exist yet (e.g. empty locations)
                    if (settings.source == WidgetPhotoSource.LOCATION_GROUPS) {
                        return emptyList()
                    }
                    return resolveFlatPoolFrames(
                        context = context,
                        widgetId = widgetId,
                        kind = kind,
                        settings = settings.copy(source = WidgetPhotoSource.ALL_PHONE_RANDOM),
                        privacy = privacy,
                        slotsPerFrame = slotsPerFrame,
                        frameCount = frameCount,
                        advance = advance
                    )
                }
                val selectedGroups = pickGroups(
                    groups = groups,
                    frameCount = frameCount,
                    shuffle = settings.shuffleOrder || kind == PhotoWidgetKind.COLLAGE,
                    advance = advance,
                    prevIdx = prevIdx,
                    prevGroupKey = prevGroupKey,
                    saveState = { newIdx, newKey ->
                        if (widgetId >= 0) {
                            prefs.edit()
                                .putInt(indexKey, newIdx)
                                .putString("group_key_$widgetId", newKey)
                                .apply()
                        }
                    }
                )
                selectedGroups.map { group ->
                    val chosen = pickDiversePhotosForGroup(
                        context = context,
                        groupPhotos = group.photos,
                        kind = kind,
                        settings = settings,
                        slotsPerFrame = slotsPerFrame
                    )
                    WidgetResolvedSelection(
                        photos = chosen,
                        caption = WidgetCaptionData(
                            title = group.title,
                            subtitle = group.subtitle
                        ),
                        groupKey = group.groupKey,
                        totalChoices = groups.size
                    )
                }
            }

            WidgetPhotoSource.SELECTED_ALBUMS -> {
                val resolvedBuckets = expandSelectedAlbums(settings.selectedAlbumIds, privacy)
                val albumPhotos = queryAlbumPhotos(context, privacy, resolvedBuckets, settings.excludeScreenshots)
                if (albumPhotos.isEmpty()) {
                    // If user hasn't picked albums yet, show favorites/recent gracefully
                    return if (settings.selectedAlbumIds.isEmpty()) {
                        resolveFlatPoolFrames(
                            context, widgetId, kind,
                            settings.copy(source = WidgetPhotoSource.FAVORITES_AND_RECENT),
                            privacy, slotsPerFrame, frameCount, advance
                        )
                    } else emptyList()
                }
                if (kind.isMultiPhoto && settings.groupByAlbum && resolvedBuckets.size > 1) {
                    val albumGroups = buildAlbumCollageGroups(albumPhotos, minPhotosPerGroup = 1)
                    if (albumGroups.isNotEmpty()) {
                        val picked = pickGroups(
                            groups = albumGroups,
                            frameCount = frameCount,
                            shuffle = settings.shuffleOrder,
                            advance = advance,
                            prevIdx = prevIdx,
                            prevGroupKey = prevGroupKey,
                            saveState = { newIdx, newKey ->
                                if (widgetId >= 0) {
                                    prefs.edit()
                                        .putInt(indexKey, newIdx)
                                        .putString("group_key_$widgetId", newKey)
                                        .apply()
                                }
                            }
                        )
                        return picked.map { group ->
                            val chosen = pickDiversePhotosForGroup(
                                context = context,
                                groupPhotos = group.photos,
                                kind = kind,
                                settings = settings,
                                slotsPerFrame = slotsPerFrame
                            )
                            WidgetResolvedSelection(
                                photos = chosen,
                                caption = WidgetCaptionData(
                                    title = group.title,
                                    subtitle = chosen.firstOrNull()?.let { widgetDateLabel(it.takenAtMs) }
                                ),
                                groupKey = group.groupKey,
                                totalChoices = albumGroups.size
                            )
                        }
                    }
                }
                buildFramesFromFlatList(
                    context = context,
                    pool = albumPhotos,
                    widgetId = widgetId,
                    prefs = prefs,
                    kind = kind,
                    settings = settings,
                    slotsPerFrame = slotsPerFrame,
                    frameCount = frameCount,
                    shuffle = settings.shuffleOrder,
                    advance = advance,
                    captionFor = { chosen ->
                        val first = chosen.firstOrNull()
                        first?.let {
                            WidgetCaptionData(
                                title = it.albumName,
                                subtitle = widgetDateLabel(it.takenAtMs).takeIf { d -> d.isNotBlank() }
                            )
                        }
                    }
                )
            }

            else -> resolveFlatPoolFrames(
                context = context,
                widgetId = widgetId,
                kind = kind,
                settings = settings,
                privacy = privacy,
                slotsPerFrame = slotsPerFrame,
                frameCount = frameCount,
                advance = advance
            )
        }
    }

    private fun resolveFlatPoolFrames(
        context: Context,
        widgetId: Int,
        kind: PhotoWidgetKind,
        settings: PhotoWidgetSettings,
        privacy: PrivacyContext,
        slotsPerFrame: Int,
        frameCount: Int,
        advance: Boolean
    ): List<WidgetResolvedSelection> {
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val pool: List<WidgetPhoto> = when (settings.source) {
            WidgetPhotoSource.SELECTED_PHOTOS -> {
                val wanted = settings.selectedPhotoIds
                if (wanted.isEmpty()) {
                    queryFavoritesOrRecent(context, privacy, favoritesOnly = false, excludeScreenshots = settings.excludeScreenshots)
                } else {
                    querySpecificPhotos(context, privacy, wanted)
                }
            }
            WidgetPhotoSource.FAVORITES_ONLY -> {
                queryFavoritesOrRecent(context, privacy, favoritesOnly = true, excludeScreenshots = settings.excludeScreenshots)
            }
            WidgetPhotoSource.FAVORITES_AND_RECENT -> {
                queryFavoritesOrRecent(context, privacy, favoritesOnly = false, excludeScreenshots = settings.excludeScreenshots)
            }
            else -> {
                queryUniformRandomPhotos(context, privacy, settings.excludeScreenshots)
            }
        }
        if (pool.isEmpty()) return emptyList()

        val shouldShuffle = settings.shuffleOrder || settings.source == WidgetPhotoSource.ALL_PHONE_RANDOM
        return buildFramesFromFlatList(
            context = context,
            pool = pool,
            widgetId = widgetId,
            prefs = prefs,
            kind = kind,
            settings = settings,
            slotsPerFrame = slotsPerFrame,
            frameCount = frameCount,
            shuffle = shouldShuffle,
            advance = advance,
            captionFor = { chosen ->
                val first = chosen.firstOrNull() ?: return@buildFramesFromFlatList null
                val title = when {
                    settings.source == WidgetPhotoSource.FAVORITES_ONLY -> "Favorites"
                    settings.source == WidgetPhotoSource.SELECTED_PHOTOS && chosen.size > 1 -> "Selected Photos"
                    else -> first.albumName.ifBlank { "Photos" }
                }
                WidgetCaptionData(
                    title = title,
                    subtitle = widgetDateLabel(first.takenAtMs).takeIf { it.isNotBlank() }
                )
            }
        )
    }

    private fun pickDiversePhotosForGroup(
        context: Context,
        groupPhotos: List<WidgetPhoto>,
        kind: PhotoWidgetKind,
        settings: PhotoWidgetSettings,
        slotsPerFrame: Int
    ): List<WidgetPhoto> {
        if (groupPhotos.size <= 1) return groupPhotos
        val randomize = settings.shuffleOrder || kind == PhotoWidgetKind.COLLAGE
        if (!settings.avoidSimilarPhotos || !kind.isMultiPhoto) {
            return if (randomize && groupPhotos.size > slotsPerFrame) {
                groupPhotos.shuffled().take(slotsPerFrame)
            } else {
                groupPhotos.take(slotsPerFrame)
            }
        }
        val allowStepDown = settings.collageLayout == WidgetCollageLayout.AUTO
        return WidgetPhotoDiversity.selectVisuallyDistinctPhotos(
            context = context,
            candidates = groupPhotos,
            desiredMax = slotsPerFrame,
            minSlots = minOf(2, groupPhotos.size),
            allowStepDownOnDuplicates = allowStepDown,
            randomize = randomize
        )
    }

    private fun buildFramesFromFlatList(
        context: Context,
        pool: List<WidgetPhoto>,
        widgetId: Int,
        prefs: android.content.SharedPreferences,
        kind: PhotoWidgetKind,
        settings: PhotoWidgetSettings,
        slotsPerFrame: Int,
        frameCount: Int,
        shuffle: Boolean,
        advance: Boolean,
        captionFor: (List<WidgetPhoto>) -> WidgetCaptionData?
    ): List<WidgetResolvedSelection> {
        if (pool.isEmpty()) return emptyList()
        val indexKey = "idx_$widgetId"
        val seedKey = "seed_$widgetId"

        val ordered: List<WidgetPhoto> = if (shuffle) {
            val savedSeed = if (widgetId >= 0) prefs.getLong(seedKey, 0L) else 0L
            val seed = if (savedSeed == 0L || (advance && frameCount > 1)) {
                Random.nextLong().also { if (widgetId >= 0) prefs.edit().putLong(seedKey, it).apply() }
            } else savedSeed
            pool.shuffled(Random(seed))
        } else {
            pool
        }

        val prevIdx = prefs.getInt(indexKey, -1)
        val step = slotsPerFrame.coerceAtLeast(1)
        val wrapped = prevIdx >= 0 && advance && (prevIdx + step >= ordered.size)
        val finalOrdered = if (shuffle && wrapped && ordered.size > step) {
            val freshSeed = Random.nextLong()
            if (widgetId >= 0) prefs.edit().putLong(seedKey, freshSeed).apply()
            pool.shuffled(Random(freshSeed))
        } else {
            ordered
        }
        val startIdx = when {
            prevIdx < 0 -> 0
            advance -> Math.floorMod(prevIdx + step, finalOrdered.size)
            else -> Math.floorMod(prevIdx, finalOrdered.size)
        }
        if (widgetId >= 0) {
            prefs.edit().putInt(indexKey, startIdx).apply()
        }

        val effectiveFrames = if (finalOrdered.size <= step) 1 else frameCount.coerceAtLeast(1)
        val useDiversity = settings.avoidSimilarPhotos && kind.isMultiPhoto && step > 1 && finalOrdered.size > step
        return (0 until effectiveFrames).map { frameOffset ->
            val frameStart = (startIdx + frameOffset * step) % finalOrdered.size
            val windowSize = if (useDiversity) minOf(step * 3, finalOrdered.size, 16) else minOf(step, finalOrdered.size)
            val window = ArrayList<WidgetPhoto>(windowSize)
            for (slot in 0 until windowSize) {
                window.add(finalOrdered[(frameStart + slot) % finalOrdered.size])
            }
            val chosen = if (useDiversity) {
                WidgetPhotoDiversity.selectVisuallyDistinctPhotos(
                    context = context,
                    candidates = window,
                    desiredMax = step,
                    minSlots = minOf(2, window.size),
                    allowStepDownOnDuplicates = settings.collageLayout == WidgetCollageLayout.AUTO,
                    randomize = shuffle
                )
            } else {
                window
            }
            WidgetResolvedSelection(
                photos = chosen,
                caption = captionFor(chosen),
                totalChoices = finalOrdered.size
            )
        }
    }

    private fun buildCoherentGroups(
        context: Context,
        privacy: PrivacyContext,
        settings: PhotoWidgetSettings
    ): List<WidgetMemory> {
        val photos = ArrayList<WidgetPhoto>(512)
        var olderLocated = 0
        queryMediaStoreImages(context) { photo, _ ->
            if (photo.bucketId in privacy.blockedBucketIds) return@queryMediaStoreImages true
            if (settings.excludeScreenshots && photo.isScreenshot) return@queryMediaStoreImages true

            val hasLoc = privacy.locations.containsKey(photo.luminaItemId)
            if (photos.size < MAX_GROUP_SCAN_PHOTOS) {
                photos.add(photo)
            } else if (hasLoc && olderLocated < 600) {
                photos.add(photo)
                olderLocated++
            }
            photos.size < MAX_GROUP_SCAN_PHOTOS || (privacy.locations.isNotEmpty() && olderLocated < 600)
        }
        if (photos.isEmpty()) return emptyList()

        return when (settings.source) {
            WidgetPhotoSource.LOCATION_GROUPS -> {
                buildWidgetMemories(photos, privacy.locations, WidgetMemorySource.TRIPS)
            }
            WidgetPhotoSource.DAY_GROUPS -> {
                buildWidgetMemories(photos, emptyMap(), WidgetMemorySource.DAYS)
            }
            WidgetPhotoSource.SMART_GROUPS -> {
                val base = buildWidgetMemories(photos, privacy.locations, WidgetMemorySource.BOTH)
                val albumFallback = if (base.size < 4) buildAlbumCollageGroups(photos) else emptyList()
                (base + albumFallback).distinctBy { it.groupKey }
            }
            WidgetPhotoSource.MEMORIES_MIX -> {
                val otd = buildOnThisDayMemories(photos, LocalDate.now())
                val memSrc = settings.memorySource
                val base = buildWidgetMemories(photos, privacy.locations, memSrc)
                (otd + base).distinctBy { it.groupKey }
            }
            else -> emptyList()
        }
    }

    private fun pickGroups(
        groups: List<WidgetMemory>,
        frameCount: Int,
        shuffle: Boolean,
        advance: Boolean,
        prevIdx: Int,
        prevGroupKey: String?,
        saveState: (Int, String) -> Unit
    ): List<WidgetMemory> {
        if (groups.isEmpty()) return emptyList()
        val count = minOf(frameCount.coerceAtLeast(1), groups.size)

        if (!shuffle) {
            val idx = if (prevIdx < 0) 0 else Math.floorMod(prevIdx + if (advance) 1 else 0, groups.size)
            saveState(idx, groups[idx].groupKey)
            return (0 until count).map { offset -> groups[(idx + offset) % groups.size] }
        }

        // Keep current group stable on non-advance renders (like resize), pick fresh random group on advance!
        val primary = if (!advance && prevGroupKey != null) {
            groups.firstOrNull { it.groupKey == prevGroupKey } ?: groups.random()
        } else if (!advance && prevIdx in groups.indices) {
            groups[prevIdx]
        } else {
            val candidates = if (groups.size > 1 && prevGroupKey != null) {
                groups.filterNot { it.groupKey == prevGroupKey }.ifEmpty { groups }
            } else groups
            candidates.random()
        }
        val primaryIdx = groups.indexOf(primary).coerceAtLeast(0)
        saveState(primaryIdx, primary.groupKey)

        if (count == 1) return listOf(primary)
        val rest = groups.filterNot { it.groupKey == primary.groupKey }.shuffled().take(count - 1)
        return listOf(primary) + rest
    }

    private fun querySpecificPhotos(
        context: Context,
        privacy: PrivacyContext,
        wantedIds: List<String>
    ): List<WidgetPhoto> {
        val rawNumericIds = wantedIds.mapNotNull { id ->
            id.removePrefix(LocalMediaRepository.LOCAL_ITEM_PREFIX).toLongOrNull()
        }.distinct()
        if (rawNumericIds.isEmpty()) return emptyList()

        val found = HashMap<String, WidgetPhoto>(rawNumericIds.size)
        // Indexed SQL IN(...) chunks avoid scanning the entire MediaStore table for old picked photos
        for (chunk in rawNumericIds.chunked(200)) {
            val selection = "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",")})"
            queryMediaStoreImages(context, selection = selection) { photo, _ ->
                if (photo.bucketId in privacy.blockedBucketIds) return@queryMediaStoreImages true
                found[photo.luminaItemId] = photo
                true
            }
        }
        // Preserve user's exact selection order!
        return wantedIds.mapNotNull { found[it] }
    }

    private fun queryAlbumPhotos(
        context: Context,
        privacy: PrivacyContext,
        allowedBuckets: Set<String>,
        excludeScreenshots: Boolean
    ): List<WidgetPhoto> {
        if (allowedBuckets.isEmpty()) return emptyList()
        val out = ArrayList<WidgetPhoto>(128)
        queryMediaStoreImages(context) { photo, _ ->
            if (photo.bucketId in privacy.blockedBucketIds) return@queryMediaStoreImages true
            if (photo.bucketId !in allowedBuckets) return@queryMediaStoreImages true
            if (excludeScreenshots && photo.isScreenshot) return@queryMediaStoreImages true
            out.add(photo)
            out.size < 1200
        }
        return out
    }

    private fun queryFavoritesOrRecent(
        context: Context,
        privacy: PrivacyContext,
        favoritesOnly: Boolean,
        excludeScreenshots: Boolean
    ): List<WidgetPhoto> {
        val favorites = ArrayList<WidgetPhoto>(64)
        val recent = ArrayList<WidgetPhoto>(64)
        queryMediaStoreImages(context) { photo, isSysFav ->
            if (photo.bucketId in privacy.blockedBucketIds) return@queryMediaStoreImages true
            if (excludeScreenshots && photo.isScreenshot) return@queryMediaStoreImages true
            val isFav = photo.luminaItemId in privacy.favoriteIds || isSysFav
            if (isFav) {
                if (favorites.size < 160) favorites.add(photo)
            } else if (!favoritesOnly && recent.size < 80) {
                recent.add(photo)
            }
            if (favoritesOnly) {
                favorites.size < 160
            } else {
                favorites.size < 80 || recent.size < 80
            }
        }
        return if (favoritesOnly) favorites else (favorites + recent)
    }

    /**
     * Uniform reservoir sampling over the ENTIRE visible phone photo library so every photo
     * on the phone — new or years old — has an equal probability of appearing!
     */
    private fun queryUniformRandomPhotos(
        context: Context,
        privacy: PrivacyContext,
        excludeScreenshots: Boolean
    ): List<WidgetPhoto> {
        val reservoir = ArrayList<WidgetPhoto>(RANDOM_RESERVOIR_SIZE)
        var seen = 0
        queryMediaStoreImages(context) { photo, _ ->
            if (photo.bucketId in privacy.blockedBucketIds) return@queryMediaStoreImages true
            if (excludeScreenshots && photo.isScreenshot) return@queryMediaStoreImages true
            seen++
            if (reservoir.size < RANDOM_RESERVOIR_SIZE) {
                reservoir.add(photo)
            } else {
                val slot = Random.nextInt(seen)
                if (slot < RANDOM_RESERVOIR_SIZE) {
                    reservoir[slot] = photo
                }
            }
            true
        }
        return reservoir
    }

    private inline fun queryMediaStoreImages(
        context: Context,
        selection: String = "${MediaStore.MediaColumns.SIZE} > 0",
        onPhoto: (photo: WidgetPhoto, isSystemFavorite: Boolean) -> Boolean
    ) {
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.BUCKET_ID)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.WIDTH)
            add(MediaStore.Images.Media.HEIGHT)
            add(MediaStore.Images.Media.ORIENTATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.MediaColumns.IS_FAVORITE)
            }
        }.toTypedArray()

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val bucketIdCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID)
                val bucketCol = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                val takenCol = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val widthCol = c.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = c.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val orientCol = c.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else -1
                val favCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE)
                } else -1

                while (c.moveToNext()) {
                    val rawBucket = if (bucketIdCol >= 0) c.getString(bucketIdCol) else null
                    val luminaBucketId = "${LocalMediaRepository.LOCAL_BUCKET_PREFIX}${rawBucket ?: "default"}"
                    val id = c.getLong(idCol)
                    val luminaItemId = "${LocalMediaRepository.LOCAL_ITEM_PREFIX}$id"
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val album = if (bucketCol >= 0) c.getString(bucketCol) ?: "Photos" else "Photos"
                    val relPath = if (relPathCol >= 0) c.getString(relPathCol).orEmpty() else ""
                    val taken = if (takenCol >= 0) c.getLong(takenCol) else 0L
                    val ms = if (taken > 0L) taken else c.getLong(modCol) * 1000L
                    val wPx = if (widthCol >= 0) c.getInt(widthCol) else 0
                    val hPx = if (heightCol >= 0) c.getInt(heightCol) else 0
                    val orient = if (orientCol >= 0) c.getInt(orientCol) else 0
                    val isScreenshot = album.contains("screenshot", ignoreCase = true) ||
                        relPath.contains("screenshot", ignoreCase = true)
                    val isSysFav = favCol >= 0 && c.getInt(favCol) == 1

                    val keepGoing = onPhoto(
                        WidgetPhoto(
                            luminaItemId = luminaItemId,
                            uri = uri,
                            albumName = album,
                            takenAtMs = ms,
                            bucketId = luminaBucketId,
                            isScreenshot = isScreenshot,
                            width = wPx,
                            height = hPx,
                            orientation = orient
                        ),
                        isSysFav
                    )
                    if (!keepGoing) break
                }
            }
        }.onFailure { Log.w(TAG, "Unable to query MediaStore images for widget", it) }
    }

    private suspend fun loadPrivacyContext(
        context: Context,
        needFavorites: Boolean,
        needLocations: Boolean,
        needCollections: Boolean
    ): PrivacyContext? {
        val db = (context.applicationContext as? LuminaApplication)?.container?.database
            ?: return null
        val extrasDao = db.galleryExtrasDao()
        val blocked = runCatching {
            (extrasDao.getAllLockedAlbumIds() + extrasDao.getAllHiddenAlbumIds()).toHashSet()
        }.getOrElse {
            // Fail closed: never risk leaking locked/hidden photos if DB check fails!
            Log.w(TAG, "Unable to verify locked/hidden album exclusions", it)
            return null
        }
        val favorites = if (needFavorites) {
            runCatching { extrasDao.getAllFavoriteItemIds().toHashSet() }.getOrDefault(emptySet())
        } else emptySet()
        val locations = if (needLocations) {
            runCatching { extrasDao.observeCachedLocations().first().associateBy { it.itemId } }
                .getOrDefault(emptyMap())
        } else emptyMap()
        val collections = if (needCollections) {
            runCatching {
                val colDao = db.albumCollectionDao()
                val cols = colDao.getAllCollections().map { it.id to it.parentCollectionId }
                val layouts = colDao.getAllLayouts().map { it.itemId to it.parentCollectionId }
                expandCollectionsToBuckets(cols, layouts, blocked)
            }.getOrDefault(emptyMap())
        } else emptyMap()

        return PrivacyContext(blocked, favorites, locations, collections)
    }

    private fun expandSelectedAlbums(selectedIds: List<String>, privacy: PrivacyContext): Set<String> {
        val out = LinkedHashSet<String>()
        for (id in selectedIds) {
            val expanded = privacy.collectionToBuckets[id]
            if (expanded != null) {
                out.addAll(expanded)
            } else if (id !in privacy.blockedBucketIds) {
                out.add(id)
            }
        }
        return out
    }

    private fun expandCollectionsToBuckets(
        collections: List<Pair<String, String?>>,
        layouts: List<Pair<String, String?>>,
        blockedBuckets: Set<String>
    ): Map<String, Set<String>> {
        val childItemsByParent = HashMap<String, MutableList<String>>()
        for ((itemId, parent) in layouts) {
            if (parent != null) {
                childItemsByParent.getOrPut(parent) { mutableListOf() }.add(itemId)
            }
        }
        for ((colId, parent) in collections) {
            if (parent != null) {
                childItemsByParent.getOrPut(parent) { mutableListOf() }.add(colId)
            }
        }
        val result = HashMap<String, Set<String>>()
        fun resolve(colId: String, visiting: MutableSet<String>): Set<String> {
            result[colId]?.let { return it }
            if (!visiting.add(colId)) return emptySet()
            val buckets = LinkedHashSet<String>()
            for (child in childItemsByParent[colId].orEmpty()) {
                if (child.startsWith(LocalMediaRepository.LOCAL_BUCKET_PREFIX)) {
                    if (child !in blockedBuckets) buckets.add(child)
                } else {
                    buckets.addAll(resolve(child, visiting))
                }
            }
            visiting.remove(colId)
            result[colId] = buckets
            return buckets
        }
        for ((colId, _) in collections) {
            resolve(colId, HashSet())
        }
        return result
    }

    private fun albumPriority(name: String): Int = when {
        name.equals("Camera", ignoreCase = true) -> 0
        name.equals("DCIM", ignoreCase = true) -> 1
        name.equals("Screenshots", ignoreCase = true) -> 2
        name.equals("Pictures", ignoreCase = true) -> 3
        name.equals("Download", ignoreCase = true) || name.equals("Downloads", ignoreCase = true) -> 4
        else -> 10
    }

    private data class MutableAlbumAcc(
        val name: String,
        val coverUri: String,
        var count: Int
    )
}
