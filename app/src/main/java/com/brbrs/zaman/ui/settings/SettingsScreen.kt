package com.brbrs.zaman.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.brbrs.zaman.BuildConfig
import com.brbrs.zaman.data.prefs.SyncInterval
import com.brbrs.zaman.data.prefs.TextScale
import com.brbrs.zaman.data.prefs.ThemeMode
import com.brbrs.zaman.ui.lock.AppLockViewModel
import com.brbrs.zaman.ui.theme.GlassCard
import com.brbrs.zaman.ui.theme.SectionLabel
import com.brbrs.zaman.ui.theme.ZamanColors
import com.brbrs.zaman.ui.theme.ZamanType
import com.brbrs.zaman.ui.theme.glassBorder
import com.brbrs.zaman.ui.theme.glassFill
import com.brbrs.zaman.ui.theme.inkMuted

@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
    lockViewModel: AppLockViewModel = hiltViewModel(),
    displayViewModel: DisplayViewModel = hiltViewModel(),
    dataViewModel: DataViewModel = hiltViewModel(),
    syncSettingsViewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val loginName by viewModel.loginName.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val syncInterval by syncSettingsViewModel.interval.collectAsState()
    val calendarContractEnabled by syncSettingsViewModel.calendarContractEnabled.collectAsState()
    // Feature #2 — the permission request has to happen here (Compose), not in the ViewModel;
    // everything after it's granted (stub account, preference, first mirror pass) is
    // SyncSettingsViewModel.setCalendarContractEnabled. A denial just leaves the toggle off.
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) syncSettingsViewModel.setCalendarContractEnabled(true)
    }
    val lockEnabled by lockViewModel.lockEnabled.collectAsState()
    val themeMode by displayViewModel.themeMode.collectAsState()
    val textScale by displayViewModel.textScale.collectAsState()
    val customFontEnabled by displayViewModel.customFontEnabled.collectAsState()
    val localOnlyEnabled by dataViewModel.localOnlyEnabled.collectAsState()
    val opState by dataViewModel.opState.collectAsState()

    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    // -- Export/Import plumbing: SAF document pickers + the actual file I/O ----------------
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = (opState as? DataOpState.ExportReady)?.json
        if (uri != null && json != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }
            android.widget.Toast.makeText(context, "Exported to the file you picked", android.widget.Toast.LENGTH_SHORT).show()
        }
        dataViewModel.clearOpState()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) dataViewModel.importJson(text)
            else android.widget.Toast.makeText(context, "Couldn't read that file", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(opState) {
        when (val state = opState) {
            is DataOpState.ExportReady -> exportLauncher.launch("zaman-backup-${System.currentTimeMillis()}.json")
            is DataOpState.ImportDone -> {
                android.widget.Toast.makeText(
                    context,
                    "Imported ${state.result.calendarCount} calendars, ${state.result.eventCount} events",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                dataViewModel.clearOpState()
            }
            is DataOpState.Failed -> {
                android.widget.Toast.makeText(context, state.message, android.widget.Toast.LENGTH_LONG).show()
                dataViewModel.clearOpState()
            }
            else -> Unit
        }
    }

    com.brbrs.zaman.ui.theme.ScreenBackground {
    Scaffold(
        containerColor = Color.Transparent,
        // Same fix as Today/Month: this screen applies its own statusBarsPadding on the header
        // Row below instead of relying on Scaffold's own inset handling, so that inset isn't
        // accounted for twice.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { _ ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
        ) {
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.statusBarsPadding(),
            ) {
                androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Settings", style = ZamanType.headlineSmall)
            }

            // -- Account
            Spacer(Modifier.height(24.dp))
            SectionLabel("Account")
            Spacer(Modifier.height(8.dp))

            when (val state = uiState) {
                is LoginUiState.SignedOut -> ConnectPrompt(onConnect = { viewModel.startEnteringServer() })

                is LoginUiState.EnteringServer -> ServerEntryForm(
                    onSubmit = { server ->
                        viewModel.connect(server) { browserUrl ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(browserUrl)))
                        }
                    },
                    onCancel = { viewModel.cancelPolling() },
                )

                is LoginUiState.AwaitingApproval -> AwaitingApprovalState(
                    onReopenBrowser = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.browserUrl))) },
                    onCancel = { viewModel.cancelPolling() },
                )

                is LoginUiState.SignedIn -> SignedInState(
                    server = serverUrl,
                    loginName = loginName,
                    status = syncStatus,
                    onSignOut = { viewModel.signOut() },
                    onSyncNow = { viewModel.syncNow() },
                )

                is LoginUiState.Error -> ErrorState(
                    message = state.message,
                    onRetry = { viewModel.startEnteringServer() },
                )
            }

            // -- Calendars — moved here from its own bottom-bar tab: it's account-scoped
            // configuration (which of this account's calendars sync), not a daily-use screen,
            // so it reads better as a collapsible section of Settings than a 3rd/4th tab.
            Spacer(Modifier.height(24.dp))
            com.brbrs.zaman.ui.calendars.CalendarsSection()

            // -- Sync — how often the background worker wakes up to refresh from Nextcloud.
            // Added after a battery-drain report traced to CalDavSyncWorker's fixed 15-minute
            // floor; letting the user back it off trades agenda freshness for fewer wakeups.
            Spacer(Modifier.height(24.dp))
            SectionLabel("Sync")
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SyncIntervalRow(selected = syncInterval, onSelect = { syncSettingsViewModel.setInterval(it) })
            }

            // -- Feature #2: mirror Zaman's calendars into Android's own CalendarContract, so
            // the system Calendar app, widgets and other apps see them as real device calendars,
            // and edits made there push back out to Nextcloud over Zaman's own CalDAV path.
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    icon = Icons.Outlined.Sync,
                    title = "Sync with Android Calendar",
                    subtitle = "Show Zaman's calendars in the system Calendar app, and push edits made there back to Nextcloud",
                    checked = calendarContractEnabled,
                    onCheckedChange = { enable ->
                        if (enable) {
                            calendarPermissionLauncher.launch(
                                arrayOf(
                                    android.Manifest.permission.READ_CALENDAR,
                                    android.Manifest.permission.WRITE_CALENDAR,
                                ),
                            )
                        } else {
                            syncSettingsViewModel.setCalendarContractEnabled(false)
                        }
                    },
                )
            }

            // -- Security
            Spacer(Modifier.height(24.dp))
            SectionLabel("Security")
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    icon = Icons.Outlined.Fingerprint,
                    title = "App lock",
                    subtitle = "Require biometrics or your screen lock when opening Zaman",
                    checked = lockEnabled,
                    onCheckedChange = { lockViewModel.setLockEnabled(it) },
                )
            }

            // -- Display
            Spacer(Modifier.height(24.dp))
            SectionLabel("Display")
            Spacer(Modifier.height(8.dp))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                ChipGroupRow(
                    icon = Icons.Outlined.DarkMode,
                    title = "Theme",
                    options = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                        ThemeMode.OLED to "OLED",
                    ),
                    selected = themeMode,
                    onSelect = { displayViewModel.setThemeMode(it) },
                )
            }

            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                ChipGroupRow(
                    icon = Icons.Outlined.TextFields,
                    title = "Text size",
                    options = TextScale.entries.map { it to it.label },
                    selected = textScale,
                    onSelect = { displayViewModel.setTextScale(it) },
                )
            }

            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    icon = Icons.Outlined.FontDownload,
                    title = "DM Serif + Inter Tight",
                    subtitle = "Use the custom font pairing instead of system fonts",
                    checked = customFontEnabled,
                    onCheckedChange = { displayViewModel.setCustomFontEnabled(it) },
                )
            }

            // -- Travel (starting address for the travel-time estimate shown on events with a
            // location set — see TravelTimeEstimator's doc comment for why this is a typed
            // address rather than the device's actual location) ----
            Spacer(Modifier.height(24.dp))
            SectionLabel("Travel")
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val startingAddress by displayViewModel.startingAddress.collectAsState()
                var addressText by remember(startingAddress) { mutableStateOf(startingAddress) }
                Column(Modifier.padding(16.dp)) {
                    Text("Starting address", style = ZamanType.titleMedium)
                    Text(
                        "Used to estimate travel time to an event's location (straight-line, not a real route)",
                        style = ZamanType.bodySmall,
                        color = inkMuted(),
                        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                    )
                    OutlinedTextField(
                        value = addressText,
                        onValueChange = { addressText = it },
                        label = { Text("e.g. your home or office address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { displayViewModel.setStartingAddress(addressText) },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Save") }
                }
            }

            // -- Data (local-only mode + export/import — suite-wide v2 milestone) ----
            Spacer(Modifier.height(24.dp))
            SectionLabel("Data")
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    icon = Icons.Outlined.CloudOff,
                    title = "Local-only mode",
                    subtitle = "Never talk to Nextcloud — events stay on this device until you export them",
                    checked = localOnlyEnabled,
                    onCheckedChange = { dataViewModel.setLocalOnlyEnabled(it) },
                )
            }
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    LinkRow(
                        icon = Icons.Outlined.Download,
                        title = "Export data",
                        subtitle = "Save every calendar and event as a JSON file you control",
                    ) { dataViewModel.prepareExport() }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    LinkRow(
                        icon = Icons.Outlined.Upload,
                        title = "Import data",
                        subtitle = "Restore from a Zaman export — overwrites matching calendars/events",
                    ) { importLauncher.launch(arrayOf("application/json")) }
                }
            }

            // -- Support
            Spacer(Modifier.height(24.dp))
            SectionLabel("Support")
            Spacer(Modifier.height(8.dp))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    LinkRow(
                        icon = Icons.Outlined.Favorite,
                        title = "Buy me a coffee",
                        subtitle = "If Zaman keeps your plans on track, a small tip means a lot",
                    ) { open("https://bunq.me/barburasdonations?description=Donation%20from%20Zaman") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    LinkRow(
                        icon = Icons.Outlined.Language,
                        title = "More by andrei BARBURAS",
                        subtitle = "See what else I'm building at barburas.com",
                    ) { open("https://barburas.com") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    LinkRow(
                        icon = Icons.Outlined.PrivacyTip,
                        title = "Privacy Policy",
                        subtitle = "How Zaman handles your data",
                    ) { open("https://barburas.com/privacy-policy/") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    LinkRow(
                        icon = Icons.Outlined.Code,
                        title = "View on GitHub",
                        subtitle = "Feedback, feature requests and source code",
                    ) { open("https://github.com/andreibarburas/android-apps") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    LinkRow(
                        icon = Icons.Outlined.Forum,
                        title = "Join r/BarburasLab",
                        subtitle = "Discuss Zaman and the rest of the suite on Reddit",
                    ) { open("https://www.reddit.com/r/BarburasLab/") }
                }
            }

            Spacer(Modifier.height(32.dp))
            Text(
                text = "Zaman ${BuildConfig.VERSION_NAME} · by andrei BARBURAS",
                style = ZamanType.labelSmall,
                color = inkMuted(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
    } // end ScreenBackground
}

// -- Reusable composables matching Tavla's Settings pattern --------------------

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = ZamanType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = ZamanType.labelSmall, color = inkMuted())
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )
    }
}

