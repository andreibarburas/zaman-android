package com.brbrs.zaman.data.ical

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal but real RFC 5545 reader: line unfolding, VEVENT/VTODO property
 * parsing with params, VALUE=DATE (all-day) vs DATE-TIME, and TZID resolution
 * via java.time's IANA database (covers the vast majority of Nextcloud's own
 * exports and anything a modern client writes; a custom/non-IANA VTIMEZONE
 * block is not independently parsed — see parseVTimeZoneOffsetFallback).
 *
 * Deliberately does NOT attempt full RFC 5545 — exceptions beyond EXDATE,
 * nested VALARM, and multi-value RDATE are out of scope for v1.
 */
object ICalParser {

    private val DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE // yyyyMMdd
    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun parseEvents(icsText: String): List<ICalEvent> {
        val lines = unfold(icsText)
        val events = mutableListOf<ICalEvent>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("BEGIN:VEVENT")) {
                val (event, next) = parseOneVEvent(lines, i + 1)
                events += event
                i = next
            } else i++
        }
        return events
    }

    /**
     * Same walk as [parseEvents], but each event is paired with its OWN standalone raw ICS text
     * (just that one VEVENT, wrapped in a minimal VCALENDAR) instead of the whole [icsText] —
     * see [wrapAsStandaloneIcs]'s doc comment for why this matters whenever [icsText] can hold
     * more than one VEVENT (an ICS subscription feed synced as a single resource).
     */
    fun parseEventsWithRaw(icsText: String): List<Pair<ICalEvent, String>> {
        val lines = unfold(icsText)
        val events = mutableListOf<Pair<ICalEvent, String>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("BEGIN:VEVENT")) {
                val (event, next) = parseOneVEvent(lines, i + 1)
                events += event to wrapAsStandaloneIcs(lines.subList(i, next))
                i = next
            } else i++
        }
        return events
    }

    fun parseTodos(icsText: String): List<ICalTodo> {
        val lines = unfold(icsText)
        val todos = mutableListOf<ICalTodo>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("BEGIN:VTODO")) {
                val props = mutableMapOf<String, Prop>()
                var j = i + 1
                while (j < lines.size && !lines[j].startsWith("END:VTODO")) {
                    parseProp(lines[j])?.let { props[it.name] = it }
                    j++
                }
                todos += ICalTodo(
                    uid = props["UID"]?.value ?: "unknown-${todos.size}",
                    summary = props["SUMMARY"]?.value ?: "(untitled)",
                    due = props["DUE"]?.let { parseDateTimeProp(it) },
                    completed = props["STATUS"]?.value == "COMPLETED",
                )
                i = j + 1
            } else i++
        }
        return todos
    }

    /** Same idea as [parseEventsWithRaw], for VTODOs (Koto's task list). */
    fun parseTodosWithRaw(icsText: String): List<Pair<ICalTodo, String>> {
        val lines = unfold(icsText)
        val todos = mutableListOf<Pair<ICalTodo, String>>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("BEGIN:VTODO")) {
                val props = mutableMapOf<String, Prop>()
                var j = i + 1
                while (j < lines.size && !lines[j].startsWith("END:VTODO")) {
                    parseProp(lines[j])?.let { props[it.name] = it }
                    j++
                }
                val todo = ICalTodo(
                    uid = props["UID"]?.value ?: "unknown-${todos.size}",
                    summary = props["SUMMARY"]?.value ?: "(untitled)",
                    due = props["DUE"]?.let { parseDateTimeProp(it) },
                    completed = props["STATUS"]?.value == "COMPLETED",
                )
                todos += todo to wrapAsStandaloneIcs(lines.subList(i, j + 1))
                i = j + 1
            } else i++
        }
        return todos
    }

    /**
     * Wraps a single already-unfolded VEVENT/VTODO block (BEGIN..END inclusive) in a minimal
     * standalone VCALENDAR, so it can be stored as EventEntity.icsRaw and later edited in place
     * by ICalWriter the same way a normal single-event CalDAV resource's raw ICS text already
     * is — those functions (markTodoCompleted, setAttendeePartstat) scan for the FIRST
     * BEGIN:VEVENT/END:VEVENT (or VTODO) in the string, which only behaves correctly when the
     * string holds exactly one component.
     *
     * Without this, a multi-VEVENT resource (an ICS subscription feed, synced as one resource
     * per feed — see CalendarRepository.buildEventEntities) stored the ENTIRE feed's raw text as
     * EVERY one of its events' icsRaw: for a feed with hundreds of events, that's the whole feed
     * duplicated hundreds of times over in the database (one subscription in this suite has
     * logged a single feed response over 4MB — see AppModule's OkHttpClient logging comment), and
     * loading any date range that touches enough of those rows blew the CursorWindow heap
     * (OutOfMemoryError in EventDao.observeCandidatesForRange) — real crash this was written to
     * fix. It also meant setAttendeePartstat/markTodoCompleted always edited the FIRST event in
     * the whole feed, never the one actually being acted on.
     */
    private fun wrapAsStandaloneIcs(block: List<String>): String =
        (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//BarburasLab//Zaman//EN") + block + listOf("END:VCALENDAR"))
            .joinToString("\r\n")

    // --- line unfolding: a CRLF/LF followed by a single space or tab continues the previous line ---
    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").split("\n")
        val out = mutableListOf<String>()
        for (line in raw) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + line.substring(1)
            } else if (line.isNotBlank()) {
                out += line
            }
        }
        return out
    }

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    private fun parseProp(line: String): Prop? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = unescapeText(line.substring(colon + 1))
        val parts = head.split(';')
        val name = parts[0].uppercase()
        val params = parts.drop(1).mapNotNull {
            val eq = it.indexOf('=')
            if (eq < 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1)
        }.toMap()
        return Prop(name, params, value)
    }

    private fun unescapeText(v: String) =
        v.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    private fun parseOneVEvent(lines: List<String>, start: Int): Pair<ICalEvent, Int> {
        val props = mutableMapOf<String, Prop>()
        val exDates = mutableListOf<ICalDateTime>()
        val attendees = mutableListOf<String>()
        val attendeeDetails = mutableListOf<ICalAttendee>()
        val allReminderMinutes = mutableListOf<Int>()
        var j = start
        while (j < lines.size && !lines[j].startsWith("END:VEVENT")) {
            if (lines[j].startsWith("BEGIN:VALARM")) {
                val (minutes, next) = parseOneValarm(lines, j + 1)
                minutes?.let { allReminderMinutes += it }
                j = next
                continue
            }
            parseProp(lines[j])?.let { p ->
                when (p.name) {
                    "EXDATE" -> exDates += parseDateTimeProp(p)
                    "ATTENDEE" -> {
                        attendees += (p.params["CN"] ?: p.value.removePrefix("mailto:"))
                        attendeeDetails += ICalAttendee(
                            email = p.value.removePrefix("mailto:").removePrefix("MAILTO:").lowercase(),
                            displayName = p.params["CN"],
                            partstat = p.params["PARTSTAT"],
                        )
                    }
                    else -> props[p.name] = p
                }
            }
            j++
        }
        val dtStart = props["DTSTART"]?.let { parseDateTimeProp(it) }
            ?: ICalDateTime(ZonedDateTime.now(), isDateOnly = true)
        val dtEnd = props["DTEND"]?.let { parseDateTimeProp(it) }
        val event = ICalEvent(
            uid = props["UID"]?.value ?: "unknown",
            summary = props["SUMMARY"]?.value ?: "(untitled)",
            description = props["DESCRIPTION"]?.value,
            location = props["LOCATION"]?.value,
            start = dtStart,
            end = dtEnd,
            allDay = dtStart.isDateOnly,
            rrule = props["RRULE"]?.value,
            exDates = exDates,
            recurrenceId = props["RECURRENCE-ID"]?.let { parseDateTimeProp(it) },
            sequence = props["SEQUENCE"]?.value?.toIntOrNull() ?: 0,
            status = props["STATUS"]?.value,
            reminderMinutesBeforeStart = allReminderMinutes.firstOrNull(),
            extraReminderMinutes = allReminderMinutes.drop(1),
            attendees = attendees,
            attendeeDetails = attendeeDetails,
            categories = props["CATEGORIES"]?.value?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
        )
        return event to (j + 1)
    }

    /** @return (reminder minutes before start, or null if TRIGGER isn't a relative "before start" duration) to (index just past END:VALARM). */
    private fun parseOneValarm(lines: List<String>, start: Int): Pair<Int?, Int> {
        var trigger: String? = null
        var j = start
        while (j < lines.size && !lines[j].startsWith("END:VALARM")) {
            parseProp(lines[j])?.let { if (it.name == "TRIGGER" && it.params["VALUE"] != "DATE-TIME") trigger = it.value }
            j++
        }
        return parseNegativeDurationMinutes(trigger) to (j + 1)
    }

    /** "-PT15M" -> 15, "-PT1H" -> 60, "-P1D" -> 1440. Anything else (a positive/absolute trigger) is null. */
    private fun parseNegativeDurationMinutes(trigger: String?): Int? {
        if (trigger == null || !trigger.startsWith("-P")) return null
        val match = Regex("""-P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?""").matchEntire(trigger) ?: return null
        val (days, hours, minutes) = match.destructured
        val total = (days.toIntOrNull() ?: 0) * 24 * 60 + (hours.toIntOrNull() ?: 0) * 60 + (minutes.toIntOrNull() ?: 0)
        return total.takeIf { it > 0 }
    }

    private fun parseDateTimeProp(p: Prop): ICalDateTime {
        val isDateOnly = p.params["VALUE"] == "DATE" || p.value.length == 8
        if (isDateOnly) {
            val date = LocalDate.parse(p.value, DATE_FMT)
            return ICalDateTime(date.atStartOfDay(ZoneId.systemDefault()), isDateOnly = true)
        }
        val utc = p.value.endsWith("Z")
        val raw = p.value.trimEnd('Z')
        val ldt = LocalDateTime.parse(raw, DATETIME_FMT)
        val zone = when {
            utc -> ZoneId.of("UTC")
            p.params["TZID"] != null -> runCatching { ZoneId.of(p.params["TZID"]) }.getOrDefault(ZoneId.systemDefault())
            else -> ZoneId.systemDefault()
        }
        return ICalDateTime(ldt.atZone(zone), isDateOnly = false)
    }
}
