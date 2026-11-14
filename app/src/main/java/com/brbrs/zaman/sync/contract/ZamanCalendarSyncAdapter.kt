package com.brbrs.zaman.sync.contract

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/**
 * Feature #2 — the actual OS-facing sync adapter. Bound whenever Android decides a sync for
 * Zaman's stub account is due: a DIRTY row under that account (the user edited something in the
 * system Calendar app), a pull-to-refresh in the system Calendar app, or the periodic schedule
 * ZamanAccount.get() turned on via ContentResolver.setSyncAutomatically.
 *
 * All the real work is CalendarContractRepository.sync() — push dirty rows out over Zaman's own
 * CalDAV path, then remirror Room -> CalendarContract. AbstractThreadedSyncAdapter's callback is
 * synchronous, so the suspend call is bridged with runBlocking; onPerformSync already runs on a
 * dedicated sync thread the OS provides, so blocking it here doesn't block anything else.
 */
class ZamanCalendarSyncAdapter(
    context: Context,
    autoInitialize: Boolean,
    private val contractRepository: CalendarContractRepository,
) : AbstractThreadedSyncAdapter(context, autoInitialize) {

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient,
        syncResult: SyncResult,
    ) {
        runCatching {
            runBlocking { contractRepository.sync() }
        }.onFailure {
            syncResult.stats.numIoExceptions++
        }
    }
}

/**
 * Bound by the OS to obtain the sync adapter — see res/xml/syncadapter.xml and the manifest's
 * <service> entry for it. Only one adapter instance is ever created per process (the
 * synchronized lazy-init below is the standard pattern every CalDAV sync-adapter service uses),
 * since AbstractThreadedSyncAdapter already serializes syncs for a given account/authority pair
 * internally.
 */
@AndroidEntryPoint
class SyncAdapterService : Service() {

    @Inject lateinit var contractRepository: CalendarContractRepository

    override fun onBind(intent: Intent?): IBinder {
        synchronized(lock) {
            val existing = adapterInstance
            if (existing != null) return existing.syncAdapterBinder
            val created = ZamanCalendarSyncAdapter(applicationContext, true, contractRepository)
            adapterInstance = created
            return created.syncAdapterBinder
        }
    }

    companion object {
        private val lock = Any()
        @Volatile private var adapterInstance: ZamanCalendarSyncAdapter? = null
    }
}
