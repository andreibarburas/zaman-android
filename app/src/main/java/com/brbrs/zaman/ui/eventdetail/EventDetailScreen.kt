package com.brbrs.zaman.ui.eventdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.data.local.AttendeeDetail
import com.brbrs.zaman.data.local.DREAM_BLOCK_CATEGORY
import com.brbrs.zaman.data.local.attendeeDetails
import com.brbrs.zaman.data.local.categories
import com.brbrs.zaman.data.local.isDreamBlock
import com.brbrs.zaman.ui.contacts.ContactsViewModel
import com.brbrs.zaman.ui.theme.AttendeeAvatar
import com.brbrs.zaman.ui.theme.CoverImage
import com.brbrs.zaman.ui.theme.LocalZamanIsDark
import com.brbrs.zaman.ui.theme.ScreenBackground
import com.brbrs.zaman.ui.theme.SourceLogo
import com.brbrs.zaman.ui.theme.ZamanColors
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.effectiveSourceBadge
import com.brbrs.zaman.ui.theme.inkMuted
import com.brbrs.zaman.ui.theme.zamanCard
import com.brbrs.zaman.util.findByEmail
import com.brbrs.zaman.util.openNavigationTo
import com.brbrs.zaman.util.openVinciContact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The single event screen — a genuine full-page destination (feature request: the old
 * ModalBottomSheet "quick peek" read as a floating overlay, not part of the app, next to every
 * other screen's own ScreenBackground + statusBarsPadding top bar). Reached exactly the same way
 * as before (a NavHost route, including the cross-app zaman://event/ deep link), just rendered as
 * a page instead of a sheet — the back button/back-stack plumbing is unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(
    onBack: () -> Unit,
    onEdit: (String, Long) -> Unit,
    viewModel: EventDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val isDark = LocalZamanIsDark.current
    // Feature: tapping an attendee opens their Vinci profile directly, when their ATTENDEE email
    // matches a known Vinci (CardDAV) contact — see util/ExternalApps.kt's findByEmail. Loaded
    // once per screen, same "safe to call every time, only hits the network once per session"
    // contract EventEditScreen's LinkedContactSection already relies on.
    val contactsViewModel: ContactsViewModel = hiltViewModel()
    LaunchedEffect(Unit) { contactsViewModel.load() }
    val vinciContacts by contactsViewModel.contacts.collectAsState()

    // Feature request: the screen's own background now carries a soft tint of the event's
    // calendar color (same mechanism EventEditScreen already uses) instead of the flat default
    // app gradient — null while state.colorHex hasn't loaded yet (or for a calendar with no
    // color set), which ScreenBackground already falls back on gracefully.
    ScreenBackground(calendarColorHex = state.colorHex) {
    Scaffold(
        containerColor = Color.Transparent,
        // Same fix as every other screen (Today/Month/Settings): applies its own
        // statusBarsPadding() on the header Row below instead of letting Scaffold account for
        // that inset a second time.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { _ ->
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.notFound || state.event == null -> Column(Modifier.fillMaxSize()) {
                TopBar(onBack = onBack, canEdit = false, onEdit = {}, onDelete = {})
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This event is gone.", style = ZamanType.bodyMedium, color = inkMuted())
                }
            }
            else -> {
                val event = state.event!!
                val stripe = runCatching { Color(android.graphics.Color.parseColor(state.colorHex)) }
                    .getOrDefault(ZamanColors.AccentDark)
                val zone = ZoneId.systemDefault()
                val occStart = state.occurrenceStartMillis
                val durationMillis = event.endEpochMillis?.let { it - event.startEpochMillis }
                val occEnd = durationMillis?.let { occStart + it }
                val canEdit = event.sourceApp == null // Koto/Tavla items stay read-only here too

                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding(),
                ) {
                    TopBar(
                        onBack = onBack,
                        canEdit = canEdit,
                        onEdit = { onEdit(event.href, occStart) },
                        onDelete = { viewModel.deleteEvent(); onBack() },
                    )

                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                        Spacer(Modifier.height(8.dp))

                        // Title — DM Serif Display, same headline style every other full screen
                        // in the suite uses for its own title, so this reads as a real page
                        // rather than a popup's bold-but-still-body-font label.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(stripe, CircleShape))
                            Spacer(Modifier.width(10.dp))
                            event.effectiveSourceBadge()?.let {
                                SourceLogo(it)
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(event.summary, style = ZamanType.headlineMedium)

                        Spacer(Modifier.height(6.dp))

                        // Which calendar this event is actually in — feature request: previously
                        // only a color dot hinted at this, never the calendar's own name.
                        state.calendarName?.let { name ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                state.calendarCoverImagePath?.let { path ->
                                    CoverImage(
                                        path = path,
                                        modifier = Modifier.size(18.dp).clip(RoundedCornerShapeFour),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(name, style = ZamanType.bodyMedium, color = inkMuted())
                            }
                            Spacer(Modifier.height(20.dp))
                        } ?: Spacer(Modifier.height(14.dp))

                        Column(
                            Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // Full date including the year, plus the time — feature request:
                            // the old "EEEE, MMMM d" label dropped the year entirely.
                            DetailRow(Icons.Filled.Schedule, timeLabel(event.allDay, occStart, occEnd, zone))

                            if (event.rrule != null) {
                                DetailRow(Icons.Filled.Repeat, "Repeating event")
                            }

                            event.location?.takeIf { it.isNotBlank() }?.let { address ->
                                // Tapping the address opens whatever navigation app is installed
                                // (a plain geo: intent — Android's own chooser, or a direct
                                // launch, picks among Maps/Waze/HERE/whatever is actually there).
                                DetailRow(
                                    icon = Icons.Filled.LocationOn,
                                    text = address,
                                    onClick = { openNavigationTo(context, address) },
                                )
                            }

                            if (event.isDreamBlock()) {
                                DetailRow(Icons.Filled.Shield, "Protected time", tint = androidx.compose.ui.graphics.Color(0xFFEF9A00))
                            }

                            // The dream-block sentinel tag (above, its own row) never shows up
                            // alongside the user's real tags here.
                            val categories = event.categories().filterNot { it.equals(DREAM_BLOCK_CATEGORY, ignoreCase = true) }
                            if (categories.isNotEmpty()) {
                                DetailRow(Icons.Filled.Label, categories.joinToString(", "))
                            }
                        }

                        val attendees = event.attendeeDetails()
                        if (attendees.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            Column(
                                Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Group, contentDescription = null, tint = inkMuted(), modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Attendees", style = ZamanType.labelMedium, color = inkMuted())
                                }
                                AttendeesRow(attendees, vinciContacts, contactsViewModel, context)
                            }
                        }

                        event.description?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(16.dp))
                            Text(it, style = ZamanType.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    }
}

/** Standard full-screen top bar — back arrow on the left, Edit/Delete on the right (only for a
 *  Zaman-native event, never a synced-in Koto/Tavla item) — same shape as Settings'/EventEdit's
 *  own header Row, so this page reads as one more screen in the suite, not a one-off popup. */
@Composable
private fun TopBar(onBack: () -> Unit, canEdit: Boolean, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
        }
        if (canEdit) {
            Row {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = ZamanColors.Danger)
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
            }
        }
    }
}

private val RoundedCornerShapeFour = RoundedCornerShape(4.dp)

@Composable
private fun DetailRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: (() -> Unit)? = null,
    tint: Color = inkMuted(),
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            style = ZamanType.bodyMedium,
            // Underlined + tinted primary, same "this is tappable" treatment the existing Linked
            // contact row (EventEditScreen) already uses, so it doesn't just look like plain text.
            color = if (onClick != null) MaterialTheme.colorScheme.primary else Color.Unspecified,
            textDecoration = if (onClick != null) androidx.compose.ui.text.style.TextDecoration.Underline else null,
        )
    }
}

