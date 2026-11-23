package com.luminagallery.drive.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.MainTab
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.galleryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lumina_gallery_preferences"
)

class PreferencesManager(private val context: Context) {

    @Volatile
    var latestPreferences: UserPreferences = UserPreferences()
        private set

    val preferencesFlow: Flow<UserPreferences> = context.galleryDataStore.data.map { prefs ->
        val themeName = prefs[KEY_THEME_MODE] ?: ThemeMode.AMOLED_BLACK.name
        val themeMode = runCatching { ThemeMode.valueOf(themeName) }
            .getOrDefault(ThemeMode.AMOLED_BLACK)

        val sortName = prefs[KEY_ALBUM_SORT_MODE] ?: AlbumSortMode.CUSTOM.name
        val sortMode = runCatching { AlbumSortMode.valueOf(sortName) }
            .getOrDefault(AlbumSortMode.CUSTOM)

        val tabName = prefs[KEY_DEFAULT_START_TAB] ?: MainTab.ALBUMS.name
        val startTab = runCatching { MainTab.valueOf(tabName) }
            .getOrDefault(MainTab.ALBUMS)

        UserPreferences(
            maxCacheSizeMb = (prefs[KEY_MAX_CACHE_MB] ?: DEFAULT_CACHE_MB).coerceIn(250, 2048),
            themeMode = themeMode,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            gridColumns = (prefs[KEY_GRID_COLUMNS] ?: DEFAULT_GRID_COLUMNS).coerceIn(2, 5),
            backgroundSyncEnabled = prefs[KEY_BACKGROUND_SYNC] ?: false,
            albumSortMode = sortMode,
            defaultStartTab = startTab,
            showDayLocationHeaders = prefs[KEY_SHOW_DAY_LOCATIONS] ?: true,
            showAlbumSidebarDefault = prefs[KEY_SHOW_ALBUM_SIDEBAR] ?: true,
            showCloudBadges = prefs[KEY_SHOW_CLOUD_BADGES] ?: true,
            autoPlayVideos = prefs[KEY_AUTO_PLAY_VIDEOS] ?: true,
            startVideosMuted = prefs[KEY_START_VIDEOS_MUTED] ?: false,
            loopVideos = prefs[KEY_LOOP_VIDEOS] ?: false,
            showViewerFilmstrip = prefs[KEY_SHOW_VIEWER_FILMSTRIP] ?: true,
            maxViewerBrightness = prefs[KEY_MAX_VIEWER_BRIGHTNESS] ?: false,
            keepScreenOnViewer = prefs[KEY_KEEP_SCREEN_ON_VIEWER] ?: true,
            swipeDownToDismiss = prefs[KEY_SWIPE_DOWN_DISMISS] ?: true,
            prefetchAdjacentMedia = prefs[KEY_PREFETCH_ADJACENT] ?: true,
            wifiOnlyHighRes = prefs[KEY_WIFI_ONLY_HIGH_RES] ?: true,
            externalGalleryPackage = prefs[KEY_EXTERNAL_GALLERY_PACKAGE].orEmpty(),
            externalGalleryLabel = prefs[KEY_EXTERNAL_GALLERY_LABEL].orEmpty(),
            autoCheckUpdates = prefs[KEY_AUTO_CHECK_UPDATES] ?: true
        ).also { latestPreferences = it }
    }

    suspend fun getCurrentPreferences(): UserPreferences = preferencesFlow.first()

