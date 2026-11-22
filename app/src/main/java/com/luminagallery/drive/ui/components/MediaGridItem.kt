package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.domain.model.MediaItem

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    item: MediaItem,
    isUnmeteredWifi: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    showCloudBadge: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current

    val insetPadding by animateDpAsState(
        targetValue = if (isSelected) 7.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "selectionInset"
    )

    val cornerRadius by animateDpAsState(
        targetValue = if (isSelected) 10.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "selectionRadius"
    )

    val imageRequest = remember(item.isLocal, item.localContentUri, item.id, item.thumbnailLink, isUnmeteredWifi) {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            ImageRequest.Builder(context)
                .data(item.localContentUri)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .crossfade(100)
                .build()
        } else {
            val url = DriveConstants.buildThumbnailUrl(
                thumbnailLink = item.thumbnailLink,
                fileId = item.id,
                isUnmeteredWifi = isUnmeteredWifi,
                isVideo = item.isVideo,
                sizeBytes = item.sizeBytes
            ) ?: return@remember null
            ImageRequest.Builder(context)
                .data(url)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .diskCacheKey(DriveConstants.thumbnailCacheKey(item.id, isUnmeteredWifi))
                .crossfade(120)
                .build()
        }
    }

    val durationText = remember(item.durationMillis) {
        if (item.isVideo) FormatUtils.formatDuration(item.durationMillis) else null
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.945f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 580f),
        label = "mediaTilePress"
    )

    val clickModifier = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .background(
                if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .hoverable(interactionSource = interactionSource)
            .then(clickModifier)
    ) {
        val tileModifier = with(sharedTransitionScope) {
            Modifier
                .fillMaxSize()
                .padding(insetPadding)
                .clip(RoundedCornerShape(cornerRadius))
                .sharedElement(
                    sharedContentState = rememberSharedContentState(key = "media_${item.id}"),
                    animatedVisibilityScope = animatedVisibilityScope,
                    boundsTransform = OneUiMotion.SharedPhotoBoundsTransform
                )
        }

        AsyncImage(
            model = imageRequest,
            imageLoader = imageLoader,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = tileModifier.background(MaterialTheme.colorScheme.surfaceContainer)
        )

        OneUiCoverHighlightOverlay(
            isPressed = isPressed,
            isHovered = isHovered,
            shape = RoundedCornerShape(cornerRadius),
            hoverBorderWidth = 1.dp
        )

        // Minimal Samsung Gallery-style bottom corner video duration
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))
                        )
                    )
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
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        // Top-right subtle badges: Favorite red heart + optional Google Drive cloud indicator
        if (isFavorite || (showCloudBadge && !item.isLocal)) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (showCloudBadge && !item.isLocal) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = "Google Drive",
                        tint = Color.White.copy(alpha = 0.88f),
                        modifier = Modifier.size(13.dp)
                    )
                }
                if (isFavorite) {
                    Box(contentAlignment = Alignment.Center) {
                        // Subtle shadow layer so the heart stays razor-sharp even on pure white photos
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = Color.Black.copy(alpha = 0.42f),
                            modifier = Modifier
                                .size(15.dp)
                                .offset(y = 0.8.dp)
                        )
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = "Favorite",
                            tint = Color(0xFFFF4D67),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Samsung One UI circular selection checkmark
        if (isSelectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Black.copy(alpha = 0.28f)
                    )
                    .border(
                        width = if (isSelected) 0.dp else 1.5.dp,
                        color = Color.White.copy(alpha = 0.85f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
