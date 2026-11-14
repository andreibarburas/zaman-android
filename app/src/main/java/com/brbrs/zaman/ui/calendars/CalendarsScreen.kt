package com.brbrs.zaman.ui.calendars

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.prefs.SyncOutcome
import com.brbrs.zaman.ui.settings.SyncStatusLine
import com.brbrs.zaman.ui.theme.GlassCard
import com.brbrs.zaman.ui.theme.ZamanColors
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.glassBorder
import com.brbrs.zaman.ui.theme.inkMuted

/**
 * The per-calendar enable/disable list — previously its own bottom-bar "Calendars" tab, now a
 * collapsible section inside Settings (it's account configuration, not something you open daily
 * the way Today/Month are). Collapsed by default, showing just a one-line summary so Settings
 * doesn't get overwhelming for an account with many calendars.
 */
@Composable
fun CalendarsSection(viewModel: CalendarsViewModel = hiltViewModel()) {
    val calendars by viewModel.calendars.collectAsState()
    val eventCounts by viewModel.eventCounts.collectAsState()
    val status by viewModel.syncStatus.collectAsState()
    var expanded by remember { mutableStateOf(false) }

    val writable = calendars.filter { !it.isSubscription && !it.isTaskList && it.sourceApp == null }
    val subscriptions = calendars.filter { it.isSubscription }
    val crossApp = calendars.filter { it.isTaskList || (it.sourceApp != null && !it.isSubscription) }
    val enabledCount = calendars.count { it.enabled }

    SectionLabel("Calendars")
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when {
                            calendars.isEmpty() -> "No calendars yet"
                            else -> "$enabledCount of ${calendars.size} enabled · ${eventCounts.values.sum()} events cached"
                        },
                        style = ZamanType.bodyMedium,
                    )
                    SyncStatusLine(status, modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(
                    onClick = { viewModel.refreshFromNextcloud() },
                    enabled = status.outcome != SyncOutcome.RUNNING,
                ) {
                    if (status.outcome == SyncOutcome.RUNNING) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Sync, contentDescription = "Sync now")
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                    if (writable.isNotEmpty()) {
                        SubsectionLabel("Nextcloud")
                        writable.forEach { cal ->
                            CalendarRow(
                                cal, eventCounts[cal.url],
                                onToggle = { viewModel.setEnabled(cal.url, it) },
                                onCoverPicked = { uri -> viewModel.setCoverImage(cal.url, uri) },
                                onClearCover = { viewModel.setCoverImage(cal.url, null) },
                            )
                        }
                    }
                    if (subscriptions.isNotEmpty()) {
                        SubsectionLabel("Subscribed in Nextcloud")
                        subscriptions.forEach { cal ->
                            CalendarRow(
                                cal, eventCounts[cal.url],
                                onToggle = { viewModel.setEnabled(cal.url, it) },
                                onCoverPicked = { uri -> viewModel.setCoverImage(cal.url, uri) },
                                onClearCover = { viewModel.setCoverImage(cal.url, null) },
                            )
                        }
                    }
                    if (crossApp.isNotEmpty()) {
                        SubsectionLabel("Tasks with due dates")
                        crossApp.forEach { cal ->
                            CalendarRow(
                                cal, eventCounts[cal.url],
                                onToggle = { viewModel.setEnabled(cal.url, it) },
                                onCoverPicked = { uri -> viewModel.setCoverImage(cal.url, uri) },
                                onClearCover = { viewModel.setCoverImage(cal.url, null) },
                            )
                        }
                    }
                    AddCalendarRow(
                        running = status.outcome == SyncOutcome.RUNNING,
                        onClick = { viewModel.refreshFromNextcloud() },
                    )
                    Row(Modifier.padding(top = 8.dp, bottom = 4.dp)) { HintCard() }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = ZamanType.labelMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
    )
}

