package com.brbrs.zaman.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * How often the periodic background sync ([com.brbrs.zaman.data.repo.CalDavSyncWorker]) should
 * run. 15 minutes is WorkManager's own PeriodicWorkRequest floor — there's no going tighter than
 * that — so it's both the default and the shortest option offered.
 */
enum class SyncInterval(val minutes: Long, val label: String) {
    MIN_15(15L, "Every 15 minutes"),
    MIN_30(30L, "Every 30 minutes"),
    HOUR_1(60L, "Every hour"),
    HOUR_2(120L, "Every 2 hours"),
    HOUR_4(240L, "Every 4 hours"),
    HOUR_8(480L, "Every 8 hours"),
    DAILY(1440L, "Once a day");

    companion object {
        val DEFAULT = MIN_15
        fun fromMinutes(minutes: Long): SyncInterval = entries.find { it.minutes == minutes } ?: DEFAULT
    }
}

/**
 * Kept in its own DataStore, same one-concern-per-store convention as the rest of the suite
 * (auth/display/local-mode/sync-status each have their own) — clearing/resetting any of those
 * should never reset how often the user wants Zaman to wake up and sync.
 */
class SyncPreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val INTERVAL_MINUTES = longPreferencesKey("interval_minutes")
    }

    val interval: Flow<SyncInterval> = store.data.map { prefs ->
        SyncInterval.fromMinutes(prefs[Keys.INTERVAL_MINUTES] ?: SyncInterval.DEFAULT.minutes)
    }

    suspend fun setInterval(interval: SyncInterval) {
        store.edit { it[Keys.INTERVAL_MINUTES] = interval.minutes }
    }
}
