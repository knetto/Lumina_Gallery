package com.luminagallery.drive.ui.screens.compare

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.HyperOsTrashConfirmSheet
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.SecureWindowEffect
import com.luminagallery.drive.ui.components.TrashConfirmMode
import com.luminagallery.drive.ui.components.findActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * Side-by-side Compare Mode for 2 to 4 photos:
 *  - Synchronized pinch-to-zoom (1x..6x), pan, & double-tap zoom across all panes (toggleable!).
 *  - Switch between Top/Bottom and Left/Right split when comparing 2 photos, or 2x2 grid for 3-4.
 *  - Quick Favorite heart & Trash actions per shot so picking the sharpest photo takes seconds.
 */
@Composable
fun CompareScreen(
    itemIds: List<String>,
    onNavigateBack: () -> Unit
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val favoriteIds by container.galleryExtrasRepository.favoriteItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val lockedAlbumIds by container.galleryExtrasRepository.lockedAlbumIdsFlow
        .collectAsStateWithLifecycle(initialValue = container.galleryExtrasRepository.latestLockedAlbumIds)
    val networkState by container.networkMonitor.networkState.collectAsStateWithLifecycle()
    val view = LocalView.current

    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    val isComparingLocked = remember(items, lockedAlbumIds) {
        lockedAlbumIds.isNotEmpty() && items.any { it.parentId in lockedAlbumIds }
    }
    SecureWindowEffect(enabled = isComparingLocked)

    // Ensure status bar icons remain crisp white over the pure-black Compare canvas even in Light Mode
    androidx.compose.runtime.DisposableEffect(view) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (prevLightStatus != null) controller.isAppearanceLightStatusBars = prevLightStatus
            if (prevLightNav != null) controller.isAppearanceLightNavigationBars = prevLightNav
        }
    }
    var syncZoom by remember { mutableStateOf(true) }
    var horizontalTwoUp by remember { mutableStateOf(false) }

    // Shared synchronized zoom/pan state
    var sharedScale by remember { mutableFloatStateOf(1f) }
    var sharedOffset by remember { mutableStateOf(Offset.Zero) }

    // Per-pane independent zoom/pan when syncZoom == false
    val individualScale = remember { mutableStateMapOf<String, Float>() }
    val individualOffset = remember { mutableStateMapOf<String, Offset>() }

    var confirmTrashItem by remember { mutableStateOf<MediaItem?>(null) }
    var pendingTrashItem by remember { mutableStateOf<MediaItem?>(null) }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val removed = pendingTrashItem
        pendingTrashItem = null
        if (result.resultCode == Activity.RESULT_OK && removed != null) {
            container.localMediaRepository.notifyPermissionChanged()
            items = items.filterNot { it.id == removed.id }
            scope.launch { snackbarHostState.showSnackbar("Moved ${removed.name} to Trash") }
            if (items.size < 2) onNavigateBack()
        }
    }

    LaunchedEffect(itemIds) {
        val cachedLocal = container.localMediaRepository.getCachedAlbumMedia(
            com.luminagallery.drive.data.local.LocalMediaRepository.LOCAL_BUCKET_ALL
        ).ifEmpty {
            runCatching { container.localMediaRepository.observeAllLocalMedia().first() }.getOrDefault(emptyList())
        }
        val allLocal = HashMap<String, MediaItem>(cachedLocal.size)
        cachedLocal.forEach { allLocal[it.id] = it }
        // Also search hidden/locked albums if comparing items inside a locked album
        container.localMediaRepository.getCachedLocalAlbums()
            .filter { it.isLocked }
            .forEach { album ->
                container.localMediaRepository.getCachedAlbumMedia(album.id).forEach { allLocal[it.id] = it }
            }

        val resolved = ArrayList<MediaItem>(itemIds.size)
        for (id in itemIds.take(4)) {
            val localMatch = allLocal[id]
            if (localMatch != null && !localMatch.isVideo) {
                resolved.add(localMatch)
            } else if (localMatch == null) {
                val driveEntity = container.database.driveItemDao().getById(id)
                if (driveEntity != null) {
                    val mapped = com.luminagallery.drive.data.repository.DriveMapper.entityToMediaItem(driveEntity)
                    if (!mapped.isVideo) resolved.add(mapped)
                }
            }
        }
        items = resolved
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Text(
                        text = "Compare (${items.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (items.size == 2) {
                        IconButton(onClick = { horizontalTwoUp = !horizontalTwoUp }) {
                            Icon(
                                imageVector = if (horizontalTwoUp) Icons.Rounded.ViewAgenda else Icons.Rounded.ViewColumn,
                                contentDescription = "Toggle layout",
                                tint = Color.White
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (syncZoom) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color(0xFF222228),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { syncZoom = !syncZoom }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (syncZoom) Icons.Rounded.Link else Icons.Rounded.LinkOff,
                                contentDescription = null,
                                tint = if (syncZoom) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (syncZoom) "Sync zoom" else "Free zoom",
                                color = if (syncZoom) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }

            // Comparison panes
            val paneContent: @Composable (MediaItem, Modifier) -> Unit = { item, paneMod ->
                val scale = if (syncZoom) sharedScale else (individualScale[item.id] ?: 1f)
                val offset = if (syncZoom) sharedOffset else (individualOffset[item.id] ?: Offset.Zero)
                val isFav = item.id in favoriteIds
                val offlinePath = remember(item.id) { container.offlineMediaManager.getOfflineFilePath(item.id) }
                var paneSize by remember(item.id) { mutableStateOf(IntSize.Zero) }

                fun clampPaneOffset(raw: Offset, s: Float): Offset {
                    val maxX = ((s - 1f) * paneSize.width / 2f).coerceAtLeast(0f)
                    val maxY = ((s - 1f) * paneSize.height / 2f).coerceAtLeast(0f)
                    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
                }

                val request = remember(item.id, offlinePath, networkState.isUnmeteredWifi) {
                    when {
                        !offlinePath.isNullOrBlank() -> {
                            ImageRequest.Builder(context)
                                .data(File(offlinePath))
                                .size(2048)
                                .precision(Precision.INEXACT)
                                .crossfade(false)
                                .build()
                        }
                        item.isLocal && !item.localContentUri.isNullOrBlank() -> {
                            ImageRequest.Builder(context)
                                .data(item.localContentUri)
                                .size(2048)
                                .precision(Precision.INEXACT)
                                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                                .crossfade(false)
                                .build()
                        }
                        else -> {
                            val url = if (item.isRaw) {
                                DriveConstants.buildRawViewerUrl(item.thumbnailLink, item.id, networkState.isUnmeteredWifi)
                            } else {
                                DriveConstants.buildFullMediaUrl(item.id)
                            }
                            ImageRequest.Builder(context)
                                .data(url)
                                .size(2048)
                                .precision(Precision.INEXACT)
                                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .crossfade(false)
                                .build()
                        }
                    }
                }

                Box(
                    modifier = paneMod
                        .onSizeChanged { paneSize = it }
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0E0E11))
                        .border(0.7.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .clipToBounds()
                        .pointerInput(item.id, syncZoom) {
                            detectTapGestures(
                                onDoubleTap = { tapOffset ->
                                    val curScale = if (syncZoom) sharedScale else (individualScale[item.id] ?: 1f)
                                    val nextScale = if (curScale > 1.2f) 1f else 3f
                                    val targetOffset = if (nextScale == 1f) {
                                        Offset.Zero
                                    } else {
                                        val cx = paneSize.width / 2f
                                        val cy = paneSize.height / 2f
                                        clampPaneOffset(
                                            Offset((cx - tapOffset.x) * (nextScale - 1f), (cy - tapOffset.y) * (nextScale - 1f)),
                                            nextScale
                                        )
                                    }
                                    if (syncZoom) {
                                        sharedScale = nextScale
                                        sharedOffset = targetOffset
                                    } else {
                                        individualScale[item.id] = nextScale
                                        individualOffset[item.id] = targetOffset
                                    }
                                }
                            )
                        }
                        .pointerInput(item.id, syncZoom) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val cx = paneSize.width / 2f
                                val cy = paneSize.height / 2f
                                if (syncZoom) {
                                    val prev = sharedScale
                                    val next = (prev * zoom).coerceIn(1f, 6f)
                                    val ratio = if (prev > 0f) next / prev else 1f
                                    val fx = (centroid.x - cx) * (1f - ratio)
                                    val fy = (centroid.y - cy) * (1f - ratio)
                                    sharedScale = next
                                    sharedOffset = if (next <= 1.02f) {
                                        Offset.Zero
                                    } else {
                                        clampPaneOffset(
                                            Offset(sharedOffset.x * ratio + pan.x + fx, sharedOffset.y * ratio + pan.y + fy),
                                            next
                                        )
                                    }
                                } else {
                                    val prev = individualScale[item.id] ?: 1f
                                    val next = (prev * zoom).coerceIn(1f, 6f)
                                    val ratio = if (prev > 0f) next / prev else 1f
                                    val fx = (centroid.x - cx) * (1f - ratio)
                                    val fy = (centroid.y - cy) * (1f - ratio)
                                    individualScale[item.id] = next
                                    val curOff = individualOffset[item.id] ?: Offset.Zero
                                    individualOffset[item.id] = if (next <= 1.02f) {
                                        Offset.Zero
                                    } else {
                                        clampPaneOffset(
                                            Offset(curOff.x * ratio + pan.x + fx, curOff.y * ratio + pan.y + fy),
                                            next
                                        )
                                    }
                                }
                            }
                        }
                ) {
                    AsyncImage(
                        model = request,
                        imageLoader = imageLoader,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )

                    // Compact per-shot info & quick actions bar
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.name,
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    val w = item.effectiveWidth
                                    val h = item.effectiveHeight
                                    if (w != null && h != null) append("${w}×${h} · ")
                                    append(FormatUtils.formatFileSize(item.sizeBytes))
                                },
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .clickable {
                                        scope.launch {
                                            container.galleryExtrasRepository.toggleFavoriteItem(item.id, isFav)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (isFav) Color(0xFFFF4D67) else Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            if (item.isLocal) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .clickable { confirmTrashItem = item },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Trash",
                                        tint = Color.White.copy(alpha = 0.88f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                when (items.size) {
                    0, 1 -> {
                        Text(
                            text = "Select 2 to 4 photos to compare",
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    2 -> {
                        if (horizontalTwoUp) {
                            Row(
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                paneContent(items[0], Modifier.weight(1f).fillMaxHeight())
                                paneContent(items[1], Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                paneContent(items[0], Modifier.weight(1f).fillMaxWidth())
                                paneContent(items[1], Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                    else -> {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                paneContent(items[0], Modifier.weight(1f).fillMaxHeight())
                                paneContent(items[1], Modifier.weight(1f).fillMaxHeight())
                            }
                            Row(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                paneContent(items[2], Modifier.weight(1f).fillMaxHeight())
                                if (items.size >= 4) {
                                    paneContent(items[3], Modifier.weight(1f).fillMaxHeight())
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        LuminaSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        )
    }

    confirmTrashItem?.let { target ->
        HyperOsTrashConfirmSheet(
            items = listOf(target),
            mode = TrashConfirmMode.MOVE_TO_TRASH,
            canSkipSystemPrompt = container.localMediaRepository.canManageMediaDirectly(),
            supportsSkipPromptSetting = container.localMediaRepository.supportsManageMediaPermission(),
            onOpenManageMediaSettings = {
                container.localMediaRepository.buildManageMediaSettingsIntent()?.let { intent ->
                    runCatching { context.startActivity(intent) }
                }
            },
            onConfirm = {
                confirmTrashItem = null
                val launchFallback: () -> Unit = {
                    val sender = container.localMediaRepository.buildTrashIntentSender(listOf(target))
                    if (sender != null) {
                        pendingTrashItem = target
                        trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    }
                }
                if (container.localMediaRepository.canManageMediaDirectly()) {
                    scope.launch {
                        container.localMediaRepository.trashLocalItemsDirectly(listOf(target), toTrash = true)
                            .onSuccess {
                                container.localMediaRepository.notifyPermissionChanged()
                                items = items.filterNot { it.id == target.id }
                                snackbarHostState.showSnackbar("Moved ${target.name} to trash")
                                if (items.size < 2) onNavigateBack()
                            }
                            .onFailure { launchFallback() }
                    }
                } else {
                    launchFallback()
                }
            },
            onDismiss = { confirmTrashItem = null }
        )
    }
}
