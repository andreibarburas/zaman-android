package com.brbrs.zaman.ui.eventedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.local.allReminderMinutes
import com.brbrs.zaman.data.local.attendeeDetails
import com.brbrs.zaman.data.local.categories
import com.brbrs.zaman.data.local.DREAM_BLOCK_CATEGORY
import com.brbrs.zaman.data.local.isDreamBlock
import com.brbrs.zaman.data.ical.EventAttendeeInput
import com.brbrs.zaman.data.repo.AgendaEntry
import com.brbrs.zaman.data.repo.CalendarRepository
import com.brbrs.zaman.data.repo.EditScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/** A deliberately small subset of RRULE — covers the common cases; anything Nextcloud wrote
 *  that doesn't match one of these (an INTERVAL>1, a BYDAY list, COUNT/UNTIL, ...) is kept as
 *  CUSTOM and shown read-only rather than silently rewritten into something simpler on save. */
enum class RepeatOption(val label: String, val rrule: String?) {
    NONE("Does not repeat", null),
    DAILY("Daily", "FREQ=DAILY"),
    WEEKLY("Weekly", "FREQ=WEEKLY"),
    MONTHLY("Monthly", "FREQ=MONTHLY"),
    YEARLY("Yearly", "FREQ=YEARLY"),
    CUSTOM("Custom (from Nextcloud)", null);

    companion object {
        fun fromRRule(rrule: String?): RepeatOption = when {
            rrule == null -> NONE
            rrule == "FREQ=DAILY" -> DAILY
            rrule == "FREQ=WEEKLY" -> WEEKLY
            rrule == "FREQ=MONTHLY" -> MONTHLY
            rrule == "FREQ=YEARLY" -> YEARLY
            else -> CUSTOM
        }
    }
}

/** Settings → "Reminder" picker options, in minutes before start. */
enum class ReminderOption(val label: String, val minutes: Int?) {
    NONE("No reminder", null),
    AT_START("At time of event", 0),
    MIN_15("15 minutes before", 15),
    MIN_30("30 minutes before", 30),
    HOUR_1("1 hour before", 60),
    DAY_1("1 day before", 1440);

    companion object {
        fun fromMinutes(minutes: Int?): ReminderOption =
            entries.find { it.minutes == minutes } ?: NONE
    }
}

data class EventEditFormState(
    val summary: String = "",
    val location: String = "",
    val description: String = "",
    val allDay: Boolean = false,
    val start: ZonedDateTime = ZonedDateTime.now().withSecond(0).withNano(0),
    val end: ZonedDateTime = ZonedDateTime.now().plusHours(1).withSecond(0).withNano(0),
    val calendarUrl: String? = null,
    val isExistingEvent: Boolean = false,
    val canDelete: Boolean = false,
    val repeatOption: RepeatOption = RepeatOption.NONE,
    val customRRule: String? = null, // only set when repeatOption == CUSTOM, carried through unchanged
    /** Zero or more reminders, in the order added — up to MAX_REMINDERS_PER_EVENT (see
     *  ReminderScheduler). An empty list is "no reminder"; there's no separate NONE entry here
     *  the way the old single-ReminderOption field had one. */
    val reminders: List<ReminderOption> = emptyList(),
    /** Editable invitee list — added/removed in EventEditScreen's Attendees section, written as
     *  ATTENDEE lines on save (see CalendarRepository.createEvent/updateEvent's `attendees`
     *  param). [EventAttendeeForm.partstat] is preserved from whatever Nextcloud last had for an
     *  existing invitee, so editing other fields of the event never resets anyone's RSVP. */
    val attendees: List<EventAttendeeForm> = emptyList(),
    /** CATEGORIES tags — added/removed in EventEditScreen's Tags section. */
    val categories: List<String> = emptyList(),
    val linkedContactUid: String? = null,
    val linkedContactName: String? = null,
)

data class EventAttendeeForm(val email: String, val displayName: String? = null, val partstat: String? = null)

