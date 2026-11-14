package com.brbrs.zaman.ui.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row in the left-hand "which calendars show" menu — every calendar Zaman knows about
 *  (synced or not; see CalendarsViewModel for the separate sync on/off toggle), plus whether the
 *  display checkbox for it is currently checked. */
data class CalendarVisibilityRow(
    val calendar: CalendarEntity,
    val visible: Boolean,
)

/**
 * Backs the left-hand drawer (ZamanNavHost) that lets the user pick which calendars' events show
 * in Today/Week/Month — display-only, completely separate from the sync on/off toggle in
 * Settings' Calendars section. See DisplayPreferences.hiddenCalendarUrls and
 * CalendarRepository.observeVisibleEventsInRange.
 */
@HiltViewModel
class CalendarVisibilityViewModel @Inject constructor(
    private val repository: CalendarRepository,
) : ViewModel() {

    val rows: StateFlow<List<CalendarVisibilityRow>> =
        combine(repository.observeCalendars(), repository.hiddenCalendarUrls) { calendars, hidden ->
            calendars.map {
                // A calendar that isn't synced (CalendarEntity.enabled, toggled in Settings) has
                // no events in Room to show anyway — see EventDao's SQL, which already filters
                // on enabled = 1 — so it defaults to unchecked here too, rather than showing a
                // misleadingly-checked row for something that can never actually display.
                CalendarVisibilityRow(it, visible = it.enabled && it.url !in hidden)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setVisible(url: String, visible: Boolean) {
        viewModelScope.launch { repository.setCalendarDisplayVisible(url, visible) }
    }
}
