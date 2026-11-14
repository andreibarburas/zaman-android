@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.brbrs.zaman.ui.eventedit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.data.local.isDreamBlock
import com.brbrs.zaman.ui.theme.LocalZamanIsDark
import com.brbrs.zaman.ui.theme.ScreenBackground
import com.brbrs.zaman.ui.theme.SectionLabel
import com.brbrs.zaman.ui.theme.TopBarIcon
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.inkMuted
import com.brbrs.zaman.ui.theme.zamanCard
import com.brbrs.zaman.util.findByEmail
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Transparent-fill text field colors, matching Tavla's borderless input look on top of a
 *  zamanCard section — Material3's default OutlinedTextField border doubled up against the
 *  card's own border and was a big part of why this screen read "rough" next to Tavla's.
 *  The unfocused indicator is NOT fully transparent (a first pass made it so) — an empty field
 *  with zero border and zero fill is indistinguishable from empty space until it's tapped and
 *  gains the focused border, which read as "the field isn't there." A faint hairline, matching
 *  the card's own border, keeps every field visibly a field at rest. */
@Composable
private fun sectionFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    disabledContainerColor = androidx.compose.ui.graphics.Color.Transparent,
    focusedIndicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
    unfocusedIndicatorColor = com.brbrs.zaman.ui.theme.glassBorder(),
    disabledIndicatorColor = com.brbrs.zaman.ui.theme.glassBorder(),
)

