package com.luminagallery.drive.ui.screens.viewer

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.oneUiClickable
import com.luminagallery.drive.ui.components.topViewerScrim
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onActiveItemChanged: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val imageLoader = LocalGalleryImageLoader.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var chromeVisible by remember { mutableStateOf(true) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var dismissDragFraction by remember { mutableFloatStateOf(0f) }
    val activeVideoController = remember { ActiveVideoController() }

    // Sync system status & navigation bar visibility with immersive chrome toggle
    DisposableEffect(chromeVisible) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onTrashCompleted(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.pendingTrashIntentSender) {
        val sender = uiState.pendingTrashIntentSender ?: return@LaunchedEffect
        trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(uiState.pendingShareUris) {
        val uris = uiState.pendingShareUris ?: return@LaunchedEffect
        FormatUtils.launchShareSheet(context, uris, uiState.pendingShareMimeType)
        viewModel.consumePendingShare()
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    val items = uiState.items
    if (items.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Color.White)
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = uiState.initialPageIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )

    val currentItem = items.getOrNull(pagerState.currentPage) ?: items.first()

    // Notify parent grid of active item for accurate shared-element return target
    LaunchedEffect(currentItem.id) {
        isCurrentPageZoomed = false
        onActiveItemChanged(currentItem.id)
    }

    // Network-aware full-resolution pre-fetching:
    // On Unmetered Wi-Fi -> pre-fetch index - 1 and index + 1 into RAM only (no disk write!).
    // On Metered Cellular -> strictly on-demand (zero background pre-fetching!).
    DisposableEffect(pagerState.currentPage, items.size, uiState.isUnmeteredWifi) {
        val activePrefetches = mutableListOf<Disposable>()
        if (uiState.isUnmeteredWifi) {
            val neighborIndices = listOf(pagerState.currentPage - 1, pagerState.currentPage + 1)
                .filter { it in items.indices }
            for (idx in neighborIndices) {
                val neighbor = items[idx]
                if (!neighbor.isVideo && !neighbor.isLocal) {
                    val prefetchUrl = if (neighbor.isRaw) {
                        DriveConstants.buildRawViewerUrl(neighbor.thumbnailLink, neighbor.id, isUnmeteredWifi = true)
                    } else {
                        DriveConstants.buildFullMediaUrl(neighbor.id)
                    }
                    val request = ImageRequest.Builder(context)
                        .data(prefetchUrl)
                        .memoryCacheKey(DriveConstants.fullResMemoryKey(neighbor.id))
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.DISABLED)
                        .build()
                    activePrefetches.add(imageLoader.enqueue(request))
                }
            }
        }
        onDispose {
            activePrefetches.forEach { it.dispose() }
        }
    }

    val backgroundAlpha = (1f - dismissDragFraction * 0.85f).coerceIn(0.12f, 1f)
    val showChromeNow = chromeVisible && dismissDragFraction < 0.05f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
    ) {
        // Media Pager (sits neatly above the pure-black Samsung bottom dock when chrome is visible
        // so bright screenshots never clash with bottom icons, and goes full-bleed when immersive!)
        val bottomDockClearance by animateDpAsState(
            targetValue = if (showChromeNow) 118.dp else 0.dp,
            animationSpec = tween(durationMillis = 200),
            label = "mediaBottomClearance"
        )

        HorizontalPager(
            state = pagerState,
            key = { index -> items[index].id },
            userScrollEnabled = !isCurrentPageZoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = bottomDockClearance)
        ) { page ->
            val pageItem = items[page]
            val isActivePage = page == pagerState.currentPage

            val sharedModifier = if (isActivePage) {
                with(sharedTransitionScope) {
                    Modifier.sharedElement(
                        sharedContentState = rememberSharedContentState(key = "media_${pageItem.id}"),
                        animatedVisibilityScope = animatedVisibilityScope,
                        boundsTransform = OneUiMotion.SharedPhotoBoundsTransform
                    )
                }
            } else {
                Modifier
            }

            if (pageItem.isVideo) {
                VideoPlayerPage(
                    item = pageItem,
                    isActivePage = isActivePage,
                    isUnmeteredWifi = uiState.isUnmeteredWifi,
                    chromeVisible = showChromeNow,
                    videoController = activeVideoController,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onVerticalDismissProgress = { fraction ->
                        if (isActivePage) dismissDragFraction = fraction
                    },
                    onSwipeUpForDetails = {
                        if (isActivePage) viewModel.openExifSheet(pageItem)
                    },
                    onFrameCapturedMessage = viewModel::showMessage,
                    onDismiss = onDismiss,
                    modifier = sharedModifier
                )
            } else {
                ZoomablePhotoPage(
                    item = pageItem,
                    isUnmeteredWifi = uiState.isUnmeteredWifi,
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomStateChanged = { zoomed ->
                        if (isActivePage) isCurrentPageZoomed = zoomed
                    },
                    onVerticalDismissProgress = { fraction ->
                        if (isActivePage) dismissDragFraction = fraction
                    },
                    onSwipeUpForDetails = {
                        if (isActivePage) viewModel.openExifSheet(pageItem)
                    },
                    onDismiss = onDismiss,
                    modifier = sharedModifier
                )
            }
        }

        // Translucent Top Bar
        AnimatedVisibility(
            visible = showChromeNow,
            enter = fadeIn(tween(180)) + slideInVertically { -it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .topViewerScrim()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = currentItem.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(currentItem.fullDateLabel)
                            if (!uiState.isUnmeteredWifi) append(" · Cellular")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (uiState.isExporting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(20.dp)
                    )
                }
            }
        }

        // Authentic Samsung One UI Bottom Chrome:
        //  1. Floating Video Controls Row (Capture Frame | || 00:01 / 00:02 | Mute) — videos only!
        //  2. Horizontal Filmstrip Scrubber (with expanded active tile & live white video scrub playhead!)
        //  3. Minimalist Icon-Only Action Row (Heart | Info | Share | Save or Delete)
        AnimatedVisibility(
            visible = showChromeNow,
            enter = fadeIn(tween(180)) + slideInVertically { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val isFavorited = currentItem.id in uiState.favoriteItemIds
            val heartScale by animateFloatAsState(
                targetValue = if (isFavorited) 1.2f else 1.0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "viewerHeartPop"
            )

            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                // 1. Floating Samsung Video Pill Row (sits gracefully over bottom edge of video!)
                AnimatedVisibility(
                    visible = currentItem.isVideo,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(120))
                ) {
                    SamsungVideoControlBar(
                        isPlaying = activeVideoController.isPlaying,
                        isEnded = activeVideoController.isEnded,
                        isMuted = activeVideoController.isMuted,
                        positionMs = activeVideoController.currentPositionMs,
                        durationMs = activeVideoController.durationMs.takeIf { it > 0L }
                            ?: (currentItem.durationMillis ?: 0L),
                        onCaptureFrame = activeVideoController::captureFrame,
                        onTogglePlayPause = activeVideoController::togglePlayPause,
                        onToggleMute = activeVideoController::toggleMute,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                // Pure-black Samsung Bottom Dock (Filmstrip + Icon Row)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .navigationBarsPadding()
                ) {
                    // 2. Samsung Filmstrip Thumbnail Scrubber
                    SamsungFilmstripScrubber(
                        items = items,
                        currentIndex = pagerState.currentPage,
                        isUnmeteredWifi = uiState.isUnmeteredWifi,
                        videoPositionMs = activeVideoController.currentPositionMs,
                        videoDurationMs = activeVideoController.durationMs.takeIf { it > 0L }
                            ?: (currentItem.durationMillis ?: 0L),
                        onSelectPage = { targetIdx ->
                            coroutineScope.launch {
                                if (abs(targetIdx - pagerState.currentPage) <= 3) {
                                    pagerState.animateScrollToPage(targetIdx)
                                } else {
                                    pagerState.scrollToPage(targetIdx)
                                }
                            }
                        },
                        onScrubPage = { targetIdx ->
                            if (targetIdx != pagerState.currentPage && targetIdx in items.indices) {
                                coroutineScope.launch { pagerState.scrollToPage(targetIdx) }
                            }
                        },
                        onSeekVideoMs = activeVideoController::seekTo,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                    )

                    // 3. Minimalist Icon-Only Samsung Action Row (zero text label clutter!)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleFavorite(currentItem) }) {
                            Icon(
                                imageVector = if (isFavorited) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = "Favorite",
                                tint = if (isFavorited) Color(0xFFFF5C77) else Color.White,
                                modifier = Modifier
                                    .size(25.dp)
                                    .graphicsLayer {
                                        scaleX = heartScale
                                        scaleY = heartScale
                                    }
                            )
                        }

                        IconButton(onClick = { viewModel.openExifSheet(currentItem) }) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = "Details",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.shareItem(currentItem) },
                            enabled = !uiState.isExporting
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Share,
                                contentDescription = "Share",
                                tint = if (!uiState.isExporting) Color.White else Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        if (currentItem.isLocal) {
                            IconButton(
                                onClick = { viewModel.requestTrashLocalItem(currentItem) },
                                enabled = !uiState.isExporting
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Delete",
                                    tint = if (!uiState.isExporting) Color.White else Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        } else {
                            IconButton(
                                onClick = { viewModel.saveItemToDevice(currentItem) },
                                enabled = !uiState.isExporting
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Download,
                                    contentDescription = "Save to device",
                                    tint = if (!uiState.isExporting) Color.White else Color.White.copy(alpha = 0.4f),
                                    modifier = Modifier.size(25.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 132.dp)
        )
    }

    if (uiState.showExifSheet) {
        ExifMetadataBottomSheet(
            item = uiState.activeExifOverride ?: currentItem,
            driveFolderPath = uiState.activeDrivePath,
            onDismiss = viewModel::closeExifSheet,
            onCopiedMessage = viewModel::showMessage
        )
    }
}

/**
 * Exact Samsung One UI floating video controls row sitting right above the filmstrip:
 *  - Left circle: Capture current video frame to gallery
 *  - Center pill: Play/Pause icon + `00:01 / 00:02` tabular monospace clock
 *  - Right circle: Mute / Unmute speaker
 */
@Composable
private fun SamsungVideoControlBar(
    isPlaying: Boolean,
    isEnded: Boolean,
    isMuted: Boolean,
    positionMs: Long,
    durationMs: Long,
    onCaptureFrame: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillBg = Color(0xB3141416)
    val pillBorder = Color.White.copy(alpha = 0.16f)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Capture frame circle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(pillBg)
                .border(0.8.dp, pillBorder, CircleShape)
                .clickable(onClick = onCaptureFrame),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CropFree,
                contentDescription = "Capture video frame",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }

        // Center: Play/Pause + "00:01 / 00:02" glass capsule
        Surface(
            shape = RoundedCornerShape(50),
            color = pillBg,
            modifier = Modifier
                .height(38.dp)
                .border(0.8.dp, pillBorder, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onTogglePlayPause)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = when {
                        isEnded -> Icons.Rounded.Replay
                        isPlaying -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
                Text(
                    text = "${FormatUtils.formatDurationPadded(positionMs)} / ${FormatUtils.formatDurationPadded(durationMs)}",
                    style = TextStyle(
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        color = Color.White
                    ),
                    maxLines = 1
                )
            }
        }

        // Right: Mute / Unmute circle
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(pillBg)
                .border(0.8.dp, pillBorder, CircleShape)
                .clickable(onClick = onToggleMute),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isMuted) {
                    Icons.AutoMirrored.Rounded.VolumeOff
                } else {
                    Icons.AutoMirrored.Rounded.VolumeUp
                },
                contentDescription = if (isMuted) "Unmute" else "Mute",
                tint = Color.White,
                modifier = Modifier.size(19.dp)
            )
        }
    }
}

