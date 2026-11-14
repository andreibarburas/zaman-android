package com.brbrs.zaman.ui.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.ui.theme.CalendarTileBackground
import com.brbrs.zaman.ui.theme.LocalZamanIsDark
import com.brbrs.zaman.ui.theme.ZamanColors
import com.brbrs.zaman.ui.theme.ZamanType

/**
 * The left-hand "which calendars show" menu — a standard Material3 slide-in drawer, opened by a
 * hamburger icon on Today/Week/Month's own top bars (see ZamanNavHost), listing every calendar
 * with the same frosted-cover-or-color-glow tile look as Tavla's board rows (see
 * CalendarTileBackground's doc comment). Checking a row is purely a DISPLAY toggle — it hides
 * that calendar's events from Today/Week/Month only; whether a calendar syncs at all is still
 * decided in Settings → Calendars, untouched by this screen.
 */
@Composable
fun CalendarVisibilityDrawerContent(
    modifier: Modifier = Modifier,
    viewModel: CalendarVisibilityViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    val isDark = LocalZamanIsDark.current

    ModalDrawerSheet(modifier = modifier) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(10.dp))
                Text("Show calendars", style = ZamanType.titleMedium)
            }
            Text(
                "Choose which calendars appear in Today, Week and Month. This doesn't change what's synced — manage that in Settings.",
                style = ZamanType.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            )

            if (rows.isEmpty()) {
                Text(
                    "No calendars yet — add one from Settings.",
                    style = ZamanType.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rows, key = { it.calendar.url }) { row ->
                        CalendarVisibilityRowItem(
                            row = row,
                            isDark = isDark,
                            onToggle = { viewModel.setVisible(row.calendar.url, !row.visible) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CalendarVisibilityRowItem(
    row: CalendarVisibilityRow,
    isDark: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A calendar that isn't synced can never actually show anything (see the ViewModel's doc
    // comment on `visible`), so its checkbox is disabled rather than toggleable — nothing to
    // turn on until the user enables sync for it in Settings.
    val synced = row.calendar.enabled
    CalendarTileBackground(
        colorHex = row.calendar.colorHex,
        coverImagePath = row.calendar.coverImagePath,
        isDark = isDark,
        cornerRadius = 16.dp,
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .alpha(if (synced) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Small cover-photo-or-color thumbnail to the left of the name — the exact same
            // treatment as an agenda row's calendar cover (see TodayScreen's AgendaRow /
            // CalendarsScreen's own calendar-list row), so the same calendar reads identically
            // wherever its cover/color shows up in the app.
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val coverPath = row.calendar.coverImagePath
                if (coverPath != null) {
                    com.brbrs.zaman.ui.theme.CoverImage(
                        path = coverPath,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    val swatch = runCatching { Color(android.graphics.Color.parseColor(row.calendar.colorHex)) }
                        .getOrDefault(if (isDark) ZamanColors.AccentDark else ZamanColors.AccentLight)
                    Box(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(swatch))
                }
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = row.calendar.displayName,
                style = ZamanType.titleMedium,
                color = if (isDark) ZamanColors.InkDark else ZamanColors.InkLight,
                modifier = Modifier.weight(1f),
            )
            Checkbox(
                checked = row.visible,
                enabled = synced,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = if (isDark) Color.White.copy(alpha = 0.6f) else ZamanColors.InkMutedLight,
                ),
            )
        }
    }
}
