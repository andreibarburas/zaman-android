package com.brbrs.zaman.data.carddav

import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
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

private val XML_MEDIA = "application/xml; charset=utf-8".toMediaType()

data class VinciContact(
    val uid: String,
    val displayName: String,
    val emails: List<String> = emptyList(),
    /** Raw base64 payload of a vCard PHOTO property (ENCODING=b / data: URI forms), undecoded —
     *  see ui/theme/Components.kt's AttendeeAvatar for where this actually gets turned into a
     *  Bitmap. Null when the contact has no PHOTO at all, or it's the (rarer) external-URL form,
     *  in which case [photoUrl] is set instead. */
    val photoBase64: String? = null,
    /** An external http(s) PHOTO;VALUE=uri: reference — loaded via Coil (AsyncImage) rather than
     *  decoded locally, same as CoverImage elsewhere. Most Nextcloud-synced contact photos are
     *  the inline base64 form instead ([photoBase64]), this is the fallback for the other case. */
    val photoUrl: String? = null,
)

/**
 * Vinci's contacts live in the same Nextcloud account's address book — this is the "deep
 * integration with Vinci" the suite was built around, without needing Vinci's own app code:
 * Zaman reads the same CardDAV data directly, the same way it already reads calendars.
 * Linking an event to a contact here is a Zaman-local annotation (see EventEntity.linkedContactUid)
 * — it does not write an ATTENDEE back, so it can never corrupt an event another app wrote.
 */
