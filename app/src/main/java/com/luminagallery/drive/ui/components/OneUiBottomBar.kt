package com.luminagallery.drive.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luminagallery.drive.domain.model.MainTab

/**
 * Authentic Samsung One UI Gallery bottom navigation bar:
 * Clean, flat, full-width pure-black bar with 4 crisp items:
 *   Pictures | Albums | Stories | Menu (≡)
 */
@Composable
fun OneUiBottomNavBar(
    selectedTab: MainTab,
    menuOpen: Boolean,
    onSelectTab: (MainTab) -> Unit,
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier
) {
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SamsungNavItem(
                label = "Pictures",
                selectedIcon = Icons.Rounded.Image,
                unselectedIcon = Icons.Outlined.Image,
                selected = !menuOpen && selectedTab == MainTab.PICTURES,
                onClick = { onSelectTab(MainTab.PICTURES) },
                modifier = Modifier.weight(1f)
            )
            SamsungNavItem(
                label = "Albums",
                selectedIcon = Icons.Rounded.PhotoAlbum,
                unselectedIcon = Icons.Outlined.PhotoAlbum,
                selected = !menuOpen && selectedTab == MainTab.ALBUMS,
                onClick = { onSelectTab(MainTab.ALBUMS) },
                modifier = Modifier.weight(1f)
            )
            SamsungNavItem(
                label = "Stories",
                selectedIcon = Icons.Rounded.Bookmarks,
                unselectedIcon = Icons.Outlined.Bookmarks,
                selected = !menuOpen && selectedTab == MainTab.STORIES,
                onClick = { onSelectTab(MainTab.STORIES) },
                modifier = Modifier.weight(1f)
            )
            SamsungNavItem(
                label = "Menu",
                selectedIcon = Icons.Rounded.Menu,
                unselectedIcon = Icons.Rounded.Menu,
                selected = menuOpen,
                onClick = onMenuClick,
                modifier = Modifier.weight(1f)
            )
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

    val tint by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        label = "navTint"
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navScale"
    )

    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier
                .size(23.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
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