/** One chip per attendee, now with their actual Vinci profile picture (or an initials circle —
 *  see AttendeeAvatar) instead of a generic Vinci logo, and the matched contact's own name.
 *  Tappable straight to their Vinci profile when a match exists; plain, non-tappable chips
 *  otherwise, same as EventEditScreen's own (editable) attendee chips look at rest. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AttendeesRow(
    attendees: List<AttendeeDetail>,
    vinciContacts: List<com.brbrs.zaman.data.carddav.VinciContact>,
    contactsViewModel: ContactsViewModel,
    context: android.content.Context,
) {
    // Fetched lazily per-contact (see ContactsViewModel.ensurePhotoLoaded's doc comment) rather
    // than bulk-loaded for the whole address book — this is the actual "only fetch photos for
    // the attendees on screen, not all 1283 contacts" fix.
    val photos by contactsViewModel.photos.collectAsState()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attendees.forEach { attendee ->
            val match = vinciContacts.findByEmail(attendee.email)
            LaunchedEffect(match?.uid) { match?.uid?.let { contactsViewModel.ensurePhotoLoaded(it) } }
            // Prefer the photo-augmented copy once its fetch resolves; the plain (photo-less)
            // match is still enough to show the name/open-Vinci chip meanwhile.
            val matchWithPhoto = match?.uid?.let { photos[it] } ?: match
            // A matched Vinci contact's own name wins over whatever CN the ICS attendee line
            // happened to carry (often stale or just the email again) — that's the point of
            // matching in the first place. Unmatched attendees fall back to their ICS name,
            // then the bare email, same as before.
            val label = match?.displayName ?: attendee.displayName?.takeIf { it.isNotBlank() } ?: attendee.email
            AssistChip(
                onClick = {
                    android.util.Log.d("ZamanVinciLink", "attendee chip clicked, email=${attendee.email}, match=${match?.uid}")
                    match?.let { openVinciContact(context, it.uid) }
                },
                label = { Text(label, style = ZamanType.bodySmall) },
                leadingIcon = {
                    AttendeeAvatar(
                        photoBase64 = matchWithPhoto?.photoBase64,
                        photoUrl = matchWithPhoto?.photoUrl,
                        displayName = label,
                        size = 20.dp,
                    )
                },
            )
        }
    }
}

private fun timeLabel(allDay: Boolean, startMillis: Long, endMillis: Long?, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(startMillis).atZone(zone)
    // Full date including the year (feature request) — the old "EEEE, MMMM d" silently assumed
    // "this year", which is wrong for anything more than a few months out.
    val dateFmt = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
    val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    if (allDay) return dateFmt.format(start)
    val datePart = dateFmt.format(start)
    val end = endMillis?.let { Instant.ofEpochMilli(it).atZone(zone) }
    return if (end != null) {
        if (end.toLocalDate() == start.toLocalDate()) {
            "$datePart · ${timeFmt.format(start)}–${timeFmt.format(end)}"
        } else {
            "$datePart ${timeFmt.format(start)} – ${dateFmt.format(end)} ${timeFmt.format(end)}"
        }
    } else {
        "$datePart · ${timeFmt.format(start)}"
    }
}