class CardDavContactsClient(
    private val http: OkHttpClient,
    private val auth: LoginFlowV2Auth,
) {
    suspend fun fetchContacts(): List<VinciContact> = withContext(Dispatchers.IO) {
        val server = auth.currentServerUrl() ?: return@withContext emptyList()
        val loginName = auth.currentLoginName() ?: return@withContext emptyList()
        val addressBookUrls = discoverAddressBooks(server, loginName)
        addressBookUrls.flatMap { fetchContactsFrom(server, it) }
    }

    private fun discoverAddressBooks(serverUrl: String, loginName: String): List<String> {
        val home = "$serverUrl/remote.php/dav/addressbooks/users/$loginName/"
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:resourcetype/></d:prop>
            </d:propfind>
        """.trimIndent()
        val req = Request.Builder().url(home).method("PROPFIND", body.toRequestBody(XML_MEDIA))
            .header("Depth", "1").build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val xml = resp.body?.string().orEmpty()
                val doc = parseDoc(xml)
                val responses = doc.getElementsByTagNameNS("DAV:", "response")
                (0 until responses.length).mapNotNull { i ->
                    val r = responses.item(i) as? Element ?: return@mapNotNull null
                    val href = r.child("href")?.textContent ?: return@mapNotNull null
                    val isAddressBook = r.getElementsByTagNameNS("urn:ietf:params:xml:ns:carddav", "addressbook").length > 0
                    val resolved = URI(home).resolve(href).toString()
                    if (isAddressBook && resolved.trimEnd('/') != home.trimEnd('/')) resolved else null
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Only UID/FN/EMAIL (via <c:prop name="…"/> children of <c:address-data>, which per RFC 6352
     * §10.4 asks the server to return ONLY those vCard properties) — deliberately NOT PHOTO.
     * With a few hundred to a few thousand contacts, asking for every contact's full vCard
     * including an embedded profile photo turns this one request into tens of MB and a 10+
     * second stall before the list (and therefore attendee-email matching) is usable at all —
     * see fetchContactPhoto for the actual photo, fetched lazily per-contact only once a real
     * ATTENDEE match needs it.
     */
    private fun fetchContactsFrom(serverUrl: String, addressBookUrl: String): List<VinciContact> {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:addressbook-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
              <d:prop>
                <d:getetag/>
                <c:address-data>
                  <c:prop name="UID"/>
                  <c:prop name="FN"/>
                  <c:prop name="EMAIL"/>
                </c:address-data>
              </d:prop>
            </c:addressbook-query>
        """.trimIndent()
        val req = Request.Builder().url(addressBookUrl)
            .method("REPORT", body.toRequestBody(XML_MEDIA))
            .header("Depth", "1").build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val xml = resp.body?.string().orEmpty()
                val doc = parseDoc(xml)
                val responses = doc.getElementsByTagNameNS("DAV:", "response")
                (0 until responses.length).mapNotNull { i ->
                    val r = responses.item(i) as? Element ?: return@mapNotNull null
                    val vcard = r.getElementsByTagNameNS("urn:ietf:params:xml:ns:carddav", "address-data")
                        .item(0)?.textContent ?: return@mapNotNull null
                    parseVCard(vcard)
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Fetches ONE contact's PHOTO by UID — a server-side filtered addressbook-query (not a scan
     * of the bulk list, which no longer carries photo data at all — see fetchContactsFrom), so
     * this stays cheap (one small response) regardless of how large the address book is. Called
     * lazily, only for an attendee a user is actually looking at (see ContactsRepository.fetchPhoto),
     * not for all 1283 contacts up front. Tries each discovered address book in turn; most
     * accounts only have one, so this is almost always a single request.
     */
    suspend fun fetchContactPhoto(contactUid: String): VinciContact? = withContext(Dispatchers.IO) {
        val server = auth.currentServerUrl() ?: return@withContext null
        val loginName = auth.currentLoginName() ?: return@withContext null
        val escapedUid = contactUid.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:addressbook-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:carddav">
              <d:prop><d:getetag/><c:address-data/></d:prop>
              <c:filter>
                <c:prop-filter name="UID">
                  <c:text-match match-type="equals">$escapedUid</c:text-match>
                </c:prop-filter>
              </c:filter>
            </c:addressbook-query>
        """.trimIndent()
        for (addressBookUrl in discoverAddressBooks(server, loginName)) {
            val req = Request.Builder().url(addressBookUrl)
                .method("REPORT", body.toRequestBody(XML_MEDIA))
                .header("Depth", "1").build()
            val found = runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val xml = resp.body?.string().orEmpty()
                    val doc = parseDoc(xml)
                    val responses = doc.getElementsByTagNameNS("DAV:", "response")
                    (0 until responses.length).firstNotNullOfOrNull { i ->
                        val r = responses.item(i) as? Element ?: return@firstNotNullOfOrNull null
                        val vcard = r.getElementsByTagNameNS("urn:ietf:params:xml:ns:carddav", "address-data")
                            .item(0)?.textContent ?: return@firstNotNullOfOrNull null
                        parseVCard(vcard)?.takeIf { it.uid == contactUid }
                    }
                }
            }.getOrNull()
            if (found != null) return@withContext found
        }
        null
    }

    /** Minimal vCard reader — UID, FN, EMAIL (any number of them, any TYPE= params — needed to
     *  match a calendar ATTENDEE's mailto: address back to a Vinci contact, see
     *  util/ExternalApps.kt's findByEmail), and now PHOTO (the attendee's actual profile picture,
     *  feature: single event screen). Unfolds continuation lines first — PHOTO's base64 payload
     *  is the one property here actually long enough to get RFC 6350-folded across many lines (a
     *  line starting with a single space/tab continues the previous one); UID/FN/EMAIL never
     *  needed that before because they're always short enough to fit on one line. */
    private fun parseVCard(vcard: String): VinciContact? {
        var uid: String? = null
        var fn: String? = null
        val emails = mutableListOf<String>()
        var photoBase64: String? = null
        var photoUrl: String? = null
        unfoldVCardLines(vcard).forEach { line ->
            when {
                line.startsWith("UID:") -> uid = line.removePrefix("UID:").trim()
                line.startsWith("FN:") -> fn = line.removePrefix("FN:").trim()
                // "EMAIL:foo@bar.com" or "EMAIL;TYPE=HOME,INTERNET:foo@bar.com" — the address is
                // always everything after the FIRST colon, params (if any) are before it.
                line.startsWith("EMAIL") && line.contains(":") ->
                    line.substringAfter(":").trim().takeIf { it.isNotEmpty() }?.let { emails += it }
                // PHOTO comes in three shapes in practice: vCard 3.0's "PHOTO;ENCODING=b;TYPE=JPEG:<base64>"
                // (raw base64, no prefix), vCard 4.0's "PHOTO:data:image/jpeg;base64,<base64>" (a
                // data: URI — take everything after the comma), or a bare external URL
                // ("PHOTO;VALUE=uri:https://…", loaded via Coil instead of decoded locally).
                line.startsWith("PHOTO") && line.contains(":") -> {
                    val value = line.substringAfter(":").trim()
                    when {
                        value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true) ->
                            photoUrl = value
                        value.startsWith("data:") && value.contains(",") ->
                            photoBase64 = value.substringAfter(",").takeIf { it.isNotEmpty() }
                        value.isNotEmpty() -> photoBase64 = value
                    }
                }
            }
        }
        val id = uid ?: return null
        return VinciContact(
            uid = id,
            displayName = fn ?: "(unnamed contact)",
            emails = emails,
            photoBase64 = photoBase64,
            photoUrl = photoUrl,
        )
    }

    /** RFC 6350 line unfolding: a line starting with exactly one leading space or tab is a
     *  continuation of the previous line (that one leading char is not part of the content).
     *  Needed before any property parsing, not just PHOTO's — but PHOTO's base64 payload is the
     *  one that actually gets split across lines in practice at the ~75-character fold width. */
    private fun unfoldVCardLines(vcard: String): List<String> {
        val raw = vcard.split("\r\n", "\n")
        val unfolded = mutableListOf<String>()
        for (line in raw) {
            if (unfolded.isNotEmpty() && (line.startsWith(" ") || line.startsWith("\t"))) {
                unfolded[unfolded.size - 1] = unfolded.last() + line.substring(1)
            } else {
                unfolded += line
            }
        }
        return unfolded
    }

    private fun parseDoc(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(org.xml.sax.InputSource(StringReader(xml)))

    private fun Element.child(localName: String): Element? {
        val nodes = getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) }
            .filterIsInstance<Element>()
            .firstOrNull { it.parentNode === this }
    }
}
