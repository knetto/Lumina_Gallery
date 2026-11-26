package com.luminagallery.drive.ui.screens.viewer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import com.luminagallery.drive.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as MediaOptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.ExtractedPhotoText
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.data.repository.MotionPhotoInfo
import com.luminagallery.drive.data.repository.WallpaperTarget
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.HyperOsTrashConfirmSheet
import com.luminagallery.drive.ui.components.LocalAlbumTransitionOrigin
import com.luminagallery.drive.ui.components.AnimatedFavoriteIcon
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.MoveCopyToAlbumSheet
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.SecureWindowEffect
import com.luminagallery.drive.ui.components.TrashConfirmMode
import com.luminagallery.drive.ui.components.findActivity
import com.luminagallery.drive.ui.components.oneUiClickable
import com.luminagallery.drive.ui.components.topViewerScrim
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    animatedVisibilityScope: AnimatedVisibilityScope,
    initialItemId: String = "",
    isRectMorphing: Boolean = false,
    onActiveItemChanged: (String) -> Unit,
    onOpenEditor: (itemId: String, sourceUri: String, displayName: String, isLocal: Boolean) -> Unit = { _, _, _, _ -> },
    onOpenPerson: ((personId: String) -> Unit)? = null,
    onSearchTag: ((tag: String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val view = LocalView.current
    val imageLoader = LocalGalleryImageLoader.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val offlineIds by appContainer.offlineMediaManager.offlineItemIdsFlow
        .collectAsStateWithLifecycle(initialValue = emptySet())
    val lockedAlbumIds by appContainer.galleryExtrasRepository.lockedAlbumIdsFlow
        .collectAsStateWithLifecycle(initialValue = appContainer.galleryExtrasRepository.latestLockedAlbumIds)
    val isViewingLockedMedia = remember(uiState.items, lockedAlbumIds) {
        lockedAlbumIds.isNotEmpty() && uiState.items.any { it.parentId in lockedAlbumIds }
    }
    SecureWindowEffect(enabled = isViewingLockedMedia)

    var chromeVisible by remember { mutableStateOf(true) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    val activeVideoController = remember { ActiveVideoController() }

    // Viewer Power Action States (Motion Photo, Wallpaper, Rename, Move/Copy, Trim, Overflow Menu)
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showWallpaperSheet by remember { mutableStateOf(false) }
    var showMoveCopySheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTrimSheet by remember { mutableStateOf(false) }
    var pendingRenameNewName by remember { mutableStateOf<String?>(null) }
    var currentMotionInfo by remember { mutableStateOf<MotionPhotoInfo?>(null) }
    var isPlayingMotionPhoto by remember { mutableStateOf(false) }
    var isApplyingQuickAction by remember { mutableStateOf(false) }
    var extractedTextResult by remember { mutableStateOf<ExtractedPhotoText?>(null) }
    var trashConfirmItem by remember { mutableStateOf<MediaItem?>(null) }

    val prefs = uiState.preferences

    // Samsung Gallery "Maximum brightness" & "Keep screen on" viewer flags
    DisposableEffect(prefs.maxViewerBrightness, prefs.keepScreenOnViewer) {
        val window = context.findActivity()?.window
        val previousBrightness = window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window != null) {
            if (prefs.keepScreenOnViewer) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            if (prefs.maxViewerBrightness) {
                val lp = window.attributes
                lp.screenBrightness = 1.0f
                window.attributes = lp
            }
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (prefs.maxViewerBrightness) {
                    val lp = window.attributes
                    lp.screenBrightness = previousBrightness
                    window.attributes = lp
                }
            }
        }
    }

    // Force crisp white status & nav bar icons over pure-black Viewer canvas even in Light Mode
    DisposableEffect(view) {
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

    // Sync system status & navigation bar visibility with immersive chrome toggle
    DisposableEffect(chromeVisible) {
        val window = context.findActivity()?.window
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

    val favoriteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onFavoriteRequestCompleted(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.pendingTrashIntentSender) {
        val sender = uiState.pendingTrashIntentSender ?: return@LaunchedEffect
        trashLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    LaunchedEffect(uiState.pendingFavoriteIntentSender) {
        val sender = uiState.pendingFavoriteIntentSender ?: return@LaunchedEffect
        favoriteLauncher.launch(IntentSenderRequest.Builder(sender).build())
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
        if (uiState.hasLoadedOnce) {
            // Last item was trashed / unfavorited — dismiss cleanly instead of spinning forever
            LaunchedEffect(Unit) { onDismiss() }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isRectMorphing) Color.Transparent else Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (!isRectMorphing && !uiState.hasLoadedOnce) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = uiState.initialPageIndex.coerceIn(0, items.lastIndex),
        pageCount = { items.size }
    )

    // If ViewerViewModel booted with the single tapped item for instant frame-0 morphing and the
    // full album/search list arrives moments later, silently lock onto the exact same item index.
    var hasSyncedFullList by remember { mutableStateOf(items.size > 1 || initialItemId.isBlank()) }
    LaunchedEffect(items.size) {
        if (!hasSyncedFullList && items.size > 1) {
            val targetIdx = items.indexOfFirst { it.id == initialItemId }
            if (targetIdx >= 0 && pagerState.currentPage != targetIdx) {
                pagerState.scrollToPage(targetIdx)
            }
            hasSyncedFullList = true
        }
    }

    val currentItem = items.getOrNull(pagerState.currentPage) ?: items.first()
    val settledItem = items.getOrNull(pagerState.settledPage) ?: currentItem
    val isCurrentOffline = currentItem.id in offlineIds

    val renameWriteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val newName = pendingRenameNewName
        pendingRenameNewName = null
        if (result.resultCode == Activity.RESULT_OK && !newName.isNullOrBlank()) {
            coroutineScope.launch {
                appContainer.localFileOperationsManager.renameLocalItem(currentItem, newName)
                    .onSuccess { finalName ->
                        appContainer.localMediaRepository.notifyPermissionChanged()
                        viewModel.showMessage("Renamed to $finalName")
                    }
                    .onFailure { err ->
                        viewModel.showMessage(err.localizedMessage ?: "Rename failed")
                    }
            }
        }
    }

    // Reset zoom/motion immediately when page changes, and detect Motion Photos once settled
    LaunchedEffect(currentItem.id, hasSyncedFullList) {
        isCurrentPageZoomed = false
        isPlayingMotionPhoto = false
        currentMotionInfo = null
        if (hasSyncedFullList || currentItem.id == initialItemId || initialItemId.isBlank()) {
            onActiveItemChanged(currentItem.id)
        }
    }

    LaunchedEffect(settledItem.id, items.size) {
        if (!settledItem.isVideo) {
            val offlinePath = appContainer.offlineMediaManager.getOfflineFilePath(settledItem.id)
            val detected = appContainer.motionPhotoDetector.detect(
                item = settledItem,
                siblingItems = items,
                offlineFilePath = offlinePath
            )
            if (settledItem.id == currentItem.id) {
                currentMotionInfo = detected
            }
        }
    }

    // Network-aware full-resolution pre-fetching:
    // Respects user preferences for neighbor prefetching & Wi-Fi-only high-res.
    DisposableEffect(pagerState.currentPage, items.size, uiState.isUnmeteredWifi, prefs.prefetchAdjacentMedia) {
        val activePrefetches = mutableListOf<Disposable>()
        if (uiState.isUnmeteredWifi && prefs.prefetchAdjacentMedia) {
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
                        .size(VIEWER_MAX_DECODE_PX)
                        .precision(Precision.INEXACT)
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

    val dismissDrag = remember(currentItem.id) { ViewerDismissDrag() }
    val isMediaDragged by remember(dismissDrag) { derivedStateOf { dismissDrag.offsetPx > 1f } }
    val showChromeNow = chromeVisible && !isMediaDragged && !isRectMorphing
    val isViewerClosing = animatedVisibilityScope.transition.targetState == EnterExitState.PostExit
    val allowVerticalGestures by rememberUpdatedState(!isRectMorphing && !isViewerClosing)
    val allowSwipeDismiss by rememberUpdatedState(prefs.swipeDownToDismiss && !isViewerClosing)
    val latestOnDismiss by rememberUpdatedState(onDismiss)
    val latestVisibleItem by rememberUpdatedState(currentItem)
    val haptic = LocalHapticFeedback.current
    val originTracker = LocalAlbumTransitionOrigin.current
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(originTracker) {
        onDispose {
            originTracker.viewerContentRectProvider = null
            originTracker.viewerBackdropAlphaProvider = null
        }
    }

    val showFilmstrip = prefs.showViewerFilmstrip
    // Hiding the controls during a drag must leave the media's layout and scale pivot in place.
    val bottomDockClearance by animateDpAsState(
        targetValue = when {
            !chromeVisible || currentItem.isVideo -> 0.dp
            showFilmstrip -> 118.dp
            else -> 60.dp
        },
        animationSpec = tween(durationMillis = 220, easing = OneUiMotion.PageSlideEasing),
        label = "mediaBottomClearance"
    )
    val viewportWidth = viewportSize.width.toFloat()
    val viewportHeight = viewportSize.height.toFloat()
    var decodedAspectForActive by remember(currentItem.id) { mutableStateOf<Float?>(null) }

    SideEffect {
        originTracker.viewerBackdropAlphaProvider = { dismissDrag.backdropAlpha(viewportHeight) }
        val fittedRect = fittedViewerContentRect(currentItem, viewportWidth, viewportHeight, decodedAspectForActive)
            ?: Rect(0f, 0f, viewportWidth, viewportHeight)
        val viewportCenter = Offset(viewportWidth * 0.5f, viewportHeight * 0.5f)
        originTracker.viewerContentRectProvider = { dismissDrag.transform(fittedRect, viewportCenter) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                if (!isRectMorphing) {
                    drawRect(Color.Black, alpha = dismissDrag.backdropAlpha(viewportHeight))
                }
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = bottomDockClearance.coerceAtLeast(0.dp))
                .onSizeChanged { viewportSize = it }
                .viewerVerticalGestures(
                    drag = dismissDrag,
                    scope = coroutineScope,
                    canStart = { allowVerticalGestures && !isCurrentPageZoomed && !pagerState.isScrollInProgress },
                    canDismiss = { allowSwipeDismiss },
                    onDismiss = { latestOnDismiss() },
                    onDetails = {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        viewModel.openExifSheet(latestVisibleItem)
                    }
                )
        ) {
            HorizontalPager(
                state = pagerState,
                key = { index -> items.getOrNull(index)?.id ?: "page_$index" },
                userScrollEnabled = !isCurrentPageZoomed && !isMediaDragged,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = dismissDrag.scale(size.height)
                        scaleX = scale
                        scaleY = scale
                        translationY = dismissDrag.offsetPx
                    }
            ) { page ->
                val pageItem = items.getOrNull(page) ?: return@HorizontalPager
                val isActivePage = page == pagerState.currentPage

                if (pageItem.isVideo) {
                    VideoPlayerPage(
                        item = pageItem,
                        isActivePage = isActivePage,
                        isUnmeteredWifi = uiState.isUnmeteredWifi,
                        chromeVisible = showChromeNow,
                        videoController = activeVideoController,
                        autoPlay = prefs.autoPlayVideos,
                        startMuted = prefs.startVideosMuted,
                        loopVideo = prefs.loopVideos,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        onFrameCapturedMessage = viewModel::showMessage,
                        onZoomStateChanged = { zoomed ->
                            if (isActivePage) isCurrentPageZoomed = zoomed
                        },
                        onVideoAspectResolved = { aspect ->
                            if (isActivePage) decodedAspectForActive = aspect
                        }
                    )
                } else {
                    ZoomablePhotoPage(
                        item = pageItem,
                        isUnmeteredWifi = uiState.isUnmeteredWifi,
                        startMuted = prefs.startVideosMuted,
                        isClosing = isViewerClosing && isActivePage,
                        motionPhotoInfo = if (isActivePage) currentMotionInfo else null,
                        isPlayingMotion = isActivePage && isPlayingMotionPhoto,
                        onMotionPlayStateChanged = { playing ->
                            if (isActivePage) isPlayingMotionPhoto = playing
                        },
                        onTap = { chromeVisible = !chromeVisible },
                        onZoomStateChanged = { zoomed ->
                            if (isActivePage) isCurrentPageZoomed = zoomed
                        },
                        onImageAspectResolved = { aspect ->
                            if (isActivePage) decodedAspectForActive = aspect
                        }
                    )
                }
            }
        }

        // Translucent Top Bar (vanishes cleanly on morph close so it never shrinks inside the photo!)
        AnimatedVisibility(
            visible = showChromeNow,
            enter = fadeIn(tween(150)),
            exit = if (isRectMorphing) {
                ExitTransition.None
            } else {
                fadeOut(tween(150)) + slideOutVertically { -it / 2 }
            },
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

                val activeMotionBadge = currentMotionInfo
                if (activeMotionBadge != null && !currentItem.isVideo) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isPlayingMotionPhoto) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.56f),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .border(0.8.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(50))
                            .clip(RoundedCornerShape(50))
                            .clickable { isPlayingMotionPhoto = !isPlayingMotionPhoto }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.MotionPhotosOn,
                                contentDescription = "Motion photo",
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (isPlayingMotionPhoto) "Playing" else activeMotionBadge.kind.badgeLabel,
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                if (!currentItem.isVideo && !isApplyingQuickAction) {
                    IconButton(
                        onClick = {
                            val target = currentItem
                            coroutineScope.launch {
                                isApplyingQuickAction = true
                                try {
                                    appContainer.ocrIndexerManager.extractTextForViewer(target)
                                        .onSuccess { extracted ->
                                            if (latestVisibleItem.id != target.id) return@onSuccess
                                            if (extracted.fullText.isBlank()) {
                                                viewModel.showMessage("No text detected in this photo")
                                            } else {
                                                extractedTextResult = extracted
                                            }
                                        }
                                        .onFailure { error ->
                                            Log.w("ViewerScreen", "Photo text recognition failed", error)
                                            if (latestVisibleItem.id == target.id) {
                                                viewModel.showMessage("Could not read text in this photo")
                                            }
                                        }
                                } finally {
                                    isApplyingQuickAction = false
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DocumentScanner,
                            contentDescription = "Extract text from photo",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (uiState.isExporting || isApplyingQuickAction) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp)
                    )
                }

                // Overflow menu for Set as Wallpaper, Album Cover, Move/Copy, Rename, Offline Pin, External Edit, Trim
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                        shape = RoundedCornerShape(22.dp),
                        containerColor = Color(0xFF28282B)
                    ) {
                        val openInLabel = if (prefs.externalGalleryPackage.isNotBlank()) {
                            "Open in ${prefs.externalGalleryLabel.ifBlank { "other gallery" }}"
                        } else {
                            "Open in another gallery"
                        }
                        DropdownMenuItem(
                            text = { Text(openInLabel, color = Color.White) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.85f),
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showOverflowMenu = false
                                val targetPkg = prefs.externalGalleryPackage.ifBlank { null }
                                val itemToOpen = currentItem
                                val quickUri = appContainer.localFileOperationsManager
                                    .getQuickLocalOrOfflineUri(itemToOpen)
                                if (quickUri != null) {
                                    appContainer.localFileOperationsManager.launchInExternalGallery(
                                        activityContext = context,
                                        uri = quickUri,
                                        mimeType = itemToOpen.mimeType,
                                        isVideo = itemToOpen.isVideo,
                                        targetPackage = targetPkg
                                    ).onFailure { err ->
                                        viewModel.showMessage(err.localizedMessage ?: "No gallery app available")
                                    }
                                } else {
                                    coroutineScope.launch {
                                        isApplyingQuickAction = true
                                        appContainer.exportMediaUseCase.prepareShareUris(
                                            items = listOf(itemToOpen),
                                            stripLocation = false
                                        )
                                            .onSuccess { uris ->
                                                val uri = uris.firstOrNull()
                                                if (uri != null) {
                                                    appContainer.localFileOperationsManager.launchInExternalGallery(
                                                        activityContext = context,
                                                        uri = uri,
                                                        mimeType = itemToOpen.mimeType,
                                                        isVideo = itemToOpen.isVideo,
                                                        targetPackage = targetPkg
                                                    ).onFailure { err ->
                                                        viewModel.showMessage(err.localizedMessage ?: "No gallery app available")
                                                    }
                                                }
                                            }
                                            .onFailure { err ->
                                                viewModel.showMessage(err.localizedMessage ?: "Unable to prepare file")
                                            }
                                        isApplyingQuickAction = false
                                    }
                                }
                            }
                        )
                        if (!currentItem.isVideo) {
                            DropdownMenuItem(
                                text = { Text("Edit photo", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    onOpenEditor(
                                        currentItem.id,
                                        currentItem.localContentUri.orEmpty(),
                                        currentItem.name,
                                        currentItem.isLocal
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit in another app", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    coroutineScope.launch {
                                        isApplyingQuickAction = true
                                        appContainer.exportMediaUseCase.prepareShareUris(
                                            items = listOf(currentItem),
                                            stripLocation = false
                                        )
                                            .onSuccess { uris ->
                                                uris.firstOrNull()?.let { uri ->
                                                    appContainer.localFileOperationsManager.launchExternalEditor(
                                                        context,
                                                        uri,
                                                        currentItem.mimeType
                                                    )
                                                }
                                            }
                                            .onFailure { err ->
                                                viewModel.showMessage(err.localizedMessage ?: "Unable to open external editor")
                                            }
                                        isApplyingQuickAction = false
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Set as wallpaper", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    showWallpaperSheet = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Capture frame", color = Color.White) },
                                leadingIcon = {
                                    Icon(Icons.Rounded.CropFree, contentDescription = null, tint = Color.White)
                                },
                                enabled = activeVideoController.activeItemId == currentItem.id,
                                onClick = {
                                    showOverflowMenu = false
                                    activeVideoController.captureFrame()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Trim video", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    activeVideoController.pause()
                                    showTrimSheet = true
                                }
                            )
                        }

                        if (!currentItem.parentId.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text("Set as album cover", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    coroutineScope.launch {
                                        appContainer.galleryExtrasRepository.setCustomAlbumCover(
                                            currentItem.parentId,
                                            currentItem
                                        )
                                        viewModel.showMessage("Updated album cover")
                                    }
                                }
                            )
                        }

                        if (currentItem.isLocal) {
                            DropdownMenuItem(
                                text = { Text("Move or copy to album", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    showMoveCopySheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename", color = Color.White) },
                                onClick = {
                                    showOverflowMenu = false
                                    showRenameDialog = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isCurrentOffline) "Remove offline copy" else "Keep available offline",
                                        color = Color.White
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Rounded.OfflinePin,
                                        contentDescription = null,
                                        tint = if (isCurrentOffline) Color(0xFF69F0AE) else Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                },
                                onClick = {
                                    showOverflowMenu = false
                                    coroutineScope.launch {
                                        if (isCurrentOffline) {
                                            appContainer.offlineMediaManager.unpinItemOffline(currentItem.id)
                                            viewModel.showMessage("Removed offline copy")
                                        } else {
                                            isApplyingQuickAction = true
                                            appContainer.offlineMediaManager.pinItemOffline(currentItem)
                                                .onSuccess { viewModel.showMessage("Saved for offline viewing") }
                                                .onFailure { err ->
                                                    viewModel.showMessage(err.localizedMessage ?: "Offline save failed")
                                                }
                                            isApplyingQuickAction = false
                                        }
                                    }
                                }
                            )
                        }

                        DropdownMenuItem(
                            text = { Text("Tags & details", color = Color.White) },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.openExifSheet(currentItem)
                            }
                        )
                    }
                }
            }
        }

        // Playback, filmstrip navigation and media actions share the bottom of the viewer.
        AnimatedVisibility(
            visible = showChromeNow,
            enter = fadeIn(tween(150)),
            exit = if (isRectMorphing) {
                ExitTransition.None
            } else {
                fadeOut(tween(150)) + slideOutVertically { it / 2 }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            val isFavorited = currentItem.id in uiState.favoriteItemIds
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                AnimatedVisibility(
                    visible = currentItem.isVideo,
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(120)),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    VideoPlaybackControls(
                        controller = activeVideoController,
                        itemId = currentItem.id,
                        fallbackDurationMs = currentItem.durationMillis ?: 0L,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                // Pure-black Samsung Bottom Dock (Filmstrip + Icon Row)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (currentItem.isVideo) Color(0xED0C1119) else Color.Black)
                        .navigationBarsPadding()
                ) {
                    // 2. Samsung Filmstrip Thumbnail Scrubber
                    if (showFilmstrip) {
                        SamsungFilmstripScrubber(
                            items = items,
                            currentIndex = pagerState.currentPage,
                            isUnmeteredWifi = uiState.isUnmeteredWifi,
                            videoController = activeVideoController,
                            fallbackVideoDurationMs = currentItem.durationMillis ?: 0L,
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp)
                        )
                    }

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
                            AnimatedFavoriteIcon(
                                isFavorite = isFavorited,
                                size = 25.dp,
                                inactiveTint = Color.White,
                                activeTint = Color(0xFFFF5C77),
                                animationKey = currentItem.id
                            )
                        }

                        if (!currentItem.isVideo) {
                            IconButton(
                                onClick = {
                                    onOpenEditor(
                                        currentItem.id,
                                        currentItem.localContentUri.orEmpty(),
                                        currentItem.name,
                                        currentItem.isLocal
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
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
                                onClick = { trashConfirmItem = currentItem },
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

        LuminaSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(
                    bottom = when {
                        currentItem.isVideo && showChromeNow -> if (showFilmstrip) 228.dp else 170.dp
                        else -> 132.dp
                    }
                )
        )
    }

    trashConfirmItem?.let { itemToTrash ->
        val isHiddenLockedFile = com.luminagallery.drive.data.local.HiddenLockedFolderManager
            .isHiddenFileUri(itemToTrash.localContentUri)
        HyperOsTrashConfirmSheet(
            items = listOf(itemToTrash),
            mode = if (isHiddenLockedFile) TrashConfirmMode.DELETE_PERMANENTLY else TrashConfirmMode.MOVE_TO_TRASH,
            canSkipSystemPrompt = isHiddenLockedFile || appContainer.localMediaRepository.canManageMediaDirectly(),
            supportsSkipPromptSetting = !isHiddenLockedFile && appContainer.localMediaRepository.supportsManageMediaPermission(),
            onOpenManageMediaSettings = {
                appContainer.localMediaRepository.buildManageMediaSettingsIntent()?.let { intent ->
                    runCatching { context.startActivity(intent) }
                }
            },
            onConfirm = {
                trashConfirmItem = null
                viewModel.requestTrashLocalItem(itemToTrash)
            },
            onDismiss = { trashConfirmItem = null }
        )
    }

    if (uiState.showExifSheet) {
        ExifMetadataBottomSheet(
            item = uiState.activeExifOverride ?: currentItem,
            driveFolderPath = uiState.activeDrivePath,
            onDismiss = viewModel::closeExifSheet,
            onCopiedMessage = viewModel::showMessage,
            onOpenPerson = onOpenPerson?.let { openFn ->
                { pid ->
                    viewModel.closeExifSheet()
                    openFn(pid)
                }
            },
            onSearchTag = onSearchTag?.let { searchFn ->
                { tag ->
                    viewModel.closeExifSheet()
                    searchFn(tag)
                }
            }
        )
    }

    extractedTextResult?.let { scanned ->
        ModalBottomSheet(
            onDismissRequest = { extractedTextResult = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Text in photo",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                clipboard?.setPrimaryClip(ClipData.newPlainText("Photo text", scanned.fullText))
                                extractedTextResult = null
                                viewModel.showMessage("Copied all text to clipboard")
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Copy all", fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = {
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, scanned.fullText)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share text"))
                                extractedTextResult = null
                            }
                        ) {
                            Text("Share")
                        }
                    }
                }

                Text(
                    text = "Tap any block to copy it, or highlight specific words below:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    scanned.blocks.forEach { block ->
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = block,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        clipboard?.setPrimaryClip(ClipData.newPlainText("Photo text", block))
                                        viewModel.showMessage("Copied block to clipboard")
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy text block",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showWallpaperSheet) {
        ModalBottomSheet(
            onDismissRequest = { showWallpaperSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text = "Set as wallpaper",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showWallpaperSheet = false
                            coroutineScope.launch {
                                val quickUri = appContainer.localFileOperationsManager
                                    .getQuickLocalOrOfflineUri(currentItem)
                                val uri = quickUri ?: appContainer.exportMediaUseCase
                                    .prepareShareUris(listOf(currentItem), stripLocation = false)
                                    .getOrNull()
                                    ?.firstOrNull()
                                if (uri != null) {
                                    val launched = appContainer.localFileOperationsManager
                                        .launchNativeWallpaperCrop(context, uri, currentItem.mimeType)
                                    if (!launched) {
                                        viewModel.showMessage("Wallpaper cropper unavailable on this device")
                                    }
                                } else {
                                    viewModel.showMessage("Could not prepare photo for wallpaper crop")
                                }
                            }
                        }
                        .padding(horizontal = 24.dp, vertical = 15.dp)
                ) {
                    Text(
                        "Crop & set wallpaper (Recommended)",
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                WallpaperTarget.entries.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showWallpaperSheet = false
                                coroutineScope.launch {
                                    isApplyingQuickAction = true
                                    appContainer.localFileOperationsManager.setAsWallpaper(currentItem, target)
                                        .onSuccess { viewModel.showMessage("Wallpaper applied (${target.label})") }
                                        .onFailure { err ->
                                            viewModel.showMessage(err.localizedMessage ?: "Failed to apply wallpaper")
                                        }
                                    isApplyingQuickAction = false
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 15.dp)
                    ) {
                        Text(target.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }

    if (showMoveCopySheet && currentItem.isLocal) {
        MoveCopyToAlbumSheet(
            items = listOf(currentItem),
            initialMoveMode = false,
            onDismiss = { showMoveCopySheet = false },
            onCompleted = { msg ->
                showMoveCopySheet = false
                viewModel.showMessage(msg)
            }
        )
    }

    if (showRenameDialog && currentItem.isLocal) {
        var newName by remember(currentItem.id) {
            mutableStateOf(currentItem.name.substringBeforeLast('.'))
        }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            shape = RoundedCornerShape(26.dp),
            title = { Text("Rename file", fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Filename") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        showRenameDialog = false
                        val sender = appContainer.localFileOperationsManager
                            .buildWritePermissionSender(listOf(currentItem))
                        if (sender != null) {
                            pendingRenameNewName = newName.trim()
                            renameWriteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                        } else {
                            coroutineScope.launch {
                                appContainer.localFileOperationsManager.renameLocalItem(currentItem, newName.trim())
                                    .onSuccess { finalName ->
                                        appContainer.localMediaRepository.notifyPermissionChanged()
                                        viewModel.showMessage("Renamed to $finalName")
                                    }
                                    .onFailure { err ->
                                        viewModel.showMessage(err.localizedMessage ?: "Rename failed")
                                    }
                            }
                        }
                    }
                ) { Text("Rename", fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTrimSheet && currentItem.isVideo) {
        val totalMs = (activeVideoController.durationMs.takeIf { it > 0L }
            ?: currentItem.durationMillis ?: 10_000L).coerceAtLeast(1_000L)
        var range by remember(currentItem.id, totalMs) { mutableStateOf(0f..totalMs.toFloat()) }
        var isTrimming by remember { mutableStateOf(false) }

        ModalBottomSheet(
            onDismissRequest = { if (!isTrimming) showTrimSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Trim video (lossless)",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Start: ${FormatUtils.formatDurationPadded(range.start.toLong())}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Clip: ${FormatUtils.formatDurationPadded((range.endInclusive - range.start).toLong().coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "End: ${FormatUtils.formatDurationPadded(range.endInclusive.toLong())}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                RangeSlider(
                    value = range,
                    onValueChange = { updated ->
                        if (updated.endInclusive - updated.start >= 400f) {
                            val seekTarget = if (abs(updated.start - range.start) > 1f) {
                                updated.start.toLong()
                            } else {
                                updated.endInclusive.toLong()
                            }
                            range = updated
                            activeVideoController.pause()
                            activeVideoController.seekTo(seekTarget)
                        }
                    },
                    valueRange = 0f..totalMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        enabled = !isTrimming,
                        onClick = {
                            activeVideoController.seekTo(range.start.toLong())
                            activeVideoController.play()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Preview start")
                    }
                    Button(
                        onClick = {
                            if (isTrimming) return@Button
                            isTrimming = true
                            coroutineScope.launch {
                                appContainer.videoTrimmer.trimAndSaveCopy(
                                    item = currentItem,
                                    startMs = range.start.toLong(),
                                    endMs = range.endInclusive.toLong()
                                ).onSuccess { savedName ->
                                    appContainer.localMediaRepository.notifyPermissionChanged()
                                    isTrimming = false
                                    showTrimSheet = false
                                    viewModel.showMessage("Saved trimmed video: $savedName")
                                }.onFailure { err ->
                                    isTrimming = false
                                    viewModel.showMessage(err.localizedMessage ?: "Trim failed")
                                }
                            }
                        },
                        enabled = !isTrimming,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1.4f)
                    ) {
                        if (isTrimming) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Trimming…")
                        } else {
                            Text("Save trimmed copy")
                        }
                    }
                }
            }
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
    videoController: ActiveVideoController,
    fallbackVideoDurationMs: Long,
    onSelectPage: (Int) -> Unit,
    onScrubPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val density = LocalDensity.current
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = currentIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    )

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
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = 480f
                    ),
                    label = "stripGap"
                )

                val thumbRequest = remember(item.id, item.isLocal, item.localContentUri, item.thumbnailLink, item.isVideo, isUnmeteredWifi) {
                    if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
                        ImageRequest.Builder(context)
                            .data(LocalThumbnailRequest(item.localContentUri, item.isVideo))
                            .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                            .size(160)
                            .precision(Precision.INEXACT)
                            .allowRgb565(true)
                            .crossfade(false)
                            .build()
                    } else {
                        ImageRequest.Builder(context)
                            .data(
                                com.luminagallery.drive.data.repository.DriveThumbnailRequest(
                                    fileId = item.id,
                                    thumbnailLink = item.thumbnailLink,
                                    isUnmeteredWifi = isUnmeteredWifi,
                                    isVideo = item.isVideo,
                                    sizeBytes = item.sizeBytes,
                                    requestedMaxPx = 220
                                )
                            )
                            .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                            .diskCacheKey(DriveConstants.stableThumbDiskKey(item.id))
                            .size(160)
                            .precision(Precision.INEXACT)
                            .allowRgb565(true)
                            .crossfade(false)
                            .build()
                    }
                }

                val safeSideMargin = sideMargin.coerceAtLeast(0.dp)
                val safeTileWidth = tileWidth.coerceAtLeast(1.dp)
                val safeTileHeight = tileHeight.coerceAtLeast(1.dp)

                Box(
                    modifier = Modifier
                        .padding(horizontal = safeSideMargin)
                        .height(54.dp)
                        .width(safeTileWidth),
                    contentAlignment = Alignment.Center
                ) {
                    val tileWidthPx = with(density) { safeTileWidth.toPx() }.coerceAtLeast(1f)
                    val latestTileWidthPx by rememberUpdatedState(tileWidthPx)
                    val latestFallbackDuration by rememberUpdatedState(fallbackVideoDurationMs)

                    val scrubModifier = if (isVideoActive) {
                        Modifier
                            .pointerInput(item.id) {
                                detectTapGestures { offset ->
                                    val dur = (videoController.durationMs.takeIf { it > 0L }
                                        ?: latestFallbackDuration).coerceAtLeast(1L)
                                    val fraction = (offset.x / latestTileWidthPx).coerceIn(0f, 1f)
                                    videoController.seekTo((fraction * dur).toLong())
                                }
                            }
                            .pointerInput(item.id) {
                                detectHorizontalDragGestures(
                                    onDragStart = { videoController.isScrubbing = true },
                                    onDragEnd = { videoController.isScrubbing = false },
                                    onDragCancel = { videoController.isScrubbing = false }
                                ) { change, _ ->
                                    change.consume()
                                    val dur = (videoController.durationMs.takeIf { it > 0L }
                                        ?: latestFallbackDuration).coerceAtLeast(1L)
                                    val fraction = (change.position.x / latestTileWidthPx).coerceIn(0f, 1f)
                                    videoController.seekTo((fraction * dur).toLong())
                                }
                            }
                    } else {
                        Modifier.clickable { onSelectPage(index) }
                    }

                    Box(
                        modifier = Modifier
                            .width(safeTileWidth)
                            .height(safeTileHeight)
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

                        // Tiny white play triangle on neighboring video thumbnails
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

                    // Isolated playhead needle: only this tiny Box recomposes at 20fps during playback!
                    if (isVideoActive) {
                        FilmstripPlayheadNeedle(
                            videoController = videoController,
                            fallbackDurationMs = fallbackVideoDurationMs,
                            tileWidthPx = tileWidthPx
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmstripPlayheadNeedle(
    videoController: ActiveVideoController,
    fallbackDurationMs: Long,
    tileWidthPx: Float
) {
    val density = LocalDensity.current
    val safeDuration = (videoController.durationMs.takeIf { it > 0L } ?: fallbackDurationMs).coerceAtLeast(1L)
    val playFraction = (videoController.currentPositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
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

/**
 * High-refresh-rate zoomable image viewer:
 *  - Shows cached grid thumbnail synchronously on frame 0 via `placeholderMemoryCacheKey`
 *    while streaming the full-resolution image (`?alt=media`) strictly into memory.
 *  - Double-tap to zoom with spring physics anchored around the tap point.
 *  - Pinch-to-zoom (`1f..5f`) with strict pan boundary clamping.
 */
@MediaOptIn(UnstableApi::class)
@Composable
private fun ZoomablePhotoPage(
    item: MediaItem,
    isUnmeteredWifi: Boolean,
    startMuted: Boolean = false,
    isClosing: Boolean = false,
    motionPhotoInfo: MotionPhotoInfo? = null,
    isPlayingMotion: Boolean = false,
    onMotionPlayStateChanged: (Boolean) -> Unit = {},
    onTap: () -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    onImageAspectResolved: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContainer = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val coroutineScope = rememberCoroutineScope()

    val scaleAnim = remember(item.id) { Animatable(1f) }
    val offsetXAnim = remember(item.id) { Animatable(0f) }
    val offsetYAnim = remember(item.id) { Animatable(0f) }

    // Return pinch zoom to neutral while closing into the thumbnail.
    LaunchedEffect(isClosing, item.id) {
        if (!isClosing) return@LaunchedEffect
        val closeSpec = tween<Float>(
            durationMillis = OneUiMotion.MORPH_CLOSE_MS,
            easing = OneUiMotion.MorphCloseEasing
        )
        launch { scaleAnim.animateTo(1f, closeSpec) }
        launch { offsetXAnim.animateTo(0f, closeSpec) }
        launch { offsetYAnim.animateTo(0f, closeSpec) }
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var resolvedImageAspect by remember(item.id) { mutableStateOf<Float?>(null) }
    val offlinePath = remember(item.id) { appContainer.offlineMediaManager.getOfflineFilePath(item.id) }

    // Motion / Live Photo ExoPlayer (Samsung + Xiaomi + Pixel + iPhone Live Photos)
    var motionVideoUri by remember(item.id, motionPhotoInfo) { mutableStateOf<android.net.Uri?>(null) }
    var motionFirstFrameReady by remember(item.id, isPlayingMotion) { mutableStateOf(false) }
    var motionPlayer by remember(item.id) { mutableStateOf<ExoPlayer?>(null) }

    LaunchedEffect(item.id, motionPhotoInfo, isPlayingMotion) {
        if (isPlayingMotion && motionPhotoInfo != null && motionVideoUri == null) {
            motionVideoUri = appContainer.motionPhotoDetector.resolvePlayableVideoUri(
                item = item,
                info = motionPhotoInfo,
                offlineFilePath = offlinePath
            )
            if (motionVideoUri == null) {
                onMotionPlayStateChanged(false)
            }
        }
    }

    DisposableEffect(item.id, isPlayingMotion, motionVideoUri, lifecycleOwner) {
        val uri = motionVideoUri
        if (!isPlayingMotion || uri == null) {
            motionFirstFrameReady = false
            motionPlayer = null
            return@DisposableEffect onDispose {}
        }
        val audioAttrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val playerInstance = ExoPlayer.Builder(context)
            .setAudioAttributes(audioAttrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                setMediaItem(Media3Item.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_OFF
                volume = if (startMuted) 0f else 1f
                playWhenReady = true
                prepare()
            }
        motionPlayer = playerInstance

        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                motionFirstFrameReady = true
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onMotionPlayStateChanged(false)
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                onMotionPlayStateChanged(false)
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                playerInstance.pause()
                onMotionPlayStateChanged(false)
            }
        }
        playerInstance.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            playerInstance.removeListener(listener)
            playerInstance.stop()
            playerInstance.release()
            if (motionPlayer === playerInstance) motionPlayer = null
            motionFirstFrameReady = false
        }
    }

    val fullResRequest = remember(item.isLocal, item.localContentUri, item.id, offlinePath, isUnmeteredWifi) {
        if (!offlinePath.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(File(offlinePath))
                .size(VIEWER_MAX_DECODE_PX)
                .precision(Precision.INEXACT)
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .memoryCacheKey(DriveConstants.fullResMemoryKey(item.id))
                .crossfade(false)
                .build()
        } else if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(item.localContentUri)
                .size(VIEWER_MAX_DECODE_PX)
                .precision(Precision.INEXACT)
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
                .size(VIEWER_MAX_DECODE_PX)
                .precision(Precision.INEXACT)
                .placeholderMemoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .memoryCacheKey(DriveConstants.fullResMemoryKey(item.id))
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.DISABLED)
                .crossfade(false)
                .build()
        }
    }

    val latestMotionInfo by rememberUpdatedState(motionPhotoInfo)

    // Clamp pan strictly to the actual fitted image bounds (never allowing panning into black letterbox voids)
    fun clampOffset(x: Float, y: Float, scale: Float): Offset {
        val cw = containerSize.width.toFloat()
        val ch = containerSize.height.toFloat()
        val fitted = fittedViewerContentRect(item, cw, ch, resolvedImageAspect)
        val contentW = fitted?.width ?: cw
        val contentH = fitted?.height ?: ch
        val maxX = ((contentW * scale - cw) / 2f).coerceAtLeast(0f)
        val maxY = ((contentH * scale - ch) / 2f).coerceAtLeast(0f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(item.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = {
                        if (latestMotionInfo != null && scaleAnim.value <= 1.05f) {
                            onMotionPlayStateChanged(true)
                        }
                    },
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
                    var curScale = scaleAnim.value
                    var curOffX = offsetXAnim.value
                    var curOffY = offsetYAnim.value
                    var mutatedTransform = false

                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }

                        if (pressedPointers >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val centroid = event.calculateCentroid()

                            val prevScale = curScale.coerceAtLeast(0.01f)
                            val nextScale = (prevScale * zoomChange).coerceIn(1f, 5f)
                            val scaleRatio = nextScale / prevScale
                            val centerX = containerSize.width / 2f
                            val centerY = containerSize.height / 2f

                            val focusShiftX = (centroid.x - centerX) * (1f - scaleRatio)
                            val focusShiftY = (centroid.y - centerY) * (1f - scaleRatio)
                            val clamped = clampOffset(
                                x = curOffX * scaleRatio + panChange.x + focusShiftX,
                                y = curOffY * scaleRatio + panChange.y + focusShiftY,
                                scale = nextScale
                            )
                            curScale = nextScale
                            curOffX = clamped.x
                            curOffY = clamped.y
                            mutatedTransform = true

                            coroutineScope.launch {
                                scaleAnim.snapTo(curScale)
                                offsetXAnim.snapTo(curOffX)
                                offsetYAnim.snapTo(curOffY)
                            }
                            onZoomStateChanged(nextScale > 1.02f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (pressedPointers == 1 && curScale > 1.02f) {
                            val pan = event.calculatePan()
                            val nextClamped = clampOffset(
                                x = curOffX + pan.x,
                                y = curOffY + pan.y,
                                scale = curScale
                            )
                            val atHorizontalEdge =
                                abs(nextClamped.x - curOffX) < 0.5f && abs(pan.x) > abs(pan.y) * 1.4f
                            curOffX = nextClamped.x
                            curOffY = nextClamped.y
                            mutatedTransform = true

                            coroutineScope.launch {
                                offsetXAnim.snapTo(curOffX)
                                offsetYAnim.snapTo(curOffY)
                            }
                            if (atHorizontalEdge) {
                                onZoomStateChanged(false)
                            } else {
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (mutatedTransform && curScale < 1.05f) {
                        onZoomStateChanged(false)
                        coroutineScope.launch {
                            launch { scaleAnim.animateTo(1f, spring()) }
                            launch { offsetXAnim.animateTo(0f, spring()) }
                            launch { offsetYAnim.animateTo(0f, spring()) }
                        }
                    } else if (curScale > 1.02f) {
                        onZoomStateChanged(true)
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
            onSuccess = { state ->
                val intrinsic = state.painter.intrinsicSize
                if (intrinsic.width > 1f && intrinsic.height > 1f &&
                    !intrinsic.width.isNaN() && !intrinsic.height.isNaN()
                ) {
                    val aspect = intrinsic.width / intrinsic.height
                    resolvedImageAspect = aspect
                    onImageAspectResolved(aspect)
                }
            },
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scaleAnim.value
                    scaleY = scaleAnim.value
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value
                }
        )

        val activeMotionPlayer = motionPlayer
        if (activeMotionPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.view_exo_texture_player, null) as PlayerView).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = activeMotionPlayer
                    }
                },
                update = { pv ->
                    if (pv.player !== activeMotionPlayer) pv.player = activeMotionPlayer
                },
                onRelease = { pv ->
                    pv.player = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleAnim.value
                        scaleY = scaleAnim.value
                        translationX = offsetXAnim.value
                        translationY = offsetYAnim.value
                        alpha = if (motionFirstFrameReady) 1f else 0f
                    }
            )
        }
    }
}

private const val VIEWER_MAX_DECODE_PX = 3200

/**
 * On-screen rect (root coordinates) of a ContentScale.Fit photo / video inside the viewer's pager
 * area, computed purely from geometry so it stays valid while the morph frame is transformed.
 */
private fun fittedViewerContentRect(
    item: MediaItem?,
    areaWidth: Float,
    areaHeight: Float,
    fallbackAspectRatio: Float? = null
): Rect? {
    if (item == null || areaWidth <= 1f || areaHeight <= 1f) return null
    val iw = item.effectiveWidth
    val ih = item.effectiveHeight
    val imageAspect = when {
        fallbackAspectRatio != null && fallbackAspectRatio.isFinite() && fallbackAspectRatio > 0.05f -> fallbackAspectRatio
        iw != null && ih != null && iw > 0 && ih > 0 -> iw.toFloat() / ih.toFloat()
        else -> return null
    }
    val areaAspect = areaWidth / areaHeight
    val fittedW: Float
    val fittedH: Float
    if (imageAspect > areaAspect) {
        fittedW = areaWidth
        fittedH = areaWidth / imageAspect
    } else {
        fittedH = areaHeight
        fittedW = areaHeight * imageAspect
    }
    val left = (areaWidth - fittedW) * 0.5f
    val top = (areaHeight - fittedH) * 0.5f
    return Rect(left, top, left + fittedW, top + fittedH)
}
