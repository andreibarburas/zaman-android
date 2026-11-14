package com.brbrs.zaman.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "calendars")
data class CalendarEntity(
    @PrimaryKey val url: String,
    val displayName: String,
    val colorHex: String?,
    val readOnly: Boolean,
    val isSubscription: Boolean,
    val enabled: Boolean,
    val syncToken: String?,
    /** source badge shown on items that originate from another suite app, e.g. "KOTO" / "TAVLA" — null for native CalDAV calendars */
    val sourceApp: String?,
    /** true for a VTODO-only collection (Nextcloud Tasks / Koto's backend) — read via fetchTodosFullRange, not calendar-query/sync-collection */
    val isTaskList: Boolean = false,
    /**
     * For an ICS subscription only: the original webcal/https feed URL from Nextcloud's own
     * <cs:source> property — the feed the user actually subscribed to, as opposed to [url], which
     * is Nextcloud's own proxied mirror collection for it. Nextcloud's mirror turned out to be
     * unreliable to query directly (calendar-query/REPORT comes back empty, and even its own
     * PROPFIND exposes no real child resources — see CalDavClient.fetchEventsViaDirectGet's doc
     * comment) — strongly suggesting Nextcloud itself doesn't always have a populated server-side
     * cache for a subscription to serve from. Fetching the original feed straight from its source
     * sidesteps that layer entirely. Null for a normal (non-subscription) calendar, or if a
     * subscription somehow has no <cs:source> (falls back to the old Nextcloud-mirror path).
     */
    val subscriptionSourceUrl: String? = null,
    /**
     * Absolute path to a user-picked cover image for this calendar, copied into the app's private
     * storage (see CalendarsViewModel.setCoverImage) — never a content:// URI, which only stays
     * valid for as long as the originating gallery app grants a (often one-shot) permission grant.
     * Shown as a small thumbnail in Settings' Calendars list, on agenda rows, and in Month's
     * day-cell dots, purely cosmetic (never synced to Nextcloud or anywhere else).
     */
    val coverImagePath: String? = null,
    /**
     * Battery/sync-window fix: the whole-feed ETag from the last successful fetch of a
     * subscription's single-resource ICS body (fetchEventsViaDirectGet) — lets the next sync
     * send `If-None-Match` and get back a cheap 304 instead of re-downloading and re-parsing the
     * entire feed when nothing actually changed. Null for a non-subscription calendar, or a
     * subscription whose server doesn't return an ETag on that resource.
     */
    val feedEtag: String? = null,
)

