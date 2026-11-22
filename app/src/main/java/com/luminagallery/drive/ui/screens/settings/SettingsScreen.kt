package com.luminagallery.drive.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.Identity
import com.luminagallery.drive.LocalAppContainer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.luminagallery.drive.domain.model.CredentialSource
import com.luminagallery.drive.domain.model.ServiceAccountState
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.ui.components.FormatUtils
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appContainer = LocalAppContainer.current
    val snackbarHostState = remember { SnackbarHostState() }

    var sliderCacheMb by remember(uiState.preferences.maxCacheSizeMb) {
        mutableFloatStateOf(uiState.preferences.maxCacheSizeMb.toFloat())
    }

    val googleAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        runCatching {
            val authResult = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(activityResult.data)
            val token = authResult.accessToken
            if (!token.isNullOrBlank()) {
                viewModel.onGoogleSignInSuccess(
                    accessToken = token,
                    fallbackEmail = authResult.toGoogleSignInAccount()?.email
                )
            } else {
                viewModel.showMessage("Google sign-in cancelled")
            }
        }.onFailure { err ->
            viewModel.showMessage(err.localizedMessage ?: "Google Sign-In failed")
        }
    }

    fun startGoogleSignIn() {
        val request = appContainer.tokenManager.buildGoogleAuthorizationRequest()
        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    result.pendingIntent?.let { pi ->
                        googleAuthLauncher.launch(
                            IntentSenderRequest.Builder(pi.intentSender).build()
                        )
                    }
                } else {
                    val token = result.accessToken
                    if (!token.isNullOrBlank()) {
                        viewModel.onGoogleSignInSuccess(
                            accessToken = token,
                            fallbackEmail = result.toGoogleSignInAccount()?.email
                        )
                    }
                }
            }
            .addOnFailureListener { err ->
                viewModel.showMessage(err.localizedMessage ?: "Google Sign-In failed")
            }
    }

    val credentialsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importCredentials(uri)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearSnackbar()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Storage Manager Card
            item(key = "section_storage") {
                SettingsCard(
                    title = "Storage Manager",
                    subtitle = "Thumbnails use an LRU disk cache; full-res photos & videos never persist on disk",
                    icon = Icons.Rounded.Storage
                ) {
                    val maxBytes = (uiState.storageStats.maxCacheLimitMb.toLong() * 1024L * 1024L)
                        .coerceAtLeast(1L)
                    val usedRatio = (uiState.storageStats.diskCacheUsedBytes.toFloat() / maxBytes.toFloat())
                        .coerceIn(0f, 1f)
                    val animatedRatio by animateFloatAsState(
                        targetValue = usedRatio,
                        label = "cacheUsageBar"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = FormatUtils.formatFileSize(uiState.storageStats.diskCacheUsedBytes),
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Text(
                                text = "of ${formatMbLabel(uiState.storageStats.maxCacheLimitMb)} thumbnail cache",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${uiState.storageStats.cachedMediaCount} items · ${uiState.storageStats.cachedFolderCount} folders",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    LinearProgressIndicator(
                        progress = { animatedRatio },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        strokeCap = StrokeCap.Round
                    )

                    Spacer(Modifier.height(4.dp))

                    // Max cache limit slider (250 MB – 2 GB)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Max thumbnail cache cap",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = formatMbLabel(sliderCacheMb.roundToInt()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Slider(
                        value = sliderCacheMb,
                        onValueChange = { raw ->
                            sliderCacheMb = (raw / 50f).roundToInt() * 50f
                        },
                        onValueChangeFinished = {
                            val committed = sliderCacheMb.roundToInt().coerceIn(250, 2048)
                            if (committed != uiState.preferences.maxCacheSizeMb) {
                                viewModel.updateMaxCacheSizeMb(committed)
                            }
                        },
                        valueRange = 250f..2048f
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("250 MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("1 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("2 GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = viewModel::clearThumbnailCache,
                            enabled = !uiState.isClearingCache,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isClearingCache) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.CleaningServices,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Clear Thumbnail Cache")
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = viewModel::rebuildMetadataIndex,
                        enabled = !uiState.syncStatus.isSyncing,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Rebuild Local Metadata Index")
                    }
                }
            }

            // 2. Battery & Network Optimization Card
            item(key = "section_power") {
                SettingsCard(
                    title = "Battery & Sync Optimization",
                    subtitle = "Zero background wakeups by default; incremental Drive changes.list delta sync",
                    icon = Icons.Rounded.BatteryChargingFull
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.networkState.isUnmeteredWifi) {
                                Icons.Rounded.Wifi
                            } else {
                                Icons.Rounded.SignalCellularAlt
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (uiState.networkState.isUnmeteredWifi) {
                                    "Unmetered Wi-Fi Active"
                                } else if (uiState.networkState.isOnline) {
                                    "Metered Cellular Active"
                                } else {
                                    "Offline Mode"
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (uiState.networkState.isUnmeteredWifi) {
                                    "Full-res ±1 pre-fetch & high-DPI thumbnails enabled"
                                } else {
                                    "Full-res pre-fetch paused; eco thumbnails active to save radio power"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Background Delta Sync",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Optional WorkManager sync strictly while charging on unmetered Wi-Fi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.preferences.backgroundSyncEnabled,
                            onCheckedChange = viewModel::setBackgroundSyncEnabled
                        )
                    }

                    FilledTonalButton(
                        onClick = viewModel::triggerDeltaSync,
                        enabled = !uiState.syncStatus.isSyncing,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (uiState.syncStatus.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Syncing delta changes…")
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Sync Delta Now · ${FormatUtils.formatLastSync(uiState.syncStatus.lastSyncEpochMs)}")
                        }
                    }
                }
            }

            // 3. Appearance & OLED Efficiency Card
            item(key = "section_appearance") {
                SettingsCard(
                    title = "Appearance & OLED",
                    subtitle = "Pitch black #000000 saves OLED display power",
                    icon = Icons.Rounded.Palette
                ) {
                    val modes = listOf(
                        ThemeMode.AMOLED_BLACK to "AMOLED",
                        ThemeMode.DARK to "Dark",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.SYSTEM to "System"
                    )

                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        modes.forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = uiState.preferences.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                            ) {
                                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Dynamic Color (Material You)",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Adapts accent palette from wallpaper while keeping #000000 AMOLED black",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = uiState.preferences.dynamicColor,
                                onCheckedChange = viewModel::setDynamicColor
                            )
                        }
                    }
                }
            }

            // 4. Google Drive Account & Credentials Card
            item(key = "section_credentials") {
                SettingsCard(
                    title = "Google Drive Account",
                    subtitle = "Read-only Google Drive access (drive.readonly)",
                    icon = Icons.Rounded.Key
                ) {
                    when (val account = uiState.serviceAccountState) {
                        ServiceAccountState.NotConfigured -> {
                            Text(
                                text = "Not signed in to Google Drive yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        is ServiceAccountState.Configured -> {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val clipboard = context.getSystemService(
                                            Context.CLIPBOARD_SERVICE
                                        ) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText(
                                                "Google Account Email",
                                                account.clientEmail
                                            )
                                        )
                                        viewModel.showMessage("Copied email to clipboard")
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = account.clientEmail,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        val sourceLabel = when (account.source) {
                                            CredentialSource.GOOGLE_SIGN_IN ->
                                                "Native Google Sign-In (${account.projectId})"
                                            CredentialSource.ENCRYPTED_PREFS ->
                                                "Service Account · EncryptedSharedPreferences"
                                            CredentialSource.BUNDLED_ASSETS ->
                                                "Service Account · Bundled Assets"
                                        }
                                        Text(
                                            text = sourceLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy email",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    Button(
                        onClick = ::startGoogleSignIn,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AccountCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign in with Google")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                credentialsPickerLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import JSON")
                        }

                        val activeSource = (uiState.serviceAccountState as? ServiceAccountState.Configured)?.source
                        if (activeSource == CredentialSource.GOOGLE_SIGN_IN ||
                            activeSource == CredentialSource.ENCRYPTED_PREFS
                        ) {
                            OutlinedButton(
                                onClick = {
                                    if (activeSource == CredentialSource.GOOGLE_SIGN_IN) {
                                        viewModel.signOutGoogleAccount()
                                    } else {
                                        viewModel.clearImportedCredentials()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (activeSource == CredentialSource.GOOGLE_SIGN_IN) "Sign Out" else "Remove")
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            content()
        }
    }
}

private fun formatMbLabel(mb: Int): String =
    if (mb >= 1024) String.format(Locale.US, "%.2f GB", mb / 1024f) else "$mb MB"
