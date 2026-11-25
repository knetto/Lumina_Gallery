package com.luminagallery.drive.ui.screens.viewer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
import com.luminagallery.drive.data.repository.VideoFormatSupport
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.ui.components.GlassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects the active video player to the playback controls and filmstrip.
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
    var isScrubbing by mutableStateOf(false)

    internal var onTogglePlayPause: (() -> Unit)? = null
    internal var onPause: (() -> Unit)? = null
    internal var onPlay: (() -> Unit)? = null
    internal var onToggleMute: (() -> Unit)? = null
    internal var onSeekTo: ((Long) -> Unit)? = null
    internal var onCaptureFrame: (() -> Unit)? = null
    internal var onSetSpeed: ((Float) -> Unit)? = null

    fun togglePlayPause() = onTogglePlayPause?.invoke()
    fun pause() = onPause?.invoke()
    fun play() = onPlay?.invoke()
    fun toggleMute() = onToggleMute?.invoke()
    fun seekTo(positionMs: Long) {
        currentPositionMs = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L))
        onSeekTo?.invoke(currentPositionMs)
    }
    fun skipBy(deltaMs: Long) {
        seekTo(currentPositionMs + deltaMs)
    }
    fun changePlaybackSpeed(speed: Float) {
        playbackSpeed = speed
        onSetSpeed?.invoke(speed)
    }
    fun captureFrame() = onCaptureFrame?.invoke()
}

