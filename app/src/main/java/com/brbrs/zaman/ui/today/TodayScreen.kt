package com.brbrs.zaman.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.brbrs.zaman.data.prefs.ThemeMode
import com.brbrs.zaman.ui.settings.DisplayViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.repo.AgendaEntry
import com.brbrs.zaman.ui.theme.GlassCard
import com.brbrs.zaman.ui.theme.ScreenBackground
import com.brbrs.zaman.ui.theme.SourceLogo
import com.brbrs.zaman.ui.theme.effectiveSourceBadge
import com.brbrs.zaman.ui.theme.ZamanColors
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.glassBorder
import com.brbrs.zaman.ui.theme.glassFill
import com.brbrs.zaman.ui.theme.inkMuted
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs

@Composable
fun TodayScreen(
    onNewEvent: () -> Unit = {},
    onOpenEvent: (String, Long) -> Unit = { _, _ -> },
    onOpenSearch: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenCalendarMenu: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
    displayViewModel: DisplayViewModel = hiltViewModel(),
) {
    val events by viewModel.todaysEvents.collectAsState()
    val calendarColors by viewModel.calendarColors.collectAsState()
    val calendarCovers by viewModel.calendarCovers.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val weekItemCounts by viewModel.weekItemCounts.collectAsState()
    val themeMode by displayViewModel.themeMode.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val lastPendingDelete by viewModel.lastPendingDelete.collectAsState()
    val packedRatio by viewModel.packedRatio.collectAsState()
    val now = LocalTime.now()

    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(lastPendingDelete) {
        val pending = lastPendingDelete ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Deleted \"${pending.summary}\"",
            actionLabel = "Undo",
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.undoDelete(pending.href)
        viewModel.clearLastPendingDelete()
    }

    // Tracks the swipe direction so the AnimatedContent transition below slides the SAME way a
    // tap on a day chip would've moved (both go through viewModel.selectDate, so both animate
    // identically) — remembered across recompositions, read once per date change.
    var lastDate by remember { mutableStateOf(selectedDate) }
    val movingForward = selectedDate.isAfter(lastDate)
    lastDate = selectedDate

    ScreenBackground {
    // Feature: "calendar as a living organism" — an ambient glow over Today's own background
    // that breathes faster/stronger the more packed the day is (see TodayViewModel.packedRatio).
    // Deliberately layered here, inside TodayScreen's own content, rather than inside
    // ScreenBackground itself (ui/theme/Background.kt) — that composable is shared by every
    // other screen (Week, Month, EventEdit), and this glow is Today-only.
    BreathingGlow(packedRatio)
    Scaffold(
        containerColor = Color.Transparent,
        // No topBar here, and contentWindowInsets is zeroed out — the header Row below applies
        // its own statusBarsPadding() directly (same pattern as Tavla's CardScreen), so Scaffold
        // isn't also reserving a status-bar inset on top of it (item 7: that double accounting
        // was the real source of the oversized top margin, not the Row's own padding).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewEvent,
                shape = RoundedCornerShape(17.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.navigationBarsPadding(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New event")
            }
        }
    ) { _ ->
        val isDark = com.brbrs.zaman.ui.theme.LocalZamanIsDark.current
        Column(Modifier.fillMaxSize()) {

            // -- Top bar: brand, search, quick theme toggle, settings shortcut ----
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 10.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Opens the left-hand "which calendars show" drawer — see CalendarVisibilityDrawerContent.
                // Purely a display filter; sync on/off is still Settings' own job.
                IconButton(onClick = onOpenCalendarMenu) {
                    Icon(Icons.Filled.Menu, contentDescription = "Show calendars")
                }
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(com.brbrs.zaman.R.drawable.ic_logo_zaman),
                    contentDescription = "Zaman",
                    // The PNG itself is white-on-transparent, which read as invisible/washed-out
                    // on the light background. Re-tint it per theme instead of shipping a second
                    // asset: SrcIn keeps the artwork's own alpha (the brush-script shape) and just
                    // swaps every opaque pixel to the right color for the current theme.
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                        if (isDark) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Black,
                        androidx.compose.ui.graphics.BlendMode.SrcIn,
                    ),
                    modifier = Modifier.height(28.dp),
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
                // Item: a sync status/refresh control right here, so checking "did it sync" or
                // forcing a refresh no longer means going into Settings first. Spins while a sync
                // is actually running (SyncOutcome.RUNNING); a tap always requests a fresh sync.
                val isSyncing = syncStatus.outcome == com.brbrs.zaman.data.prefs.SyncOutcome.RUNNING
                val syncFailed = syncStatus.outcome == com.brbrs.zaman.data.prefs.SyncOutcome.FAILURE
                val infiniteTransition = rememberInfiniteTransition(label = "sync-spin")
                val spinAngle by infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                    label = "sync-spin-angle",
                )
                IconButton(onClick = { viewModel.syncNow() }) {
                    Icon(
                        if (syncFailed) Icons.Filled.SyncProblem else Icons.Filled.Sync,
                        contentDescription = if (isSyncing) "Syncing…" else "Sync now",
                        tint = if (syncFailed) ZamanColors.Danger else androidx.compose.material3.LocalContentColor.current,
                        modifier = if (isSyncing) Modifier.rotate(spinAngle) else Modifier,
                    )
                }
                IconButton(onClick = {
                    // A quick one-tap cycle between Light and Dark — Settings → Display still
                    // offers the full System/Light/Dark/OLED picker for anything more specific.
                    val next = if (themeMode == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
                    displayViewModel.setThemeMode(next)
                }) {
                    Icon(
                        if (themeMode == ThemeMode.LIGHT) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                        contentDescription = "Toggle theme",
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Settings")
                }
            }

            // Item 5 + transition: a horizontal swipe anywhere below the header moves to the
            // next/previous day, and AnimatedContent slides the whole date-head/week/agenda block
            // the same way regardless of whether the day changed by swipe or by tapping a day
            // chip — matching the slide used elsewhere in the app (e.g. opening Settings).
            var dragTotal = 0f
            AnimatedContent(
                targetState = selectedDate,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { dragTotal = 0f },
                            onHorizontalDrag = { change, dragAmount -> dragTotal += dragAmount; change.consume() },
                            onDragEnd = {
                                if (abs(dragTotal) > 80f) {
                                    if (dragTotal < 0) viewModel.selectDate(selectedDate.plusDays(1))
                                    else viewModel.selectDate(selectedDate.minusDays(1))
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
                label = "today-date",
            ) { date ->
                val dateEvents = if (date == selectedDate) events else emptyList()
                Column(Modifier.fillMaxSize()) {
                    // -- Date head --------------------------------------------------
                    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp)) {
                        // Item 6: "Wednesday, 28 October 2026 | D301/W44" — full weekday + day-month-
                        // year, then the day-of-year and ISO week number, each with its own letter.
                        val weekField = WeekFields.ISO.weekOfWeekBasedYear()
                        Text(
                            text = date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")) +
                                " | D${date.dayOfYear}/W${date.get(weekField)}",
                            style = ZamanType.headlineLarge,
                        )
                        Text(
                            text = if (dateEvents.isEmpty()) "No items · Nextcloud + Tavla + Koto"
                            else "${dateEvents.size} items · Nextcloud + Tavla + Koto",
                            style = ZamanType.bodyMedium,
                            color = inkMuted(),
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }

                    // -- Week strip ---------------------------------------------------
                    val weekStart = date.minusDays((date.dayOfWeek.value - 1).toLong())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        for (i in 0..6) {
                            val day = weekStart.plusDays(i.toLong())
                            DayChip(
                                day = day,
                                isActive = day == date,
                                hasItems = (weekItemCounts[day] ?: 0) > 0,
                                onClick = { viewModel.selectDate(day) },
                            )
                        }
                    }

                    // -- Agenda -------------------------------------------------------
                    if (dateEvents.isEmpty()) {
                        EmptyAgendaState()
                    } else {
                        val zone = ZoneId.systemDefault()
                        // Item 8: events visibly reordered mid-sync because two entries with the
                        // same occurrenceStartMillis (e.g. several all-day items) had no tiebreaker
                        // — ties fell back to whatever order Room's underlying query happened to
                        // return that emission, which can change while a sync is replacing rows.
                        // href is stable across emissions, so it's added as a final tiebreaker to
                        // make the sort fully deterministic.
                        val sorted = dateEvents.sortedWith(
                            compareBy({ !it.event.allDay }, { it.occurrenceStartMillis }, { it.event.href })
                        )
                        val isToday = date == LocalDate.now()
                        // Index to insert the live "now" row right before the first timed event after now.
                        val nowInsertIndex = if (isToday) {
                            sorted.indexOfFirst {
                                !it.event.allDay && Instant.ofEpochMilli(it.occurrenceStartMillis).atZone(zone).toLocalTime() > now
                            }
                        } else -1

                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            sorted.forEachIndexed { index, entry ->
                                if (index == nowInsertIndex) {
                                    item(key = "now-row") { NowRow(now) }
                                }
                                // Keyed by href+occurrence time, not just href — a recurring event's
                                // master row can produce several entries in the same window.
                                item(key = "${entry.event.href}-${entry.occurrenceStartMillis}") {
                                    AgendaRow(
                                        entry,
                                        calendarColorHex = calendarColors[entry.event.calendarUrl],
                                        calendarCoverPath = calendarCovers[entry.event.calendarUrl],
                                        onClick = if (entry.event.sourceApp == null) ({ onOpenEvent(entry.event.href, entry.occurrenceStartMillis) }) else null,
                                        // Feature #5: only a real Koto VTODO can be checked off from
                                        // here — Tavla's due-date cards have no VTODO behind them to
                                        // write STATUS:COMPLETED back to.
                                        onCompleteTask = if (entry.event.sourceApp == "KOTO") ({ viewModel.completeTask(entry.event) }) else null,
                                    )
                                }
                            }
                            if (isToday && nowInsertIndex == -1 && sorted.any { !it.event.allDay }) {
                                item(key = "now-row-end") { NowRow(now) }
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
private fun DayChip(day: LocalDate, isActive: Boolean, hasItems: Boolean, onClick: () -> Unit) {
    val bg = if (isActive) MaterialTheme.colorScheme.primary else glassFill()
    val border = if (isActive) MaterialTheme.colorScheme.primary else glassBorder()
    val dowColor = if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f) else inkMuted()
    val numColor = if (isActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val dotColor = if (isActive) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f) else inkMuted()

    Surface(
        modifier = Modifier.width(46.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Column(
            Modifier.padding(vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3),
                style = ZamanType.labelSmall,
                color = dowColor,
            )
            Spacer(Modifier.height(4.dp))
            Text(day.dayOfMonth.toString(), style = ZamanType.titleMedium, color = numColor)
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (hasItems) dotColor else Color.Transparent)
            )
        }
    }
}

@Composable
private fun NowRow(now: LocalTime) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = DateTimeFormatter.ofPattern("HH:mm").format(now),
            style = ZamanType.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(46.dp),
            textAlign = TextAlign.End,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun EmptyAgendaState() {
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("No events yet", style = ZamanType.titleMedium)
                Spacer(Modifier.width(4.dp))
                Text(
                    "Connect a Nextcloud account in Settings to pull in your calendars.",
                    style = ZamanType.bodyLarge,
                    color = inkMuted(),
                )
            }
        }
    }
}

@Composable
private fun AgendaRow(
    entry: AgendaEntry,
    calendarColorHex: String?,
    calendarCoverPath: String?,
    onClick: (() -> Unit)?,
    onCompleteTask: (() -> Unit)? = null,
) {
    val event = entry.event
    val timeLabel = if (event.allDay) "All day" else
        DateTimeFormatter.ofPattern("HH:mm").format(
            Instant.ofEpochMilli(entry.occurrenceStartMillis).atZone(ZoneId.systemDefault())
        )
    val stripe = stripeColorFor(event, calendarColorHex)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // -- Time rail: label + connecting vertical line, matching the mockup's .rail -----
        Column(Modifier.width(46.dp)) {
            Text(
                text = timeLabel,
                style = ZamanType.labelMedium,
                color = inkMuted(),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .padding(top = 6.dp, bottom = 2.dp)
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth().background(glassBorder()))
        }
        Spacer(Modifier.width(10.dp))

        val cardModifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }

        Surface(
            modifier = cardModifier,
            shape = RoundedCornerShape(16.dp),
            color = if (event.sourceApp != null) glassFill().copy(alpha = 0.6f) else glassFill(),
            border = androidx.compose.foundation.BorderStroke(1.dp, glassBorder()),
        ) {
            // IntrinsicSize.Min is required here: the stripe Box below is sized with
            // fillMaxHeight(), but a plain fillMaxWidth() Row has no bounded height of its own to
            // fill — its height just wraps its tallest child — so without this the stripe
            // resolved to 0dp and was completely invisible no matter what color it held. Same
            // pattern MonthScreen's DayAgendaItem already uses correctly for its own stripe.
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                // Color stripe, matching the mockup's --stripe left edge.
                Box(Modifier.width(3.dp).fillMaxHeight().background(stripe))
                Column(Modifier.padding(13.dp).weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        event.effectiveSourceBadge()?.let {
                            SourceLogo(it)
                            Spacer(Modifier.width(6.dp))
                        } ?: calendarCoverPath?.let { path ->
                            // Same "small round logo before the title" treatment as a Koto/Tavla
                            // SourceLogo, but for the event's own calendar cover instead of a
                            // suite-app icon — the two are mutually exclusive per row (a Koto/Tavla
                            // item has no real calendar of its own to have picked a cover for).
                            com.brbrs.zaman.ui.theme.CoverImage(
                                path = path,
                                modifier = Modifier.size(16.dp).clip(CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(event.summary, style = ZamanType.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    }
                    event.location?.let { address ->
                        val navContext = LocalContext.current
                        // Tapping the address opens whatever navigation app is installed — see
                        // util/ExternalApps.kt's openNavigationTo. Nested inside the row's own
                        // clickable (which opens the event detail popup); Compose resolves pointer
                        // input inner-first, so a tap here launches navigation instead of
                        // bubbling up to the row's click.
                        Text(
                            address,
                            style = ZamanType.bodySmall,
                            color = inkMuted(),
                            modifier = Modifier.padding(top = 2.dp).clickable { com.brbrs.zaman.util.openNavigationTo(navContext, address) },
                        )
                    }
                }
                // Feature #5 — Koto task checkoff: a small round checkbox, trailing, only on an
                // actual Koto VTODO row (see the onCompleteTask wiring at the call site). Tapping
                // it writes STATUS:COMPLETED back to Nextcloud and hides the row immediately.
                onCompleteTask?.let { complete ->
                    androidx.compose.material3.IconButton(
                        onClick = complete,
                        modifier = Modifier.size(40.dp).padding(end = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = "Mark task complete",
                            tint = inkMuted(),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Koto/Tavla items keep their fixed suite-app accent regardless of calendar (there isn't a real
 * CalDAV calendar color behind them — Tavla cards don't have one, and Koto task lists aren't
 * color-customized in Nextcloud the way event calendars are). Every native CalDAV event, though,
 * previously fell through to one flat ZamanColors.AccentDark no matter which calendar it actually
 * came from — this used [event.calendarUrl] to look up that calendar's own [CalendarEntity.colorHex]
 * (set in Nextcloud, synced in at discovery time) so two different calendars' events are visibly
 * distinguishable in the agenda, same as Nextcloud's own web Calendar already shows them.
 */
private fun stripeColorFor(event: EventEntity, calendarColorHex: String?): Color = when (event.sourceApp) {
    "KOTO" -> ZamanColors.AccentKoto
    "TAVLA" -> ZamanColors.AccentTavla
    else -> calendarColorHex
        ?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
        ?: ZamanColors.AccentDark
}

/**
 * Feature: "calendar as a living organism" — a soft radial glow over Today's background that
 * breathes (expands/contracts + fades) on a continuous loop, where [packedRatio] (0..1, booked
 * hours ÷ a standard working day — see TodayViewModel.packedRatio) drives BOTH how fast it
 * breathes and how strong/warm it looks: an empty day is a slow, faint, cool pulse; a packed one
 * is a quicker, brighter, warmer one — an ambient glanceable stress indicator you'd pick up on
 * without reading a single number. Purely decorative (ignores touch) and drawn low enough in the
 * z-order that every real control on top of it (Scaffold's FAB, agenda rows, the week strip)
 * stays fully legible — this is a background mood, not a foreground visual.
 */
@Composable
private fun BreathingGlow(packedRatio: Float) {
    // Calm day: slow 6s breath. Fully packed day: brisk 2.6s breath — still a "breath", not a
    // flicker; linear interpolation so the handoff between days reads as continuous, not jumpy.
    val periodMillis = (6000 - (3400 * packedRatio)).toInt().coerceAtLeast(2600)
    val transition = rememberInfiniteTransition(label = "breathingGlow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathingGlowPhase",
    )
    // Calm day glows a cool, muted blue; a packed day shifts warmer toward amber — the same
    // "heads up" color language EventEditScreen's conflict warning already uses.
    val glowColor = androidx.compose.ui.graphics.lerp(
        Color(0xFF4A7FBF),
        Color(0xFFEF9A00),
        packedRatio,
    )
    // Intensity: a quiet day still breathes a little (never fully invisible — it's ambient, not
    // binary), a packed day breathes noticeably stronger.
    val baseAlpha = 0.05f + (0.16f * packedRatio)
    val alpha = baseAlpha * (0.6f + 0.4f * phase)
    val scale = 0.92f + (0.18f * phase)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(glowColor.copy(alpha = alpha), glowColor.copy(alpha = 0f)),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.28f),
                        radius = (size.maxDimension * 0.75f * scale).coerceAtLeast(1f),
                    ),
                )
            },
    )
}