@Composable
fun EventEditScreen(
    onDone: () -> Unit,
    viewModel: EventEditViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsState()
    val calendars by viewModel.writableCalendars.collectAsState()
    val allCalendars by viewModel.allCalendars.collectAsState()
    val saveResult by viewModel.saveResult.collectAsState()
    val pendingScopeChoice by viewModel.pendingScopeChoice.collectAsState()
    val pendingDreamBlockWarning by viewModel.pendingDreamBlockWarning.collectAsState()
    val isDark = LocalZamanIsDark.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Feature: tapping an attendee chip opens their Vinci profile directly, same lookup
    // LinkedContactSection's picker already does — see util/ExternalApps.kt's findByEmail.
    // hiltViewModel() with no key returns the SAME instance LinkedContactSection's own call
    // further down resolves to (both scoped to this screen's nav back-stack entry), so this
    // doesn't trigger a second contacts fetch.
    val contactsViewModel: com.brbrs.zaman.ui.contacts.ContactsViewModel = hiltViewModel()
    LaunchedEffect(Unit) { contactsViewModel.load() }
    val vinciContacts by contactsViewModel.contacts.collectAsState()

    LaunchedEffect(saveResult) {
        if (saveResult is SaveResult.Done) onDone()
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // Per item 3: the edit screen's background tints toward the event's own calendar — its
    // color, or (frosted, like Tavla's CardScreen) its cover photo when it has one — instead of
    // the generic app gradient every other screen gets. Looked up from allCalendars, not the
    // writable-only list, since an existing event can sit on a read-only subscription calendar.
    val selectedCalendar = allCalendars.firstOrNull { it.url == form.calendarUrl }

    ScreenBackground(
        calendarColorHex = selectedCalendar?.colorHex,
        coverImagePath = selectedCalendar?.coverImagePath,
    ) {
    Column(Modifier.fillMaxSize().imePadding()) {
        // -- Header -- direct port of Tavla's CardScreen header: a bare Row with its own
        // statusBarsPadding (not Scaffold's), borderless TopBarIcons instead of a Material3
        // TopAppBar, and no filled background of its own — the ScreenBackground shows through.
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onDone() }
            Spacer(Modifier.weight(1f))
            Text(
                if (form.isExistingEvent) "Edit event" else "New event",
                style = ZamanType.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            if (form.isExistingEvent) {
                val context = androidx.compose.ui.platform.LocalContext.current
                TopBarIcon(Icons.Filled.Share, "Share event") {
                    val ics = viewModel.buildIcsForShare()
                    val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/calendar"
                        putExtra(android.content.Intent.EXTRA_TEXT, ics)
                        putExtra(android.content.Intent.EXTRA_SUBJECT, form.summary)
                    }
                    context.startActivity(android.content.Intent.createChooser(sendIntent, "Share event"))
                }
                TopBarIcon(Icons.Filled.ContentCopy, "Duplicate event") { viewModel.duplicate() }
            }
            if (form.canDelete) {
                TopBarIcon(Icons.Filled.Delete, "Delete event") { showDeleteConfirm = true }
            } else if (!form.isExistingEvent) {
                Spacer(Modifier.width(44.dp))
            }
        }

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Every field-group below is labeled the same way Settings labels its sections
            // ("ACCOUNT", "SECURITY", …) — a small uppercase SectionLabel above a zamanCard —
            // instead of the plain-text sub-headers ("Attendees", "Linked contact") this screen
            // used to mix in among them, which was the actual font/heading inconsistency being
            // seen: two different heading treatments on the same screen.

            // -- Title + all-day --
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Event")
                Column(Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp)) {
                    OutlinedTextField(
                        value = form.summary,
                        onValueChange = { v -> viewModel.update { it.copy(summary = v) } },
                        label = { Text("Title") },
                        singleLine = true,
                        colors = sectionFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.padding(top = 12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("All day", style = ZamanType.titleMedium, modifier = Modifier.weight(1f))
                        Switch(checked = form.allDay, onCheckedChange = { v -> viewModel.update { it.copy(allDay = v) } })
                    }
                }
            }

            // -- Date & time --
            val dateFmt = DateTimeFormatter.ofPattern("EEE, d MMM yyyy")
            val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Date & time")
                Column(Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Date", style = ZamanType.labelMedium, color = inkMuted())
                            Text(form.start.format(dateFmt), style = ZamanType.titleMedium, modifier = Modifier.padding(top = 2.dp))
                        }
                        TextButton(onClick = { showDatePicker = true }) { Text("Change") }
                    }
                    if (!form.allDay) {
                        Spacer(Modifier.padding(top = 10.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.weight(1f).clickable { showStartTimePicker = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("Starts", style = ZamanType.labelMedium, color = inkMuted())
                                    Text(form.start.format(timeFmt), style = ZamanType.titleMedium, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                            Row(
                                Modifier.weight(1f).clickable { showEndTimePicker = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("Ends", style = ZamanType.labelMedium, color = inkMuted())
                                    Text(form.end.format(timeFmt), style = ZamanType.titleMedium, modifier = Modifier.padding(top = 2.dp))
                                }
                            }
                        }
                    }
                }
            }

            // -- Location & notes --
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Location & notes")
                Column(Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp)) {
                    OutlinedTextField(
                        value = form.location,
                        onValueChange = { v -> viewModel.update { it.copy(location = v) } },
                        label = { Text("Location") },
                        singleLine = true,
                        colors = sectionFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val travelEstimate by viewModel.travelEstimate.collectAsState()
                    travelEstimate?.let { estimate ->
                        Text(
                            "🚗 $estimate",
                            style = ZamanType.labelMedium,
                            color = inkMuted(),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Spacer(Modifier.padding(top = 8.dp))
                    OutlinedTextField(
                        value = form.description,
                        onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                        label = { Text("Notes") },
                        colors = sectionFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                }
            }

            // -- Calendar, repeat, reminder --
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Calendar & repeat")
                Column(Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!form.isExistingEvent) {
                        CalendarPicker(
                            calendars = calendars,
                            selectedUrl = form.calendarUrl,
                            onSelect = { url -> viewModel.update { it.copy(calendarUrl = url) } },
                        )
                    }
                    RepeatPicker(
                        selected = form.repeatOption,
                        // CUSTOM only ever comes from an RRULE Nextcloud already wrote — it's shown but
                        // not selectable, so the user can't accidentally flatten "every 2nd Tuesday"
                        // down to plain weekly by picking from this list.
                        options = RepeatOption.entries.filter { it != RepeatOption.CUSTOM || form.repeatOption == RepeatOption.CUSTOM },
                        onSelect = { option -> viewModel.update { s -> s.copy(repeatOption = option) } },
                    )
                    ReminderPicker(
                        selected = form.reminders,
                        onAdd = { option -> viewModel.update { s -> s.copy(reminders = s.reminders + option) } },
                        onRemove = { index -> viewModel.update { s -> s.copy(reminders = s.reminders.filterIndexed { i, _ -> i != index }) } },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Attendees")
                Column(Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (form.attendees.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            val attendeePhotos by contactsViewModel.photos.collectAsState()
                            form.attendees.forEach { a ->
                                val match = vinciContacts.findByEmail(a.email)
                                androidx.compose.runtime.LaunchedEffect(match?.uid) {
                                    match?.uid?.let { contactsViewModel.ensurePhotoLoaded(it) }
                                }
                                // Fetched lazily per-attendee (see ContactsViewModel.ensurePhotoLoaded)
                                // rather than bulk-loaded for the whole address book.
                                val matchWithPhoto = match?.uid?.let { attendeePhotos[it] } ?: match
                                // A matched Vinci contact's own name wins over the ICS CN, same
                                // reasoning as EventDetailScreen's AttendeesRow.
                                val label = match?.displayName ?: a.displayName?.takeIf { it.isNotBlank() } ?: a.email
                                val statusSuffix = when (a.partstat) {
                                    "ACCEPTED" -> " ✓"
                                    "DECLINED" -> " ✕"
                                    "TENTATIVE" -> " ?"
                                    else -> ""
                                }
                                androidx.compose.material3.AssistChip(
                                    onClick = {
                                        android.util.Log.d("ZamanVinciLink", "attendee chip clicked, email=${a.email}, match=${match?.uid}")
                                        match?.let { com.brbrs.zaman.util.openVinciContact(context, it.uid) }
                                    },
                                    label = { Text(label + statusSuffix) },
                                    // Same actual-profile-picture-or-initials avatar as the single
                                    // event screen, rather than a generic Vinci logo, for an
                                    // attendee with a matching contact.
                                    leadingIcon = if (match != null) {
                                        { com.brbrs.zaman.ui.theme.AttendeeAvatar(matchWithPhoto?.photoBase64, matchWithPhoto?.photoUrl, label, size = 20.dp) }
                                    } else null,
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close, "Remove",
                                            modifier = Modifier.size(16.dp).clickable { viewModel.removeAttendee(a.email) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    var newAttendeeEmail by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newAttendeeEmail,
                            onValueChange = { newAttendeeEmail = it },
                            label = { Text("Invite by email") },
                            singleLine = true,
                            colors = sectionFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            if (android.util.Patterns.EMAIL_ADDRESS.matcher(newAttendeeEmail.trim()).matches()) {
                                viewModel.addAttendee(newAttendeeEmail.trim())
                                newAttendeeEmail = ""
                            }
                        }) { Text("Add") }
                    }
                }
            }

            // Dream scheduling: a "Protect this time" block is marked with the one sentinel
            // CATEGORIES tag (see Entities.kt's DREAM_BLOCK_CATEGORY) via the same addCategory/
            // removeCategory the Tags section below already uses, so it's filtered out of that
            // generic tag list (it's not something the user typed, it's a switch) and shown here
            // as its own dedicated row instead.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Dream scheduling")
                Row(
                    Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Protect this time", style = ZamanType.bodyLarge)
                        Text(
                            "For the things you want to do but never prioritize — Zaman will warn you before you book over it.",
                            style = ZamanType.bodySmall,
                            color = inkMuted(),
                        )
                    }
                    Switch(
                        checked = form.categories.any { it.equals(com.brbrs.zaman.data.local.DREAM_BLOCK_CATEGORY, ignoreCase = true) },
                        onCheckedChange = { viewModel.setDreamBlockProtected(it) },
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel("Tags")
                Column(Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The dream-block sentinel tag has its own switch above — never show it here.
                    val visibleCategories = form.categories.filterNot {
                        it.equals(com.brbrs.zaman.data.local.DREAM_BLOCK_CATEGORY, ignoreCase = true)
                    }
                    if (visibleCategories.isNotEmpty()) {
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            visibleCategories.forEach { tag ->
                                androidx.compose.material3.AssistChip(
                                    onClick = {},
                                    label = { Text(tag) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close, "Remove",
                                            modifier = Modifier.size(16.dp).clickable { viewModel.removeCategory(tag) },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    var newTag by remember { mutableStateOf("") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTag,
                            onValueChange = { newTag = it },
                            label = { Text("Add tag") },
                            singleLine = true,
                            colors = sectionFieldColors(),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            viewModel.addCategory(newTag)
                            newTag = ""
                        }) { Text("Add") }
                    }
                }
            }

            if (form.isExistingEvent) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("Linked contact")
                    Column(Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(16.dp)) {
                        LinkedContactSection(
                            linkedName = form.linkedContactName,
                            linkedUid = form.linkedContactUid,
                            onLink = { uid, name -> viewModel.linkContact(uid, name) },
                            onUnlink = { viewModel.unlinkContact() },
                        )
                    }
                }
            }

            // Feature #6 — advisory only, never blocks Save: Zaman has no concept of attendee
            // availability to actually enforce a conflict against, this is just "heads up, you
            // already have something else on" the way glancing at your own calendar would tell you.
            val conflicts by viewModel.conflicts.collectAsState()
            if (conflicts.isNotEmpty()) {
                Column(
                    Modifier.fillMaxWidth().zamanCard(isDark = isDark).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        "Conflicts with:",
                        style = ZamanType.labelMedium,
                        color = androidx.compose.ui.graphics.Color(0xFFEF9A00),
                    )
                    conflicts.forEach { entry ->
                        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
                        val zone = java.time.ZoneId.systemDefault()
                        val start = java.time.Instant.ofEpochMilli(entry.occurrenceStartMillis).atZone(zone)
                        val endLabel = entry.occurrenceEndMillis?.let {
                            " – " + fmt.format(java.time.Instant.ofEpochMilli(it).atZone(zone))
                        }.orEmpty()
                        // Flag a protected dream block distinctly here too — Save will stop with
                        // a hard confirmation dialog over this one specifically (see below).
                        val isDream = entry.event.isDreamBlock()
                        Text(
                            "${entry.event.summary} (${fmt.format(start)}$endLabel)" + if (isDream) " · protected" else "",
                            style = ZamanType.bodyMedium,
                            color = if (isDream) androidx.compose.ui.graphics.Color(0xFFEF9A00) else androidx.compose.ui.graphics.Color.Unspecified,
                        )
                    }
                }
            }

            if (saveResult is SaveResult.Error) {
                Text(
                    (saveResult as SaveResult.Error).message,
                    color = androidx.compose.ui.graphics.Color(0xFFEF5350),
                    style = ZamanType.bodyLarge,
                )
            }

            Button(
                onClick = { viewModel.save() },
                enabled = saveResult !is SaveResult.Saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saveResult is SaveResult.Saving) "Saving…" else "Save")
            }
        }
    }
    } // end ScreenBackground

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = form.start.toInstant().toEpochMilli(),
        )
        AlertDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val newDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of("UTC")).toLocalDate()
                        viewModel.update {
                            it.copy(
                                start = it.start.withYear(newDate.year).withMonth(newDate.monthValue).withDayOfMonth(newDate.dayOfMonth),
                                end = it.end.withYear(newDate.year).withMonth(newDate.monthValue).withDayOfMonth(newDate.dayOfMonth),
                            )
                        }
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
            text = { DatePicker(state = state) },
        )
    }

    if (showStartTimePicker) {
        TimePickerDialogHost(
            initial = form.start.toLocalTime(),
            onConfirm = { t ->
                viewModel.update { it.copy(start = it.start.withHour(t.hour).withMinute(t.minute)) }
                showStartTimePicker = false
            },
            onDismiss = { showStartTimePicker = false },
        )
    }
    if (showEndTimePicker) {
        TimePickerDialogHost(
            initial = form.end.toLocalTime(),
            onConfirm = { t ->
                viewModel.update { it.copy(end = it.end.withHour(t.hour).withMinute(t.minute)) }
                showEndTimePicker = false
            },
            onDismiss = { showEndTimePicker = false },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this event?") },
            text = { Text("This removes it from Nextcloud for everyone it's shared with.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; viewModel.delete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }

    // Feature #4: saving an edit to an event that repeats asks which occurrences it applies to,
    // instead of silently rewriting the whole series (or, before the ICalWriter fix alongside
    // this, silently dropping the series' RRULE entirely).
    if (pendingScopeChoice) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissScopeChoice() },
            title = { Text("This is a repeating event") },
            text = { Text("Which events do you want to change?") },
            confirmButton = {
                TextButton(onClick = { viewModel.chooseScope(com.brbrs.zaman.data.repo.EditScope.ALL_EVENTS) }) { Text("All events") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.chooseScope(com.brbrs.zaman.data.repo.EditScope.THIS_EVENT) }) { Text("This event") }
                    TextButton(onClick = { viewModel.chooseScope(com.brbrs.zaman.data.repo.EditScope.THIS_AND_FUTURE) }) { Text("This and following") }
                }
            },
        )
    }

    // Dream scheduling's "bodyguard": Zaman can't stop another app/person's invite from landing
    // on the server, but it can stop from inside its own app — a hard-blocking dialog, not just
    // the advisory "Conflicts with:" list above, the one time this save would book over a block
    // the user explicitly protected. Saving only proceeds if they confirm here.
    if (pendingDreamBlockWarning) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDreamBlockWarning() },
            title = { Text("This collides with a protected block") },
            text = { Text("You marked that time as protected — for something you wanted to make room for, not fill. Book over it anyway?") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmSaveDespiteDreamBlock() }) { Text("Book anyway") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDreamBlockWarning() }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TimePickerDialogHost(initial: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { TimePicker(state = state) },
    )
}

