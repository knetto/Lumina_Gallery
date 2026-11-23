package com.luminagallery.drive.ui.screens.viewer

import android.net.Uri
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.R
import com.luminagallery.drive.data.repository.VideoFormatSupport
import com.luminagallery.drive.data.repository.WallpaperTarget
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
import com.luminagallery.drive.ui.components.findActivity
import com.luminagallery.drive.ui.components.topViewerScrim
import kotlinx.coroutines.launch

/**
 * Full-screen viewer used when Lumina Gallery handles an Android `ACTION_VIEW` intent
 * (e.g., tapping a photo or video in Samsung My Files, Downloads, or messaging apps).
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalMediaViewerOverlay(
    uriString: String,
    mimeType: String,
    onOpenEditor: (uri: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    BackHandler(onBack = onDismiss)

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

    val parsedUri = remember(uriString) { Uri.parse(uriString) }
    val displayName = remember(parsedUri) {
        val queried = runCatching {
            context.contentResolver.query(
                parsedUri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
        queried?.takeIf { it.isNotBlank() }
            ?: parsedUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "External Media" }
            ?: "External Media"
    }
    val isVideo = remember(mimeType, uriString, displayName) {
        VideoFormatSupport.isVideoFile(displayName, mimeType) ||
            VideoFormatSupport.isVideoFile(uriString, mimeType)
    }

    var scale by remember(uriString) { mutableFloatStateOf(1f) }
    var offset by remember(uriString) { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var chromeVisible by remember(uriString) { mutableStateOf(true) }
    var showWallpaperSheet by remember { mutableStateOf(false) }

    fun clampOffset(raw: Offset, s: Float): Offset {
        val maxX = ((s - 1f) * containerSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((s - 1f) * containerSize.height / 2f).coerceAtLeast(0f)
        return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it }
    ) {
        if (isVideo) {
            var exoPlayer by remember(uriString) { mutableStateOf<ExoPlayer?>(null) }
            DisposableEffect(uriString, lifecycleOwner) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                val isMp2p = VideoFormatSupport.isMp2pVideo(displayName, mimeType) ||
                    VideoFormatSupport.isMp2pVideo(uriString, mimeType)
                val extractorsFactory = VideoFormatSupport.buildExtractorsFactory(preferProgramStream = isMp2p)
                val hintedMime = VideoFormatSupport.resolveExoPlayerMimeType(displayName, mimeType)
                    ?: VideoFormatSupport.resolveExoPlayerMimeType(uriString, mimeType)
                val mediaItem = Media3Item.Builder()
                    .setUri(parsedUri)
                    .apply { if (!hintedMime.isNullOrBlank()) setMimeType(hintedMime) }
                    .build()
                val playerInstance = ExoPlayer.Builder(
                    context,
                    VideoFormatSupport.buildRenderersFactory(context)
                )
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                    .setAudioAttributes(audioAttributes, true)
                    .setHandleAudioBecomingNoisy(true)
                    .build()
                    .apply {
                        setMediaItem(mediaItem)
                        playWhenReady = true
                        prepare()
                    }
                exoPlayer = playerInstance
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                        playerInstance.pause()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    playerInstance.stop()
                    playerInstance.release()
                    if (exoPlayer === playerInstance) exoPlayer = null
                }
            }
            val activePlayer = exoPlayer
            if (activePlayer != null) {
                AndroidView(
                    factory = { ctx ->
                        (LayoutInflater.from(ctx).inflate(R.layout.view_exo_texture_player, null) as PlayerView).apply {
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            player = activePlayer
                        }
                    },
                    update = { pv -> if (pv.player !== activePlayer) pv.player = activePlayer },
                    onRelease = { pv -> pv.player = null },
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            val req = remember(uriString) {
                ImageRequest.Builder(context)
                    .data(parsedUri)
                    .crossfade(false)
                    .build()
            }
            AsyncImage(
                model = req,
                imageLoader = imageLoader,
                contentDescription = displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(uriString) {
                        detectTapGestures(
                            onTap = { chromeVisible = !chromeVisible },
                            onDoubleTap = { tapOffset ->
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val targetScale = 2.6f
                                    val cx = containerSize.width / 2f
                                    val cy = containerSize.height / 2f
                                    scale = targetScale
                                    offset = clampOffset(
                                        Offset((cx - tapOffset.x) * (targetScale - 1f), (cy - tapOffset.y) * (targetScale - 1f)),
                                        targetScale
                                    )
                                }
                            }
                        )
                    }
                    .pointerInput(uriString) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val prev = scale
                            val next = (prev * zoom).coerceIn(1f, 5f)
                            val ratio = if (prev > 0f) next / prev else 1f
                            val cx = containerSize.width / 2f
                            val cy = containerSize.height / 2f
                            val fx = (centroid.x - cx) * (1f - ratio)
                            val fy = (centroid.y - cy) * (1f - ratio)
                            scale = next
                            offset = if (next <= 1.02f) {
                                Offset.Zero
                            } else {
                                clampOffset(
                                    Offset(offset.x * ratio + pan.x + fx, offset.y * ratio + pan.y + fy),
                                    next
                                )
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
        }

        // Top Bar
        AnimatedVisibility(
            visible = isVideo || chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
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
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close", tint = Color.White)
                }
                Text(
                    text = displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isVideo) {
                    IconButton(
                        onClick = {
                            FormatUtils.launchShareSheet(context, listOf(parsedUri), mimeType.ifBlank { "video/*" })
                        }
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share video", tint = Color.White)
                    }
                }
            }
        }

        // Bottom Actions Bar (for images)
        AnimatedVisibility(
            visible = !isVideo && chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .navigationBarsPadding()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = { onOpenEditor(uriString, displayName) }) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = Color.White)
                }
                IconButton(onClick = { showWallpaperSheet = true }) {
                    Icon(Icons.Outlined.Wallpaper, contentDescription = "Set wallpaper", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        scope.launch {
                            val cleanUri = container.mediaExportManager
                                .prepareCleanShareUri(parsedUri, displayName, mimeType)
                                .getOrDefault(parsedUri)
                            FormatUtils.launchShareSheet(context, listOf(cleanUri), mimeType)
                        }
                    }
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White)
                }
            }
        }

        LuminaSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 70.dp)
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
                                val pseudoItem = MediaItem(
                                    id = "ext_$uriString",
                                    parentId = null,
                                    name = displayName,
                                    mimeType = mimeType,
                                    mediaType = MediaType.PHOTO,
                                    sizeBytes = 0L,
                                    createdTimeEpochMs = 0L,
                                    monthYearLabel = "",
                                    fullDateLabel = "",
                                    thumbnailLink = uriString,
                                    width = null,
                                    height = null,
                                    rotation = null,
                                    durationMillis = null,
                                    exif = ExifMetadata(null, null, null, null, null, null, null, null),
                                    isLocal = true,
                                    localContentUri = uriString
                                )
                                scope.launch {
                                    container.localFileOperationsManager.setAsWallpaper(pseudoItem, target)
                                        .onSuccess { snackbarHostState.showSnackbar("Applied to ${target.label}") }
                                        .onFailure { err ->
                                            snackbarHostState.showSnackbar(err.localizedMessage ?: "Failed to set wallpaper")
                                        }
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
}
