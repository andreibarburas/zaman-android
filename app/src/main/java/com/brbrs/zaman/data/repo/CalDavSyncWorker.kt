package com.brbrs.zaman.data.repo

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brbrs.zaman.data.prefs.CalendarContractPreferences
import com.brbrs.zaman.sync.contract.CalendarContractRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

private const val UNIQUE_WORK_NAME = "zaman-caldav-sync"
private const val PERIODIC_WORK_NAME = "zaman-caldav-sync-periodic"

@HiltWorker
class CalDavSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: CalendarRepository,
    private val contractRepository: CalendarContractRepository,
    private val contractPreferences: CalendarContractPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        repository.syncNow()
        // Feature #2 — remirror Room -> CalendarContract right after a fresh CalDAV pull, so the
        // system Calendar app reflects server-side changes without waiting for its own next sync
        // pass. Gated by the Settings toggle; a no-op cost-wise when the user hasn't turned it on
        // (CalendarContractRepository.sync() already short-circuits if the stub account doesn't
        // exist, but checking the preference first avoids even that check on every worker run).
        if (contractPreferences.enabled.first()) contractRepository.sync()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        /**
         * enqueueUniqueWork (not plain enqueue) so overlapping manual-refresh +
         * periodic triggers never race two writers against the same Nextcloud
         * calendar collection — that pattern caused 423 Locked errors elsewhere
         * in the suite.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<CalDavSyncWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Keeps the agenda fresh without the user ever opening Settings → Sync now.
         *
         * [intervalMinutes] is the user's own choice from Settings → Sync (SyncPreferences),
         * clamped to WorkManager's own 15-minute PeriodicWorkRequest floor — there's no going
         * tighter than that regardless of what's stored. UPDATE (not KEEP) so that calling this
         * again after the user picks a different interval actually reschedules the existing
         * periodic work instead of silently no-op'ing against whatever interval was first
         * enqueued; UPDATE still leaves an identical request alone (no wasted reschedule) when
         * nothing changed, e.g. the unconditional call every app launch in MainActivity.
         */
        fun enqueuePeriodic(context: Context, intervalMinutes: Long = com.brbrs.zaman.data.prefs.SyncInterval.DEFAULT.minutes) {
            val clamped = intervalMinutes.coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<CalDavSyncWorker>(clamped, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        // Skip the periodic sync entirely once the OS considers the battery low —
                        // a calendar refresh isn't worth contributing to a low-battery situation,
                        // and WorkManager will just run it on the next window once it isn't.
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
        }
    }
}
