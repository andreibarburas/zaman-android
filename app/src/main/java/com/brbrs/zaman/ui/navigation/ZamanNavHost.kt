package com.brbrs.zaman.ui.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.brbrs.zaman.sync.contract.CalendarContractEventResolver
import com.brbrs.zaman.ui.calendars.CalendarVisibilityDrawerContent
import com.brbrs.zaman.ui.eventedit.EventEditScreen
import com.brbrs.zaman.ui.eventdetail.EventDetailScreen
import com.brbrs.zaman.ui.month.MonthScreen
import com.brbrs.zaman.ui.week.WeekScreen
import com.brbrs.zaman.ui.search.SearchScreen
import com.brbrs.zaman.ui.settings.SettingsScreen
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.glassBorder
import com.brbrs.zaman.ui.theme.inkMuted
import com.brbrs.zaman.ui.today.TodayScreen
import kotlinx.coroutines.launch

private sealed class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Today : Tab("today", "Today", Icons.Filled.CalendarToday)
    object Week : Tab("week", "Week", Icons.Filled.ViewWeek)
    object Month : Tab("month", "Month", Icons.Filled.CalendarMonth)
}

// Settings is reached from Today's own top-bar gear icon, not a bottom tab (that icon would
// otherwise duplicate this one) — see TodayScreen's onOpenSettings. Calendars' per-calendar
// toggles moved into Settings itself as a collapsible section (see SettingsScreen's
// CalendarsSection), so there's no separate Calendars screen/route anymore either.
private const val SETTINGS_ROUTE = "settings"
private val tabs = listOf(Tab.Today, Tab.Week, Tab.Month)
private const val EVENT_EDIT_ROUTE = "event_edit?eventHref={eventHref}&occurrenceStart={occurrenceStart}"
private const val EVENT_DETAIL_ROUTE = "event_detail?eventHref={eventHref}&occurrenceStart={occurrenceStart}"
private const val SEARCH_ROUTE = "search"

fun newEventRoute() = "event_edit?eventHref=&occurrenceStart=-1"

/**
 * occurrenceStartMillis is which INSTANCE of a recurring series was tapped — e.g. the 3rd Tuesday
 * of a weekly series, not the series' own original DTSTART — so EventEditViewModel can tell them
 * apart when the user picks "this event" / "this and future" / "all events" (feature #4). -1
 * means "not a specific occurrence" (e.g. opened from Search, which lists masters, not expanded
 * occurrences); EventEditViewModel falls back to the event's own startEpochMillis in that case.
 */
fun editEventRoute(href: String, occurrenceStartMillis: Long = -1L) =
    "event_edit?eventHref=${android.net.Uri.encode(href)}&occurrenceStart=$occurrenceStartMillis"

/** Same occurrenceStartMillis convention as [editEventRoute] — every in-app tap on an event now
 *  lands here first (a read-only popup), with its own Edit button pushing on to [editEventRoute]
 *  only if the user actually wants to change something. */
fun eventDetailRoute(href: String, occurrenceStartMillis: Long = -1L) =
    "event_detail?eventHref=${android.net.Uri.encode(href)}&occurrenceStart=$occurrenceStartMillis"

