package com.brbrs.zaman.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.repo.AgendaEntry
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import javax.inject.Inject

data class WeekDayEntry(val agenda: AgendaEntry, val colorHex: String?) {
    val event get() = agenda.event
}

/** Week view (Monday-first, matching Month's own convention): same underlying
 *  CalendarRepository.observeEventsInRange query as Month/Today, just scoped to a 7-day range
 *  instead of a calendar month or a single day. A genuine new screen rather than Month with a
 *  narrower filter, since the two read naturally as different shapes (a 7-day agenda list vs. a
 *  month grid), matching how Today and Month are already two separate screens over the same data. */
@HiltViewModel
class WeekViewModel @Inject constructor(
    private val repository: CalendarRepository,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()

    private val _weekStart = MutableStateFlow(LocalDate.now().with(WeekFields.ISO.dayOfWeek(), 1))
    val weekStart: StateFlow<LocalDate> = _weekStart

    fun previousWeek() { _weekStart.value = _weekStart.value.minusWeeks(1) }
    fun nextWeek() { _weekStart.value = _weekStart.value.plusWeeks(1) }
    fun jumpToThisWeek() { _weekStart.value = LocalDate.now().with(WeekFields.ISO.dayOfWeek(), 1) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val eventsInWeek: kotlinx.coroutines.flow.Flow<List<AgendaEntry>> =
        _weekStart.flatMapLatest { start ->
            val startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMillis = start.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            combine(repository.observeVisibleEventsInRange(startMillis, endMillis), repository.pendingDeleteHrefs) { entries, pending ->
                entries.filterNot { it.event.href in pending }
            }
        }

    val entriesInWeek: StateFlow<List<WeekDayEntry>> =
        combine(eventsInWeek, repository.observeCalendars()) { entries, calendars ->
            val colorByUrl = calendars.associate { it.url to it.colorHex }
            entries.map { WeekDayEntry(it, colorByUrl[it.event.calendarUrl]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
