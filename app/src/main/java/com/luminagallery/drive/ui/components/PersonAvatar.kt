package com.luminagallery.drive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BeachAccess
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material.icons.rounded.Forest
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.LocationCity
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Pets
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.repository.ai.SmartObjectVocabulary
import java.io.File

/**
 * Crisp Samsung One UI / Apple Photos–style circular portrait avatar rendered directly from
 * the locally cached `160x160` aligned face crop JPEG.
 */
@Composable
fun PersonAvatar(
    cropPath: String?,
    contentDescription: String?,
    size: Dp = 68.dp,
    isSelected: Boolean = false,
    isFavorite: Boolean = false,
    borderColor: Color? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val cropFile = remember(cropPath) {
        cropPath?.takeIf { it.isNotBlank() }?.let { File(it) }?.takeIf { it.exists() }
    }
    val request = remember(cropFile) {
        cropFile?.let { file ->
            ImageRequest.Builder(context)
                .data(file)
                .size(256)
                .crossfade(false)
                .build()
        }
    }

    val ringColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        borderColor != null -> borderColor
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val ringWidth = if (isSelected || borderColor != null) 2.2.dp else 0.8.dp

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(ringWidth, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (request != null) {
                AsyncImage(
                    model = request,
                    imageLoader = imageLoader,
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(size * 0.48f)
                )
            }
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size * 0.32f).coerceIn(18.dp, 24.dp))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(1.5.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (isFavorite) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size((size * 0.28f).coerceIn(16.dp, 21.dp))
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .border(1.2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size((size * 0.17f).coerceIn(10.dp, 13.dp))
                )
            }
        }
    }
}

fun iconForObjectCategory(category: SmartObjectVocabulary.ObjectCategory?): ImageVector = when (category) {
    SmartObjectVocabulary.ObjectCategory.PETS -> Icons.Rounded.Pets
    SmartObjectVocabulary.ObjectCategory.FOOD -> Icons.Rounded.Restaurant
    SmartObjectVocabulary.ObjectCategory.BEACH -> Icons.Rounded.BeachAccess
    SmartObjectVocabulary.ObjectCategory.NATURE -> Icons.Rounded.Forest
    SmartObjectVocabulary.ObjectCategory.VEHICLES -> Icons.Rounded.DirectionsCar
    SmartObjectVocabulary.ObjectCategory.ARCHITECTURE -> Icons.Rounded.LocationCity
    SmartObjectVocabulary.ObjectCategory.FLOWERS -> Icons.Rounded.LocalFlorist
    SmartObjectVocabulary.ObjectCategory.SKY -> Icons.Rounded.WbTwilight
    SmartObjectVocabulary.ObjectCategory.SPORTS -> Icons.Rounded.SportsSoccer
    SmartObjectVocabulary.ObjectCategory.MUSIC -> Icons.Rounded.MusicNote
    SmartObjectVocabulary.ObjectCategory.TRAVEL -> Icons.Rounded.Flight
    SmartObjectVocabulary.ObjectCategory.CELEBRATION -> Icons.Rounded.Celebration
    SmartObjectVocabulary.ObjectCategory.DOCUMENTS -> Icons.Rounded.Description
    SmartObjectVocabulary.ObjectCategory.TECH -> Icons.Rounded.Devices
    null -> Icons.Rounded.AutoAwesome
}
