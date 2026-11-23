package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.AutoAwesomeMosaic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private data class MultiSelectAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun MultiSelectActionBar(
    visible: Boolean,
    selectedCount: Int,
    isExporting: Boolean,
    exportProgressText: String?,
    onShare: () -> Unit,
    onSaveToDevice: (() -> Unit)? = null,
    onDeleteLocal: (() -> Unit)? = null,
    onCompare: (() -> Unit)? = null,
    onMoveCopyLocal: (() -> Unit)? = null,
    onCreateCollagePdf: (() -> Unit)? = null,
    onInfo: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    var overflowExpanded by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            initialOffsetY = { it }
        ) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        val actions = buildList {
            if (selectedCount > 0) {
                add(MultiSelectAction(Icons.Rounded.Share, "Share", onShare))
            }
            if (onMoveCopyLocal != null && selectedCount > 0) {
                add(MultiSelectAction(Icons.AutoMirrored.Rounded.DriveFileMove, "Move or copy to album", onMoveCopyLocal))
            }
            if (onDeleteLocal != null && selectedCount > 0) {
                add(MultiSelectAction(Icons.Rounded.DeleteOutline, "Move to trash", onDeleteLocal))
            }
            if (onSaveToDevice != null && selectedCount > 0) {
                add(MultiSelectAction(Icons.Rounded.Download, "Save to device", onSaveToDevice))
            }
            if (onCompare != null && selectedCount in 2..4) {
                add(MultiSelectAction(Icons.Rounded.Compare, "Compare photos", onCompare))
            }
            if (onCreateCollagePdf != null && selectedCount >= 1) {
                add(MultiSelectAction(Icons.Rounded.AutoAwesomeMosaic, "Create collage or PDF", onCreateCollagePdf))
            }
            if (selectedCount == 1) {
                add(MultiSelectAction(Icons.Rounded.Info, "View info", onInfo))
            }
        }
        val maxInline = if (actions.size <= 4) 4 else 3
        val inlineActions = actions.take(maxInline)
        val overflowActions = actions.drop(maxInline)

        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.95f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    IconButton(
                        onClick = onClearSelection,
                        enabled = !isExporting,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear selection",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                    Column {
                        Text(
                            text = "$selectedCount selected",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!exportProgressText.isNullOrBlank()) {
                            Text(
                                text = exportProgressText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(22.dp),
                        strokeWidth = 2.5.dp
                    )
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(0.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        inlineActions.forEach { action ->
                            ActionIconPill(
                                icon = action.icon,
                                contentDescription = action.label,
                                onClick = action.onClick
                            )
                        }
                        if (overflowActions.isNotEmpty()) {
                            Box {
                                ActionIconPill(
                                    icon = Icons.Rounded.MoreVert,
                                    contentDescription = "More actions",
                                    onClick = { overflowExpanded = true }
                                )
                                DropdownMenu(
                                    expanded = overflowExpanded,
                                    onDismissRequest = { overflowExpanded = false },
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ) {
                                    overflowActions.forEach { action ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = action.label,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = action.icon,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            },
                                            onClick = {
                                                overflowExpanded = false
                                                action.onClick()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionIconPill(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(21.dp)
        )
    }
}
