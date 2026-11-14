package com.brbrs.zaman.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.prefs.CalendarContractPreferences
import com.brbrs.zaman.data.prefs.SyncInterval
import com.brbrs.zaman.data.prefs.SyncPreferences
import com.brbrs.zaman.data.repo.CalDavSyncWorker
import com.brbrs.zaman.sync.contract.CalendarContractRepository
import com.brbrs.zaman.sync.contract.ZamanAccount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs both the "Sync" picker in Settings and MainActivity's own startup read (see its
 * LaunchedEffect) — the single place that turns the user's chosen [SyncInterval] into an actual
 * WorkManager reschedule, so a change here takes effect immediately instead of waiting for the
 * next app launch.
 *
 * Also backs feature #2's "Sync with Android Calendar" toggle — the READ_CALENDAR/WRITE_CALENDAR
 * runtime permission request itself has to happen in Compose (see SettingsScreen's
 * rememberLauncherForActivityResult), but everything after permission is granted — creating or
 * removing the stub account, flipping the preference, kicking an immediate mirror pass — lives
 * here, same as [setInterval] does for the sync-interval picker.
 */
@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val prefs: SyncPreferences,
    private val contractPrefs: CalendarContractPreferences,
    private val contractRepository: CalendarContractRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val interval: StateFlow<SyncInterval> =
        prefs.interval.stateIn(viewModelScope, SharingStarted.Eagerly, SyncInterval.DEFAULT)

    val calendarContractEnabled: StateFlow<Boolean> =
        contractPrefs.enabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setInterval(interval: SyncInterval) {
        viewModelScope.launch {
            prefs.setInterval(interval)
            CalDavSyncWorker.enqueuePeriodic(appContext, interval.minutes)
        }
    }

    /** Called only after the READ_CALENDAR/WRITE_CALENDAR permission request (in Compose) has
     *  already been granted — see SettingsScreen. Turning this off removes the stub Android
     *  account entirely, which cascades and clears every Calendars/Events/Reminders row Zaman
     *  ever mirrored, so nothing orphaned is left behind in the system Calendar app. */
    fun setCalendarContractEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                ZamanAccount.get(appContext)
                contractPrefs.setEnabled(true)
                contractRepository.sync()
            } else {
                contractPrefs.setEnabled(false)
                ZamanAccount.remove(appContext)
            }
        }
    }
}
