package com.brbrs.zaman.data.ical

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One invitee to write as an ATTENDEE line — see [ICalWriter.buildVEvent]'s `attendees` param.
 *  [partstat] defaults to NEEDS-ACTION for a brand-new invitee; updateEvent's default param
 *  passes the existing event's already-known PARTSTAT through so an ordinary edit (time, title,
 *  ...) never resets everyone's RSVP status back to "needs action". */
data class EventAttendeeInput(val email: String, val displayName: String? = null, val partstat: String? = null)

/** Builds a minimal, valid single-VEVENT .ics body — the write side of ICalParser. */
object ICalWriter {

    private val DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE
    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    fun buildVEvent(
        uid: String,
        summary: String,
        start: ZonedDateTime,
        end: ZonedDateTime?,
        allDay: Boolean,
        location: String?,
        description: String?,
        sequence: Int = 0,
        reminderMinutesBeforeStart: Int? = null,
        /** Any reminders beyond the first — see EventEntity.extraReminderMinutes. Each becomes
         *  its own VALARM block; RFC 5545 allows more than one VALARM per VEVENT. */
        extraReminderMinutes: List<Int> = emptyList(),
        /** Fixed as part of feature #4: this was previously accepted by callers (EventEntity.rrule,
         *  RepeatOption) but never actually written here — every save of ANY event, recurring or
         *  not, silently wrote a plain non-recurring VEVENT to Nextcloud, dropping the series.
         *  Harmless for a brand-new event (no RRULE existed to lose), but editing an existing
         *  recurring one would have quietly turned it into a one-off on save. */
        rrule: String? = null,
        /** Cancelled occurrences of a recurring series (feature #4's "this event" scope writes
         *  one here instead of touching RRULE) — one EXDATE line per date, same VALUE=DATE vs
         *  DATE-TIME convention as DTSTART. Ignored (and should always be empty) when rrule is
         *  null — EXDATE without an RRULE is meaningless. */
        exDates: List<ZonedDateTime> = emptyList(),
        /** CATEGORIES tags — one ICS CATEGORIES line, comma-joined (RFC 5545 §3.8.1.2). */
        categories: List<String> = emptyList(),
        /** Invitees added from Zaman itself (feature: in-app invitee management). Each becomes an
         *  ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE[;CN=displayName]:mailto:
         *  line. [organizerEmail] (the signed-in Nextcloud user's own address, when known) becomes
         *  the ORGANIZER line -- omitted when null, which is still valid ICS, but a server/other
         *  client may not show an organizer for the event in that case. */
        attendees: List<EventAttendeeInput> = emptyList(),
        organizerEmail: String? = null,
    ): String {
        val now = ZonedDateTime.now(ZoneId.of("UTC"))
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//BarburasLab//Zaman 1.0.0//EN",
            "BEGIN:VEVENT",
            "UID:$uid",
            "DTSTAMP:${now.format(DATETIME_FMT)}",
            "SEQUENCE:$sequence",
            "SUMMARY:${escape(summary)}",
        )
        if (allDay) {
            lines += "DTSTART;VALUE=DATE:${start.toLocalDate().format(DATE_FMT)}"
            val endDate = (end ?: start.plusDays(1)).toLocalDate()
            lines += "DTEND;VALUE=DATE:${endDate.format(DATE_FMT)}"
        } else {
            lines += "DTSTART:${start.withZoneSameInstant(ZoneId.of("UTC")).format(DATETIME_FMT)}"
            end?.let { lines += "DTEND:${it.withZoneSameInstant(ZoneId.of("UTC")).format(DATETIME_FMT)}" }
        }
        rrule?.takeIf { it.isNotBlank() }?.let { lines += "RRULE:$it" }
        if (rrule != null) {
            exDates.forEach { ex ->
                lines += if (allDay) "EXDATE;VALUE=DATE:${ex.toLocalDate().format(DATE_FMT)}"
                else "EXDATE:${ex.withZoneSameInstant(ZoneId.of("UTC")).format(DATETIME_FMT)}"
            }
        }
        location?.takeIf { it.isNotBlank() }?.let { lines += "LOCATION:${escape(it)}" }
        description?.takeIf { it.isNotBlank() }?.let { lines += "DESCRIPTION:${escape(it)}" }
        categories.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { lines += "CATEGORIES:${it.joinToString(",") { c -> escape(c) }}" }
        if (attendees.isNotEmpty()) {
            organizerEmail?.takeIf { it.isNotBlank() }?.let { lines += "ORGANIZER:mailto:$it" }
            attendees.forEach { a ->
                val cn = a.displayName?.takeIf { it.isNotBlank() }?.let { ";CN=${escape(it)}" } ?: ""
                val partstat = a.partstat?.takeIf { it.isNotBlank() } ?: "NEEDS-ACTION"
                lines += "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=$partstat;RSVP=TRUE$cn:mailto:${a.email}"
            }
        }
        val allReminders = (listOfNotNull(reminderMinutesBeforeStart) + extraReminderMinutes).distinct()
        allReminders.forEach { minutes ->
            if (minutes <= 0) return@forEach
            lines += "BEGIN:VALARM"
            lines += "ACTION:DISPLAY"
            lines += "DESCRIPTION:${escape(summary)}"
            lines += "TRIGGER:-PT${minutes}M"
            lines += "END:VALARM"
        }
        lines += "END:VEVENT"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n")
    }

    /**
     * Mutates a VTODO's raw ICS text to mark it completed — Koto task checkoff from Zaman's
     * agenda (feature #5: see CalendarRepository.completeTask). Edits STATUS/COMPLETED/
     * PERCENT-COMPLETE/DTSTAMP/SEQUENCE in place on the existing text rather than rebuilding the
     * VTODO from scratch, so every other property Koto wrote (CATEGORIES, PRIORITY, RRULE, a
     * parent-list UID, ...) round-trips back through Koto untouched.
     */
    fun markTodoCompleted(icsRaw: String): String {
        val now = ZonedDateTime.now(ZoneId.of("UTC")).format(DATETIME_FMT)
        val lines = icsRaw.replace("\r\n", "\n").split("\n").toMutableList()
        val todoStart = lines.indexOfFirst { it.startsWith("BEGIN:VTODO") }
        val todoEnd = lines.indexOfFirst { it.startsWith("END:VTODO") }
        if (todoStart < 0 || todoEnd < 0 || todoEnd <= todoStart) return icsRaw // not a VTODO — leave untouched

        val body = lines.subList(todoStart + 1, todoEnd)
        body.removeAll {
            it.startsWith("STATUS:") || it.startsWith("COMPLETED:") ||
                it.startsWith("PERCENT-COMPLETE:") || it.startsWith("DTSTAMP:")
        }
        val sequenceIndex = body.indexOfFirst { it.startsWith("SEQUENCE:") }
        if (sequenceIndex >= 0) {
            val current = body[sequenceIndex].removePrefix("SEQUENCE:").trim().toIntOrNull() ?: 0
            body[sequenceIndex] = "SEQUENCE:${current + 1}"
        } else {
            body.add("SEQUENCE:1")
        }
        body.add("DTSTAMP:$now")
        body.add("STATUS:COMPLETED")
        body.add("PERCENT-COMPLETE:100")
        body.add("COMPLETED:$now")

        return lines.joinToString("\r\n")
    }

    /**
     * RSVP write-back: patches one ATTENDEE line's PARTSTAT in place, same "edit the existing
     * text rather than rebuild from scratch" approach as [markTodoCompleted] — a full
     * buildVEvent-style rebuild would drop every other ATTENDEE line (buildVEvent never wrote
     * attendees to begin with, per ICalModels.ICalEvent's old "read-only, Zaman doesn't write
     * invites back" doc comment, now superseded by this function) plus any other property a
     * server or another client added that Zaman doesn't otherwise model.
     *
     * [email] is matched case-insensitively against each ATTENDEE line's mailto: address. If no
     * matching line exists (the organizer invited an address that never appeared in the ICS
     * Zaman originally pulled — shouldn't normally happen, since the row driving this call always
     * came from an attendee CalendarContract mirrored from this same ICS, but defensive regardless),
     * the ICS is returned unchanged rather than guessing at a new ATTENDEE's CN/ROLE.
     */
    fun setAttendeePartstat(icsRaw: String, email: String, partstat: String): String {
        val targetEmail = email.lowercase()
        val now = ZonedDateTime.now(ZoneId.of("UTC")).format(DATETIME_FMT)
        val lines = icsRaw.replace("\r\n", "\n").split("\n").toMutableList()
        val eventStart = lines.indexOfFirst { it.startsWith("BEGIN:VEVENT") }
        val eventEnd = lines.indexOfFirst { it.startsWith("END:VEVENT") }
        if (eventStart < 0 || eventEnd < 0 || eventEnd <= eventStart) return icsRaw // not a VEVENT

        var matchedIndex = -1
        for (i in eventStart + 1 until eventEnd) {
            val line = lines[i]
            if (!line.startsWith("ATTENDEE")) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val value = line.substring(colon + 1).removePrefix("mailto:").removePrefix("MAILTO:")
            if (value.lowercase() == targetEmail) { matchedIndex = i; break }
        }
        if (matchedIndex < 0) return icsRaw // see doc comment above

        val original = lines[matchedIndex]
        val colon = original.indexOf(':')
        val head = original.substring(0, colon) // "ATTENDEE" + any ;PARAM=value segments
        val params = head.split(';').drop(1).filterNot { it.startsWith("PARTSTAT=") }
        val newHead = (listOf("ATTENDEE") + params + "PARTSTAT=$partstat" + "RSVP=TRUE").joinToString(";")
        lines[matchedIndex] = "$newHead:${original.substring(colon + 1)}"

        val body = lines.subList(eventStart + 1, eventEnd)
        body.removeAll { it.startsWith("DTSTAMP:") }
        val sequenceIndex = body.indexOfFirst { it.startsWith("SEQUENCE:") }
        if (sequenceIndex >= 0) {
            val current = body[sequenceIndex].removePrefix("SEQUENCE:").trim().toIntOrNull() ?: 0
            body[sequenceIndex] = "SEQUENCE:${current + 1}"
        } else {
            body.add("SEQUENCE:1")
        }
        body.add("DTSTAMP:$now")

        return lines.joinToString("\r\n")
    }

    /**
     * "This and following events" (feature #4): closes an existing series off right before
     * [beforeInstant] by adding/replacing its UNTIL, so the master keeps every occurrence up to
     * (not including) that point and a brand-new series (same RRULE, no UNTIL) takes over from
     * there — see CalendarRepository.updateRecurringEvent. Drops any existing COUNT too, since
     * UNTIL and COUNT are mutually exclusive per RFC 5545.
     */
    fun truncateRRuleBefore(rrule: String, beforeInstant: java.time.Instant, allDay: Boolean): String {
        val until = beforeInstant.minusSeconds(1)
        val untilStr = if (allDay) {
            until.atZone(ZoneId.of("UTC")).toLocalDate().format(DATE_FMT)
        } else {
            until.atZone(ZoneId.of("UTC")).format(DATETIME_FMT)
        }
        val kept = rrule.split(";").filterNot { it.startsWith("UNTIL=") || it.startsWith("COUNT=") }
        return (kept + "UNTIL=$untilStr").joinToString(";")
    }

    private fun escape(v: String) =
        v.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
}
