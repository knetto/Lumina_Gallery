package com.luminagallery.drive.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luminagallery.drive.domain.model.MainTab

private val NavBarHorizontalPadding = 8.dp
private val NavPillShape = RoundedCornerShape(18.dp)

/**
 * Gallery bottom navigation bar: Pictures | Albums | Stories | Menu (≡)
 *
 * Motion details (iOS tab bar / HyperOS inspired, intentionally subtle):
 *  - A soft pill glides between tabs on a critically-damped spring.
 *  - The newly selected icon gives a single springy "tap" bounce and cross-fades outline -> filled.
 *  - Pressing a tab compresses it slightly so every touch feels physical.
 */
@Composable
fun OneUiBottomNavBar(
    selectedTab: MainTab,
    menuOpen: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedIndex = when {
        menuOpen -> 3
        selectedTab == MainTab.PICTURES -> 0
        selectedTab == MainTab.ALBUMS -> 1
        else -> 2
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
    ) {
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        )
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = NavBarHorizontalPadding, vertical = 5.dp)
        ) {
            val itemWidth = maxWidth / 4
            val pillWidth = (itemWidth - 14.dp).coerceIn(48.dp, 78.dp)
            val pillOffset by animateDpAsState(
                targetValue = itemWidth * selectedIndex + (itemWidth - pillWidth) / 2,
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 420f),
                label = "navPillOffset"
            )
            Box(
                modifier = Modifier
                    .offset(x = pillOffset)
                    .width(pillWidth)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.075f), NavPillShape)
            )
            Row(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SamsungNavItem(
                    label = "Pictures",
                    selectedIcon = Icons.Rounded.Image,
                    unselectedIcon = Icons.Outlined.Image,
                    selected = selectedIndex == 0,
                    onClick = { onSelectTab(MainTab.PICTURES) },
                    modifier = Modifier.weight(1f)
                )
                SamsungNavItem(
                    label = "Albums",
                    selectedIcon = Icons.Rounded.PhotoLibrary,
                    unselectedIcon = Icons.Outlined.PhotoLibrary,
                    selected = selectedIndex == 1,
                    onClick = { onSelectTab(MainTab.ALBUMS) },
                    modifier = Modifier.weight(1f)
                )
                SamsungNavItem(
                    label = "Stories",
                    selectedIcon = Icons.Rounded.AutoAwesome,
                    unselectedIcon = Icons.Outlined.AutoAwesome,
                    selected = selectedIndex == 2,
                    onClick = { onSelectTab(MainTab.STORIES) },
                    modifier = Modifier.weight(1f)
                )
                SamsungNavItem(
                    label = "Menu",
                    selectedIcon = Icons.AutoMirrored.Rounded.MenuOpen,
                    unselectedIcon = Icons.Rounded.Menu,
                    selected = selectedIndex == 3,
                    onClick = onMenuClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SamsungNavItem(
    label: String,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = MaterialTheme.colorScheme.onBackground
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val tint by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = tween(durationMillis = 220),
        label = "navTint"
    )
    val filled by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "navFilled"
    )
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "navPress"
    )
    // One springy "tap" bounce whenever this tab becomes selected (never on first composition).
    val bounce = remember { Animatable(1f) }
    val wasSelected = remember { booleanArrayOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !wasSelected[0]) {
            bounce.snapTo(0.78f)
            bounce.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
        }
        wasSelected[0] = selected
    }

    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                },
            contentAlignment = Alignment.Center
        ) {
            if (filled < 0.999f) {
                Icon(
                    imageVector = unselectedIcon,
                    contentDescription = if (selected) null else label,
                    tint = tint,
                    modifier = Modifier
                        .size(23.dp)
                        .graphicsLayer { alpha = 1f - filled }
                )
            }
            if (filled > 0.001f) {
                Icon(
                    imageVector = selectedIcon,
                    contentDescription = if (selected) label else null,
                    tint = tint,
                    modifier = Modifier
                        .size(23.dp)
                        .graphicsLayer { alpha = filled }
                )
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
            maxLines = 1
        )
    }
}