@Composable
private fun RepeatPicker(selected: RepeatOption, options: List<RepeatOption>, onSelect: (RepeatOption) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Repeat") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelect(option); expanded = false })
            }
        }
    }
}

/** One row per reminder already added (each removable), plus an "Add reminder" menu for picking
 *  another — up to [com.brbrs.zaman.reminders.MAX_REMINDERS_PER_EVENT] (e.g. "1 day before" AND
 *  "30 minutes before" on the same event). Replaces the old single-ReminderOption dropdown. */
@Composable
private fun ReminderPicker(selected: List<ReminderOption>, onAdd: (ReminderOption) -> Unit, onRemove: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text("Reminders", style = ZamanType.labelMedium, color = inkMuted())
        if (selected.isEmpty()) {
            Text("None", style = ZamanType.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        } else {
            selected.forEachIndexed { index, option ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(option.label, style = ZamanType.bodyMedium, modifier = Modifier.weight(1f))
                    androidx.compose.material3.IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove reminder",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
        if (selected.size < com.brbrs.zaman.reminders.MAX_REMINDERS_PER_EVENT) {
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 4.dp)) {
                TextButton(onClick = { expanded = true }) { Text("+ Add reminder") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    // NONE isn't offered here — an empty list already means "no reminder";
                    // already-added options stay out too, there's no point reminding twice at
                    // the same offset.
                    ReminderOption.entries.filter { it != ReminderOption.NONE && it !in selected }.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = { onAdd(option); expanded = false })
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkedContactSection(
    linkedName: String?,
    linkedUid: String?,
    onLink: (uid: String, name: String) -> Unit,
    onUnlink: () -> Unit,
    contactsViewModel: com.brbrs.zaman.ui.contacts.ContactsViewModel = hiltViewModel(),
) {
    var showPicker by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Text("Linked contact", style = ZamanType.titleMedium)
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        if (linkedName != null) {
            com.brbrs.zaman.ui.theme.SourceLogo("VINCI")
            Spacer(Modifier.width(6.dp))
        }
        Text(
            linkedName ?: "No contact linked",
            style = ZamanType.bodyMedium,
            color = if (linkedName != null) androidx.compose.ui.graphics.Color.Unspecified else inkMuted(),
            modifier = Modifier.weight(1f).let { base ->
                if (linkedName != null && linkedUid != null) {
                    base.clickable {
                        // Opens Vinci the same way Vinci/Tavla/Koto would open Zaman — a plain
                        // ACTION_VIEW on its deep-link scheme. A no-op (caught, not crashed) if
                        // Vinci isn't installed on this device.
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("vinci://contact/$linkedUid"),
                                )
                            )
                        }
                    }
                } else base
            },
        )
        if (linkedName != null) {
            TextButton(onClick = onUnlink) { Text("Unlink") }
        }
        TextButton(onClick = { showPicker = true }) { Text(if (linkedName != null) "Change" else "Link to Vinci…") }
    }

    if (showPicker) {
        val contacts by contactsViewModel.contacts.collectAsState()
        LaunchedEffect(Unit) { contactsViewModel.load() }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Link a Vinci contact") },
            text = {
                if (contacts.isEmpty()) {
                    Text("No contacts found — make sure you're connected to Nextcloud in Settings.", style = ZamanType.bodyMedium)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        contacts.forEach { contact ->
                            Text(
                                contact.displayName,
                                style = ZamanType.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLink(contact.uid, contact.displayName)
                                        showPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun CalendarPicker(
    calendars: List<com.brbrs.zaman.data.local.CalendarEntity>,
    selectedUrl: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = calendars.firstOrNull { it.url == selectedUrl }

    if (calendars.isEmpty()) {
        Text(
            "No writable calendar yet — connect to Nextcloud in Settings first.",
            style = ZamanType.bodyLarge,
            color = inkMuted(),
        )
        return
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.displayName ?: "Choose a calendar",
            onValueChange = {},
            readOnly = true,
            label = { Text("Calendar") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            calendars.forEach { cal ->
                DropdownMenuItem(
                    text = { Text(cal.displayName) },
                    onClick = { onSelect(cal.url); expanded = false },
                )
            }
        }
    }
}
