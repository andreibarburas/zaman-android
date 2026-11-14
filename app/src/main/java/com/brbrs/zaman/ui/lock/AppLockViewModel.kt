package com.brbrs.zaman.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Gate state for the biometric lock screen shown at app open. Separate from
 * AccountViewModel/SettingsScreen's toggle so the gate itself stays dumb: it
 * only cares whether the app is currently unlocked for this process lifetime.
 */
@HiltViewModel
class AppLockViewModel @Inject constructor(
    private val auth: LoginFlowV2Auth,
) : ViewModel() {

    val lockEnabled: StateFlow<Boolean> =
        auth.biometricLockEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Unlocked state lives only in memory: every fresh process start (app killed,
    // reopened from recents after being backgrounded past the OS's own limits)
    // re-locks. We don't persist "unlocked" across process death on purpose.
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked

    fun markUnlocked() {
        _unlocked.value = true
    }

    /** Re-lock when the app goes to the background, matching the suite's lock behavior. */
    fun markLocked() {
        _unlocked.value = false
    }

    fun setLockEnabled(enabled: Boolean) {
        viewModelScope.launch { auth.setBiometricLockEnabled(enabled) }
        if (!enabled) _unlocked.value = true
    }
}