@Composable
private fun <T> ChipGroupRow(
    icon: ImageVector,
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = ZamanType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                Surface(
                    modifier = Modifier.weight(1f).clickable { onSelect(value) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else glassFill(),
                    border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else glassBorder()),
                ) {
                    Text(
                        label,
                        style = ZamanType.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else inkMuted(),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Dropdown row for [SyncInterval] — a 7-option set, too many for [ChipGroupRow]'s one-line
 *  equal-weight layout, so this follows the same anchored-dropdown pattern as Event Edit's own
 *  Repeat/Reminder pickers instead. */
@Composable
private fun SyncIntervalRow(selected: SyncInterval, onSelect: (SyncInterval) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clickable { expanded = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Background sync", style = ZamanType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text("How often Zaman checks Nextcloud for changes", style = ZamanType.labelSmall, color = inkMuted())
            }
        }
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selected.label, style = ZamanType.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                SyncInterval.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = ZamanType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = ZamanType.labelSmall, color = inkMuted())
        }
    }
}

// -- Login state composables --------------------------------------------------

@Composable
private fun ConnectPrompt(onConnect: () -> Unit) {
    Button(modifier = Modifier.fillMaxWidth(), onClick = onConnect) {
        Text("Connect to Nextcloud")
    }
}

@Composable
private fun ServerEntryForm(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var server by remember { mutableStateOf("") }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Nextcloud server address", style = ZamanType.titleMedium)
            Spacer(Modifier.padding(top = 8.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it },
                placeholder = { Text("cloud.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
                Spacer(Modifier.padding(start = 8.dp))
                Button(
                    enabled = server.isNotBlank(),
                    onClick = { onSubmit(server) },
                ) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun AwaitingApprovalState(onReopenBrowser: () -> Unit, onCancel: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp)
                Text("Waiting for approval in your browser…", style = ZamanType.titleMedium)
            }
            Text(
                "Approve Zaman in the Nextcloud tab that just opened, then come back here.",
                style = ZamanType.bodySmall,
                color = inkMuted(),
                modifier = Modifier.padding(top = 6.dp),
            )
            Row(Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = onReopenBrowser) { Text("Reopen browser") }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun SignedInState(
    server: String?,
    loginName: String?,
    status: com.brbrs.zaman.data.prefs.SyncStatus,
    onSignOut: () -> Unit,
    onSyncNow: () -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(loginName ?: "Connected", style = ZamanType.titleMedium)
            server?.let { Text(it, style = ZamanType.bodySmall, color = inkMuted()) }

            SyncStatusLine(status, modifier = Modifier.padding(top = 10.dp))

            Row(Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = onSyncNow, enabled = status.outcome != com.brbrs.zaman.data.prefs.SyncOutcome.RUNNING) {
                    if (status.outcome == com.brbrs.zaman.data.prefs.SyncOutcome.RUNNING) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.padding(start = 6.dp))
                    Text(if (status.outcome == com.brbrs.zaman.data.prefs.SyncOutcome.RUNNING) "Syncing…" else "Sync now")
                }
                Spacer(Modifier.padding(start = 8.dp))
                OutlinedButton(onClick = onSignOut) {
                    Text("Disconnect")
                }
            }
        }
    }
}

