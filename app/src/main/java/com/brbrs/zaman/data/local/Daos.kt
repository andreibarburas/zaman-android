package com.brbrs.zaman.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendars ORDER BY sourceApp IS NOT NULL, displayName")
    fun observeAll(): Flow<List<CalendarEntity>>

    // INSERT OR IGNORE + UPDATE instead of OnConflictStrategy.REPLACE — REPLACE
    // triggers ON DELETE CASCADE on the events FK and would wipe cached events
    // on every re-discovery of an already-known calendar.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(calendars: List<CalendarEntity>)

    @Update
    suspend fun update(calendar: CalendarEntity)

    @Query("UPDATE calendars SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    @Query("UPDATE calendars SET syncToken = :token WHERE url = :url")
    suspend fun setSyncToken(url: String, token: String?)

    @Query("UPDATE calendars SET coverImagePath = :path WHERE url = :url")
    suspend fun setCoverImagePath(url: String, path: String?)

    /** Battery/sync-window fix — see CalendarEntity.feedEtag's doc comment. */
    @Query("UPDATE calendars SET feedEtag = :etag WHERE url = :url")
    suspend fun setFeedEtag(url: String, etag: String?)

    /** For Export/Import — a one-shot snapshot, not a Flow. */
    @Query("SELECT * FROM calendars")
    suspend fun allOnce(): List<CalendarEntity>

    /** One-time cleanup for calendars discovered before CalDavClient started excluding
     *  Nextcloud Deck's auto-generated per-board VTODO calendars (see discoverCalendars) —
     *  removes any that got into Room in an earlier sync. Cascades to their cached events too. */
    @Query("DELETE FROM calendars WHERE url LIKE '%app-generated--deck--%'")
    suspend fun deleteDeckBoardCalendars()

    /** Import needs to overwrite an existing row with the imported one — REPLACE is fine here
     *  because importAll() always re-inserts that calendar's events in the same transaction,
     *  right after, so the ON DELETE CASCADE this triggers never leaves anything orphaned. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(calendars: List<CalendarEntity>)
}

@Dao
interface EventDao {
    @Query(
        """
        SELECT events.* FROM events
        JOIN calendars ON calendars.url = events.calendarUrl
        WHERE calendars.enabled = 1
          AND events.startEpochMillis < :rangeEndMillis
          AND (
                (events.endEpochMillis IS NOT NULL AND events.endEpochMillis > :rangeStartMillis)
                OR
                (events.endEpochMillis IS NULL AND events.startEpochMillis >= :rangeStartMillis)
              )
        ORDER BY events.startEpochMillis
        """
    )
    fun observeInRange(rangeStartMillis: Long, rangeEndMillis: Long): Flow<List<EventEntity>>

    /**
     * Candidate set for RRULE expansion (see CalendarRepository.observeEventsInRange /
     * RRuleExpander): a non-recurring event still needs the same point-in-range check as
     * [observeInRange], but a recurring one (rrule IS NOT NULL) only needs its *series* to have
     * started before the window ends — its actual occurrence(s) inside the window are computed
     * in Kotlin afterwards, since SQL can't evaluate an RRULE. Without this, a recurring event
     * whose single stored DTSTART (the master occurrence) falls outside [rangeStart, rangeEnd)
     * would never surface here at all, even though the series has an occurrence today.
     */
    @Query(
        """
        SELECT events.* FROM events
        JOIN calendars ON calendars.url = events.calendarUrl
        WHERE calendars.enabled = 1
          AND events.startEpochMillis < :rangeEndMillis
          AND (
                events.rrule IS NOT NULL
                OR
                (events.endEpochMillis IS NOT NULL AND events.endEpochMillis > :rangeStartMillis)
                OR
                (events.endEpochMillis IS NULL AND events.startEpochMillis >= :rangeStartMillis)
              )
        ORDER BY events.startEpochMillis
        """
    )
    fun observeCandidatesForRange(rangeStartMillis: Long, rangeEndMillis: Long): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE href = :href LIMIT 1")
    suspend fun byHref(href: String): EventEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(events: List<EventEntity>)

    @Update
    suspend fun update(events: List<EventEntity>)

    @Query("DELETE FROM events WHERE href = :href")
    suspend fun deleteByHref(href: String)

    /** Also deletes any disambiguated rows stored as "<href>#uid=...[#rid=...]" for a resource
     *  that held more than one VEVENT (see CalendarRepository.storeEvents) — a server-reported
     *  removal of :href alone would otherwise leave those orphaned after an incremental sync. */
    @Query("DELETE FROM events WHERE href = :href OR href LIKE :href || '#uid=%'")
    suspend fun deleteByHrefAndVariants(href: String)

    @Query("DELETE FROM events WHERE calendarUrl = :calendarUrl")
    suspend fun deleteAllForCalendar(calendarUrl: String)

    /** Snapshot of one calendar's cached events before a full resync wipes them — lets
     *  CalendarRepository.replaceAllEventsIfNonEmpty diff old vs. new instead of re-arming every
     *  event's AlarmManager reminder on every resync (see storeEvents' battery-drain fix). */
    @Query("SELECT * FROM events WHERE calendarUrl = :calendarUrl")
    suspend fun byCalendarUrl(calendarUrl: String): List<EventEntity>

    /**
     * Atomically replaces everything cached for one calendar: delete-then-insert as a SINGLE
     * transaction, instead of CalendarRepository issuing the delete and the insert/update as
     * separate calls. With separate calls, observeInRange()'s Flow fired twice for one sync — once
     * right after the delete (with that calendar's events briefly missing/empty) and again once
     * the new rows landed — and the LazyColumn in Today/Month visibly reacted to that interim
     * state: events from OTHER calendars shifted position for a moment while this calendar's were
     * gone, read by the user as "events change their order" while a sync runs with the app open.
     * Wrapping both steps in one transaction makes Room's invalidation tracking (and therefore the
     * Flow) fire exactly once, with only the final, consistent result.
     */
    @androidx.room.Transaction
    suspend fun replaceForCalendar(calendarUrl: String, newEvents: List<EventEntity>) {
        deleteAllForCalendar(calendarUrl)
        insertIgnoring(newEvents)
        update(newEvents) // IGNORE won't overwrite an existing href, so update covers the "changed" case — mirrors storeEvents' own two-call pattern
    }

    /** For Export/Import — a one-shot snapshot, not a Flow. */
    @Query("SELECT * FROM events")
    suspend fun allOnce(): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(events: List<EventEntity>)

    @Query(
        """
        SELECT events.* FROM events
        JOIN calendars ON calendars.url = events.calendarUrl
        WHERE calendars.enabled = 1 AND events.summary LIKE '%' || :query || '%'
        ORDER BY events.startEpochMillis DESC
        LIMIT 100
        """
    )
    fun search(query: String): Flow<List<EventEntity>>

    /** For re-arming reminders after a reboot (AlarmManager alarms don't survive one) — see BootReceiver. */
    @Query("SELECT * FROM events WHERE reminderMinutesBeforeStart IS NOT NULL AND startEpochMillis > :nowMillis")
    suspend fun futureWithReminders(nowMillis: Long): List<EventEntity>

    @Query("UPDATE events SET linkedContactUid = :uid, linkedContactName = :name WHERE href = :href")
    suspend fun setLinkedContact(href: String, uid: String?, name: String?)

    /** For the sync-status card — a plain count, not a Flow. */
    @Query("SELECT COUNT(*) FROM events")
    suspend fun countAll(): Int

    /**
     * Per-calendar cached counts for the Calendars section in Settings — "N events" next to each
     * calendar row, so a subscription that's silently stuck at 0 (or any calendar whose sync is
     * failing) is visible at a glance instead of only showing up as a vague overall sync-status
     * line. A Flow, not a one-shot suspend fun, so the number updates live while a sync is
     * actually running rather than needing a manual screen refresh/reopen to see the new count.
     */
    @Query("SELECT calendarUrl, COUNT(*) as count FROM events GROUP BY calendarUrl")
    fun observeCountsByCalendar(): Flow<List<CalendarEventCount>>
}

data class CalendarEventCount(val calendarUrl: String, val count: Int)
