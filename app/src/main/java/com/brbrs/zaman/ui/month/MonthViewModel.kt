package com.brbrs.zaman.ui.month

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.repo.AgendaEntry
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.WeekFields
import javax.inject.Inject

data class MonthDayEntry(val agenda: AgendaEntry, val colorHex: String?, val coverImagePath: String? = null) {
    val event get() = agenda.event
}

@HiltViewModel
class MonthViewModel @Inject constructor(
    private val repository: CalendarRepository,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val _visibleMonth = MutableStateFlow(YearMonth.now())
    val visibleMonth: StateFlow<YearMonth> = _visibleMonth

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
        if (YearMonth.from(date) != _visibleMonth.value) _visibleMonth.value = YearMonth.from(date)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val eventsInMonth: kotlinx.coroutines.flow.Flow<List<AgendaEntry>> =
        _visibleMonth.flatMapLatest { month ->
            // The grid now shows the leading/trailing days of the neighboring months too (dimmed,
            // but with their real events — matching a standard month-grid calendar), so the query
            // range has to cover the whole Monday-first grid, not just the 1st-to-1st month span.
            val gridStart = month.atDay(1).with(WeekFields.ISO.dayOfWeek(), 1)
            val gridEndExclusive = month.atEndOfMonth().with(WeekFields.ISO.dayOfWeek(), 7).plusDays(1)
            val start = gridStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = gridEndExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
            // Same optimistic-hide-on-delete filtering as TodayViewModel — see
            // CalendarRepository.requestDeleteEvent.
            combine(repository.observeVisibleEventsInRange(start, end), repository.pendingDeleteHrefs) { entries, pending ->
                entries.filterNot { it.event.href in pending }
            }
        }

    /** Joined against calendars (in memory — calendar counts are tiny) so each dot carries its own calendar's color. */
    val entriesInMonth: StateFlow<List<MonthDayEntry>> =
        combine(eventsInMonth, repository.observeCalendars()) { entries, calendars ->
            val colorByUrl = calendars.associate { it.url to it.colorHex }
            val coverByUrl = calendars.associate { it.url to it.coverImagePath }
            entries.map { MonthDayEntry(it, colorByUrl[it.event.calendarUrl], coverByUrl[it.event.calendarUrl]) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun previousMonth() { _visibleMonth.value = _visibleMonth.value.minusMonths(1) }
    fun nextMonth() { _visibleMonth.value = _visibleMonth.value.plusMonths(1) }
    fun jumpToToday() { _visibleMonth.value = YearMonth.now() }

    // -- Bulk/multi-select -----------------------------------------------------------------

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode

    private val _selectedHrefs = MutableStateFlow<Set<String>>(emptySet())
    val selectedHrefs: StateFlow<Set<String>> = _selectedHrefs

    fun enterSelectionMode(firstHref: String? = null) {
        _selectionMode.value = true
        _selectedHrefs.value = firstHref?.let { setOf(it) } ?: emptySet()
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedHrefs.value = emptySet()
    }

    fun toggleSelected(href: String) {
        val current = _selectedHrefs.value
        _selectedHrefs.value = if (href in current) current - href else current + href
    }

    /** Optimistic bulk delete, same undo-window mechanism as a single delete — see
     *  CalendarRepository.requestDeleteEvent. */
    fun deleteSelected() {
        val hrefs = _selectedHrefs.value
        val byHref = entriesInMonth.value.associateBy { it.event.href }
        hrefs.forEach { href -> byHref[href]?.let { repository.requestDeleteEvent(it.event) } }
        exitSelectionMode()
    }

    // -- Drag-and-drop reschedule -----------------------------------------------------------

    private val _rescheduleMessage = MutableStateFlow<String?>(null)
    val rescheduleMessage: StateFlow<String?> = _rescheduleMessage

    fun dismissRescheduleMessage() { _rescheduleMessage.value = null }

    fun rescheduleEvent(entry: MonthDayEntry, newDate: LocalDate) {
        viewModelScope.launch {
            val ok = repository.rescheduleEvent(entry.event, newDate)
            if (!ok) _rescheduleMessage.value = "Can't move a repeating or synced-in item this way — open it to edit instead."
        }
    }
}
