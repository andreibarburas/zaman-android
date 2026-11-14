package com.brbrs.zaman.ui.week

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.ui.theme.ScreenBackground
import com.brbrs.zaman.ui.theme.SourceLogo
import com.brbrs.zaman.ui.theme.ZamanColors
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.effectiveSourceBadge
import com.brbrs.zaman.ui.theme.glassBorder
import com.brbrs.zaman.ui.theme.glassFill
import com.brbrs.zaman.ui.theme.inkMuted
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/** Height of one hour row in the time grid, and the width of the hour-label gutter to its left —
 *  matching a standard hourly week view (reference screenshot) rather than Week's previous
 *  per-day agenda-list layout. */
private val HourHeight = 56.dp
private val HourGutterWidth = 42.dp

/** Full-week time grid, Monday-first: a day-header row with date numbers, an all-day row for
 *  whole-day items (birthdays, etc.), and a scrollable 24-hour grid below with each timed event
 *  positioned by its own start/end time and a simple side-by-side layout when two events overlap
 *  — the same underlying data as Month/Today, just laid out as an hourly grid instead of a list.
 *  Swipe left/right (or the chevrons) moves a week at a time, same transition as Month. */
@Composable
fun WeekScreen(
    onOpenEvent: (String, Long) -> Unit = { _, _ -> },
    onOpenCalendarMenu: () -> Unit = {},
    viewModel: WeekViewModel = hiltViewModel(),
) {
    val weekStart by viewModel.weekStart.collectAsState()
    val entries by viewModel.entriesInWeek.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()

    val entriesByDay = entries.groupBy { Instant.ofEpochMilli(it.agenda.occurrenceStartMillis).atZone(zone).toLocalDate() }

    var lastWeekStart by remember { mutableStateOf(weekStart) }
    val movingForward = weekStart.isAfter(lastWeekStart)
    lastWeekStart = weekStart

    ScreenBackground {
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val weekEnd = weekStart.plusDays(6)
                // "November 09-15, 2026 | W46" — full month name, zero-padded day range, year,
                // and the ISO week-of-year, rather than the earlier abbreviated "Nov 9–15".
                val weekNumber = weekStart.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                val datePart = if (weekStart.month == weekEnd.month) {
                    "${weekStart.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} " +
                        "%02d-%02d, %d".format(weekStart.dayOfMonth, weekEnd.dayOfMonth, weekEnd.year)
                } else {
                    "${weekStart.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} %02d".format(weekStart.dayOfMonth) +
                        " - ${weekEnd.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} " +
                        "%02d, %d".format(weekEnd.dayOfMonth, weekEnd.year)
                }
                val label = "$datePart | W$weekNumber"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenCalendarMenu) {
                        Icon(Icons.Filled.Menu, contentDescription = "Show calendars")
                    }
                    Text(text = label, style = ZamanType.headlineSmall)
                }
                Row {
                    IconButton(onClick = { viewModel.previousWeek() }) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous week")
                    }
                    IconButton(onClick = { viewModel.nextWeek() }) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next week")
                    }
                }
            }

            var dragTotal = 0f
            AnimatedContent(
                targetState = weekStart,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onHorizontalDrag = { change, dragAmount -> dragTotal += dragAmount; change.consume() },
                            onDragEnd = {
                                if (abs(dragTotal) > 80f) {
                                    if (dragTotal < 0) viewModel.nextWeek() else viewModel.previousWeek()
                                }
                            },
                        )
                    },
                transitionSpec = {
                    if (movingForward) {
                        (slideInHorizontally(tween(300)) { it } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(300)) { -it } + fadeOut(tween(300)))
                    } else {
                        (slideInHorizontally(tween(300)) { -it } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(300)) { it } + fadeOut(tween(300)))
                    }
                },
                label = "week",
            ) { shownWeekStart ->
                val days = (0..6).map { shownWeekStart.plusDays(it.toLong()) }

                Column(Modifier.fillMaxSize()) {
                    // -- Day-of-week + date header -------------------------------------------
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Spacer(Modifier.width(HourGutterWidth))
                        days.forEach { date ->
                            val isToday = date == today
                            Column(
                                modifier = Modifier.weight(1f),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                                    style = ZamanType.labelSmall,
                                    color = inkMuted(),
                                )
                                Box(
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .size(22.dp)
                                        .background(if (isToday) ZamanColors.AccentDark else Color.Transparent, RoundedCornerShape(50)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        date.dayOfMonth.toString(),
                                        style = ZamanType.labelMedium,
                                        color = if (isToday) ZamanColors.OnAccent else inkMuted(),
                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    )
                                }
                            }
                        }
                    }

                    // -- All-day row ----------------------------------------------------------
                    val allDayByDay = days.associateWith { d -> (entriesByDay[d] ?: emptyList()).filter { it.event.allDay } }
                    val hasAllDay = allDayByDay.values.any { it.isNotEmpty() }
                    if (hasAllDay) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                            Spacer(Modifier.width(HourGutterWidth))
                            days.forEach { date ->
                                Column(
                                    modifier = Modifier.weight(1f).padding(horizontal = 1.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    (allDayByDay[date] ?: emptyList()).forEach { entry ->
                                        WeekAllDayChip(entry, onClick = { onOpenEvent(entry.event.href, entry.agenda.occurrenceStartMillis) })
                                    }
                                }
                            }
                        }
                    }

                    // -- Hourly time grid, vertically scrollable ------------------------------
                    val scrollState = rememberScrollState()
                    LaunchedEffect(shownWeekStart) {
                        // Land roughly on the start of the working day rather than midnight,
                        // same spirit as most calendar apps' week view default scroll position.
                        scrollState.scrollTo((HourHeight.value * 7f).toInt())
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                    ) {
                        Row(Modifier.fillMaxWidth().height(HourHeight * 24)) {
                            Column(Modifier.width(HourGutterWidth)) {
                                for (h in 0..23) {
                                    Box(Modifier.height(HourHeight).fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                                        Text(
                                            text = "%02d:00".format(h),
                                            style = ZamanType.labelSmall,
                                            color = inkMuted(),
                                            modifier = Modifier.padding(end = 4.dp, top = 2.dp),
                                        )
                                    }
                                }
                            }
                            days.forEach { date ->
                                val timed = (entriesByDay[date] ?: emptyList()).filterNot { it.event.allDay }
                                WeekDayTimeColumn(
                                    date = date,
                                    isToday = date == today,
                                    entries = timed,
                                    onOpenEvent = { entry -> onOpenEvent(entry.event.href, entry.agenda.occurrenceStartMillis) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    } // end ScreenBackground
}

@Composable
private fun WeekAllDayChip(entry: WeekDayEntry, onClick: () -> Unit) {
    val baseColor = runCatching { Color(android.graphics.Color.parseColor(entry.colorHex)) }
        .getOrDefault(ZamanColors.AccentDark)
    val textColor = if (baseColor.luminance() > 0.5f) Color.Black else Color.White
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = baseColor.copy(alpha = 0.85f),
    ) {
        Text(
            entry.event.summary,
            style = ZamanType.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

/** One day's column in the hourly grid: hour gridlines drawn once behind everything, each timed
 *  event positioned top/height by its own start/end minute-of-day (clamped to this day, for the
 *  rare event that spans midnight), overlapping events given their own side-by-side column via a
 *  simple greedy assignment, and — only on today's column — a red "now" line. */
@Composable
private fun WeekDayTimeColumn(
    date: LocalDate,
    isToday: Boolean,
    entries: List<WeekDayEntry>,
    onOpenEvent: (WeekDayEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val dayStartMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEndMillis = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val dayMinutes = 24 * 60

    data class Placed(val entry: WeekDayEntry, val startMin: Int, val endMin: Int, val col: Int, val totalCols: Int)

    val raw = entries.map { entry ->
        val rawStart = entry.agenda.occurrenceStartMillis.coerceIn(dayStartMillis, dayEndMillis)
        val rawEnd = (entry.agenda.occurrenceEndMillis ?: (entry.agenda.occurrenceStartMillis + 30 * 60 * 1000L))
            .coerceIn(dayStartMillis, dayEndMillis)
        val startMin = ((rawStart - dayStartMillis) / 60000L).toInt().coerceIn(0, dayMinutes)
        val endMinRaw = ((rawEnd - dayStartMillis) / 60000L).toInt().coerceIn(0, dayMinutes)
        // Every block gets at least 15 minutes of height, otherwise a very short appointment
        // would render as an unreadable sliver.
        val endMin = (if (endMinRaw > startMin) endMinRaw else startMin + 30).coerceAtMost(dayMinutes).coerceAtLeast(startMin + 15)
        Triple(entry, startMin, endMin)
    }.sortedBy { it.second }

    // Greedy overlap-column assignment: each event takes the first column whose previous
    // occupant has already ended by this event's start; otherwise it opens a new column.
    val columnEnds = mutableListOf<Int>()
    val columnOf = mutableListOf<Int>()
    raw.forEach { (_, startMin, endMin) ->
        val existing = columnEnds.indexOfFirst { it <= startMin }
        if (existing >= 0) {
            columnEnds[existing] = endMin
            columnOf.add(existing)
        } else {
            columnEnds.add(endMin)
            columnOf.add(columnEnds.size - 1)
        }
    }
    val totalCols = columnEnds.size.coerceAtLeast(1)
    val placed = raw.mapIndexed { i, (entry, startMin, endMin) -> Placed(entry, startMin, endMin, columnOf[i], totalCols) }

    val gridLineColor = glassBorder()
    val nowMinute = if (isToday) {
        val t = LocalTime.now()
        t.hour * 60 + t.minute
    } else null

    BoxWithConstraints(
        modifier = modifier.background(if (isToday) ZamanColors.AccentDark.copy(alpha = 0.05f) else Color.Transparent),
    ) {
        val totalWidth = maxWidth
        val totalHeight = maxHeight

        Canvas(Modifier.fillMaxSize()) {
            val hourPx = size.height / 24f
            for (h in 0..24) {
                drawLine(
                    color = gridLineColor,
                    start = Offset(0f, h * hourPx),
                    end = Offset(size.width, h * hourPx),
                    strokeWidth = 1f,
                )
            }
        }

        placed.forEach { p ->
            val baseColor = runCatching { Color(android.graphics.Color.parseColor(p.entry.colorHex)) }
                .getOrDefault(ZamanColors.AccentDark)
            val textColor = if (baseColor.luminance() > 0.5f) Color.Black else Color.White
            val widthFraction = 1f / p.totalCols
            val topOffset = totalHeight * (p.startMin / dayMinutes.toFloat())
            val blockHeight = totalHeight * ((p.endMin - p.startMin) / dayMinutes.toFloat())
            val leftOffset = totalWidth * widthFraction * p.col

            Surface(
                modifier = Modifier
                    .offset(x = leftOffset, y = topOffset)
                    .width(totalWidth * widthFraction)
                    .height(blockHeight)
                    .padding(horizontal = 1.dp, vertical = 0.5.dp)
                    .clickable { onOpenEvent(p.entry) },
                shape = RoundedCornerShape(4.dp),
                color = baseColor.copy(alpha = 0.88f),
            ) {
                Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        p.entry.event.effectiveSourceBadge()?.let {
                            SourceLogo(it, size = 10.dp)
                            Spacer(Modifier.width(3.dp))
                        }
                        Text(
                            p.entry.event.summary,
                            style = ZamanType.labelSmall,
                            color = textColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        nowMinute?.let { minute ->
            val y = totalHeight * (minute / dayMinutes.toFloat())
            Box(
                modifier = Modifier
                    .offset(y = y)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(ZamanColors.AccentSecondaryDark),
            )
        }
    }
}
