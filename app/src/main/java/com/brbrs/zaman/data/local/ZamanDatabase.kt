package com.brbrs.zaman.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CalendarEntity::class, EventEntity::class],
    // Bumped 1 -> 2: this session added isTaskList/reminderMinutesBeforeStart/attendeesJson/
    // linkedContactUid/linkedContactName without ever bumping this. Room validates the
    // compiled entity schema's hash against the one stored in the DB file on every open —
    // same version number but a different hash is NOT treated as "nothing to migrate", it's
    // treated as corruption and throws IllegalStateException on startup. A fresh install never
    // hit this (new DB, no old hash to compare against), but a reinstall/update over an
    // existing install would've crashed on launch. fallbackToDestructiveMigration(true) in
    // AppModule handles the actual migration (drops + recreates — fine, it's a sync cache).
    // 2 -> 3: added EventEntity.exDatesJson for RRULE expansion (see RRuleExpander/CalendarRepository).
    // 3 -> 4: forces every calendar to drop its stored syncToken and re-enumerate from scratch.
    // A calendar whose very first "initial" sync happened to fall back to the old
    // fetchEventsFullRange() calendar-query (see CalDavClient's VEVENT time-range fix) got a
    // real, valid sync-token established off a silently EMPTY result — every sync since then
    // was a correct incremental diff off a broken baseline, so it would never recover on its
    // own even after that query was fixed. Destroying the DB (fallbackToDestructiveMigration in
    // AppModule) is the only way to force a genuine from-scratch re-sync for already-installed
    // users; it's fine, this whole DB is just a sync cache.
    // 4 -> 5: added CalendarEntity.subscriptionSourceUrl, needed to fetch an ICS subscription's
    // events straight from its original feed instead of Nextcloud's own proxied mirror collection
    // (see that field's doc comment) — a destructive migration just drops the old column-less
    // rows and re-discovers, same as 3 -> 4.
    // 5 -> 6: added CalendarEntity.coverImagePath (purely local/cosmetic — a user-picked cover
    // photo, never synced). Unlike every previous bump, this one does NOT need
    // fallbackToDestructiveMigration's drop-and-rediscover: it doesn't touch events, sync tokens,
    // or anything CalDAV-correctness-related at all, and by now a full subscription resync is a
    // genuinely slow, multi-megabyte operation (see the ProtonCalendar feed) — forcing that again
    // just to add one nullable column would be a real, user-visible cost for literally no
    // behavioral reason. MIGRATION_5_6 below does the real, additive thing instead: ALTER TABLE
    // ADD COLUMN, nothing deleted. fallbackToDestructiveMigration still backstops any OTHER
    // future bump that doesn't get a real migration written for it.
    // 6 -> 7: added EventEntity.extraReminderMinutes (multiple reminders per event — see its doc
    // comment). Purely additive/local like 5 -> 6, so a real migration again instead of a
    // destructive resync.
    // 7 -> 8: added CalendarEntity.feedEtag — the bounded-sync-window/battery fix's conditional-GET
    // cache for a subscription's whole-feed fetch (see CalDavClient.fetchEventsViaDirectGet and
    // CalendarRepository.syncOne). Purely additive/local, same as 5 -> 6 and 6 -> 7.
    // 8 -> 9: added EventEntity.attendeeDetailsJson — RSVP write-back (structured attendee email/
    // displayName/PARTSTAT, mirrored into CalendarContract.Attendees and read back by
    // CalendarContractRepository.pushAttendeeStatusChanges). Purely additive/local, same pattern
    // as every bump since 5 -> 6.
    // 9 -> 10: added EventEntity.categoriesJson (CATEGORIES tags, round-tripped to the VEVENT's
    // own CATEGORIES line). Purely additive/local, same pattern as every bump since 5 -> 6.
    // 10 -> 11: one-time repair, no schema change. buildEventEntities/syncTaskList used to store
    // the WHOLE raw feed text (obj.icsText) as EventEntity.icsRaw for every event parsed out of
    // a multi-VEVENT CalDAV resource (an ICS subscription's single synced resource) — see
    // ICalParser.wrapAsStandaloneIcs's doc comment. For a feed with hundreds of events that's
    // the entire feed duplicated hundreds of times over in the events table; loading enough of
    // those rows at once (even a plain app-launch query) blew the CursorWindow heap with an
    // OutOfMemoryError in EventDao.observeCandidatesForRange. MIGRATION_10_11 truncates any
    // oversized stored value with raw SQL substr()/length() — never materializing the full text
    // into a Kotlin String — so it's safe to run even on an install that's currently crash-
    // looping on launch because of this. A later sync re-fills each event's icsRaw correctly
    // (now scoped to just that one VEVENT) the next time its calendar refreshes.
    version = 11,
    exportSchema = false,
)
abstract class ZamanDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao
    abstract fun eventDao(): EventDao
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE calendars ADD COLUMN coverImagePath TEXT")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN extraReminderMinutes TEXT")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE calendars ADD COLUMN feedEtag TEXT")
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN attendeeDetailsJson TEXT")
    }
}

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE events ADD COLUMN categoriesJson TEXT")
    }
}

val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // See this migration's doc comment on ZamanDatabase's @Database(version=...) above.
        // 4000 chars is generous for one real VEVENT/VTODO (a few KB at most) while being far
        // below anything that could repeat the CursorWindow OOM; markTodoCompleted/
        // setAttendeePartstat both no-op gracefully (return the text unchanged) if they can't
        // find a complete BEGIN/END pair in a truncated value, rather than crashing or corrupting
        // it — and the next sync overwrites it with the correct per-event text anyway.
        db.execSQL("UPDATE events SET icsRaw = substr(icsRaw, 1, 4000) WHERE length(icsRaw) > 4000")
    }
}
