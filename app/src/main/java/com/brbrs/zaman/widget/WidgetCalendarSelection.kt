package com.brbrs.zaman.widget

import android.content.Context
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Per-widget-instance calendar selection — the widget's own configure screen (see
 * ZamanAgendaWidgetConfigureActivity), completely separate from the in-app left-hand drawer's
 * global display selection (see DisplayPreferences.hiddenCalendarUrls): two different widgets on
 * the same home screen can each show a different subset of calendars.
 *
 * Stored via Glance's own documented PreferencesGlanceStateDefinition (one small Preferences
 * blob per GlanceId, managed by Glance itself) rather than a hand-rolled DataStore file — a
 * stable, public API, unlike the GlanceTheme/FontFamily internals this codebase already hit
 * pitfalls with elsewhere.
 *
 * The key holds the SELECTED set, not a hidden set (opposite of DisplayPreferences): a widget
 * that has never been configured has no stored value at all, and that absence means "show every
 * calendar" (see [getSelectedCalendarUrls]'s null return) — so existing placed widgets, from
 * before this feature existed, keep behaving exactly as they did.
 */
private val SelectedCalendarUrlsKey = stringSetPreferencesKey("selected_calendar_urls")

/** Null means "no selection stored yet — show every calendar" (the pre-existing, unfiltered
 *  behavior); a non-null set (even empty) means the configure screen was used and this is
 *  exactly what it left checked. */
suspend fun getSelectedCalendarUrls(context: Context, glanceId: GlanceId): Set<String>? {
    val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId)
    return prefs[SelectedCalendarUrlsKey]
}

suspend fun setSelectedCalendarUrls(context: Context, glanceId: GlanceId, urls: Set<String>) {
    updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
        prefs.toMutablePreferences().apply { this[SelectedCalendarUrlsKey] = urls }
    }
}
