package com.luminagallery.drive.ui.components

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade
import coil3.size.Precision
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.local.LocalMediaRepository
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.AlbumCoverPreview
import com.luminagallery.drive.domain.model.DriveFolder

private val FolderCoverShape = RoundedCornerShape(18.dp)

private val DriveFolder.hasCollageCover: Boolean
    get() = !isLocal && subfolderCount > 0 && previewCovers.size >= 2

/**
 * Samsung One UI Gallery album tile:
 *  - Clean 1:1 `18.dp` rounded cover thumbnail.
 *  - Linked Google Drive albums show the 4-color Google Drive badge in the top-right corner.
 *  - Crisp album title & count neatly below.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    folder: DriveFolder,
    isUnmeteredWifi: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showDriveBadge: Boolean = !folder.isLocal,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    compact: Boolean = false,
    onLongClick: (() -> Unit)? = null
) {
    val countLabel = remember(folder.subfolderCount, folder.mediaCount) {
        when {
            folder.subfolderCount > 0 && folder.mediaCount > 0 ->
                "${folder.subfolderCount} ${if (folder.subfolderCount == 1) "album" else "albums"} · ${folder.mediaCount}"
            folder.subfolderCount > 0 ->
                "${folder.subfolderCount} ${if (folder.subfolderCount == 1) "album" else "albums"}"
            else -> folder.mediaCount.toString()
        }
    }

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
        label = "folderCoverScale"
    )

    val originTracker = LocalAlbumTransitionOrigin.current
    val hostView = LocalView.current
    val coverBoundsRef = remember { arrayOf<androidx.compose.ui.geometry.Rect?>(null) }

    val coverMorphInfo = remember(folder, showDriveBadge, isUnmeteredWifi) {
        val coverId = folder.coverItemId
        when {
            folder.isLocked -> null
            folder.hasCollageCover -> AlbumCoverMorphInfo.Collage(
                previews = folder.previewCovers.take(4),
                showDriveBadge = showDriveBadge,
                isUnmeteredWifi = isUnmeteredWifi
            )
            coverId.isNullOrBlank() -> null
            else -> AlbumCoverMorphInfo.Single(
                preview = AlbumCoverPreview(
                    itemId = coverId,
                    thumbnailLink = folder.coverThumbnailLink,
                    isLocal = folder.isLocal,
                    isVideo = folder.coverIsVideo,
                    sizeBytes = folder.coverSizeBytes
                ),
                isUnmeteredWifi = isUnmeteredWifi
            )
        }
    }
    SideEffect { originTracker.recordAlbumCover(folder.id, coverMorphInfo) }

    val wrappedOnClick = {
        val vw = hostView.width.toFloat()
        val vh = hostView.height.toFloat()
        coverBoundsRef[0]?.let { rect ->
            originTracker.recordBounds(folder.id, rect, vw, vh, markAsLast = true)
        }
        onClick()
    }

    val clickMod = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = wrappedOnClick,
            onLongClick = onLongClick
        )
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = wrappedOnClick
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .hoverable(interactionSource = interactionSource)
            .then(clickMod)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val rect = coords.boundsInRoot()
                    coverBoundsRef[0] = rect
                    originTracker.recordBounds(
                        key = folder.id,
                        boundsInRoot = rect,
                        rootWidth = hostView.width.toFloat(),
                        rootHeight = hostView.height.toFloat(),
                        markAsLast = false
                    )
                }
                .graphicsLayer {
                    alpha = if (originTracker.activeMorphKey == folder.id) 0f else 1f
                    scaleX = coverScale
                    scaleY = coverScale
                }
        ) {
            FolderCoverArtwork(
                folder = folder,
                isUnmeteredWifi = isUnmeteredWifi,
                showDriveBadge = showDriveBadge,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected
            )
            OneUiCoverHighlightOverlay(
                isPressed = isPressed,
                isHovered = isHovered,
                shape = RoundedCornerShape(18.dp)
            )
        }

        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))

        Text(
            text = folder.name,
            style = if (compact) {
                MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 11.5.sp)
            } else {
                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
            },
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )

        Text(
            text = countLabel,
            style = if (compact) {
                MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)
        )
    }
}

/**
 * Pure rounded cover square used inside `FolderCard` AND as the clean photo-only
 * floating drag thumbnail (matching Samsung Gallery's drag aesthetic!).
 */
@Composable
fun FolderCoverArtwork(
    folder: DriveFolder,
    isUnmeteredWifi: Boolean,
    modifier: Modifier = Modifier,
    showDriveBadge: Boolean = !folder.isLocal,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false
) {
    // Locked albums hide their photo cover behind a sleek private frosted lock card
    if (folder.isLocked) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(FolderCoverShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                )
                .border(
                    width = 0.8.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                    shape = FolderCoverShape
                )
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Lock,
                    contentDescription = "Locked album",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (showDriveBadge && !folder.isLocal) {
                GoogleDriveBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    badgeSize = 24.dp
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
        return
    }

    // Google Drive folders containing subfolders get Samsung's exact split-collage album group cover!
    if (folder.hasCollageCover) {
        CollectionCoverArtwork(
            previewCovers = folder.previewCovers,
            hasDriveChild = showDriveBadge,
            isUnmeteredWifi = isUnmeteredWifi,
            modifier = modifier,
            isSelectionMode = isSelectionMode,
            isSelected = isSelected
        )
        return
    }

    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val coverShape = FolderCoverShape

    val coverRequest = remember(
        folder.isLocal,
        folder.coverItemId,
        folder.coverThumbnailLink,
        folder.coverIsVideo,
        folder.coverSizeBytes,
        isUnmeteredWifi
    ) {
        val coverId = folder.coverItemId ?: return@remember null
        if (folder.isLocal) {
            val uriStr = folder.coverThumbnailLink ?: return@remember null
            ImageRequest.Builder(context)
                .data(LocalThumbnailRequest(uriStr, folder.coverIsVideo))
                .size(420)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(coverId))
                .crossfade(false)
                .build()
        } else {
            val url = DriveConstants.buildThumbnailUrl(
                thumbnailLink = folder.coverThumbnailLink,
                fileId = coverId,
                isUnmeteredWifi = isUnmeteredWifi,
                isVideo = folder.coverIsVideo,
                sizeBytes = folder.coverSizeBytes
            ) ?: return@remember null
            ImageRequest.Builder(context)
                .data(url)
                .size(420)
                .precision(Precision.INEXACT)
                .allowRgb565(true)
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(coverId))
                .diskCacheKey(DriveConstants.thumbnailCacheKey(coverId, isUnmeteredWifi))
                .crossfade(100)
                .build()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(coverShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
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
        if (coverRequest != null) {
            AsyncImage(
                model = coverRequest,
                imageLoader = imageLoader,
                contentDescription = folder.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                                MaterialTheme.colorScheme.surfaceContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        if (showDriveBadge && !folder.isLocal) {
            GoogleDriveBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
                badgeSize = 24.dp
            )
        }

        if (folder.id == LocalMediaRepository.LOCAL_BUCKET_VIDEOS) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(7.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Videocam,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        } else if (!folder.isLocal && folder.subfolderCount > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.58f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(11.dp)
                )
                Text(
                    text = folder.subfolderCount.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White
                )
            }
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
