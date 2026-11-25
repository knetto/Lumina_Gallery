package com.luminagallery.drive.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.luminagallery.drive.LuminaApplication
import com.luminagallery.drive.R
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.ui.theme.LuminaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

private enum class ActiveWidgetPicker {
    NONE,
    ALBUMS,
    PHOTOS
}

class PhotoWidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setResult(RESULT_CANCELED)

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val manager = AppWidgetManager.getInstance(this)
        val provider = LuminaPhotoWidgetProvider.providerForComponent(
            this,
            manager.getAppWidgetInfo(widgetId)?.provider
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || provider == null) {
            finish()
            return
        }

        val kind = provider.kind
        val initialSettings = PhotoWidgetSettings.load(this, widgetId, kind)
        val actualWidgetSizeDp = LuminaPhotoWidgetProvider.resolveWidgetSizesDpForKind(
            manager.getAppWidgetOptions(widgetId),
            kind
        ).first()
        val appContainer = (application as? LuminaApplication)?.container

        setContent {
            val prefs by (appContainer?.preferencesManager?.preferencesFlow
                ?.collectAsStateWithLifecycle(initialValue = UserPreferences())
                ?: remember { mutableStateOf(UserPreferences(themeMode = ThemeMode.SYSTEM)) })

            val systemDark = isSystemInDarkTheme()
            val useDark = when (prefs.themeMode) {
                ThemeMode.AMOLED_BLACK, ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> systemDark
            }
            LaunchedEffect(useDark) {
                // Status bar sits over the dark ambient preview header, nav bar follows app theme
                val navStyle = if (useDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                    navigationBarStyle = navStyle
                )
            }

            LuminaTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                WidgetSettingsRoot(
                    widgetId = widgetId,
                    kind = kind,
                    actualWidgetSizeDp = actualWidgetSizeDp,
                    initialSettings = initialSettings,
                    onCancel = { finish() },
                    onSave = { updated, onResult ->
                        val sourceChanged = updated.source != initialSettings.source ||
                            updated.selectedAlbumIds != initialSettings.selectedAlbumIds ||
                            updated.selectedPhotoIds != initialSettings.selectedPhotoIds ||
                            updated.excludeScreenshots != initialSettings.excludeScreenshots ||
                            updated.avoidSimilarPhotos != initialSettings.avoidSimilarPhotos ||
                            updated.memorySource != initialSettings.memorySource ||
                            updated.groupByAlbum != initialSettings.groupByAlbum
                        lifecycleScope.launch {
                            try {
                                updated.save(applicationContext, widgetId, resetState = sourceChanged)
                                WidgetRefreshScheduler.syncSchedule(applicationContext)
                                provider.renderWidgets(
                                    applicationContext,
                                    manager,
                                    intArrayOf(widgetId),
                                    advance = false
                                )
                                setResult(
                                    RESULT_OK,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                                )
                                onResult(true)
                                finish()
                            } catch (_: RuntimeException) {
                                onResult(false)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun WidgetSettingsRoot(
    widgetId: Int,
    kind: PhotoWidgetKind,
    actualWidgetSizeDp: SizeF,
    initialSettings: PhotoWidgetSettings,
    onCancel: () -> Unit,
    onSave: (PhotoWidgetSettings, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var settings by remember { mutableStateOf(initialSettings) }
    var activePicker by remember { mutableStateOf(ActiveWidgetPicker.NONE) }
    var previewShuffleTick by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    val albums by produceState<List<WidgetAlbumOption>>(initialValue = emptyList()) {
        value = WidgetMediaRepository.loadPickerAlbums(context)
    }
    val albumById = remember(albums) { albums.associateBy { it.id } }
    val albumNameById = remember(albums) { albums.associate { it.id to it.name } }

    // Inline thumbnails when user has picked specific photos
    val pickedPhotoPreviews by produceState<List<WidgetPhoto>>(
        initialValue = emptyList(),
        key1 = settings.selectedPhotoIds
    ) {
        value = if (settings.selectedPhotoIds.isEmpty()) emptyList()
        else WidgetMediaRepository.loadPhotosByIds(context, settings.selectedPhotoIds.take(12))
    }

    BackHandler(enabled = activePicker != ActiveWidgetPicker.NONE) {
        activePicker = ActiveWidgetPicker.NONE
    }

    when (activePicker) {
        ActiveWidgetPicker.ALBUMS -> {
            AlbumMultiPickerScreen(
                albums = albums,
                selectedIds = settings.selectedAlbumIds.toSet(),
                onDone = { picked ->
                    settings = settings.copy(
                        source = WidgetPhotoSource.SELECTED_ALBUMS,
                        selectedAlbumIds = picked
                    )
                    activePicker = ActiveWidgetPicker.NONE
                },
                onBack = { activePicker = ActiveWidgetPicker.NONE }
            )
            return
        }

        ActiveWidgetPicker.PHOTOS -> {
            PhotoMultiPickerScreen(
                albums = albums,
                initialSelectedIds = settings.selectedPhotoIds,
                onDone = { pickedIds ->
                    settings = settings.copy(
                        source = WidgetPhotoSource.SELECTED_PHOTOS,
                        selectedPhotoIds = pickedIds
                    )
                    activePicker = ActiveWidgetPicker.NONE
                },
                onBack = { activePicker = ActiveWidgetPicker.NONE }
            )
            return
        }

        ActiveWidgetPicker.NONE -> Unit
    }

    val isUsingOriginalSource = remember(settings, initialSettings) {
        settings.source == initialSettings.source &&
            settings.selectedAlbumIds == initialSettings.selectedAlbumIds &&
            settings.selectedPhotoIds == initialSettings.selectedPhotoIds &&
            settings.excludeScreenshots == initialSettings.excludeScreenshots &&
            settings.avoidSimilarPhotos == initialSettings.avoidSimilarPhotos &&
            settings.memorySource == initialSettings.memorySource &&
            settings.groupByAlbum == initialSettings.groupByAlbum
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                ) {
                    if (saveFailed) {
                        Text(
                            text = stringResource(R.string.widget_save_failed),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .weight(0.9f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .clickable(enabled = !saving, onClick = onCancel)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(android.R.string.cancel),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        Button(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                saving = true
                                saveFailed = false
                                onSave(settings) { ok ->
                                    saving = false
                                    if (!ok) saveFailed = true
                                }
                            },
                            enabled = !saving,
                            shape = RoundedCornerShape(18.dp),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp),
                            modifier = Modifier
                                .weight(1.6f)
                                .height(50.dp)
                        ) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.widget_save),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = innerPadding.calculateBottomPadding())
        ) {
            // PINNED LIVE PREVIEW STAGE — stays visible as you tweak every toggle below!
            PinnedWidgetPreviewHeader(
                widgetId = widgetId,
                kind = kind,
                actualWidgetSizeDp = actualWidgetSizeDp,
                settings = settings,
                usePlacedWidgetPhotos = isUsingOriginalSource && previewShuffleTick == 0,
                previewShuffleTick = previewShuffleTick,
                onClose = onCancel,
                onPreviewShuffle = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    previewShuffleTick++
                }
            )

            // SCROLLABLE SETTINGS SHEET
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. XIAOMI HYPEROS GLASS STYLE (right below preview for instant visual tweaking!)
                ModernSettingsCard(
                    title = "Glass appearance",
                    icon = Icons.Rounded.AutoAwesome
                ) {
                    Text(
                        text = "Border effect",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    FrameStyleGrid(
                        selected = settings.frameStyle,
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            settings = settings.copy(frameStyle = it)
                        }
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Corner curvature",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    SegmentedPillRow(
                        options = WidgetCornerStyle.entries,
                        selected = settings.cornerStyle,
                        labelOf = {
                            when (it) {
                                WidgetCornerStyle.HYPER_OS -> "HyperOS"
                                WidgetCornerStyle.SOFT -> "Soft"
                                WidgetCornerStyle.COMPACT -> "Compact"
                            }
                        },
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            settings = settings.copy(cornerStyle = it)
                        }
                    )

                    if (kind == PhotoWidgetKind.COLLAGE || kind == PhotoWidgetKind.MEMORIES) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = "Collage layout",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        CollageLayoutGrid(
                            selected = settings.collageLayout,
                            onSelect = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                settings = settings.copy(collageLayout = it)
                            }
                        )
                    }
                }

                // 2. PHOTO SOURCE SECTION
                ModernSettingsCard(
                    title = "Photo source",
                    icon = Icons.Rounded.PhotoLibrary
                ) {
                    val sourceOptions = sourceOptionsForKind(kind)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        sourceOptions.forEach { src ->
                            val selected = settings.source == src
                            SourceOptionRow(
                                icon = sourceIcon(src),
                                title = sourceLabel(src),
                                subtitle = sourceDescription(src, settings, albumNameById),
                                selected = selected,
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    settings = settings.copy(source = src)
                                    if (src == WidgetPhotoSource.SELECTED_ALBUMS && settings.selectedAlbumIds.isEmpty()) {
                                        activePicker = ActiveWidgetPicker.ALBUMS
                                    } else if (src == WidgetPhotoSource.SELECTED_PHOTOS && settings.selectedPhotoIds.isEmpty()) {
                                        activePicker = ActiveWidgetPicker.PHOTOS
                                    }
                                }
                            )
                        }
                    }

                    // Inline visual strip for Selected Photos
                    AnimatedVisibility(visible = settings.source == WidgetPhotoSource.SELECTED_PHOTOS) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            if (pickedPhotoPreviews.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(bottom = 10.dp)
                                ) {
                                    items(pickedPhotoPreviews, key = { it.luminaItemId }) { item ->
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(item.uri)
                                                .size(160)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = item.albumName,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(RoundedCornerShape(14.dp))
                                                .border(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                                    RoundedCornerShape(14.dp)
                                                )
                                        )
                                    }
                                }
                            }
                            TonalActionButton(
                                icon = Icons.Rounded.Edit,
                                label = if (settings.selectedPhotoIds.isEmpty()) "Pick photos from gallery"
                                else "Change picked photos (${settings.selectedPhotoIds.size})",
                                onClick = { activePicker = ActiveWidgetPicker.PHOTOS }
                            )
                        }
                    }

                    // Inline album strip for Selected Albums
                    AnimatedVisibility(visible = settings.source == WidgetPhotoSource.SELECTED_ALBUMS) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            if (settings.selectedAlbumIds.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(bottom = 10.dp)
                                ) {
                                    items(settings.selectedAlbumIds, key = { it }) { albumId ->
                                        val opt = albumById[albumId]
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                if (opt?.coverUri != null) {
                                                    AsyncImage(
                                                        model = ImageRequest.Builder(context)
                                                            .data(opt.coverUri)
                                                            .size(100)
                                                            .build(),
                                                        contentDescription = opt.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                                    )
                                                }
                                                Text(
                                                    text = opt?.name ?: "Album",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            TonalActionButton(
                                icon = Icons.Rounded.Folder,
                                label = if (settings.selectedAlbumIds.isEmpty()) "Choose one or more albums"
                                else "Edit albums (${settings.selectedAlbumIds.size} selected)",
                                onClick = { activePicker = ActiveWidgetPicker.ALBUMS }
                            )
                        }
                    }

                    AnimatedVisibility(visible = settings.source == WidgetPhotoSource.MEMORIES_MIX) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Text(
                                text = "Include memory types",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                            SegmentedPillRow(
                                options = WidgetMemorySource.entries,
                                selected = settings.memorySource,
                                labelOf = {
                                    when (it) {
                                        WidgetMemorySource.BOTH -> "Both"
                                        WidgetMemorySource.TRIPS -> "Trips"
                                        WidgetMemorySource.DAYS -> "Days"
                                    }
                                },
                                onSelect = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    settings = settings.copy(memorySource = it)
                                }
                            )
                        }
                    }
                }

                // 3. SHUFFLE & TIMING SECTION
                ModernSettingsCard(
                    title = "Shuffle & timing",
                    icon = Icons.Rounded.Schedule
                ) {
                    Text(
                        text = "Auto-change interval",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    IntervalGridSelector(
                        selected = settings.shuffleInterval,
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            settings = settings.copy(shuffleInterval = it)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when {
                            settings.shuffleInterval == WidgetShuffleInterval.MANUAL ->
                                "Manual only — stays on the current photo until you shuffle it."
                            settings.shuffleInterval.isFastSlideshow ->
                                "Live slideshow cross-fades while visible, refreshing with a new batch every 30 min."
                            else ->
                                "Automatically updates in the background every ${intervalLabel(settings.shuffleInterval).lowercase()}."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val showShuffleToggle = settings.source != WidgetPhotoSource.ALL_PHONE_RANDOM
                    val showGroupToggle = kind.isMultiPhoto && settings.source == WidgetPhotoSource.SELECTED_ALBUMS
                    val showDiversityToggle = kind.isMultiPhoto
                    val showScreenshotToggle = settings.source != WidgetPhotoSource.SELECTED_PHOTOS

                    if (showShuffleToggle || showGroupToggle || showDiversityToggle || showScreenshotToggle) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        )
                    }

                    if (showShuffleToggle) {
                        WidgetToggleRow(
                            title = "Shuffle randomly",
                            subtitle = "Randomize order instead of newest / picked order",
                            checked = settings.shuffleOrder,
                            onCheckedChange = { settings = settings.copy(shuffleOrder = it) }
                        )
                    }
                    if (showDiversityToggle) {
                        if (showShuffleToggle) SubtleRowDivider()
                        WidgetToggleRow(
                            title = "Avoid similar photos",
                            subtitle = "Skip burst retakes & look-alike shots so collages show different moments",
                            checked = settings.avoidSimilarPhotos,
                            onCheckedChange = { settings = settings.copy(avoidSimilarPhotos = it) }
                        )
                    }
                    if (showGroupToggle) {
                        if (showShuffleToggle || showDiversityToggle) SubtleRowDivider()
                        WidgetToggleRow(
                            title = "Group collage by album",
                            subtitle = "Keep each collage focused on one chosen album",
                            checked = settings.groupByAlbum,
                            onCheckedChange = { settings = settings.copy(groupByAlbum = it) }
                        )
                    }
                    if (showScreenshotToggle) {
                        if (showShuffleToggle || showDiversityToggle || showGroupToggle) SubtleRowDivider()
                        WidgetToggleRow(
                            title = "Exclude screenshots",
                            subtitle = "Skip the Screenshots folder when picking photos",
                            checked = settings.excludeScreenshots,
                            onCheckedChange = { settings = settings.copy(excludeScreenshots = it) }
                        )
                    }
                }

                // 4. OVERLAYS & INTERACTION TOGGLES
                ModernSettingsCard(
                    title = "Overlays & controls",
                    icon = Icons.Rounded.Tune
                ) {
                    WidgetToggleRow(
                        title = "Show caption label",
                        subtitle = "Display location, day, memory or album title",
                        checked = settings.showCaption,
                        onCheckedChange = { settings = settings.copy(showCaption = it) }
                    )

                    AnimatedVisibility(visible = settings.showCaption) {
                        Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
                            SegmentedPillRow(
                                options = WidgetCaptionStyle.entries,
                                selected = settings.captionStyle,
                                labelOf = {
                                    when (it) {
                                        WidgetCaptionStyle.GLASS_PILL -> "Frosted Pill"
                                        WidgetCaptionStyle.EDITORIAL_FADE -> "Editorial Fade"
                                    }
                                },
                                onSelect = {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    settings = settings.copy(captionStyle = it)
                                }
                            )
                        }
                    }

                    SubtleRowDivider()
                    WidgetToggleRow(
                        title = "Show live date badge",
                        subtitle = "Floating frosted glass weekday & day card",
                        checked = settings.showDateBadge,
                        onCheckedChange = { settings = settings.copy(showDateBadge = it) }
                    )

                    SubtleRowDivider()
                    WidgetToggleRow(
                        title = "Quick shuffle button",
                        subtitle = "Subtle translucent shuffle icon in the bottom-right",
                        checked = settings.showShuffleButton,
                        onCheckedChange = { settings = settings.copy(showShuffleButton = it) }
                    )

                    SubtleRowDivider()
                    WidgetToggleRow(
                        title = "Quick settings button",
                        subtitle = "Subtle gear icon in the top-left corner",
                        checked = settings.showSettingsButton,
                        onCheckedChange = { settings = settings.copy(showSettingsButton = it) }
                    )

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                    )

                    Text(
                        text = "When tapping a photo",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    SegmentedPillRow(
                        options = WidgetTapAction.entries,
                        selected = settings.tapAction,
                        labelOf = {
                            when (it) {
                                WidgetTapAction.OPEN_PHOTO -> "Open photo"
                                WidgetTapAction.SHUFFLE_NEXT -> "Shuffle"
                                WidgetTapAction.OPEN_SETTINGS -> "Settings"
                            }
                        },
                        onSelect = {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            settings = settings.copy(tapAction = it)
                        }
                    )
                }

                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