sealed interface SaveResult {
    data object Idle : SaveResult
    data object Saving : SaveResult
    data object Done : SaveResult
    data class Error(val message: String) : SaveResult
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EventEditViewModel @Inject constructor(
    private val repository: CalendarRepository,
    private val travelTimeEstimator: com.brbrs.zaman.data.geo.TravelTimeEstimator,
    private val travelPreferences: com.brbrs.zaman.data.prefs.TravelPreferences,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val existingHref: String? = savedStateHandle.get<String>("eventHref")?.takeIf { it.isNotBlank() }

    // Which instance of a recurring series was actually tapped (see ZamanNavHost.editEventRoute's
    // doc comment) — -1/not-passed falls back to the event's own startEpochMillis once it loads,
    // i.e. "acts like the master occurrence" when nothing more specific is known (Search, a deep
    // link). Needed so save()'s scope dialog (feature #4) can tell which occurrence to EXDATE /
    // split the series at, as opposed to always assuming the master's own first occurrence.
    private val navOccurrenceStartMillis: Long? = savedStateHandle.get<Long>("occurrenceStart")?.takeIf { it >= 0 }

    private val _form = MutableStateFlow(EventEditFormState())
    val form: StateFlow<EventEditFormState> = _form

    private val _saveResult = MutableStateFlow<SaveResult>(SaveResult.Idle)
    val saveResult: StateFlow<SaveResult> = _saveResult

    /** Non-null while the "this event / this and future / all events" dialog should be showing —
     *  see save()/chooseScope()/dismissScopeChoice(). */
    private val _pendingScopeChoice = MutableStateFlow(false)
    val pendingScopeChoice: StateFlow<Boolean> = _pendingScopeChoice

    /** True once save() has been blocked on a dream-block collision and is waiting on the
     *  screen's confirmation dialog — see confirmSaveDespiteDreamBlock()/dismissDreamBlockWarning(). */
    private val _pendingDreamBlockWarning = MutableStateFlow(false)
    val pendingDreamBlockWarning: StateFlow<Boolean> = _pendingDreamBlockWarning

    val writableCalendars: StateFlow<List<CalendarEntity>> =
        repository.observeWritableCalendars().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Separate from writableCalendars above: an existing event can belong to a read-only
    // subscription calendar, which observeWritableCalendars() excludes — so looking up that
    // calendar's color/cover for the screen's own background (item 3) needs the full list,
    // otherwise an event on a non-writable calendar silently fell back to the generic background.
    val allCalendars: StateFlow<List<CalendarEntity>> =
        repository.observeCalendars().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var editingExisting: EventEntity? = null

    /**
     * Feature #6 — timed events that overlap whatever is currently in the start/end fields,
     * recomputed live as the user picks dates/times, so the warning is already there by the
     * time they go to Save rather than only appearing after. Never blocks saving — see
     * CalendarRepository.findConflicts's doc comment on why this is advisory only.
     */
    val conflicts: StateFlow<List<AgendaEntry>> = _form
        .map { Triple(it.start, if (it.allDay) null else it.end, it.allDay) }
        .distinctUntilChanged()
        .flatMapLatest { (start, end, allDay) ->
            if (allDay) {
                flowOf(emptyList())
            } else {
                flow {
                    val endOrDefault = end ?: start.plusHours(1)
                    emit(
                        repository.findConflicts(
                            start.toInstant().toEpochMilli(),
                            endOrDefault.toInstant().toEpochMilli(),
                            excludeHref = existingHref,
                        )
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Travel-time estimate (feature) — recomputed whenever the location field settles (debounced
     * so it's not re-geocoding on every keystroke) and only when both a location AND a starting
     * address (Settings) are set. Null means "nothing to show" (one of those is missing, or the
     * lookup failed) — see TravelTimeEstimator's doc comment on exactly what this is and isn't.
     */
    val travelEstimate: StateFlow<String?> = _form
        .map { it.location.trim() }
        .distinctUntilChanged()
        .flatMapLatest { location ->
            flow {
                emit(null)
                if (location.isBlank()) return@flow
                kotlinx.coroutines.delay(600) // debounce — a geocode is a real network call
                val starting = travelPreferences.startingAddress.first()
                if (starting.isBlank()) return@flow
                emit(travelTimeEstimator.estimate(starting, location))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        if (existingHref != null) {
            viewModelScope.launch {
                val event = repository.eventByHref(existingHref)
                if (event != null) {
                    editingExisting = event
                    val zone = ZoneId.systemDefault()
                    _form.value = EventEditFormState(
                        summary = event.summary,
                        location = event.location.orEmpty(),
                        description = event.description.orEmpty(),
                        allDay = event.allDay,
                        start = Instant.ofEpochMilli(event.startEpochMillis).atZone(zone),
                        end = Instant.ofEpochMilli(event.endEpochMillis ?: (event.startEpochMillis + 3_600_000)).atZone(zone),
                        calendarUrl = event.calendarUrl,
                        isExistingEvent = true,
                        canDelete = event.sourceApp == null, // items synced in from Koto/Tavla aren't Zaman's to delete
                        repeatOption = RepeatOption.fromRRule(event.rrule),
                        customRRule = event.rrule?.takeIf { RepeatOption.fromRRule(it) == RepeatOption.CUSTOM },
                        reminders = event.allReminderMinutes().map { ReminderOption.fromMinutes(it) },
                        attendees = event.attendeeDetails().map { a -> EventAttendeeForm(a.email, a.displayName, a.partstat) },
                        categories = event.categories(),
                        linkedContactUid = event.linkedContactUid,
                        linkedContactName = event.linkedContactName,
                    )
                }
            }
        } else {
            viewModelScope.launch {
                writableCalendars.collect { cals ->
                    if (_form.value.calendarUrl == null && cals.isNotEmpty()) {
                        _form.value = _form.value.copy(calendarUrl = cals.first().url)
                    }
                }
            }
        }
    }

    fun update(transform: (EventEditFormState) -> EventEditFormState) {
        _form.value = transform(_form.value)
    }

    /**
     * Feature #4: editing an existing recurring event no longer silently rewrites the whole
     * series. If this is one, save() stops short and raises the scope dialog instead — the
     * screen observes [pendingScopeChoice] and calls [chooseScope] once the user picks. A
     * non-recurring event, or a brand-new one, saves immediately exactly as before.
     */
    fun save() {
        val state = _form.value
        if (state.summary.isBlank() || state.calendarUrl == null) {
            _saveResult.value = SaveResult.Error("Give the event a title and a calendar.")
            return
        }
        // Dream scheduling's "strong warning + confirmation required" defense: Zaman can't stop
        // another app/person's invite from landing on the server, but it can make colliding with
        // a block the user protected on purpose a deliberate choice, not an accident — so saving
        // stops here the first time, and confirmSaveDespiteDreamBlock() is what actually proceeds.
        if (!state.allDay && conflicts.value.any { it.event.isDreamBlock() }) {
            _pendingDreamBlockWarning.value = true
            return
        }
        val existing = editingExisting
        if (existing != null && existing.rrule != null) {
            _pendingScopeChoice.value = true
            return
        }
        performSave(scope = null)
    }

    /** Called once the user has seen [pendingDreamBlockWarning] and chosen to save anyway —
     *  re-runs save()'s remaining checks (recurring-series scope), skipping only the dream-block
     *  check itself since that's already been confirmed for this save attempt. */
    fun confirmSaveDespiteDreamBlock() {
        _pendingDreamBlockWarning.value = false
        val existing = editingExisting
        if (existing != null && existing.rrule != null) {
            _pendingScopeChoice.value = true
            return
        }
        performSave(scope = null)
    }

    fun dismissDreamBlockWarning() {
        _pendingDreamBlockWarning.value = false
    }

    fun chooseScope(scope: EditScope) {
        _pendingScopeChoice.value = false
        performSave(scope)
    }

    fun dismissScopeChoice() {
        _pendingScopeChoice.value = false
    }

    private fun performSave(scope: EditScope?) {
        val state = _form.value
        val calendarUrl = state.calendarUrl ?: return
        val rrule = when (state.repeatOption) {
            RepeatOption.NONE -> null
            RepeatOption.CUSTOM -> state.customRRule
            else -> state.repeatOption.rrule
        }
        val reminderMinutesList = state.reminders.mapNotNull { it.minutes }.distinct()
        val reminderMinutes = reminderMinutesList.firstOrNull()
        val extraReminderMinutes = reminderMinutesList.drop(1)
        val end = if (state.allDay) null else state.end
        val attendeeInputs = state.attendees.map { EventAttendeeInput(it.email, it.displayName, it.partstat) }
        viewModelScope.launch {
            _saveResult.value = SaveResult.Saving
            runCatching {
                val existing = editingExisting
                when {
                    existing != null && scope != null -> repository.updateRecurringEvent(
                        existing = existing,
                        occurrenceStartMillis = navOccurrenceStartMillis ?: existing.startEpochMillis,
                        scope = scope,
                        summary = state.summary,
                        start = state.start,
                        end = end,
                        allDay = state.allDay,
                        location = state.location.ifBlank { null },
                        description = state.description.ifBlank { null },
                        reminderMinutesBeforeStart = reminderMinutes,
                        extraReminderMinutes = extraReminderMinutes,
                    )
                    existing != null -> repository.updateEvent(
                        existing = existing,
                        summary = state.summary,
                        start = state.start,
                        end = end,
                        allDay = state.allDay,
                        location = state.location.ifBlank { null },
                        description = state.description.ifBlank { null },
                        rrule = rrule,
                        reminderMinutesBeforeStart = reminderMinutes,
                        extraReminderMinutes = extraReminderMinutes,
                        categories = state.categories,
                        attendees = attendeeInputs,
                    )
                    else -> repository.createEvent(
                        calendarUrl = calendarUrl,
                        summary = state.summary,
                        start = state.start,
                        end = end,
                        allDay = state.allDay,
                        location = state.location.ifBlank { null },
                        description = state.description.ifBlank { null },
                        rrule = rrule,
                        reminderMinutesBeforeStart = reminderMinutes,
                        extraReminderMinutes = extraReminderMinutes,
                        categories = state.categories,
                        attendees = attendeeInputs,
                    )
                }
            }.onSuccess {
                _saveResult.value = SaveResult.Done
            }.onFailure { e ->
                _saveResult.value = SaveResult.Error(e.message ?: "Couldn't save that to Nextcloud.")
            }
        }
    }

    fun addAttendee(email: String, displayName: String? = null) {
        val trimmed = email.trim()
        if (trimmed.isEmpty() || _form.value.attendees.any { it.email.equals(trimmed, ignoreCase = true) }) return
        _form.value = _form.value.copy(attendees = _form.value.attendees + EventAttendeeForm(trimmed, displayName?.takeIf { it.isNotBlank() }))
    }

    fun removeAttendee(email: String) {
        _form.value = _form.value.copy(attendees = _form.value.attendees.filterNot { it.email.equals(email, ignoreCase = true) })
    }

    fun addCategory(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isEmpty() || _form.value.categories.any { it.equals(trimmed, ignoreCase = true) }) return
        _form.value = _form.value.copy(categories = _form.value.categories + trimmed)
    }

    fun removeCategory(tag: String) {
        _form.value = _form.value.copy(categories = _form.value.categories.filterNot { it.equals(tag, ignoreCase = true) })
    }

    /** Dream scheduling's "Protect this time" switch — just adds/removes the one sentinel
     *  CATEGORIES tag (see EventEntity.DREAM_BLOCK_CATEGORY's doc comment), reusing add/removeCategory
     *  rather than a new field, so it round-trips through save()/duplicate()/buildIcsForShare()
     *  for free exactly like any other tag. */
    fun setDreamBlockProtected(protected: Boolean) {
        if (protected) addCategory(DREAM_BLOCK_CATEGORY) else removeCategory(DREAM_BLOCK_CATEGORY)
    }

    /** Builds the current event's .ics text for sharing (Share action) — not necessarily saved
     *  yet, so this reflects the live form, same way Save would build it. */
    fun buildIcsForShare(): String {
        val state = _form.value
        val rrule = when (state.repeatOption) {
            RepeatOption.NONE -> null
            RepeatOption.CUSTOM -> state.customRRule
            else -> state.repeatOption.rrule
        }
        return com.brbrs.zaman.data.ical.ICalWriter.buildVEvent(
            uid = editingExisting?.uid ?: java.util.UUID.randomUUID().toString(),
            summary = state.summary, start = state.start, end = if (state.allDay) null else state.end,
            allDay = state.allDay, location = state.location.ifBlank { null }, description = state.description.ifBlank { null },
            rrule = rrule, categories = state.categories,
            attendees = state.attendees.map { EventAttendeeInput(it.email, it.displayName, it.partstat) },
        )
    }

    /**
     * Duplicate action: creates a brand-new event (fresh UID/href, same calendar) from the
     * current form's fields. Deliberately starts from the live form (not the stored entity), so
     * duplicating after editing some fields but before saving duplicates what's on screen.
     * Reminders/attendees/categories/recurrence all carry over; the new copy is NOT linked to
     * whatever Vinci contact the original was linked to (that's a per-event local annotation).
     */
    fun duplicate() {
        val state = _form.value
        val calendarUrl = state.calendarUrl ?: return
        val rrule = when (state.repeatOption) {
            RepeatOption.NONE -> null
            RepeatOption.CUSTOM -> state.customRRule
            else -> state.repeatOption.rrule
        }
        val reminderMinutesList = state.reminders.mapNotNull { it.minutes }.distinct()
        viewModelScope.launch {
            _saveResult.value = SaveResult.Saving
            runCatching {
                repository.createEvent(
                    calendarUrl = calendarUrl,
                    summary = state.summary,
                    start = state.start,
                    end = if (state.allDay) null else state.end,
                    allDay = state.allDay,
                    location = state.location.ifBlank { null },
                    description = state.description.ifBlank { null },
                    rrule = rrule,
                    reminderMinutesBeforeStart = reminderMinutesList.firstOrNull(),
                    extraReminderMinutes = reminderMinutesList.drop(1),
                    categories = state.categories,
                    attendees = state.attendees.map { EventAttendeeInput(it.email, it.displayName) },
                )
            }.onSuccess {
                _saveResult.value = SaveResult.Done
            }.onFailure { e ->
                _saveResult.value = SaveResult.Error(e.message ?: "Couldn't duplicate that event.")
            }
        }
    }

    /** Local-only annotation — never written back to Nextcloud as an ATTENDEE. See CalendarRepository.linkContact. */
    fun linkContact(contactUid: String, contactName: String) {
        _form.value = _form.value.copy(linkedContactUid = contactUid, linkedContactName = contactName)
        existingHref?.let { href -> viewModelScope.launch { repository.linkContact(href, contactUid, contactName) } }
    }

    fun unlinkContact() {
        _form.value = _form.value.copy(linkedContactUid = null, linkedContactName = null)
        existingHref?.let { href -> viewModelScope.launch { repository.unlinkContact(href) } }
    }

    /** Optimistic: hides the event everywhere immediately and navigates back right away. The
     *  actual CalDAV delete happens a few seconds later, in CalendarRepository, unless the user
     *  taps Undo on the snackbar Today/Month show for it — see requestDeleteEvent. */
    fun delete() {
        val existing = editingExisting ?: return
        repository.requestDeleteEvent(existing)
        _saveResult.value = SaveResult.Done
    }
}
