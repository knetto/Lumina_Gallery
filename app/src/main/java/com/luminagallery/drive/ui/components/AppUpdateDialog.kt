package com.luminagallery.drive.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.data.repository.AppUpdateManager
import com.luminagallery.drive.data.repository.AppUpdateState
import com.luminagallery.drive.data.repository.GitHubAppUpdate
import kotlinx.coroutines.launch

/**
 * Samsung One UI-styled popup shown automatically when a newer GitHub commit updates
 * `apk/Lumina-Gallery.apk`, or when triggered manually from Settings -> Check for updates.
 */
@Composable
fun AppUpdateDialogHost(
    appUpdateManager: AppUpdateManager
) {
    val state by appUpdateManager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Automatically launch Android's package installer as soon as the user returns
    // from granting "Install unknown apps" permission.
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

    Dialog(
        onDismissRequest = {
            if (!isDownloading) appUpdateManager.dismissPopup()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading
        )
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 22.dp)
            ) {
                when (val s = state) {
                    is AppUpdateState.Available -> {
                        UpdateHeader(
                            title = "Update available",
                            subtitle = "${s.update.commitDateLabel} · ${FormatUtils.formatFileSize(s.update.apkSizeBytes)}",
                            shortSha = s.update.shortSha
                        )
                        Spacer(Modifier.height(14.dp))
                        CommitChangelogCard(message = s.update.commitMessage)
                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch { appUpdateManager.skipUpdate(s.update.commitSha) }
                                }
                            ) {
                                Text(
                                    text = "Skip",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = appUpdateManager::dismissPopup) {
                                    Text("Later")
                                }
                                Button(
                                    shape = RoundedCornerShape(18.dp),
                                    onClick = {
                                        scope.launch {
                                            appUpdateManager.downloadAndInstallUpdate(context, s.update)
                                        }
                                    }
                                ) {
                                    Text("Update now", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    is AppUpdateState.Downloading -> {
                        val pct = (s.progress * 100f).toInt().coerceIn(0, 100)
                        UpdateHeader(
                            title = "Downloading update…",
                            subtitle = if (s.totalBytes > 0L) {
                                "${FormatUtils.formatFileSize(s.downloadedBytes)} / ${FormatUtils.formatFileSize(s.totalBytes)} · $pct%"
                            } else {
                                FormatUtils.formatFileSize(s.downloadedBytes)
                            },
                            shortSha = s.update.shortSha
                        )
                        Spacer(Modifier.height(18.dp))
                        if (s.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { s.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp),
                                strokeCap = StrokeCap.Round
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp),
                                strokeCap = StrokeCap.Round
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Verifying APK integrity automatically when complete…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    is AppUpdateState.ReadyToInstall -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (s.needsInstallPermission) {
                                        Icons.Rounded.Security
                                    } else {
                                        Icons.Rounded.CheckCircle
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (s.needsInstallPermission) {
                                        "Allow app installs"
                                    } else {
                                        "Ready to install"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Commit #${s.update.shortSha} verified",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = if (s.needsInstallPermission) {
                                "Enable \"Allow from this source\" for Lumina Gallery once so Android can install the downloaded update in-place."
                            } else {
                                "Your update is verified and ready. Tap Install now to update in-place while keeping all albums, favorites, and Drive sign-in intact."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = appUpdateManager::dismissPopup) {
                                Text("Close")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                shape = RoundedCornerShape(18.dp),
                                onClick = {
                                    if (s.needsInstallPermission) {
                                        appUpdateManager.openInstallUnknownAppsSettings(context)
                                    } else {
                                        appUpdateManager.launchDownloadedInstaller(context)
                                    }
                                }
                            ) {
                                Text(
                                    text = if (s.needsInstallPermission) "Open settings" else "Install now",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    is AppUpdateState.Error -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Update failed",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = s.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = appUpdateManager::dismissPopup) {
                                Text("Close")
                            }
                            if (s.update != null) {
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    shape = RoundedCornerShape(18.dp),
                                    onClick = {
                                        scope.launch {
                                            appUpdateManager.downloadAndInstallUpdate(context, s.update)
                                        }
                                    }
                                ) {
                                    Text("Retry", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun UpdateHeader(
    title: String,
    subtitle: String,
    shortSha: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "#$shortSha",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CommitChangelogCard(message: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "What's new in this commit",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
