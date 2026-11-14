package com.brbrs.zaman.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Settings → Local-only mode: the suite-wide v2 pattern already shipped for Vinci, Tavla
 * and Koto. When enabled, Zaman never talks to Nextcloud — discovery and sync both no-op —
 * and the only way data enters or leaves the app is Export/Import. Kept in its own DataStore
 * (separate from auth/display) so toggling it never touches the signed-in account or the
 * user's theme/text-size choices.
 */
class LocalModePreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val LOCAL_ONLY_ENABLED = booleanPreferencesKey("local_only_enabled")
    }

    val localOnlyEnabled: Flow<Boolean> = store.data.map { it[Keys.LOCAL_ONLY_ENABLED] ?: false }

    suspend fun setLocalOnlyEnabled(enabled: Boolean) {
        store.edit { it[Keys.LOCAL_ONLY_ENABLED] = enabled }
    }
}