    suspend fun setMaxCacheSizeMb(sizeMb: Int) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_MAX_CACHE_MB] = sizeMb.coerceIn(250, 2048)
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setGridColumns(columns: Int) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_GRID_COLUMNS] = columns.coerceIn(2, 5)
        }
    }

    suspend fun setBackgroundSyncEnabled(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_BACKGROUND_SYNC] = enabled
        }
    }

    suspend fun setAlbumSortMode(mode: AlbumSortMode) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_ALBUM_SORT_MODE] = mode.name
        }
    }

    suspend fun setDefaultStartTab(tab: MainTab) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_DEFAULT_START_TAB] = tab.name
        }
    }

    suspend fun setShowDayLocationHeaders(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_SHOW_DAY_LOCATIONS] = enabled
        }
    }

    suspend fun setShowAlbumSidebarDefault(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_SHOW_ALBUM_SIDEBAR] = enabled
        }
    }

    suspend fun setShowCloudBadges(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_SHOW_CLOUD_BADGES] = enabled
        }
    }

    suspend fun setAutoPlayVideos(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_AUTO_PLAY_VIDEOS] = enabled
        }
    }

    suspend fun setStartVideosMuted(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_START_VIDEOS_MUTED] = enabled
        }
    }

    suspend fun setLoopVideos(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_LOOP_VIDEOS] = enabled
        }
    }

    suspend fun setShowViewerFilmstrip(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_SHOW_VIEWER_FILMSTRIP] = enabled
        }
    }

    suspend fun setMaxViewerBrightness(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_MAX_VIEWER_BRIGHTNESS] = enabled
        }
    }

    suspend fun setKeepScreenOnViewer(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_KEEP_SCREEN_ON_VIEWER] = enabled
        }
    }

    suspend fun setSwipeDownToDismiss(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_SWIPE_DOWN_DISMISS] = enabled
        }
    }

    suspend fun setPrefetchAdjacentMedia(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_PREFETCH_ADJACENT] = enabled
        }
    }

    suspend fun setWifiOnlyHighRes(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_WIFI_ONLY_HIGH_RES] = enabled
        }
    }

    suspend fun setExternalGallery(packageName: String, label: String) {
        context.galleryDataStore.edit { prefs ->
            if (packageName.isBlank()) {
                prefs.remove(KEY_EXTERNAL_GALLERY_PACKAGE)
                prefs.remove(KEY_EXTERNAL_GALLERY_LABEL)
            } else {
                prefs[KEY_EXTERNAL_GALLERY_PACKAGE] = packageName.trim()
                prefs[KEY_EXTERNAL_GALLERY_LABEL] = label.trim()
            }
        }
    }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_AUTO_CHECK_UPDATES] = enabled
        }
    }

    suspend fun getDismissedUpdateSha(): String =
        context.galleryDataStore.data.map { it[KEY_DISMISSED_UPDATE_SHA].orEmpty() }.first()

    suspend fun setDismissedUpdateSha(commitSha: String) {
        context.galleryDataStore.edit { prefs ->
            if (commitSha.isBlank()) {
                prefs.remove(KEY_DISMISSED_UPDATE_SHA)
            } else {
                prefs[KEY_DISMISSED_UPDATE_SHA] = commitSha.trim()
            }
        }
    }

    suspend fun getLastUpdateCheckEpochMs(): Long =
        context.galleryDataStore.data.map { it[KEY_LAST_UPDATE_CHECK_MS] ?: 0L }.first()

    suspend fun setLastUpdateCheckEpochMs(epochMs: Long) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_LAST_UPDATE_CHECK_MS] = epochMs
        }
    }

    val recentSearchesFlow: Flow<List<String>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[KEY_RECENT_SEARCHES].orEmpty()
        if (raw.isBlank()) emptyList() else raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    suspend fun addRecentSearch(query: String) {
        val clean = query.trim().replace("\n", " ")
        if (clean.length < 2) return
        context.galleryDataStore.edit { prefs ->
            val current = prefs[KEY_RECENT_SEARCHES].orEmpty()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(clean, ignoreCase = true) }
            val updated = (listOf(clean) + current).take(8)
            prefs[KEY_RECENT_SEARCHES] = updated.joinToString("\n")
        }
    }

    suspend fun removeRecentSearch(query: String) {
        context.galleryDataStore.edit { prefs ->
            val updated = prefs[KEY_RECENT_SEARCHES].orEmpty()
                .split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(query, ignoreCase = true) }
            prefs[KEY_RECENT_SEARCHES] = updated.joinToString("\n")
        }
    }

    suspend fun clearRecentSearches() {
        context.galleryDataStore.edit { prefs ->
            prefs.remove(KEY_RECENT_SEARCHES)
        }
    }

    /**
     * Atomically applies settings restored from Google Drive `appDataFolder` while keeping
     * device-local settings (like Samsung/Xiaomi external gallery package) untouched.
     */
    suspend fun applyCloudSyncedPreferences(synced: UserPreferences) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_MAX_CACHE_MB] = synced.maxCacheSizeMb.coerceIn(250, 2048)
            prefs[KEY_THEME_MODE] = synced.themeMode.name
            prefs[KEY_DYNAMIC_COLOR] = synced.dynamicColor
            prefs[KEY_GRID_COLUMNS] = synced.gridColumns.coerceIn(2, 5)
            prefs[KEY_BACKGROUND_SYNC] = synced.backgroundSyncEnabled
            prefs[KEY_ALBUM_SORT_MODE] = synced.albumSortMode.name
            prefs[KEY_DEFAULT_START_TAB] = synced.defaultStartTab.name
            prefs[KEY_SHOW_DAY_LOCATIONS] = synced.showDayLocationHeaders
            prefs[KEY_SHOW_ALBUM_SIDEBAR] = synced.showAlbumSidebarDefault
            prefs[KEY_SHOW_CLOUD_BADGES] = synced.showCloudBadges
            prefs[KEY_AUTO_PLAY_VIDEOS] = synced.autoPlayVideos
            prefs[KEY_START_VIDEOS_MUTED] = synced.startVideosMuted
            prefs[KEY_LOOP_VIDEOS] = synced.loopVideos
            prefs[KEY_SHOW_VIEWER_FILMSTRIP] = synced.showViewerFilmstrip
            prefs[KEY_MAX_VIEWER_BRIGHTNESS] = synced.maxViewerBrightness
            prefs[KEY_KEEP_SCREEN_ON_VIEWER] = synced.keepScreenOnViewer
            prefs[KEY_SWIPE_DOWN_DISMISS] = synced.swipeDownToDismiss
            prefs[KEY_PREFETCH_ADJACENT] = synced.prefetchAdjacentMedia
            prefs[KEY_WIFI_ONLY_HIGH_RES] = synced.wifiOnlyHighRes
            prefs[KEY_AUTO_CHECK_UPDATES] = synced.autoCheckUpdates
        }
    }

    suspend fun getLastCloudProfileSyncMs(): Long =
        context.galleryDataStore.data.map { it[KEY_LAST_CLOUD_PROFILE_SYNC_MS] ?: 0L }.first()

    suspend fun setLastCloudProfileSyncMs(epochMs: Long) {
        context.galleryDataStore.edit { prefs ->
            prefs[KEY_LAST_CLOUD_PROFILE_SYNC_MS] = epochMs
        }
    }

    suspend fun getPendingLocalCloudRulesJson(): String =
        context.galleryDataStore.data.map { it[KEY_PENDING_LOCAL_CLOUD_RULES].orEmpty() }.first()

    suspend fun setPendingLocalCloudRulesJson(rawJson: String) {
        context.galleryDataStore.edit { prefs ->
            if (rawJson.isBlank()) {
                prefs.remove(KEY_PENDING_LOCAL_CLOUD_RULES)
            } else {
                prefs[KEY_PENDING_LOCAL_CLOUD_RULES] = rawJson
            }
        }
    }

    companion object {
        const val DEFAULT_CACHE_MB = 500
        const val DEFAULT_GRID_COLUMNS = 4

        private val KEY_MAX_CACHE_MB = intPreferencesKey("max_cache_mb")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val KEY_BACKGROUND_SYNC = booleanPreferencesKey("background_sync_enabled")
        private val KEY_ALBUM_SORT_MODE = stringPreferencesKey("album_sort_mode")
        private val KEY_DEFAULT_START_TAB = stringPreferencesKey("default_start_tab")
        private val KEY_SHOW_DAY_LOCATIONS = booleanPreferencesKey("show_day_location_headers")
        private val KEY_SHOW_ALBUM_SIDEBAR = booleanPreferencesKey("show_album_sidebar_default")
        private val KEY_SHOW_CLOUD_BADGES = booleanPreferencesKey("show_cloud_badges")
        private val KEY_AUTO_PLAY_VIDEOS = booleanPreferencesKey("auto_play_videos")
        private val KEY_START_VIDEOS_MUTED = booleanPreferencesKey("start_videos_muted")
        private val KEY_LOOP_VIDEOS = booleanPreferencesKey("loop_videos")
        private val KEY_SHOW_VIEWER_FILMSTRIP = booleanPreferencesKey("show_viewer_filmstrip")
        private val KEY_MAX_VIEWER_BRIGHTNESS = booleanPreferencesKey("max_viewer_brightness")
        private val KEY_KEEP_SCREEN_ON_VIEWER = booleanPreferencesKey("keep_screen_on_viewer")
        private val KEY_SWIPE_DOWN_DISMISS = booleanPreferencesKey("swipe_down_to_dismiss")
        private val KEY_PREFETCH_ADJACENT = booleanPreferencesKey("prefetch_adjacent_media")
        private val KEY_WIFI_ONLY_HIGH_RES = booleanPreferencesKey("wifi_only_high_res")
        private val KEY_EXTERNAL_GALLERY_PACKAGE = stringPreferencesKey("external_gallery_package")
        private val KEY_EXTERNAL_GALLERY_LABEL = stringPreferencesKey("external_gallery_label")
        private val KEY_AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        private val KEY_DISMISSED_UPDATE_SHA = stringPreferencesKey("dismissed_update_sha")
        private val KEY_LAST_UPDATE_CHECK_MS = longPreferencesKey("last_update_check_ms")
        private val KEY_RECENT_SEARCHES = stringPreferencesKey("recent_searches")
        private val KEY_LAST_CLOUD_PROFILE_SYNC_MS = longPreferencesKey("last_cloud_profile_sync_ms")
        private val KEY_PENDING_LOCAL_CLOUD_RULES = stringPreferencesKey("pending_local_cloud_rules")
    }
}
