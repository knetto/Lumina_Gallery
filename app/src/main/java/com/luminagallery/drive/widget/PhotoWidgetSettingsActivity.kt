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
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
                val style = if (useDark) {
                    SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            LuminaTheme(themeMode = prefs.themeMode, dynamicColor = prefs.dynamicColor) {
                WidgetSettingsRoot(
                    kind = kind,
                    initialSettings = initialSettings,
                    onCancel = { finish() },
                    onSave = { updated, onResult ->
                        lifecycleScope.launch {
                            try {
                                updated.save(applicationContext, widgetId, resetState = true)
                                WidgetRefreshScheduler.syncSchedule(applicationContext)
                                provider.renderWidgets(applicationContext, manager, intArrayOf(widgetId), advance = false)
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
    kind: PhotoWidgetKind,
    initialSettings: PhotoWidgetSettings,
    onCancel: () -> Unit,
    onSave: (PhotoWidgetSettings, (Boolean) -> Unit) -> Unit
) {
    val context = LocalContext.current
    var settings by remember { mutableStateOf(initialSettings) }
    var activePicker by remember { mutableStateOf(ActiveWidgetPicker.NONE) }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }

    val albums by produceState<List<WidgetAlbumOption>>(initialValue = emptyList()) {
        value = WidgetMediaRepository.loadPickerAlbums(context)
    }

    val albumNameById = remember(albums) { albums.associate { it.id to it.name } }

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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel, enabled = !saving) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(android.R.string.cancel))
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = widgetKindTitle(kind),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "HyperOS Glass Widget Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
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
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onCancel,
                            enabled = !saving,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        Button(
                            onClick = {
                                saving = true
                                saveFailed = false
                                onSave(settings) { ok ->
                                    saving = false
                                    if (!ok) saveFailed = true
                                }
                            },
                            enabled = !saving,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1.5f).height(48.dp)
                        ) {
                            if (saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(stringResource(R.string.widget_save), fontWeight = FontWeight.SemiBold)
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. LIVE HYPEROS GLASS PREVIEW
            LiveWidgetPreviewStage(
                kind = kind,
                settings = settings
            )

            // 2. PHOTO SOURCE SECTION
            WidgetSettingsCard(title = "Photo source") {
                val sourceOptions = sourceOptionsForKind(kind)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sourceOptions.forEach { src ->
                        val selected = settings.source == src
                        SourceOptionRow(
                            title = sourceLabel(src),
                            subtitle = sourceDescription(src, settings, albumNameById),
                            selected = selected,
                            onClick = {
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

                // Inline picker action button for Selected Photos / Selected Albums
                if (settings.source == WidgetPhotoSource.SELECTED_PHOTOS) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { activePicker = ActiveWidgetPicker.PHOTOS },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (settings.selectedPhotoIds.isEmpty()) "Pick photos from gallery"
                            else "Edit picked photos (${settings.selectedPhotoIds.size} selected)"
                        )
                    }
                } else if (settings.source == WidgetPhotoSource.SELECTED_ALBUMS) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { activePicker = ActiveWidgetPicker.ALBUMS },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (settings.selectedAlbumIds.isEmpty()) "Choose one or more albums"
                            else "Edit albums (${settings.selectedAlbumIds.size} selected)"
                        )
                    }
                } else if (settings.source == WidgetPhotoSource.MEMORIES_MIX) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Include memory types",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = WidgetMemorySource.entries,
                        selected = settings.memorySource,
                        labelOf = {
                            when (it) {
                                WidgetMemorySource.BOTH -> "Days & Trips"
                                WidgetMemorySource.TRIPS -> "Trips & Places"
                                WidgetMemorySource.DAYS -> "Photo Days"
                            }
                        },
                        onSelect = { settings = settings.copy(memorySource = it) }
                    )
                }
            }

            // 3. SHUFFLE & TIMING SECTION
            WidgetSettingsCard(title = "Shuffle & timing") {
                Text(
                    text = "Auto-change interval",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = WidgetShuffleInterval.entries,
                    selected = settings.shuffleInterval,
                    labelOf = ::intervalLabel,
                    onSelect = { settings = settings.copy(shuffleInterval = it) }
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = when {
                        settings.shuffleInterval == WidgetShuffleInterval.MANUAL ->
                            "Stays on the current photo/group until you shuffle manually."
                        settings.shuffleInterval.isFastSlideshow ->
                            "Smoothly cross-fades while visible on your home screen, with a fresh batch every 30 min."
                        else ->
                            "Automatically updates in the background every ${intervalLabel(settings.shuffleInterval).lowercase()}."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )

                if (settings.source != WidgetPhotoSource.ALL_PHONE_RANDOM) {
                    WidgetToggleRow(
                        title = "Shuffle randomly",
                        subtitle = "Randomize order instead of newest / picked order",
                        checked = settings.shuffleOrder,
                        onCheckedChange = { settings = settings.copy(shuffleOrder = it) }
                    )
                }

                if (kind.isMultiPhoto && settings.source == WidgetPhotoSource.SELECTED_ALBUMS) {
                    WidgetToggleRow(
                        title = "Group collage by album",
                        subtitle = "Show photos from one chosen album per collage instead of mixing them",
                        checked = settings.groupByAlbum,
                        onCheckedChange = { settings = settings.copy(groupByAlbum = it) }
                    )
                }

                if (settings.source != WidgetPhotoSource.SELECTED_PHOTOS) {
                    WidgetToggleRow(
                        title = "Exclude screenshots",
                        subtitle = "Skip the Screenshots folder when picking photos",
                        checked = settings.excludeScreenshots,
                        onCheckedChange = { settings = settings.copy(excludeScreenshots = it) }
                    )
                }
            }

            // 4. XIAOMI HYPEROS GLASS STYLE SECTION
            WidgetSettingsCard(title = "Xiaomi HyperOS glass style") {
                Text(
                    text = "Glass border effect",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = WidgetFrameStyle.entries,
                    selected = settings.frameStyle,
                    labelOf = {
                        when (it) {
                            WidgetFrameStyle.HYPER_GLASS -> "HyperOS Glass"
                            WidgetFrameStyle.FROSTED_FRAME -> "Frosted Frame"
                            WidgetFrameStyle.CRYSTAL_RIM -> "Crystal Rim"
                            WidgetFrameStyle.BORDERLESS -> "Borderless"
                        }
                    },
                    onSelect = { settings = settings.copy(frameStyle = it) }
                )

                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Squircle corner curve",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = WidgetCornerStyle.entries,
                    selected = settings.cornerStyle,
                    labelOf = {
                        when (it) {
                            WidgetCornerStyle.HYPER_OS -> "HyperOS (26dp)"
                            WidgetCornerStyle.SOFT -> "Soft (20dp)"
                            WidgetCornerStyle.COMPACT -> "Compact (12dp)"
                        }
                    },
                    onSelect = { settings = settings.copy(cornerStyle = it) }
                )

                if (kind == PhotoWidgetKind.COLLAGE || kind == PhotoWidgetKind.MEMORIES) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Collage layout",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    ChipRow(
                        options = WidgetCollageLayout.entries,
                        selected = settings.collageLayout,
                        labelOf = {
                            when (it) {
                                WidgetCollageLayout.AUTO -> "Auto Adaptive"
                                WidgetCollageLayout.HERO_SIDE -> "Hero + Side"
                                WidgetCollageLayout.GRID_2X2 -> "2×2 Grid"
                                WidgetCollageLayout.BENTO_4 -> "4-Tile Bento"
                            }
                        },
                        onSelect = { settings = settings.copy(collageLayout = it) }
                    )
                }
            }

            // 5. OVERLAYS & INTERACTION TOGGLES
            WidgetSettingsCard(title = "Overlays & quick controls") {
                WidgetToggleRow(
                    title = "Show caption label",
                    subtitle = "Display location, day, memory or album title on the widget",
                    checked = settings.showCaption,
                    onCheckedChange = { settings = settings.copy(showCaption = it) }
                )

                if (settings.showCaption) {
                    Spacer(Modifier.height(6.dp))
                    ChipRow(
                        options = WidgetCaptionStyle.entries,
                        selected = settings.captionStyle,
                        labelOf = {
                            when (it) {
                                WidgetCaptionStyle.GLASS_PILL -> "Frosted Glass Pill"
                                WidgetCaptionStyle.EDITORIAL_FADE -> "Editorial Fade"
                            }
                        },
                        onSelect = { settings = settings.copy(captionStyle = it) }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                WidgetToggleRow(
                    title = "Show live date badge",
                    subtitle = "Floating HyperOS frosted glass weekday & day card",
                    checked = settings.showDateBadge,
                    onCheckedChange = { settings = settings.copy(showDateBadge = it) }
                )

                WidgetToggleRow(
                    title = "Show quick shuffle button",
                    subtitle = "Subtle translucent shuffle button in the bottom-right corner",
                    checked = settings.showShuffleButton,
                    onCheckedChange = { settings = settings.copy(showShuffleButton = it) }
                )

                WidgetToggleRow(
                    title = "Show quick settings button",
                    subtitle = "Translucent gear button in the top-left corner (you can also edit via long-press or Settings)",
                    checked = settings.showSettingsButton,
                    onCheckedChange = { settings = settings.copy(showSettingsButton = it) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )

                Text(
                    text = "When tapping a photo",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ChipRow(
                    options = WidgetTapAction.entries,
                    selected = settings.tapAction,
                    labelOf = {
                        when (it) {
                            WidgetTapAction.OPEN_PHOTO -> "Open in Lumina"
                            WidgetTapAction.SHUFFLE_NEXT -> "Shuffle next"
                            WidgetTapAction.OPEN_SETTINGS -> "Open settings"
                        }
                    },
                    onSelect = { settings = settings.copy(tapAction = it) }
                )
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

private data class PreviewSourceKey(
    val kind: PhotoWidgetKind,
    val source: WidgetPhotoSource,
    val selectedAlbumIds: List<String>,
    val selectedPhotoIds: List<String>,
    val excludeScreenshots: Boolean,
    val memorySource: WidgetMemorySource,
    val groupByAlbum: Boolean
)

private data class PreviewPhotoSample(
    val bitmaps: List<Bitmap>,
    val caption: WidgetCaptionData
)

@Composable
private fun LiveWidgetPreviewStage(
    kind: PhotoWidgetKind,
    settings: PhotoWidgetSettings
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density

    val previewWidthDp = when (kind) {
        PhotoWidgetKind.COLLAGE, PhotoWidgetKind.MEMORIES -> 296f
        PhotoWidgetKind.STRIP -> 296f
        else -> 178f
    }
    val previewHeightDp = when (kind) {
        PhotoWidgetKind.COLLAGE, PhotoWidgetKind.MEMORIES -> 158f
        PhotoWidgetKind.STRIP -> 84f
        else -> 178f
    }

    // Load sample photos ONLY when the content source / selection changes (never jumps photos on visual toggles!)
    val sourceKey = remember(
        kind,
        settings.source,
        settings.selectedAlbumIds,
        settings.selectedPhotoIds,
        settings.excludeScreenshots,
        settings.memorySource,
        settings.groupByAlbum
    ) {
        PreviewSourceKey(
            kind = kind,
            source = settings.source,
            selectedAlbumIds = settings.selectedAlbumIds,
            selectedPhotoIds = settings.selectedPhotoIds,
            excludeScreenshots = settings.excludeScreenshots,
            memorySource = settings.memorySource,
            groupByAlbum = settings.groupByAlbum
        )
    }

    val sample by produceState<PreviewPhotoSample?>(
        initialValue = null,
        key1 = sourceKey
    ) {
        value = withContext(Dispatchers.IO) {
            loadPreviewSample(context, kind, settings)
        }
    }

    // Instant live glass re-render on the cached sample bitmaps when any visual toggle changes
    val previewBitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = sample,
        key2 = settings.frameStyle,
        key3 = Triple(
            settings.cornerStyle,
            settings.collageLayout,
            Triple(settings.showCaption, settings.captionStyle, Triple(settings.showDateBadge, settings.showShuffleButton, settings.showSettingsButton))
        )
    ) {
        val loaded = sample ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val effDensity = density.coerceIn(1.5f, 3f)
            val pattern = HyperOsGlassRenderer.resolveTilePattern(
                kind = kind,
                collageLayout = settings.collageLayout,
                widthDp = previewWidthDp,
                heightDp = previewHeightDp,
                photoCount = loaded.bitmaps.size
            )
            HyperOsGlassRenderer.renderWidgetBitmap(
                photos = loaded.bitmaps,
                pattern = pattern,
                widthPx = (previewWidthDp * effDensity).toInt(),
                heightPx = (previewHeightDp * effDensity).toInt(),
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
            .clip(RoundedCornerShape(26.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF141D2F),
                        Color(0xFF1E2D45),
                        Color(0xFF101726)
                    )
                )
            )
            .padding(vertical = 22.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(previewWidthDp.dp)
                .height(previewHeightDp.dp)
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
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }

            // Mirror optional quick buttons cleanly in live preview
            if (settings.showSettingsButton) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x66121A26))
                        .border(0.8.dp, Color(0x4AFFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
            if (settings.showShuffleButton) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x66121A26))
                        .border(0.8.dp, Color(0x4AFFFFFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

private suspend fun loadPreviewSample(
    context: Context,
    kind: PhotoWidgetKind,
    settings: PhotoWidgetSettings
): PreviewPhotoSample {
    val maxSlots = if (kind.isMultiPhoto) 4 else 1
    val resolved = runCatching {
        WidgetMediaRepository.resolveWidgetFrames(
            context = context,
            widgetId = -1,
            kind = kind,
            settings = settings,
            slotsPerFrame = maxSlots,
            frameCount = 1,
            advance = false
        ).firstOrNull()
    }.getOrNull()

    val decoded = resolved?.photos.orEmpty().mapNotNull { photo ->
        decodePreviewUri(context, Uri.parse(photo.uri), 420)
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

                // Quick album filter bar
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
// REUSABLE SETTINGS UI HELPERS
// =====================================================================

@Composable
private fun WidgetSettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
private fun SourceOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
        else MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) BorderStroke(1.4.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(option) },
                label = { Text(labelOf(option)) },
                shape = RoundedCornerShape(14.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    }
}

internal fun widgetKindTitle(kind: PhotoWidgetKind): String = when (kind) {
    PhotoWidgetKind.FRAME -> "Photo Frame"
    PhotoWidgetKind.RANDOM_SHUFFLE -> "Random Phone Shuffle"
    PhotoWidgetKind.COLLAGE -> "Smart Collage"
    PhotoWidgetKind.MEMORIES -> "Memories & Flashbacks"
    PhotoWidgetKind.STRIP -> "Photo Strip"
    PhotoWidgetKind.FAVORITES -> "Favorites Frame"
    PhotoWidgetKind.PHOTO_DATE -> "Photo & Date"
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
