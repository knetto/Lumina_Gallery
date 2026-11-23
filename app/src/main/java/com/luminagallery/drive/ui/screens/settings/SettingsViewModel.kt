package com.luminagallery.drive.ui.screens.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.remote.auth.CredentialStore
import com.luminagallery.drive.data.remote.auth.ServiceAccountTokenManager
import com.luminagallery.drive.data.repository.AppUpdateManager
import com.luminagallery.drive.data.repository.AppUpdateState
import com.luminagallery.drive.data.repository.CloudProfileSyncManager
import com.luminagallery.drive.data.repository.CloudProfileSyncStatus
import com.luminagallery.drive.data.repository.CoilCacheManager
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
import com.luminagallery.drive.data.repository.OfflineMediaManager
import com.luminagallery.drive.data.repository.sync.DriveSyncScheduler
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.MainTab
import com.luminagallery.drive.domain.model.NetworkState
import com.luminagallery.drive.domain.model.ServiceAccountState
import com.luminagallery.drive.domain.model.StorageStats
import com.luminagallery.drive.domain.model.SyncStatus
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import com.luminagallery.drive.domain.usecase.SyncGalleryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Immutable
data class SettingsUiState(
    val serviceAccountState: ServiceAccountState = ServiceAccountState.NotConfigured,
    val preferences: UserPreferences = UserPreferences(),
    val storageStats: StorageStats = StorageStats(
        diskCacheUsedBytes = 0L,
        maxCacheLimitMb = PreferencesManager.DEFAULT_CACHE_MB,
        cachedMediaCount = 0,
        cachedFolderCount = 0
    ),
    val syncStatus: SyncStatus = SyncStatus(),
    val cloudProfileSyncStatus: CloudProfileSyncStatus = CloudProfileSyncStatus(),
    val networkState: NetworkState = NetworkState(),
    val hiddenAlbumCount: Int = 0,
    val ignoredCleanOutCount: Int = 0,
    val offlinePinnedCount: Int = 0,
    val offlinePinnedBytes: Long = 0L,
    val isClearingCache: Boolean = false,
    val snackbarMessage: String? = null
)

