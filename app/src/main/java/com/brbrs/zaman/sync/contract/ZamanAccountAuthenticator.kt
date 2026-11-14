package com.brbrs.zaman.sync.contract

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * Feature #2 — a near-empty AccountAuthenticator. Android requires one to exist for any account
 * type that wants to own CalendarContract rows, but Zaman has no separate login flow for this
 * account (see ZamanAccount's doc comment) — every method here either hands back the one stub
 * account or declines, there is no real add-account UI to launch.
 */
class ZamanAccountAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle =
        throw UnsupportedOperationException()

    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle = Bundle().apply {
        putString(AccountManager.KEY_ACCOUNT_NAME, ZamanAccount.ACCOUNT_NAME)
        putString(AccountManager.KEY_ACCOUNT_TYPE, ZamanAccount.ACCOUNT_TYPE)
    }

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle? = null

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = throw UnsupportedOperationException()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = throw UnsupportedOperationException()

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }
}

/** Bound by the OS whenever it needs this account type's authenticator — see
 *  res/xml/authenticator.xml and the manifest's <service> entry for it. */
class AuthenticatorService : Service() {
    private lateinit var authenticator: ZamanAccountAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = ZamanAccountAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
