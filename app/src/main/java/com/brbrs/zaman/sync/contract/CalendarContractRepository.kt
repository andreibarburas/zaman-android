package com.brbrs.zaman.sync.contract

import android.accounts.Account
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import com.brbrs.zaman.data.local.AttendeeDetail
import com.brbrs.zaman.data.local.CalendarDao
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.local.EventDao
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.local.allReminderMinutes
import com.brbrs.zaman.data.local.attendeeDetails
import com.brbrs.zaman.data.local.exDateMillis
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ZamanContractSync"

/** Events per ContentResolver.applyBatch() call, in syncEventsForCalendar — see applyBatched's
 *  doc comment. 200 events means up to ~600 ops in the worst case (one event + up to 2 reminders
 *  each), comfortably under the Binder transaction buffer's ~1MB ceiling. */
private const val EVENT_BATCH_SIZE = 200

/** One row read back off CalendarContract.Events while it's still DIRTY, enough to push out
 *  over Zaman's own CalDAV path — see CalendarContractRepository.pushDirtyEventsFromContract. */
private data class DirtyEventRow(
    val contractId: Long,
    val calendarUrl: String,
    val syncId: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val dtStartMillis: Long,
    val dtEndMillis: Long?,
    val allDay: Boolean,
    val deleted: Boolean,
)

/**
 * Feature #2 — the CalendarContract half of "replace DAVx5 for calendars". Mirrors Zaman's own
 * Room cache into Android's shared calendar provider, so the system Calendar app, widgets, and
 * other calendar-aware apps see Zaman's Nextcloud calendars as real device calendars — and pushes
 * edits made directly in the system Calendar app back out over Zaman's existing CalDAV path.
 *
 * Deliberately NOT a second CalDAV poller: this never talks to Nextcloud directly. The pull
 * direction (Nextcloud -> Room) stays exactly as it already was — CalDavSyncWorker calling
 * CalendarRepository.syncNow() on whatever schedule Settings -> Sync has configured (see the
 * battery/sync-window fix). This repository only mirrors whatever Room already has into the
 * provider, and pushes local CalendarContract edits through CalendarRepository's existing
 * create/update/delete methods — which is what actually talks to the server for the push side.
 *
 * Koto's task list and Tavla's pseudo-calendar are deliberately excluded (see
 * [mirrorableCalendars]) — CalendarContract is for real calendar events; a VTODO task list isn't
 * one, and Tavla's due-date cards are a synthetic read-only overlay with no CalDAV resource of
 * their own to push edits back to.
 *
 * A provider-sourced edit to a RECURRING event is always treated as editing the whole series —
 * feature #4's "this event"/"this and following" scope dialog is an Event-Edit-screen-only
 * affordance; the system Calendar app has no way to signal that choice.
 */
