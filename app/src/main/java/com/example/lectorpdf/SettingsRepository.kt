package com.example.lectorpdf

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** CORNER = se toma desde una esquina (con doblez). SLIDE = se desliza la página completa (sin doblez). */
enum class PageTurnMode { CORNER, SLIDE }

/** SINGLE = una página por pantalla. DOUBLE = dos páginas, como un libro abierto. */
enum class ViewMode { SINGLE, DOUBLE }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,          // predeterminado: seguir al sistema
    val pageTurnMode: PageTurnMode = PageTurnMode.CORNER,
    val viewMode: ViewMode = ViewMode.SINGLE,
    val rootFolderUri: String? = null
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val TURN = stringPreferencesKey("page_turn_mode")
        val VIEW = stringPreferencesKey("view_mode")
        val ROOT = stringPreferencesKey("root_folder_uri")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME]
                ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                ?: ThemeMode.SYSTEM,
            pageTurnMode = p[Keys.TURN]
                ?.let { runCatching { PageTurnMode.valueOf(it) }.getOrNull() }
                ?: PageTurnMode.CORNER,
            viewMode = p[Keys.VIEW]
                ?.let { runCatching { ViewMode.valueOf(it) }.getOrNull() }
                ?: ViewMode.SINGLE,
            rootFolderUri = p[Keys.ROOT]
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setPageTurnMode(mode: PageTurnMode) {
        context.dataStore.edit { it[Keys.TURN] = mode.name }
    }

    suspend fun setViewMode(mode: ViewMode) {
        context.dataStore.edit { it[Keys.VIEW] = mode.name }
    }

    suspend fun setRootFolder(uri: String) {
        context.dataStore.edit { it[Keys.ROOT] = uri }
    }

    // Para agregar más opciones: nueva Key + campo en AppSettings + setter aquí.
}
