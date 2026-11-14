package com.brbrs.zaman.data.repo

import android.content.Context
import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import com.brbrs.zaman.data.local.CalendarEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val COVER_MEDIA = "application/octet-stream".toMediaType()
private const val COVERS_FOLDER = "zaman-covers"
private const val TAG = "ZamanCoverSync"

/** Same deterministic filename scheme as the local cache (CalendarsViewModel) — one calendar
 *  always maps to the same name, so a re-upload just overwrites instead of leaking old copies. */
fun coverFileNameFor(calendarUrl: String): String =
    "${UUID.nameUUIDFromBytes(calendarUrl.toByteArray())}.img"

/**
 * Backs calendar cover photos up to a small `zaman-covers/` folder in the user's own Nextcloud
 * over WebDAV — the suite-wide rule for binary assets (see engineering-learnings.md: "Store
 * binary assets like cover images in the Nextcloud app folder ... cache locally for speed, but
 * the source of truth is always Nextcloud"). Covers used to live ONLY in app-private storage
 * (filesDir/calendar_covers), so an uninstall — even one forced by Android refusing an in-place
 * update — silently wiped every calendar's photo for good. This repository uploads on every
 * cover set/clear and re-downloads anything missing locally right after calendar discovery, so a
 * fresh install gets covers back automatically once the user signs back in — no manual
 * export/import step needed.
 *
 * Reuses the suite's single shared OkHttpClient: its auth interceptor (see AppModule) already
 * attaches the user's Nextcloud Basic Auth header to any request whose host matches their own
 * server, so no separate credential handling is needed here.
 */
@Singleton
class CoverSyncRepository @Inject constructor(
    private val http: OkHttpClient,
    private val auth: LoginFlowV2Auth,
    @ApplicationContext private val appContext: Context,
) {
    private val coversDir: File get() = File(appContext.filesDir, "calendar_covers")

    private suspend fun coversFolderUrl(): HttpUrl? {
        val server = auth.currentServerUrl()?.toHttpUrlOrNull() ?: return null
        val loginName = auth.currentLoginName() ?: return null
        return server.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(loginName)
            .addPathSegment(COVERS_FOLDER)
            .build()
    }

    /** Uploads (or re-uploads) this calendar's cover file to Nextcloud. Best-effort: a failure
     *  here (offline, server hiccup) only means the next sync's restore pass won't find it —
     *  it never blocks or fails the local cover change itself. */
    suspend fun uploadCover(calendarUrl: String, file: File) = withContext(Dispatchers.IO) {
        val folder = coversFolderUrl() ?: return@withContext
        runCatching {
            ensureFolderExists(folder)
            val fileUrl = folder.newBuilder().addPathSegment(coverFileNameFor(calendarUrl)).build()
            val req = Request.Builder().url(fileUrl).put(file.readBytes().toRequestBody(COVER_MEDIA)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w(TAG, "upload failed for $calendarUrl: HTTP ${resp.code}")
                }
            }
        }.onFailure { e -> android.util.Log.w(TAG, "upload failed for $calendarUrl", e) }
    }

    /** Removes this calendar's cover from Nextcloud too, mirroring a local clear. */
    suspend fun deleteCover(calendarUrl: String) = withContext(Dispatchers.IO) {
        val folder = coversFolderUrl() ?: return@withContext
        runCatching {
            val fileUrl = folder.newBuilder().addPathSegment(coverFileNameFor(calendarUrl)).build()
            http.newCall(Request.Builder().url(fileUrl).delete().build()).execute().close()
        }.onFailure { e -> android.util.Log.w(TAG, "remote delete failed for $calendarUrl", e) }
    }

    /**
     * Two-way reconciliation between the local cover cache and Nextcloud, run on every discovery
     * pass. For each calendar this checks which side actually has the file and fixes whichever
     * side is missing it:
     *  - local missing, remote has it -> download (the reinstall-recovery case this was built for)
     *  - local has it, remote missing -> upload (the BACKFILL case: any cover set before this
     *    sync feature existed was only ever written locally, so plain downloadMissingCovers()
     *    alone would never see it as "restorable" after an uninstall — there was nothing on
     *    Nextcloud to restore FROM. This also self-heals a cover whose original upload silently
     *    failed, e.g. offline at the time it was set.)
     *  - both sides agree (both present or both absent) -> nothing to do
     *
     * Returns calendarUrl -> newly-downloaded local path only (uploads don't change local state,
     * so the caller — CalendarRepository — only needs to write Room rows for the download case).
     */
    suspend fun reconcileCovers(calendars: List<CalendarEntity>): Map<String, String> =
        withContext(Dispatchers.IO) {
            val folder = coversFolderUrl() ?: return@withContext emptyMap()
            val result = mutableMapOf<String, String>()
            calendars.forEach { cal ->
                val localFile = cal.coverImagePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0 }
                val fileUrl = folder.newBuilder().addPathSegment(coverFileNameFor(cal.url)).build()
                runCatching {
                    val remoteExists = http.newCall(Request.Builder().url(fileUrl).head().build()).execute().use { it.isSuccessful }
                    when {
                        localFile == null && remoteExists -> {
                            http.newCall(Request.Builder().url(fileUrl).get().build()).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    coversDir.mkdirs()
                                    val dest = File(coversDir, coverFileNameFor(cal.url))
                                    resp.body?.byteStream()?.use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
                                    if (dest.exists() && dest.length() > 0) {
                                        result[cal.url] = dest.absolutePath
                                        android.util.Log.d(TAG, "restored cover for ${cal.displayName} from Nextcloud")
                                    }
                                }
                            }
                        }
                        localFile != null && !remoteExists -> {
                            ensureFolderExists(folder)
                            val req = Request.Builder().url(fileUrl).put(localFile.readBytes().toRequestBody(COVER_MEDIA)).build()
                            http.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) android.util.Log.d(TAG, "backfilled cover for ${cal.displayName} to Nextcloud")
                                else android.util.Log.w(TAG, "backfill upload failed for ${cal.url}: HTTP ${resp.code}")
                            }
                        }
                        // Both present or both absent — nothing to reconcile.
                    }
                }.onFailure { e -> android.util.Log.w(TAG, "reconcile failed for ${cal.url}", e) }
            }
            result
        }

    /** MKCOL the covers folder; 405/409 (already exists) is the expected steady state. */
    private fun ensureFolderExists(folder: HttpUrl) {
        val req = Request.Builder().url(folder).method("MKCOL", null).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 405 && resp.code != 409) {
                android.util.Log.w(TAG, "MKCOL $folder -> HTTP ${resp.code}")
            }
        }
    }
}
