package com.brbrs.zaman.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.caldav.LoginFlowSession
import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import com.brbrs.zaman.data.prefs.SyncStatus
import com.brbrs.zaman.data.repo.CalDavSyncWorker
import com.brbrs.zaman.data.repo.CalendarRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

sealed interface LoginUiState {
    data object SignedOut : LoginUiState
    data object EnteringServer : LoginUiState
    /** Browser opened for approval; keep polling until the user approves or this is cancelled. */
    data class AwaitingApproval(val browserUrl: String) : LoginUiState
    data object SignedIn : LoginUiState
    data class Error(val message: String) : LoginUiState
}

private const val POLL_INTERVAL_MS = 1500L
private const val POLL_TIMEOUT_MS = 5 * 60_000L // Nextcloud's own Login Flow v2 token also expires around here

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val auth: LoginFlowV2Auth,
    private val repository: CalendarRepository,
    private val syncPrefs: com.brbrs.zaman.data.prefs.SyncPreferences,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // A plain (unauthenticated) client for the handshake itself — the app's
    // main OkHttpClient (injected via AppModule) attaches Basic auth headers
    // we don't have yet at this point. Still carries the same User-Agent as
    // the main client, though — without it, Nextcloud's own "grant access"
    // page showed the raw default "okhttp/4.12.0" as the requesting app, not
    // "Zaman", which reads as untrustworthy to someone approving the request.
    private val plainHttp = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", com.brbrs.zaman.di.USER_AGENT).build())
        }
        .build()

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.SignedOut)
    val uiState: StateFlow<LoginUiState> = _uiState

    // True only for the brief window between tapping Connect and the server actually
    // answering (success -> AwaitingApproval, failure -> Error) — LoginUiState has no state
    // of its own for that gap, so callers that want a spinner on the button itself need this.
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    val serverUrl: StateFlow<String?> =
        auth.serverUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val loginName: StateFlow<String?> =
        auth.loginName.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val syncStatus: StateFlow<SyncStatus> =
        repository.syncStatus.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatus())

    /** Runs synchronously in this ViewModel (not just enqueued to WorkManager) so "Sync now"
     *  can show a spinner and the real result immediately instead of the button appearing to
     *  do nothing while a background worker runs on its own schedule. */
    fun syncNow() {
        viewModelScope.launch { repository.syncNow() }
    }

    init {
        viewModelScope.launch {
            if (auth.isLoggedIn.first()) _uiState.value = LoginUiState.SignedIn
        }
    }

    fun startEnteringServer() {
        _uiState.value = LoginUiState.EnteringServer
    }

    /** Returns the browser URL to open; caller launches it, then calls [beginPolling]. */
    fun connect(serverInput: String, onBrowserUrlReady: (String) -> Unit) {
        viewModelScope.launch {
            _isConnecting.value = true
            runCatching { auth.startLoginFlow(plainHttp, serverInput) }
                .onSuccess { session ->
                    _isConnecting.value = false
                    _uiState.value = LoginUiState.AwaitingApproval(session.browserUrl)
                    onBrowserUrlReady(session.browserUrl)
                    beginPolling(session)
                }
                .onFailure { e ->
                    _isConnecting.value = false
                    _uiState.value = LoginUiState.Error(describeConnectionError(e))
                }
        }
    }

    private fun beginPolling(session: LoginFlowSession) {
        viewModelScope.launch {
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            // A network hiccup while polling is expected, not fatal: the user is off in their
            // browser approving the request, so this app is backgrounded and Android often
            // restricts its network access until it's foregrounded again. Keep polling and only
            // surface the last error if the whole window runs out without ever succeeding.
            var lastError: String? = null
            while (System.currentTimeMillis() < deadline) {
                val result = runCatching { auth.pollOnce(plainHttp, session) }
                result.onSuccess { approved ->
                    if (approved) {
                        _uiState.value = LoginUiState.SignedIn
                        // CalDavSyncWorker now calls repository.syncNow(), which discovers
                        // calendars AND syncs AND records the outcome — one call does it all
                        // and the Settings/Calendars status line reflects it immediately.
                        CalDavSyncWorker.enqueue(appContext)
                        // Respects whatever interval the user already picked (or the default, on
                        // a fresh sign-in) instead of silently resetting it to 15 minutes here —
                        // UPDATE policy means this would otherwise clobber a longer interval the
                        // user chose before signing out and back in.
                        CalDavSyncWorker.enqueuePeriodic(appContext, syncPrefs.interval.first().minutes)
                        return@launch
                    }
                }
                result.onFailure { e -> lastError = describeConnectionError(e) }
                delay(POLL_INTERVAL_MS)
            }
            val suffix = lastError?.let { " Last error: $it" }.orEmpty()
            _uiState.value = LoginUiState.Error("Approval timed out — try connecting again.$suffix")
        }
    }

    /**
     * Turns an OkHttp/connection exception into something a non-technical user can act on,
     * instead of a bare "could not reach that server" for every failure mode. Same
     * classification as the rest of the suite — see Tavla's LoginViewModel.
     */
    private fun describeConnectionError(e: Throwable): String = when {
        e.message?.contains("CLEARTEXT") == true -> "Server requires HTTPS"
        e.message?.contains("Unable to resolve host") == true -> "Cannot find that server — check the address"
        e.message?.contains("timeout", ignoreCase = true) == true -> "Connection timed out"
        e.message?.contains("CERTIFICATE") == true || e.message?.contains("trust", ignoreCase = true) == true ->
            "SSL certificate error — if this server uses a self-signed or custom certificate, " +
                "install it as a trusted certificate on this device first"
        e.message?.contains("Connection refused") == true -> "Connection refused — is the address and port correct?"
        e.message.isNullOrBlank() -> "Cannot reach server (${e.javaClass.simpleName})"
        else -> "Cannot reach server: ${e.message}"
    }

    fun cancelPolling() {
        _uiState.value = LoginUiState.SignedOut
    }

    fun signOut() {
        viewModelScope.launch {
            auth.logout()
            CalDavSyncWorker.cancelPeriodic(appContext)
            _uiState.value = LoginUiState.SignedOut
        }
    }
}
