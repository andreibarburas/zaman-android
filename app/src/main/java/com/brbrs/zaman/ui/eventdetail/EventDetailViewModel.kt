package com.brbrs.zaman.ui.eventdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EventDetailState(
    val event: EventEntity? = null,
    val colorHex: String? = null,
    /** The owning calendar's own display name — feature: the single event screen shows which
     *  calendar an event is actually in, not just a color stripe. Null while loading or if the
     *  calendar was somehow removed since this event was synced. */
    val calendarName: String? = null,
    /** The owning calendar's cover photo path, if the user set one — shown as a small thumbnail
     *  next to [calendarName], same cover-image convention as Settings' calendar list. */
    val calendarCoverImagePath: String? = null,
    /** Which occurrence of a recurring series was tapped (see ZamanNavHost.editEventRoute's own
     *  doc comment) — falls back to the event's own startEpochMillis once it's loaded, same
     *  convention EventEditViewModel uses, so a date/time computed from this is always the
     *  instance the user actually tapped, not the series' original DTSTART. */
    val occurrenceStartMillis: Long = -1L,
    val loading: Boolean = true,
    val notFound: Boolean = false,
)

/**
 * Read-only popup shown for a tapped event before committing to the full edit screen (feature
 * request: tapping an item used to jump straight into editing it). Deliberately its own small
 * ViewModel rather than reusing EventEditViewModel — this only ever reads one event and never
 * builds/validates a form, so it doesn't need any of that machinery.
 */
@HiltViewModel
class EventDetailViewModel @Inject constructor(
    private val repository: CalendarRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val href: String = savedStateHandle.get<String>("eventHref").orEmpty()
    private val navOccurrenceStartMillis: Long? =
        savedStateHandle.get<Long>("occurrenceStart")?.takeIf { it >= 0 }

    private val _state = MutableStateFlow(EventDetailState())
    val state: StateFlow<EventDetailState> = _state

    init {
        viewModelScope.launch {
            val event = repository.eventByHref(href)
            if (event == null) {
                _state.value = EventDetailState(loading = false, notFound = true)
                return@launch
            }
            val calendar = repository.observeCalendars().first().find { it.url == event.calendarUrl }
            _state.value = EventDetailState(
                event = event,
                colorHex = calendar?.colorHex,
                calendarName = calendar?.displayName,
                calendarCoverImagePath = calendar?.coverImagePath,
                occurrenceStartMillis = navOccurrenceStartMillis ?: event.startEpochMillis,
                loading = false,
            )
        }
    }

    /** Optimistic delete, same undo-window mechanism as everywhere else — see
     *  CalendarRepository.requestDeleteEvent. */
    fun deleteEvent() {
        _state.value.event?.let { repository.requestDeleteEvent(it) }
    }
}
