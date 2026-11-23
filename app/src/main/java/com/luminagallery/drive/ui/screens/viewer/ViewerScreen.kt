package com.luminagallery.drive.ui.screens.viewer

import android.app.Activity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn as MediaOptIn
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
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem as Media3Item
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
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.data.repository.MotionPhotoInfo
import com.luminagallery.drive.data.repository.WallpaperTarget
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.ExifMetadataBottomSheet
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.MoveCopyToAlbumSheet
import com.luminagallery.drive.ui.components.OneUiMotion
import com.luminagallery.drive.ui.components.oneUiClickable
import com.luminagallery.drive.ui.components.topViewerScrim
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    viewModel: ViewerViewModel,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onActiveItemChanged: (String) -> Unit,
    onOpenEditor: (itemId: String, sourceUri: String, displayName: String, isLocal: Boolean) -> Unit = { _, _, _, _ -> },
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

    var chromeVisible by remember { mutableStateOf(true) }
    var isCurrentPageZoomed by remember { mutableStateOf(false) }
    var dismissDragFraction by remember { mutableFloatStateOf(0f) }
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

    val prefs = uiState.preferences

    // Samsung Gallery "Maximum brightness" & "Keep screen on" viewer flags
    DisposableEffect(prefs.maxViewerBrightness, prefs.keepScreenOnViewer) {
        val window = (context as? Activity)?.window
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
    LaunchedEffect(currentItem.id) {
        isCurrentPageZoomed = false
        isPlayingMotionPhoto = false
        currentMotionInfo = null
        onActiveItemChanged(currentItem.id)
    }

    LaunchedEffect(settledItem.id) {
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

    val backgroundAlpha = (1f - dismissDragFraction * 0.85f).coerceIn(0.12f, 1f)
    val showChromeNow = chromeVisible && dismissDragFraction < 0.05f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = backgroundAlpha))
    ) {
        // Media Pager (sits neatly above the pure-black Samsung bottom dock when chrome is visible
        // so bright screenshots never clash with bottom icons, and goes full-bleed when immersive!)
        val showFilmstrip = prefs.showViewerFilmstrip
        val bottomDockClearance by animateDpAsState(
            targetValue = when {
                !showChromeNow -> 0.dp
                showFilmstrip -> 118.dp
                else -> 60.dp
            },
            animationSpec = tween(durationMillis = 200),
            label = "mediaBottomClearance"
        )

        HorizontalPager(
            state = pagerState,
            key = { index -> items.getOrNull(index)?.id ?: "page_$index" },
            userScrollEnabled = !isCurrentPageZoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = bottomDockClearance)
        ) { page ->
            val pageItem = items.getOrNull(page) ?: return@HorizontalPager
            val isActivePage = page == pagerState.currentPage
            val isSettledPage = page == pagerState.settledPage

            // Attach sharedElement ONLY on the settled page so Compose never detaches/reattaches
            // shared transition nodes mid-drag while the user's finger is swiping the pager!
            val sharedModifier = if (isSettledPage) {
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
                    autoPlay = prefs.autoPlayVideos,
                    startMuted = prefs.startVideosMuted,
                    loopVideo = prefs.loopVideos,
                    swipeDownToDismiss = prefs.swipeDownToDismiss,
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
                    swipeDownToDismiss = prefs.swipeDownToDismiss,
                    motionPhotoInfo = if (isActivePage) currentMotionInfo else null,
                    isPlayingMotion = isActivePage && isPlayingMotionPhoto,
                    onMotionPlayStateChanged = { playing ->
                        if (isActivePage) isPlayingMotionPhoto = playing
                    },
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
                            text = { Text(openInLabel) },
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
                                        appContainer.exportMediaUseCase.prepareShareUris(listOf(itemToOpen))
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
                                text = { Text("Edit photo") },
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
                                text = { Text("Edit in another app") },
                                onClick = {
                                    showOverflowMenu = false
                                    coroutineScope.launch {
                                        isApplyingQuickAction = true
                                        appContainer.exportMediaUseCase.prepareShareUris(listOf(currentItem))
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
                                text = { Text("Set as wallpaper") },
                                onClick = {
                                    showOverflowMenu = false
                                    showWallpaperSheet = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text("Trim video") },
                                onClick = {
                                    showOverflowMenu = false
                                    showTrimSheet = true
                                }
                            )
                        }

                        if (!currentItem.parentId.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text("Set as album cover") },
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
                                text = { Text("Move or copy to album") },
                                onClick = {
                                    showOverflowMenu = false
                                    showMoveCopySheet = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    showOverflowMenu = false
                                    showRenameDialog = true
                                }
                            )
                        } else {
                            DropdownMenuItem(
                                text = {
                                    Text(if (isCurrentOffline) "Remove offline copy" else "Keep available offline")
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
                            text = { Text("Tags & details") },
                            onClick = {
                                showOverflowMenu = false
                                viewModel.openExifSheet(currentItem)
                            }
                        )
                    }
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
                        playbackSpeed = activeVideoController.playbackSpeed,
                        positionMs = activeVideoController.currentPositionMs,
                        durationMs = activeVideoController.durationMs.takeIf { it > 0L }
                            ?: (currentItem.durationMillis ?: 0L),
                        onCaptureFrame = activeVideoController::captureFrame,
                        onTogglePlayPause = activeVideoController::togglePlayPause,
                        onCycleSpeed = activeVideoController::cyclePlaybackSpeed,
                        onOpenTrim = { showTrimSheet = true },
                        onToggleMute = activeVideoController::toggleMute,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
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
                    if (showFilmstrip) {
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
                            range = updated
                        }
                    },
                    valueRange = 0f..totalMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )
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
                    modifier = Modifier.fillMaxWidth()
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

