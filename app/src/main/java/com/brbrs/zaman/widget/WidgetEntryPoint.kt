package com.brbrs.zaman.widget

import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Glance's GlanceAppWidget isn't an Android framework component Hilt knows how to inject into
 * directly (unlike Activity/Service/BroadcastReceiver), so ZamanAgendaWidget reaches
 * CalendarRepository through this EntryPoint instead of constructor injection — the standard
 * pattern for using Hilt-provided singletons from code Hilt itself can't instantiate.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun calendarRepository(): CalendarRepository
}
