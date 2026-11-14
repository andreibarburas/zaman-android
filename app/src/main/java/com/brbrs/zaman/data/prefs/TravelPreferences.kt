package com.brbrs.zaman.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The one setting the travel-time estimate feature needs — see TravelTimeEstimator's doc
 *  comment for why this is a typed starting address rather than the device's actual location. */
class TravelPreferences(private val store: DataStore<Preferences>) {

    private object Keys {
        val STARTING_ADDRESS = stringPreferencesKey("starting_address")
    }

    val startingAddress: Flow<String> = store.data.map { it[Keys.STARTING_ADDRESS].orEmpty() }

    suspend fun setStartingAddress(address: String) {
        store.edit { it[Keys.STARTING_ADDRESS] = address }
    }
}
