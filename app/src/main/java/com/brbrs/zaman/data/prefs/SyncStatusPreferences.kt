package com.brbrs.zaman.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class SyncOutcome { NEVER_RUN, RUNNING, SUCCESS, PARTIAL_FAILURE, FAILURE }

data class SyncStatus(
    val outcome: SyncOutcome = SyncOutcome.NEVER_RUN,
    val lastAttemptEpochMillis: Long? = null,
    val lastSuccessEpochMillis: Long? = null,
    val calendarsFound: Int = 0,
    val eventsCached: Int = 0,
    val message: String? = null,
)

/**
 * Every CalDAV/Deck call in CalendarRepository was wrapped in a silent `runCatching` —
 * a failed PROPFIND, a 401, a parse error all just... vanished, and "sync" always looked
 * the same (instant, no feedback) whether it found 40 events or zero. This is the one
 * place that outcome is recorded, so Settings/Calendars can actually show the user what
 * happened instead of them having to guess from logcat.
 */
class SyncStatusPreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val OUTCOME = stringPreferencesKey("outcome")
        val LAST_ATTEMPT = longPreferencesKey("last_attempt_epoch_millis")
        val LAST_SUCCESS = longPreferencesKey("last_success_epoch_millis")
        val CALENDARS_FOUND = intPreferencesKey("calendars_found")
        val EVENTS_CACHED = intPreferencesKey("events_cached")
        val MESSAGE = stringPreferencesKey("message")
    }

    val status: Flow<SyncStatus> = store.data.map { prefs ->
        SyncStatus(
            outcome = prefs[Keys.OUTCOME]?.let { runCatching { SyncOutcome.valueOf(it) }.getOrNull() } ?: SyncOutcome.NEVER_RUN,
            lastAttemptEpochMillis = prefs[Keys.LAST_ATTEMPT],
            lastSuccessEpochMillis = prefs[Keys.LAST_SUCCESS],
            calendarsFound = prefs[Keys.CALENDARS_FOUND] ?: 0,
            eventsCached = prefs[Keys.EVENTS_CACHED] ?: 0,
            message = prefs[Keys.MESSAGE],
        )
    }

    suspend fun markRunning() {
        store.edit { it[Keys.OUTCOME] = SyncOutcome.RUNNING.name; it[Keys.LAST_ATTEMPT] = System.currentTimeMillis() }
    }

    suspend fun markResult(outcome: SyncOutcome, calendarsFound: Int, eventsCached: Int, message: String?) {
        store.edit { prefs ->
            prefs[Keys.OUTCOME] = outcome.name
            prefs[Keys.CALENDARS_FOUND] = calendarsFound
            prefs[Keys.EVENTS_CACHED] = eventsCached
            if (outcome == SyncOutcome.SUCCESS) prefs[Keys.LAST_SUCCESS] = System.currentTimeMillis()
            if (message != null) prefs[Keys.MESSAGE] = message else prefs.remove(Keys.MESSAGE)
        }
    }
}
