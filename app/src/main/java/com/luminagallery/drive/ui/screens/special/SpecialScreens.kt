package com.luminagallery.drive.ui.screens.special

import android.app.Activity
import android.content.IntentSender
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocationScanProgress
import com.luminagallery.drive.domain.model.GeoTaggedMediaItem
import com.luminagallery.drive.domain.model.LocationPhotoGroup
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.domain.model.SpecialGalleryFilter
import com.luminagallery.drive.ui.components.FastScrollThumb
import com.luminagallery.drive.ui.components.PinchZoomGridContainer
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.HyperOsTrashConfirmSheet
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.MapPhotoCluster
import com.luminagallery.drive.ui.components.MediaGridItem
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.SamsungPhotoMap
import com.luminagallery.drive.ui.components.TrashConfirmMode
import com.luminagallery.drive.ui.components.oneUiClickable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Unified Samsung Gallery ≡ Menu destination for:
 *  - Videos (all videos on device + Google Drive)
 *  - Favorites (all hearted photos & videos across device + Google Drive)
 *  - Recent (most recently added items)
 *  - Trash (Recycle Bin with Restore & Empty Trash)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecialCollectionScreen(
    filter: SpecialGalleryFilter,
    onNavigateBack: () -> Unit,
    onOpenViewer: (filter: SpecialGalleryFilter, itemId: String) -> Unit,
    lastViewedItemId: String? = null,
    onConsumedLastViewedItemId: () -> Unit = {}
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val snackbarHostState = remember { SnackbarHostState() }
    val networkState by container.networkMonitor.networkState.collectAsStateWithLifecycle()
    val prefs by container.preferencesManager.preferencesFlow
        .collectAsStateWithLifecycle(initialValue = container.preferencesManager.latestPreferences)
    val highResAllowed = networkState.isUnmeteredWifi || !prefs.wifiOnlyHighRes
    val favoriteIds by container.galleryExtrasRepository.favoriteItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())

    val itemsFlow = remember(filter) {
        when (filter) {
            SpecialGalleryFilter.VIDEOS -> combine(
                container.localMediaRepository.observeAllLocalMedia(),
                container.galleryRepository.searchMedia("", MediaType.VIDEO, null, null),
                container.galleryExtrasRepository.excludedAlbumIdsFlow
            ) { local, drive, excludedIds ->
                val blockedIds = container.galleryRepository.expandBlockedFolderIds(excludedIds)
                (local.filter { it.isVideo } + drive)
                    .filterNot { it.parentId in blockedIds }
                    .sortedByDescending { it.createdTimeEpochMs }
            }

            SpecialGalleryFilter.FAVORITES -> combine(
                container.localMediaRepository.observeAllLocalMedia(),
                container.galleryRepository.searchMedia("", null, null, null),
                container.galleryExtrasRepository.favoriteItemIdsFlow,
                container.galleryExtrasRepository.excludedAlbumIdsFlow
            ) { local, drive, favIds, excludedIds ->
                val blockedIds = container.galleryRepository.expandBlockedFolderIds(excludedIds)
                (local + drive)
                    .filter { it.id in favIds && it.parentId !in blockedIds }
                    .sortedByDescending { it.createdTimeEpochMs }
            }

            SpecialGalleryFilter.RECENT -> combine(
                container.localMediaRepository.observeAllLocalMedia(),
                container.galleryRepository.searchMedia("", null, null, null),
                container.galleryExtrasRepository.excludedAlbumIdsFlow
            ) { local, drive, excludedIds ->
                val blockedIds = container.galleryRepository.expandBlockedFolderIds(excludedIds)
                (local + drive)
                    .filterNot { it.parentId in blockedIds }
                    .sortedByDescending { it.createdTimeEpochMs }
                    .take(300)
            }

            SpecialGalleryFilter.TRASH ->
                container.localMediaRepository.observeTrashedLocalMedia()
        }
    }

    val mediaItems by itemsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedTrashIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingSender by remember { mutableStateOf<IntentSender?>(null) }
    var pendingMessage by remember { mutableStateOf<String?>(null) }
    var pendingTrashAction by remember { mutableStateOf<Pair<TrashConfirmMode, List<MediaItem>>?>(null) }

    BackHandler(enabled = selectedTrashIds.isNotEmpty()) {
        selectedTrashIds = emptySet()
    }

    val intentSenderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingSender = null
        if (result.resultCode == Activity.RESULT_OK) {
            selectedTrashIds = emptySet()
            container.localMediaRepository.notifyPermissionChanged()
        }
    }

    LaunchedEffect(pendingSender) {
        val sender = pendingSender ?: return@LaunchedEffect
        intentSenderLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(pendingMessage) {
        val msg = pendingMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        pendingMessage = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { LuminaSnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = filter.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = FormatUtils.formatMediaBreakdown(mediaItems),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedTrashIds.isNotEmpty()) {
                            selectedTrashIds = emptySet()
                        } else {
                            onNavigateBack()
                        }
                    }) {
                        Icon(
                            imageVector = if (selectedTrashIds.isNotEmpty()) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = if (selectedTrashIds.isNotEmpty()) "Clear selection" else "Back"
                        )
                    }
                },
                actions = {
                    if (filter == SpecialGalleryFilter.TRASH && mediaItems.isNotEmpty()) {
                        val allSelected = selectedTrashIds.size == mediaItems.size
                        IconButton(
                            onClick = {
                                selectedTrashIds = if (allSelected) {
                                    emptySet()
                                } else {
                                    mediaItems.mapTo(LinkedHashSet()) { it.id }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SelectAll,
                                contentDescription = if (allSelected) "Clear selection" else "Select all"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (mediaItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when (filter) {
                            SpecialGalleryFilter.VIDEOS -> "No videos found"
                            SpecialGalleryFilter.FAVORITES -> "Tap the heart on any photo to add Favorites"
                            SpecialGalleryFilter.RECENT -> "No recent photos or videos"
                            SpecialGalleryFilter.TRASH -> "Trash is empty"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (filter == SpecialGalleryFilter.TRASH) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            val targets = if (selectedTrashIds.isNotEmpty()) {
                                mediaItems.filter { it.id in selectedTrashIds }
                            } else mediaItems

                            FilledTonalButton(
                                onClick = {
                                    pendingTrashAction = TrashConfirmMode.RESTORE_FROM_TRASH to targets
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.RestoreFromTrash, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (selectedTrashIds.isEmpty()) "Restore all" else "Restore (${targets.size})")
                            }

                            FilledTonalButton(
                                onClick = {
                                    pendingTrashAction = TrashConfirmMode.DELETE_PERMANENTLY to targets
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (selectedTrashIds.isEmpty()) "Empty" else "Delete (${targets.size})")
                            }
                        }
                    }

                    PinchZoomGridContainer(
                        currentColumns = prefs.gridColumns,
                        onColumnsChange = { cols ->
                            scope.launch { container.preferencesManager.setGridColumns(cols) }
                        },
                        minColumns = 1,
                        maxColumns = 6,
                        modifier = Modifier.weight(1f)
                    ) {
                        val returnMorphIds = remember(mediaItems) { mediaItems.map { it.id } }
                        com.luminagallery.drive.ui.components.ReturnMorphScrollEffect(
                            gridState = gridState,
                            lastViewedItemId = lastViewedItemId,
                            itemIds = returnMorphIds,
                            onConsumed = onConsumedLastViewedItemId
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(prefs.gridColumns),
                            state = gridState,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                            verticalArrangement = Arrangement.spacedBy(1.5.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = mediaItems,
                                key = { it.id }
                            ) { item ->
                                val isTrashSelected = item.id in selectedTrashIds
                                val trashSelectionActive = filter == SpecialGalleryFilter.TRASH && selectedTrashIds.isNotEmpty()
                                MediaGridItem(
                                    item = item,
                                    isUnmeteredWifi = highResAllowed,
                                    isSelectionMode = trashSelectionActive,
                                    isSelected = isTrashSelected,
                                    isFavorite = item.id in favoriteIds,
                                    showCloudBadge = prefs.showCloudBadges,
                                    onClick = {
                                        if (trashSelectionActive) {
                                            selectedTrashIds = if (isTrashSelected) {
                                                selectedTrashIds - item.id
                                            } else {
                                                selectedTrashIds + item.id
                                            }
                                        } else {
                                            onOpenViewer(filter, item.id)
                                        }
                                    },
                                    onLongClick = {
                                        if (filter == SpecialGalleryFilter.TRASH) {
                                            selectedTrashIds = if (isTrashSelected) {
                                                selectedTrashIds - item.id
                                            } else {
                                                selectedTrashIds + item.id
                                            }
                                        }
                                    }
                                )
                            }
                        }

                        FastScrollThumb(
                            gridState = gridState,
                            totalItemCount = mediaItems.size,
                            labelForIndex = { idx -> mediaItems.getOrNull(idx)?.monthYearLabel },
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                }
            }
        }
    }

    pendingTrashAction?.let { (mode, targets) ->
        HyperOsTrashConfirmSheet(
            items = targets,
            mode = mode,
            canSkipSystemPrompt = container.localMediaRepository.canManageMediaDirectly(),
            supportsSkipPromptSetting = container.localMediaRepository.supportsManageMediaPermission(),
            onOpenManageMediaSettings = {
                container.localMediaRepository.buildManageMediaSettingsIntent()?.let { intent ->
                    runCatching { context.startActivity(intent) }
                }
            },
            onConfirm = {
                pendingTrashAction = null
                val launchFallback: () -> Unit = {
                    val sender = when (mode) {
                        TrashConfirmMode.RESTORE_FROM_TRASH ->
                            container.localMediaRepository.buildTrashIntentSender(targets, toTrash = false)
                        TrashConfirmMode.DELETE_PERMANENTLY ->
                            container.localMediaRepository.buildPermanentDeleteIntentSender(targets)
                        TrashConfirmMode.MOVE_TO_TRASH ->
                            container.localMediaRepository.buildTrashIntentSender(targets, toTrash = true)
                    }
                    if (sender != null) {
                        pendingSender = sender
                    } else {
                        pendingMessage = if (mode == TrashConfirmMode.RESTORE_FROM_TRASH) {
                            "Restore requires Android 11 or newer"
                        } else {
                            "Permanent delete requires Android 11 or newer"
                        }
                    }
                }
                if (container.localMediaRepository.canDeleteDirectlyWithoutDialog()) {
                    scope.launch {
                        val result = when (mode) {
                            TrashConfirmMode.RESTORE_FROM_TRASH ->
                                container.localMediaRepository.trashLocalItemsDirectly(targets, toTrash = false)
                            TrashConfirmMode.DELETE_PERMANENTLY ->
                                container.localMediaRepository.deleteLocalItemsPermanentlyDirectly(targets)
                            TrashConfirmMode.MOVE_TO_TRASH ->
                                container.localMediaRepository.trashLocalItemsDirectly(targets, toTrash = true)
                        }
                        result.onSuccess { count ->
                            selectedTrashIds = emptySet()
                            container.localMediaRepository.notifyPermissionChanged()
                            pendingMessage = when (mode) {
                                TrashConfirmMode.RESTORE_FROM_TRASH ->
                                    if (count == 1) "Restored item" else "Restored $count items"
                                TrashConfirmMode.DELETE_PERMANENTLY ->
                                    if (count == 1) "Permanently deleted" else "Permanently deleted $count items"
                                TrashConfirmMode.MOVE_TO_TRASH ->
                                    if (count == 1) "Moved to trash" else "Moved $count items to trash"
                            }
                        }.onFailure {
                            launchFallback()
                        }
                    }
                } else {
                    launchFallback()
                }
            },
            onDismiss = { pendingTrashAction = null }
        )
    }
}

private enum class LocationsOverviewSheetMode { PEEK, HALF, EXPANDED }

/**
 * Pixel-faithful Samsung One UI Gallery Locations Screen:
 *  - Full-bleed live GPU vector map centered on your #1 country (e.g. Netherlands) behind a
 *    3-state Samsung dark overflow card (`PEEK` / `HALF` / `EXPANDED`).
 *  - Interacting with the map smoothly collapses the folder list into a slim bottom bar so you
 *    can explore the whole map freely; dragging/tapping the bottom bar brings your folders right back!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    onNavigateBack: () -> Unit,
    onOpenPhoto: (MediaItem, String?) -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val networkState by container.networkMonitor.networkState.collectAsStateWithLifecycle()
    val prefs by container.preferencesManager.preferencesFlow
        .collectAsStateWithLifecycle(initialValue = container.preferencesManager.latestPreferences)
    val highResAllowed = networkState.isUnmeteredWifi || !prefs.wifiOnlyHighRes
    val favoriteIds by container.galleryExtrasRepository.favoriteItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())

    var permRetryTick by remember { mutableIntStateOf(0) }
    var hasLocPermission by remember {
        mutableStateOf(container.localMediaRepository.hasMediaLocationPermission())
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocPermission = container.localMediaRepository.hasMediaLocationPermission()
        container.localMediaRepository.notifyPermissionChanged()
        permRetryTick++
    }

    val scanProgress by container.galleryExtrasRepository.locationProgressFlow
        .collectAsStateWithLifecycle()
    val excludedAlbumIds by container.galleryExtrasRepository.excludedAlbumIdsFlow
        .collectAsStateWithLifecycle(
            initialValue = container.galleryExtrasRepository.latestLockedAlbumIds +
                container.galleryExtrasRepository.latestHiddenAlbumIds
        )

    val visibleLocationGroups = remember(scanProgress.groups, excludedAlbumIds) {
        if (excludedAlbumIds.isEmpty()) {
            scanProgress.groups
        } else {
            scanProgress.groups.mapNotNull { group ->
                val allowedItems = group.items.filterNot { it.parentId in excludedAlbumIds }
                val allowedGeo = group.geoItems.filterNot { it.item.parentId in excludedAlbumIds }
                if (allowedItems.isEmpty()) {
                    null
                } else {
                    group.copy(
                        coverItem = allowedItems.first(),
                        items = allowedItems,
                        geoItems = allowedGeo
                    )
                }
            }
        }
    }

    LaunchedEffect(permRetryTick) {
        val local = container.localMediaRepository.observeAllLocalMedia().first()
        container.galleryExtrasRepository.syncLocationsIndex(local)
    }

    var selectedLocationKey by remember { mutableStateOf<String?>(null) }
    var mapSubViewGridOnly by remember { mutableStateOf(false) }

    val selectedLocation = remember(visibleLocationGroups, selectedLocationKey) {
        selectedLocationKey?.let { key -> visibleLocationGroups.firstOrNull { it.locationKey == key } }
    }

    val isInCityDetail = selectedLocation != null

    // Group cities by Country (sorted by total photo count descending, like Samsung's "Netherlands")
    val countrySections = remember(visibleLocationGroups) {
        visibleLocationGroups
            .groupBy { it.country.ifBlank { "Other" } }
            .entries
            .map { (country, cities) -> country to cities.sortedByDescending { it.items.size } }
            .sortedByDescending { (_, cities) -> cities.sumOf { it.items.size } }
    }

    val allGeoItems = remember(visibleLocationGroups) {
        visibleLocationGroups.flatMap { it.geoItems }
    }
    // Country with the most photo locations (e.g. Netherlands!) -> nicely centered on the top hero map!
    val topCountryGeoItems = remember(countrySections) {
        countrySections.firstOrNull()?.second?.flatMap { it.geoItems }.orEmpty()
    }
    var overviewSelectedPin by remember { mutableStateOf<MapPhotoCluster?>(null) }
    var sheetMode by remember { mutableStateOf(LocationsOverviewSheetMode.HALF) }

    BackHandler(enabled = isInCityDetail || overviewSelectedPin != null || sheetMode != LocationsOverviewSheetMode.HALF) {
        when {
            mapSubViewGridOnly -> mapSubViewGridOnly = false
            selectedLocation != null -> selectedLocationKey = null
            overviewSelectedPin != null -> {
                overviewSelectedPin = null
                sheetMode = LocationsOverviewSheetMode.HALF
            }
            sheetMode != LocationsOverviewSheetMode.HALF -> {
                sheetMode = LocationsOverviewSheetMode.HALF
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = selectedLocation?.displayName ?: "Locations",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val sub = when {
                            selectedLocation != null -> FormatUtils.formatMediaBreakdown(selectedLocation.items)
                            visibleLocationGroups.isNotEmpty() -> "${visibleLocationGroups.size} ${if (visibleLocationGroups.size == 1) "place" else "places"}"
                            else -> null
                        }
                        if (!sub.isNullOrBlank()) {
                            Text(
                                text = sub,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            when {
                                mapSubViewGridOnly -> mapSubViewGridOnly = false
                                selectedLocation != null -> selectedLocationKey = null
                                overviewSelectedPin != null -> {
                                    overviewSelectedPin = null
                                    sheetMode = LocationsOverviewSheetMode.HALF
                                }
                                sheetMode == LocationsOverviewSheetMode.PEEK -> {
                                    sheetMode = LocationsOverviewSheetMode.HALF
                                }
                                else -> onNavigateBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isInCityDetail && allGeoItems.isNotEmpty()) {
                        IconButton(onClick = {
                            overviewSelectedPin = null
                            sheetMode = if (sheetMode == LocationsOverviewSheetMode.PEEK) {
                                LocationsOverviewSheetMode.HALF
                            } else {
                                LocationsOverviewSheetMode.PEEK
                            }
                        }) {
                            Icon(
                                imageVector = if (sheetMode == LocationsOverviewSheetMode.PEEK) {
                                    Icons.Rounded.GridView
                                } else {
                                    Icons.Outlined.Map
                                },
                                contentDescription = if (sheetMode == LocationsOverviewSheetMode.PEEK) {
                                    "Show location folders"
                                } else {
                                    "Focus on map"
                                }
                            )
                        }
                    } else if (isInCityDetail) {
                        IconButton(onClick = { mapSubViewGridOnly = !mapSubViewGridOnly }) {
                            Icon(
                                imageVector = if (mapSubViewGridOnly) Icons.Outlined.Map else Icons.Rounded.GridView,
                                contentDescription = if (mapSubViewGridOnly) "Map view" else "Grid view"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (scanProgress.isScanning && scanProgress.totalCount > 0) {
                LinearProgressIndicator(
                    progress = {
                        (scanProgress.scannedCount.toFloat() / scanProgress.totalCount.coerceAtLeast(1))
                            .coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                )
            }

            AnimatedContent(
                targetState = Pair(selectedLocation?.locationKey, mapSubViewGridOnly),
                transitionSpec = {
                    (scaleIn(tween(300, easing = OneUiMotion.DecelerateEasing), initialScale = 0.94f) +
                        fadeIn(tween(220)))
                        .togetherWith(
                            scaleOut(tween(200), targetScale = 0.96f) + fadeOut(tween(150))
                        )
                },
                label = "locationsModeTransition",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { (_, isGridOnly) ->
                val activeCity = selectedLocation

                when {
                    !hasLocPermission && scanProgress.groups.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(26.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Text(
                                        text = "Allow media location access",
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Text(
                                        text = "Android hides GPS tags in photos until media location permission is granted. Tap below to group your photos by country, city & map pins.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = {
                                            permLauncher.launch(
                                                container.localMediaRepository.requiredMediaPermissions()
                                            )
                                        },
                                        shape = RoundedCornerShape(16.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Allow Media Location")
                                    }
                                }
                            }
                        }
                    }

                    activeCity != null && isGridOnly -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(prefs.gridColumns),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                            verticalArrangement = Arrangement.spacedBy(1.5.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(activeCity.items, key = { it.id }) { item ->
                                MediaGridItem(
                                    item = item,
                                    isUnmeteredWifi = highResAllowed,
                                    isSelectionMode = false,
                                    isSelected = false,
                                    isFavorite = item.id in favoriteIds,
                                    onClick = { onOpenPhoto(item, activeCity.locationKey) }
                                )
                            }
                        }
                    }

                    activeCity != null -> {
                        // Full-screen City Samsung Interactive Vector Map + Draggable Photo Drawer
                        SamsungLocationMapDetailView(
                            geoItems = activeCity.geoItems,
                            initialFocusItems = activeCity.geoItems,
                            gridColumns = prefs.gridColumns,
                            isUnmeteredWifi = highResAllowed,
                            favoriteIds = favoriteIds,
                            onOpenPhoto = { item -> onOpenPhoto(item, activeCity.locationKey) }
                        )
                    }

                    scanProgress.isScanning && scanProgress.groups.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    text = if (scanProgress.totalCount > 0) {
                                        "Scanning photo locations (${scanProgress.scannedCount}/${scanProgress.totalCount})…"
                                    } else {
                                        "Scanning photo locations…"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    countrySections.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Text(
                                    text = "No geotagged photos found",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    else -> {
                        // Full-bleed Interactive Vector Map centered on top country (e.g. Netherlands)
                        // behind a 3-state overlapping Samsung dark folder card (`PEEK` / `HALF` / `EXPANDED`).
                        // Touching/panning/zooming the map smoothly collapses the folder card to a slim bottom bar!
                        val density = LocalDensity.current
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val totalH = maxHeight
                            val peekCardHeight = 62.dp
                            val halfCardHeight = (totalH * 0.52f).coerceIn(260.dp, totalH - 220.dp)
                            val expandedCardHeight = (totalH * 0.90f).coerceAtLeast(halfCardHeight)

                            val targetCardHeight = when {
                                overviewSelectedPin != null && sheetMode == LocationsOverviewSheetMode.EXPANDED -> expandedCardHeight
                                overviewSelectedPin != null -> halfCardHeight
                                sheetMode == LocationsOverviewSheetMode.PEEK -> peekCardHeight
                                sheetMode == LocationsOverviewSheetMode.EXPANDED -> expandedCardHeight
                                else -> halfCardHeight
                            }
                            val cardHeight by animateDpAsState(
                                targetValue = targetCardHeight,
                                animationSpec = OneUiMotion.snappySpring(),
                                label = "locationsOverflowCardHeight"
                            )
                            val activeCameraPadBottomPx = with(density) {
                                when (sheetMode) {
                                    LocationsOverviewSheetMode.PEEK -> peekCardHeight.roundToPx()
                                    else -> halfCardHeight.roundToPx()
                                }
                            }

                            // 1. Full-Bleed Live Interactive Vector Map (NO resize/jump when sheet hides!)
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 6.dp)
                                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            ) {
                                SamsungPhotoMap(
                                    geoItems = allGeoItems,
                                    initialFocusItems = topCountryGeoItems.ifEmpty { allGeoItems },
                                    selectedClusterId = overviewSelectedPin?.id,
                                    cameraPaddingBottomPx = activeCameraPadBottomPx,
                                    isMapExpanded = sheetMode == LocationsOverviewSheetMode.PEEK,
                                    onUserInteractMap = {
                                        // Focusing / panning / zooming the map smoothly hides the folder card!
                                        if (overviewSelectedPin != null) {
                                            overviewSelectedPin = null
                                        }
                                        if (sheetMode != LocationsOverviewSheetMode.PEEK) {
                                            sheetMode = LocationsOverviewSheetMode.PEEK
                                        }
                                    },
                                    onSelectCluster = { cluster ->
                                        overviewSelectedPin = cluster
                                        if (cluster != null) {
                                            sheetMode = LocationsOverviewSheetMode.HALF
                                        }
                                    },
                                    onToggleExpandMap = {
                                        overviewSelectedPin = null
                                        sheetMode = if (sheetMode == LocationsOverviewSheetMode.PEEK) {
                                            LocationsOverviewSheetMode.HALF
                                        } else {
                                            LocationsOverviewSheetMode.PEEK
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            // 2. Overlapping Samsung Overflow Card with 3-State Drag Handle & Country Folders!
                            Surface(
                                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                shadowElevation = 16.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(cardHeight)
                                    .border(
                                        width = 0.8.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                                    )
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    // Smooth 3-state Drag / Tap Handle Bar
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerInput(sheetMode, overviewSelectedPin) {
                                                var dragAccum = 0f
                                                detectVerticalDragGestures(
                                                    onDragStart = { dragAccum = 0f },
                                                    onVerticalDrag = { _, dragAmount ->
                                                        dragAccum += dragAmount
                                                    },
                                                    onDragEnd = {
                                                        when {
                                                            dragAccum < -36f -> {
                                                                sheetMode = when (sheetMode) {
                                                                    LocationsOverviewSheetMode.PEEK -> LocationsOverviewSheetMode.HALF
                                                                    LocationsOverviewSheetMode.HALF -> LocationsOverviewSheetMode.EXPANDED
                                                                    LocationsOverviewSheetMode.EXPANDED -> LocationsOverviewSheetMode.EXPANDED
                                                                }
                                                            }
                                                            dragAccum > 36f -> {
                                                                if (overviewSelectedPin != null && sheetMode == LocationsOverviewSheetMode.HALF) {
                                                                    overviewSelectedPin = null
                                                                    sheetMode = LocationsOverviewSheetMode.PEEK
                                                                } else {
                                                                    sheetMode = when (sheetMode) {
                                                                        LocationsOverviewSheetMode.EXPANDED -> LocationsOverviewSheetMode.HALF
                                                                        LocationsOverviewSheetMode.HALF -> LocationsOverviewSheetMode.PEEK
                                                                        LocationsOverviewSheetMode.PEEK -> LocationsOverviewSheetMode.PEEK
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            .clickable {
                                                if (overviewSelectedPin != null) {
                                                    overviewSelectedPin = null
                                                    sheetMode = LocationsOverviewSheetMode.HALF
                                                } else {
                                                    sheetMode = when (sheetMode) {
                                                        LocationsOverviewSheetMode.PEEK -> LocationsOverviewSheetMode.HALF
                                                        LocationsOverviewSheetMode.HALF -> LocationsOverviewSheetMode.EXPANDED
                                                        LocationsOverviewSheetMode.EXPANDED -> LocationsOverviewSheetMode.HALF
                                                    }
                                                }
                                            }
                                            .padding(top = 10.dp, bottom = 8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(38.dp)
                                                .height(4.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f))
                                        )
                                        val activePin = overviewSelectedPin
                                        when {
                                            activePin != null -> {
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    text = "${activePin.count} ${if (activePin.count == 1) "item" else "items"}",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            sheetMode == LocationsOverviewSheetMode.PEEK -> {
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    text = "${visibleLocationGroups.size} ${if (visibleLocationGroups.size == 1) "place" else "places"}",
                                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }

                                    if (sheetMode != LocationsOverviewSheetMode.PEEK || overviewSelectedPin != null) {
                                        val activePin = overviewSelectedPin
                                        if (activePin != null) {
                                            // Quick grid of photos at the tapped map pin
                                            LazyVerticalGrid(
                                                columns = GridCells.Fixed(prefs.gridColumns),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                                                verticalArrangement = Arrangement.spacedBy(1.5.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxWidth()
                                            ) {
                                                items(activePin.items, key = { it.item.id }) { geo ->
                                                    val locKey = remember(geo.item.id, visibleLocationGroups) {
                                                        visibleLocationGroups.firstOrNull { grp ->
                                                            grp.items.any { it.id == geo.item.id }
                                                        }?.locationKey
                                                    }
                                                    MediaGridItem(
                                                        item = geo.item,
                                                        isUnmeteredWifi = highResAllowed,
                                                        isSelectionMode = false,
                                                        isSelected = false,
                                                        isFavorite = geo.item.id in favoriteIds,
                                                        onClick = { onOpenPhoto(geo.item, locKey) }
                                                    )
                                                }
                                            }
                                        } else {
                                            // Country-grouped 3-column Samsung Location Folders
                                            LazyVerticalGrid(
                                                columns = GridCells.Fixed(3),
                                                contentPadding = PaddingValues(
                                                    start = 16.dp,
                                                    end = 16.dp,
                                                    top = 2.dp,
                                                    bottom = 24.dp
                                                ),
                                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .fillMaxWidth()
                                            ) {
                                                countrySections.forEach { (country, cities) ->
                                                    item(
                                                        key = "country_$country",
                                                        span = { GridItemSpan(maxLineSpan) }
                                                    ) {
                                                        Text(
                                                            text = country,
                                                            style = MaterialTheme.typography.titleMedium.copy(
                                                                fontWeight = FontWeight.SemiBold
                                                            ),
                                                            color = MaterialTheme.colorScheme.onBackground,
                                                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                                        )
                                                    }

                                                    items(
                                                        items = cities,
                                                        key = { it.locationKey }
                                                    ) { group ->
                                                        Column(
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .oneUiClickable(
                                                                    pressedScale = 0.94f,
                                                                    hoveredScale = 1.025f,
                                                                    onClick = {
                                                                        mapSubViewGridOnly = false
                                                                        selectedLocationKey = group.locationKey
                                                                    }
                                                                )
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .fillMaxWidth()
                                                                    .aspectRatio(1f)
                                                                    .clip(RoundedCornerShape(18.dp))
                                                                    .background(MaterialTheme.colorScheme.surfaceContainer)
                                                            ) {
                                                                AsyncImage(
                                                                    model = ImageRequest.Builder(context)
                                                                        .data(group.coverItem.localContentUri)
                                                                        .memoryCacheKey(DriveConstants.stableThumbMemoryKey(group.coverItem.id))
                                                                        .build(),
                                                                    imageLoader = imageLoader,
                                                                    contentDescription = group.displayName,
                                                                    contentScale = ContentScale.Crop,
                                                                    modifier = Modifier.fillMaxSize()
                                                                )
                                                            }
                                                            Spacer(Modifier.height(6.dp))
                                                            Text(
                                                                text = group.displayName,
                                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                textAlign = TextAlign.Center,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                modifier = Modifier.padding(horizontal = 2.dp)
                                                            )
                                                            Text(
                                                                text = group.items.size.toString(),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                textAlign = TextAlign.Center,
                                                                maxLines = 1,
                                                                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SamsungLocationMapDetailView(
    geoItems: List<GeoTaggedMediaItem>,
    initialFocusItems: List<GeoTaggedMediaItem>,
    gridColumns: Int,
    isUnmeteredWifi: Boolean,
    favoriteIds: Set<String>,
    onOpenPhoto: (MediaItem) -> Unit
) {
    var selectedCluster by remember(geoItems) { mutableStateOf<MapPhotoCluster?>(null) }
    var sheetExpanded by remember { mutableStateOf(false) }

    val displayedItems = remember(selectedCluster, geoItems) {
        selectedCluster?.items?.map { it.item } ?: geoItems.map { it.item }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val expandedHeight = (maxHeight * 0.56f).coerceAtLeast(260.dp)
        val sheetHeight by animateDpAsState(
            targetValue = if (sheetExpanded) expandedHeight else 76.dp,
            animationSpec = OneUiMotion.snappySpring(),
            label = "mapSheetHeight"
        )

        // Rounded-top interactive Samsung Vector Photo Map!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 60.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        ) {
            SamsungPhotoMap(
                geoItems = geoItems,
                initialFocusItems = initialFocusItems,
                selectedClusterId = selectedCluster?.id,
                onUserInteractMap = {
                    if (sheetExpanded) sheetExpanded = false
                },
                onSelectCluster = { cluster ->
                    selectedCluster = cluster
                    if (cluster != null) {
                        sheetExpanded = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Theme-adaptive Draggable Bottom Sheet ("473 items")
        Surface(
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 12.dp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(sheetHeight)
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                // Drag Handle + Count Header + Clear-pin CTA
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            var dragAccum = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dragAccum = 0f },
                                onVerticalDrag = { _, dragAmount -> dragAccum += dragAmount },
                                onDragEnd = {
                                    if (dragAccum < -28f) sheetExpanded = true
                                    else if (dragAccum > 28f) sheetExpanded = false
                                }
                            )
                        }
                        .clickable { sheetExpanded = !sheetExpanded }
                        .padding(top = 10.dp, bottom = 10.dp, start = 16.dp, end = 16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f))
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "${displayedItems.size} ${if (displayedItems.size == 1) "item" else "items"}",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (selectedCluster != null) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable { selectedCluster = null }
                            ) {
                                Text(
                                    text = "Show all ${geoItems.size}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                if (sheetExpanded) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(gridColumns),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                        verticalArrangement = Arrangement.spacedBy(1.5.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(displayedItems, key = { it.id }) { item ->
                            MediaGridItem(
                                item = item,
                                isUnmeteredWifi = isUnmeteredWifi,
                                isSelectionMode = false,
                                isSelected = false,
                                isFavorite = item.id in favoriteIds,
                                onClick = { onOpenPhoto(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}
