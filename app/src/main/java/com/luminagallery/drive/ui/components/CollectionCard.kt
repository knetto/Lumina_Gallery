package com.luminagallery.drive.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.AlbumCoverPreview
import com.luminagallery.drive.domain.model.AlbumGridItem

private val CollectionCoverShape = RoundedCornerShape(18.dp)

/**
 * Pixel-faithful Samsung One UI Gallery Collection / Group cover:
 *  - Single outer `18.dp` rounded square clipping edge-to-edge thumbnails with crisp `2.dp` black dividers:
 *      • 1 photo  -> full edge-to-edge cover
 *      • 2 photos -> 50/50 left-right vertical split
 *      • 3 photos -> 50% full-height left + two stacked right tiles
 *      • 4+ photos -> clean 2×2 split grid
 *  - Clean collection name directly underneath (no noisy frame or padding clutter).
 */
@Composable
fun CollectionCard(
    collection: AlbumGridItem.CollectionEntry,
    isUnmeteredWifi: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val coverScale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.94f
            isHovered -> 1.025f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 560f),
        label = "collectionCoverScale"
    )

    val originTracker = LocalAlbumTransitionOrigin.current
    val hostView = LocalView.current
    val coverBoundsRef = remember { arrayOf<androidx.compose.ui.geometry.Rect?>(null) }

    val singleCoverMorphInfo = remember(collection.previewCovers, isUnmeteredWifi) {
        val single = collection.previewCovers.singleOrNull()
        if (single == null) {
            null
        } else {
            AlbumCoverMorphInfo(
                isLocal = single.isLocal,
                coverItemId = single.itemId,
                coverThumbnailLink = single.thumbnailLink,
                coverIsVideo = single.isVideo,
                coverSizeBytes = single.sizeBytes,
                isUnmeteredWifi = isUnmeteredWifi
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    originTracker.recordAlbumCover(collection.id, singleCoverMorphInfo)
                    coverBoundsRef[0]?.let { rect ->
                        originTracker.recordBounds(
                            key = collection.id,
                            boundsInRoot = rect,
                            rootWidth = hostView.width.toFloat(),
                            rootHeight = hostView.height.toFloat(),
                            markAsLast = true
                        )
                    }
                    onClick()
                }
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val rect = coords.boundsInRoot()
                    coverBoundsRef[0] = rect
                    originTracker.recordAlbumCover(collection.id, singleCoverMorphInfo)
                    originTracker.recordBounds(
                        key = collection.id,
                        boundsInRoot = rect,
                        rootWidth = hostView.width.toFloat(),
                        rootHeight = hostView.height.toFloat(),
                        markAsLast = false
                    )
                }
                .graphicsLayer {
                    alpha = if (originTracker.activeMorphKey == collection.id) 0f else 1f
                    scaleX = coverScale
                    scaleY = coverScale
                }
        ) {
            CollectionCoverArtwork(
                previewCovers = collection.previewCovers,
                hasDriveChild = collection.hasDriveChild,
                isUnmeteredWifi = isUnmeteredWifi,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected
            )
            OneUiCoverHighlightOverlay(
                isPressed = isPressed,
                isHovered = isHovered,
                shape = RoundedCornerShape(18.dp)
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = collection.name,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        if (collection.totalMediaCount > 0) {
            Text(
                text = collection.totalMediaCount.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
            )
        }
    }
}

/**
 * Pure cover square used both inside `CollectionCard` and for the floating drag ghost overlay.
 */
@Composable
fun CollectionCoverArtwork(
    previewCovers: List<AlbumCoverPreview>,
    hasDriveChild: Boolean,
    isUnmeteredWifi: Boolean,
    modifier: Modifier = Modifier,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false
) {
    val coverShape = CollectionCoverShape
    val dividerColor = MaterialTheme.colorScheme.background
    val gap = 2.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(coverShape)
            .background(dividerColor)
            .border(
                width = if (isSelected) 2.dp else 0.5.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                },
                shape = coverShape
            )
    ) {
        when (previewCovers.size) {
            0 -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            1 -> {
                CollageTile(
                    preview = previewCovers[0],
                    isUnmeteredWifi = isUnmeteredWifi,
                    modifier = Modifier.fillMaxSize()
                )
            }

            2 -> {
                // Exact Samsung Gallery 2-album 50/50 split (like "WhatsApp")
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    CollageTile(
                        preview = previewCovers[0],
                        isUnmeteredWifi = isUnmeteredWifi,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    CollageTile(
                        preview = previewCovers[1],
                        isUnmeteredWifi = isUnmeteredWifi,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            3 -> {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    CollageTile(
                        preview = previewCovers[0],
                        isUnmeteredWifi = isUnmeteredWifi,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        CollageTile(
                            preview = previewCovers[1],
                            isUnmeteredWifi = isUnmeteredWifi,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                        CollageTile(
                            preview = previewCovers[2],
                            isUnmeteredWifi = isUnmeteredWifi,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }
            }

            else -> {
                // Exact Samsung Gallery 2×2 edge-to-edge split (like "Wintersport")
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        CollageTile(
                            preview = previewCovers[0],
                            isUnmeteredWifi = isUnmeteredWifi,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        CollageTile(
                            preview = previewCovers[1],
                            isUnmeteredWifi = isUnmeteredWifi,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(gap)
                    ) {
                        CollageTile(
                            preview = previewCovers[2],
                            isUnmeteredWifi = isUnmeteredWifi,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                        CollageTile(
                            preview = previewCovers[3],
                            isUnmeteredWifi = isUnmeteredWifi,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }

        if (hasDriveChild) {
            GoogleDriveBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                badgeSize = 22.dp
            )
        }

        if (isSelectionMode) {
            SamsungSelectionCheckbox(
                isSelected = isSelected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(7.dp)
            )
        }
    }
}

@Composable
fun SamsungSelectionCheckbox(
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary
                else Color.Black.copy(alpha = 0.34f)
            )
            .border(
                width = if (isSelected) 0.dp else 1.5.dp,
                color = Color.White.copy(alpha = 0.88f),
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

@Composable
private fun CollageTile(
    preview: AlbumCoverPreview,
    isUnmeteredWifi: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current

    val request = remember(preview, isUnmeteredWifi) {
        if (preview.isLocal) {
            val uri = preview.thumbnailLink ?: return@remember null
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(uri, preview.isVideo))
                .size(256)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(preview.itemId))
                .crossfade(false)
                .build()
        } else {
            val url = DriveConstants.buildThumbnailUrl(
                thumbnailLink = preview.thumbnailLink,
                fileId = preview.itemId,
                isUnmeteredWifi = isUnmeteredWifi,
                isVideo = preview.isVideo,
                sizeBytes = preview.sizeBytes
            ) ?: return@remember null
            ImageRequest.Builder(context)
                .data(url)
                .size(256)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(preview.itemId))
                .diskCacheKey(DriveConstants.thumbnailCacheKey(preview.itemId, isUnmeteredWifi))
                .crossfade(80)
                .build()
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        if (request != null) {
            AsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
