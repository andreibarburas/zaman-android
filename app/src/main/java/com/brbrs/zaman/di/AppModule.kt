package com.brbrs.zaman.di

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.brbrs.zaman.data.caldav.CalDavClient
import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import com.brbrs.zaman.data.carddav.CardDavContactsClient
import com.brbrs.zaman.data.local.CalendarDao
import com.brbrs.zaman.data.local.EventDao
import com.brbrs.zaman.data.local.MIGRATION_5_6
import com.brbrs.zaman.data.local.MIGRATION_6_7
import com.brbrs.zaman.data.local.MIGRATION_7_8
import com.brbrs.zaman.data.local.MIGRATION_8_9
import com.brbrs.zaman.data.local.MIGRATION_9_10
import com.brbrs.zaman.data.local.MIGRATION_10_11
import com.brbrs.zaman.data.local.ZamanDatabase
import com.brbrs.zaman.data.prefs.CalendarContractPreferences
import com.brbrs.zaman.data.prefs.DisplayPreferences
import com.brbrs.zaman.data.prefs.LocalModePreferences
import com.brbrs.zaman.data.prefs.SyncPreferences
import com.brbrs.zaman.data.prefs.SyncStatusPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

// Not private — AccountViewModel's separate plainHttp client (used only for the Login Flow v2
// handshake itself, before any stored credentials exist to build the real authenticated client
// around) needs this too. Without it, Nextcloud's "grant access" screen showed the raw default
// "okhttp/4.12.0" as the requesting app's name instead of Zaman, which looks alarming/untrustworthy
// to someone approving access on their own account.
// Was hardcoded to a literal "1.0.0" that never tracked real releases — same bug class as
// Vinci's hardcoded version string/User-Agent (see engineering-learnings.md's version-bump
// checklist: build.gradle.kts, the in-app version string, AND this header must all stay in
// sync). Not a val -- BuildConfig.VERSION_NAME needs the app's own BuildConfig, so this is
// computed where it's used instead of as a top-level constant.
internal val USER_AGENT get() = "Zaman/${com.brbrs.zaman.BuildConfig.VERSION_NAME} (com.brbrs.zaman; BarburasLab)"