/** Shared by Settings' Account card and Calendars' header — the one place that turns
 *  [com.brbrs.zaman.data.prefs.SyncStatus] into a sentence a non-technical user can act on. */
@Composable
fun SyncStatusLine(status: com.brbrs.zaman.data.prefs.SyncStatus, modifier: Modifier = Modifier) {
    val (dotColor, text) = when (status.outcome) {
        com.brbrs.zaman.data.prefs.SyncOutcome.NEVER_RUN -> inkMuted() to "Not synced yet"
        com.brbrs.zaman.data.prefs.SyncOutcome.RUNNING -> ZamanColors.AccentDark to "Syncing…"
        com.brbrs.zaman.data.prefs.SyncOutcome.SUCCESS -> androidx.compose.ui.graphics.Color(0xFF2E9E5B) to
            ("Synced ${relativeTime(status.lastSuccessEpochMillis)} — " +
                "${status.calendarsFound} calendar${if (status.calendarsFound == 1) "" else "s"}, " +
                "${status.eventsCached} event${if (status.eventsCached == 1) "" else "s"} cached")
        com.brbrs.zaman.data.prefs.SyncOutcome.PARTIAL_FAILURE -> ZamanColors.AccentSecondaryDark to "Synced with errors: ${status.message.orEmpty()}"
        com.brbrs.zaman.data.prefs.SyncOutcome.FAILURE -> ZamanColors.AccentSecondaryDark to (status.message ?: "Sync failed")
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = dotColor,
            shape = androidx.compose.foundation.shape.CircleShape,
            modifier = Modifier.size(7.dp),
        ) {}
        Text(text, style = ZamanType.bodySmall, color = inkMuted(), modifier = Modifier.padding(start = 8.dp))
    }
}

private fun relativeTime(epochMillis: Long?): String {
    if (epochMillis == null) return "just now"
    val diffSeconds = (System.currentTimeMillis() - epochMillis) / 1000
    return when {
        diffSeconds < 60 -> "just now"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
        else -> "${diffSeconds / 86400}d ago"
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(message, style = ZamanType.bodyLarge)
            Button(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text("Try again")
            }
        }
    }
}
