package com.brbrs.zaman.sync.contract

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Open in Zaman" from another app's own calendar UI (the stock Calendar app, Gmail's event
 * chip, a car head unit, ...): that app fires `ACTION_VIEW` against
 * `CalendarContract.Events.CONTENT_URI/<id>` for whatever event the user tapped there — see
 * MainActivity's manifest intent-filter and ZamanNavHost's own doc comment on where this is
 * called from. This maps that numeric platform row id back to Zaman's own event href (the same
 * `_SYNC_ID` [CalendarContractRepository] wrote when it mirrored the event into
 * CalendarContract), but ONLY for a calendar that actually belongs to Zaman's own sync-adapter
 * account ([ZamanAccount.ACCOUNT_TYPE]) — otherwise the id belongs to some other app's calendar
 * that happens to live in the same shared system provider, and there's nothing for Zaman to
 * open.
 */
object CalendarContractEventResolver {

    suspend fun resolveHref(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val eventId = uri.lastPathSegment?.toLongOrNull() ?: return@withContext null
        val resolver = context.contentResolver

        var syncId: String? = null
        var calendarId: Long? = null
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(CalendarContract.Events._SYNC_ID, CalendarContract.Events.CALENDAR_ID),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                syncId = cursor.getString(0)
                calendarId = cursor.getLong(1)
            }
        }
        val resolvedSyncId = syncId
        val resolvedCalendarId = calendarId
        if (resolvedSyncId == null || resolvedCalendarId == null) return@withContext null

        var isZamanCalendar = false
        resolver.query(
            ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, resolvedCalendarId),
            arrayOf(CalendarContract.Calendars.ACCOUNT_TYPE),
            null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                isZamanCalendar = cursor.getString(0) == ZamanAccount.ACCOUNT_TYPE
            }
        }

        if (isZamanCalendar) resolvedSyncId else null
    }
}
