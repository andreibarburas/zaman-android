package com.brbrs.zaman.data.repo

import com.brbrs.zaman.data.caldav.CalDavClient
import com.brbrs.zaman.data.caldav.CollectionKind
import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import com.brbrs.zaman.data.caldav.RawCalendarObject
import com.brbrs.zaman.data.deck.DeckApiClient
import com.brbrs.zaman.data.deck.DeckCardWithDueDate
import com.brbrs.zaman.data.ical.ICalParser
import com.brbrs.zaman.data.ical.ICalWriter
import com.brbrs.zaman.data.ical.RRuleExpander
import com.brbrs.zaman.data.local.CalendarDao
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.local.EventDao
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.local.allReminderMinutes
import com.brbrs.zaman.data.local.attendeeDetails
import com.brbrs.zaman.data.local.categories
import com.brbrs.zaman.data.local.encodeCategories
import com.brbrs.zaman.data.local.exDateMillis
import com.brbrs.zaman.data.prefs.DisplayPreferences
import com.brbrs.zaman.data.prefs.LocalModePreferences
import com.brbrs.zaman.data.prefs.SyncOutcome
import com.brbrs.zaman.data.prefs.SyncStatus
import com.brbrs.zaman.data.prefs.SyncStatusPreferences
import com.brbrs.zaman.reminders.ReminderScheduler
import com.brbrs.zaman.widget.WidgetRefresher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A delete the user can still take back — surfaced as an Undo snackbar by Today/Month. */
data class PendingDelete(val href: String, val summary: String)

private const val UNDO_DELETE_WINDOW_MS = 5_000L

/** Pseudo calendar-home URL Tavla's Deck cards are stored under in Room — there's no real CalDAV collection for them. */
private const val TAVLA_PSEUDO_CALENDAR_URL = "zaman://tavla-deck-due-dates"


/**
 * One displayable occurrence of an event within a queried range. For a non-recurring event this
 * just wraps its own stored start/end; for a recurring one (event.rrule != null), [event] is
 * still the single stored master row but [occurrenceStartMillis]/[occurrenceEndMillis] are this
 * specific instance's computed time — see RRuleExpander. A weekly series can therefore produce
 * several AgendaEntry values from one EventEntity within the same window.
 */
data class AgendaEntry(
    val event: EventEntity,
    val occurrenceStartMillis: Long,
    val occurrenceEndMillis: Long?,
)

/**
 * Feature #4 — which part of a recurring series an edit applies to, mirroring the
 * "this event / this and following events / all events" dialog every other calendar app shows
 * when you edit one occurrence of a series. See CalendarRepository.updateRecurringEvent.
 */
enum class EditScope { THIS_EVENT, THIS_AND_FUTURE, ALL_EVENTS }

