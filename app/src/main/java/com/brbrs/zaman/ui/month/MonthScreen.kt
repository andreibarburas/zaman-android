package com.brbrs.zaman.ui.month

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.ui.theme.ScreenBackground
import com.brbrs.zaman.ui.theme.ZamanColors
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.inkMuted
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

/** Width of the leading ISO-week-number gutter column, matching the grid in the reference
 *  screenshot (a slim ruler to the left of Monday's column, not a day column of its own). */
private val WeekGutterWidth = 22.dp

@Composable
fun MonthScreen(
    onOpenEvent: (String, Long) -> Unit = { _, _ -> },
    onOpenCalendarMenu: () -> Unit = {},
    viewModel: MonthViewModel = hiltViewModel(),
) {
    val month by viewModel.visibleMonth.collectAsState()
    val entries by viewModel.entriesInMonth.collectAsState()
    val selectionMode by viewModel.selectionMode.collectAsState()
    val selectedHrefs by viewModel.selectedHrefs.collectAsState()
    val rescheduleMessage by viewModel.rescheduleMessage.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()

    // Drag-and-drop reschedule state: which agenda item is currently being dragged, where the
    // finger currently is (root coordinates), and each visible day column's own bounds in root
    // coordinates (populated by onGloballyPositioned on each day column) — all three live at this
    // level since the floating drag-overlay and the hit-test on drop both need to sit above/across
    // the whole grid, not scoped to one column or one chip.
    var draggedEntry by remember { mutableStateOf<MonthDayEntry?>(null) }
    var dragPosition by remember { mutableStateOf<Offset?>(null) }
    val cellBounds = remember { mutableMapOf<LocalDate, Rect>() }

    val entriesByDay = entries
        .groupBy { Instant.ofEpochMilli(it.agenda.occurrenceStartMillis).atZone(zone).toLocalDate() }

    var lastMonth by remember { mutableStateOf(month) }
    val movingForward = month.isAfter(lastMonth)
    lastMonth = month

    ScreenBackground {
    Scaffold(
        containerColor = Color.Transparent,
        // Same reasoning as Today's Scaffold: the header below applies its own statusBarsPadding
        // directly, so Scaffold's own inset handling is zeroed out rather than stacking on top of it.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    TextButton(onClick = { viewModel.exitSelectionMode() }) { Text("Cancel") }
                    Text("${selectedHrefs.size} selected", style = ZamanType.titleMedium)
                    IconButton(onClick = { viewModel.deleteSelected() }, enabled = selectedHrefs.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                    }
                } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenCalendarMenu) {
                        Icon(Icons.Filled.Menu, contentDescription = "Show calendars")
                    }
                    Text(
                        text = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
                        style = ZamanType.headlineSmall,
                    )
                }
                Row {
                    IconButton(onClick = { viewModel.previousMonth() }) {
                        Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month")
                    }
                    IconButton(onClick = { viewModel.nextMonth() }) {
                        Icon(Icons.Filled.ChevronRight, contentDescription = "Next month")
                    }
                }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp)) {
                Spacer(Modifier.width(WeekGutterWidth))
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(
                        it, style = ZamanType.labelMedium, color = inkMuted(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // Item 5 + transition: a horizontal swipe anywhere in the grid area below moves to the
            // next/previous month, and AnimatedContent slides it the same way a tap on the chevrons
            // would — a left swipe (negative total drag) means "go forward."
            var dragTotal = 0f
            AnimatedContent(
                targetState = month,
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
                                    if (dragTotal < 0) viewModel.nextMonth() else viewModel.previousMonth()
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
                label = "month",
            ) { shownMonth ->
                // A dense, scrollable grid of whole weeks (Monday-first), spilling into the
                // neighboring months at both ends so every row is a full 7 days — matching a
                // standard month-grid calendar rather than padding with blank cells.
                val gridStart = shownMonth.atDay(1).with(WeekFields.ISO.dayOfWeek(), 1)
                val gridEnd = shownMonth.atEndOfMonth().with(WeekFields.ISO.dayOfWeek(), 7)
                val allDays = generateSequence(gridStart) { it.plusDays(1) }.takeWhile { !it.isAfter(gridEnd) }.toList()
                val weeks = allDays.chunked(7)

                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                    items(weeks, key = { it.first().toString() }) { week ->
                        Row(Modifier.fillMaxWidth()) {
                            val weekNumber = week.first().get(WeekFields.ISO.weekOfWeekBasedYear())
                            Text(
                                weekNumber.toString(),
                                style = ZamanType.labelSmall,
                                color = inkMuted().copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.width(WeekGutterWidth).padding(top = 20.dp),
                            )
                            week.forEach { date ->
                                val dayEntries = (entriesByDay[date] ?: emptyList())
                                    .sortedWith(compareBy({ it.agenda.occurrenceStartMillis }, { it.event.href }))
                                MonthDayColumn(
                                    date = date,
                                    inCurrentMonth = YearMonth.from(date) == shownMonth,
                                    isToday = date == today,
                                    entries = dayEntries,
                                    isDragTarget = draggedEntry != null &&
                                        dragPosition?.let { pos -> cellBounds[date]?.contains(pos) } == true,
                                    selectionMode = selectionMode,
                                    selectedHrefs = selectedHrefs,
                                    onOpenEvent = { entry ->
                                        if (selectionMode) viewModel.toggleSelected(entry.event.href)
                                        else onOpenEvent(entry.event.href, entry.agenda.occurrenceStartMillis)
                                    },
                                    onChipDragStart = { entry -> if (!selectionMode) draggedEntry = entry },
                                    onChipDrag = { pos -> if (!selectionMode) dragPosition = pos },
                                    onChipDragEnd = { entry, wasRealDrag ->
                                        if (!selectionMode) {
                                            if (!wasRealDrag) {
                                                viewModel.enterSelectionMode(entry.event.href)
                                            } else {
                                                val target = dragPosition?.let { pos -> cellBounds.entries.find { it.value.contains(pos) }?.key }
                                                if (target != null) viewModel.rescheduleEvent(entry, target)
                                            }
                                        }
                                        draggedEntry = null
                                        dragPosition = null
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .onGloballyPositioned { coords -> cellBounds[date] = coords.boundsInRoot() },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating copy of the dragged item, following the finger — rendered above everything
        // else in this Box so it isn't clipped by the LazyColumn/grid it's being dragged across.
        draggedEntry?.let { entry ->
            dragPosition?.let { pos ->
                Box(
                    Modifier
                        .size(width = 160.dp, height = 36.dp)
                        .offset {
                            IntOffset(
                                (pos.x - 80.dp.toPx()).toInt(),
                                (pos.y - 18.dp.toPx()).toInt(),
                            )
                        }
                        .background(ZamanColors.AccentDark.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(entry.event.summary, color = ZamanColors.OnAccent, style = ZamanType.bodySmall, maxLines = 1)
                }
            }
        }

        rescheduleMessage?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { viewModel.dismissRescheduleMessage() }) { Text("OK") } },
            ) { Text(msg) }
        }
        }
    }
    } // end ScreenBackground
}

/** One day's column within a week row: the day number, then EVERY one of its events stacked as a
 *  small colored chip (one per event, filled with that event's own calendar color — matching the
 *  reference screenshot's dense month grid). No cap/overflow — a busy day just makes that week
 *  row taller, which the surrounding LazyColumn already handles fine since nothing else in the
 *  grid depends on a fixed row height (feature request: "+N" was hiding real events). A day
 *  outside the currently-viewed month (the grid's leading/trailing days) is dimmed rather than
 *  hidden, so its real events still show. */
@Composable
private fun MonthDayColumn(
    date: LocalDate,
    inCurrentMonth: Boolean,
    isToday: Boolean,
    entries: List<MonthDayEntry>,
    isDragTarget: Boolean,
    selectionMode: Boolean,
    selectedHrefs: Set<String>,
    onOpenEvent: (MonthDayEntry) -> Unit,
    onChipDragStart: (MonthDayEntry) -> Unit,
    onChipDrag: (Offset) -> Unit,
    onChipDragEnd: (MonthDayEntry, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isSunday = date.dayOfWeek == DayOfWeek.SUNDAY
    Column(
        modifier
            .padding(horizontal = 1.dp)
            .background(
                if (isDragTarget) ZamanColors.AccentDark.copy(alpha = 0.18f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 2.dp)
                .size(18.dp)
                .background(if (isToday) ZamanColors.AccentDark else Color.Transparent, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = ZamanType.labelSmall,
                color = when {
                    isToday -> ZamanColors.OnAccent
                    !inCurrentMonth -> inkMuted().copy(alpha = 0.4f)
                    isSunday -> ZamanColors.AccentSecondaryDark
                    else -> inkMuted()
                },
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
        }

        entries.forEach { entry ->
            MonthEventChip(
                entry = entry,
                dimmed = !inCurrentMonth,
                isSelected = entry.event.href in selectedHrefs,
                onTap = { onOpenEvent(entry) },
                onDragStart = { onChipDragStart(entry) },
                onDrag = onChipDrag,
                onDragEnd = { wasRealDrag -> onChipDragEnd(entry, wasRealDrag) },
            )
        }
    }
}

/** A single filled, calendar-colored chip for one event — the month grid's event is the chip
 *  itself (not a dot), same gesture handling Month has always had: tap opens it, a long-press
 *  with no real movement after enters multi-select, and a long-press followed by real dragging
 *  reschedules it onto whichever day column the finger ends up over. */
@Composable
private fun MonthEventChip(
    entry: MonthDayEntry,
    dimmed: Boolean,
    isSelected: Boolean,
    onTap: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (wasRealDrag: Boolean) -> Unit,
) {
    val event = entry.event
    val baseColor = runCatching { Color(android.graphics.Color.parseColor(entry.colorHex)) }
        .getOrDefault(sourceStripeFor(event))
    val bg = if (dimmed) baseColor.copy(alpha = 0.25f) else baseColor.copy(alpha = 0.85f)
    val textColor = if (bg.luminance() > 0.5f) Color.Black else Color.White

    var totalDrag by remember { mutableStateOf(0f) }
    var itemRootOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragAccum by remember { mutableStateOf(Offset.Zero) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .onGloballyPositioned { coords -> itemRootOrigin = coords.boundsInRoot().center }
            .pointerInput(event.href) {
                detectTapGestures(onTap = { onTap() })
            }
            .pointerInput(event.href) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { totalDrag = 0f; dragAccum = Offset.Zero; onDragStart() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDrag += abs(dragAmount.x) + abs(dragAmount.y)
                        dragAccum += dragAmount
                        onDrag(itemRootOrigin + dragAccum)
                    },
                    onDragEnd = { onDragEnd(totalDrag >= 24f) },
                )
            }
            .let { if (isSelected) it.border(1.dp, ZamanColors.AccentDark, RoundedCornerShape(4.dp)) else it },
        shape = RoundedCornerShape(4.dp),
        color = bg,
    ) {
        Text(
            text = event.summary,
            style = ZamanType.labelSmall,
            color = if (dimmed) textColor.copy(alpha = 0.7f) else textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp),
        )
    }
}

private fun sourceStripeFor(event: EventEntity): Color =
    when (event.sourceApp) {
        "KOTO" -> ZamanColors.AccentKoto
        "TAVLA" -> ZamanColors.AccentTavla
        else -> ZamanColors.AccentDark
    }
