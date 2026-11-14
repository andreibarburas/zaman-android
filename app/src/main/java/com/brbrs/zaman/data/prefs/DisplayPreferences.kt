package com.brbrs.zaman.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Text-size steps, same labels and relative scale as the rest of the suite (e.g. Vinci). */
enum class TextScale(val label: String, val multiplier: Float) {
    SMALL("Small", 0.9f),
    DEFAULT("Default", 1.0f),
    LARGE("Large", 1.15f),
    EXTRA_LARGE("Extra Large", 1.3f),
}

enum class ThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    OLED("OLED"),
    SYSTEM("System"),
}

/**
 * Display preferences shared with the rest of the suite's own settings screens (e.g. Vinci's
 * Display section): theme, text size, and whether to use the suite's custom font pairing or
 * fall back to the system fonts. Kept in its own DataStore, separate from auth, so clearing one
 * never touches the other.
 */
class DisplayPreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val TEXT_SCALE = stringPreferencesKey("text_scale")
        val CUSTOM_FONT_ENABLED = booleanPreferencesKey("custom_font_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val HIDDEN_CALENDAR_URLS = stringSetPreferencesKey("hidden_calendar_urls")
    }

    val textScale: Flow<TextScale> = store.data.map { prefs ->
        prefs[Keys.TEXT_SCALE]?.let { name -> TextScale.entries.find { it.name == name } } ?: TextScale.DEFAULT
    }

    val customFontEnabled: Flow<Boolean> = store.data.map { it[Keys.CUSTOM_FONT_ENABLED] ?: true }

    val themeMode: Flow<ThemeMode> = store.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { name -> ThemeMode.entries.find { it.name == name } } ?: ThemeMode.SYSTEM
    }

    /**
     * Calendar display visibility — the left-hand menu's checkboxes. Deliberately separate from
     * CalendarEntity.enabled (sync on/off, set in Settings' Calendars section): this only decides
     * whether Today/Week/Month show a calendar's events, never whether it's fetched or mirrored
     * to CalendarContract. Stored as the HIDDEN set (not a visible set) so a newly discovered
     * calendar is visible by default without needing to backfill anything for it.
     */
    val hiddenCalendarUrls: Flow<Set<String>> = store.data.map { it[Keys.HIDDEN_CALENDAR_URLS] ?: emptySet() }

    suspend fun setTextScale(scale: TextScale) {
        store.edit { it[Keys.TEXT_SCALE] = scale.name }
    }

    suspend fun setCustomFontEnabled(enabled: Boolean) {
        store.edit { it[Keys.CUSTOM_FONT_ENABLED] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        store.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setCalendarHidden(url: String, hidden: Boolean) {
        store.edit { prefs ->
            val current = prefs[Keys.HIDDEN_CALENDAR_URLS] ?: emptySet()
            prefs[Keys.HIDDEN_CALENDAR_URLS] = if (hidden) current + url else current - url
        }
    }
}
