package com.brbrs.zaman.data.repo

import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val JSON_MEDIA = "application/json".toMediaType()
private const val SIDECAR_FOLDER = "Zaman"
private const val SIDECAR_FILE = "contact-links.json"
private const val TAG = "ZamanContactLinks"

/**
 * Feature #1 — the Vinci integration, scoped (per Andrei's choice) as "write the contact-links
 * sidecar": makes Zaman the 5th app in the suite's existing cross-app-linking architecture (see
 * the cross-app-linking project doc), alongside Tavla/Koto/Nóta, each of which writes its own
 * `<App>/contact-links.json` sidecar to the user's own Nextcloud — <itemKey> -> [cardavUid, ...]
 * — which Vinci reads from all of them to show a "Linked items" section on a contact's own
 * detail screen. No shared database, and this never touches any other app's sidecar file —
 * Zaman's is entirely its own, same as Tavla's/Koto's/Nóta's are to each other.
 *
 * Keyed on the event's own iCal UID ([com.brbrs.zaman.data.local.EventEntity.uid]), not its CalDAV
 * href — matching Koto's choice of the VTODO's own uid as its key, and because href can change
 * (e.g. feature #4's "this event"/"this and following" scopes create brand-new resources for the
 * split-off portion of a series) while uid is the one thing that actually identifies "the same
 * logical thing" across that kind of split.
 *
 * Deliberately NOT a true etag-checked debounced sync like Tavla's SolidtimeLinksSync — linking
 * is a rare, deliberate user tap, not a value that changes continuously, so a plain
 * read-modify-write on every link/unlink (see CalendarRepository.linkContact/unlinkContact) is
 * simpler and correct for that access pattern. The pull side still runs on every discovery pass
 * (see CalendarRepository.discoverCalendars) so a link made on another device shows up here too.
 *
 * Reuses the suite's single shared OkHttpClient: its auth interceptor (see AppModule) already
 * attaches the user's Nextcloud Basic Auth header to any request whose host matches their own
 * server, so no separate credential handling is needed here — same pattern as CoverSyncRepository.
 */
@Singleton
class ContactLinksSyncRepository @Inject constructor(
    private val http: OkHttpClient,
    private val auth: LoginFlowV2Auth,
) {
    private suspend fun folderUrl(): HttpUrl? {
        val server = auth.currentServerUrl()?.toHttpUrlOrNull() ?: return null
        val loginName = auth.currentLoginName() ?: return null
        return server.newBuilder()
            .addPathSegments("remote.php/dav/files")
            .addPathSegment(loginName)
            .addPathSegment(SIDECAR_FOLDER)
            .build()
    }

    private suspend fun sidecarUrl(): HttpUrl? =
        folderUrl()?.newBuilder()?.addPathSegment(SIDECAR_FILE)?.build()

    /** eventUid -> [cardavUid, ...]. Empty map (not a failure) if the file doesn't exist yet —
     *  the normal state before anything in Zaman has ever been linked. */
    suspend fun pull(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val url = sidecarUrl() ?: return@withContext emptyMap()
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emptyMap()
                } else {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    obj.keys().asSequence().associateWith { key ->
                        val arr = obj.getJSONArray(key)
                        (0 until arr.length()).map { arr.getString(it) }
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    /** Overwrites the whole sidecar with [links]. Best-effort: a failure here (offline, server
     *  hiccup) only means the next device/session won't see this particular change yet — it
     *  never blocks or fails the local link/unlink itself, which already happened in Room. */
    suspend fun push(links: Map<String, List<String>>) = withContext(Dispatchers.IO) {
        val folder = folderUrl() ?: return@withContext
        val url = sidecarUrl() ?: return@withContext
        runCatching {
            ensureFolderExists(folder)
            val body = JSONObject().apply {
                links.forEach { (key, uids) -> put(key, JSONArray(uids)) }
            }.toString()
            val req = Request.Builder().url(url).put(body.toRequestBody(JSON_MEDIA)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) android.util.Log.w(TAG, "push failed: HTTP ${resp.code}")
            }
        }.onFailure { e -> android.util.Log.w(TAG, "push failed", e) }
    }

    /** MKCOL the Zaman/ folder; 405/409 (already exists) is the expected steady state. */
    private fun ensureFolderExists(folder: HttpUrl) {
        val req = Request.Builder().url(folder).method("MKCOL", null).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 405 && resp.code != 409) {
                android.util.Log.w(TAG, "MKCOL $folder -> HTTP ${resp.code}")
            }
        }
    }
}
