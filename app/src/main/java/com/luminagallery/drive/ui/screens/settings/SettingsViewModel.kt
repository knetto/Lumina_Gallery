package com.luminagallery.drive.ui.screens.settings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.luminagallery.drive.AppContainer
import com.luminagallery.drive.data.local.PreferencesManager
import com.luminagallery.drive.data.remote.NetworkMonitor
import com.luminagallery.drive.data.remote.auth.CredentialStore
import com.luminagallery.drive.data.remote.auth.ServiceAccountTokenManager
import com.luminagallery.drive.data.repository.CoilCacheManager
import com.luminagallery.drive.data.repository.GalleryExtrasRepository
import com.luminagallery.drive.data.repository.GalleryRepository
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
    val networkState: NetworkState = NetworkState(),
    val hiddenAlbumCount: Int = 0,
    val ignoredCleanOutCount: Int = 0,
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
    private val syncUseCase: SyncGalleryUseCase,
    networkMonitor: NetworkMonitor
) : ViewModel() {

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
        extrasRepository.ignoredCleanOutIdsFlow
    ) { hidden, ignored -> hidden.size to ignored.size }

    private val localStatusFlow = combine(
        repository.syncStatusFlow,
        networkMonitor.networkState,
        extrasCountsFlow,
        isClearingCacheFlow,
        snackbarFlow
    ) { sync, net, (hiddenCount, ignoredCount), clearing, snack ->
        SettingsLocalStatus(sync, net, hiddenCount, ignoredCount, clearing, snack)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        credentialStore.state,
        preferencesManager.preferencesFlow,
        storageStatsFlow,
        localStatusFlow
    ) { account, prefs, stats, local ->
        SettingsUiState(
            serviceAccountState = account,
            preferences = prefs,
            storageStats = stats,
            syncStatus = local.syncStatus,
            networkState = local.networkState,
            hiddenAlbumCount = local.hiddenAlbumCount,
            ignoredCleanOutCount = local.ignoredCleanOutCount,
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

    fun onGoogleSignInSuccess(accessToken: String, fallbackEmail: String?) {
        viewModelScope.launch {
            tokenManager.cacheInteractiveOAuthToken(accessToken)
            credentialStore.saveGoogleSignInSession(
                email = fallbackEmail ?: "Google Account",
                displayName = null
            )
            repository.fetchSignedInUserProfile()
                .onSuccess { (email, displayName) ->
                    credentialStore.saveGoogleSignInSession(email, displayName)
                    snackbarFlow.value = "Signed in as $email"
                }
                .onFailure {
                    snackbarFlow.value = "Connected Google account"
                }
        }
    }

    fun signOutGoogleAccount() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                tokenManager.clearPlayServicesTokenBlocking()
            }
            credentialStore.clearGoogleSignInSession()
            credentialStore.clearImportedCredentials()
            tokenManager.invalidate()
            snackbarFlow.value = "Signed out of Google Drive"
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
            snackbarFlow.value = "Thumbnail cache limit set to ${if (sizeMb >= 1024) String.format("%.1f GB", sizeMb / 1024f) else "$sizeMb MB"}"
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

    private data class SettingsLocalStatus(
        val syncStatus: SyncStatus,
        val networkState: NetworkState,
        val hiddenAlbumCount: Int,
        val ignoredCleanOutCount: Int,
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
            syncUseCase = container.syncGalleryUseCase,
            networkMonitor = container.networkMonitor
        ) as T
    }
}
