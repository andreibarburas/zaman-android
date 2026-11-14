package com.brbrs.zaman.data.ical

import java.time.ZonedDateTime

/** One VEVENT, already resolved against its VTIMEZONE (if any). */
data class ICalEvent(
    val uid: String,
    val summary: String,
    val description: String?,
    val location: String?,
    val start: ICalDateTime,
    val end: ICalDateTime?,
    val allDay: Boolean,
    val rrule: String?,
    val exDates: List<ICalDateTime>,
    /** RECURRENCE-ID — present only on an override instance of a recurring event. */
    val recurrenceId: ICalDateTime?,
    val sequence: Int,
    val status: String?,
    /** Minutes before start, from the first VALARM's TRIGGER (only a relative, negative
     *  duration before DTSTART is handled — an absolute TRIGGER;VALUE=DATE-TIME or a
     *  post-start reminder is out of scope for v1). Null if there's no VALARM, or its
     *  TRIGGER couldn't be parsed as one of those. */
    val reminderMinutesBeforeStart: Int?,
    /** Minutes-before for every VALARM after the first, same parsing rules as
     *  [reminderMinutesBeforeStart]. Empty if the VEVENT has 0 or 1 VALARMs. */
    val extraReminderMinutes: List<Int> = emptyList(),
    /** Display name (CN param) or bare mailto: address per ATTENDEE line — kept for existing
     *  display call sites; see [attendeeDetails] for the structured form that RSVP write-back
     *  (CalendarContractRepository.pushDirtyEventsFromContract) actually reads/writes. */
    val attendees: List<String>,
    /** One entry per ATTENDEE line, with its mailto: address, display name and current PARTSTAT
     *  (if the server/sender set one — a plain ATTENDEE with no PARTSTAT param, the common case
     *  for an invite that hasn't been responded to yet, leaves this null, not "NEEDS-ACTION").
     *  Default emptyList() so ICalEvent's few other construction sites (none exist today besides
     *  ICalParser, but future test/preview code shouldn't have to supply this to compile). */
    val attendeeDetails: List<ICalAttendee> = emptyList(),
    /** CATEGORIES tags, split on commas (RFC 5545 §3.8.1.2 is a comma-separated text-value list). */
    val categories: List<String> = emptyList(),
)

/** One ATTENDEE line, structured. [email] is always lowercased so a later case-insensitive
 *  lookup (matching a CalendarContract.Attendees row's ATTENDEE_EMAIL back to the right ICS
 *  line) is a plain equality check rather than every call site remembering to fold case itself. */
data class ICalAttendee(val email: String, val displayName: String?, val partstat: String?)

data class ICalDateTime(val instant: ZonedDateTime, val isDateOnly: Boolean)

/** One VTODO, mapped the same way for the all-day "Tasks with due dates" lane. */
data class ICalTodo(
    val uid: String,
    val summary: String,
    val due: ICalDateTime?,
    val completed: Boolean,
)
