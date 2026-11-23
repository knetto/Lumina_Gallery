package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

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
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            initialOffsetY = { it }
        ) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = modifier
    ) {
        GlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.94f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClearSelection, enabled = !isExporting) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Clear selection"
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = "$selectedCount selected",
                            style = MaterialTheme.typography.titleSmall
                        )
                        if (!exportProgressText.isNullOrBlank()) {
                            Text(
                                text = exportProgressText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
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
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (onCompare != null && selectedCount in 2..4) {
                            ActionIconPill(
                                icon = Icons.Rounded.Compare,
                                contentDescription = "Compare photos",
                                onClick = onCompare
                            )
                        }
                        if (onCreateCollagePdf != null && selectedCount >= 1) {
                            ActionIconPill(
                                icon = Icons.Rounded.AutoAwesomeMosaic,
                                contentDescription = "Create collage or PDF",
                                onClick = onCreateCollagePdf
                            )
                        }
                        if (onMoveCopyLocal != null) {
                            ActionIconPill(
                                icon = Icons.AutoMirrored.Rounded.DriveFileMove,
                                contentDescription = "Move or copy to album",
                                onClick = onMoveCopyLocal
                            )
                        }
                        ActionIconPill(
                            icon = Icons.Rounded.Share,
                            contentDescription = "Share",
                            onClick = onShare
                        )
                        if (onSaveToDevice != null) {
                            ActionIconPill(
                                icon = Icons.Rounded.Download,
                                contentDescription = "Save to device",
                                onClick = onSaveToDevice
                            )
                        }
                        if (onDeleteLocal != null) {
                            ActionIconPill(
                                icon = Icons.Rounded.DeleteOutline,
                                contentDescription = "Move to trash",
                                onClick = onDeleteLocal
                            )
                        }
                        if (selectedCount == 1) {
                            ActionIconPill(
                                icon = Icons.Rounded.Info,
                                contentDescription = "View info",
                                onClick = onInfo
                            )
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
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface
        )
    }
}
