package com.brbrs.zaman.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.brbrs.zaman.data.local.EventEntity
import com.brbrs.zaman.data.local.allReminderMinutes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** UI/storage cap on reminders-per-event — see EventEditScreen's reminder list and
 *  EventEntity.extraReminderMinutes. Also bounds how many alarm "slots" this class ever has to
 *  manage per event (see the slot-index scheme below). */
const val MAX_REMINDERS_PER_EVENT = 5

/**
 * Arms/disarms the actual OS alarms for one event's reminder(s). Deliberately uses
 * AlarmManager.setAndAllowWhileIdle (inexact, can drift a few minutes under Doze)
 * rather than setExactAndAllowWhileIdle, which needs the user to separately grant
 * SCHEDULE_EXACT_ALARM/USE_EXACT_ALARM on Android 12+ — a reminder landing within a
 * few minutes of the real time is an acceptable v1 trade-off against adding that
 * extra permission flow.
 *
 * Multiple reminders per event means multiple distinct PendingIntents, one per "slot" (0 until
 * MAX_REMINDERS_PER_EVENT). scheduleFor always re-evaluates every slot for the event — arming the
 * ones a reminder now occupies and cancelling the rest — rather than tracking what was previously
 * armed, so a reminder list that shrinks (or grows, or reorders) between calls can't leave a
 * stale alarm behind.
 */
@Singleton
class ReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun scheduleFor(event: EventEntity) {
        val minutesList = event.allReminderMinutes().take(MAX_REMINDERS_PER_EVENT)
        for (slot in 0 until MAX_REMINDERS_PER_EVENT) {
            val minutesBefore = minutesList.getOrNull(slot)
            val triggerAt = minutesBefore?.let { event.startEpochMillis - it * 60_000L }
            if (minutesBefore == null || triggerAt == null || triggerAt <= System.currentTimeMillis()) {
                cancelSlot(event.href, slot)
                continue
            }
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(ReminderReceiver.EXTRA_EVENT_HREF, event.href)
                putExtra(ReminderReceiver.EXTRA_SUMMARY, event.summary)
                putExtra(ReminderReceiver.EXTRA_START_EPOCH_MILLIS, event.startEpochMillis)
                putExtra(ReminderReceiver.EXTRA_ALL_DAY, event.allDay)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, requestCodeFor(event.href, slot), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    fun cancelFor(eventHref: String) {
        for (slot in 0 until MAX_REMINDERS_PER_EVENT) cancelSlot(eventHref, slot)
    }

    private fun cancelSlot(eventHref: String, slot: Int) {
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, requestCodeFor(eventHref, slot), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager?.cancel(pendingIntent)
    }

    /** A stable int from the href's hash plus the slot index — PendingIntent request codes must
     *  be ints, hrefs are URLs, and each event now needs up to MAX_REMINDERS_PER_EVENT distinct
     *  codes instead of just one. */
    private fun requestCodeFor(href: String, slot: Int): Int = href.hashCode() * 31 + slot
}