/**
 * Streams local or Google Drive videos via Media3 ExoPlayer.
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
    onToggleChrome: () -> Unit,
    onFrameCapturedMessage: (String) -> Unit,
    onZoomStateChanged: (Boolean) -> Unit = {},
    onVideoAspectResolved: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

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
    var retryNonce by remember(item.id) { mutableIntStateOf(0) }
    var exoPlayer by remember(item.id) { mutableStateOf<ExoPlayer?>(null) }

    // Create, bind, and release ExoPlayer strictly inside DisposableEffect (never inside `remember`)
    // so aborted compositions can never leak hardware decoder codecs.
    DisposableEffect(item.id, item.isLocal, item.localContentUri, offlinePath, isActivePage, retryNonce) {
        if (!isActivePage) {
            firstFrameRendered = false
            isPlaying = false
            exoPlayer = null
            return@DisposableEffect onDispose {}
        }

        playbackError = null
        isBuffering = true
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val renderersFactory = VideoFormatSupport.buildRenderersFactory(context)
        val isMp2p = VideoFormatSupport.isMp2pVideo(item.name, item.mimeType) ||
            item.localContentUri?.let { VideoFormatSupport.isMp2pVideo(it, item.mimeType) } == true
        val extractorsFactory = VideoFormatSupport.buildExtractorsFactory(preferProgramStream = isMp2p)
        val hintedMime = VideoFormatSupport.resolveExoPlayerMimeType(item.name, item.mimeType)
            ?: item.localContentUri?.let { VideoFormatSupport.resolveExoPlayerMimeType(it, item.mimeType) }

        fun buildMedia3Item(uri: android.net.Uri): Media3Item =
            Media3Item.Builder()
                .setUri(uri)
                .apply {
                    if (!hintedMime.isNullOrBlank()) {
                        setMimeType(hintedMime)
                    }
                }
                .build()

        val builder = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)

        val playerInstance = when {
            !offlinePath.isNullOrBlank() -> {
                val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
                builder
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                    .apply {
                        setMediaItem(buildMedia3Item(android.net.Uri.fromFile(File(offlinePath))))
                    }
            }
            item.isLocal && !item.localContentUri.isNullOrBlank() -> {
                val mediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
                builder
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                    .apply {
                        setMediaItem(buildMedia3Item(android.net.Uri.parse(item.localContentUri)))
                    }
            }
            else -> {
                val okHttpDataSourceFactory = OkHttpDataSource.Factory(appContainer.okHttpClient)
                val mediaSourceFactory = DefaultMediaSourceFactory(okHttpDataSourceFactory, extractorsFactory)
                builder
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()
                    .apply {
                        setMediaItem(buildMedia3Item(android.net.Uri.parse(DriveConstants.buildFullMediaUrl(item.id))))
                    }
            }
        }.apply {
            repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            volume = if (isMuted) 0f else 1f
            playWhenReady = autoPlay
            prepare()
        }
        exoPlayer = playerInstance

        videoController.activeItemId = item.id
        videoController.durationMs = (playerInstance.duration.takeIf { it > 0L } ?: item.durationMillis ?: 0L)
        videoController.currentPositionMs = playerInstance.currentPosition.coerceAtLeast(0L)
        videoController.isMuted = isMuted
        videoController.playbackSpeed = 1f
        videoController.isPlaying = playerInstance.isPlaying
        videoController.isEnded = false
        videoController.isScrubbing = false

        videoController.onTogglePlayPause = {
            when {
                playerInstance.playbackState == Player.STATE_ENDED -> {
                    playerInstance.seekTo(0L)
                    playerInstance.play()
                }
                playerInstance.isPlaying -> playerInstance.pause()
                else -> playerInstance.play()
            }
        }
        videoController.onPause = { playerInstance.pause() }
        videoController.onPlay = { playerInstance.play() }
        videoController.onToggleMute = {
            val next = !isMuted
            isMuted = next
            playerInstance.volume = if (next) 0f else 1f
            videoController.isMuted = next
        }
        videoController.onSeekTo = { posMs ->
            playerInstance.seekTo(posMs)
        }
        videoController.onSetSpeed = { speed ->
            playerInstance.setPlaybackSpeed(speed)
        }
        videoController.onCaptureFrame = {
            val pv = playerViewRef
            captureCurrentVideoFrame(
                context = context,
                scope = coroutineScope,
                playerView = pv,
                sourceName = item.name,
                onSavedNotify = { appContainer.localMediaRepository.notifyPermissionChanged() },
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
                if (playerInstance.duration > 0L) {
                    videoController.durationMs = playerInstance.duration
                }
                if (isEnded) {
                    videoController.currentPositionMs = videoController.durationMs
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
                isBuffering = false
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onVideoAspectResolved(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                isBuffering = false
                playbackError = error.localizedMessage ?: "Video playback error"
            }
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> playerInstance.pause()
                else -> Unit
            }
        }

        playerInstance.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            playerInstance.removeListener(listener)
            playerInstance.stop()
            playerInstance.release()
            if (exoPlayer === playerInstance) exoPlayer = null
            firstFrameRendered = false
            if (videoController.activeItemId == item.id) {
                videoController.activeItemId = null
                videoController.onTogglePlayPause = null
                videoController.onPause = null
                videoController.onPlay = null
                videoController.onToggleMute = null
                videoController.onSeekTo = null
                videoController.onSetSpeed = null
                videoController.onCaptureFrame = null
                videoController.isPlaying = false
                videoController.isScrubbing = false
            }
        }
    }

    LaunchedEffect(exoPlayer, loopVideo) {
        exoPlayer?.repeatMode = if (loopVideo) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    // Silky 20fps playhead ticker while actively playing & chrome visible (pauses while scrubbing!)
    LaunchedEffect(exoPlayer, isPlaying, chromeVisible, isActivePage) {
        val activePlayer = exoPlayer ?: return@LaunchedEffect
        while (isActivePage && isPlaying && chromeVisible) {
            if (!videoController.isScrubbing) {
                videoController.currentPositionMs = activePlayer.currentPosition.coerceAtLeast(0L)
            }
            if (activePlayer.duration > 0L) videoController.durationMs = activePlayer.duration
            delay(50L)
        }
    }

    var videoZoom by remember(item.id) { mutableFloatStateOf(1f) }
    var videoPan by remember(item.id) { mutableStateOf(Offset.Zero) }
    var videoContainerSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(isActivePage) {
        if (!isActivePage && videoZoom > 1f) {
            videoZoom = 1f
            videoPan = Offset.Zero
            onZoomStateChanged(false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { videoContainerSize = it }
            .pointerInput(item.id, exoPlayer) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { offset ->
                        val activePlayer = exoPlayer
                        if (activePlayer != null) {
                            val isLeft = offset.x < size.width / 2f
                            val delta = if (isLeft) -5_000L else 5_000L
                            val target = (activePlayer.currentPosition + delta)
                                .coerceIn(0L, activePlayer.duration.coerceAtLeast(1L))
                            activePlayer.seekTo(target)
                            videoController.currentPositionMs = target
                            skipFeedbackLeft = isLeft
                            skipFeedbackText = if (isLeft) "-5s" else "+5s"
                        }
                    }
                )
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            val nextZoom = (videoZoom * zoomChange).coerceIn(1f, 4f)
                            val maxX = ((videoContainerSize.width * (nextZoom - 1f)) / 2f).coerceAtLeast(0f)
                            val maxY = ((videoContainerSize.height * (nextZoom - 1f)) / 2f).coerceAtLeast(0f)
                            val nextPan = if (nextZoom <= 1.02f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    x = (videoPan.x + panChange.x).coerceIn(-maxX, maxX),
                                    y = (videoPan.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                            }
                            videoZoom = if (nextZoom <= 1.02f) 1f else nextZoom
                            videoPan = nextPan
                            onZoomStateChanged(videoZoom > 1.02f)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (videoZoom > 1.02f) {
                            val panChange = event.calculatePan()
                            if (panChange != Offset.Zero) {
                                val maxX = ((videoContainerSize.width * (videoZoom - 1f)) / 2f).coerceAtLeast(0f)
                                val maxY = ((videoContainerSize.height * (videoZoom - 1f)) / 2f).coerceAtLeast(0f)
                                videoPan = Offset(
                                    x = (videoPan.x + panChange.x).coerceIn(-maxX, maxX),
                                    y = (videoPan.y + panChange.y).coerceIn(-maxY, maxY)
                                )
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = videoZoom
                scaleY = videoZoom
                translationX = videoPan.x
                translationY = videoPan.y
            },
        contentAlignment = Alignment.Center
    ) {
        val activePlayer = exoPlayer
        if (activePlayer != null) {
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
                        player = activePlayer
                        playerViewRef = this
                    }
                },
                update = { view ->
                    playerViewRef = view
                    if (view.player !== activePlayer) {
                        view.player = activePlayer
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

        // Pinch-to-zoom indicator + 1-tap reset pill
        AnimatedVisibility(
            visible = isActivePage && videoZoom > 1.05f,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 62.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .clickable {
                        videoZoom = 1f
                        videoPan = Offset.Zero
                        onZoomStateChanged(false)
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1f× · Tap to reset", videoZoom),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
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
                containerColor = Color.Black.copy(alpha = 0.82f),
                modifier = Modifier.padding(24.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { retryNonce++ }) {
                            Text("Retry", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    val resolvedUri = when {
                                        item.isLocal && !item.localContentUri.isNullOrBlank() -> {
                                            val rawUri = android.net.Uri.parse(item.localContentUri)
                                            if (rawUri.scheme == "file") {
                                                appContainer.mediaExportManager
                                                    .prepareCleanShareUri(rawUri, item.name, item.mimeType)
                                                    .getOrNull()
                                            } else {
                                                rawUri
                                            }
                                        }
                                        else -> {
                                            appContainer.exportMediaUseCase
                                                .prepareShareUris(listOf(item), stripLocation = false)
                                                .getOrNull()
                                                ?.firstOrNull()
                                        }
                                    }
                                    if (resolvedUri != null) {
                                        val mime = VideoFormatSupport.normalizeVideoMimeType(item.name, item.mimeType)
                                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(resolvedUri, mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            context.startActivity(Intent.createChooser(viewIntent, "Play video with"))
                                        }.onFailure {
                                            onFrameCapturedMessage("No external video player found")
                                        }
                                    } else {
                                        onFrameCapturedMessage("Unable to open video externally")
                                    }
                                }
                            }
                        ) {
                            Text("Open externally", color = Color.White.copy(alpha = 0.9f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Samsung-style Video Frame Capture: grabs the exact current frame from ExoPlayer's TextureView/SurfaceView
 * (works for BOTH local videos and streamed Google Drive videos!) and saves it to Pictures/Lumina.
 */
@OptIn(UnstableApi::class)
private fun captureCurrentVideoFrame(
    context: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    playerView: PlayerView?,
    sourceName: String,
    onSavedNotify: () -> Unit,
    onResult: (String) -> Unit
) {
    val surfaceView = playerView?.videoSurfaceView
    if (surfaceView == null || surfaceView.width <= 0 || surfaceView.height <= 0) {
        onResult("Unable to capture frame right now")
        return
    }

    fun persistBitmap(bmp: Bitmap) {
        scope.launch(Dispatchers.IO) {
            val resolver = context.contentResolver
            var insertedUri: android.net.Uri? = null
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
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Insert failed")
                insertedUri = uri
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)) {
                        error("Compress failed")
                    }
                } ?: error("Output stream unavailable")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
            }.onFailure {
                insertedUri?.let { runCatching { resolver.delete(it, null, null) } }
            }.isSuccess
            bmp.recycle()
            if (saved) onSavedNotify()
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
