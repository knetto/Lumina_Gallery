package com.luminagallery.drive.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.R
import com.luminagallery.drive.data.repository.AppUpdateManager
import com.luminagallery.drive.data.repository.AppUpdateState
import kotlinx.coroutines.launch

/**
 * Flagship One UI 8 / Xiaomi HyperOS / Apple-inspired bottom-anchored floating Update Card.
 *
 * Anchored neatly above the navigation bar for effortless one-hand reach with:
 *  - Crisp Lumina Prism Blossom app icon hero + subtle ambient top wash
 *  - Clean metadata pills (commit hash, date, APK size)
 *  - Dedicated scrollable "What's new" release notes card
 *  - Full-width tactile primary CTA + spacious secondary actions (never cramped!)
 *  - Silky 120Hz animated progress bar & SHA-1 integrity verification feedback
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppUpdateDialogHost(
    appUpdateManager: AppUpdateManager
) {
    val state by appUpdateManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner, appUpdateManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                appUpdateManager.refreshInstallPermissionAndLaunchIfReady(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val shouldShowDialog = when (val s = state) {
        is AppUpdateState.Available -> s.showPopup
        is AppUpdateState.Downloading -> true
        is AppUpdateState.ReadyToInstall -> true
        is AppUpdateState.Error -> s.showPopup
        else -> false
    }

    if (!shouldShowDialog) return

    val isDownloading = state is AppUpdateState.Downloading
    val primary = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)

    Dialog(
        onDismissRequest = {
            if (!isDownloading) appUpdateManager.dismissPopup()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading
        )
    ) {
        // Full-screen translucent scrim anchoring our One UI / HyperOS card cleanly at the bottom
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    enabled = !isDownloading,
                    onClick = appUpdateManager::dismissPopup
                )
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = surfaceColor,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.8.dp, cardBorder, RoundedCornerShape(32.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    // Subtle flagship ambient top wash echoing the Lumina icon palette
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        primary.copy(alpha = 0.14f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Subtle top pill indicator
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 4.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f))
                        )

                        Spacer(Modifier.height(18.dp))

                        val phaseKey = when (state) {
                            is AppUpdateState.Available -> "available"
                            is AppUpdateState.Downloading -> "downloading"
                            is AppUpdateState.ReadyToInstall -> "ready"
                            is AppUpdateState.Error -> "error"
                            else -> "other"
                        }

                        AnimatedContent(
                            targetState = phaseKey,
                            transitionSpec = {
                                (fadeIn(tween(220, easing = OneUiMotion.FluidEasing)) +
                                    scaleIn(tween(240, easing = OneUiMotion.FluidEasing), initialScale = 0.97f))
                                    .togetherWith(fadeOut(tween(140)))
                            },
                            label = "updateCardPhase"
                        ) { _ ->
                            when (val s = state) {
                                is AppUpdateState.Available -> {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        LuminaAppIconBadge()

                                        Spacer(Modifier.height(14.dp))

                                        Text(
                                            text = "Update available",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (-0.4).sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(Modifier.height(8.dp))

                                        // Clean metadata pill strip: #commit · size · date (wraps cleanly at large font scales)
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            MetaPill(
                                                text = "#${s.update.shortSha}",
                                                accent = true,
                                                monospace = true
                                            )
                                            MetaPill(
                                                text = FormatUtils.formatFileSize(s.update.apkSizeBytes),
                                                accent = false
                                            )
                                            MetaPill(
                                                text = s.update.commitDateLabel.substringBefore(" · "),
                                                accent = false
                                            )
                                        }

                                        Spacer(Modifier.height(18.dp))

                                        // Clean scrollable release notes glass card
                                        ReleaseNotesCard(message = s.update.commitMessage)

                                        Spacer(Modifier.height(20.dp))

                                        // Full-width thumb-friendly primary CTA
                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                appUpdateManager.startDownloadAndInstall(context, s.update)
                                            },
                                            shape = RoundedCornerShape(18.dp),
                                            contentPadding = ButtonDefaults.ContentPadding,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(52.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(19.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Update now",
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontSize = 15.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            )
                                        }

                                        Spacer(Modifier.height(8.dp))

                                        // Spacious secondary actions row (never squeezed!)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    appUpdateManager.dismissPopup()
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    text = "Later",
                                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .width(1.dp)
                                                    .height(16.dp)
                                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                            )
                                            TextButton(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    scope.launch {
                                                        appUpdateManager.skipUpdate(s.update.commitSha)
                                                    }
                                                },
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(
                                                    text = "Skip this build",
                                                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }

                                is AppUpdateState.Downloading -> {
                                    val animatedProgress by animateFloatAsState(
                                        targetValue = s.progress,
                                        animationSpec = tween(140, easing = OneUiMotion.FluidEasing),
                                        label = "updateDownloadProgress"
                                    )
                                    val pct = (animatedProgress * 100f).toInt().coerceIn(0, 100)

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        LuminaAppIconBadge()

                                        Spacer(Modifier.height(14.dp))

                                        Text(
                                            text = "Downloading update…",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (-0.3).sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        Spacer(Modifier.height(6.dp))

                                        Text(
                                            text = if (s.totalBytes > 0L) {
                                                "${FormatUtils.formatFileSize(s.downloadedBytes)} of ${FormatUtils.formatFileSize(s.totalBytes)}"
                                            } else {
                                                FormatUtils.formatFileSize(s.downloadedBytes)
                                            },
                                            style = TextStyle(
                                                fontSize = 13.5.sp,
                                                fontFeatureSettings = "tnum",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )

                                        Spacer(Modifier.height(20.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            if (s.totalBytes > 0L) {
                                                LinearProgressIndicator(
                                                    progress = { animatedProgress },
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(8.dp),
                                                    strokeCap = StrokeCap.Round,
                                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                                )
                                            } else {
                                                LinearProgressIndicator(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(8.dp),
                                                    strokeCap = StrokeCap.Round,
                                                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                                )
                                            }
                                            Text(
                                                text = "$pct%",
                                                style = TextStyle(
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFeatureSettings = "tnum",
                                                    color = primary
                                                ),
                                                textAlign = TextAlign.End
                                            )
                                        }

                                        Spacer(Modifier.height(16.dp))

                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = MaterialTheme.colorScheme.surfaceContainer
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.VerifiedUser,
                                                    contentDescription = null,
                                                    tint = primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "APK signature & checksum verified before install",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }

                                        Spacer(Modifier.height(10.dp))

                                        TextButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                appUpdateManager.cancelDownload()
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "Cancel download",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                is AppUpdateState.ReadyToInstall -> {
                                    val isPermNeeded = s.needsInstallPermission
                                    val badgeColor = if (isPermNeeded) primary else Color(0xFF34D399)

                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(60.dp)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(badgeColor.copy(alpha = 0.16f))
                                                .border(0.8.dp, badgeColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (isPermNeeded) {
                                                    Icons.Rounded.Security
                                                } else {
                                                    Icons.Rounded.CheckCircle
                                                },
                                                contentDescription = null,
                                                tint = badgeColor,
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }

                                        Spacer(Modifier.height(14.dp))

                                        Text(
                                            text = if (isPermNeeded) "Allow app installs" else "Ready to install",
                                            style = MaterialTheme.typography.headlineSmall.copy(
                                                fontSize = 21.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = (-0.3).sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        Spacer(Modifier.height(6.dp))

                                        MetaPill(
                                            text = "Commit #${s.update.shortSha} verified",
                                            accent = !isPermNeeded,
                                            monospace = true
                                        )

                                        Spacer(Modifier.height(14.dp))

                                        Text(
                                            text = if (isPermNeeded) {
                                                "Enable \"Allow from this source\" once so Android can update Lumina Gallery in-place."
                                            } else {
                                                "Your photos, albums, favorites, and Google Drive sign-in stay completely intact."
                                            },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )

                                        Spacer(Modifier.height(20.dp))

                                        Button(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                if (isPermNeeded) {
                                                    appUpdateManager.openInstallUnknownAppsSettings(context)
                                                } else {
                                                    appUpdateManager.launchDownloadedInstaller(context)
                                                }
                                            },
                                            shape = RoundedCornerShape(18.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(52.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isPermNeeded) Icons.Rounded.Security else Icons.Rounded.InstallMobile,
                                                contentDescription = null,
                                                modifier = Modifier.size(19.dp)
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = if (isPermNeeded) "Open permission settings" else "Install update",
                                                style = MaterialTheme.typography.labelLarge.copy(
                                                    fontSize = 15.5.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            )
                                        }

                                        Spacer(Modifier.height(6.dp))

                                        TextButton(
                                            onClick = appUpdateManager::dismissPopup,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = "Close",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                is AppUpdateState.Error -> {
                                    val errColor = MaterialTheme.colorScheme.error
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(58.dp)
                                                .clip(RoundedCornerShape(20.dp))
                                                .background(errColor.copy(alpha = 0.15f))
                                                .border(0.8.dp, errColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.ErrorOutline,
                                                contentDescription = null,
                                                tint = errColor,
                                                modifier = Modifier.size(28.dp)
                                            )
                                        }

                                        Spacer(Modifier.height(14.dp))

                                        Text(
                                            text = "Update didn't finish",
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )

                                        Spacer(Modifier.height(8.dp))

                                        Text(
                                            text = s.message,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )

                                        Spacer(Modifier.height(20.dp))

                                        if (s.update != null) {
                                            Button(
                                                onClick = {
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    appUpdateManager.startDownloadAndInstall(context, s.update)
                                                },
                                                shape = RoundedCornerShape(18.dp),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(50.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Rounded.Refresh,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                                Spacer(Modifier.width(8.dp))
                                                Text("Try again", fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.height(6.dp))
                                        }

                                        TextButton(
                                            onClick = appUpdateManager::dismissPopup,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }

                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LuminaAppIconBadge() {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(20.dp))
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(20.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "Lumina Gallery",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun MetaPill(
    text: String,
    accent: Boolean,
    monospace: Boolean = false
) {
    val bg = if (accent) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.85f)
    }
    val fg = if (accent) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = bg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Medium,
                fontFeatureSettings = "tnum"
            ),
            color = fg,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ReleaseNotesCard(message: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 0.6.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 160.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = "What's new",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.2.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium.copy(
                    lineHeight = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