@Entity(
    tableName = "events",
    primaryKeys = ["href"],
    foreignKeys = [ForeignKey(
        entity = CalendarEntity::class,
        parentColumns = ["url"],
        childColumns = ["calendarUrl"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("calendarUrl"), Index("uid")],
)
data class EventEntity(
    val href: String,
    val calendarUrl: String,
    val etag: String?,
    val uid: String,
    val summary: String,
    val description: String?,
    val location: String?,
    val startEpochMillis: Long,
    val endEpochMillis: Long?,
    val allDay: Boolean,
    val rrule: String?,
    val recurrenceIdEpochMillis: Long?,
    val icsRaw: String,
    /** Denormalized from the owning CalendarEntity.sourceApp at write time, so the UI can badge
     *  "KOTO"/"TAVLA" items without a join — avoids guessing from UID conventions. */
    val sourceApp: String?,
    /** From the VEVENT's first VALARM — see ICalParser.parseOneValarm. Null = no reminder set. */
    val reminderMinutesBeforeStart: Int? = null,
    /** Any reminders beyond the first, comma-separated minutes-before-start (e.g. "1440,30" for
     *  "1 day before" + "30 minutes before"). Null/blank = no extra reminders. Kept as a second
     *  field alongside [reminderMinutesBeforeStart] rather than replacing it with a list column,
     *  so every existing single-reminder read/write path (ReminderScheduler, CalendarRepository's
     *  create/update params, the old ICS write-back) keeps working unchanged — this is purely
     *  additive. See EventEditViewModel.EventForm.reminders for the parsed form. */
    val extraReminderMinutes: String? = null,
    /** Display names/emails from ATTENDEE lines, JSON-encoded (org.json array) — the plain
     *  display-string form; see [attendeeDetailsJson] for the structured one RSVP write-back uses. */
    val attendeesJson: String? = null,
    /** One JSON object per ATTENDEE line — {"email":"...","displayName":"...","partstat":"..."}
     *  (org.json array of objects; displayName/partstat omitted when the line had none) —
     *  mirrored into CalendarContract.Attendees rows by CalendarContractRepository so the system
     *  Calendar app (and Xin) can read and change RSVP status, and read back the same way to push
     *  a changed PARTSTAT out to Nextcloud via ICalWriter.setAttendeePartstat. Unlike
     *  [attendeesJson]'s old doc comment, this one IS written back — see
     *  CalendarContractRepository.pushDirtyEventsFromContract. */
    val attendeeDetailsJson: String? = null,
    /** A Vinci contact this event is linked to, set locally by the user (see ContactsRepository) —
     *  never written back to Nextcloud as an ATTENDEE, this is a Zaman-local annotation only. */
    val linkedContactUid: String? = null,
    val linkedContactName: String? = null,
    /** EXDATE lines, JSON-encoded (org.json array of epoch millis in UTC) — needed so
     *  RRuleExpander can skip a cancelled occurrence of a recurring series. Null/empty for a
     *  non-recurring event or one with no exceptions. */
    val exDatesJson: String? = null,
    /** CATEGORIES, comma-joined as a single ICS text value then JSON-array-encoded for storage
     *  (org.json array of strings) — tags the user applies in EventEditScreen, round-tripped to
     *  the VEVENT's own CATEGORIES line (see ICalWriter.buildVEvent/ICalParser's CATEGORIES read). */
    val categoriesJson: String? = null,
)

/** All of this event's reminders (minutes-before-start), primary first, de-duplicated — the one
 *  list ReminderScheduler/ICalWriter should actually iterate instead of touching
 *  reminderMinutesBeforeStart/extraReminderMinutes separately. */
fun EventEntity.allReminderMinutes(): List<Int> {
    val extras = extraReminderMinutes
        ?.split(",")
        ?.mapNotNull { it.trim().toIntOrNull() }
        .orEmpty()
    return (listOfNotNull(reminderMinutesBeforeStart) + extras).distinct()
}

/** Encodes everything after the first entry of [minutes] for [EventEntity.extraReminderMinutes];
 *  the first entry itself goes in reminderMinutesBeforeStart, by convention, wherever an
 *  EventEntity is built from a full reminder list (see EventEditViewModel.save). */
fun encodeExtraReminderMinutes(minutes: List<Int>): String? =
    minutes.drop(1).takeIf { it.isNotEmpty() }?.joinToString(",")

/** One decoded entry of [EventEntity.attendeeDetailsJson]. */
data class AttendeeDetail(val email: String, val displayName: String?, val partstat: String?)

/** Parses [EventEntity.attendeeDetailsJson] back into structured rows — see
 *  CalendarContractRepository for the two places this feeds: mirroring CalendarContract.Attendees
 *  rows, and diffing them back against a dirty push to find a changed PARTSTAT. */
fun EventEntity.attendeeDetails(): List<AttendeeDetail> =
    attendeeDetailsJson?.let { json ->
        runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                AttendeeDetail(
                    email = obj.getString("email"),
                    displayName = obj.optString("displayName").takeIf { it.isNotEmpty() },
                    partstat = obj.optString("partstat").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    } ?: emptyList()

/** Parses [EventEntity.exDatesJson] back into epoch-millis, for CalendarRepository.updateEvent's
 *  default "keep whatever cancelled occurrences this series already had" behavior (feature #4). */
fun EventEntity.exDateMillis(): List<Long> =
    exDatesJson?.let { json ->
        runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getLong(it) }
        }.getOrDefault(emptyList())
    } ?: emptyList()

/** Parses [EventEntity.categoriesJson] back into a plain tag list. */
fun EventEntity.categories(): List<String> =
    categoriesJson?.let { json ->
        runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    } ?: emptyList()

/**
 * Dream scheduling — a "protect this time" event (reading, a hobby, anything you want but never
 * prioritize) is marked by a plain CATEGORIES tag, not a new Room column/migration: it's a
 * namespaced sentinel ("Zaman:Protected") so it round-trips through plain CalDAV CATEGORIES like
 * any other tag (survives a re-sync, visible/editable from another CalDAV client too) while being
 * unlikely to collide with anything the user types themselves. EventEditScreen's "Protect this
 * time" switch adds/removes exactly this one tag and hides it from the regular Tags chip list.
 */
const val DREAM_BLOCK_CATEGORY = "Zaman:Protected"

fun EventEntity.isDreamBlock(): Boolean = categories().any { it.equals(DREAM_BLOCK_CATEGORY, ignoreCase = true) }

/** Encodes a tag list for [EventEntity.categoriesJson]; null when empty so the column stays
 *  cheap to check with a plain null-check elsewhere. */
fun encodeCategories(tags: List<String>): String? =
    tags.filter { it.isNotBlank() }.distinct().takeIf { it.isNotEmpty() }
        ?.let { org.json.JSONArray(it).toString() }