@Composable
fun ZamanNavHost() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val onTabScreen = tabs.any { it.route == currentRoute }

    // The left-hand "which calendars show" menu (see CalendarVisibilityDrawerContent) — a single
    // drawer shared by Today/Week/Month, each reached via a hamburger icon on its own top bar,
    // rather than three separate drawers, since the visibility it controls already applies
    // uniformly across all three screens.
    val calendarDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val openCalendarMenu: () -> Unit = { drawerScope.launch { calendarDrawerState.open() } }

    // "Open in Zaman" from another app's own calendar UI (see MainActivity's manifest
    // intent-filter and CalendarContractEventResolver's doc comment): that intent carries a
    // content://com.android.calendar/events/<id> URI, which navDeepLink alone can't resolve —
    // it only pattern-matches a URI against an argument placeholder, it can't run a
    // ContentResolver query to translate a platform event id into Zaman's own href first. So
    // this reads the launching Activity's own Intent once, resolves it off the main thread, and
    // navigates straight to the SAME detail popup a normal in-app tap opens, once ready.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val activity = context as? android.app.Activity ?: return@LaunchedEffect
        val intent = activity.intent
        val data = intent?.data
        if (intent?.action == android.content.Intent.ACTION_VIEW &&
            data?.scheme == "content" && data.host == "com.android.calendar"
        ) {
            val href = CalendarContractEventResolver.resolveHref(context, data)
            // Clear the data so a later recomposition (e.g. a config change) doesn't re-fire
            // this navigation a second time for the same already-handled intent.
            activity.intent = android.content.Intent(intent).setData(null)
            if (href != null) navController.navigate(eventDetailRoute(href))
        }
    }

    ModalNavigationDrawer(
        drawerState = calendarDrawerState,
        // Only Today/Week/Month actually offer the hamburger icon that opens this, but gating the
        // drawer itself to onTabScreen isn't necessary — gestureEnabled off is enough to stop an
        // accidental edge-swipe from opening it over Settings/Search/Edit, where it wouldn't apply.
        gesturesEnabled = onTabScreen,
        drawerContent = { CalendarVisibilityDrawerContent() },
    ) {
    Scaffold(
        // Each individual screen (Today, Month, Settings, Edit Event) already zeroes out its
        // OWN Scaffold's contentWindowInsets and applies statusBarsPadding() itself on its header
        // Row. This outer Scaffold wraps all of them via NavHost, and its default
        // contentWindowInsets (safeDrawing, which includes the top status-bar inset) was being
        // applied a second time via `.padding(padding)` below — the real source of the
        // oversized top margin that survived the enableEdgeToEdge() fix. Zeroing it here too
        // stops that second application.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (onTabScreen) {
                val currentDestination = backStackEntry?.destination
                Surface(
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                    modifier = Modifier.fillMaxWidth().border(
                        androidx.compose.foundation.BorderStroke(1.dp, glassBorder()),
                    ),
                ) {
                    Row(
                        // Taller bottom bar (feature request: the old 10dp felt cramped) — more
                        // vertical padding plus a slightly bigger icon, not just padding alone,
                        // so the extra height doesn't just look like dead space around a small icon.
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceAround,
                    ) {
                        tabs.forEach { tab ->
                            val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                            val tint = if (selected) MaterialTheme.colorScheme.primary else inkMuted()
                            Column(
                                modifier = Modifier
                                    .clickable {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                    .padding(horizontal = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(24.dp))
                                Text(tab.label, style = ZamanType.labelSmall, color = tint, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.Today.route,
            modifier = androidx.compose.ui.Modifier.padding(padding),
        ) {
            composable(Tab.Today.route) {
                TodayScreen(
                    onNewEvent = { navController.navigate(newEventRoute()) },
                    onOpenEvent = { href, occurrenceStart -> navController.navigate(eventDetailRoute(href, occurrenceStart)) },
                    onOpenSearch = { navController.navigate(SEARCH_ROUTE) },
                    onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
                    onOpenCalendarMenu = openCalendarMenu,
                )
            }
            composable(Tab.Week.route) {
                WeekScreen(
                    onOpenEvent = { href, occurrenceStart -> navController.navigate(eventDetailRoute(href, occurrenceStart)) },
                    onOpenCalendarMenu = openCalendarMenu,
                )
            }
            composable(Tab.Month.route) {
                MonthScreen(
                    onOpenEvent = { href, occurrenceStart -> navController.navigate(eventDetailRoute(href, occurrenceStart)) },
                    onOpenCalendarMenu = openCalendarMenu,
                )
            }
            composable(SETTINGS_ROUTE) { SettingsScreen(onBack = { navController.popBackStack() }) }
            composable(SEARCH_ROUTE) {
                SearchScreen(
                    onBack = { navController.popBackStack() },
                    onOpenEvent = { href -> navController.navigate(eventDetailRoute(href)) },
                )
            }
            // A tap on any event lands here first — a read-only popup (ModalBottomSheet) — whose
            // own Edit button pushes on to EVENT_EDIT_ROUTE below, rather than every onOpenEvent
            // caller jumping straight into editing (feature request: that used to be the default).
            composable(
                route = EVENT_DETAIL_ROUTE,
                arguments = listOf(
                    navArgument("eventHref") { type = NavType.StringType; defaultValue = "" },
                    navArgument("occurrenceStart") { type = NavType.LongType; defaultValue = -1L },
                ),
                // Vinci/Tavla/Koto's cross-app event links AND the home-screen widget's own row
                // taps (see ZamanAgendaWidget) both resolve to this zaman://event/<href> URI —
                // moved here from the edit route so an external tap opens the same read-only
                // popup an in-app tap does, rather than jumping straight into editing.
                deepLinks = listOf(
                    androidx.navigation.navDeepLink { uriPattern = "zaman://event/{eventHref}" }
                ),
            ) {
                EventDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { href, occurrenceStart ->
                        // Replace the detail popup with the edit screen rather than stacking on
                        // top of it, so the system/app back button from Edit returns straight to
                        // Today/Week/Month instead of back through the popup again.
                        navController.navigate(editEventRoute(href, occurrenceStart)) {
                            popUpTo(EVENT_DETAIL_ROUTE) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = EVENT_EDIT_ROUTE,
                arguments = listOf(
                    navArgument("eventHref") { type = NavType.StringType; defaultValue = "" },
                    navArgument("occurrenceStart") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) {
                EventEditScreen(onDone = { navController.popBackStack() })
            }
        }
    }
    }
}