@Singleton
class CalendarContractRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
    private val calendarRepository: CalendarRepository,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Guards the whole push-then-mirror sequence in [sync]. Without this, two overlapping
     * callers (e.g. the Settings toggle's immediate first sync landing at the same moment as
     * CalDavSyncWorker's own post-pull sync) each run their own independent
     * "query existing _SYNC_ID -> insert if missing" pass over the same calendar; neither sees
     * the other's not-yet-committed insert, so the same event gets inserted twice under two
     * different Events._ID rows sharing one _SYNC_ID — which is exactly what showed up as
     * duplicated events in the system Calendar app and its home-screen widget. A ContentResolver
     * call has no cross-table transaction to lean on here, so serializing entry is the fix.
     */
    private val syncMutex = Mutex()

    private fun Uri.asSyncAdapter(account: Account): Uri =
        buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
            .build()

    private fun mirrorableCalendars(all: List<CalendarEntity>): List<CalendarEntity> =
        all.filter { !it.isTaskList && it.url != TAVLA_PSEUDO_CALENDAR_URL }

    /**
     * Push dirty CalendarContract rows out to Nextcloud, then remirror Room -> CalendarContract.
     * Called by both ZamanCalendarSyncAdapter (OS-triggered — e.g. the user edited an event in
     * the system Calendar app, Android noticed the dirty row and invoked the sync adapter) and
     * CalDavSyncWorker (after its own CalDAV pull), so the two possible entry points always
     * converge on the same push-then-mirror sequence.
     */
    suspend fun sync() = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                if (ZamanAccount.existsOrNull(context) == null) return@runCatching
                pushDirtyEventsFromContract()
                syncCalendarsToContract()
                syncAllEventsToContract()
            }.onFailure { e -> android.util.Log.w(TAG, "sync() failed", e) }
        }
    }

    // -- Calendars: Room -> CalendarContract ---------------------------------------------------

    suspend fun syncCalendarsToContract() = withContext(Dispatchers.IO) {
        val account = ZamanAccount.get(context)
        val calendars = mirrorableCalendars(calendarDao.observeAll().first())
        calendars.forEach { cal -> upsertCalendar(account, cal) }

        // Self-heals duplicate Calendars rows — the same pre-Mutex race that duplicated Events
        // rows (see syncMutex's doc comment) could just as easily have duplicated a Calendars
        // row itself: two rows sharing one NAME (= Zaman calendar url), each with its own full
        // mirrored event set. findCalendarId only ever sees the first (lowest _ID) of a
        // duplicate pair, so the other one is invisible to every later upsert/mirror pass and
        // just sits there forever as a second, stale copy of every event — which is exactly what
        // shows up as duplicated events in the system Calendar app even after the Events-level
        // dedup. Deleting a Calendars row cascades and removes its Events/Reminders too, so nothing
        // further is needed to clean those up.
        val keepUrls = calendars.map { it.url }.toSet()
        val keptIdByUrl = mutableMapOf<String, Long>()
        val toRemove = mutableListOf<Long>()
        resolver.query(
            Calendars.CONTENT_URI.asSyncAdapter(account),
            arrayOf(Calendars._ID, Calendars.NAME),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(account.name, account.type),
            "${Calendars._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val url = cursor.getString(1) ?: continue
                val id = cursor.getLong(0)
                when {
                    url !in keepUrls -> toRemove += id // orphaned — Zaman calendar gone/excluded
                    keptIdByUrl[url] == null -> keptIdByUrl[url] = id // first (lowest _ID) copy kept
                    else -> toRemove += id // a duplicate of one already kept
                }
            }
        }
        toRemove.forEach { id ->
            resolver.delete(ContentUris.withAppendedId(Calendars.CONTENT_URI, id).asSyncAdapter(account), null, null)
        }
    }

    private fun upsertCalendar(account: Account, calendar: CalendarEntity) {
        val existingId = findCalendarId(account, calendar.url)
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, account.name)
            put(Calendars.ACCOUNT_TYPE, account.type)
            put(Calendars.NAME, calendar.url)
            put(Calendars.CALENDAR_DISPLAY_NAME, calendar.displayName)
            put(Calendars.CALENDAR_COLOR, parseColor(calendar.colorHex))
            put(Calendars.CALENDAR_ACCESS_LEVEL, if (calendar.readOnly) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, account.name)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, if (calendar.enabled) 1 else 0)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        if (existingId != null) {
            resolver.update(ContentUris.withAppendedId(Calendars.CONTENT_URI, existingId).asSyncAdapter(account), values, null, null)
        } else {
            resolver.insert(Calendars.CONTENT_URI.asSyncAdapter(account), values)
        }
    }

    private fun findCalendarId(account: Account, calendarUrl: String): Long? {
        resolver.query(
            Calendars.CONTENT_URI.asSyncAdapter(account),
            arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ? AND ${Calendars.NAME} = ?",
            arrayOf(account.name, account.type, calendarUrl),
            null,
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    private fun parseColor(hex: String?): Int =
        hex?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: FALLBACK_COLOR

    // -- Events: Room -> CalendarContract ------------------------------------------------------

    suspend fun syncAllEventsToContract() = withContext(Dispatchers.IO) {
        val account = ZamanAccount.get(context)
        val calendars = mirrorableCalendars(calendarDao.observeAll().first())
        calendars.forEach { cal -> syncEventsForCalendar(account, cal) }
    }

    private suspend fun syncEventsForCalendar(account: Account, calendar: CalendarEntity) {
        val contractCalendarId = findCalendarId(account, calendar.url) ?: return
        // sourceApp != null rows (Koto/Tavla items) shouldn't end up on a real event calendar in
        // the first place, but excluded defensively regardless — never mirrored.
        val localEvents = eventDao.byCalendarUrl(calendar.url).filter { it.sourceApp == null }
        val localHrefs = localEvents.map { it.href }.toSet()

        val existing = mutableMapOf<String, Long>() // href (_SYNC_ID) -> Contract Events._ID
        // Self-heals any duplicate rows left over from the pre-Mutex race (see syncMutex's doc
        // comment): if the same _SYNC_ID shows up more than once under this calendar, keep the
        // lowest _ID and delete the rest rather than letting [existing] silently overwrite down
        // to one — an overwrite alone would stop new duplicates but never remove the old ones.
        val duplicateIdsToDelete = mutableListOf<Long>()
        resolver.query(
            Events.CONTENT_URI.asSyncAdapter(account),
            arrayOf(Events._ID, Events._SYNC_ID),
            "${Events.CALENDAR_ID} = ?",
            arrayOf(contractCalendarId.toString()),
            "${Events._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val syncId = cursor.getString(1) ?: continue
                val id = cursor.getLong(0)
                val keptId = existing[syncId]
                if (keptId == null) existing[syncId] = id else duplicateIdsToDelete += id
            }
        }
        val staleIds = existing.filterKeys { it !in localHrefs }.values.toList()
        // Deletes (duplicate cleanup + stale rows) cost little in count, but batching them too
        // avoids yet another per-row round trip on top of the events batch below.
        applyBatched(account, (duplicateIdsToDelete + staleIds).map { id ->
            ContentProviderOperation.newDelete(ContentUris.withAppendedId(Events.CONTENT_URI, id).asSyncAdapter(account)).build()
        })

        // The actual fix for "stuck in syncing": with thousands of cached events, inserting or
        // updating them one ContentResolver call at a time is thousands of cross-process Binder
        // round trips to the calendar provider — easily minutes of apparent hang on first
        // enable. ContentResolver.applyBatch collapses a whole chunk into one round trip, same
        // technique DAVx5 itself relies on for bulk CalendarContract writes. A new event's real
        // _ID isn't known until its insert op actually runs, so its reminder ops reference it by
        // withValueBackReference (its position within THIS CHUNK's op list) rather than a value;
        // an existing event's _ID is already known, so its reminders use it directly.
        localEvents.chunked(EVENT_BATCH_SIZE).forEach { chunk ->
            val ops = ArrayList<ContentProviderOperation>()
            chunk.forEach { event ->
                val values = eventContentValues(contractCalendarId, event)
                val existingId = existing[event.href]
                val reminderMinutes = event.allReminderMinutes()
                val attendees = event.attendeeDetails()
                if (existingId != null) {
                    ops += ContentProviderOperation
                        .newUpdate(ContentUris.withAppendedId(Events.CONTENT_URI, existingId).asSyncAdapter(account))
                        .withValues(values).build()
                    ops += ContentProviderOperation
                        .newDelete(Reminders.CONTENT_URI.asSyncAdapter(account))
                        .withSelection("${Reminders.EVENT_ID} = ?", arrayOf(existingId.toString())).build()
                    reminderMinutes.forEach { minutes ->
                        ops += reminderOp(account).withValue(Reminders.EVENT_ID, existingId)
                            .withValue(Reminders.MINUTES, minutes).withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
                    }
                    // Attendees are re-mirrored wholesale (delete then re-insert) rather than
                    // diffed, same as Reminders just above — cheap, since a VEVENT rarely has
                    // more than a handful. This only ever runs right after pushDirtyEventsFromContract
                    // already pushed any PARTSTAT change out and persisted it back into Room (see
                    // CalendarRepository.updateAttendeeStatus), so re-mirroring here reflects that
                    // same, now-current status straight back — it does not clobber a pending
                    // local edit the way an unconditional re-mirror of title/time could.
                    ops += ContentProviderOperation
                        .newDelete(Attendees.CONTENT_URI.asSyncAdapter(account))
                        .withSelection("${Attendees.EVENT_ID} = ?", arrayOf(existingId.toString())).build()
                    attendees.forEach { a ->
                        ops += attendeeOp(account).withValue(Attendees.EVENT_ID, existingId)
                            .withValues(attendeeContentValues(a)).build()
                    }
                } else {
                    val eventOpIndex = ops.size
                    ops += ContentProviderOperation.newInsert(Events.CONTENT_URI.asSyncAdapter(account)).withValues(values).build()
                    reminderMinutes.forEach { minutes ->
                        ops += reminderOp(account).withValueBackReference(Reminders.EVENT_ID, eventOpIndex)
                            .withValue(Reminders.MINUTES, minutes).withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
                    }
                    attendees.forEach { a ->
                        ops += attendeeOp(account).withValueBackReference(Attendees.EVENT_ID, eventOpIndex)
                            .withValues(attendeeContentValues(a)).build()
                    }
                }
            }
            applyBatched(account, ops)
        }
    }

    private fun reminderOp(account: Account): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(Reminders.CONTENT_URI.asSyncAdapter(account))

    private fun attendeeOp(account: Account): ContentProviderOperation.Builder =
        ContentProviderOperation.newInsert(Attendees.CONTENT_URI.asSyncAdapter(account))

    private fun attendeeContentValues(a: AttendeeDetail): ContentValues = ContentValues().apply {
        put(Attendees.ATTENDEE_EMAIL, a.email)
        put(Attendees.ATTENDEE_NAME, a.displayName ?: a.email)
        put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
        put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED)
        put(Attendees.ATTENDEE_STATUS, partstatToContractStatus(a.partstat))
    }

    /** iCal PARTSTAT -> CalendarContract.Attendees.ATTENDEE_STATUS; see [contractStatusToPartstat]
     *  for the reverse direction used when reading an RSVP change back out of the provider. */
    private fun partstatToContractStatus(partstat: String?): Int = when (partstat) {
        "ACCEPTED" -> Attendees.ATTENDEE_STATUS_ACCEPTED
        "DECLINED" -> Attendees.ATTENDEE_STATUS_DECLINED
        "TENTATIVE" -> Attendees.ATTENDEE_STATUS_TENTATIVE
        "NEEDS-ACTION" -> Attendees.ATTENDEE_STATUS_INVITED
        else -> Attendees.ATTENDEE_STATUS_NONE
    }

    private fun contractStatusToPartstat(status: Int): String = when (status) {
        Attendees.ATTENDEE_STATUS_ACCEPTED -> "ACCEPTED"
        Attendees.ATTENDEE_STATUS_DECLINED -> "DECLINED"
        Attendees.ATTENDEE_STATUS_TENTATIVE -> "TENTATIVE"
        Attendees.ATTENDEE_STATUS_INVITED -> "NEEDS-ACTION"
        else -> "NEEDS-ACTION"
    }

    /** Runs [ops] through [ContentResolver.applyBatch], chunked to [EVENT_BATCH_SIZE]-sized
     *  pieces if the caller handed over more than that in one go (the delete batches above can,
     *  for a calendar with a lot of stale rows) — Android's Binder transaction buffer has a ~1MB
     *  ceiling per call, so an unbounded batch risks a TransactionTooLargeException on a big
     *  enough backlog. A failed chunk is logged and skipped rather than aborting the whole sync;
     *  it'll simply be retried on the next sync pass since Room still has the real data. */
    private fun applyBatched(account: Account, ops: List<ContentProviderOperation>) {
        if (ops.isEmpty()) return
        ops.chunked(EVENT_BATCH_SIZE).forEach { chunk ->
            runCatching { resolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(chunk)) }
                .onFailure { e -> android.util.Log.w(TAG, "applyBatch failed for ${chunk.size} ops", e) }
        }
    }

    private fun eventContentValues(calendarId: Long, event: EventEntity): ContentValues = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events._SYNC_ID, event.href)
        put(Events.TITLE, event.summary)
        put(Events.DESCRIPTION, event.description)
        put(Events.EVENT_LOCATION, event.location)
        put(Events.ALL_DAY, if (event.allDay) 1 else 0)
        put(Events.STATUS, Events.STATUS_CONFIRMED)
        put(Events.HAS_ALARM, if (event.allReminderMinutes().isNotEmpty()) 1 else 0)
        if (event.allDay) {
            // CalendarContract wants all-day events anchored to UTC midnight regardless of the
            // device's own zone — a naive local-zone epoch here is the classic "all-day event
            // shows up on the wrong day" bug.
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, utcMidnight(event.startEpochMillis))
            put(Events.DTEND, utcMidnight(event.endEpochMillis ?: (event.startEpochMillis + 86_400_000L)))
        } else {
            put(Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
            put(Events.DTSTART, event.startEpochMillis)
            if (event.endEpochMillis != null) put(Events.DTEND, event.endEpochMillis) else put(Events.DURATION, "PT1H")
        }
        if (event.rrule != null) {
            put(Events.RRULE, event.rrule)
            val exDates = event.exDateMillis()
            if (exDates.isNotEmpty()) put(Events.EXDATE, exDates.joinToString(",") { exDateStamp(it, event.allDay) })
            // An event with an RRULE can't also carry its own DTEND per CalendarContract's rules
            // when expressed via DURATION-only recurrence in some providers — DTEND alongside
            // RRULE is accepted by AOSP's calendar provider though, so no special-casing needed
            // beyond what's already set above.
        }
    }

    private fun utcMidnight(epochMillis: Long): Long =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun exDateStamp(epochMillis: Long, allDay: Boolean): String {
        val zone = if (allDay) ZoneId.of("UTC") else ZoneId.systemDefault()
        val zdt = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return if (allDay) {
            DateTimeFormatter.BASIC_ISO_DATE.format(zdt.toLocalDate())
        } else {
            zdt.withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        }
    }

    // -- Events: CalendarContract -> Nextcloud (push) ----------------------------------------

    /** Pushes anything the user created/edited/deleted directly in the system Calendar app (a
     *  DIRTY=1 row under Zaman's own account) back out over Zaman's existing CalDAV write path.
     *  A dirty row under a read-only (e.g. subscription) calendar is skipped as a defensive
     *  backstop — the OS shouldn't have let the user edit one in the first place. */
    suspend fun pushDirtyEventsFromContract() = withContext(Dispatchers.IO) {
        val account = ZamanAccount.get(context)
        val calendars = mirrorableCalendars(calendarDao.observeAll().first())
        val writableUrlById = calendars.filter { !it.readOnly }
            .mapNotNull { cal -> findCalendarId(account, cal.url)?.let { it to cal.url } }
            .toMap()
        if (writableUrlById.isEmpty()) return@withContext

        val rows = mutableListOf<DirtyEventRow>()
        resolver.query(
            Events.CONTENT_URI.asSyncAdapter(account),
            arrayOf(
                Events._ID, Events.CALENDAR_ID, Events._SYNC_ID, Events.TITLE, Events.DESCRIPTION,
                Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.DELETED,
            ),
            "${Events.DIRTY} = 1",
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calendarId = cursor.getLong(1)
                val calendarUrl = writableUrlById[calendarId] ?: continue
                rows += DirtyEventRow(
                    contractId = cursor.getLong(0),
                    calendarUrl = calendarUrl,
                    syncId = cursor.getString(2),
                    title = cursor.getString(3),
                    description = cursor.getString(4),
                    location = cursor.getString(5),
                    dtStartMillis = cursor.getLong(6),
                    dtEndMillis = if (cursor.isNull(7)) null else cursor.getLong(7),
                    allDay = cursor.getInt(8) == 1,
                    deleted = cursor.getInt(9) == 1,
                )
            }
        }

        rows.forEach { row ->
            runCatching { pushOneDirtyRow(account, row) }
                .onFailure { e -> android.util.Log.w(TAG, "push failed for contract event ${row.contractId}", e) }
        }
    }

    private suspend fun pushOneDirtyRow(account: Account, row: DirtyEventRow) {
        if (row.deleted) {
            row.syncId?.let { href -> eventDao.byHref(href)?.let { calendarRepository.deleteEvent(it) } }
            resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, row.contractId).asSyncAdapter(account), null, null)
            return
        }

        val zone = if (row.allDay) ZoneId.of("UTC") else ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(row.dtStartMillis).atZone(zone)
        val end = row.dtEndMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
        val title = row.title?.takeIf { it.isNotBlank() } ?: "(untitled)"

        if (row.syncId == null) {
            // Created directly in the system Calendar app. createEvent's own href is
            // deterministic but not handed back here — simplest correct fix is to let the
            // remirror pass that always follows this in sync() insert the matching Contract row
            // fresh, rather than guessing its href here. Delete this clientless placeholder now
            // so the remirror doesn't leave a duplicate sitting next to the real one.
            calendarRepository.createEvent(
                calendarUrl = row.calendarUrl, summary = title, start = start, end = end,
                allDay = row.allDay, location = row.location, description = row.description,
            )
            resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, row.contractId).asSyncAdapter(account), null, null)
        } else {
            val existing = eventDao.byHref(row.syncId) ?: return
            calendarRepository.updateEvent(
                existing = existing, summary = title, start = start, end = end,
                allDay = row.allDay, location = row.location, description = row.description,
            )
            pushAttendeeStatusChanges(row.contractId, eventDao.byHref(row.syncId) ?: existing)
            resolver.update(
                ContentUris.withAppendedId(Events.CONTENT_URI, row.contractId).asSyncAdapter(account),
                ContentValues().apply { put(Events.DIRTY, 0) },
                null, null,
            )
        }
    }

    /**
     * RSVP write-back, the other half of [pushOneDirtyRow]: reads whatever CalendarContract.
     * Attendees rows this event now has (Xin, or the system Calendar app's own RSVP picker, may
     * have changed one's ATTENDEE_STATUS as part of the same DIRTY=1 batch) and pushes any PARTSTAT
     * that no longer matches what Room has cached out to Nextcloud via
     * CalendarRepository.updateAttendeeStatus, one attendee at a time. Relies on the caller (both
     * the system Calendar app's RSVP flow and Xin's) setting Events.DIRTY=1 in the same
     * ContentProviderOperation batch as the Attendees update — the provider does not do this
     * automatically for an Attendees-only write, so without that this change would never surface
     * as a dirty row for pushOneDirtyRow to find in the first place.
     */
    private suspend fun pushAttendeeStatusChanges(contractEventId: Long, cached: EventEntity) {
        val account = ZamanAccount.get(context)
        val cachedByEmail = cached.attendeeDetails().associateBy { it.email.lowercase() }
        resolver.query(
            Attendees.CONTENT_URI.asSyncAdapter(account),
            arrayOf(Attendees.ATTENDEE_EMAIL, Attendees.ATTENDEE_STATUS),
            "${Attendees.EVENT_ID} = ?",
            arrayOf(contractEventId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val email = cursor.getString(0)?.lowercase() ?: continue
                val newPartstat = contractStatusToPartstat(cursor.getInt(1))
                val cachedPartstat = cachedByEmail[email]?.partstat
                if (cachedPartstat != newPartstat) {
                    runCatching { calendarRepository.updateAttendeeStatus(cached, email, newPartstat) }
                        .onFailure { e -> android.util.Log.w(TAG, "attendee push failed for $email", e) }
                }
            }
        }
    }
}

/** Mirrors CalendarRepository's own pseudo-calendar key — kept in sync with it rather than
 *  importing a private constant across files. */
private const val TAVLA_PSEUDO_CALENDAR_URL = "zaman://tavla-deck-due-dates"
private const val FALLBACK_COLOR = 0xFF4A6FA5.toInt()
