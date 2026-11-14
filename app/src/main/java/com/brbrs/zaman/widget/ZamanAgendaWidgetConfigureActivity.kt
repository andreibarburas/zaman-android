package com.brbrs.zaman.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.repo.CalendarRepository
import com.brbrs.zaman.ui.theme.ZamanTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Android's standard widget-configure screen: shown automatically right after the agenda widget
 * is placed (or reconfigured via long-press), letting that ONE widget instance pick which
 * calendars it shows — see WidgetCalendarSelection.kt's doc comment for how this differs from the
 * in-app left-hand drawer's global display selection (CalendarVisibilityDrawerContent).
 *
 * Not a Fragment/ViewModel screen like the rest of the app: a widget's configure Activity is
 * launched directly by the widget host with EXTRA_APPWIDGET_ID, outside any NavHost, so this
 * reads/writes everything itself via field injection (CalendarRepository is already a singleton
 * Hilt knows how to provide — see WidgetEntryPoint's doc comment for why GlanceAppWidget itself
 * can't use that same field-injection shortcut, unlike this ordinary Activity).
 */
@AndroidEntryPoint
class ZamanAgendaWidgetConfigureActivity : ComponentActivity() {

    @Inject
    lateinit var calendarRepository: CalendarRepository

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Standard widget-configure contract: if the user backs out (or the process dies)
        // without tapping Save, the host must treat this as cancelled so it removes the
        // half-placed widget instead of leaving a broken one on the home screen.
        setResult(Activity.RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            ZamanTheme {
                ConfigureScreen(
                    appWidgetId = appWidgetId,
                    calendarRepository = calendarRepository,
                    onSave = { selectedUrls -> saveAndFinish(selectedUrls) },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun saveAndFinish(selectedUrls: Set<String>) {
        val context = this
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
            setSelectedCalendarUrls(context, glanceId, selectedUrls)
            runCatching { ZamanAgendaWidget().update(context, glanceId) }
            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, resultValue)
            finish()
        }
    }
}

@Composable
private fun ConfigureScreen(
    appWidgetId: Int,
    calendarRepository: CalendarRepository,
    onSave: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var calendars by remember { mutableStateOf<List<CalendarEntity>?>(null) }
    var selected by remember { mutableStateOf<Set<String>?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(appWidgetId) {
        val all = runCatching { calendarRepository.observeCalendars().first() }.getOrDefault(emptyList())
        calendars = all
        val glanceId = runCatching { GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId) }.getOrNull()
        val stored = glanceId?.let { runCatching { getSelectedCalendarUrls(context, it) }.getOrNull() }
        // No stored selection yet (first-ever configure for this widget instance) defaults to
        // every calendar checked — matching the "show all" behavior an unconfigured widget has.
        selected = stored ?: all.map { it.url }.toSet()
    }

    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val currentCalendars = calendars
            val currentSelected = selected
            if (currentCalendars == null || currentSelected == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    Text(
                        "Show in this widget",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(20.dp),
                    )
                    Text(
                        "Pick which calendars this widget shows — other widgets and the app itself aren't affected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                    if (currentCalendars.isEmpty()) {
                        Text(
                            "No calendars yet — add one from Settings first.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(20.dp).weight(1f),
                        )
                    } else {
                        // Without weight(1f), LazyColumn took every bit of height Column offered
                        // it (an unweighted lazy list fills the whole remaining main-axis space by
                        // default) and the Save/Cancel row below it ended up laid out past the
                        // bottom of the screen — present in the tree, just never visible or
                        // reachable, which is why Save looked like it didn't exist at all.
                        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                            items(currentCalendars, key = { it.url }) { calendar ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Checkbox(
                                        checked = currentSelected.contains(calendar.url),
                                        onCheckedChange = { checked ->
                                            selected = if (checked) currentSelected + calendar.url else currentSelected - calendar.url
                                        },
                                    )
                                    Text(calendar.displayName, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        androidx.compose.material3.TextButton(onClick = onCancel) { Text("Cancel") }
                        Button(onClick = { onSave(currentSelected) }, modifier = Modifier.padding(start = 8.dp)) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
