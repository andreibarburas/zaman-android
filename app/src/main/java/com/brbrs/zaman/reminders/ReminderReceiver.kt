package com.brbrs.zaman.reminders

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Fired by the AlarmManager alarm ReminderScheduler armed — shows the actual notification. Also
 *  handles the notification's "Snooze" action (ACTION_SNOOZE), re-firing itself a fixed number of
 *  minutes later rather than going through ReminderScheduler's per-event alarm slots (snooze is
 *  a one-off, independent of whatever reminders the event itself has configured). */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val href = intent.getStringExtra(EXTRA_EVENT_HREF) ?: return
        val summary = intent.getStringExtra(EXTRA_SUMMARY) ?: "Event"
        val startEpochMillis = intent.getLongExtra(EXTRA_START_EPOCH_MILLIS, 0L)
        val allDay = intent.getBooleanExtra(EXTRA_ALL_DAY, false)

        if (intent.action == ACTION_SNOOZE) {
            context.getSystemService(NotificationManager::class.java)?.cancel(href.hashCode())
            scheduleSnooze(context, href, summary, startEpochMillis, allDay)
            return
        }

        val whenLabel = if (allDay) "Today" else DateTimeFormatter.ofPattern("HH:mm")
            .format(Instant.ofEpochMilli(startEpochMillis).atZone(ZoneId.systemDefault()))

        NotificationHelper.showEventReminder(
            context = context,
            notificationId = href.hashCode(),
            title = summary,
            whenLabel = whenLabel,
            eventHref = href,
            startEpochMillis = startEpochMillis,
            allDay = allDay,
        )
    }

    companion object {
        const val EXTRA_EVENT_HREF = "event_href"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_START_EPOCH_MILLIS = "start_epoch_millis"
        const val EXTRA_ALL_DAY = "all_day"
        const val ACTION_SNOOZE = "com.brbrs.zaman.SNOOZE_REMINDER"
        const val SNOOZE_MINUTES = 10L

        /** Separate request-code namespace from ReminderScheduler's own per-event slots (see
         *  that class's requestCodeFor), so a snooze alarm never collides with or gets silently
         *  overwritten by whichever slot alarm the event's own reminders happen to be using. */
        private fun snoozeRequestCode(href: String): Int = href.hashCode() * 31 + 997

        fun scheduleSnooze(context: Context, href: String, summary: String, startEpochMillis: Long, allDay: Boolean) {
            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra(EXTRA_EVENT_HREF, href)
                putExtra(EXTRA_SUMMARY, summary)
                putExtra(EXTRA_START_EPOCH_MILLIS, startEpochMillis)
                putExtra(EXTRA_ALL_DAY, allDay)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, snoozeRequestCode(href), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val triggerAt = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000L
            runCatching {
                context.getSystemService(AlarmManager::class.java)
                    ?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }
}
