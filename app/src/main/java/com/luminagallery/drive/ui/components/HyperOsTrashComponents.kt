package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.domain.model.MediaItem

enum class TrashConfirmMode {
    MOVE_TO_TRASH,
    RESTORE_FROM_TRASH,
    DELETE_PERMANENTLY
}

private val HyperOsDestructiveRed = Color(0xFFFF453A)

/**
 * Xiaomi HyperOS Gallery-inspired floating rounded bottom confirmation sheet for Trash,
 * Restore, and Permanent Delete:
 *  - Floating rounded card (28.dp corners, inset from edges & above navigation bar)
 *  - Clean thumbnail preview of the photo(s) being acted on + centered typography
 *  - Dual equal-width pill buttons (Cancel | coral-red Move to trash / Delete or accent Restore)
 *  - Optional 1-tap shortcut to Android's Media Management toggle so Android's extra system
 *    popup never interrupts again on Android 12+.
 */
@Composable
fun HyperOsTrashConfirmSheet(
    items: List<MediaItem>,
    mode: TrashConfirmMode = TrashConfirmMode.MOVE_TO_TRASH,
    canSkipSystemPrompt: Boolean = true,
    supportsSkipPromptSetting: Boolean = false,
    onOpenManageMediaSettings: (() -> Unit)? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (items.isEmpty()) return

    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val haptic = LocalHapticFeedback.current
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val cardColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val borderColor = if (isDarkTheme) {
        Color.White.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val cancelBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val confirmBg = when (mode) {
        TrashConfirmMode.RESTORE_FROM_TRASH -> MaterialTheme.colorScheme.primary
        TrashConfirmMode.MOVE_TO_TRASH, TrashConfirmMode.DELETE_PERMANENTLY -> HyperOsDestructiveRed
    }
    val confirmFg = when (mode) {
        TrashConfirmMode.RESTORE_FROM_TRASH -> MaterialTheme.colorScheme.onPrimary
        TrashConfirmMode.MOVE_TO_TRASH, TrashConfirmMode.DELETE_PERMANENTLY -> Color.White
    }

    val count = items.size
    val totalBytes = remember(items) { items.sumOf { it.sizeBytes } }
    val sizeLabel = remember(totalBytes) {
        if (totalBytes > 0L) FormatUtils.formatFileSize(totalBytes) else null
    }

    val title = when (mode) {
        TrashConfirmMode.MOVE_TO_TRASH ->
            if (count == 1) {
                if (items.first().isVideo) "Move video to trash?" else "Move photo to trash?"
            } else {
                "Move $count items to trash?"
            }
        TrashConfirmMode.RESTORE_FROM_TRASH ->
            if (count == 1) "Restore item from trash?" else "Restore $count items?"
        TrashConfirmMode.DELETE_PERMANENTLY ->
            if (count == 1) "Delete permanently?" else "Permanently delete $count items?"
    }

    val subtitle = when (mode) {
        TrashConfirmMode.MOVE_TO_TRASH -> buildString {
            if (sizeLabel != null) append("$sizeLabel · ")
            append("Items in Trash are deleted after 30 days.")
        }
        TrashConfirmMode.RESTORE_FROM_TRASH ->
            "Restored items will return to their original albums."
        TrashConfirmMode.DELETE_PERMANENTLY -> buildString {
            if (sizeLabel != null) append("Frees $sizeLabel · ")
            append("This action cannot be undone.")
        }
    }

    val confirmLabel = when (mode) {
        TrashConfirmMode.MOVE_TO_TRASH -> "Move to trash"
        TrashConfirmMode.RESTORE_FROM_TRASH -> "Restore"
        TrashConfirmMode.DELETE_PERMANENTLY -> "Delete"
    }

    val badgeIcon = when (mode) {
        TrashConfirmMode.MOVE_TO_TRASH -> Icons.Rounded.DeleteOutline
        TrashConfirmMode.RESTORE_FROM_TRASH -> Icons.Rounded.RestoreFromTrash
        TrashConfirmMode.DELETE_PERMANENTLY -> Icons.Rounded.DeleteForever
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.56f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    initialOffsetY = { it / 2 }
                ) + fadeIn(tween(170)),
                exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(tween(130))
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = cardColor,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                        .border(0.75.dp, borderColor, RoundedCornerShape(28.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Thumbnail preview strip (up to 3 rounded previews) or clean icon badge
                        val previewItems = remember(items) {
                            items.filter { !it.localContentUri.isNullOrBlank() || !it.thumbnailLink.isNullOrBlank() }.take(3)
                        }
                        if (previewItems.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                previewItems.forEach { preview ->
                                    val model = preview.localContentUri ?: preview.thumbnailLink
                                    Box(
                                        modifier = Modifier
                                            .size(if (previewItems.size == 1) 56.dp else 48.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(cancelBg)
                                            .border(
                                                0.6.dp,
                                                Color.White.copy(alpha = 0.12f),
                                                RoundedCornerShape(14.dp)
                                            )
                                    ) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(model)
                                                .size(160)
                                                .build(),
                                            imageLoader = imageLoader,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                if (count > 3) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(cancelBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "+${count - 3}",
                                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(confirmBg.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = badgeIcon,
                                    contentDescription = null,
                                    tint = confirmBg,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(6.dp))

                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.5.sp,
                                lineHeight = 19.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        if (supportsSkipPromptSetting && !canSkipSystemPrompt && onOpenManageMediaSettings != null) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable {
                                        onDismiss()
                                        onOpenManageMediaSettings()
                                    }
                            ) {
                                Text(
                                    text = "Skip Android's extra popup · Grant Media Management",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // Signature Xiaomi HyperOS side-by-side equal pill buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(cancelBg)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onDismiss()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "Cancel",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(50.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(confirmBg)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onConfirm()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = confirmLabel,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = confirmFg
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Clean Xiaomi HyperOS-style floating rounded pill toast/snackbar that matches Lumina's AMOLED
 * dark / light theme instead of Material 3's boxy inverted-white snackbar bar.
 */
@Composable
fun LuminaSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pillBg = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f)
    val pillBorder = if (isDark) {
        Color.White.copy(alpha = 0.11f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val textColor = MaterialTheme.colorScheme.onSurface

    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { data ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = pillBg,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .border(0.7.dp, pillBorder, RoundedCornerShape(50))
            ) {
                Row(
                    modifier = Modifier.padding(
                        start = 18.dp,
                        end = if (data.visuals.actionLabel != null) 10.dp else 18.dp,
                        top = 11.dp,
                        bottom = 11.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = data.visuals.message,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = textColor,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    data.visuals.actionLabel?.let { label ->
                        Spacer(Modifier.width(2.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { data.performAction() }
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
