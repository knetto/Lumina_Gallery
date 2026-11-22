package com.luminagallery.drive.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.luminagallery.drive.domain.model.AlbumSortMode
import com.luminagallery.drive.domain.model.ThemeMode
import com.luminagallery.drive.domain.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.galleryDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "lumina_gallery_preferences"
)

class PreferencesManager(private val context: Context) {

    val preferencesFlow: Flow<UserPreferences> = context.galleryDataStore.data.map { prefs ->
        val themeName = prefs[KEY_THEME_MODE] ?: ThemeMode.AMOLED_BLACK.name
        val themeMode = runCatching { ThemeMode.valueOf(themeName) }
            .getOrDefault(ThemeMode.AMOLED_BLACK)

        val sortName = prefs[KEY_ALBUM_SORT_MODE] ?: AlbumSortMode.CUSTOM.name
        val sortMode = runCatching { AlbumSortMode.valueOf(sortName) }
            .getOrDefault(AlbumSortMode.CUSTOM)

        UserPreferences(
            maxCacheSizeMb = (prefs[KEY_MAX_CACHE_MB] ?: DEFAULT_CACHE_MB).coerceIn(250, 2048),
            themeMode = themeMode,
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            gridColumns = (prefs[KEY_GRID_COLUMNS] ?: DEFAULT_GRID_COLUMNS).coerceIn(2, 5),
            backgroundSyncEnabled = prefs[KEY_BACKGROUND_SYNC] ?: false,
            albumSortMode = sortMode
        )
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

    companion object {
        const val DEFAULT_CACHE_MB = 500
        const val DEFAULT_GRID_COLUMNS = 4

        private val KEY_MAX_CACHE_MB = intPreferencesKey("max_cache_mb")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_GRID_COLUMNS = intPreferencesKey("grid_columns")
        private val KEY_BACKGROUND_SYNC = booleanPreferencesKey("background_sync_enabled")
        private val KEY_ALBUM_SORT_MODE = stringPreferencesKey("album_sort_mode")
    }
}
