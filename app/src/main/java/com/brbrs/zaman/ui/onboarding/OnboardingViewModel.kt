package com.brbrs.zaman.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val auth: LoginFlowV2Auth,
) : ViewModel() {

    // Null until the stored flag's first read lands — MainActivity waits for that instead of
    // flashing onboarding for a frame on every cold start.
    val onboardingDone: StateFlow<Boolean?> =
        auth.onboardingDone.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // A returning user who was already connected before onboarding existed (or who signed in
    // directly) shouldn't be shown the welcome screen on their next cold start either.
    val isLoggedIn: StateFlow<Boolean?> =
        auth.isLoggedIn.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun markDone() {
        viewModelScope.launch { auth.setOnboardingDone() }
    }
}
