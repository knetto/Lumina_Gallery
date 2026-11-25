package com.luminagallery.drive.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

internal data class PlacedWidgetInfo(
    val widgetId: Int,
    val kind: PhotoWidgetKind,
    val settings: PhotoWidgetSettings
)

internal fun listPlacedWidgets(context: Context): List<PlacedWidgetInfo> {
    val manager = AppWidgetManager.getInstance(context)
    val result = mutableListOf<PlacedWidgetInfo>()
    for (provider in LuminaPhotoWidgetProvider.allProviders()) {
        val ids = manager.getAppWidgetIds(ComponentName(context, provider.javaClass))
        for (id in ids) {
            result.add(
                PlacedWidgetInfo(
                    widgetId = id,
                    kind = provider.kind,
                    settings = PhotoWidgetSettings.load(context, id, provider.kind)
                )
            )
        }
    }
    return result
}

@Composable
internal fun WidgetManagerSheetContent(
    refreshTick: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val placed = remember(refreshTick) { listPlacedWidgets(context) }
    val appWidgetManager = remember { AppWidgetManager.getInstance(context) }
    val canPinWidgets = remember { appWidgetManager.isRequestPinAppWidgetSupported }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Home screen widgets",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
        )
        Text(
            text = "Customize Xiaomi HyperOS glass frames, shuffle intervals, albums, and overlays for any widget.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (placed.isNotEmpty()) {
            Button(
                onClick = {
                    scope.launch {
                        LuminaPhotoWidgetProvider.refreshAllWidgets(context, advance = true)
                        Toast.makeText(context, "Shuffled all home screen widgets", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Shuffle all active widgets now")
            }

            Text(
                text = "Active on your home screen (${placed.size})",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    placed.forEachIndexed { idx, item ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onDismiss()
                                    val intent = Intent(context, PhotoWidgetSettingsActivity::class.java).apply {
                                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, item.widgetId)
                                    }
                                    context.startActivity(intent)
                                }
                                .padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Widgets,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = widgetKindTitle(item.kind),
                                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${sourceLabel(item.settings.source)} · ${intervalLabel(item.settings.shuffleInterval)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No Lumina widgets placed yet. Add one below or from your phone's Widgets picker.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        if (canPinWidgets) {
            Text(
                text = "Add a glass widget to home screen",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp)
            )
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    val providers = LuminaPhotoWidgetProvider.allProviders()
                    providers.forEachIndexed { idx, provider ->
                        if (idx > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val component = ComponentName(context, provider.javaClass)
                                    val ok = runCatching {
                                        appWidgetManager.requestPinAppWidget(component, null, null)
                                    }.getOrDefault(false)
                                    if (ok) {
                                        onDismiss()
                                    } else {
                                        Toast.makeText(
                                            context,
                                            "Open your Home Screen's Widgets menu to add ${widgetKindTitle(provider.kind)}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = widgetKindTitle(provider.kind),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = widgetPinSubtitle(provider.kind),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = "Pin widget",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

private fun widgetPinSubtitle(kind: PhotoWidgetKind): String = when (kind) {
    PhotoWidgetKind.FRAME -> "2×2 resizable · Pick photos or album(s) to shuffle"
    PhotoWidgetKind.RANDOM_SHUFFLE -> "2×2 resizable · Shuffles random photos from your phone"
    PhotoWidgetKind.COLLAGE -> "4×2 adaptive · Grouped location, day, album or batch collage"
    PhotoWidgetKind.MEMORIES -> "4×2 adaptive · On This Day, trips & photo days"
    PhotoWidgetKind.STRIP -> "4×1 adaptive · Horizontal or vertical glass photo strip"
    PhotoWidgetKind.FAVORITES -> "2×2 resizable · Dedicated ♥ Favorites glass frame"
    PhotoWidgetKind.PHOTO_DATE -> "2×2 resizable · Live HyperOS glass date + photo frame"
}
