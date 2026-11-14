package com.brbrs.zaman.data.caldav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()
private val ICS_MEDIA = "text/calendar; charset=utf-8".toMediaType()

// A deliberately wide UTC time-range (RFC 5545 form) standing in for "unconditional" in a
// calendar-query's VEVENT time-range filter — used only by the unbounded paths below
// (searchEventsUnbounded, and a caller that explicitly needs "truly everything").
private const val FULL_RANGE_START = "19700101T000000Z"
private const val FULL_RANGE_END = "20991231T235959Z"

private val RANGE_FMT: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

/**
 * Battery/sync-window fix: bounding every routine fetch to a window around "now" instead of a
 * calendar's entire history — same default DAVx5 itself ships (90 days back, 2 years forward).
 * A calendar with years of history doesn't need its oldest events re-downloaded and re-diffed on
 * every periodic sync just to confirm nothing moved; see CalendarRepository's doc comment and
 * Search's on-demand widen fallback (searchEventsUnbounded) for anything outside this window.
 */
private fun boundedRangeStart(): String =
    java.time.Instant.now().minus(90, java.time.temporal.ChronoUnit.DAYS).atZone(java.time.ZoneOffset.UTC).format(RANGE_FMT)

private fun boundedRangeEnd(): String =
    java.time.Instant.now().plus(730, java.time.temporal.ChronoUnit.DAYS).atZone(java.time.ZoneOffset.UTC).format(RANGE_FMT)

/**
 * Talks CalDAV directly to Nextcloud — no DAVx5/ICSx and no Android
 * CalendarContract bridge. REPORT/PROPFIND over OkHttp, iCalendar parsed by
 * [com.brbrs.zaman.data.ical.ICalParser].
 */
