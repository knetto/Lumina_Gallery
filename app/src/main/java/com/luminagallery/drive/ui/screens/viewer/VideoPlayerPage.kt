package com.luminagallery.drive.ui.screens.viewer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import com.luminagallery.drive.R
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.GlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Shared reactive controller linking the active `VideoPlayerPage`'s ExoPlayer to Samsung's
 * floating pill controls + inline filmstrip scrubber in `ViewerScreen`.
 */
@Stable
class ActiveVideoController {
    var activeItemId by mutableStateOf<String?>(null)
    var isPlaying by mutableStateOf(false)
    var isEnded by mutableStateOf(false)
    var isMuted by mutableStateOf(false)
    var playbackSpeed by mutableFloatStateOf(1f)
    var currentPositionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)

    internal var onTogglePlayPause: (() -> Unit)? = null
    internal var onToggleMute: (() -> Unit)? = null
    internal var onSeekTo: ((Long) -> Unit)? = null
    internal var onCaptureFrame: (() -> Unit)? = null
    internal var onSetSpeed: ((Float) -> Unit)? = null

    fun togglePlayPause() = onTogglePlayPause?.invoke()
    fun toggleMute() = onToggleMute?.invoke()
    fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
        onSeekTo?.invoke(currentPositionMs)
    }
    fun skipBy(deltaMs: Long) {
        seekTo(currentPositionMs + deltaMs)
    }
    fun cyclePlaybackSpeed() {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val idx = speeds.indexOfFirst { kotlin.math.abs(it - playbackSpeed) < 0.05f }
        val next = speeds[(idx + 1) % speeds.size]
        playbackSpeed = next
        onSetSpeed?.invoke(next)
    }
    fun captureFrame() = onCaptureFrame?.invoke()
}