/**
 * Pixel-faithful Samsung One UI Gallery bottom Filmstrip Scrubber:
 *  - Shows surrounding album photos & videos in a smooth centered horizontal strip.
 *  - Active photo pops wider with crisp breathing room on both sides.
 *  - Active video expands into a wide scrub bar with a live vertical white playhead that
 *    glides smoothly during playback AND supports direct horizontal finger-scrubbing to seek!
 */
@Composable
private fun SamsungFilmstripScrubber(
    items: List<MediaItem>,
    currentIndex: Int,
    isUnmeteredWifi: Boolean,
    videoPositionMs: Long,
    videoDurationMs: Long,
    onSelectPage: (Int) -> Unit,
    onScrubPage: (Int) -> Unit,
    onSeekVideoMs: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val density = LocalDensity.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val halfViewportDp = maxWidth / 2
        val activeTileHalfDp = if (items.getOrNull(currentIndex)?.isVideo == true) 52.dp else 30.dp
        val edgePadding = (halfViewportDp - activeTileHalfDp).coerceAtLeast(16.dp)

        // Keep active page centered when user swipes the main HorizontalPager
        LaunchedEffect(currentIndex) {
            if (!listState.isScrollInProgress && currentIndex in items.indices) {
                listState.animateScrollToItem(index = currentIndex, scrollOffset = 0)
            }
        }

        // When user drags the filmstrip directly, scrub the HorizontalPager smoothly to the center tile!
        LaunchedEffect(listState) {
            snapshotFlow {
                if (!listState.isScrollInProgress) return@snapshotFlow null
                val layout = listState.layoutInfo
                val centerPx = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                layout.visibleItemsInfo.minByOrNull { info ->
                    abs((info.offset + info.size / 2) - centerPx)
                }?.index
            }
                .filter { it != null }
                .distinctUntilChanged()
                .collect { centerIdx ->
                    if (centerIdx != null) {
                        onScrubPage(centerIdx)
                    }
                }
        }

        LazyRow(
            state = listState,
            contentPadding = PaddingValues(horizontal = edgePadding),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> "strip_${item.id}" }
            ) { index, item ->
                val isCurrent = index == currentIndex
                val isVideoActive = isCurrent && item.isVideo

                val tileWidth by animateDpAsState(
                    targetValue = when {
                        isVideoActive -> 92.dp
                        isCurrent -> 46.dp
                        else -> 32.dp
                    },
                    animationSpec = OneUiMotion.snappySpring(),
                    label = "stripWidth"
                )
                val tileHeight by animateDpAsState(
                    targetValue = if (isCurrent) 46.dp else 40.dp,
                    animationSpec = OneUiMotion.snappySpring(),
                    label = "stripHeight"
                )
                val sideMargin by animateDpAsState(
                    targetValue = if (isCurrent) 8.dp else 0.dp,
                    animationSpec = OneUiMotion.snappySpring(),
                    label = "stripGap"
                )

                val thumbRequest = remember(item.id, item.isLocal, item.localContentUri, item.thumbnailLink, isUnmeteredWifi) {
                    if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
                        ImageRequest.Builder(context)
                            .data(item.localContentUri)
                            .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                            .size(160)
                            .build()
                    } else {
                        val url = DriveConstants.buildThumbnailUrl(
                            thumbnailLink = item.thumbnailLink,
                            fileId = item.id,
                            isUnmeteredWifi = isUnmeteredWifi,
                            isVideo = item.isVideo,
                            sizeBytes = item.sizeBytes
                        )
                        ImageRequest.Builder(context)
                            .data(url)
                            .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                            .diskCacheKey(DriveConstants.thumbnailCacheKey(item.id, isUnmeteredWifi))
                            .size(160)
                            .build()
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = sideMargin)
                        .height(54.dp)
                        .width(tileWidth),
                    contentAlignment = Alignment.Center
                ) {
                    val safeDuration = videoDurationMs.coerceAtLeast(1L)
                    val tileWidthPx = with(density) { tileWidth.toPx() }.coerceAtLeast(1f)

                    val scrubModifier = if (isVideoActive) {
                        Modifier
                            .pointerInput(item.id, safeDuration, tileWidthPx) {
                                detectTapGestures { offset ->
                                    val fraction = (offset.x / tileWidthPx).coerceIn(0f, 1f)
                                    onSeekVideoMs((fraction * safeDuration).toLong())
                                }
                            }
                            .pointerInput(item.id, safeDuration, tileWidthPx) {
                                detectHorizontalDragGestures { change, _ ->
                                    change.consume()
                                    val fraction = (change.position.x / tileWidthPx).coerceIn(0f, 1f)
                                    onSeekVideoMs((fraction * safeDuration).toLong())
                                }
                            }
                    } else {
                        Modifier.clickable { onSelectPage(index) }
                    }

                    Box(
                        modifier = Modifier
                            .width(tileWidth)
                            .height(tileHeight)
                            .clip(RoundedCornerShape(if (isCurrent) 4.dp else 2.dp))
                            .background(Color(0xFF1C1C20))
                            .then(scrubModifier)
                    ) {
                        AsyncImage(
                            model = thumbRequest,
                            imageLoader = imageLoader,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )

                        // Tiny white play triangle on neighboring video thumbnails (like Screenshot 2!)
                        if (item.isVideo && !isCurrent) {
                            Icon(
                                imageVector = Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(2.dp)
                                    .size(13.dp)
                            )
                        }
                    }

                    // Samsung's signature vertical white video playhead needle spanning above & below tile!
                    if (isVideoActive) {
                        val playFraction = (videoPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
                        val needleOffsetX = ((playFraction - 0.5f) * (tileWidthPx - with(density) { 4.dp.toPx() })).roundToInt()
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(needleOffsetX, 0) }
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(Color.White)
                                .border(0.4.dp, Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                        )
                    }
                }
            }
        }
    }
}

