package com.brbrs.zaman.data.ical

import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Expands RRULE occurrences within [rangeStart, rangeEnd).
 *
 * Covers FREQ=DAILY|WEEKLY|MONTHLY|YEARLY with INTERVAL, COUNT, UNTIL, and
 * BYDAY (weekly + simple monthly "2nd Tuesday" forms). This is the subset
 * that covers the overwhelming majority of real calendars (standups,
 * "every other Friday", monthly billing, yearly birthdays/anniversaries).
 * BYSETPOS, BYWEEKNO, BYYEARDAY and RDATE are not implemented — an event
 * using them falls back to showing only its first (master) occurrence,
 * which is safe (no missing-but-wrong instances) rather than silently wrong.
 */
object RRuleExpander {

    /**
     * [startInstant] is the series' own DTSTART (the master occurrence) — the expansion always
     * walks forward from there regardless of [rangeStart], since an every-Nth-day/week pattern
     * is only correct relative to its own anchor date, not to whatever window happens to be
     * showing right now.
     */
    fun expand(
        rrule: String?,
        startInstant: ZonedDateTime,
        exDates: List<ZonedDateTime>,
        rangeStart: ZonedDateTime,
        rangeEnd: ZonedDateTime,
    ): List<ZonedDateTime> {
        val rule = rrule ?: return if (inRange(startInstant, rangeStart, rangeEnd)) listOf(startInstant) else emptyList()

        val parts = rule.split(';').mapNotNull {
            val eq = it.indexOf('='); if (eq < 0) null else it.substring(0, eq) to it.substring(eq + 1)
        }.toMap()

        val freq = parts["FREQ"] ?: return emptyList()
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val count = parts["COUNT"]?.toIntOrNull()
        val until = parts["UNTIL"]?.let { parseUntil(it) }
        val byDay = parts["BYDAY"]?.split(',')?.map { parseByDay(it) }

        val exSet = exDates.map { it.toLocalDateTime() }.toSet()
        val out = mutableListOf<ZonedDateTime>()
        var cursor = startInstant

        // Fast-forward a series that started long before the requested window (a daily standup
        // from 2 years ago, say) to just before rangeStart, in whole cycle-steps so it lands
        // back on the exact same recurrence alignment — otherwise the loop below would have to
        // walk one cycle at a time from the original DTSTART and could hit the safety guard
        // before ever reaching "today". Skipped when COUNT is set, since occurrence numbering
        // has to start from the true first occurrence for COUNT to mean anything.
        if (count == null) {
            cursor = when {
                freq == "DAILY" -> fastForward(cursor, rangeStart) { c, n -> c.plusDays(n * interval) }
                freq == "WEEKLY" && byDay.isNullOrEmpty() -> fastForward(cursor, rangeStart) { c, n -> c.plusWeeks(n * interval) }
                freq == "MONTHLY" -> fastForward(cursor, rangeStart) { c, n -> c.plusMonths(n * interval) }
                freq == "YEARLY" -> fastForward(cursor, rangeStart) { c, n -> c.plusYears(n * interval) }
                else -> cursor // WEEKLY+BYDAY steps a day at a time below — no safe larger jump
            }
        }

        var occurrenceCount = 0
        var safetyGuard = 0

        while (cursor.isBefore(rangeEnd) && safetyGuard < 10_000) {
            safetyGuard++
            if (until != null && cursor.isAfter(until)) break
            if (count != null && occurrenceCount >= count) break

            val matchesByDay = byDay.isNullOrEmpty() || byDay.any { (n, dow) ->
                cursor.dayOfWeek == dow && (n == 0 || matchesOrdinal(cursor, n))
            }
            if (matchesByDay) {
                occurrenceCount++
                if (!exSet.contains(cursor.toLocalDateTime()) && inRange(cursor, rangeStart, rangeEnd)) {
                    out += cursor
                }
            }

            cursor = when (freq) {
                "DAILY" -> cursor.plusDays(interval.toLong())
                "WEEKLY" -> if (!byDay.isNullOrEmpty()) cursor.plusDays(1) else cursor.plusWeeks(interval.toLong())
                "MONTHLY" -> cursor.plusMonths(interval.toLong())
                "YEARLY" -> cursor.plusYears(interval.toLong())
                else -> return out // unsupported FREQ — stop rather than loop forever
            }
        }
        return out
    }

    private fun inRange(t: ZonedDateTime, start: ZonedDateTime, end: ZonedDateTime) =
        !t.isBefore(start) && t.isBefore(end)

    /** Largest n (binary search) such that stepping [cursor] forward n whole cycles with [apply]
     *  is still before [rangeStart] — generic over DAILY/WEEKLY/MONTHLY/YEARLY since the caller's
     *  [apply] already encodes which unit and INTERVAL to step by. */
    private fun fastForward(cursor: ZonedDateTime, rangeStart: ZonedDateTime, apply: (ZonedDateTime, Long) -> ZonedDateTime): ZonedDateTime {
        if (!cursor.isBefore(rangeStart)) return cursor
        var lo = 0L
        var hi = 1L
        while (hi < 1_000_000L && apply(cursor, hi).isBefore(rangeStart)) {
            lo = hi
            hi *= 2
        }
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (apply(cursor, mid).isBefore(rangeStart)) lo = mid else hi = mid - 1
        }
        return apply(cursor, lo)
    }

    private fun matchesOrdinal(d: ZonedDateTime, n: Int): Boolean {
        val weekOfMonth = (d.dayOfMonth - 1) / 7 + 1
        return if (n > 0) weekOfMonth == n else {
            val daysLeftInMonth = d.toLocalDate().lengthOfMonth() - d.dayOfMonth
            daysLeftInMonth / 7 == (-n - 1)
        }
    }

    private fun parseByDay(token: String): Pair<Int, DayOfWeek> {
        val m = Regex("([+-]?\\d*)([A-Z]{2})").find(token) ?: return 0 to DayOfWeek.MONDAY
        val n = m.groupValues[1].toIntOrNull() ?: 0
        val dow = when (m.groupValues[2]) {
            "MO" -> DayOfWeek.MONDAY; "TU" -> DayOfWeek.TUESDAY; "WE" -> DayOfWeek.WEDNESDAY
            "TH" -> DayOfWeek.THURSDAY; "FR" -> DayOfWeek.FRIDAY; "SA" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
        return n to dow
    }

    private fun parseUntil(raw: String): ZonedDateTime {
        val clean = raw.trimEnd('Z')
        return if (clean.length == 8) {
            java.time.LocalDate.parse(clean, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                .atStartOfDay(java.time.ZoneId.of("UTC"))
        } else {
            java.time.LocalDateTime.parse(clean, java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                .atZone(java.time.ZoneId.of("UTC"))
        }
    }
}
