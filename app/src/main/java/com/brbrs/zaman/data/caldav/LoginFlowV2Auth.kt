package com.brbrs.zaman.data.caldav

import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI

/**
 * Nextcloud Login Flow v2 — same handshake as Nóta/Vinci/Tavla/Koto.
 *
 * Flow:
 *  1. POST {server}/index.php/login/v2 → {login, poll: {token, endpoint}}
 *  2. Open `login` in a browser/custom tab for the user to approve.
 *  3. Poll `endpoint` with `token` until it 200s with {server, loginName, appPassword}.
 *
 * CRITICAL: the server-returned poll endpoint's host must be rewritten to match
 * the address the user actually typed — otherwise Tailscale/custom-subdomain
 * setups silently poll the wrong host forever. Never remove this rewrite.
 */
class LoginFlowV2Auth(private val store: DataStore<Preferences>) {

    private object Keys {
        val SERVER = stringPreferencesKey("server_url")
        val LOGIN_NAME = stringPreferencesKey("login_name")
        val APP_PASSWORD = stringPreferencesKey("app_password")
        val BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    val serverUrl: Flow<String?> = store.data.map { it[Keys.SERVER] }
    val loginName: Flow<String?> = store.data.map { it[Keys.LOGIN_NAME] }
    val isLoggedIn: Flow<Boolean> = store.data.map { it[Keys.APP_PASSWORD] != null }
    val biometricLockEnabled: Flow<Boolean> = store.data.map { it[Keys.BIOMETRIC_LOCK_ENABLED] ?: false }

    // Separate from the session itself: a returning user who signs out shouldn't be shown
    // the first-run welcome screen again on their next sign-in.
    val onboardingDone: Flow<Boolean> = store.data.map { it[Keys.ONBOARDING_DONE] ?: false }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        store.edit { it[Keys.BIOMETRIC_LOCK_ENABLED] = enabled }
    }

    suspend fun setOnboardingDone() {
        store.edit { it[Keys.ONBOARDING_DONE] = true }
    }

    suspend fun basicAuthHeader(): String? {
        val prefs = store.data.first()
        val user = prefs[Keys.LOGIN_NAME] ?: return null
        val pass = prefs[Keys.APP_PASSWORD] ?: return null
        val token = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)
        return "Basic $token"
    }

    suspend fun currentServerUrl(): String? = store.data.first()[Keys.SERVER]
    suspend fun currentLoginName(): String? = store.data.first()[Keys.LOGIN_NAME]

    /**
     * Starts the handshake; returns the browser URL to open for approval plus a poller.
     *
     * Every caller of this (AccountViewModel) runs it from viewModelScope, whose default
     * dispatcher is Main — and OkHttp's `.execute()` is a blocking synchronous call, so without
     * explicitly stepping off Main here, every real network call throws
     * NetworkOnMainThreadException. Never remove this withContext.
     */
    suspend fun startLoginFlow(client: OkHttpClient, serverBaseUrl: String): LoginFlowSession =
        withContext(Dispatchers.IO) {
            val normalized = normalizeServerUrl(serverBaseUrl)
            val req = Request.Builder()
                .url("$normalized/index.php/login/v2")
                .post(ByteArray(0).toRequestBody(null))
                .build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Login Flow v2 init failed: HTTP ${resp.code}" }
                val body = JSONObject(resp.body?.string().orEmpty())
                val poll = body.getJSONObject("poll")
                val rawPollEndpoint = poll.getString("endpoint")
                // Rewrite the poll endpoint's host to the one the user typed.
                val rewrittenEndpoint = rewriteHost(rawPollEndpoint, normalized)
                LoginFlowSession(
                    browserUrl = body.getString("login"),
                    pollEndpoint = rewrittenEndpoint,
                    pollToken = poll.getString("token"),
                    serverUrl = normalized,
                )
            }
        }

    /** Call repeatedly (e.g. every 1s) until it returns true; 404 means "keep waiting". See [startLoginFlow] on why this is on Dispatchers.IO. */
    suspend fun pollOnce(client: OkHttpClient, session: LoginFlowSession): Boolean =
        withContext(Dispatchers.IO) {
            val form = okhttp3.FormBody.Builder().add("token", session.pollToken).build()
            val req = Request.Builder().url(session.pollEndpoint).post(form).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404) return@withContext false
                check(resp.isSuccessful) { "Login Flow v2 poll failed: HTTP ${resp.code}" }
                val body = JSONObject(resp.body?.string().orEmpty())
                store.edit { prefs ->
                    prefs[Keys.SERVER] = body.optString("server", session.serverUrl)
                    prefs[Keys.LOGIN_NAME] = body.getString("loginName")
                    prefs[Keys.APP_PASSWORD] = body.getString("appPassword")
                }
                true
            }
        }

    /** Signs out but keeps onboarding state — a returning user isn't shown the welcome screen again. */
    suspend fun logout() {
        store.edit { prefs ->
            prefs.remove(Keys.SERVER)
            prefs.remove(Keys.LOGIN_NAME)
            prefs.remove(Keys.APP_PASSWORD)
            prefs.remove(Keys.BIOMETRIC_LOCK_ENABLED)
        }
    }

    private fun normalizeServerUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
        else "https://$trimmed"
    }

    private fun rewriteHost(pollEndpoint: String, userTypedServer: String): String {
        val userUri = URI(userTypedServer)
        val pollUri = URI(pollEndpoint)
        return URI(
            userUri.scheme, pollUri.userInfo, userUri.host, userUri.port,
            pollUri.path, pollUri.query, pollUri.fragment
        ).toString()
    }
}

data class LoginFlowSession(
    val browserUrl: String,
    val pollEndpoint: String,
    val pollToken: String,
    val serverUrl: String,
)