class CalDavClient(
    private val http: OkHttpClient,
    private val auth: LoginFlowV2Auth,
) {

    /**
     * PROPFIND the user's calendar-home-set and list every writable/subscribed
     * calendar AND task list. Nextcloud Tasks (Koto's backend) is not a
     * separate protocol — a task list is a calendar collection whose
     * supported-calendar-component-set is VTODO rather than VEVENT, in the
     * exact same calendar-home-set this already walks, so Koto's due-dates
     * come in for free here: no sidecar, no second server integration.
     */
    suspend fun discoverCalendars(serverUrl: String, loginName: String): List<DiscoveredCalendar> =
        withContext(Dispatchers.IO) {
            val home = "$serverUrl/remote.php/dav/calendars/$loginName/"
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/" xmlns:oc="http://owncloud.org/ns" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  <d:prop>
                    <d:resourcetype/>
                    <d:displayname/>
                    <ic:calendar-color/>
                    <cs:source/>
                    <oc:calendar-enabled/>
                    <d:current-user-privilege-set/>
                    <c:supported-calendar-component-set/>
                  </d:prop>
                </d:propfind>
            """.trimIndent()

            val req = Request.Builder()
                .url(home)
                .method("PROPFIND", body.toRequestBody(XML_MEDIA))
                .header("Depth", "1")
                .build()

            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "PROPFIND calendars failed: HTTP ${resp.code}" }
                val xml = resp.body?.string().orEmpty()
                parseCalendarDiscovery(xml, home, serverUrl)
            }
        }

    /**
     * Full sync (`calendar-query`) or incremental (`sync-collection` with a
     * stored sync-token) — prefer the latter once a calendar has synced once.
     *
     * RFC 4791 §9.6.1 says a bare <c:comp-filter name="VEVENT"/> with no <c:time-range>
     * should match every VEVENT unconditionally — but Nextcloud's sabre/dav backend (and
     * several other real-world CalDAV servers) only reliably returns matches through its
     * query optimizer when a time-range IS present; without one it silently came back with
     * an empty <d:multistatus/> for real calendars that do have events, which is exactly
     * what a full logcat capture showed across several of the user's actual Nextcloud
     * calendars. Always including a deliberately wide time-range (far past to far future)
     * makes this an unconditional full fetch in practice while keeping the server's
     * calendar-query implementation on its well-tested path.
     *
     * Battery/sync-window fix: defaults to a BOUNDED range (see boundedRangeStart/End) rather
     * than "everything ever" — this is the fallback path for a stale/unsupported sync-token, and
     * on a calendar with years of history "full fetch" was really "full history fetch", every
     * time the token happened to go stale. Pass [rangeStart]/[rangeEnd] explicitly (e.g.
     * FULL_RANGE_START/END via fetchEventsUnbounded) only for the rare case that genuinely needs
     * every event ever, not just what's near "now".
     */
    suspend fun fetchEventsFullRange(
        calendarUrl: String,
        rangeStart: String = boundedRangeStart(),
        rangeEnd: String = boundedRangeEnd(),
    ): List<RawCalendarObject> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop>
                <d:getetag/>
                <c:calendar-data/>
              </d:prop>
              <c:filter>
                <c:comp-filter name="VCALENDAR">
                  <c:comp-filter name="VEVENT">
                    <c:time-range start="$rangeStart" end="$rangeEnd"/>
                  </c:comp-filter>
                </c:comp-filter>
              </c:filter>
            </c:calendar-query>
        """.trimIndent()
        return runReport(calendarUrl, body)
    }

    /** The old, unbounded "every event ever" fetch — used only by Search's on-demand widen
     *  fallback (feature: battery/sync window), never by a periodic/routine sync. */
    suspend fun fetchEventsUnbounded(calendarUrl: String): List<RawCalendarObject> =
        fetchEventsFullRange(calendarUrl, FULL_RANGE_START, FULL_RANGE_END)

    /**
     * Server-side SUMMARY search, unbounded in time — Search's on-demand widen fallback for a
     * match outside the bounded sync window (feature: battery/sync window). Results are never
     * persisted to Room by CalendarRepository; they're shown for that one search and discarded.
     * Only meaningful for a genuinely-owned calendar — a subscription's calendar-query comes back
     * empty regardless of filter (see fetchEventsViaPropfind's doc comment), so CalendarRepository
     * skips calling this for those.
     */
    suspend fun searchEventsUnbounded(calendarUrl: String, query: String): List<RawCalendarObject> {
        val escaped = query.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop>
                <d:getetag/>
                <c:calendar-data/>
              </d:prop>
              <c:filter>
                <c:comp-filter name="VCALENDAR">
                  <c:comp-filter name="VEVENT">
                    <c:prop-filter name="SUMMARY">
                      <c:text-match collation="i;unicode-casemap">$escaped</c:text-match>
                    </c:prop-filter>
                  </c:comp-filter>
                </c:comp-filter>
              </c:filter>
            </c:calendar-query>
        """.trimIndent()
        return runReport(calendarUrl, body)
    }

    /**
     * A Nextcloud "subscription" calendar (an external ICS/webcal feed Nextcloud proxies —
     * resourcetype <cs:subscribed/>, not a normal owned calendar) is backed by Nextcloud's own
     * internal webcal cache rather than a regular queryable calendar backend. calendar-query
     * REPORT — including fetchEventsFullRange above, confirmed working fine for genuinely-owned
     * calendars — consistently comes back with a completely empty <d:multistatus/> against these,
     * even though the same events are visibly present in Nextcloud's own web Calendar app.
     *
     * The first attempt here was a plain PROPFIND Depth:1 asking for <c:calendar-data/> directly
     * as a property — but that didn't fix anything either (confirmed by the user after a full
     * uninstall/reinstall), and RFC 4791 explains why: CALDAV:calendar-data is only defined to be
     * returned by calendar-query/calendar-multiget REPORT, never by a bare PROPFIND. sabre/dav
     * (Nextcloud's CalDAV backend) simply omits it from a PROPFIND response — so that attempt was
     * getting back a 200 for every member's <d:getetag/> while silently never getting any
     * <c:calendar-data/>, and parseMultistatusObjects correctly treated "no calendar-data" as "no
     * event" for every single one of them.
     *
     * The fix: PROPFIND Depth:1 for just the member hrefs/etags (a property PROPFIND reliably
     * returns for any collection), then GET each member resource directly — a plain HTTP GET on
     * an .ics resource always returns its raw iCalendar body; it doesn't go through REPORT's
     * filter/query engine at all, so it's unaffected by whatever makes the webcal-mirror backend
     * refuse calendar-query/calendar-data requests.
     */
    suspend fun fetchEventsViaPropfind(calendarUrl: String): List<RawCalendarObject> =
        withContext(Dispatchers.IO) {
            val listBody = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:propfind xmlns:d="DAV:">
                  <d:prop>
                    <d:getetag/>
                    <d:resourcetype/>
                  </d:prop>
                </d:propfind>
            """.trimIndent()
            val listReq = Request.Builder()
                .url(calendarUrl)
                .method("PROPFIND", listBody.toRequestBody(XML_MEDIA))
                .header("Depth", "1")
                .build()
            val members = http.newCall(listReq).execute().use { resp ->
                check(resp.isSuccessful) { "PROPFIND events failed: HTTP ${resp.code}" }
                parseMemberHrefs(resp.body?.string().orEmpty(), calendarUrl)
            }
            members.mapNotNull { href ->
                val getReq = Request.Builder().url(href).get().build()
                http.newCall(getReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@mapNotNull null
                    val text = resp.body?.string().orEmpty()
                    if (!text.contains("BEGIN:VCALENDAR")) return@mapNotNull null
                    RawCalendarObject(href, resp.header("ETag"), text)
                }
            }
        }

    /**
     * Last-resort fallback for a subscription calendar, below fetchEventsViaPropfind: a Nextcloud
     * "subscription" collection may not expose its mirrored events as separate child resources at
     * all — sabre/dav can model the whole subscribed feed as a single synthetic resource (its
     * calendar-data holding every VEVENT concatenated, same as root bug #1's multi-event-resource
     * case), in which case a Depth:1 PROPFIND against it returns only the collection entry itself
     * and zero real members, so fetchEventsViaPropfind's member-then-GET approach comes back
     * empty too — not because GET doesn't work, but because there was nothing to GET. The one
     * request shape every CalDAV/webcal client ultimately falls back to for "just give me
     * whatever's in here" is a bare GET straight on the collection URL itself; this is how a
     * generic calendar subscription works in the first place (an .ics URL is just fetched, never
     * PROPFIND'd or REPORT'd).
     */
    suspend fun fetchEventsViaDirectGet(calendarUrl: String): List<RawCalendarObject> =
        fetchEventsViaDirectGetConditional(calendarUrl, ifNoneMatchEtag = null).objects

    /**
     * Battery/sync-window fix: conditional GET for a subscription's whole-feed fetch. A webcal
     * feed is one single HTTP resource — there's no sync-collection/time-range to bound it with
     * the way an owned calendar has — so the only lever here is HTTP's own `If-None-Match`:
     * send back the ETag from the last successful fetch (stored on CalendarEntity.feedEtag), and
     * a well-behaved server returns a bare 304 instead of the full feed body when nothing
     * changed, skipping the download AND the full re-parse/re-diff entirely. A feed host that
     * doesn't support conditional requests just ignores the header and returns 200 as always —
     * this never makes things worse, only sometimes better.
     */
    suspend fun fetchEventsViaDirectGetConditional(calendarUrl: String, ifNoneMatchEtag: String?): DirectGetResult =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(calendarUrl).header("Accept", "text/calendar")
            ifNoneMatchEtag?.let { builder.header("If-None-Match", it) }
            http.newCall(builder.get().build()).execute().use { resp ->
                if (resp.code == 304) return@withContext DirectGetResult(notModified = true, etag = ifNoneMatchEtag, objects = emptyList())
                if (!resp.isSuccessful) return@withContext DirectGetResult(notModified = false, etag = null, objects = emptyList())
                val text = resp.body?.string().orEmpty()
                if (!text.contains("BEGIN:VCALENDAR")) return@withContext DirectGetResult(notModified = false, etag = null, objects = emptyList())
                DirectGetResult(
                    notModified = false,
                    etag = resp.header("ETag"),
                    objects = listOf(RawCalendarObject(calendarUrl, resp.header("ETag"), text)),
                )
            }
        }

    /** Same REPORT shape as fetchEventsFullRange, filtered to VTODO instead of VEVENT. */
    suspend fun fetchTodosFullRange(calendarUrl: String): List<RawCalendarObject> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:prop>
                <d:getetag/>
                <c:calendar-data/>
              </d:prop>
              <c:filter>
                <c:comp-filter name="VCALENDAR">
                  <c:comp-filter name="VTODO"/>
                </c:comp-filter>
              </c:filter>
            </c:calendar-query>
        """.trimIndent()
        return runReport(calendarUrl, body)
    }

    suspend fun syncCollection(calendarUrl: String, syncToken: String?): SyncResult =
        withContext(Dispatchers.IO) {
            val tokenEl = if (syncToken != null) "<d:sync-token>$syncToken</d:sync-token>" else "<d:sync-token/>"
            val body = """
                <?xml version="1.0" encoding="utf-8"?>
                <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                  $tokenEl
                  <d:sync-level>1</d:sync-level>
                  <d:prop>
                    <d:getetag/>
                    <c:calendar-data/>
                  </d:prop>
                </d:sync-collection>
            """.trimIndent()

            val req = Request.Builder()
                .url(calendarUrl)
                .method("REPORT", body.toRequestBody(XML_MEDIA))
                .header("Depth", "1")
                .build()
            http.newCall(req).execute().use { resp ->
                // 507 insufficient-storage means the token is stale — caller should fall back to full sync.
                if (resp.code == 507) {
                    SyncResult(needsFullResync = true, token = null, objects = emptyList(), removedHrefs = emptyList())
                } else {
                    check(resp.isSuccessful) { "sync-collection failed: HTTP ${resp.code}" }
                    val xml = resp.body?.string().orEmpty()
                    parseSyncCollection(xml, calendarUrl)
                }
            }
        }

    suspend fun putEvent(href: String, icsBody: String, ifMatchEtag: String?): String? =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(href).put(icsBody.toRequestBody(ICS_MEDIA))
            if (ifMatchEtag != null) b.header("If-Match", ifMatchEtag) else b.header("If-None-Match", "*")
            http.newCall(b.build()).execute().use { resp ->
                check(resp.isSuccessful) { "PUT event failed: HTTP ${resp.code}" }
                resp.header("ETag")
            }
        }

    suspend fun deleteEvent(href: String, etag: String?) {
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(href).delete()
            if (etag != null) b.header("If-Match", etag)
            http.newCall(b.build()).execute().use { resp ->
                check(resp.isSuccessful || resp.code == 404) { "DELETE event failed: HTTP ${resp.code}" }
            }
        }
    }

    private suspend fun runReport(calendarUrl: String, xmlBody: String): List<RawCalendarObject> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(calendarUrl)
                .method("REPORT", xmlBody.toRequestBody(XML_MEDIA))
                .header("Depth", "1")
                .build()
            http.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "REPORT failed: HTTP ${resp.code}" }
                parseMultistatusObjects(resp.body?.string().orEmpty(), calendarUrl)
            }
        }

    // --- XML parsing helpers (org.json has no XML support; javax.xml.parsers is in the platform, no extra dep) ---

    private fun parseDoc(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))

    private fun Element.child(localName: String): Element? {
        val nodes = getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.parentNode === this }
    }

    private fun Element.text(localName: String): String? = child(localName)?.textContent

    /**
     * Multistatus <d:href> values are server-relative paths per RFC 4918/CalDAV
     * (e.g. "/remote.php/dav/calendars/alice/personal/"), not full URLs — Nextcloud
     * always returns them this way. Every href this client stores or re-issues a
     * request against must be resolved to an absolute URL against the server
     * origin first, or OkHttp throws trying to request a bare path.
     */
    private fun resolveHref(base: String, href: String): String = URI(base).resolve(href).toString()

    private fun parseCalendarDiscovery(xml: String, homeUrl: String, serverUrl: String): List<DiscoveredCalendar> {
        val doc = parseDoc(xml)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = mutableListOf<DiscoveredCalendar>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as? Element ?: continue
            val href = r.text("href") ?: continue
            val propstat = r.getElementsByTagNameNS("DAV:", "propstat")
            var prop: Element? = null
            for (j in 0 until propstat.length) {
                val ps = propstat.item(j) as Element
                if (ps.text("status")?.contains("200") == true) { prop = ps.child("prop"); break }
            }
            // A regular calendar's <d:resourcetype> contains <cal:calendar/>. An ICS
            // subscription added in Nextcloud's own Calendar app is a DIFFERENT
            // resourcetype entirely — <d:collection/><cs:subscribed/> — with no
            // <cal:calendar/> element at all. Checking only for <cal:calendar/> here
            // silently dropped every subscription calendar during discovery, before
            // isSubscription was ever evaluated: they never reached Room, so nothing
            // downstream (Calendars screen, sync, agenda) ever saw them.
            val isCaldavCalendar = prop?.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar")?.length ?: 0 > 0
            val isSubscribedCollection = prop?.getElementsByTagNameNS("http://calendarserver.org/ns/", "subscribed")?.length ?: 0 > 0
            val isCalendar = isCaldavCalendar || isSubscribedCollection
            val resolved = resolveHref(serverUrl, href)
            if (!isCalendar || resolved.trimEnd('/') == homeUrl.trimEnd('/')) continue
            // Nextcloud Deck auto-publishes a SEPARATE CalDAV VTODO calendar per board —
            // "app-generated--deck--board-N" — purely so other CalDAV clients can see card due
            // dates. Zaman already reads Deck properly through its own OCS REST API
            // (DeckApiClient / Tavla's pseudo-calendar), so discovering these too was pulling in
            // one spurious extra "calendar" per Deck board (dozens, for an active Tavla user) —
            // none of them real events, and crowding out/slowing down the real calendars'
            // discovery and sync in the same pass.
            if (resolved.contains("app-generated--deck--")) continue
            // <cs:source><d:href>webcal://example.com/feed.ics</d:href></cs:source> — the feed
            // the user actually subscribed to in Nextcloud's Calendar app. Converted from
            // webcal:// to https:// since that's not a scheme OkHttp/Java URL understands, but is
            // universally just an alias for https for this exact purpose.
            val sourceHref = prop?.child("source")?.child("href")?.textContent
            val sourceUrl = sourceHref?.let {
                if (it.startsWith("webcal://", ignoreCase = true)) "https://" + it.removePrefix("webcal://") else it
            }
            val isSubscription = isSubscribedCollection || sourceHref != null

            // A task list (Nextcloud Tasks / Koto's backend) advertises VTODO
            // in its supported-calendar-component-set, usually without VEVENT.
            val compNodes = prop?.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "comp")
            val supportedComps = (0 until (compNodes?.length ?: 0))
                .mapNotNull { (compNodes?.item(it) as? Element)?.getAttribute("name") }
            val isTaskList = supportedComps.isNotEmpty() &&
                supportedComps.contains("VTODO") && !supportedComps.contains("VEVENT")

            out += DiscoveredCalendar(
                url = resolved,
                displayName = prop?.text("displayname") ?: resolved.trimEnd('/').substringAfterLast('/'),
                color = prop?.text("calendar-color"),
                readOnly = isSubscription,
                isSubscription = isSubscription,
                subscriptionSourceUrl = sourceUrl,
                kind = if (isTaskList) CollectionKind.TASK_LIST else CollectionKind.EVENTS,
            )
        }
        return out
    }

    /**
     * Depth:1 PROPFIND lists the collection itself as the first <d:response> (its own href,
     * equal to [calendarUrl] once resolved) alongside every member resource — only the members
     * are real calendar objects, and the collection entry is also the one most likely to carry a
     * <d:resourcetype><d:collection/></d:resourcetype> marking it as a container rather than a
     * resource, which is the more robust way to exclude it (a trailing-slash string comparison
     * breaks the moment the server normalizes the href differently than [calendarUrl] was
     * stored).
     */
    private fun parseMemberHrefs(xml: String, calendarUrl: String): List<String> {
        val doc = parseDoc(xml)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = mutableListOf<String>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as? Element ?: continue
            val href = r.text("href") ?: continue
            val propstats = r.getElementsByTagNameNS("DAV:", "propstat")
            var prop: Element? = null
            for (j in 0 until propstats.length) {
                val ps = propstats.item(j) as? Element ?: continue
                if (ps.text("status")?.contains("200") == true) { prop = ps.child("prop"); break }
            }
            val isCollection = prop?.getElementsByTagNameNS("DAV:", "collection")?.length ?: 0 > 0
            if (isCollection) continue
            out += resolveHref(calendarUrl, href)
        }
        return out
    }

    private fun parseMultistatusObjects(xml: String, calendarUrl: String): List<RawCalendarObject> {
        val doc = parseDoc(xml)
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val out = mutableListOf<RawCalendarObject>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as? Element ?: continue
            val href = r.text("href") ?: continue
            // A response can carry several <d:propstat> blocks, one per distinct status code
            // (e.g. calendar-data succeeding at 200 while some unrelated requested prop 404s) —
            // always picking propstat[0] (as this used to) silently returns nothing the moment a
            // 404 block happens to come first, which a PROPFIND-based fetch (see
            // fetchEventsViaPropfind) hits far more often than a REPORT's single-concern response.
            // Same more-careful propstat selection parseCalendarDiscovery already uses.
            val propstats = r.getElementsByTagNameNS("DAV:", "propstat")
            var prop: Element? = null
            for (j in 0 until propstats.length) {
                val ps = propstats.item(j) as? Element ?: continue
                if (ps.text("status")?.contains("200") == true) { prop = ps.child("prop"); break }
            }
            if (prop == null) continue
            val etag = prop.text("getetag")
            val icsNodes = prop.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
            val ics = if (icsNodes.length > 0) icsNodes.item(0).textContent else null
            if (ics != null) out += RawCalendarObject(resolveHref(calendarUrl, href), etag, ics)
        }
        return out
    }

    private fun parseSyncCollection(xml: String, calendarUrl: String): SyncResult {
        val doc = parseDoc(xml)
        val newToken = (doc.getElementsByTagNameNS("DAV:", "sync-token").item(0) as? Element)?.textContent
        val responses = doc.getElementsByTagNameNS("DAV:", "response")
        val objects = mutableListOf<RawCalendarObject>()
        val removed = mutableListOf<String>()
        for (i in 0 until responses.length) {
            val r = responses.item(i) as? Element ?: continue
            val href = resolveHref(calendarUrl, r.text("href") ?: continue)
            val status = r.text("status")
            if (status?.contains("404") == true) { removed += href; continue }
            val prop = (r.getElementsByTagNameNS("DAV:", "propstat").item(0) as? Element)?.child("prop") ?: continue
            val etag = prop.text("getetag")
            val icsNodes = prop.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-data")
            val ics = if (icsNodes.length > 0) icsNodes.item(0).textContent else null
            if (ics != null) objects += RawCalendarObject(href, etag, ics)
        }
        return SyncResult(needsFullResync = false, token = newToken, objects = objects, removedHrefs = removed)
    }
}

enum class CollectionKind { EVENTS, TASK_LIST }

data class DiscoveredCalendar(
    val url: String,
    val displayName: String,
    val color: String?,
    val readOnly: Boolean,
    val isSubscription: Boolean,
    val subscriptionSourceUrl: String? = null,
    val kind: CollectionKind = CollectionKind.EVENTS,
)

data class RawCalendarObject(val href: String, val etag: String?, val icsText: String)

/** Battery/sync-window fix — see fetchEventsViaDirectGetConditional's doc comment.
 *  [notModified] true means the server returned 304: [objects] is always empty in that case and
 *  the caller should keep whatever it already has cached rather than treat this as "feed is now
 *  empty". [etag] is the value to store for next time (null when the fetch failed or 304'd with
 *  no etag echoed back — caller should keep the previous one it already has in that case). */
data class DirectGetResult(val notModified: Boolean, val etag: String?, val objects: List<RawCalendarObject>)

data class SyncResult(
    val needsFullResync: Boolean,
    val token: String?,
    val objects: List<RawCalendarObject>,
    val removedHrefs: List<String>,
)
