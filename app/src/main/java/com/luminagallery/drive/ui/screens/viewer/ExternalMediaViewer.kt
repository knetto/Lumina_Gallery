package com.luminagallery.drive.ui.screens.viewer

import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LocalAppContainer
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.repository.WallpaperTarget
import com.luminagallery.drive.domain.model.ExifMetadata
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.MediaType
import com.luminagallery.drive.ui.components.FormatUtils
import com.luminagallery.drive.ui.components.LuminaSnackbarHost
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
    val container = LocalAppContainer.current
    val imageLoader = LocalGalleryImageLoader.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val parsedUri = remember(uriString) { Uri.parse(uriString) }
    val isVideo = remember(mimeType, uriString) {
        mimeType.startsWith("video/", ignoreCase = true) ||
            uriString.endsWith(".mp4", ignoreCase = true) ||
            uriString.endsWith(".mov", ignoreCase = true) ||
            uriString.endsWith(".mkv", ignoreCase = true)
    }
    val displayName = remember(parsedUri) {
        parsedUri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "External Media" } ?: "External Media"
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showWallpaperSheet by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (isVideo) {
            val exoPlayer = remember(uriString) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(Media3Item.fromUri(parsedUri))
                    playWhenReady = true
                    prepare()
                }
            }
            DisposableEffect(exoPlayer) {
                onDispose {
                    exoPlayer.stop()
                    exoPlayer.release()
                }
            }
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        player = exoPlayer
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
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
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.6f
                                }
                            }
                        )
                    }
                    .pointerInput(uriString) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val next = (scale * zoom).coerceIn(1f, 5f)
                            scale = next
                            offset = if (next <= 1.02f) Offset.Zero else offset + pan
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
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
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
        }

        // Bottom Actions Bar (for images)
        if (!isVideo) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
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