// =====================================================================
// PINNED EXACT-SIZE & EXACT-PHOTO HYPEROS LIVE PREVIEW HEADER
// =====================================================================

private data class PreviewSourceKey(
    val widgetId: Int,
    val kind: PhotoWidgetKind,
    val source: WidgetPhotoSource,
    val selectedAlbumIds: List<String>,
    val selectedPhotoIds: List<String>,
    val excludeScreenshots: Boolean,
    val avoidSimilarPhotos: Boolean,
    val memorySource: WidgetMemorySource,
    val groupByAlbum: Boolean,
    val usePlacedWidgetPhotos: Boolean,
    val shuffleTick: Int
)

private data class PreviewPhotoSample(
    val bitmaps: List<Bitmap>,
    val caption: WidgetCaptionData
)

@Composable
private fun PinnedWidgetPreviewHeader(
    widgetId: Int,
    kind: PhotoWidgetKind,
    actualWidgetSizeDp: SizeF,
    settings: PhotoWidgetSettings,
    usePlacedWidgetPhotos: Boolean,
    previewShuffleTick: Int,
    onClose: () -> Unit,
    onPreviewShuffle: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density

    val realW = actualWidgetSizeDp.width.coerceAtLeast(64f)
    val realH = actualWidgetSizeDp.height.coerceAtLeast(64f)

    val sourceKey = remember(
        widgetId,
        kind,
        settings.source,
        settings.selectedAlbumIds,
        settings.selectedPhotoIds,
        settings.excludeScreenshots,
        settings.avoidSimilarPhotos,
        settings.memorySource,
        settings.groupByAlbum,
        usePlacedWidgetPhotos,
        previewShuffleTick
    ) {
        PreviewSourceKey(
            widgetId = widgetId,
            kind = kind,
            source = settings.source,
            selectedAlbumIds = settings.selectedAlbumIds,
            selectedPhotoIds = settings.selectedPhotoIds,
            excludeScreenshots = settings.excludeScreenshots,
            avoidSimilarPhotos = settings.avoidSimilarPhotos,
            memorySource = settings.memorySource,
            groupByAlbum = settings.groupByAlbum,
            usePlacedWidgetPhotos = usePlacedWidgetPhotos,
            shuffleTick = previewShuffleTick
        )
    }

    val sample by produceState<PreviewPhotoSample?>(
        initialValue = null,
        key1 = sourceKey
    ) {
        value = withContext(Dispatchers.IO) {
            loadPreviewSample(
                context = context,
                widgetId = widgetId,
                kind = kind,
                settings = settings,
                usePlacedWidgetPhotos = usePlacedWidgetPhotos,
                advanceRandom = previewShuffleTick > 0
            )
        }
    }

    // Render using the EXACT placed widget dimensions & aspect ratio so tile geometry + corner proportions match 1:1
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = sample,
        key2 = settings.frameStyle,
        key3 = Triple(
            settings.cornerStyle,
            settings.collageLayout,
            Triple(
                settings.showCaption,
                settings.captionStyle,
                Triple(settings.showDateBadge, settings.showShuffleButton, settings.showSettingsButton)
            )
        )
    ) {
        val loaded = sample ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val maxRenderEdge = 720f
            val baseScale = density.coerceIn(1.5f, 3f)
            val rawW = realW * baseScale
            val rawH = realH * baseScale
            val clampFactor = min(1f, maxRenderEdge / maxOf(rawW, rawH))
            val renderW = (rawW * clampFactor).roundToInt().coerceAtLeast(96)
            val renderH = (rawH * clampFactor).roundToInt().coerceAtLeast(96)
            val effDensity = (baseScale * clampFactor).coerceAtLeast(0.85f)

            val (pattern, orderedBitmaps) = HyperOsGlassRenderer.resolveSmartPatternAndOrder(
                kind = kind,
                collageLayout = settings.collageLayout,
                widthDp = realW,
                heightDp = realH,
                photos = loaded.bitmaps,
                aspectRatioOf = { bmp -> (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.35f, 3.0f) },
                varietySeed = (widgetId + previewShuffleTick) and 0x7FFFFFFF
            )
            HyperOsGlassRenderer.renderWidgetBitmap(
                photos = orderedBitmaps,
                pattern = pattern,
                widthPx = renderW,
                heightPx = renderH,
                density = effDensity,
                settings = settings,
                caption = loaded.caption,
                date = LocalDate.now(),
                reserveBottomRightButton = settings.showShuffleButton,
                reserveTopLeftButton = settings.showSettingsButton
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF19272C),
                        Color(0xFF1F2F2E),
                        Color(0xFF12181E)
                    )
                )
            )
    ) {
        // Soft HyperOS ambient blurred wallpaper bokeh behind the live preview
        Canvas(modifier = Modifier.matchParentSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x38589E9A), Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.25f),
                    radius = size.width * 0.52f
                ),
                center = Offset(size.width * 0.18f, size.height * 0.25f),
                radius = size.width * 0.52f
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0x3347709C), Color.Transparent),
                    center = Offset(size.width * 0.85f, size.height * 0.72f),
                    radius = size.width * 0.48f
                ),
                center = Offset(size.width * 0.85f, size.height * 0.72f),
                radius = size.width * 0.48f
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Compact frosted top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x36FFFFFF))
                        .border(0.75.dp, Color(0x40FFFFFF), CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(android.R.string.cancel),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = widgetKindTitle(kind),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${realW.roundToInt()} × ${realH.roundToInt()} dp · Live preview",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xBFD8E6F2),
                        maxLines = 1
                    )
                }

                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x36FFFFFF))
                        .border(0.75.dp, Color(0x40FFFFFF), CircleShape)
                        .clickable(onClick = onPreviewShuffle),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = "Preview another photo",
                        tint = Color.White,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }

            // Exact-proportion preview widget box
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                val maxBoxW = (maxWidth.value).coerceAtMost(332f)
                val maxBoxH = 206f
                // Fit real widget dimensions inside the preview stage without distorting aspect ratio
                val displayScale = min(1f, min(maxBoxW / realW, maxBoxH / realH))
                val displayW = (realW * displayScale).coerceAtLeast(72f)
                val displayH = (realH * displayScale).coerceAtLeast(72f)
                val buttonSize = (28f * displayScale).coerceIn(20f, 28f)
                val buttonMargin = (10f * displayScale).coerceIn(6f, 10f)

                Box(
                    modifier = Modifier
                        .width(displayW.dp)
                        .height(displayH.dp)
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape((settings.cornerStyle.radiusDp * displayScale).dp),
                            ambientColor = Color(0x66000000),
                            spotColor = Color(0x88000000)
                        )
                ) {
                    val bmp = previewBitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Live widget preview",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    if (settings.showSettingsButton) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(buttonMargin.dp)
                                .size(buttonSize.dp)
                                .clip(CircleShape)
                                .background(Color(0x66121A26))
                                .border(0.8.dp, Color(0x4AFFFFFF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size((buttonSize * 0.54f).dp)
                            )
                        }
                    }
                    if (settings.showShuffleButton) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(buttonMargin.dp)
                                .size(buttonSize.dp)
                                .clip(CircleShape)
                                .background(Color(0x66121A26))
                                .border(0.8.dp, Color(0x4AFFFFFF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size((buttonSize * 0.54f).dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadPreviewSample(
    context: Context,
    widgetId: Int,
    kind: PhotoWidgetKind,
    settings: PhotoWidgetSettings,
    usePlacedWidgetPhotos: Boolean,
    advanceRandom: Boolean
): PreviewPhotoSample {
    val maxSlots = when (kind) {
        PhotoWidgetKind.COLLAGE, PhotoWidgetKind.MEMORIES -> 6
        PhotoWidgetKind.STRIP -> 4
        else -> 1
    }

    // 1. If editing an already-placed widget with its unchanged source, load the EXACT photos currently shown on it!
    if (usePlacedWidgetPhotos && widgetId >= 0) {
        val prefs = context.getSharedPreferences(PHOTO_WIDGET_PREFS, Context.MODE_PRIVATE)
        val savedIdsRaw = prefs.getString("shown_ids_$widgetId", null)
        val savedIds = savedIdsRaw?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        if (savedIds.isNotEmpty()) {
            val exactPhotos = WidgetMediaRepository.loadPhotosByIds(context, savedIds.take(maxSlots))
            val exactBitmaps = exactPhotos.mapNotNull { decodePreviewUri(context, Uri.parse(it.uri), 480) }
            if (exactBitmaps.isNotEmpty()) {
                val savedTitle = prefs.getString("shown_title_$widgetId", null)
                val savedSub = prefs.getString("shown_sub_$widgetId", null)
                val caption = if (!savedTitle.isNullOrBlank()) {
                    WidgetCaptionData(savedTitle, savedSub)
                } else {
                    val first = exactPhotos.first()
                    WidgetCaptionData(
                        title = first.albumName.ifBlank { "Photos" },
                        subtitle = widgetDateLabel(first.takenAtMs).takeIf { it.isNotBlank() }
                    )
                }
                return PreviewPhotoSample(exactBitmaps, caption)
            }
        }
    }

    // 2. Otherwise resolve live sample photos matching the newly selected source / shuffle preview
    val resolved = runCatching {
        WidgetMediaRepository.resolveWidgetFrames(
            context = context,
            widgetId = -1,
            kind = kind,
            settings = settings,
            slotsPerFrame = maxSlots,
            frameCount = 1,
            advance = advanceRandom
        ).firstOrNull()
    }.getOrNull()

    val decoded = resolved?.photos.orEmpty().mapNotNull { photo ->
        decodePreviewUri(context, Uri.parse(photo.uri), 480)
    }.ifEmpty {
        val artIds = listOf(
            R.drawable.widget_art_sunset_peaks,
            R.drawable.widget_art_ocean_waves,
            R.drawable.widget_art_golden_meadow,
            R.drawable.widget_art_aurora_pine
        )
        artIds.take(maxSlots).mapNotNull { resId -> renderDrawableToBitmap(context, resId, 320, 320) }
    }

    val caption = resolved?.caption ?: when (kind) {
        PhotoWidgetKind.COLLAGE -> WidgetCaptionData("Amalfi Coast", "Location group")
        PhotoWidgetKind.MEMORIES -> WidgetCaptionData("1 year ago", "On This Day")
        PhotoWidgetKind.FAVORITES -> WidgetCaptionData("Favorites", "Curated picks")
        else -> WidgetCaptionData("Summer Trip", "24 photos")
    }
    return PreviewPhotoSample(decoded, caption)
}

private fun decodePreviewUri(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val maxDim = maxOf(info.size.width, info.size.height)
            if (maxDim > maxEdge) {
                val scale = maxEdge.toFloat() / maxDim
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
        }
    } else {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}.getOrNull()

private fun renderDrawableToBitmap(context: Context, drawableRes: Int, width: Int, height: Int): Bitmap? {
    val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, width, height)
    drawable.draw(canvas)
    return bmp
}

// =====================================================================
// VISUAL SETTINGS CONTROLS (STYLE CARDS, LAYOUT DIAGRAMS, SEGMENTED PILLS)
// =====================================================================

@Composable
private fun ModernSettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(bottom = 14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun FrameStyleGrid(
    selected: WidgetFrameStyle,
    onSelect: (WidgetFrameStyle) -> Unit
) {
    val rows = WidgetFrameStyle.entries.chunked(2)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { style ->
                    val isSelected = style == selected
                    val bg by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        label = "frame_style_bg"
                    )
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = bg,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.75.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { onSelect(style) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            FrameStyleMiniIcon(style = style)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = when (style) {
                                        WidgetFrameStyle.HYPER_GLASS -> "HyperOS"
                                        WidgetFrameStyle.FROSTED_FRAME -> "Frosted"
                                        WidgetFrameStyle.CRYSTAL_RIM -> "Minimal"
                                        WidgetFrameStyle.BORDERLESS -> "Clean"
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                                Text(
                                    text = when (style) {
                                        WidgetFrameStyle.HYPER_GLASS -> "Glass rim"
                                        WidgetFrameStyle.FROSTED_FRAME -> "Soft frame"
                                        WidgetFrameStyle.CRYSTAL_RIM -> "Thin edge"
                                        WidgetFrameStyle.BORDERLESS -> "No border"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameStyleMiniIcon(style: WidgetFrameStyle) {
    Canvas(modifier = Modifier.size(32.dp)) {
        val cr = CornerRadius(9.dp.toPx())
        // Mini sky-sunset fill
        drawRoundRect(
            brush = Brush.linearGradient(
                listOf(Color(0xFF4BA3E3), Color(0xFF28588A))
            ),
            cornerRadius = cr
        )
        when (style) {
            WidgetFrameStyle.HYPER_GLASS -> {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        listOf(Color(0xCCFFFFFF), Color(0x33FFFFFF), Color(0x99FFFFFF))
                    ),
                    cornerRadius = cr,
                    style = Stroke(width = 1.8.dp.toPx())
                )
            }
            WidgetFrameStyle.FROSTED_FRAME -> {
                drawRoundRect(
                    color = Color(0x55FFFFFF),
                    cornerRadius = cr,
                    style = Stroke(width = 4.5.dp.toPx())
                )
                drawRoundRect(
                    color = Color(0x99FFFFFF),
                    cornerRadius = cr,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            WidgetFrameStyle.CRYSTAL_RIM -> {
                drawRoundRect(
                    color = Color(0x66FFFFFF),
                    cornerRadius = cr,
                    style = Stroke(width = 1.dp.toPx())
                )
            }
            WidgetFrameStyle.BORDERLESS -> Unit
        }
    }
}

@Composable
private fun CollageLayoutGrid(
    selected: WidgetCollageLayout,
    onSelect: (WidgetCollageLayout) -> Unit
) {
    val rows = listOf(
        listOf(
            WidgetCollageLayout.AUTO,
            WidgetCollageLayout.HERO_SIDE,
            WidgetCollageLayout.GRID_2X2,
            WidgetCollageLayout.BENTO_4
        ),
        listOf(
            WidgetCollageLayout.BENTO_TOP,
            WidgetCollageLayout.MOSAIC_5,
            WidgetCollageLayout.GRID_6
        )
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { layout ->
                    val isSelected = layout == selected
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 0.75.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(layout) }
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            CollageMiniDiagram(
                                layout = layout,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = when (layout) {
                                    WidgetCollageLayout.AUTO -> "Smart"
                                    WidgetCollageLayout.HERO_SIDE -> "Hero + 2"
                                    WidgetCollageLayout.GRID_2X2 -> "2×2"
                                    WidgetCollageLayout.BENTO_4 -> "Bento 4"
                                    WidgetCollageLayout.BENTO_TOP -> "Top Hero"
                                    WidgetCollageLayout.MOSAIC_5 -> "Mosaic 5"
                                    WidgetCollageLayout.GRID_6 -> "6-Grid"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageMiniDiagram(layout: WidgetCollageLayout, tint: Color) {
    Canvas(modifier = Modifier.size(width = 36.dp, height = 24.dp)) {
        val cr = CornerRadius(5.dp.toPx())
        val fill = tint.copy(alpha = 0.16f)
        val stroke = Stroke(width = 1.2.dp.toPx())
        val lw = 1.dp.toPx()
        drawRoundRect(color = fill, cornerRadius = cr)
        drawRoundRect(color = tint.copy(alpha = 0.75f), cornerRadius = cr, style = stroke)
        when (layout) {
            WidgetCollageLayout.AUTO, WidgetCollageLayout.BENTO_4 -> {
                val x = size.width * 0.52f
                val y = size.height * 0.52f
                val x2 = x + (size.width - x) * 0.5f
                drawLine(tint, Offset(x, 0f), Offset(x, size.height), strokeWidth = lw)
                drawLine(tint, Offset(x, y), Offset(size.width, y), strokeWidth = lw)
                drawLine(tint, Offset(x2, y), Offset(x2, size.height), strokeWidth = lw)
            }
            WidgetCollageLayout.HERO_SIDE -> {
                val x = size.width * 0.58f
                val y = size.height * 0.5f
                drawLine(tint, Offset(x, 0f), Offset(x, size.height), strokeWidth = lw)
                drawLine(tint, Offset(x, y), Offset(size.width, y), strokeWidth = lw)
            }
            WidgetCollageLayout.GRID_2X2 -> {
                val x = size.width * 0.5f
                val y = size.height * 0.5f
                drawLine(tint, Offset(x, 0f), Offset(x, size.height), strokeWidth = lw)
                drawLine(tint, Offset(0f, y), Offset(size.width, y), strokeWidth = lw)
            }
            WidgetCollageLayout.BENTO_TOP -> {
                val y = size.height * 0.54f
                val x1 = size.width / 3f
                val x2 = size.width * 2f / 3f
                drawLine(tint, Offset(0f, y), Offset(size.width, y), strokeWidth = lw)
                drawLine(tint, Offset(x1, y), Offset(x1, size.height), strokeWidth = lw)
                drawLine(tint, Offset(x2, y), Offset(x2, size.height), strokeWidth = lw)
            }
            WidgetCollageLayout.MOSAIC_5 -> {
                val x = size.width * 0.44f
                val x2 = x + (size.width - x) * 0.5f
                val y = size.height * 0.5f
                drawLine(tint, Offset(x, 0f), Offset(x, size.height), strokeWidth = lw)
                drawLine(tint, Offset(x, y), Offset(size.width, y), strokeWidth = lw)
                drawLine(tint, Offset(x2, 0f), Offset(x2, size.height), strokeWidth = lw)
            }
            WidgetCollageLayout.GRID_6 -> {
                val x1 = size.width / 3f
                val x2 = size.width * 2f / 3f
                val y = size.height * 0.5f
                drawLine(tint, Offset(x1, 0f), Offset(x1, size.height), strokeWidth = lw)
                drawLine(tint, Offset(x2, 0f), Offset(x2, size.height), strokeWidth = lw)
                drawLine(tint, Offset(0f, y), Offset(size.width, y), strokeWidth = lw)
            }
        }
    }
}

@Composable
private fun <T> SegmentedPillRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else Color.Transparent,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(option) }
                ) {
                    Text(
                        text = labelOf(option),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun IntervalGridSelector(
    selected: WidgetShuffleInterval,
    onSelect: (WidgetShuffleInterval) -> Unit
) {
    val rows = WidgetShuffleInterval.entries.chunked(5)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                row.forEach { interval ->
                    val isSelected = interval == selected
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainer,
                        border = if (isSelected) null
                        else BorderStroke(0.75.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(interval) }
                    ) {
                        Text(
                            text = if (interval == WidgetShuffleInterval.MANUAL) "Off" else intervalLabel(interval),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ),
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.padding(vertical = 9.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TonalActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun SourceOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = if (selected) 1.4.dp else 0.75.dp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .border(
                        width = 1.6.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.65f),
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(!checked)
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onCheckedChange(it)
            }
        )
    }
}

@Composable
private fun SubtleRowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 2.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    )
}

// =====================================================================
// MULTI-ALBUM PICKER SCREEN
// =====================================================================

@Composable
private fun AlbumMultiPickerScreen(
    albums: List<WidgetAlbumOption>,
    selectedIds: Set<String>,
    onDone: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    var selected by remember { mutableStateOf(selectedIds) }
    var search by remember { mutableStateOf("") }
    val filtered = remember(albums, search) {
        val q = search.trim()
        if (q.isEmpty()) albums else albums.filter { it.name.contains(q, ignoreCase = true) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Choose albums",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (selected.isEmpty()) "Select 1 or more albums"
                            else "${selected.size} album${if (selected.size == 1) "" else "s"} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                    }
                    Button(
                        onClick = { onDone(selected.toList()) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Done")
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search albums…") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }
    ) { pad ->
        val context = LocalContext.current
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { album ->
                val isSelected = album.id in selected
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    border = if (isSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable {
                            selected = if (isSelected) selected - album.id else selected + album.id
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            if (album.coverUri != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(album.coverUri)
                                        .size(160)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = album.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(Icons.Rounded.Folder, contentDescription = null)
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (album.isCollection) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = "Group",
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = "${album.photoCount} photo${if (album.photoCount == 1) "" else "s"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .border(
                                    width = 1.6.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// VISUAL MULTI-PHOTO PICKER SCREEN
// =====================================================================

@Composable
private fun PhotoMultiPickerScreen(
    albums: List<WidgetAlbumOption>,
    initialSelectedIds: List<String>,
    onDone: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedOrder by remember { mutableStateOf(initialSelectedIds) }
    var activeAlbumId by remember { mutableStateOf<String?>(null) }

    val photos by produceState<List<WidgetPhoto>>(initialValue = emptyList(), key1 = activeAlbumId) {
        value = WidgetMediaRepository.loadPickerPhotos(context, filterAlbumId = activeAlbumId, limit = 800)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(modifier = Modifier.statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Select photos",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = if (selectedOrder.isEmpty()) "Tap photos to add (1 for single frame, 2+ to shuffle)"
                            else "${selectedOrder.size} photo${if (selectedOrder.size == 1) "" else "s"} picked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (selectedOrder.isNotEmpty()) {
                        TextButton(onClick = { selectedOrder = emptyList() }) { Text("Clear") }
                    }
                    Button(
                        onClick = { onDone(selectedOrder) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Done (${selectedOrder.size})")
                    }
                }

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "all") {
                        FilterChip(
                            selected = activeAlbumId == null,
                            onClick = { activeAlbumId = null },
                            label = { Text("All Photos") }
                        )
                    }
                    items(albums.take(24), key = { it.id }) { album ->
                        FilterChip(
                            selected = activeAlbumId == album.id,
                            onClick = { activeAlbumId = album.id },
                            label = { Text(album.name, maxLines = 1) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(photos, key = { it.luminaItemId }) { photo ->
                val pickIndex = selectedOrder.indexOf(photo.luminaItemId)
                val isSelected = pickIndex >= 0
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            selectedOrder = if (isSelected) {
                                selectedOrder - photo.luminaItemId
                            } else {
                                (selectedOrder + photo.luminaItemId).take(200)
                            }
                        }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(photo.uri)
                            .size(280)
                            .crossfade(true)
                            .build(),
                        contentDescription = photo.albumName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
                                .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else Color(0x66000000)
                            )
                            .border(1.4.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Text(
                                text = "${pickIndex + 1}",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// LABELS & METADATA
// =====================================================================

internal fun widgetKindTitle(kind: PhotoWidgetKind): String = when (kind) {
    PhotoWidgetKind.FRAME -> "Photo Frame"
    PhotoWidgetKind.RANDOM_SHUFFLE -> "Random Phone Shuffle"
    PhotoWidgetKind.COLLAGE -> "Smart Collage"
    PhotoWidgetKind.MEMORIES -> "Memories & Flashbacks"
    PhotoWidgetKind.STRIP -> "Photo Strip"
    PhotoWidgetKind.FAVORITES -> "Favorites Frame"
    PhotoWidgetKind.PHOTO_DATE -> "Photo & Date"
}

private fun sourceIcon(source: WidgetPhotoSource): ImageVector = when (source) {
    WidgetPhotoSource.SELECTED_PHOTOS -> Icons.Rounded.PhotoLibrary
    WidgetPhotoSource.SELECTED_ALBUMS -> Icons.Rounded.Folder
    WidgetPhotoSource.ALL_PHONE_RANDOM -> Icons.Rounded.Shuffle
    WidgetPhotoSource.SMART_GROUPS -> Icons.Rounded.AutoAwesome
    WidgetPhotoSource.LOCATION_GROUPS -> Icons.Rounded.LocationOn
    WidgetPhotoSource.DAY_GROUPS -> Icons.Rounded.Today
    WidgetPhotoSource.MEMORIES_MIX -> Icons.Rounded.AutoAwesome
    WidgetPhotoSource.FAVORITES_ONLY -> Icons.Rounded.Favorite
    WidgetPhotoSource.FAVORITES_AND_RECENT -> Icons.Rounded.Favorite
}

private fun sourceOptionsForKind(kind: PhotoWidgetKind): List<WidgetPhotoSource> = when (kind) {
    PhotoWidgetKind.COLLAGE -> listOf(
        WidgetPhotoSource.SMART_GROUPS,
        WidgetPhotoSource.LOCATION_GROUPS,
        WidgetPhotoSource.DAY_GROUPS,
        WidgetPhotoSource.SELECTED_ALBUMS,
        WidgetPhotoSource.SELECTED_PHOTOS,
        WidgetPhotoSource.ALL_PHONE_RANDOM
    )
    PhotoWidgetKind.MEMORIES -> listOf(
        WidgetPhotoSource.MEMORIES_MIX,
        WidgetPhotoSource.LOCATION_GROUPS,
        WidgetPhotoSource.DAY_GROUPS,
        WidgetPhotoSource.SELECTED_ALBUMS,
        WidgetPhotoSource.SELECTED_PHOTOS
    )
    PhotoWidgetKind.STRIP -> listOf(
        WidgetPhotoSource.FAVORITES_AND_RECENT,
        WidgetPhotoSource.SELECTED_ALBUMS,
        WidgetPhotoSource.SELECTED_PHOTOS,
        WidgetPhotoSource.SMART_GROUPS,
        WidgetPhotoSource.ALL_PHONE_RANDOM
    )
    PhotoWidgetKind.RANDOM_SHUFFLE -> listOf(
        WidgetPhotoSource.ALL_PHONE_RANDOM,
        WidgetPhotoSource.SELECTED_ALBUMS,
        WidgetPhotoSource.SELECTED_PHOTOS,
        WidgetPhotoSource.FAVORITES_ONLY
    )
    PhotoWidgetKind.FAVORITES -> listOf(
        WidgetPhotoSource.FAVORITES_ONLY,
        WidgetPhotoSource.FAVORITES_AND_RECENT,
        WidgetPhotoSource.SELECTED_PHOTOS,
        WidgetPhotoSource.SELECTED_ALBUMS
    )
    PhotoWidgetKind.FRAME, PhotoWidgetKind.PHOTO_DATE -> listOf(
        WidgetPhotoSource.SELECTED_PHOTOS,
        WidgetPhotoSource.SELECTED_ALBUMS,
        WidgetPhotoSource.ALL_PHONE_RANDOM,
        WidgetPhotoSource.FAVORITES_ONLY,
        WidgetPhotoSource.FAVORITES_AND_RECENT
    )
}

internal fun sourceLabel(source: WidgetPhotoSource): String = when (source) {
    WidgetPhotoSource.SELECTED_PHOTOS -> "Selected photos"
    WidgetPhotoSource.SELECTED_ALBUMS -> "Selected album(s)"
    WidgetPhotoSource.ALL_PHONE_RANDOM -> "All phone photos (Random)"
    WidgetPhotoSource.SMART_GROUPS -> "Smart groups (Locations & Days)"
    WidgetPhotoSource.LOCATION_GROUPS -> "Locations & Trips"
    WidgetPhotoSource.DAY_GROUPS -> "Photo Days"
    WidgetPhotoSource.MEMORIES_MIX -> "On This Day, Trips & Days"
    WidgetPhotoSource.FAVORITES_ONLY -> "Favorites"
    WidgetPhotoSource.FAVORITES_AND_RECENT -> "Favorites & Recent"
}

private fun sourceDescription(
    source: WidgetPhotoSource,
    settings: PhotoWidgetSettings,
    albumNames: Map<String, String>
): String = when (source) {
    WidgetPhotoSource.SELECTED_PHOTOS -> if (settings.selectedPhotoIds.isEmpty()) {
        "Pick 1 photo for a pinned frame or multiple photos to shuffle"
    } else {
        "${settings.selectedPhotoIds.size} photo${if (settings.selectedPhotoIds.size == 1) "" else "s"} selected"
    }
    WidgetPhotoSource.SELECTED_ALBUMS -> if (settings.selectedAlbumIds.isEmpty()) {
        "Choose 1 or more albums or album groups to shuffle"
    } else {
        val names = settings.selectedAlbumIds.mapNotNull { albumNames[it] }.take(3)
        if (names.isNotEmpty()) {
            val extra = (settings.selectedAlbumIds.size - names.size).coerceAtLeast(0)
            names.joinToString(", ") + if (extra > 0) " +$extra more" else ""
        } else {
            "${settings.selectedAlbumIds.size} album(s) selected"
        }
    }
    WidgetPhotoSource.ALL_PHONE_RANDOM -> "Uniformly shuffles any visible photo on your phone"
    WidgetPhotoSource.SMART_GROUPS -> "Shuffles coherent Location/Trip and Photo Day collages"
    WidgetPhotoSource.LOCATION_GROUPS -> "Groups photos from the same city or trip together"
    WidgetPhotoSource.DAY_GROUPS -> "Groups photos taken on the same day into a collage"
    WidgetPhotoSource.MEMORIES_MIX -> "Highlights On This Day flashbacks alongside trips and days"
    WidgetPhotoSource.FAVORITES_ONLY -> "Shuffles your starred ♥ Favorite photos"
    WidgetPhotoSource.FAVORITES_AND_RECENT -> "Prioritizes Favorites, then recent photos"
}

internal fun intervalLabel(interval: WidgetShuffleInterval): String = when (interval) {
    WidgetShuffleInterval.MANUAL -> "Manual"
    WidgetShuffleInterval.SEC_15 -> "15s"
    WidgetShuffleInterval.SEC_30 -> "30s"
    WidgetShuffleInterval.MIN_1 -> "1m"
    WidgetShuffleInterval.MIN_15 -> "15m"
    WidgetShuffleInterval.MIN_30 -> "30m"
    WidgetShuffleInterval.HOUR_1 -> "1h"
    WidgetShuffleInterval.HOUR_3 -> "3h"
    WidgetShuffleInterval.HOUR_6 -> "6h"
    WidgetShuffleInterval.DAILY -> "Daily"
}