/**
 * High-refresh-rate zoomable image viewer:
 *  - Shows cached grid thumbnail synchronously on frame 0 via `placeholderMemoryCacheKey`
 *    while streaming the full-resolution image (`?alt=media`) strictly into memory.
 *  - Double-tap to zoom with spring physics anchored around the tap point.
 *  - Pinch-to-zoom (`1f..5f`) with strict pan boundary clamping.
 *  - Vertical swipe-to-dismiss with dynamic scale and background opacity fade.
 */
@Composable
private fun ZoomablePhotoPage(
    item: MediaItem,
    isUnmeteredWifi: Boolean,
    onTap: () -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    onVerticalDismissProgress: (Float) -> Unit,
    onSwipeUpForDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val coroutineScope = rememberCoroutineScope()

    val scaleAnim = remember(item.id) { Animatable(1f) }
    val offsetXAnim = remember(item.id) { Animatable(0f) }
    val offsetYAnim = remember(item.id) { Animatable(0f) }
    val dismissOffsetY = remember(item.id) { Animatable(0f) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    val fullResRequest = remember(item.isLocal, item.localContentUri, item.id, isUnmeteredWifi) {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(item.localContentUri)
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .memoryCacheKey(DriveConstants.fullResMemoryKey(item.id))
                .crossfade(false)
                .build()
        } else {
            val fullUrl = if (item.isRaw) {
                DriveConstants.buildRawViewerUrl(item.thumbnailLink, item.id, isUnmeteredWifi)
            } else {
                DriveConstants.buildFullMediaUrl(item.id)
            }
            ImageRequest.Builder(context)
                .data(fullUrl)
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .memoryCacheKey(DriveConstants.fullResMemoryKey(item.id))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(false)
                .build()
        }
    }

    fun clampOffset(x: Float, y: Float, scale: Float): Offset {
        val maxX = ((scale - 1f) * containerSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((scale - 1f) * containerSize.height / 2f).coerceAtLeast(0f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }

    val dismissFraction = (abs(dismissOffsetY.value) / 420f).coerceIn(0f, 1f)
    val dismissScale = 1f - (dismissFraction * 0.22f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tapOffset ->
                        coroutineScope.launch {
                            val springSpec = spring<Float>(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                            if (scaleAnim.value > 1.15f) {
                                onZoomStateChanged(false)
                                launch { scaleAnim.animateTo(1f, springSpec) }
                                launch { offsetXAnim.animateTo(0f, springSpec) }
                                launch { offsetYAnim.animateTo(0f, springSpec) }
                            } else {
                                val targetScale = 2.6f
                                val centerX = containerSize.width / 2f
                                val centerY = containerSize.height / 2f
                                val target = clampOffset(
                                    x = (centerX - tapOffset.x) * (targetScale - 1f),
                                    y = (centerY - tapOffset.y) * (targetScale - 1f),
                                    scale = targetScale
                                )
                                onZoomStateChanged(true)
                                launch { scaleAnim.animateTo(targetScale, springSpec) }
                                launch { offsetXAnim.animateTo(target.x, springSpec) }
                                launch { offsetYAnim.animateTo(target.y, springSpec) }
                            }
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var isVerticalDismissDrag = false
                    var gestureStartedZoomed = scaleAnim.value > 1.02f

                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }

                        if (pressedPointers >= 2) {
                            isVerticalDismissDrag = false
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()

                            val prevScale = scaleAnim.value
                            val nextScale = (prevScale * zoomChange).coerceIn(1f, 5f)
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f

                            val focusShiftX = (centroid.x - centerX) * (1f - nextScale / prevScale)
                            val focusShiftY = (centroid.y - centerY) * (1f - nextScale / prevScale)
                            val clamped = clampOffset(
                                x = offsetXAnim.value + panChange.x + focusShiftX,
                                y = offsetYAnim.value + panChange.y + focusShiftY,
                                scale = nextScale
                            )

                            coroutineScope.launch {
                                scaleAnim.snapTo(nextScale)
                                offsetXAnim.snapTo(clamped.x)
                                offsetYAnim.snapTo(clamped.y)
                            }
                            onZoomStateChanged(nextScale > 1.02f)
                            gestureStartedZoomed = nextScale > 1.02f
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressedPointers == 1) {
                            val pan = event.calculatePan()
                            val currentScale = scaleAnim.value

                            if (currentScale > 1.02f) {
                                val nextClamped = clampOffset(
                                    x = offsetXAnim.value + pan.x,
                                    y = offsetYAnim.value + pan.y,
                                    scale = currentScale
                                )
                                val atHorizontalEdge =
                                    abs(nextClamped.x - offsetXAnim.value) < 0.5f &&
                                        abs(pan.x) > abs(pan.y) * 1.4f

                                coroutineScope.launch {
                                    offsetXAnim.snapTo(nextClamped.x)
                                    offsetYAnim.snapTo(nextClamped.y)
                                }
                                if (!atHorizontalEdge) {
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            } else if (!gestureStartedZoomed) {
                                if (!isVerticalDismissDrag &&
                                    abs(pan.y) > 8f &&
                                    abs(pan.y) > abs(pan.x) * 1.35f
                                ) {
                                    isVerticalDismissDrag = true
                                }
                                if (isVerticalDismissDrag) {
                                    val newY = dismissOffsetY.value + pan.y
                                    coroutineScope.launch {
                                        dismissOffsetY.snapTo(newY)
                                    }
                                    onVerticalDismissProgress((abs(newY) / 420f).coerceIn(0f, 1f))
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (isVerticalDismissDrag) {
                        val dismissThresholdPx = (containerSize.height * 0.16f).coerceAtLeast(210f)
                        val swipeUpThresholdPx = 150f
                        when {
                            dismissOffsetY.value > dismissThresholdPx -> {
                                onVerticalDismissProgress(0f)
                                onDismiss()
                            }
                            dismissOffsetY.value < -swipeUpThresholdPx -> {
                                coroutineScope.launch {
                                    dismissOffsetY.animateTo(0f, spring())
                                    onVerticalDismissProgress(0f)
                                }
                                onSwipeUpForDetails()
                            }
                            else -> {
                                coroutineScope.launch {
                                    dismissOffsetY.animateTo(
                                        0f,
                                        spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                    onVerticalDismissProgress(0f)
                                }
                            }
                        }
                    } else if (scaleAnim.value < 1.05f) {
                        onZoomStateChanged(false)
                        coroutineScope.launch {
                            launch { scaleAnim.animateTo(1f, spring()) }
                            launch { offsetXAnim.animateTo(0f, spring()) }
                            launch { offsetYAnim.animateTo(0f, spring()) }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = fullResRequest,
            imageLoader = imageLoader,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val effectiveScale = scaleAnim.value * dismissScale
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value + dismissOffsetY.value
                }
        )
    }
}
