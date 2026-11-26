package com.luminagallery.drive.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.DriveThumbnailRequest
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.MediaItem
import kotlinx.coroutines.delay

private val VideoBottomScrimBrush = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
)
private val RawBadgeShape = RoundedCornerShape(5.dp)
private val RawBadgeBg = Color.Black.copy(alpha = 0.58f)
private val TilePressScrimColor = Color.Black.copy(alpha = 0.14f)
private val SelectionUnselectedBg = Color.Black.copy(alpha = 0.28f)
private val SelectionBorderColor = Color.White.copy(alpha = 0.85f)
private val FavoriteRedColor = Color(0xFFFF4D67)
private val FavoriteShadowColor = Color.Black.copy(alpha = 0.42f)
private val SelectionSpringSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)
private val TilePressSpringSpec = spring<Float>(dampingRatio = 0.75f, stiffness = 580f)
private val SelectionAppearSpringSpec = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)
private val FavoriteBadgeSpringSpec = spring<Float>(dampingRatio = 0.42f, stiffness = 480f)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    item: MediaItem,
    isUnmeteredWifi: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    showCloudBadge: Boolean = false,
    isOfflinePinned: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val originTracker = LocalAlbumTransitionOrigin.current
    val hostView = LocalView.current
    val tileBoundsRef = remember { arrayOf<androidx.compose.ui.geometry.Rect?>(null) }

    // Unconditional animation state slots so toggling selection mode never causes positional-slot pops
    val insetPaddingRaw by animateDpAsState(
        targetValue = if (isSelectionMode && isSelected) 7.dp else 0.dp,
        animationSpec = SelectionSpringSpec,
        label = "selectionInset"
    )
    val insetPadding = insetPaddingRaw.coerceAtLeast(0.dp)

    val cornerRadiusRaw by animateDpAsState(
        targetValue = if (isSelectionMode && isSelected) 10.dp else 0.dp,
        animationSpec = SelectionSpringSpec,
        label = "selectionRadius"
    )
    val cornerRadius = cornerRadiusRaw.coerceAtLeast(0.dp)

    var retryAttempt by remember(item.id, item.localContentUri, item.thumbnailLink) {
        mutableIntStateOf(0)
    }
    var pendingRetry by remember(item.id, item.localContentUri, item.thumbnailLink) {
        mutableStateOf(false)
    }
    LaunchedEffect(pendingRetry, retryAttempt) {
        if (pendingRetry && retryAttempt < 2) {
            delay(if (retryAttempt == 0) 240L else 850L)
            pendingRetry = false
            retryAttempt += 1
        }
    }

    val imageRequest = remember(
        item.isLocal,
        item.localContentUri,
        item.id,
        item.thumbnailLink,
        item.isVideo,
        item.sizeBytes,
        isUnmeteredWifi,
        retryAttempt
    ) {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(item.localContentUri, item.isVideo, retryAttempt = retryAttempt))
                .size(360)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .apply {
                    if (retryAttempt == 0) {
                        memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                    }
                }
                .crossfade(false)
                .build()
        } else {
            ImageRequest.Builder(context)
                .data(
                    DriveThumbnailRequest(
                        fileId = item.id,
                        thumbnailLink = item.thumbnailLink,
                        isUnmeteredWifi = isUnmeteredWifi,
                        isVideo = item.isVideo,
                        sizeBytes = item.sizeBytes,
                        requestedMaxPx = 360,
                        retryAttempt = retryAttempt
                    )
                )
                .size(360)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .apply {
                    if (retryAttempt == 0) {
                        memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                    }
                }
                .diskCacheKey(DriveConstants.stableThumbDiskKey(item.id))
                .crossfade(false)
                .build()
        }
    }

    // Selection check springs in / out with selection mode; favourite heart pops when toggled.
    val selectionAppear by animateFloatAsState(
        targetValue = if (isSelectionMode) 1f else 0f,
        animationSpec = SelectionAppearSpringSpec,
        label = "selectionAppear"
    )
    val favoriteAppear by animateFloatAsState(
        targetValue = if (isFavorite) 1f else 0f,
        animationSpec = FavoriteBadgeSpringSpec,
        label = "favoriteBadgeAppear"
    )

    val durationText = remember(item.durationMillis) {
        if (item.isVideo) FormatUtils.formatDuration(item.durationMillis) else null
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.945f else 1f,
        animationSpec = TilePressSpringSpec,
        label = "mediaTilePress"
    )

    val handleClick: () -> Unit = {
        originTracker.recordTappedMediaItem(item)
        tileBoundsRef[0]?.let { rect ->
            originTracker.recordBounds(
                key = "media_${item.id}",
                boundsInRoot = rect,
                rootWidth = hostView.width.toFloat(),
                rootHeight = hostView.height.toFloat(),
                markAsLast = true
            )
        }
        onClick()
    }

    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = handleClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = handleClick
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .onGloballyPositioned { coords ->
                val rect = coords.boundsInRoot()
                tileBoundsRef[0] = rect
                originTracker.recordBounds(
                    key = "media_${item.id}",
                    boundsInRoot = rect,
                    rootWidth = hostView.width.toFloat(),
                    rootHeight = hostView.height.toFloat(),
                    markAsLast = false
                )
            }
            .graphicsLayer {
                alpha = if (originTracker.activeMorphKey == "media_${item.id}") 0f else 1f
                if (pressScale != 1f) {
                    scaleX = pressScale
                    scaleY = pressScale
                }
            }
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .then(clickModifier)
    ) {
        val baseTileMod = if (insetPadding > 0.dp || cornerRadius > 0.dp) {
            Modifier
                .fillMaxSize()
                .padding(insetPadding)
                .clip(RoundedCornerShape(cornerRadius))
        } else {
            Modifier.fillMaxSize()
        }

        // Note: Intentionally avoid Modifier.sharedElement() here because it hides the grid tile
        // behind a black hole during Android Predictive Back scrubbing; instead, LuminaNavGraph
        // runs a 120Hz origin-tracked zoom-bloom directly out of (tileCenterX, tileCenterY)!
        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            onError = {
                if (retryAttempt < 2) {
                    pendingRetry = true
                }
            },
            modifier = baseTileMod.background(MaterialTheme.colorScheme.surfaceContainer)
        )

        if (isPressed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(TilePressScrimColor)
            )
        }

        // Minimal Samsung Gallery-style bottom corner video duration
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(VideoBottomScrimBrush)
                    .padding(horizontal = 5.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    if (!durationText.isNullOrBlank()) {
                        Text(
                            text = durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        } else if (item.isRaw) {
            Text(
                text = "RAW",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RawBadgeShape)
                    .background(RawBadgeBg)
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // Top-right subtle badges: Favorite red heart + offline pin + Google Drive icon badge
        if (favoriteAppear > 0.01f || isOfflinePinned || (showCloudBadge && !item.isLocal)) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (isOfflinePinned && !item.isLocal) {
                    Icon(
                        imageVector = Icons.Rounded.OfflinePin,
                        contentDescription = "Available offline",
                        tint = Color(0xFF69F0AE),
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (showCloudBadge && !item.isLocal) {
                    GoogleDriveBadge(
                        badgeSize = 18.dp
                    )
                }
                if (favoriteAppear > 0.01f) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.graphicsLayer {
                            val s = favoriteAppear.coerceAtLeast(0f)
                            scaleX = s
                            scaleY = s
                            alpha = favoriteAppear.coerceIn(0f, 1f)
                        }
                    ) {
                        // Subtle shadow layer so the heart stays razor-sharp even on pure white photos
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = FavoriteShadowColor,
                            modifier = Modifier
                                .size(15.dp)
                                .offset(y = 0.8.dp)
                        )
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = "Favorite",
                            tint = FavoriteRedColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Circular selection check: springs in with selection mode, draws its tick when picked
        if (selectionAppear > 0.01f) {
            AnimatedSelectionCheck(
                selected = isSelected,
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedFill = SelectionUnselectedBg,
                borderColor = SelectionBorderColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .graphicsLayer {
                        val s = 0.5f + 0.5f * selectionAppear
                        scaleX = s
                        scaleY = s
                        alpha = selectionAppear.coerceIn(0f, 1f)
                    }
            )
        }
    }
}
