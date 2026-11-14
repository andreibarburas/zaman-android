package com.brbrs.zaman.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.prefs.DisplayPreferences
import com.brbrs.zaman.data.prefs.TextScale
import com.brbrs.zaman.data.prefs.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DisplayViewModel @Inject constructor(
    private val prefs: DisplayPreferences,
    private val travelPreferences: com.brbrs.zaman.data.prefs.TravelPreferences,
) : ViewModel() {

    /** Travel-time estimate's one setting — see TravelTimeEstimator's doc comment. Lives in this
     *  ViewModel (not a dedicated one) since it's a single text field with no other logic. */
    val startingAddress: StateFlow<String> =
        travelPreferences.startingAddress.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setStartingAddress(address: String) {
        viewModelScope.launch { travelPreferences.setStartingAddress(address) }
    }

    val textScale: StateFlow<TextScale> =
        prefs.textScale.stateIn(viewModelScope, SharingStarted.Eagerly, TextScale.DEFAULT)

    val customFontEnabled: StateFlow<Boolean> =
        prefs.customFontEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val themeMode: StateFlow<ThemeMode> =
        prefs.themeMode.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    fun setTextScale(scale: TextScale) {
        viewModelScope.launch { prefs.setTextScale(scale) }
    }

    fun setCustomFontEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setCustomFontEnabled(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }
}
