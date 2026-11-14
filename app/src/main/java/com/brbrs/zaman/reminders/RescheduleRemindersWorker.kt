package com.brbrs.zaman.reminders

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brbrs.zaman.data.local.EventDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * AlarmManager alarms set with setAndAllowWhileIdle don't survive a reboot — BootReceiver
 * enqueues this to re-arm every future event that still has a reminder set.
 */
@HiltWorker
class RescheduleRemindersWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val eventDao: EventDao,
    private val reminders: ReminderScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        eventDao.futureWithReminders(System.currentTimeMillis()).forEach { reminders.scheduleFor(it) }
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<RescheduleRemindersWorker>().build())
        }
    }
}