class SettingsViewModel(
    private val appContext: Context,
    private val credentialStore: CredentialStore,
    private val tokenManager: ServiceAccountTokenManager,
    private val preferencesManager: PreferencesManager,
    private val coilCacheManager: CoilCacheManager,
    private val repository: GalleryRepository,
    private val extrasRepository: GalleryExtrasRepository,
    private val offlineMediaManager: OfflineMediaManager,
    private val appUpdateManager: AppUpdateManager,
    private val cloudProfileSyncManager: CloudProfileSyncManager,
    private val syncUseCase: SyncGalleryUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

    val updateState: StateFlow<AppUpdateState> = appUpdateManager.state

    private val isClearingCacheFlow = MutableStateFlow(false)
    private val snackbarFlow = MutableStateFlow<String?>(null)

    private val storageStatsFlow = combine(
        coilCacheManager.diskUsageBytesFlow,
        preferencesManager.preferencesFlow,
        repository.observeCachedMediaCount(),
        repository.observeCachedFolderCount()
    ) { diskBytes, prefs, mediaCount, folderCount ->
        StorageStats(
            diskCacheUsedBytes = diskBytes,
            maxCacheLimitMb = prefs.maxCacheSizeMb,
            cachedMediaCount = mediaCount,
            cachedFolderCount = folderCount
        )
    }

    private val extrasCountsFlow = combine(
        extrasRepository.hiddenAlbumIdsFlow,
        extrasRepository.ignoredCleanOutIdsFlow,
        offlineMediaManager.offlineItemsFlow
    ) { hidden, ignored, offlineList ->
        ExtrasSummary(hidden.size, ignored.size, offlineList.size, offlineList.sumOf { it.sizeBytes })
    }

    private val localStatusFlow = combine(
        repository.syncStatusFlow,
        networkMonitor.networkState,
        extrasCountsFlow,
        isClearingCacheFlow,
        snackbarFlow
    ) { sync, net, summary, clearing, snack ->
        SettingsLocalStatus(
            syncStatus = sync,
            networkState = net,
            hiddenAlbumCount = summary.hiddenCount,
            ignoredCleanOutCount = summary.ignoredCount,
            offlineCount = summary.offlineCount,
            offlineBytes = summary.offlineBytes,
            isClearingCache = clearing,
            snackbarMessage = snack
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        credentialStore.state,
        preferencesManager.preferencesFlow,
        storageStatsFlow,
        localStatusFlow,
        cloudProfileSyncManager.status
    ) { account, prefs, stats, local, cloudSync ->
        SettingsUiState(
            serviceAccountState = account,
            preferences = prefs,
            storageStats = stats,
            syncStatus = local.syncStatus,
            cloudProfileSyncStatus = cloudSync,
            networkState = local.networkState,
            hiddenAlbumCount = local.hiddenAlbumCount,
            ignoredCleanOutCount = local.ignoredCleanOutCount,
            offlinePinnedCount = local.offlineCount,
            offlinePinnedBytes = local.offlineBytes,
            isClearingCache = local.isClearingCache,
            snackbarMessage = local.snackbarMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = SettingsUiState(
            serviceAccountState = credentialStore.state.value,
            networkState = networkMonitor.networkState.value
        )
    )

    init {
        refreshDiskUsage()
    }

    fun refreshDiskUsage() {
        viewModelScope.launch {
            coilCacheManager.refreshDiskUsage()
        }
    }

    // --- Cloud & Sync ---

    fun setBackgroundSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setBackgroundSyncEnabled(enabled)
            DriveSyncScheduler.setBackgroundSyncEnabled(appContext, enabled)
            snackbarFlow.value = if (enabled) {
                "Background sync scheduled (charging on Wi-Fi)"
            } else {
                "Background sync turned off"
            }
        }
    }

    fun setWifiOnlyHighRes(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setWifiOnlyHighRes(enabled)
        }
    }

    fun triggerDeltaSync() {
        viewModelScope.launch {
            syncUseCase.syncDeltaOrInitial(forceFullRescan = false)
                .onSuccess { snackbarFlow.value = "Google Drive synced" }
                .onFailure { err -> snackbarFlow.value = err.message ?: "Sync failed" }
        }
    }

    fun onGoogleSignInSuccess(
        accessToken: String,
        fallbackEmail: String?,
        grantedAppDataScope: Boolean = true
    ) {
        viewModelScope.launch {
            tokenManager.cacheInteractiveOAuthToken(
                accessToken = accessToken,
                grantedAppDataScope = grantedAppDataScope
            )
            credentialStore.saveGoogleSignInSession(
                email = fallbackEmail ?: "Google Account",
                displayName = null
            )
            var resolvedEmail = fallbackEmail
            repository.fetchSignedInUserProfile()
                .onSuccess { (email, displayName) ->
                    resolvedEmail = email
                    credentialStore.saveGoogleSignInSession(email, displayName)
                }

            val restoreResult = cloudProfileSyncManager.syncOnSignIn()
            val summary = restoreResult.getOrNull()
            if (summary != null && summary.restoredFromCloud) {
                val parts = buildList {
                    if (summary.restoredPinnedRootsCount > 0) {
                        add("${summary.restoredPinnedRootsCount} Drive album(s)")
                    }
                    if (summary.restoredCollectionsCount > 0) {
                        add("${summary.restoredCollectionsCount} group(s)")
                    }
                }
                snackbarFlow.value = if (parts.isNotEmpty()) {
                    "Restored settings & ${parts.joinToString(", ")} from Google"
                } else {
                    "Restored settings & layout from Google"
                }
                if (summary.restoredPinnedRootsCount > 0) {
                    launch { syncUseCase.syncDeltaOrInitial(forceFullRescan = false, waitIfBusy = true) }
                }
            } else if (!grantedAppDataScope) {
                snackbarFlow.value = "Connected Google Drive · allow app data permission to enable backup"
            } else if (restoreResult.isSuccess) {
                snackbarFlow.value = if (!resolvedEmail.isNullOrBlank()) {
                    "Signed in as $resolvedEmail · Account backup active"
                } else {
                    "Connected Google account · Backup active"
                }
            } else {
                snackbarFlow.value = if (!resolvedEmail.isNullOrBlank()) {
                    "Signed in as $resolvedEmail"
                } else {
                    "Connected Google account"
                }
            }
        }
    }

    fun backupCloudProfileNow() {
        viewModelScope.launch {
            cloudProfileSyncManager.backupToCloudNow()
                .onSuccess {
                    snackbarFlow.value = "Backed up settings, Drive links & album groups to Google"
                }
                .onFailure { err ->
                    snackbarFlow.value = err.message ?: "Account backup failed"
                }
        }
    }

    fun restoreCloudProfileNow() {
        viewModelScope.launch {
            cloudProfileSyncManager.restoreFromCloudNow()
                .onSuccess { summary ->
                    val parts = buildList {
                        if (summary.restoredPinnedRootsCount > 0) {
                            add("${summary.restoredPinnedRootsCount} Drive album(s)")
                        }
                        if (summary.restoredCollectionsCount > 0) {
                            add("${summary.restoredCollectionsCount} group(s)")
                        }
                    }
                    snackbarFlow.value = if (parts.isNotEmpty()) {
                        "Restored settings & ${parts.joinToString(", ")} from Google"
                    } else {
                        "Restored settings & layout from Google"
                    }
                    if (summary.restoredPinnedRootsCount > 0) {
                        launch { syncUseCase.syncDeltaOrInitial(forceFullRescan = false, waitIfBusy = true) }
                    }
                }
                .onFailure { err ->
                    snackbarFlow.value = err.message ?: "Restore failed"
                }
        }
    }

    fun exportLocalProfileBackup(uri: Uri) {
        viewModelScope.launch {
            cloudProfileSyncManager.exportProfileToUri(appContext, uri)
                .onSuccess { snackbarFlow.value = "Exported Lumina backup file" }
                .onFailure { err -> snackbarFlow.value = err.message ?: "Export failed" }
        }
    }

    fun importLocalProfileBackup(uri: Uri) {
        viewModelScope.launch {
            cloudProfileSyncManager.importProfileFromUri(appContext, uri)
                .onSuccess { summary ->
                    snackbarFlow.value = "Imported settings, ${summary.restoredCollectionsCount} group(s) & ${summary.restoredPinnedRootsCount} Drive album(s)"
                    if (summary.restoredPinnedRootsCount > 0 && credentialStore.state.value is ServiceAccountState.Configured) {
                        launch { syncUseCase.syncDeltaOrInitial(forceFullRescan = false, waitIfBusy = true) }
                    }
                }
                .onFailure { err -> snackbarFlow.value = err.message ?: "Import failed" }
        }
    }

    fun setLockHiddenAlbums(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setLockHiddenAlbums(enabled)
            snackbarFlow.value = if (enabled) {
                "Hidden albums locked with biometrics / screen lock"
            } else {
                "Hidden albums lock turned off"
            }
        }
    }

    fun signOutGoogleAccount() {
        viewModelScope.launch {
            cloudProfileSyncManager.onSignedOut()
            DriveSyncScheduler.setBackgroundSyncEnabled(appContext, false)
            preferencesManager.setBackgroundSyncEnabled(false)
            withContext(Dispatchers.IO) {
                tokenManager.clearPlayServicesTokenBlocking()
            }
            credentialStore.clearGoogleSignInSession()
            credentialStore.clearImportedCredentials()
            tokenManager.invalidate()
            // Purge all Drive account data, offline downloads, and Drive thumbnails so the previous
            // Google account's private metadata never lingers after sign-out.
            repository.clearDriveAccountData()
            offlineMediaManager.clearAllOffline()
            coilCacheManager.clearThumbnailCache()
            snackbarFlow.value = "Signed out and cleared Google Drive cache"
        }
    }

    // --- Display & Layout ---

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferencesManager.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setDynamicColor(enabled) }
    }

    fun setDefaultStartTab(tab: MainTab) {
        viewModelScope.launch { preferencesManager.setDefaultStartTab(tab) }
    }

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { preferencesManager.setGridColumns(columns) }
    }

    fun setAlbumSortMode(mode: AlbumSortMode) {
        viewModelScope.launch { preferencesManager.setAlbumSortMode(mode) }
    }

    fun setShowDayLocationHeaders(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setShowDayLocationHeaders(enabled) }
    }

    fun setShowAlbumSidebarDefault(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setShowAlbumSidebarDefault(enabled) }
    }

    fun setShowCloudBadges(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setShowCloudBadges(enabled) }
    }

    // --- Viewer & Playback ---

    fun setAutoPlayVideos(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setAutoPlayVideos(enabled) }
    }

    fun setStartVideosMuted(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setStartVideosMuted(enabled) }
    }

    fun setLoopVideos(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setLoopVideos(enabled) }
    }

    fun setShowViewerFilmstrip(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setShowViewerFilmstrip(enabled) }
    }

    fun setMaxViewerBrightness(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setMaxViewerBrightness(enabled) }
    }

    fun setKeepScreenOnViewer(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setKeepScreenOnViewer(enabled) }
    }

    fun setSwipeDownToDismiss(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setSwipeDownToDismiss(enabled) }
    }

    fun setPrefetchAdjacentMedia(enabled: Boolean) {
        viewModelScope.launch { preferencesManager.setPrefetchAdjacentMedia(enabled) }
    }

    // --- Storage & Maintenance ---

    fun updateMaxCacheSizeMb(sizeMb: Int) {
        viewModelScope.launch {
            preferencesManager.setMaxCacheSizeMb(sizeMb)
            coilCacheManager.updateMaxCacheSizeMb(sizeMb)
            snackbarFlow.value = "Thumbnail cache limit set to ${if (sizeMb >= 1024) String.format(Locale.US, "%.1f GB", sizeMb / 1024f) else "$sizeMb MB"}"
        }
    }

    fun clearThumbnailCache() {
        if (isClearingCacheFlow.value) return
        viewModelScope.launch {
            isClearingCacheFlow.value = true
            coilCacheManager.clearThumbnailCache()
            isClearingCacheFlow.value = false
            snackbarFlow.value = "Thumbnail cache cleared"
        }
    }

    fun rebuildMetadataIndex() {
        viewModelScope.launch {
            repository.resetMetadataCache()
            syncUseCase.syncDeltaOrInitial(forceFullRescan = true)
                .onSuccess { snackbarFlow.value = "Rebuilt Google Drive folder index" }
                .onFailure { err -> snackbarFlow.value = err.message ?: "Rebuild failed" }
        }
    }

    fun resetIgnoredCleanOut() {
        viewModelScope.launch {
            extrasRepository.clearIgnoredCleanOut()
            snackbarFlow.value = "Restored dismissed Clean Out suggestions"
        }
    }

    fun clearAllOfflineMedia() {
        viewModelScope.launch {
            offlineMediaManager.clearAllOffline()
            snackbarFlow.value = "Removed all offline Google Drive downloads"
        }
    }

    fun setExternalGallery(packageName: String, label: String) {
        viewModelScope.launch {
            preferencesManager.setExternalGallery(packageName, label)
            snackbarFlow.value = if (packageName.isBlank()) {
                "External gallery set to Ask every time"
            } else {
                "External gallery set to $label"
            }
        }
    }

    fun setAutoCheckUpdates(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setAutoCheckUpdates(enabled)
        }
    }

    fun checkForUpdatesManually() {
        viewModelScope.launch {
            when (val result = appUpdateManager.checkForUpdate(manual = true)) {
                is AppUpdateState.UpToDate -> {
                    snackbarFlow.value = "You're already on the latest GitHub build"
                }
                is AppUpdateState.Error -> {
                    snackbarFlow.value = result.message
                }
                else -> Unit
            }
        }
    }

    fun resetSettingsToDefaults() {
        viewModelScope.launch {
            val defaults = UserPreferences()
            preferencesManager.setThemeMode(defaults.themeMode)
            preferencesManager.setDynamicColor(defaults.dynamicColor)
            preferencesManager.setDefaultStartTab(defaults.defaultStartTab)
            preferencesManager.setGridColumns(defaults.gridColumns)
            preferencesManager.setAlbumSortMode(defaults.albumSortMode)
            preferencesManager.setShowDayLocationHeaders(defaults.showDayLocationHeaders)
            preferencesManager.setShowAlbumSidebarDefault(defaults.showAlbumSidebarDefault)
            preferencesManager.setShowCloudBadges(defaults.showCloudBadges)
            preferencesManager.setAutoPlayVideos(defaults.autoPlayVideos)
            preferencesManager.setStartVideosMuted(defaults.startVideosMuted)
            preferencesManager.setLoopVideos(defaults.loopVideos)
            preferencesManager.setShowViewerFilmstrip(defaults.showViewerFilmstrip)
            preferencesManager.setMaxViewerBrightness(defaults.maxViewerBrightness)
            preferencesManager.setKeepScreenOnViewer(defaults.keepScreenOnViewer)
            preferencesManager.setSwipeDownToDismiss(defaults.swipeDownToDismiss)
            preferencesManager.setPrefetchAdjacentMedia(defaults.prefetchAdjacentMedia)
            preferencesManager.setWifiOnlyHighRes(defaults.wifiOnlyHighRes)
            preferencesManager.setExternalGallery(defaults.externalGalleryPackage, defaults.externalGalleryLabel)
            preferencesManager.setAutoCheckUpdates(defaults.autoCheckUpdates)
            // Never reset `lockHiddenAlbums` here — turning off biometric lock must always require
            // explicit biometric authentication in Privacy & Security.
            preferencesManager.setBackgroundSyncEnabled(defaults.backgroundSyncEnabled)
            DriveSyncScheduler.setBackgroundSyncEnabled(appContext, false)
            preferencesManager.setMaxCacheSizeMb(defaults.maxCacheSizeMb)
            coilCacheManager.updateMaxCacheSizeMb(defaults.maxCacheSizeMb)
            snackbarFlow.value = "Settings restored to defaults"
        }
    }

    fun showMessage(message: String) {
        snackbarFlow.value = message
    }

    fun clearSnackbar() {
        snackbarFlow.value = null
    }

    private data class ExtrasSummary(
        val hiddenCount: Int,
        val ignoredCount: Int,
        val offlineCount: Int,
        val offlineBytes: Long
    )

    private data class SettingsLocalStatus(
        val syncStatus: SyncStatus,
        val networkState: NetworkState,
        val hiddenAlbumCount: Int,
        val ignoredCleanOutCount: Int,
        val offlineCount: Int,
        val offlineBytes: Long,
        val isClearingCache: Boolean,
        val snackbarMessage: String?
    )

    class Factory(
        private val appContext: Context,
        private val container: AppContainer
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(
            appContext = appContext.applicationContext,
            credentialStore = container.credentialStore,
            tokenManager = container.tokenManager,
            preferencesManager = container.preferencesManager,
            coilCacheManager = container.coilCacheManager,
            repository = container.galleryRepository,
            extrasRepository = container.galleryExtrasRepository,
            offlineMediaManager = container.offlineMediaManager,
            appUpdateManager = container.appUpdateManager,
            cloudProfileSyncManager = container.cloudProfileSyncManager,
            syncUseCase = container.syncGalleryUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}
