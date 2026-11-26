package com.luminagallery.drive.ui.screens.stories

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LocalGalleryImageLoader
import com.luminagallery.drive.data.remote.DriveConstants
import com.luminagallery.drive.data.repository.LocalThumbnailRequest
import com.luminagallery.drive.domain.model.GalleryStory
import com.luminagallery.drive.domain.model.MediaItem
import com.luminagallery.drive.domain.model.StoryCategory
import com.luminagallery.drive.ui.components.oneUiClickable
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Samsung One UI Gallery-inspired Stories Tab:
 *  - Featured Hero Story Pager with ambient top glow, NEW pill, editorial title & day count.
 *  - Horizontal "Trips" carousel (geotagged locations).
 *  - Horizontal "Other stories" carousel (monthly memories & video reels).
 *  - Tapping any Story launches a cinematic Ken Burns animated slideshow player!
 */
@Composable
fun StoriesTabContent(
    stories: List<GalleryStory>,
    isUnmeteredWifi: Boolean,
    onToggleFavoriteStory: (GalleryStory) -> Unit,
    onOpenLocations: () -> Unit,
    modifier: Modifier = Modifier
) {
    var activeStory by remember { mutableStateOf<GalleryStory?>(null) }

    val featured = remember(stories) { stories.filter { it.category == StoryCategory.FEATURED } }
    val trips = remember(stories) { stories.filter { it.category == StoryCategory.TRIP } }
    val otherStories = remember(stories) { stories.filter { it.category == StoryCategory.OTHER } }

    if (stories.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(44.dp)
                )
                Text(
                    text = "Stories appear automatically",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "As you capture photos and trips, your best moments are curated here automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // 1. Featured Hero Story Carousel (matching Samsung Gallery Stories!)
        if (featured.isNotEmpty()) {
            item(key = "featured_stories") {
                val pagerState = rememberPagerState(pageCount = { featured.size })
                HorizontalPager(
                    state = pagerState,
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    pageSpacing = 14.dp,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    val story = featured[page]
                    FeaturedStoryHeroCard(
                        story = story,
                        pageIndex = page + 1,
                        totalPages = featured.size,
                        isUnmeteredWifi = isUnmeteredWifi,
                        onClick = { activeStory = story },
                        onToggleFavorite = { onToggleFavoriteStory(story) }
                    )
                }
            }
        }

        // 2. Trips Section ("Trips 13 >")
        if (trips.isNotEmpty()) {
            item(key = "trips_section") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenLocations)
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Trips",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                            Text(
                                text = trips.size.toString(),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.ChevronRight,
                            contentDescription = "All places",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = trips,
                            key = { it.id }
                        ) { story ->
                            CompactStoryCard(
                                story = story,
                                isUnmeteredWifi = isUnmeteredWifi,
                                onClick = { activeStory = story },
                                onToggleFavorite = { onToggleFavoriteStory(story) }
                            )
                        }
                    }
                }
            }
        }

        // 3. Other Stories Section ("Other stories 27")
        if (otherStories.isNotEmpty()) {
            item(key = "other_stories_section") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Other stories",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        Text(
                            text = otherStories.size.toString(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = otherStories,
                            key = { it.id }
                        ) { story ->
                            CompactStoryCard(
                                story = story,
                                isUnmeteredWifi = isUnmeteredWifi,
                                onClick = { activeStory = story },
                                onToggleFavorite = { onToggleFavoriteStory(story) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Full-screen Ken Burns Story Slideshow Player
    activeStory?.let { playingStory ->
        StorySlideshowDialog(
            story = playingStory,
            isUnmeteredWifi = isUnmeteredWifi,
            onToggleFavorite = {
                onToggleFavoriteStory(playingStory)
                activeStory = playingStory.copy(isFavorited = !playingStory.isFavorited)
            },
            onDismiss = { activeStory = null }
        )
    }
}

@Composable
private fun FeaturedStoryHeroCard(
    story: GalleryStory,
    pageIndex: Int,
    totalPages: Int,
    isUnmeteredWifi: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val heartScale by animateFloatAsState(
        targetValue = if (story.isFavorited) 1.18f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "storyHeart"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.92f)
            .oneUiClickable(pressedScale = 0.96f, hoveredScale = 1.015f, onClick = onClick)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        StoryCoverImage(
            item = story.coverItem,
            isUnmeteredWifi = isUnmeteredWifi,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.14f),
                            0.45f to Color.Transparent,
                            0.74f to Color.Black.copy(alpha = 0.52f),
                            1.0f to Color.Black.copy(alpha = 0.88f)
                        )
                    )
                )
        )

        if (story.isNew) {
            Text(
                text = "NEW",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE05226))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = story.title,
                fontFamily = FontFamily.Serif,
                fontSize = 23.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = story.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = if (story.isFavorited) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Favorite story",
                        tint = if (story.isFavorited) Color(0xFFFF5C77) else Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer {
                                scaleX = heartScale
                                scaleY = heartScale
                            }
                    )
                }

                StoryPill(text = "$pageIndex / $totalPages")

                story.daySpanLabel?.let { span ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.HourglassEmpty,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = span,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactStoryCard(
    story: GalleryStory,
    isUnmeteredWifi: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(164.dp)
            .height(178.dp)
            .oneUiClickable(pressedScale = 0.94f, hoveredScale = 1.025f, onClick = onClick)
            .clip(RoundedCornerShape(22.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        StoryCoverImage(
            item = story.coverItem,
            isUnmeteredWifi = isUnmeteredWifi,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color.Black.copy(alpha = 0.2f),
                            0.42f to Color.Transparent,
                            1.0f to Color.Black.copy(alpha = 0.82f)
                        )
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (story.isNew) {
                Text(
                    text = "NEW",
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFEA580C))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Icon(
                imageVector = if (story.isFavorited) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (story.isFavorited) Color(0xFFFF5C77) else Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .clickable(onClick = onToggleFavorite)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = story.title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = story.dateRangeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StoryPill(text: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Black.copy(alpha = 0.45f)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun StoryCoverImage(
    item: MediaItem,
    isUnmeteredWifi: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageLoader = LocalGalleryImageLoader.current
    val request = remember(item.id, item.isLocal, item.isVideo, item.localContentUri, item.thumbnailLink, isUnmeteredWifi) {
        if (item.isLocal && !item.localContentUri.isNullOrBlank()) {
            val data: Any = if (item.isVideo) {
                LocalThumbnailRequest(item.localContentUri, isVideo = true)
            } else {
                item.localContentUri
            }
            ImageRequest.Builder(context)
                .data(data)
                .crossfade(200)
                .build()
        } else {
            ImageRequest.Builder(context)
                .data(
                    com.luminagallery.drive.data.repository.DriveThumbnailRequest(
                        fileId = item.id,
                        thumbnailLink = item.thumbnailLink,
                        isUnmeteredWifi = isUnmeteredWifi,
                        isVideo = item.isVideo,
                        sizeBytes = item.sizeBytes,
                        requestedMaxPx = 440
                    )
                )
                .memoryCacheKey(DriveConstants.stableThumbMemoryKey(item.id))
                .diskCacheKey(DriveConstants.stableThumbDiskKey(item.id))
                .crossfade(160)
                .build()
        }
    }

    AsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = item.name,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

/**
 * Full-screen cinematic Ken Burns animated Story Player:
 *  - Plays all items in the story (no silent 20-item cutoff!)
 *  - Hold anywhere to pause, tap left/right or swipe horizontally to jump slides
 *  - Shows a crisp video badge on video memories
 */
@Composable
fun StorySlideshowDialog(
    story: GalleryStory,
    isUnmeteredWifi: Boolean,
    onToggleFavorite: (() -> Unit)? = null,
    onOpenItem: ((MediaItem) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val slides = remember(story.id, story.items) { story.items }
    if (slides.isEmpty()) {
        LaunchedEffect(story.id) { onDismiss() }
        return
    }

    var currentIndex by remember(story.id) { mutableIntStateOf(0) }
    var isPaused by remember(story.id) { mutableStateOf(false) }
    var isHoldingPause by remember(story.id) { mutableStateOf(false) }
    var slideProgress by remember(story.id) { mutableFloatStateOf(0f) }

    LaunchedEffect(currentIndex, isPaused, isHoldingPause) {
        if (isPaused || isHoldingPause) return@LaunchedEffect
        val steps = 90
        val startStep = (slideProgress * steps).toInt()
        for (step in startStep..steps) {
            slideProgress = step / steps.toFloat()
            delay(45L)
        }
        if (currentIndex < slides.lastIndex) {
            currentIndex++
            slideProgress = 0f
        } else {
            onDismiss()
        }
    }

    val safeIndex = currentIndex.coerceIn(0, slides.lastIndex)
    val activeItem = slides[safeIndex]

    val kenBurnsScale by animateFloatAsState(
        targetValue = 1.0f + slideProgress * 0.09f,
        animationSpec = tween(durationMillis = 80, easing = LinearEasing),
        label = "kenBurns"
    )

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
                .background(Color.Black)
                .pointerInput(slides.size) {
                    detectTapGestures(
                        onPress = {
                            isHoldingPause = true
                            tryAwaitRelease()
                            isHoldingPause = false
                        },
                        onTap = { tapOffset ->
                            if (tapOffset.x < size.width * 0.32f) {
                                if (currentIndex > 0) {
                                    currentIndex--
                                    slideProgress = 0f
                                }
                            } else {
                                if (currentIndex < slides.lastIndex) {
                                    currentIndex++
                                    slideProgress = 0f
                                } else {
                                    onDismiss()
                                }
                            }
                        }
                    )
                }
                .pointerInput(slides.size) {
                    var dragAccum = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            dragAccum = 0f
                            isHoldingPause = true
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            dragAccum += dragAmount
                        },
                        onDragCancel = { isHoldingPause = false },
                        onDragEnd = {
                            isHoldingPause = false
                            if (abs(dragAccum) > 70f) {
                                if (dragAccum < 0f) {
                                    if (currentIndex < slides.lastIndex) {
                                        currentIndex++
                                        slideProgress = 0f
                                    } else {
                                        onDismiss()
                                    }
                                } else if (currentIndex > 0) {
                                    currentIndex--
                                    slideProgress = 0f
                                }
                            }
                        }
                    )
                }
        ) {
            Crossfade(
                targetState = activeItem,
                animationSpec = tween(durationMillis = 550),
                label = "storySlideCrossfade"
            ) { activeSlide ->
                Box(modifier = Modifier.fillMaxSize()) {
                    StoryCoverImage(
                        item = activeSlide,
                        isUnmeteredWifi = isUnmeteredWifi,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = kenBurnsScale
                                scaleY = kenBurnsScale
                            }
                    )
                    if (activeSlide.isVideo) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.Black.copy(alpha = 0.62f),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .then(
                                    if (onOpenItem != null) {
                                        Modifier.clickable {
                                            isPaused = true
                                            onDismiss()
                                            onOpenItem(activeSlide)
                                        }
                                    } else Modifier
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Videocam,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (onOpenItem != null) "Play video" else "Video clip",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }

            // Top progress bar(s) + header
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (slides.size <= 20) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        slides.indices.forEach { idx ->
                            val p = when {
                                idx < safeIndex -> 1f
                                idx == safeIndex -> slideProgress
                                else -> 0f
                            }
                            LinearProgressIndicator(
                                progress = { p },
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.28f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(2.5.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                } else {
                    val overallProgress = ((safeIndex + slideProgress) / slides.size.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { overallProgress },
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.28f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = story.title,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activeItem.fullDateLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }
                    IconButton(onClick = { isPaused = !isPaused }) {
                        com.luminagallery.drive.ui.components.AnimatedPlayPauseIcon(
                            isPlaying = !isPaused,
                            tint = Color.White
                        )
                    }
                    if (onToggleFavorite != null) {
                        IconButton(onClick = onToggleFavorite) {
                            com.luminagallery.drive.ui.components.AnimatedFavoriteIcon(
                                isFavorite = story.isFavorited,
                                activeTint = Color(0xFFFF5C77),
                                animationKey = story.id
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }
            }

            // Bottom caption
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.76f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${safeIndex + 1} of ${slides.size} · ${story.dateRangeLabel}",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
                if (onOpenItem != null) {
                    Text(
                        text = "Open",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = Color.White,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f))
                            .clickable {
                                onDismiss()
                                onOpenItem(activeItem)
                            }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}
