package com.brbrs.zaman.data.deck

import com.brbrs.zaman.data.caldav.LoginFlowV2Auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tavla is a Nextcloud Deck client, and Deck is NOT CalDAV — a card's due
 * date has no calendar-home-set entry, so it can't be discovered or fetched
 * the way every other calendar in this app is. Deck exposes its own OCS
 * REST API instead (the same one Tavla itself talks to over WebDAV
 * credentials), so Zaman reads that API directly, read-only, rather than
 * inventing a sidecar file Tavla doesn't write — the real data already
 * exists at this address on the same Nextcloud account.
 *
 * Scope for v1: list boards → list stacks per board → surface every card
 * with a duedate set, read-only. No write-back (completing/rescheduling a
 * card from Zaman), no board/stack filtering UI yet — see README.
 */
@Singleton
class DeckApiClient @Inject constructor(
    private val http: OkHttpClient,
    private val auth: LoginFlowV2Auth,
) {
    suspend fun isConfigured(): Boolean = auth.currentServerUrl() != null && auth.basicAuthHeader() != null

    suspend fun fetchCardsWithDueDates(): List<DeckCardWithDueDate> {
        val server = auth.currentServerUrl() ?: return emptyList()
        val boards = getJsonArray("$server/index.php/apps/deck/api/v1.0/boards")
        val out = mutableListOf<DeckCardWithDueDate>()

        for (i in 0 until boards.length()) {
            val board = boards.getJSONObject(i)
            if (board.optBoolean("archived", false)) continue
            val boardId = board.getInt("id")
            val boardTitle = board.optString("title", "Board")

            // getOrNull() (not getOrElse { continue }) — a `continue` inside an inline
            // lambda needs Kotlin 2.2+, and this project pins 2.1.0.
            val stacks = runCatching {
                getJsonArray("$server/index.php/apps/deck/api/v1.0/boards/$boardId/stacks")
            }.getOrNull() ?: continue

            for (j in 0 until stacks.length()) {
                val stack = stacks.getJSONObject(j)
                val stackTitle = stack.optString("title", "")
                val cards = stack.optJSONArray("cards") ?: JSONArray()
                for (k in 0 until cards.length()) {
                    val card = cards.getJSONObject(k)
                    val dueString = card.optString("duedate", null) ?: continue
                    if (card.optBoolean("archived", false)) continue
                    if (card.optBoolean("done", false)) continue // Deck marks completion via `done`, not a status enum
                    val dueMillis = runCatching { parseDeckTimestamp(dueString) }.getOrNull() ?: continue
                    out += DeckCardWithDueDate(
                        cardId = card.getInt("id"),
                        title = card.optString("title", "(untitled card)"),
                        boardTitle = boardTitle,
                        stackTitle = stackTitle,
                        dueEpochMillis = dueMillis,
                    )
                }
            }
        }
        return out
    }

    private suspend fun getJsonArray(url: String): JSONArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Deck API GET failed: HTTP ${resp.code} ($url)" }
            val text = resp.body?.string().orEmpty()
            // Deck's own API returns a bare JSON array for boards/stacks (unlike the OCS-wrapped
            // endpoints elsewhere in Nextcloud), so no ocs.data unwrapping is needed here.
            JSONArray(text)
        }
    }

    private fun parseDeckTimestamp(raw: String): Long =
        runCatching { OffsetDateTime.parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }
            .getOrElse { Instant.parse(raw).toEpochMilli() }
}

data class DeckCardWithDueDate(
    val cardId: Int,
    val title: String,
    val boardTitle: String,
    val stackTitle: String,
    val dueEpochMillis: Long,
)