/**
 * Samsung One UI floating video controls row sitting right above the filmstrip:
 *  - Left: Capture frame + Trim scissors
 *  - Center: Play/Pause + `00:01 / 00:02`
 *  - Right: Playback speed pill (`0.5×..2×`) + Mute / Unmute
 */
@Composable
private fun SamsungVideoControlBar(
    isPlaying: Boolean,
    isEnded: Boolean,
    isMuted: Boolean,
    playbackSpeed: Float,
    positionMs: Long,
    durationMs: Long,
    onCaptureFrame: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onCycleSpeed: () -> Unit,
    onOpenTrim: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pillBg = Color(0xB3141416)
    val pillBorder = Color.White.copy(alpha = 0.16f)
    val speedLabel = if (playbackSpeed % 1f == 0f) {
        "${playbackSpeed.toInt()}×"
    } else {
        "${String.format(Locale.US, "%.2f", playbackSpeed).trimEnd('0')}×"
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Capture frame + Trim
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
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
                    modifier = Modifier.size(18.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(pillBg)
                    .border(0.8.dp, pillBorder, CircleShape)
                    .clickable(onClick = onOpenTrim),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCut,
                    contentDescription = "Trim video",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
            }
        }

        // Center: Play/Pause + "00:01 / 00:02" glass capsule
        Surface(
            shape = RoundedCornerShape(50),
            color = pillBg,
            modifier = Modifier
                .height(36.dp)
                .border(0.8.dp, pillBorder, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onTogglePlayPause)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Icon(
                    imageVector = when {
                        isEnded -> Icons.Rounded.Replay
                        isPlaying -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "${FormatUtils.formatDurationPadded(positionMs)} / ${FormatUtils.formatDurationPadded(durationMs)}",
                    style = TextStyle(
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFeatureSettings = "tnum",
                        color = Color.White
                    ),
                    maxLines = 1
                )
            }
        }

        // Right: Speed pill + Mute / Unmute circle
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(50),
                color = if (playbackSpeed != 1f) MaterialTheme.colorScheme.primary else pillBg,
                modifier = Modifier
                    .height(36.dp)
                    .border(0.8.dp, pillBorder, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onCycleSpeed)
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = speedLabel,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(36.dp)
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
                    animationSpec = OneUiMotion.snappySpring(),
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
                            .precision(Precision.INEXACT)
                            .allowRgb565(true)
                            .crossfade(false)
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
@MediaOptIn(UnstableApi::class)
@Composable
private fun ZoomablePhotoPage(
    item: MediaItem,
    isUnmeteredWifi: Boolean,
    swipeDownToDismiss: Boolean = true,
    motionPhotoInfo: MotionPhotoInfo? = null,
    isPlayingMotion: Boolean = false,
    onMotionPlayStateChanged: (Boolean) -> Unit = {},
    onTap: () -> Unit,
    onZoomStateChanged: (Boolean) -> Unit,
    onVerticalDismissProgress: (Float) -> Unit,
    onSwipeUpForDetails: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val coroutineScope = rememberCoroutineScope()

    val scaleAnim = remember(item.id) { Animatable(1f) }
    val offsetXAnim = remember(item.id) { Animatable(0f) }
    val offsetYAnim = remember(item.id) { Animatable(0f) }
    val dismissOffsetY = remember(item.id) { Animatable(0f) }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val offlinePath = remember(item.id) { appContainer.offlineMediaManager.getOfflineFilePath(item.id) }

    // Motion / Live Photo ExoPlayer (Samsung + Xiaomi + Pixel + iPhone Live Photos)
    var motionVideoUri by remember(item.id, motionPhotoInfo) { mutableStateOf<android.net.Uri?>(null) }
    var motionFirstFrameReady by remember(item.id, isPlayingMotion) { mutableStateOf(false) }

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

    val motionPlayer: ExoPlayer? = remember(item.id, isPlayingMotion, motionVideoUri) {
        val uri = motionVideoUri
        if (!isPlayingMotion || uri == null) {
            null
        } else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(Media3Item.fromUri(uri))
                repeatMode = Player.REPEAT_MODE_OFF
                playWhenReady = true
                prepare()
            }
        }
    }

    DisposableEffect(motionPlayer) {
        if (motionPlayer == null) {
            motionFirstFrameReady = false
            return@DisposableEffect onDispose {}
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                motionFirstFrameReady = true
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onMotionPlayStateChanged(false)
                }
            }
        }
        motionPlayer.addListener(listener)
        onDispose {
            motionPlayer.removeListener(listener)
            motionPlayer.stop()
            motionPlayer.release()
            motionFirstFrameReady = false
        }
    }

    val fullResRequest = remember(item.isLocal, item.localContentUri, item.id, offlinePath, isUnmeteredWifi) {
        if (!offlinePath.isNullOrBlank() && File(offlinePath).exists()) {
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
    val latestSwipeDown by rememberUpdatedState(swipeDownToDismiss)

    fun clampOffset(x: Float, y: Float, scale: Float): Offset {
        val maxX = ((scale - 1f) * containerSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((scale - 1f) * containerSize.height / 2f).coerceAtLeast(0f)
        return Offset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }

    val dismissFraction = (abs(dismissOffsetY.value) / 420f).coerceIn(0f, 1f)
    val dismissScale = 1f - (dismissFraction * 0.22f)

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
                                    val rawY = dismissOffsetY.value + pan.y
                                    val newY = if (!latestSwipeDown && rawY > 0f) 0f else rawY
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
                            latestSwipeDown && dismissOffsetY.value > dismissThresholdPx -> {
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
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    val effectiveScale = scaleAnim.value * dismissScale
                    scaleX = effectiveScale
                    scaleY = effectiveScale
                    translationX = offsetXAnim.value
                    translationY = offsetYAnim.value + dismissOffsetY.value
                }
        )

        if (motionPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = motionPlayer
                    }
                },
                update = { pv ->
                    if (pv.player !== motionPlayer) pv.player = motionPlayer
                },
                onRelease = { pv ->
                    pv.player = null
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = if (motionFirstFrameReady) 1f else 0f
                    }
            )
        }
    }
}

private const val VIEWER_MAX_DECODE_PX = 3200
