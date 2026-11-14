package com.brbrs.zaman.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.prefs.SyncStatus
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.repo.AgendaEntry
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Standard working day length, in hours, used as the denominator for [TodayViewModel.packedRatio]. */
private const val WORKING_DAY_HOURS = 8f

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TodayViewModel @Inject constructor(
    private val repository: CalendarRepository,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    val todaysEvents: StateFlow<List<AgendaEntry>> = _selectedDate
        .flatMapLatest { date ->
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            // Filtered against pendingDeleteHrefs so a delete requested from Event Edit hides the
            // event here immediately (optimistic), even though the real CalDAV delete is still
            // waiting out the undo window — see CalendarRepository.requestDeleteEvent.
            combine(repository.observeVisibleEventsInRange(start, end), repository.pendingDeleteHrefs) { entries, pending ->
                entries.filterNot { it.event.href in pending }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Feature: "calendar as a living organism" — how packed the selected day is, as a plain
     * 0..1 ratio, driving TodayScreen's ambient breathing/pulsing background glow (see
     * BreathingGlow in TodayScreen.kt). Per the chosen design: booked hours ÷ a standard working
     * day, clamped so a wildly overbooked day still just reads as "fully stressed" rather than
     * pushing the animation's math outside its intended range. All-day events are excluded (they
     * don't actually occupy hours of the day the way a timed meeting does), and overlapping
     * events are counted once each rather than merged — double-booking two things in the same
     * hour SHOULD read as more packed than one, which is exactly the ambient signal this is for.
     */
    val packedRatio: StateFlow<Float> = todaysEvents
        .map { entries ->
            val bookedMillis = entries
                .filterNot { it.event.allDay }
                .sumOf { entry ->
                    val end = entry.occurrenceEndMillis ?: (entry.occurrenceStartMillis + 3_600_000L)
                    (end - entry.occurrenceStartMillis).coerceAtLeast(0L)
                }
            val bookedHours = bookedMillis / 3_600_000f
            (bookedHours / WORKING_DAY_HOURS).coerceIn(0f, 1f)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    /** Backs the Undo snackbar in TodayScreen — see CalendarRepository.requestDeleteEvent. */
    val lastPendingDelete = repository.lastPendingDelete

    fun undoDelete(href: String) = repository.undoDelete(href)
    fun clearLastPendingDelete() = repository.clearLastPendingDelete()

    /** One entry per day in the visible Mon–Sun week, so the week-strip can dot days with items. */
    val weekItemCounts: StateFlow<Map<LocalDate, Int>> = _selectedDate
        .flatMapLatest { date ->
            val weekStart = date.minusDays((date.dayOfWeek.value - 1).toLong())
            val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            repository.observeVisibleEventsInRange(start, end).map { entries ->
                entries.groupBy { Instant.ofEpochMilli(it.occurrenceStartMillis).atZone(zone).toLocalDate() }
                    .mapValues { it.value.size }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * calendarUrl -> colorHex, so the agenda can stripe each event by its OWN calendar's color
     * instead of one flat accent for every native-CalDAV event regardless of which calendar it
     * came from (see AgendaRow/stripeColorFor — previously every non-Koto/Tavla event got the
     * same ZamanColors.AccentDark stripe no matter which calendar it was in).
     */
    val calendarColors: StateFlow<Map<String, String?>> = repository.observeCalendars()
        .map { calendars -> calendars.associate { it.url to it.colorHex } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** calendarUrl -> cover image path, for the small thumbnail next to an agenda row's title. */
    val calendarCovers: StateFlow<Map<String, String?>> = repository.observeCalendars()
        .map { calendars -> calendars.associate { it.url to it.coverImagePath } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Lets the top bar show a live sync indicator and trigger a manual refresh without going
     *  through Settings — previously the only way to see sync status or force a refresh. */
    val syncStatus: StateFlow<SyncStatus> =
        repository.syncStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatus())

    fun syncNow() {
        viewModelScope.launch { repository.syncNow() }
    }

    /** Feature #5 — checking off a Koto task from Today's agenda. See CalendarRepository.completeTask. */
    fun completeTask(event: EventEntity) {
        viewModelScope.launch { runCatching { repository.completeTask(event) } }
    }

    fun selectDate(date: LocalDate) {
        _selectedDate.value = date
    }
}