/**
 * Streams local or Google Drive videos via Media3 ExoPlayer with zero UI clutter over the video
 * itself — controls live cleanly in Samsung's floating pill & bottom filmstrip scrubber.
 *
 * Power optimization: ExoPlayer is created ONLY when `isActivePage == true` and is
 * immediately stopped & released via `DisposableEffect` the moment the user swipes away.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerPage(
    item: MediaItem,
    isActivePage: Boolean,
    isUnmeteredWifi: Boolean,
    chromeVisible: Boolean,
    videoController: ActiveVideoController,
    autoPlay: Boolean = true,
    startMuted: Boolean = false,
    loopVideo: Boolean = false,
    swipeDownToDismiss: Boolean = true,
    onToggleChrome: () -> Unit,
    onVerticalDismissProgress: (Float) -> Unit,
    onSwipeUpForDetails: () -> Unit,
    onFrameCapturedMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    val dismissOffsetY = remember(item.id) { Animatable(0f) }

    val thumbnailRequest = remember(item.isLocal, item.localContentUri, item.id, item.thumbnailLink, isUnmeteredWifi) {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(item.localContentUri, isVideo = true))
                .size(1080)
                .precision(Precision.INEXACT)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .build()
        } else {
            val url = DriveConstants.buildThumbnailUrl(
                thumbnailLink = item.thumbnailLink,
                fileId = item.id,
                isUnmeteredWifi = isUnmeteredWifi,
                isVideo = true,
                sizeBytes = item.sizeBytes
            ) ?: return@remember null
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .diskCacheKey(DriveConstants.thumbnailCacheKey(item.id, isUnmeteredWifi))
                .build()
        }
    }

    var isPlaying by remember(item.id) { mutableStateOf(false) }
    var isBuffering by remember(item.id) { mutableStateOf(true) }
    var isEnded by remember(item.id) { mutableStateOf(false) }
    var isMuted by remember(item.id, startMuted) { mutableStateOf(startMuted) }
    var firstFrameRendered by remember(item.id) { mutableStateOf(false) }
    var playbackError by remember(item.id) { mutableStateOf<String?>(null) }
    var playerViewRef by remember(item.id) { mutableStateOf<PlayerView?>(null) }
    var skipFeedbackText by remember(item.id) { mutableStateOf<String?>(null) }
    var skipFeedbackLeft by remember(item.id) { mutableStateOf(false) }

    LaunchedEffect(skipFeedbackText) {
        if (skipFeedbackText != null) {
            delay(550L)
            skipFeedbackText = null
        }
    }

    val offlinePath = remember(item.id) { appContainer.offlineMediaManager.getOfflineFilePath(item.id) }

    val exoPlayer: ExoPlayer? = remember(item.id, item.isLocal, item.localContentUri, offlinePath, isActivePage) {
        if (!isActivePage) {
            null
        } else if (!offlinePath.isNullOrBlank() && File(offlinePath).exists()) {
            ExoPlayer.Builder(context)
                .build()
                .apply {
                    setMediaItem(Media3Item.fromUri(android.net.Uri.fromFile(File(offlinePath))))
                    repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    volume = if (startMuted) 0f else 1f
                    playWhenReady = autoPlay
                    prepare()
                }
        } else if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            ExoPlayer.Builder(context)
                .build()
                .apply {
                    setMediaItem(Media3Item.fromUri(item.localContentUri))
                    repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    volume = if (startMuted) 0f else 1f
                    playWhenReady = autoPlay
                    prepare()
                }
        } else {
            val okHttpDataSourceFactory = OkHttpDataSource.Factory(appContainer.okHttpClient)
            val mediaSourceFactory = DefaultMediaSourceFactory(okHttpDataSourceFactory)
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    val streamUrl = DriveConstants.buildFullMediaUrl(item.id)
                    setMediaItem(Media3Item.fromUri(streamUrl))
                    repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    volume = if (startMuted) 0f else 1f
                    playWhenReady = autoPlay
                    prepare()
                }
        }
    }

    // Bind active ExoPlayer instance into `ActiveVideoController`
    DisposableEffect(exoPlayer, isActivePage) {
        if (exoPlayer == null || !isActivePage) {
            firstFrameRendered = false
            isPlaying = false
            return@DisposableEffect onDispose {}
        }

        videoController.activeItemId = item.id
        videoController.durationMs = (exoPlayer.duration.takeIf { it > 0L } ?: item.durationMillis ?: 0L)
        videoController.currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
        videoController.isMuted = isMuted
        videoController.playbackSpeed = 1f
        videoController.isPlaying = exoPlayer.isPlaying
        videoController.isEnded = false

        videoController.onTogglePlayPause = {
            when {
                exoPlayer.playbackState == Player.STATE_ENDED -> {
                    exoPlayer.seekTo(0L)
                    exoPlayer.play()
                }
                exoPlayer.isPlaying -> exoPlayer.pause()
                else -> exoPlayer.play()
            }
        }
        videoController.onToggleMute = {
            val next = !isMuted
            isMuted = next
            exoPlayer.volume = if (next) 0f else 1f
            videoController.isMuted = next
        }
        videoController.onSeekTo = { posMs ->
            exoPlayer.seekTo(posMs)
        }
        videoController.onSetSpeed = { speed ->
            exoPlayer.setPlaybackSpeed(speed)
        }
        videoController.onCaptureFrame = {
            val pv = playerViewRef
            captureCurrentVideoFrame(
                context = context,
                playerView = pv,
                sourceName = item.name,
                onResult = { msg ->
                    coroutineScope.launch { onFrameCapturedMessage(msg) }
                }
            )
        }

        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                videoController.isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                isEnded = playbackState == Player.STATE_ENDED
                videoController.isEnded = isEnded
                if (exoPlayer.duration > 0L) {
                    videoController.durationMs = exoPlayer.duration
                }
                if (isEnded) {
                    videoController.currentPositionMs = videoController.durationMs
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                isBuffering = false
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playbackError = error.localizedMessage ?: "Video playback error"
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                else -> Unit
            }
        }

        exoPlayer.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
            if (videoController.activeItemId == item.id) {
                videoController.onTogglePlayPause = null
                videoController.onToggleMute = null
                videoController.onSeekTo = null
                videoController.onSetSpeed = null
                videoController.onCaptureFrame = null
                videoController.isPlaying = false
            }
        }
    }

    // Silky 20fps playhead ticker while actively playing & chrome visible (smooth white scrub bar!)
    LaunchedEffect(exoPlayer, isPlaying, chromeVisible, isActivePage) {
        while (exoPlayer != null && isActivePage && isPlaying && chromeVisible) {
            videoController.currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (exoPlayer.duration > 0L) videoController.durationMs = exoPlayer.duration
            delay(50L)
        }
    }

    val dismissFraction = (dismissOffsetY.value / 420f).coerceIn(0f, 1f)
    val dismissScale = 1f - (dismissFraction * 0.22f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(item.id, swipeDownToDismiss) {
                var swipeUpAccumulated = 0f
                var detailsFired = false
                detectVerticalDragGestures(
                    onDragStart = {
                        swipeUpAccumulated = 0f
                        detailsFired = false
                    },
                    onDragEnd = {
                        when {
                            swipeDownToDismiss && dismissOffsetY.value > 210f -> {
                                onVerticalDismissProgress(0f)
                                onDismiss()
                            }
                            !detailsFired && swipeUpAccumulated > 55f -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSwipeUpForDetails()
                            }
                            dismissOffsetY.value > 0f -> {
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
                    },
                    onDragCancel = {
                        if (dismissOffsetY.value > 0f) {
                            coroutineScope.launch {
                                dismissOffsetY.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy))
                                onVerticalDismissProgress(0f)
                            }
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (dragAmount < 0f && dismissOffsetY.value <= 0f) {
                            // Keep video completely stationary when swiping up for Details
                            swipeUpAccumulated += -dragAmount
                            if (!detailsFired && swipeUpAccumulated > 95f) {
                                detailsFired = true
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSwipeUpForDetails()
                            }
                        } else if (swipeDownToDismiss) {
                            val updated = (dismissOffsetY.value + dragAmount).coerceAtLeast(0f)
                            coroutineScope.launch { dismissOffsetY.snapTo(updated) }
                            onVerticalDismissProgress((updated / 420f).coerceIn(0f, 1f))
                        }
                    }
                )
            }
            .graphicsLayer {
                scaleX = dismissScale
                scaleY = dismissScale
                translationY = dismissOffsetY.value
            }
            .pointerInput(item.id, exoPlayer) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { offset ->
                        if (exoPlayer != null) {
                            val isLeft = offset.x < size.width / 2f
                            val delta = if (isLeft) -5_000L else 5_000L
                            val target = (exoPlayer.currentPosition + delta)
                                .coerceIn(0L, exoPlayer.duration.coerceAtLeast(1L))
                            exoPlayer.seekTo(target)
                            videoController.currentPositionMs = target
                            skipFeedbackLeft = isLeft
                            skipFeedbackText = if (isLeft) "-5s" else "+5s"
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (exoPlayer != null) {
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
                        player = exoPlayer
                        playerViewRef = this
                    }
                },
                update = { view ->
                    playerViewRef = view
                    if (view.player !== exoPlayer) {
                        view.player = exoPlayer
                    }
                },
                onRelease = { view ->
                    view.player = null
                    if (playerViewRef === view) playerViewRef = null
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!firstFrameRendered || exoPlayer == null) {
            AsyncImage(
                model = thumbnailRequest,
                imageLoader = imageLoader,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (isActivePage && isBuffering && playbackError == null) {
            CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(40.dp)
            )
        }

        // Double-tap ±5s skip indicator overlay
        AnimatedVisibility(
            visible = skipFeedbackText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (skipFeedbackLeft) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 36.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = skipFeedbackText.orEmpty(),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        playbackError?.let { err ->
            GlassSurface(
                shape = RoundedCornerShape(16.dp),
                containerColor = Color.Black.copy(alpha = 0.78f),
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

/**
 * Samsung-style Video Frame Capture: grabs the exact current frame from ExoPlayer's SurfaceView
 * (works for BOTH local videos and streamed Google Drive videos!) and saves it to Pictures/Lumina.
 */