@Singleton
class CalendarRepository @Inject constructor(
    private val caldav: CalDavClient,
    private val deck: DeckApiClient,
    private val auth: LoginFlowV2Auth,
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
    private val localMode: LocalModePreferences,
    private val reminders: ReminderScheduler,
    private val syncStatusPrefs: SyncStatusPreferences,
    private val coverSync: CoverSyncRepository,
    private val contactLinks: ContactLinksSyncRepository,
    private val contactsRepo: ContactsRepository,
    private val appScope: CoroutineScope,
    private val widgetRefresher: WidgetRefresher,
    private val displayPrefs: DisplayPreferences,
) {
    val syncStatus: Flow<SyncStatus> = syncStatusPrefs.status

    /** The left-hand menu's checkboxes — see DisplayPreferences.hiddenCalendarUrls' doc comment. */
    val hiddenCalendarUrls: Flow<Set<String>> = displayPrefs.hiddenCalendarUrls

    suspend fun setCalendarDisplayVisible(url: String, visible: Boolean) =
        displayPrefs.setCalendarHidden(url, hidden = !visible)

    // -- Undo-after-delete ----------------------------------------------------------------
    // hrefs currently "soft deleted": hidden from every agenda query but not yet actually
    // removed from Room/Nextcloud. Today/Month both filter their event flows against this.
    private val _pendingDeleteHrefs = MutableStateFlow<Set<String>>(emptySet())
    val pendingDeleteHrefs: StateFlow<Set<String>> = _pendingDeleteHrefs

    // The most recent delete request, for the Undo snackbar's text — cleared once that
    // snackbar has been shown (see clearLastPendingDelete) so it doesn't reappear on rotation.
    private val _lastPendingDelete = MutableStateFlow<PendingDelete?>(null)
    val lastPendingDelete: StateFlow<PendingDelete?> = _lastPendingDelete

    private val pendingDeleteJobs = mutableMapOf<String, Job>()

    /**
     * Hides [event] immediately (optimistic — every screen's event list filters it out via
     * [pendingDeleteHrefs]) and schedules the real delete ([deleteEvent], CalDAV DELETE + local
     * row removal) for [UNDO_DELETE_WINDOW_MS] later, unless [undoDelete] cancels it first.
     * Runs on [appScope], not a ViewModel's viewModelScope, because the screen that requested
     * the delete (Event Edit) navigates away — and its ViewModel gets cleared — well before the
     * undo window elapses; a viewModelScope job would be cancelled along with it, silently
     * dropping the delete entirely.
     */
    fun requestDeleteEvent(event: EventEntity) {
        pendingDeleteJobs.remove(event.href)?.cancel()
        _pendingDeleteHrefs.update { it + event.href }
        _lastPendingDelete.value = PendingDelete(event.href, event.summary)
        pendingDeleteJobs[event.href] = appScope.launch {
            delay(UNDO_DELETE_WINDOW_MS)
            runCatching { deleteEvent(event) }
            _pendingDeleteHrefs.update { it - event.href }
            pendingDeleteJobs.remove(event.href)
        }
    }

    /** Cancels a still-pending delete and makes the event visible again. No-op once the window
     *  has already elapsed and the real delete has happened. */
    fun undoDelete(href: String) {
        pendingDeleteJobs.remove(href)?.cancel()
        _pendingDeleteHrefs.update { it - href }
        if (_lastPendingDelete.value?.href == href) _lastPendingDelete.value = null
    }

    /** Called once the Undo snackbar for [lastPendingDelete] has been shown, so it isn't shown
     *  again (e.g. on a config change) without a new delete actually happening. */
    fun clearLastPendingDelete() {
        _lastPendingDelete.value = null
    }

    fun observeCalendars(): Flow<List<CalendarEntity>> = calendarDao.observeAll()

    /** calendarUrl -> cached event count, for the "N events" line on each calendar row in Settings. */
    fun observeEventCountsByCalendar(): Flow<Map<String, Int>> =
        eventDao.observeCountsByCalendar().map { rows -> rows.associate { it.calendarUrl to it.count } }

    fun searchEvents(query: String): Flow<List<EventEntity>> = eventDao.search(query)

    /**
     * Battery/sync-window fix — Search's on-demand widen fallback. The routine sync window only
     * covers ~90 days back/2 years forward (see CalDavClient's bounded range), so a search for
     * something from years ago wouldn't find anything in Room. Call this only once the local
     * search above has come up empty for a non-blank query (see SearchViewModel): queries every
     * enabled, writable, non-task calendar directly and unbounded, and returns results purely
     * for display — this never writes to Room, it's a one-off lookup, not a resync. Subscription
     * calendars are skipped: their calendar-query comes back empty regardless of filter (see
     * fetchEventsViaPropfind's doc comment), so there's nothing useful to ask them for here.
     */
    suspend fun searchEventsOnServer(query: String): List<EventEntity> {
        if (localMode.localOnlyEnabled.first()) return emptyList()
        val calendars = calendarDao.observeAll().first().filter {
            it.enabled && !it.isTaskList && !it.isSubscription && it.url != TAVLA_PSEUDO_CALENDAR_URL
        }
        return calendars.flatMap { cal ->
            runCatching { caldav.searchEventsUnbounded(cal.url, query) }
                .getOrDefault(emptyList())
                .let { buildEventEntities(cal, it) }
        }
    }

    /**
     * Feature #1: besides the existing local-only annotation (EventEntity.linkedContactUid, for
     * Zaman's own display), this now also pushes the link to Zaman's own Nextcloud sidecar
     * (`Zaman/contact-links.json`) so Vinci's "Linked items" section can show this event against
     * the contact, the same way it already does for Tavla/Koto/Nóta items. Still never writes an
     * ATTENDEE to the event's own ICS — the sidecar is an entirely separate file.
     */
    suspend fun linkContact(eventHref: String, contactUid: String, contactName: String) {
        eventDao.setLinkedContact(eventHref, contactUid, contactName)
        pushContactLink(eventHref, contactUid)
    }

    suspend fun unlinkContact(eventHref: String) {
        eventDao.setLinkedContact(eventHref, null, null)
        pushContactLink(eventHref, null)
    }

    /** Read-modify-write of the whole sidecar for just this one event's key — see
     *  ContactLinksSyncRepository's doc comment on why a full file rewrite is fine here. */
    private suspend fun pushContactLink(eventHref: String, contactUid: String?) {
        if (localMode.localOnlyEnabled.first()) return
        val event = eventDao.byHref(eventHref) ?: return
        runCatching {
            val current = contactLinks.pull().toMutableMap()
            if (contactUid != null) current[event.uid] = listOf(contactUid) else current.remove(event.uid)
            contactLinks.push(current)
        }
    }

    /**
     * Runs on every discovery pass (app launch, pull-to-refresh, periodic sync), pulling in any
     * contact link set from another device/session so it shows up here too — mirrors
     * restoreMissingCoversFromNextcloud's pattern. Only fills in events that don't already have a
     * LOCAL link (never overwrites a link the user just set from this device moments ago), and
     * only the first linked cardavUid per event — Zaman's own UI is still single-contact-per-event
     * (see EventEditViewModel.linkContact); the sidecar format itself supports a list, for when
     * that UI grows multi-contact support later. The display name is resolved from
     * ContactsRepository's own CardDAV read (the sidecar only ever stores the stable cardavUid,
     * never a display name) — falls back to the bare uid if contacts haven't synced yet, which
     * self-heals on the next pass once they have.
     */
    private suspend fun restoreContactLinksFromNextcloud() {
        if (localMode.localOnlyEnabled.first()) return
        runCatching {
            val remote = contactLinks.pull()
            if (remote.isEmpty()) return
            val contactsByUid = contactsRepo.contacts().associateBy { it.uid }
            val events = eventDao.allOnce().filter { it.linkedContactUid == null && remote.containsKey(it.uid) }
            events.forEach { event ->
                val cardavUid = remote[event.uid]?.firstOrNull() ?: return@forEach
                val name = contactsByUid[cardavUid]?.displayName ?: cardavUid
                eventDao.setLinkedContact(event.href, cardavUid, name)
            }
        }
    }

    /** Calendars Zaman can actually write a new event to — excludes subscriptions, Koto's task list, and Tavla's read-only pseudo-calendar. */
    fun observeWritableCalendars(): Flow<List<CalendarEntity>> =
        calendarDao.observeAll().map { list -> list.filter { !it.readOnly } }

    /**
     * Today/Month/wherever an agenda is shown read from here, not raw EventEntity rows — a
     * recurring event is stored once (its master DTSTART), so without expanding its RRULE here
     * it would only ever appear on its *original* start date and then vanish forever once that
     * date passed, even though the series is still going. See RRuleExpander and
     * EventDao.observeCandidatesForRange.
     */
    fun observeEventsInRange(startMillis: Long, endMillis: Long): Flow<List<AgendaEntry>> =
        eventDao.observeCandidatesForRange(startMillis, endMillis).map { candidates ->
            val zone = java.time.ZoneId.systemDefault()
            val rangeStart = java.time.Instant.ofEpochMilli(startMillis).atZone(zone)
            val rangeEnd = java.time.Instant.ofEpochMilli(endMillis).atZone(zone)
            val result = candidates.flatMap { event ->
                if (event.rrule == null) {
                    listOf(AgendaEntry(event, event.startEpochMillis, event.endEpochMillis))
                } else {
                    val startInstant = java.time.Instant.ofEpochMilli(event.startEpochMillis).atZone(zone)
                    val durationMillis = (event.endEpochMillis ?: (event.startEpochMillis + 3_600_000L)) - event.startEpochMillis
                    val exDates = event.exDatesJson?.let { json ->
                        runCatching {
                            val arr = org.json.JSONArray(json)
                            (0 until arr.length()).map { java.time.Instant.ofEpochMilli(arr.getLong(it)).atZone(zone) }
                        }.getOrDefault(emptyList())
                    } ?: emptyList()
                    RRuleExpander.expand(event.rrule, startInstant, exDates, rangeStart, rangeEnd).map { occStart ->
                        val occStartMillis = occStart.toInstant().toEpochMilli()
                        AgendaEntry(
                            event = event,
                            occurrenceStartMillis = occStartMillis,
                            occurrenceEndMillis = if (event.endEpochMillis != null) occStartMillis + durationMillis else null,
                        )
                    }
                }
            }
            result.sortedBy { it.occurrenceStartMillis }
        }

    /**
     * Same as [observeEventsInRange], filtered down to calendars the left-hand menu's checkboxes
     * have left visible. Today/Week/Month use this one; [observeEventsInRange] itself stays as
     * the unfiltered source for the widget (which has its own, separate per-instance selection —
     * see ZamanAgendaWidget) and for [findConflicts] (a hidden-but-still-synced calendar can
     * still clash with a new event, so that check deliberately ignores display visibility).
     */
    fun observeVisibleEventsInRange(startMillis: Long, endMillis: Long): Flow<List<AgendaEntry>> =
        combine(observeEventsInRange(startMillis, endMillis), hiddenCalendarUrls) { entries, hidden ->
            if (hidden.isEmpty()) entries else entries.filter { it.event.calendarUrl !in hidden }
        }

    suspend fun eventByHref(href: String): EventEntity? = eventDao.byHref(href)

    /**
     * Feature #6 — a lightweight overlap check, used by EventEditViewModel to WARN (never
     * block — Zaman has no notion of "busy" calendars or required attendees to actually enforce
     * anything) when the time being edited collides with another timed event. All-day events
     * (a whole-day "conflict" isn't a scheduling clash), Koto/Tavla rows (not real events on a
     * writable calendar), and the event being edited itself are all excluded.
     */
    suspend fun findConflicts(startMillis: Long, endMillis: Long, excludeHref: String?): List<AgendaEntry> =
        observeEventsInRange(startMillis, endMillis).first().filter { entry ->
            !entry.event.allDay &&
                entry.event.href != excludeHref &&
                entry.event.sourceApp == null &&
                entry.occurrenceStartMillis < endMillis &&
                (entry.occurrenceEndMillis ?: (entry.occurrenceStartMillis + 3_600_000L)) > startMillis
        }

    /**
     * Drag-and-drop reschedule (Month grid): moves a non-recurring, Zaman-writable event to a
     * different day, keeping its time-of-day and duration unchanged. Deliberately refuses a
     * recurring master (dragging one day of a series has real "this/future/all" scope questions
     * EventEditScreen's dialog already handles properly — a silent whole-series shift here would
     * surprise the user) and anything synced in from Koto/Tavla (not Zaman's to move). Returns
     * false (no-op) for either case so the caller can show a short explanation.
     */
    suspend fun rescheduleEvent(event: EventEntity, newDate: java.time.LocalDate): Boolean {
        if (event.rrule != null || event.sourceApp != null) return false
        val zone = java.time.ZoneId.systemDefault()
        val oldStart = java.time.Instant.ofEpochMilli(event.startEpochMillis).atZone(zone)
        val dayShift = java.time.temporal.ChronoUnit.DAYS.between(oldStart.toLocalDate(), newDate)
        if (dayShift == 0L) return true
        val newStart = oldStart.plusDays(dayShift)
        val newEnd = event.endEpochMillis?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).plusDays(dayShift)
        }
        updateEvent(
            existing = event,
            summary = event.summary,
            start = newStart,
            end = newEnd,
            allDay = event.allDay,
            location = event.location,
            description = event.description,
        )
        return true
    }

    suspend fun setCalendarEnabled(url: String, enabled: Boolean) =
        calendarDao.setEnabled(url, enabled)

    suspend fun setCalendarCover(url: String, path: String?) {
        calendarDao.setCoverImagePath(url, path)
        // Mirror the change to Nextcloud too (local-only installs have no server to talk to).
        // Best-effort — a failed upload/delete here never undoes the local change that already
        // happened; it just means the next discoverCalendars() restore pass has nothing to find.
        if (localMode.localOnlyEnabled.first()) return
        if (path != null) coverSync.uploadCover(url, java.io.File(path)) else coverSync.deleteCover(url)
    }

    /** Creates a brand-new event: writes to Nextcloud first (PUT with If-None-Match: *), then caches the result locally. */
    suspend fun createEvent(
        calendarUrl: String,
        summary: String,
        start: java.time.ZonedDateTime,
        end: java.time.ZonedDateTime?,
        allDay: Boolean,
        location: String?,
        description: String?,
        rrule: String? = null,
        reminderMinutesBeforeStart: Int? = null,
        extraReminderMinutes: List<Int> = emptyList(),
        categories: List<String> = emptyList(),
        attendees: List<com.brbrs.zaman.data.ical.EventAttendeeInput> = emptyList(),
    ) {
        val uid = java.util.UUID.randomUUID().toString()
        val href = "$calendarUrl${uid}.ics"
        val ics = ICalWriter.buildVEvent(
            uid, summary, start, end, allDay, location, description,
            reminderMinutesBeforeStart = reminderMinutesBeforeStart, extraReminderMinutes = extraReminderMinutes,
            rrule = rrule, categories = categories, attendees = attendees,
        )
        // Local-only mode: never touch Nextcloud — the event lives only in Room until
        // the user exports/imports it elsewhere.
        val etag = if (localMode.localOnlyEnabled.first()) null else caldav.putEvent(href, ics, ifMatchEtag = null)
        val entity = EventEntity(
            href = href,
            calendarUrl = calendarUrl,
            etag = etag,
            uid = uid,
            summary = summary,
            description = description,
            location = location,
            startEpochMillis = start.toInstant().toEpochMilli(),
            endEpochMillis = end?.toInstant()?.toEpochMilli(),
            allDay = allDay,
            rrule = rrule,
            recurrenceIdEpochMillis = null,
            icsRaw = ics,
            sourceApp = null,
            reminderMinutesBeforeStart = reminderMinutesBeforeStart,
            extraReminderMinutes = extraReminderMinutes.takeIf { it.isNotEmpty() }?.joinToString(","),
            categoriesJson = encodeCategories(categories),
            attendeesJson = attendees.takeIf { it.isNotEmpty() }
                ?.let { org.json.JSONArray(it.map { a -> a.displayName ?: a.email }).toString() },
            attendeeDetailsJson = attendees.takeIf { it.isNotEmpty() }
                ?.let { org.json.JSONArray(it.map { a ->
                    org.json.JSONObject().apply {
                        put("email", a.email)
                        a.displayName?.let { d -> put("displayName", d) }
                        put("partstat", "NEEDS-ACTION")
                    }
                }).toString() },
        )
        eventDao.insertIgnoring(listOf(entity))
        reminders.scheduleFor(entity)
    }

    /** Edits an existing, Zaman-writable event in place (If-Match the stored etag so a concurrent edit elsewhere is caught, not silently clobbered). */
    suspend fun updateEvent(
        existing: EventEntity,
        summary: String,
        start: java.time.ZonedDateTime,
        end: java.time.ZonedDateTime?,
        allDay: Boolean,
        location: String?,
        description: String?,
        rrule: String? = existing.rrule,
        reminderMinutesBeforeStart: Int? = existing.reminderMinutesBeforeStart,
        extraReminderMinutes: List<Int> = existing.allReminderMinutes().drop(1),
        /** Cancelled-occurrence dates to carry on the series — defaults to whatever this master
         *  already had, so an ordinary (non-scoped) edit never drops them. Feature #4's "this
         *  event" scope is the one caller that passes a changed list. Ignored when [rrule] ends
         *  up null. */
        exDates: List<Long> = existing.exDateMillis(),
        categories: List<String> = existing.categories(),
        attendees: List<com.brbrs.zaman.data.ical.EventAttendeeInput> = existing.attendeeDetails()
            .map { com.brbrs.zaman.data.ical.EventAttendeeInput(it.email, it.displayName, it.partstat) },
    ) {
        val zone = start.zone
        val ics = ICalWriter.buildVEvent(
            existing.uid, summary, start, end, allDay, location, description,
            sequence = 1, reminderMinutesBeforeStart = reminderMinutesBeforeStart, extraReminderMinutes = extraReminderMinutes,
            rrule = rrule,
            exDates = if (rrule != null) exDates.map { java.time.Instant.ofEpochMilli(it).atZone(zone) } else emptyList(),
            categories = categories, attendees = attendees,
        )
        val etag = if (localMode.localOnlyEnabled.first()) existing.etag else caldav.putEvent(existing.href, ics, ifMatchEtag = existing.etag)
        val updated = existing.copy(
            etag = etag,
            summary = summary,
            description = description,
            location = location,
            startEpochMillis = start.toInstant().toEpochMilli(),
            endEpochMillis = end?.toInstant()?.toEpochMilli(),
            allDay = allDay,
            rrule = rrule,
            icsRaw = ics,
            reminderMinutesBeforeStart = reminderMinutesBeforeStart,
            extraReminderMinutes = extraReminderMinutes.takeIf { it.isNotEmpty() }?.joinToString(","),
            exDatesJson = if (rrule != null && exDates.isNotEmpty()) org.json.JSONArray(exDates).toString() else null,
            categoriesJson = encodeCategories(categories),
            attendeesJson = attendees.takeIf { it.isNotEmpty() }
                ?.let { org.json.JSONArray(it.map { a -> a.displayName ?: a.email }).toString() },
            attendeeDetailsJson = attendees.takeIf { it.isNotEmpty() }
                ?.let { org.json.JSONArray(it.map { a ->
                    org.json.JSONObject().apply {
                        put("email", a.email)
                        a.displayName?.let { d -> put("displayName", d) }
                        put("partstat", a.partstat ?: "NEEDS-ACTION")
                    }
                }).toString() },
        )
        eventDao.update(listOf(updated))
        reminders.scheduleFor(updated) // re-arms with the new time/reminder, or cancels if reminder was cleared
    }

    /**
     * RSVP write-back: pushes a changed PARTSTAT for one attendee out to Nextcloud, patching the
     * existing ICS text in place (ICalWriter.setAttendeePartstat) rather than rebuilding the
     * VEVENT via buildVEvent, which never wrote ATTENDEE lines at all — see that function's doc
     * comment. Called from CalendarContractRepository.pushDirtyEventsFromContract whenever a
     * CalendarContract.Attendees row's ATTENDEE_STATUS no longer matches what Room has cached
     * (the user responded via the system Calendar app's own RSVP picker, or an app like Xin wrote
     * the row directly after sending an iTIP reply).
     */
    suspend fun updateAttendeeStatus(event: EventEntity, email: String, partstat: String) {
        val updatedIcs = ICalWriter.setAttendeePartstat(event.icsRaw, email, partstat)
        if (updatedIcs == event.icsRaw) return // no matching ATTENDEE line — nothing to push
        val etag = if (localMode.localOnlyEnabled.first()) event.etag else caldav.putEvent(event.href, updatedIcs, event.etag)
        val refreshedDetails = org.json.JSONArray(event.attendeeDetails().map { a ->
            org.json.JSONObject().apply {
                put("email", a.email)
                a.displayName?.let { put("displayName", it) }
                put("partstat", if (a.email.equals(email, ignoreCase = true)) partstat else a.partstat ?: "")
            }
        }).toString()
        val updated = event.copy(etag = etag, icsRaw = updatedIcs, attendeeDetailsJson = refreshedDetails)
        eventDao.update(listOf(updated))
    }

    /**
     * Feature #4 — edits one occurrence of a recurring series with real "this event / this and
     * following / all events" semantics, instead of the previous silent whole-series edit (and,
     * until the ICalWriter fix alongside this, a save that quietly dropped RRULE/EXDATE
     * entirely — see buildVEvent's doc comment).
     *
     * [occurrenceStartMillis] is the specific instance the user tapped (not necessarily the
     * master's own startEpochMillis), [start]/[end] are what they edited it to. The delta between
     * those two is what carries across to the series for ALL_EVENTS/THIS_AND_FUTURE, so a user
     * who nudges "next Tuesday's 10:00 standup" to 10:30 shifts every future occurrence by the
     * same 30 minutes, without needing to separately re-anchor the master's own original date.
     */
    suspend fun updateRecurringEvent(
        existing: EventEntity,
        occurrenceStartMillis: Long,
        scope: EditScope,
        summary: String,
        start: java.time.ZonedDateTime,
        end: java.time.ZonedDateTime?,
        allDay: Boolean,
        location: String?,
        description: String?,
        reminderMinutesBeforeStart: Int?,
        extraReminderMinutes: List<Int>,
    ) {
        val rrule = existing.rrule
        if (rrule == null) {
            // Shouldn't happen — EventEditViewModel only offers scope choices for a recurring
            // event — but fall back to a plain whole-event edit rather than crash.
            updateEvent(existing, summary, start, end, allDay, location, description)
            return
        }
        val deltaMillis = start.toInstant().toEpochMilli() - occurrenceStartMillis
        val zone = start.zone
        fun shifted(epochMillis: Long?): java.time.ZonedDateTime? =
            epochMillis?.let { java.time.Instant.ofEpochMilli(it + deltaMillis).atZone(zone) }

        when (scope) {
            EditScope.ALL_EVENTS -> {
                // Same delta applied to the master's own start/end, keeping its original
                // anchor date/duration — so editing ANY occurrence's time-of-day moves the
                // whole series by the same amount, regardless of which one was tapped.
                val newStart = shifted(existing.startEpochMillis)!!
                val newEnd = shifted(existing.endEpochMillis)
                updateEvent(existing, summary, newStart, newEnd, allDay, location, description)
            }
            EditScope.THIS_EVENT -> {
                // 1) Cancel just this occurrence on the master (EXDATE), leaving the master's
                //    own fields and every other occurrence untouched.
                val newExDates = (existing.exDateMillis() + occurrenceStartMillis).distinct()
                updateEvent(
                    existing, existing.summary,
                    java.time.Instant.ofEpochMilli(existing.startEpochMillis).atZone(zone),
                    existing.endEpochMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) },
                    existing.allDay, existing.location, existing.description,
                    rrule = rrule, exDates = newExDates,
                )
                // 2) Add the edited fields back as an independent, non-recurring event. Not a
                //    true RFC 5545 RECURRENCE-ID override sharing the master's UID/resource (a
                //    bigger multi-VEVENT-per-resource rewrite) — a detached one-off in the same
                //    calendar, which is what actually shows up on the agenda either way.
                createEvent(
                    calendarUrl = existing.calendarUrl, summary = summary, start = start, end = end,
                    allDay = allDay, location = location, description = description,
                    reminderMinutesBeforeStart = reminderMinutesBeforeStart, extraReminderMinutes = extraReminderMinutes,
                )
            }
            EditScope.THIS_AND_FUTURE -> {
                // 1) Close the master off right before this occurrence (UNTIL).
                val truncatedRrule = ICalWriter.truncateRRuleBefore(
                    rrule, java.time.Instant.ofEpochMilli(occurrenceStartMillis), existing.allDay,
                )
                updateEvent(
                    existing, existing.summary,
                    java.time.Instant.ofEpochMilli(existing.startEpochMillis).atZone(zone),
                    existing.endEpochMillis?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) },
                    existing.allDay, existing.location, existing.description,
                    rrule = truncatedRrule, exDates = existing.exDateMillis(),
                )
                // 2) Start a brand-new series from the edited occurrence onward, same RRULE
                //    (minus any UNTIL/COUNT the old one had), new fields, no carried-over
                //    exceptions — those all belonged to dates before this new series starts.
                createEvent(
                    calendarUrl = existing.calendarUrl, summary = summary, start = start, end = end,
                    allDay = allDay, location = location, description = description, rrule = rrule,
                    reminderMinutesBeforeStart = reminderMinutesBeforeStart, extraReminderMinutes = extraReminderMinutes,
                )
            }
        }
    }

    suspend fun deleteEvent(event: EventEntity) {
        if (!localMode.localOnlyEnabled.first()) caldav.deleteEvent(event.href, event.etag)
        eventDao.deleteByHref(event.href)
        reminders.cancelFor(event.href)
    }

    /**
     * Feature #5 — Koto task checkoff. Previously read-only (see discoverCalendars' doc comment
     * on isTaskList/readOnly): this is the one write path, scoped deliberately narrow to just
     * "mark complete" rather than full task editing. PUTs the VTODO back to Nextcloud with
     * STATUS:COMPLETED (see ICalWriter.markTodoCompleted), then removes it from the local cache
     * immediately — mirroring what the next syncTaskList pass would do anyway, since it only
     * ever keeps `!completed` todos — so it disappears from Today/Month right away instead of
     * waiting for the next periodic sync.
     */
    suspend fun completeTask(event: EventEntity) {
        if (!localMode.localOnlyEnabled.first()) {
            val updatedIcs = ICalWriter.markTodoCompleted(event.icsRaw)
            caldav.putEvent(event.href, updatedIcs, event.etag)
        }
        eventDao.deleteByHref(event.href)
    }

    /**
     * Discover calendars once (first login, or "Add calendar" refresh) and
     * merge new ones in. A VTODO-only collection is Nextcloud Tasks — Koto's
     * backend — so it's tagged sourceApp="KOTO" and marked isTaskList here;
     * everything else is treated as a normal event calendar.
     */
    suspend fun discoverCalendars() {
        // Local-only mode: Zaman never talks to Nextcloud. Discovery/sync both no-op —
        // the only way data moves is Export/Import (see ExportImportRepository).
        if (localMode.localOnlyEnabled.first()) return
        // Clean up any Deck-board calendars a previous (pre-fix) sync already inserted —
        // CalDavClient no longer returns them, but insertIgnoring() never removes a row that's
        // disappeared from discovery, so a stale install would keep them forever otherwise.
        calendarDao.deleteDeckBoardCalendars()
        val server = auth.currentServerUrl() ?: return
        val loginName = auth.currentLoginName() ?: return
        val discovered = caldav.discoverCalendars(server, loginName)
        calendarDao.insertIgnoring(
            discovered.map {
                val isTaskList = it.kind == CollectionKind.TASK_LIST
                CalendarEntity(
                    url = it.url,
                    displayName = it.displayName,
                    colorHex = it.color,
                    readOnly = it.readOnly || isTaskList, // Zaman reads Koto's tasks, doesn't write them back — v1 scope
                    isSubscription = it.isSubscription,
                    enabled = true,
                    syncToken = null,
                    sourceApp = if (isTaskList) "KOTO" else null,
                    isTaskList = isTaskList,
                    subscriptionSourceUrl = it.subscriptionSourceUrl,
                )
            }
        )
        registerTavlaPseudoCalendarIfConfigured()
        restoreMissingCoversFromNextcloud()
        restoreContactLinksFromNextcloud()
    }

    /**
     * Runs on every discovery pass (so: app launch, pull-to-refresh, periodic sync) and pulls
     * back any calendar cover that's missing locally but was previously uploaded to Nextcloud —
     * this is what makes a fresh install (after an uninstall, or a device switch) get covers back
     * on its own once the user signs back in, instead of leaving them gone for good.
     */
    private suspend fun restoreMissingCoversFromNextcloud() {
        val calendars = calendarDao.observeAll().first()
        val restored = runCatching { coverSync.reconcileCovers(calendars) }.getOrDefault(emptyMap())
        restored.forEach { (url, path) -> calendarDao.setCoverImagePath(url, path) }
    }

    /** Incremental sync for every enabled calendar; falls back to a full fetch when the token is stale. */
    suspend fun syncAllEnabled() {
        if (localMode.localOnlyEnabled.first()) return
        val calendars = calendarDao.observeAll().first().filter { it.enabled }
        calendars.forEach { runCatching { syncOne(it) } }
        if (deck.isConfigured()) runCatching { syncTavlaDueDates() }
    }

    /**
     * The single entry point both the "Sync now" button and the periodic WorkManager job
     * should call — previously "Sync now" only re-ran syncAllEnabled() against whatever
     * calendars were already in Room, so a login that discovered zero calendars (a flaky
     * PROPFIND, a timing issue right after approval) could never recover on its own; the
     * button would "succeed" forever against an empty list. This also records what actually
     * happened — counts and the first real error, if any — so it can be shown in the UI
     * instead of swallowed by a bare `runCatching {}`.
     */
    suspend fun syncNow() {
        if (localMode.localOnlyEnabled.first()) {
            syncStatusPrefs.markResult(SyncOutcome.SUCCESS, calendarDao.observeAll().first().size, eventDao.countAll(), "Local-only mode — not talking to Nextcloud.")
            return
        }
        syncStatusPrefs.markRunning()
        val discoverError = runCatching { discoverCalendars() }.exceptionOrNull()
        val calendars = calendarDao.observeAll().first().filter { it.enabled }
        var firstSyncError: Throwable? = null
        var failedCount = 0
        calendars.forEach { cal ->
            runCatching { syncOne(cal) }.onFailure { e ->
                failedCount++
                if (firstSyncError == null) firstSyncError = e
            }
        }
        val deckError = if (deck.isConfigured()) runCatching { syncTavlaDueDates() }.exceptionOrNull() else null

        val calendarsFound = calendarDao.observeAll().first().size
        val eventsCached = eventDao.countAll()
        val firstError = discoverError ?: firstSyncError ?: deckError
        val outcome = when {
            firstError != null && (calendars.isEmpty() || failedCount == calendars.size) -> SyncOutcome.FAILURE
            firstError != null -> SyncOutcome.PARTIAL_FAILURE
            calendarsFound == 0 -> SyncOutcome.FAILURE
            else -> SyncOutcome.SUCCESS
        }
        val message = when {
            firstError != null -> describeSyncError(firstError!!) + if (failedCount > 1) " (and ${failedCount - 1} other calendar${if (failedCount > 2) "s" else ""})" else ""
            calendarsFound == 0 -> "No calendars found on the server for this account. Check Settings → Account, or that the Nextcloud Calendar app has at least one calendar."
            else -> null
        }
        syncStatusPrefs.markResult(outcome, calendarsFound, eventsCached, message)
        widgetRefresher.refresh()
    }

    private fun describeSyncError(e: Throwable): String = when {
        e.message?.contains("401") == true -> "Authentication failed (401) — the stored app password may have been revoked. Try disconnecting and reconnecting."
        e.message?.contains("403") == true -> "Access denied (403) for one calendar — check its sharing permissions in Nextcloud."
        e.message?.contains("404") == true -> "A calendar returned 404 — it may have been deleted or renamed on the server."
        e.message?.contains("423") == true -> "Calendar locked (423) — another sync was in progress. Try again shortly."
        e.message?.contains("507") == true -> "Server ran out of storage (507) while syncing."
        e.message?.contains("Unable to resolve host") == true -> "Cannot reach the server — check your network connection."
        e.message?.contains("timeout", ignoreCase = true) == true -> "Connection to the server timed out."
        e.message.isNullOrBlank() -> "Sync failed (${e.javaClass.simpleName})."
        else -> "Sync failed: ${e.message}"
    }

    /**
     * A full-fetch (as opposed to sync-collection's incremental diff) used to unconditionally
     * wipe a calendar's cached events before storing whatever came back — correct when the fetch
     * actually reflects reality, but dangerous the moment it doesn't: a transient empty/partial
     * response (a flaky request, a server-side quirk) would delete perfectly good cached events
     * and replace them with nothing, with no way to recover except a lucky later sync. This was
     * exactly how "49 events cached" silently became "8" across a couple of real-world syncs —
     * confirmed via the ZamanSync diagnostic logs, where several real subscription calendars kept
     * coming back with a literally empty <d:multistatus/> on every full fetch. Only replace the
     * cache when the fetch actually returned something; an empty result is logged and otherwise
     * left alone, so a bad fetch degrades to "stale cache" instead of "no data at all".
     */
    private suspend fun replaceAllEventsIfNonEmpty(calendar: CalendarEntity, fetch: suspend () -> List<RawCalendarObject>) {
        val objects = fetch()
        if (objects.isEmpty()) {
            android.util.Log.w(
                "ZamanSync",
                "full fetch for ${calendar.displayName} (${calendar.url}) came back with 0 objects — leaving existing cached events untouched",
            )
            return
        }
        // One line per calendar is cheap and tells us, without ever needing BODY-level HTTP
        // logging again, whether a fetch actually produced data and roughly how much — "29
        // objects" for a subscription that used to show 0 confirms the fix without having to
        // read megabytes of raw ICS off logcat.
        android.util.Log.d("ZamanSync", "full fetch for ${calendar.displayName}: ${objects.size} objects")
        // Snapshot the OLD rows before wiping them — used below to skip re-arming AlarmManager
        // for events whose start time/reminder haven't actually changed. Without this, a
        // subscription calendar's full fetch (which, unlike incremental sync-collection, happens
        // on EVERY 15-minute periodic sync) was re-arming an OS alarm for every single event in
        // the feed every 15 minutes forever — hundreds of alarm calls/period for a large feed, a
        // real, measurable source of extra wakeups and battery drain for no behavioral benefit.
        val previousByHref = eventDao.byCalendarUrl(calendar.url).associateBy { it.href }
        val entities = buildEventEntities(calendar, objects)
        // Delete + insert as ONE atomic transaction (EventDao.replaceForCalendar), not two
        // separate calls — see its doc comment: issuing them separately made observeInRange()'s
        // Flow fire twice per sync, and the brief interim "this calendar's events are gone" state
        // was visible as events reordering/jumping in Today/Month while a sync ran with the app open.
        eventDao.replaceForCalendar(calendar.url, entities)
        scheduleChangedReminders(entities, previousByHref)
    }

    suspend fun syncOne(calendar: CalendarEntity) {
        // The Tavla pseudo-calendar is a Room-only placeholder row (its "url" is the fake
        // zaman://tavla-deck-due-dates key, not a real CalDAV collection) — it was falling
        // through to the real CalDAV sync path below, which tried to open that fake URL as an
        // HTTP request and failed with "Expected URL scheme 'http' or 'https' but was 'zaman'".
        // Its actual events come from syncTavlaDueDates() via Nextcloud Deck's REST API, called
        // separately in syncAllEnabled()/syncNow() — nothing to do here.
        if (calendar.url == TAVLA_PSEUDO_CALENDAR_URL) return
        if (calendar.isTaskList) {
            syncTaskList(calendar)
            return
        }
        // Subscription calendars (ICS feeds proxied by Nextcloud) don't support
        // sync-collection REPORT — they only serve GET / calendar-query.
        // For those, always do a full fetch instead of trying sync-collection first.
        //
        // confirmed (user checked Nextcloud's own web Calendar app): these events genuinely
        // exist server-side — calendar-query/REPORT specifically comes back empty against a
        // Nextcloud "subscription" collection (resourcetype <cs:subscribed/>), which is backed
        // by Nextcloud's own internal webcal cache rather than a normal queryable calendar
        // backend, even though the identical request shape works fine for owned calendars. Try
        // the REPORT first since it's cheaper when it works, and fall back to a plain PROPFIND
        // (see fetchEventsViaPropfind's doc comment) when it doesn't.
        if (calendar.isSubscription) {
            // Battery/sync-window fix: a subscription is one whole-feed HTTP resource, so it
            // can't be time-bounded the way an owned calendar's calendar-query can — the lever
            // here is conditional GET instead (see fetchEventsViaDirectGetConditional's doc
            // comment). Try that FIRST against subscriptionSourceUrl specifically, since that's
            // the attempt that actually succeeds for a real feed (see its own doc comment below)
            // — a 304 means this entire sync is a no-op: no download, no parse, no Room diff.
            val sourceUrl = calendar.subscriptionSourceUrl
            if (sourceUrl != null) {
                val conditional = runCatching {
                    caldav.fetchEventsViaDirectGetConditional(sourceUrl, calendar.feedEtag)
                }.getOrNull()
                if (conditional?.notModified == true) {
                    android.util.Log.d(
                        "ZamanSync",
                        "subscription ${calendar.displayName} unchanged since last sync (304) — skipping re-parse",
                    )
                    return
                }
                if (conditional != null && conditional.objects.isNotEmpty()) {
                    replaceAllEventsIfNonEmpty(calendar) { conditional.objects }
                    conditional.etag?.let { calendarDao.setFeedEtag(calendar.url, it) }
                    return
                }
            }
            // No subscriptionSourceUrl (no <cs:source> was returned), or the conditional fetch
            // above failed/came back empty — fall back to the old Nextcloud-mirror attempts.
            // These bypass the conditional-GET optimization (no per-attempt ETag to key off of
            // here), but they're also not the common path once subscriptionSourceUrl exists —
            // see its own doc comment for why the mirror itself is normally the broken one.
            replaceAllEventsIfNonEmpty(calendar) {
                caldav.fetchEventsFullRange(calendar.url)
                    .ifEmpty { caldav.fetchEventsViaPropfind(calendar.url) }
                    .ifEmpty { caldav.fetchEventsViaDirectGet(calendar.url) }
            }
            return
        }
        val result = try {
            caldav.syncCollection(calendar.url, calendar.syncToken)
        } catch (_: Exception) {
            // sync-collection failed (stale token, unsupported) — fall back to full fetch
            replaceAllEventsIfNonEmpty(calendar) { caldav.fetchEventsFullRange(calendar.url) }
            return
        }
        if (result.needsFullResync) {
            replaceAllEventsIfNonEmpty(calendar) { caldav.fetchEventsFullRange(calendar.url) }
            return
        }
        result.removedHrefs.forEach { eventDao.deleteByHrefAndVariants(it) }
        storeEvents(calendar, result.objects)
        calendarDao.setSyncToken(calendar.url, result.token)
    }

    /**
     * Tasks don't get sync-collection's incremental niceties here (v1 scope,
     * see README) — a task list is small enough that a full re-fetch on
     * every sync is cheap and far simpler than reconciling VTODO etags.
     */
    private suspend fun syncTaskList(calendar: CalendarEntity) {
        eventDao.deleteAllForCalendar(calendar.url)
        val objects = caldav.fetchTodosFullRange(calendar.url)
        val entities = objects.flatMap { obj ->
            val parsed = ICalParser.parseTodosWithRaw(obj.icsText)
            parsed
                .filter { (todo, _) -> !todo.completed && todo.due != null }
                .map { (todo, rawBlock) ->
                    EventEntity(
                        href = obj.href,
                        calendarUrl = calendar.url,
                        etag = obj.etag,
                        uid = todo.uid,
                        summary = todo.summary,
                        description = null,
                        location = null,
                        startEpochMillis = todo.due!!.instant.toInstant().toEpochMilli(),
                        endEpochMillis = null,
                        allDay = true,
                        rrule = null,
                        recurrenceIdEpochMillis = null,
                        // Each task's OWN VTODO block, not the whole resource text — same fix
                        // as buildEventEntities, see ICalParser.parseEventsWithRaw's doc comment.
                        icsRaw = if (parsed.size > 1) rawBlock else obj.icsText,
                        sourceApp = "KOTO",
                    )
                }
        }
        eventDao.insertIgnoring(entities)
    }

    /** Incremental path only (sync-collection's changed/new objects) — no prior delete, so no
     *  transaction/flicker concern; [buildEventEntities] + [scheduleChangedReminders] below are
     *  what the full-resync path (replaceAllEventsIfNonEmpty) also builds on. */
    private suspend fun storeEvents(calendar: CalendarEntity, objects: List<RawCalendarObject>) {
        val entities = buildEventEntities(calendar, objects)
        eventDao.insertIgnoring(entities)
        eventDao.update(entities) // IGNORE won't overwrite an existing href, so update covers the "changed" case
        // sync-collection only ever returns changed/new objects, so there's no "unchanged, skip
        // re-arming" case to diff against here — every entity in this batch is genuinely new/changed.
        scheduleChangedReminders(entities, previousByHref = emptyMap())
    }

    private fun buildEventEntities(calendar: CalendarEntity, objects: List<RawCalendarObject>): List<EventEntity> {
        return objects.flatMap { obj ->
            val parsed = ICalParser.parseEventsWithRaw(obj.icsText)
            // Almost every real CalDAV resource holds exactly one VEVENT, so obj.href (the
            // resource's own URL) doubles as EventEntity.href, its primary key. But an ICS
            // subscription calendar is synced as ONE resource per subscribed feed, containing
            // every VEVENT in that feed — and a recurring series' exception instances
            // (RECURRENCE-ID overrides) can likewise share one resource with their master.
            // Using obj.href unchanged for all of them collided on the primary key: insertIgnoring
            // kept only the first, and the following eventDao.update(entities) call then
            // overwrote that single row over and over, ending with just the LAST parsed event —
            // silently dropping every other event in the feed. Disambiguate with the event's own
            // UID (+ RECURRENCE-ID, for a recurring override) whenever a resource yields more than
            // one event; left untouched for the common single-event case so PUT/DELETE against the
            // server (which need the real resource href) keep working unchanged.
            parsed.map { (ev, rawBlock) ->
                val href = if (parsed.size > 1) {
                    buildString {
                        append(obj.href)
                        append("#uid=")
                        append(ev.uid)
                        ev.recurrenceId?.instant?.toInstant()?.toEpochMilli()?.let { append("#rid=$it") }
                    }
                } else obj.href
                // Each event's OWN VEVENT block, not the whole (possibly multi-event, possibly
                // multi-megabyte) obj.icsText — see ICalParser.parseEventsWithRaw's doc comment.
                val icsRaw = if (parsed.size > 1) rawBlock else obj.icsText
                EventEntity(
                    href = href,
                    calendarUrl = calendar.url,
                    etag = obj.etag,
                    uid = ev.uid,
                    summary = ev.summary,
                    description = ev.description,
                    location = ev.location,
                    startEpochMillis = ev.start.instant.toInstant().toEpochMilli(),
                    endEpochMillis = ev.end?.instant?.toInstant()?.toEpochMilli(),
                    allDay = ev.allDay,
                    rrule = ev.rrule,
                    recurrenceIdEpochMillis = ev.recurrenceId?.instant?.toInstant()?.toEpochMilli(),
                    icsRaw = icsRaw,
                    sourceApp = calendar.sourceApp,
                    reminderMinutesBeforeStart = ev.reminderMinutesBeforeStart,
                    extraReminderMinutes = ev.extraReminderMinutes.takeIf { it.isNotEmpty() }?.joinToString(","),
                    attendeesJson = if (ev.attendees.isEmpty()) null else org.json.JSONArray(ev.attendees).toString(),
                    attendeeDetailsJson = if (ev.attendeeDetails.isEmpty()) null else
                        org.json.JSONArray(ev.attendeeDetails.map { a ->
                            org.json.JSONObject().apply {
                                put("email", a.email)
                                a.displayName?.let { put("displayName", it) }
                                a.partstat?.let { put("partstat", it) }
                            }
                        }).toString(),
                    // Needed by RRuleExpander to skip a cancelled occurrence of a recurring
                    // series — see CalendarRepository.observeEventsInRange.
                    exDatesJson = if (ev.exDates.isEmpty()) null else
                        org.json.JSONArray(ev.exDates.map { it.instant.toInstant().toEpochMilli() }).toString(),
                    categoriesJson = encodeCategories(ev.categories),
                )
            }
        }
    }

    // A synced-in event can carry its own VALARM straight from Nextcloud — arm/re-arm it the
    // same way a locally-created one is. scheduleFor no-ops past events and cancels stale alarms
    // when a re-sync drops the reminder. But only when something reminder-relevant actually
    // changed vs. the previous sync (new event, or its start time/reminder offset changed) —
    // re-arming AlarmManager for an unchanged event achieves nothing (the alarm it's already
    // holding is identical) and was the main source of the extra wakeups/battery drain reported
    // after installing: a subscription calendar's full feed re-fetches (every 15 minutes, with no
    // incremental diffing available for ICS subscriptions) was re-arming every single event in
    // the feed every single time, changed or not.
    private fun scheduleChangedReminders(entities: List<EventEntity>, previousByHref: Map<String, EventEntity>) {
        entities.forEach { entity ->
            val previous = previousByHref[entity.href]
            val unchanged = previous != null &&
                previous.startEpochMillis == entity.startEpochMillis &&
                previous.reminderMinutesBeforeStart == entity.reminderMinutesBeforeStart &&
                previous.extraReminderMinutes == entity.extraReminderMinutes
            if (!unchanged) reminders.scheduleFor(entity)
        }
    }

    // --- Tavla (Nextcloud Deck) ---

    /**
     * Tavla talks to Nextcloud Deck's own OCS REST API, not CalDAV — a Deck
     * card's due date has no calendar-home-set entry to discover. Zaman
     * reads the same API read-only (same Nextcloud account, same app
     * password) rather than inventing a sidecar file Tavla doesn't
     * currently write — see DeckApiClient's doc comment for why.
     */
    private suspend fun registerTavlaPseudoCalendarIfConfigured() {
        if (!deck.isConfigured()) return
        calendarDao.insertIgnoring(
            listOf(
                CalendarEntity(
                    url = TAVLA_PSEUDO_CALENDAR_URL,
                    displayName = "Tavla — card due dates",
                    colorHex = "#3bb8c8",
                    readOnly = true,
                    isSubscription = false,
                    enabled = true,
                    syncToken = null,
                    sourceApp = "TAVLA",
                    isTaskList = false,
                )
            )
        )
    }

    private suspend fun syncTavlaDueDates() {
        val cards: List<DeckCardWithDueDate> = deck.fetchCardsWithDueDates()
        eventDao.deleteAllForCalendar(TAVLA_PSEUDO_CALENDAR_URL)
        val entities = cards.map { card ->
            EventEntity(
                href = "zaman://tavla-card/${card.cardId}",
                calendarUrl = TAVLA_PSEUDO_CALENDAR_URL,
                etag = null,
                uid = "tavla-card-${card.cardId}",
                summary = card.title,
                description = "On \"${card.boardTitle}\" · ${card.stackTitle}",
                location = null,
                startEpochMillis = card.dueEpochMillis,
                endEpochMillis = null,
                allDay = false,
                rrule = null,
                recurrenceIdEpochMillis = null,
                icsRaw = "",
                sourceApp = "TAVLA",
            )
        }
        eventDao.insertIgnoring(entities)
    }
}
