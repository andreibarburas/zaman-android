package com.brbrs.zaman.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.brbrs.zaman.MainActivity
import com.brbrs.zaman.R

private const val CHANNEL_ID = "zaman_event_reminders"

object NotificationHelper {

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Event reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders for Nextcloud calendar events with a VALARM set"
            }
        )
    }

    fun showEventReminder(
        context: Context, notificationId: Int, title: String, whenLabel: String, eventHref: String,
        startEpochMillis: Long = 0L, allDay: Boolean = false,
    ) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.parse("zaman://event/${android.net.Uri.encode(eventHref)}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_SNOOZE
            putExtra(ReminderReceiver.EXTRA_EVENT_HREF, eventHref)
            putExtra(ReminderReceiver.EXTRA_SUMMARY, title)
            putExtra(ReminderReceiver.EXTRA_START_EPOCH_MILLIS, startEpochMillis)
            putExtra(ReminderReceiver.EXTRA_ALL_DAY, allDay)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context, notificationId, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(whenLabel)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(0, "Snooze ${ReminderReceiver.SNOOZE_MINUTES} min", snoozePendingIntent)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // POST_NOTIFICATIONS is requested at runtime from MainActivity on API 33+; if the user
        // declined, this simply won't post — there's no further permission prompt mid-alarm.
        runCatching { manager.notify(notificationId, notification) }
    }
}
