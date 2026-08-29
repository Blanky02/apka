package dev.blanky.vinyl.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.blanky.vinyl.data.model.AudioQuality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "vinyl_settings")

/** Preferencje aplikacji (DataStore, bez blokad UI). */
class VinylSettings(private val context: Context) {

    private object Keys {
        val QUALITY = stringPreferencesKey("preferred_quality")
        val THEME = stringPreferencesKey("theme_mode")
        val OCTAVE_ENABLED = stringPreferencesKey("octave_enabled")
        val OCTAVE_BASE = stringPreferencesKey("octave_base")
        val OCTAVE_SEARCH = stringPreferencesKey("octave_search_template")
        val OCTAVE_STREAM = stringPreferencesKey("octave_stream_template")
        val OCTAVE_KEY = stringPreferencesKey("octave_account_key")
        val OCTAVE_LOGIN = stringPreferencesKey("octave_login_template")
    }

    val preferredQuality: Flow<AudioQuality> = context.settingsStore.data
        .map { p -> AudioQuality.fromName(p[Keys.QUALITY]) }

    /** "system" | "dark" | "light" */
    val themeMode: Flow<String> = context.settingsStore.data.map { p -> p[Keys.THEME] ?: "dark" }

    val octaveEnabled: Flow<Boolean> = context.settingsStore.data
        .map { p -> (p[Keys.OCTAVE_ENABLED] ?: "true") != "false" }

    val octaveBase: Flow<String> = context.settingsStore.data
        .map { p -> p[Keys.OCTAVE_BASE] ?: DEFAULT_OCTAVE_BASE }

    val octaveSearchTemplate: Flow<String> = context.settingsStore.data
        .map { p -> p[Keys.OCTAVE_SEARCH] ?: DEFAULT_OCTAVE_SEARCH }

    val octaveStreamTemplate: Flow<String> = context.settingsStore.data
        .map { p -> p[Keys.OCTAVE_STREAM] ?: DEFAULT_OCTAVE_STREAM }

    /** Klucz konta Octave (frasa odzyskiwania z octavestreaming.com). Pusty = brak. */
    val octaveKey: Flow<String> = context.settingsStore.data
        .map { p -> p[Keys.OCTAVE_KEY] ?: "" }

    /** Własny szablon endpointu logowania (metoda+ścieżka), np. "POST /api/account/login". */
    val octaveLoginTemplate: Flow<String> = context.settingsStore.data
        .map { p -> p[Keys.OCTAVE_LOGIN] ?: "" }

    suspend fun setPreferredQuality(quality: AudioQuality) =
        context.settingsStore.edit { it[Keys.QUALITY] = quality.name }

    suspend fun setThemeMode(mode: String) =
        context.settingsStore.edit { it[Keys.THEME] = mode }

    suspend fun setOctaveEnabled(enabled: Boolean) =
        context.settingsStore.edit { it[Keys.OCTAVE_ENABLED] = enabled.toString() }

    suspend fun setOctaveBase(url: String) =
        context.settingsStore.edit { it[Keys.OCTAVE_BASE] = url.trim().trimEnd('/') }

    suspend fun setOctaveSearchTemplate(template: String) =
        context.settingsStore.edit { it[Keys.OCTAVE_SEARCH] = template }

    suspend fun setOctaveStreamTemplate(template: String) =
        context.settingsStore.edit { it[Keys.OCTAVE_STREAM] = template }

    suspend fun setOctaveKey(key: String) =
        context.settingsStore.edit { it[Keys.OCTAVE_KEY] = key.trim() }

    suspend fun setOctaveLoginTemplate(template: String) =
        context.settingsStore.edit { it[Keys.OCTAVE_LOGIN] = template.trim() }

    companion object {
        const val DEFAULT_OCTAVE_BASE = "https://api.octavestreaming.com"
        const val DEFAULT_OCTAVE_SEARCH = "/api/search?q={query}"
        const val DEFAULT_OCTAVE_STREAM = "/api/track/{id}/stream"
    }
}
