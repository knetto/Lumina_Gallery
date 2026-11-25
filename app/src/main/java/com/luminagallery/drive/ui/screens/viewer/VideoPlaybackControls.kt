package com.luminagallery.drive.ui.screens.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.luminagallery.drive.ui.components.FormatUtils
import kotlin.math.roundToLong

private val PanelColor = Color(0xED171E29)
private val ControlsAccent = Color(0xFFADCDFB)
private val ControlsSecondary = Color(0xFFC0CBDA)
private val PlaybackSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
internal fun VideoPlaybackControls(
    controller: ActiveVideoController,
    itemId: String,
    fallbackDurationMs: Long,
    modifier: Modifier = Modifier
) {
    val isActive = controller.activeItemId == itemId
    val playerDuration = if (isActive) controller.durationMs else 0L
    val duration = playerDuration.takeIf { it > 0L } ?: fallbackDurationMs
    val canSeek = playerDuration > 0L
    val compact = LocalConfiguration.current.screenHeightDp < 440

    Surface(
        modifier = modifier.widthIn(max = if (compact) 760.dp else 560.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = PanelColor,
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color(0x30A8C5ED))
    ) {
        BoxWithConstraints(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            if (compact && maxWidth >= 500.dp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    VideoTransport(controller, itemId, isActive, canSeek, Modifier.weight(1f))
                    VideoTimeline(controller, duration, canSeek, itemId, Modifier.weight(1f))
                }
            } else {
                Column {
                    VideoTimeline(controller, duration, canSeek, itemId)
                    VideoTransport(controller, itemId, isActive, canSeek)
                }
            }
        }
    }
}

@Composable
private fun VideoTransport(
    controller: ActiveVideoController,
    itemId: String,
    isActive: Boolean,
    canSeek: Boolean,
    modifier: Modifier = Modifier
) {
    val isPlaying = isActive && controller.isPlaying
    val isEnded = isActive && controller.isEnded
    val isMuted = isActive && controller.isMuted
    val speed = if (isActive) controller.playbackSpeed else 1f
    var showSpeedMenu by remember(itemId) { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            IconButton(onClick = { controller.toggleMute() }, enabled = isActive) {
                Icon(
                    if (isMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute"
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { controller.skipBy(-5_000L) }, enabled = canSeek) {
                Icon(Icons.Rounded.Replay5, contentDescription = "Back 5 seconds")
            }
            FilledIconButton(
                onClick = { controller.togglePlayPause() },
                enabled = isActive,
                modifier = Modifier.size(54.dp, 48.dp),
                shape = RoundedCornerShape(16.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ControlsAccent,
                    contentColor = Color(0xFF10253E)
                )
            ) {
                Icon(
                    imageVector = when {
                        isEnded -> Icons.Rounded.Replay
                        isPlaying -> Icons.Rounded.Pause
                        else -> Icons.Rounded.PlayArrow
                    },
                    contentDescription = when {
                        isEnded -> "Replay"
                        isPlaying -> "Pause"
                        else -> "Play"
                    },
                    modifier = Modifier.size(27.dp)
                )
            }
            IconButton(onClick = { controller.skipBy(5_000L) }, enabled = canSeek) {
                Icon(Icons.Rounded.Forward5, contentDescription = "Forward 5 seconds")
            }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextButton(
                onClick = { showSpeedMenu = true },
                enabled = isActive,
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.semantics { contentDescription = "Playback speed" }
            ) {
                Text(
                    text = playbackSpeedLabel(speed),
                    color = if (isActive) ControlsAccent else ControlsSecondary.copy(alpha = 0.42f),
                    maxLines = 1
                )
            }
            DropdownMenu(
                expanded = showSpeedMenu && isActive,
                onDismissRequest = { showSpeedMenu = false },
                shape = RoundedCornerShape(18.dp),
                containerColor = Color(0xFF212B38)
            ) {
                for (option in PlaybackSpeeds) {
                    DropdownMenuItem(
                        text = { Text(playbackSpeedLabel(option), color = Color.White) },
                        trailingIcon = if (option == speed) {
                            { Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = ControlsAccent) }
                        } else null,
                        onClick = {
                            controller.changePlaybackSpeed(option)
                            showSpeedMenu = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoTimeline(
    controller: ActiveVideoController,
    durationMs: Long,
    canSeek: Boolean,
    itemId: String,
    modifier: Modifier = Modifier
) {
    val positionMs = if (canSeek) controller.currentPositionMs.coerceIn(0L, durationMs) else 0L
    val timeStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    val elapsedLabel = remember(positionMs / 1000L) { FormatUtils.formatDurationPadded(positionMs) }
    val durationLabel = remember(durationMs / 1000L) { FormatUtils.formatDurationPadded(durationMs) }
    val progress = (positionMs.toDouble() / durationMs.coerceAtLeast(1L)).toFloat()
    val sliderState = remember(itemId) { SliderState(value = progress) }
    SideEffect { sliderState.value = progress }
    val sliderColors = SliderDefaults.colors(
        thumbColor = ControlsAccent,
        activeTrackColor = ControlsAccent,
        inactiveTrackColor = Color(0xFF3F4C60)
    )
    DisposableEffect(controller, itemId) {
        onDispose {
            if (controller.activeItemId == itemId) controller.isScrubbing = false
        }
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(elapsedLabel, color = ControlsAccent, style = timeStyle, maxLines = 1)
        Slider(
            state = sliderState,
            onValueChange = {
                sliderState.value = it
                controller.isScrubbing = true
                controller.seekTo((it.toDouble() * durationMs).roundToLong())
            },
            onValueChangeFinished = { controller.isScrubbing = false },
            enabled = canSeek,
            colors = sliderColors,
            modifier = Modifier.weight(1f).height(40.dp).semantics { contentDescription = "Playback position" },
            thumb = {
                Box(
                    modifier = Modifier.size(10.dp).background(
                        if (canSeek) ControlsAccent else ControlsSecondary.copy(alpha = 0.35f),
                        CircleShape
                    )
                )
            },
            track = { state ->
                SliderDefaults.Track(
                    sliderState = state,
                    modifier = Modifier.height(4.dp),
                    enabled = canSeek,
                    colors = sliderColors,
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null
                )
            }
        )
        Text(durationLabel, color = ControlsSecondary, style = timeStyle, maxLines = 1)
    }
}

private fun playbackSpeedLabel(speed: Float): String = "${speed.toString().removeSuffix(".0")}×"
