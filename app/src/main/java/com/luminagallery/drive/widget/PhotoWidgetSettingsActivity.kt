package com.luminagallery.drive.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.luminagallery.drive.R
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.ui.theme.LuminaTheme
import kotlinx.coroutines.launch

class PhotoWidgetSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(this)
        val provider = when (manager.getAppWidgetInfo(widgetId)?.provider) {
            ComponentName(this, LuminaPhotoShuffleWidgetProvider::class.java) -> LuminaPhotoShuffleWidgetProvider()
            ComponentName(this, LuminaMemoriesWidgetProvider::class.java) -> LuminaMemoriesWidgetProvider()
            else -> {
                finish()
                return
            }
        }
        val isShuffle = provider is LuminaPhotoShuffleWidgetProvider
        val saved = PhotoWidgetSettings.load(this, widgetId)

        setContent {
            LuminaTheme(themeMode = ThemeMode.SYSTEM) {
                var seconds by rememberSaveable { mutableStateOf(saved.shuffleSeconds.toString()) }
                var source by rememberSaveable { mutableStateOf(saved.memorySource) }
                var saving by remember { mutableStateOf(false) }
                var saveFailed by remember { mutableStateOf(false) }
                val validSeconds = seconds.toIntOrNull()?.takeIf { it in 1..MAX_SHUFFLE_SECONDS }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                                .verticalScroll(rememberScrollState()).padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = stringResource(if (isShuffle) R.string.widget_photo_shuffle else R.string.widget_memories),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            if (isShuffle) {
                                OutlinedTextField(
                                    value = seconds,
                                    onValueChange = { seconds = it.filter(Char::isDigit).take(4) },
                                    label = { Text(stringResource(R.string.widget_shuffle_interval)) },
                                    supportingText = {
                                        Text(stringResource(
                                            if (validSeconds == null) R.string.widget_shuffle_interval_range
                                            else R.string.widget_shuffle_visible
                                        ))
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = validSeconds == null,
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else {
                                for (option in WidgetMemorySource.entries) {
                                    val label = when (option) {
                                        WidgetMemorySource.DAYS -> R.string.widget_memory_days
                                        WidgetMemorySource.TRIPS -> R.string.widget_memory_trips
                                        WidgetMemorySource.BOTH -> R.string.widget_memory_both
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth().selectable(
                                            selected = option == source,
                                            role = Role.RadioButton,
                                            onClick = { source = option }
                                        ).padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        RadioButton(selected = option == source, onClick = null)
                                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                            if (saveFailed) {
                                Text(stringResource(R.string.widget_save_failed), color = MaterialTheme.colorScheme.error)
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { finish() }, enabled = !saving) { Text(stringResource(android.R.string.cancel)) }
                                Button(
                                    enabled = !saving && (!isShuffle || validSeconds != null),
                                    onClick = {
                                        saving = true
                                        saveFailed = false
                                        lifecycleScope.launch {
                                            try {
                                                PhotoWidgetSettings(validSeconds ?: saved.shuffleSeconds, source)
                                                    .save(applicationContext, widgetId)
                                                provider.renderWidgets(applicationContext, manager, intArrayOf(widgetId))
                                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                                finish()
                                            } catch (_: RuntimeException) {
                                                saveFailed = true
                                            } finally {
                                                saving = false
                                            }
                                        }
                                    }
                                ) { Text(stringResource(R.string.widget_save)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
