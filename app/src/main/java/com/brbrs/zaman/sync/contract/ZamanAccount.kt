package com.brbrs.zaman.sync.contract

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.provider.CalendarContract

/**
 * Feature #2 — the account CalendarContract rows attach to. There's nothing to actually log in
 * to here: Zaman's real Nextcloud credentials already live in LoginFlowV2Auth/DataStore, and this
 * account exists purely because CalendarContract.Calendars rows are required to belong to SOME
 * Android account. Every CalDAV sync adapter does this, including DAVx5 itself — a stub account
 * is the standard trick, not a Zaman-specific shortcut.
 */
object ZamanAccount {
    const val ACCOUNT_TYPE = "com.brbrs.zaman"
    const val ACCOUNT_NAME = "Zaman"

    /** Returns the existing account, or creates it (and marks it syncable for the calendar
     *  provider) if this is the first time CalendarContract sync has been turned on. */
    fun get(context: Context): Account {
        val manager = AccountManager.get(context)
        val existing = manager.getAccountsByType(ACCOUNT_TYPE).firstOrNull()
        if (existing != null) return existing
        val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
        manager.addAccountExplicitly(account, null, null)
        ContentResolver.setIsSyncable(account, CalendarContract.AUTHORITY, 1)
        ContentResolver.setSyncAutomatically(account, CalendarContract.AUTHORITY, true)
        return account
    }

    /** Called when the user turns CalendarContract sync off in Settings — removing the account
     *  cascades and removes every Calendars/Events/Reminders row that belonged to it too, so
     *  nothing orphaned lingers in the system Calendar app. */
    fun remove(context: Context) {
        val manager = AccountManager.get(context)
        manager.getAccountsByType(ACCOUNT_TYPE).forEach { account ->
            @Suppress("DEPRECATION")
            manager.removeAccountExplicitly(account)
        }
    }

    fun existsOrNull(context: Context): Account? =
        AccountManager.get(context).getAccountsByType(ACCOUNT_TYPE).firstOrNull()
}