private val Context.authDataStore by preferencesDataStore(name = "zaman_auth")
// Separate store from auth on purpose — clearing/resetting the account (sign-out) must
// never reset the user's theme/text-size/font choices, and vice versa.
private val Context.displayDataStore by preferencesDataStore(name = "zaman_display")
private val Context.travelDataStore by preferencesDataStore(name = "zaman_travel")
// Local-only mode toggle, kept separate so it never touches the signed-in account or display prefs.
private val Context.localModeDataStore by preferencesDataStore(name = "zaman_local_mode")
private val Context.syncStatusDataStore by preferencesDataStore(name = "zaman_sync_status")
// How often the periodic sync worker should run — separate store again, so changing it never
// touches sync-status's own history or any other preference.
private val Context.syncIntervalDataStore by preferencesDataStore(name = "zaman_sync_interval")
// Feature #2 — CalendarContract mirroring on/off, kept separate for the same reason as every
// other store here: toggling it must never touch any other preference.
private val Context.calendarContractDataStore by preferencesDataStore(name = "zaman_calendar_contract")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(authStore: LoginFlowV2Auth): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                // Debug builds only — without this, every CalDAV/CardDAV/Deck request and
                // response (including the actual HTTP status) was completely invisible in
                // logcat, which is exactly why a bad PROPFIND or an empty REPORT response could
                // only ever be diagnosed by guessing.
                //
                // BASIC, not BODY: an ICS subscription feed can run into the megabytes (one of
                // Andrei's ProtonCalendar subscriptions logged a 4.3MB response body in a single
                // request) — logging that much text to logcat is itself slow enough to make a
                // sync feel stuck, AND it floods out every other request happening around it,
                // making a logcat capture useless for diagnosing anything beyond that one
                // request. BASIC still gives method/URL/response-code/byte-count for every
                // request, which is what's actually needed to tell whether a given calendar's
                // fetch got real data back or not.
                if (com.brbrs.zaman.BuildConfig.DEBUG) {
                    addInterceptor(
                        okhttp3.logging.HttpLoggingInterceptor().apply {
                            level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                        }
                    )
                }
            }
            .addInterceptor { chain ->
                var req = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                // Login Flow v2 yields loginName + appPassword; sent as HTTP Basic,
                // same as every other app in the suite — never a bearer/OAuth flow.
                // OkHttp interceptors are synchronous, so the suspend DataStore read
                // is bridged with runBlocking — this is a quick in-memory Preferences
                // read, not a network call, so it won't stall the request thread.
                //
                // Only attach it when the request is actually going to the user's own Nextcloud
                // server. This same shared client is now also used (CalendarRepository.syncOne)
                // to fetch an ICS subscription's events straight from its original feed URL —
                // some arbitrary third-party host the user subscribed to, not Nextcloud at all.
                // Attaching the Nextcloud app-password Basic Auth header unconditionally to
                // every request would have leaked it to that external server on every sync.
                val requestHost = chain.request().url.host
                val serverHost = runBlocking { authStore.currentServerUrl() }?.let {
                    runCatching { it.toHttpUrl().host }.getOrNull()
                }
                if (serverHost != null && requestHost.equals(serverHost, ignoreCase = true)) {
                    runBlocking { authStore.basicAuthHeader() }?.let { req = req.header("Authorization", it) }
                }
                chain.proceed(req.build())
            }
            .build()

    @Provides
    @Singleton
    fun provideLoginFlowV2Auth(@ApplicationContext ctx: Context): LoginFlowV2Auth =
        LoginFlowV2Auth(ctx.authDataStore)

    @Provides
    @Singleton
    fun provideCalDavClient(okHttpClient: OkHttpClient, auth: LoginFlowV2Auth): CalDavClient =
        CalDavClient(okHttpClient, auth)

    @Provides
    @Singleton
    fun provideCardDavContactsClient(okHttpClient: OkHttpClient, auth: LoginFlowV2Auth): CardDavContactsClient =
        CardDavContactsClient(okHttpClient, auth)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): ZamanDatabase =
        Room.databaseBuilder(ctx, ZamanDatabase::class.java, "zaman.db")
            // OnConflictStrategy.REPLACE cascades deletes on FKs — DAOs use
            // INSERT OR IGNORE + UPDATE instead, see CalendarDao / EventDao.
            // MIGRATION_5_6 is tried first (a real, additive ALTER TABLE) — fallback only
            // kicks in for a version bump that doesn't have a real migration registered for it.
            .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    @Singleton
    fun provideDisplayPreferences(@ApplicationContext ctx: Context): DisplayPreferences =
        DisplayPreferences(ctx.displayDataStore)

    @Provides
    @Singleton
    fun provideTravelPreferences(@ApplicationContext ctx: Context): com.brbrs.zaman.data.prefs.TravelPreferences =
        com.brbrs.zaman.data.prefs.TravelPreferences(ctx.travelDataStore)

    @Provides
    @Singleton
    fun provideLocalModePreferences(@ApplicationContext ctx: Context): LocalModePreferences =
        LocalModePreferences(ctx.localModeDataStore)

    @Provides
    @Singleton
    fun provideSyncStatusPreferences(@ApplicationContext ctx: Context): SyncStatusPreferences =
        SyncStatusPreferences(ctx.syncStatusDataStore)

    @Provides
    @Singleton
    fun provideSyncPreferences(@ApplicationContext ctx: Context): SyncPreferences =
        SyncPreferences(ctx.syncIntervalDataStore)

    // Backs the undo-after-delete timer in CalendarRepository: a delete requested from Event Edit
    // must keep running even after that screen's own ViewModel (and its viewModelScope) is gone —
    // the user navigates back to Today immediately, well before the undo window elapses.
    @Provides
    @Singleton
    fun provideApplicationScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    @Provides
    @Singleton
    fun provideCalendarContractPreferences(@ApplicationContext ctx: Context): CalendarContractPreferences =
        CalendarContractPreferences(ctx.calendarContractDataStore)

    @Provides
    @Singleton
    fun provideCalendarDao(db: ZamanDatabase): CalendarDao = db.calendarDao()

    @Provides
    @Singleton
    fun provideEventDao(db: ZamanDatabase): EventDao = db.eventDao()
}
