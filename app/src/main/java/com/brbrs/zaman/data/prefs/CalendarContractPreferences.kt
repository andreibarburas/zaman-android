package com.brbrs.zaman.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Feature #2 — whether the user has turned on "Sync with Android Calendar" (CalendarContract
 * mirroring) in Settings. Own DataStore, same one-concern-per-store convention as the rest of the
 * suite: turning this on/off must never touch the sync-interval or sync-status preferences, and
 * clearing those must never silently disable this.
 */
class CalendarContractPreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
    }

    val enabled: Flow<Boolean> = store.data.map { it[Keys.ENABLED] ?: false }

    suspend fun setEnabled(value: Boolean) {
        store.edit { it[Keys.ENABLED] = value }
    }
}