@Composable
private fun SubsectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = ZamanType.labelSmall,
        color = inkMuted(),
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarRow(
    calendar: CalendarEntity,
    eventCount: Int?,
    onToggle: (Boolean) -> Unit,
    onCoverPicked: (android.net.Uri?) -> Unit,
    onClearCover: () -> Unit,
) {
    val swatch = when {
        calendar.sourceApp == "KOTO" -> ZamanColors.AccentKoto
        calendar.sourceApp == "TAVLA" -> ZamanColors.AccentTavla
        else -> runCatching { Color(android.graphics.Color.parseColor(calendar.colorHex)) }.getOrDefault(ZamanColors.AccentDark)
    }
    // Koto/Tavla rows are cross-app placeholders, not real calendars the user owns in
    // Nextcloud — there's nothing meaningful to attach a cover photo to there.
    val coverPickable = calendar.sourceApp == null
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onCoverPicked(uri)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .then(
                    if (coverPickable) {
                        // Long-press to clear a cover that's already set (back to the plain color
                        // swatch) — a tap always opens the picker to set/replace one. combinedClickable
                        // needs onLongClick wired even when there's nothing to clear yet, so it's a
                        // no-op until a cover actually exists.
                        Modifier.combinedClickable(
                            onClick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            onLongClick = { if (calendar.coverImagePath != null) onClearCover() },
                        )
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (calendar.coverImagePath != null) {
                com.brbrs.zaman.ui.theme.CoverImage(
                    path = calendar.coverImagePath,
                    contentDescription = "${calendar.displayName} cover",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(
                    color = swatch,
                    shape = RoundedCornerShape(if (coverPickable) 8.dp else 4.dp),
                    modifier = if (coverPickable) Modifier.fillMaxSize() else Modifier.size(13.dp),
                ) {}
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(calendar.displayName, style = ZamanType.bodyMedium)
            Text(
                text = buildString {
                    append(
                        when {
                            calendar.sourceApp == "KOTO" -> "Koto — shown as all-day items"
                            calendar.isTaskList -> "Tasks · read-only"
                            calendar.isSubscription -> "ICS subscription · read-only"
                            calendar.readOnly -> "CalDAV · shared, read-only"
                            else -> "CalDAV · writable"
                        }
                    )
                    // Only meaningful for a calendar that's actually being synced — a disabled
                    // one's count is just whatever was cached before it was turned off, not a
                    // live reflection of anything, so showing it there would be misleading rather
                    // than informative.
                    if (calendar.enabled) {
                        append(" · ")
                        append(if (eventCount == null || eventCount == 0) "no events cached yet" else "$eventCount cached")
                    }
                },
                style = ZamanType.labelSmall,
                color = inkMuted(),
            )
        }
        Switch(
            checked = calendar.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
            ),
        )
    }
}

/**
 * There is no in-app "add a calendar/subscription" form — Zaman has no UI for entering a
 * webcal:// URL or creating a new collection of its own; subscriptions are added in Nextcloud's
 * own web/desktop Calendar app (see HintCard below), and Zaman just picks them up on the next
 * discovery pass. This row used to be a plain, unclickable Surface+Text with no `clickable`
 * modifier at all — tapping it did literally nothing, which looked like a broken "add" button.
 * Wiring it to the same refresh (discover + sync) the toolbar's Sync icon already runs turns it
 * into what it always should have been: "go check Nextcloud for anything new" rather than a
 * dead decoration.
 */
@Composable
private fun AddCalendarRow(running: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clickable(enabled = !running, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(1.dp, glassBorder()),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(
                    text = "  Checking Nextcloud…",
                    style = ZamanType.labelMedium,
                    color = inkMuted(),
                )
            } else {
                Text(
                    text = "＋  Add calendar or subscription",
                    style = ZamanType.labelMedium,
                    color = inkMuted(),
                )
            }
        }
    }
}

@Composable
private fun HintCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp).padding(top = 1.dp),
            )
            Text(
                text = "Zaman talks to Nextcloud over CalDAV directly — no DAVx5 needed. " +
                    "New calendars and subscriptions you add in Nextcloud show up here automatically. " +
                    "Tap a calendar's icon to set a cover photo; long-press it to clear one.",
                style = ZamanType.bodySmall,
                color = inkMuted(),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
