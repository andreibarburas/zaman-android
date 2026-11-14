package com.brbrs.zaman.ui.calendars

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.prefs.SyncStatus
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CalendarsViewModel @Inject constructor(
    private val repository: CalendarRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    val calendars: StateFlow<List<CalendarEntity>> =
        repository.observeCalendars()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** calendarUrl -> cached event count, combined into the row UI as "N events". */
    val eventCounts: StateFlow<Map<String, Int>> =
        repository.observeEventCountsByCalendar()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val syncStatus: StateFlow<SyncStatus> =
        repository.syncStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatus())

    fun setEnabled(url: String, enabled: Boolean) {
        viewModelScope.launch { repository.setCalendarEnabled(url, enabled) }
    }

    /** Was just discoverCalendars() — now the full syncNow() (discover + sync + status record),
     *  so pulling to refresh here also fixes a "0 calendars found" state instead of only ever
     *  re-running discovery with no sync and no feedback either way. */
    fun refreshFromNextcloud() {
        viewModelScope.launch { repository.syncNow() }
    }

    /**
     * Copies the user's picked gallery photo into this app's private storage and records that
     * path on the calendar — never the content:// Uri itself, which is only guaranteed valid for
     * the lifetime of the permission grant the picker handed over (often a one-shot read), not
     * across app restarts. Pass null to clear a calendar's cover instead of setting one.
     */
    fun setCoverImage(calendarUrl: String, pickedUri: Uri?) {
        viewModelScope.launch {
            android.util.Log.d("ZamanCover", "setCoverImage($calendarUrl, $pickedUri) called")
            val previousPath = calendars.value.firstOrNull { it.url == calendarUrl }?.coverImagePath
            val newPath = withContext(Dispatchers.IO) {
                // Clean up the old file whether we're replacing it or just clearing the cover —
                // otherwise every re-pick (people retry a crop/photo a few times) leaks a few
                // hundred KB of private storage per calendar forever.
                previousPath?.let { runCatching { File(it).delete() } }
                if (pickedUri == null) return@withContext null
                runCatching {
                    val dir = File(appContext.filesDir, "calendar_covers").apply { mkdirs() }
                    // UUID derived from the calendar's own URL — deterministic (same calendar
                    // always maps to the same filename, so re-picking just overwrites) and
                    // collision-free (unlike a raw hashCode()), without having to persist a
                    // separate id anywhere.
                    val file = File(dir, "${java.util.UUID.nameUUIDFromBytes(calendarUrl.toByteArray())}.img")
                    val stream = appContext.contentResolver.openInputStream(pickedUri)
                    if (stream == null) {
                        android.util.Log.w("ZamanCover", "openInputStream($pickedUri) returned null — no permission grant or dead Uri")
                    }
                    stream?.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
                    android.util.Log.d(
                        "ZamanCover",
                        "wrote cover to ${file.absolutePath}: exists=${file.exists()} size=${file.length()} bytes",
                    )
                    file.takeIf { it.exists() && it.length() > 0 }?.absolutePath
                }.onFailure { e ->
                    android.util.Log.e("ZamanCover", "failed to copy cover image for $calendarUrl from $pickedUri", e)
                }.getOrNull()
            }
            android.util.Log.d("ZamanCover", "applying coverImagePath for $calendarUrl: $newPath")
            repository.setCalendarCover(calendarUrl, newPath)
        }
    }
}
