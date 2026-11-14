package com.brbrs.zaman.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny wrapper so CalendarRepository doesn't need to know anything about Glance directly — just
 * calls this once at the end of every sync (see CalendarRepository.syncNow) so the home-screen
 * agenda widget reflects new/changed events right away instead of waiting for its own periodic
 * update window (updatePeriodMillis in zaman_agenda_widget_info.xml, which Android itself also
 * clamps to a 30-minute floor regardless of what's declared there).
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun refresh() {
        // Never let a widget-rendering problem take down a sync that otherwise succeeded.
        runCatching { ZamanAgendaWidget().updateAll(context) }
    }
}
