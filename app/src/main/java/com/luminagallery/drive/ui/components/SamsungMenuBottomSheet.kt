package com.luminagallery.drive.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pixel-faithful Samsung One UI Gallery ≡ Menu bottom sheet:
 *  - Top row of 4 circular quick shortcuts: Videos | Favorites | Recent | Locations
 *  - 2×2 wide rounded pill buttons: Google Drive | Clean out / Trash | Settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamsungMenuBottomSheet(
    onDismiss: () -> Unit,
    onVideosClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onRecentClick: () -> Unit,
    onLocationsClick: () -> Unit,
    onGoogleDriveClick: () -> Unit,
    onCleanOutClick: () -> Unit,
    onTrashClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCameraClick: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetSurface = MaterialTheme.colorScheme.surfaceContainerHigh
    val pillSurface = MaterialTheme.colorScheme.surfaceContainerHighest

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = sheetSurface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top 4 Circular Shortcuts (with subtle One UI staggered spring entrance!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                MenuCircleItem(
                    icon = Icons.Outlined.SmartDisplay,
                    label = "Videos",
                    circleColor = pillSurface,
                    staggerDelayMs = 0L,
                    onClick = { onDismiss(); onVideosClick() },
                    modifier = Modifier.weight(1f)
                )
                MenuCircleItem(
                    icon = Icons.Outlined.FavoriteBorder,
                    label = "Favorites",
                    circleColor = pillSurface,
                    staggerDelayMs = 22L,
                    onClick = { onDismiss(); onFavoritesClick() },
                    modifier = Modifier.weight(1f)
                )
                MenuCircleItem(
                    icon = Icons.Outlined.Schedule,
                    label = "Recent",
                    circleColor = pillSurface,
                    staggerDelayMs = 44L,
                    onClick = { onDismiss(); onRecentClick() },
                    modifier = Modifier.weight(1f)
                )
                MenuCircleItem(
                    icon = Icons.Outlined.LocationOn,
                    label = "Locations",
                    circleColor = pillSurface,
                    staggerDelayMs = 66L,
                    onClick = { onDismiss(); onLocationsClick() },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(2.dp))

            // Row 1 Pills: Google Drive | Clean out
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MenuWidePill(
                    icon = Icons.Outlined.Cloud,
                    label = "Google Drive",
                    pillColor = pillSurface,
                    staggerDelayMs = 55L,
                    onClick = { onDismiss(); onGoogleDriveClick() },
                    modifier = Modifier.weight(1f)
                )
                MenuWidePill(
                    icon = Icons.Outlined.CleaningServices,
                    label = "Clean out",
                    pillColor = pillSurface,
                    staggerDelayMs = 80L,
                    onClick = { onDismiss(); onCleanOutClick() },
                    modifier = Modifier.weight(1f)
                )
            }

            // Row 2 Pills: Trash | Settings
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MenuWidePill(
                    icon = Icons.Outlined.Delete,
                    label = "Trash",
                    pillColor = pillSurface,
                    staggerDelayMs = 95L,
                    onClick = { onDismiss(); onTrashClick() },
                    modifier = Modifier.weight(1f)
                )
                MenuWidePill(
                    icon = Icons.Outlined.Settings,
                    label = "Settings",
                    pillColor = pillSurface,
                    staggerDelayMs = 120L,
                    onClick = { onDismiss(); onSettingsClick() },
                    modifier = Modifier.weight(1f)
                )
            }

            if (onCameraClick != null) {
                MenuWidePill(
                    icon = Icons.Outlined.PhotoCamera,
                    label = "Open Camera",
                    pillColor = pillSurface,
                    staggerDelayMs = 135L,
                    onClick = { onDismiss(); onCameraClick() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MenuCircleItem(
    icon: ImageVector,
    label: String,
    circleColor: Color,
    staggerDelayMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enterScale = remember { Animatable(0.88f) }
    val enterAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (staggerDelayMs > 0L) delay(staggerDelayMs)
        launch { enterScale.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 460f)) }
        launch { enterAlpha.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 420f)) }
    }

    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = enterScale.value
                scaleY = enterScale.value
                alpha = enterAlpha.value
            }
            .oneUiClickable(pressedScale = 0.90f, hoveredScale = 1.05f, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(circleColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun MenuWidePill(
    icon: ImageVector,
    label: String,
    pillColor: Color,
    staggerDelayMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enterScale = remember { Animatable(0.92f) }
    val enterAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (staggerDelayMs > 0L) delay(staggerDelayMs)
        launch { enterScale.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 460f)) }
        launch { enterAlpha.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 420f)) }
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = pillColor,
        modifier = modifier
            .height(52.dp)
            .graphicsLayer {
                scaleX = enterScale.value
                scaleY = enterScale.value
                alpha = enterAlpha.value
            }
            .oneUiClickable(pressedScale = 0.94f, hoveredScale = 1.025f, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