@OptIn(UnstableApi::class)
private fun captureCurrentVideoFrame(
    context: Context,
    playerView: PlayerView?,
    sourceName: String,
    onResult: (String) -> Unit
) {
    val surfaceView = playerView?.videoSurfaceView
    if (surfaceView == null || surfaceView.width <= 0 || surfaceView.height <= 0) {
        onResult("Unable to capture frame right now")
        return
    }

    fun persistBitmap(bmp: Bitmap) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val saved = runCatching {
                val baseName = sourceName.substringBeforeLast('.').ifBlank { "VideoFrame" }
                val fileName = "${baseName}_frame_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Lumina")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Insert failed")
                resolver.openOutputStream(uri)?.use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }.isSuccess
            bmp.recycle()
            withContext(Dispatchers.Main) {
                onResult(if (saved) "Captured frame saved to gallery" else "Could not save captured frame")
            }
        }
    }

    when {
        surfaceView is TextureView -> {
            val bmp = surfaceView.bitmap
            if (bmp != null) persistBitmap(bmp) else onResult("Unable to capture frame")
        }
        surfaceView is SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
            val bmp = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
            runCatching {
                PixelCopy.request(
                    surfaceView,
                    bmp,
                    { copyResult ->
                        if (copyResult == PixelCopy.SUCCESS) {
                            persistBitmap(bmp)
                        } else {
                            bmp.recycle()
                            onResult("Unable to capture frame")
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }.onFailure {
                bmp.recycle()
                onResult("Unable to capture frame")
            }
        }
        else -> onResult("Frame capture unavailable on this device")
    }
}
