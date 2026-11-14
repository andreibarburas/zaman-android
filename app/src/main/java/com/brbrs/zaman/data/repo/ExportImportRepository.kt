package com.brbrs.zaman.data.repo

import com.brbrs.zaman.data.local.CalendarDao
import com.brbrs.zaman.data.local.CalendarEntity
import com.brbrs.zaman.data.local.EventDao
import com.brbrs.zaman.data.local.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val EXPORT_FORMAT_VERSION = 1

/**
 * Suite-wide v2 milestone (already shipped for Vinci/Tavla/Koto): a full backup of everything
 * Zaman has cached locally — calendars + events — as one JSON file the user can save anywhere
 * and bring back later, independent of whether Nextcloud is reachable. org.json only, per the
 * suite's no-Retrofit/no-Gson rule (see build.gradle.kts).
 */
@Singleton
class ExportImportRepository @Inject constructor(
    private val calendarDao: CalendarDao,
    private val eventDao: EventDao,
) {
    suspend fun exportToJson(): String = withContext(Dispatchers.Default) {
        val calendars = calendarDao.allOnce()
        val events = eventDao.allOnce()

        val calendarsJson = JSONArray()
        calendars.forEach { c ->
            calendarsJson.put(
                JSONObject().apply {
                    put("url", c.url)
                    put("displayName", c.displayName)
                    put("colorHex", c.colorHex)
                    put("readOnly", c.readOnly)
                    put("isSubscription", c.isSubscription)
                    put("enabled", c.enabled)
                    put("syncToken", c.syncToken)
                    put("sourceApp", c.sourceApp)
                    put("isTaskList", c.isTaskList)
                }
            )
        }

        val eventsJson = JSONArray()
        events.forEach { e ->
            eventsJson.put(
                JSONObject().apply {
                    put("href", e.href)
                    put("calendarUrl", e.calendarUrl)
                    put("etag", e.etag)
                    put("uid", e.uid)
                    put("summary", e.summary)
                    put("description", e.description)
                    put("location", e.location)
                    put("startEpochMillis", e.startEpochMillis)
                    put("endEpochMillis", e.endEpochMillis)
                    put("allDay", e.allDay)
                    put("rrule", e.rrule)
                    put("recurrenceIdEpochMillis", e.recurrenceIdEpochMillis)
                    put("icsRaw", e.icsRaw)
                    put("sourceApp", e.sourceApp)
                }
            )
        }

        JSONObject().apply {
            put("app", "zaman")
            put("formatVersion", EXPORT_FORMAT_VERSION)
            put("exportedAtEpochMillis", System.currentTimeMillis())
            put("calendars", calendarsJson)
            put("events", eventsJson)
        }.toString(2)
    }

    /** @return how many calendars/events were imported, for a confirmation toast. */
    suspend fun importFromJson(json: String): ImportResult = withContext(Dispatchers.Default) {
        val root = JSONObject(json)
        require(root.optString("app") == "zaman") { "This file wasn't exported from Zaman." }

        val calendarsJson = root.optJSONArray("calendars") ?: JSONArray()
        val calendars = (0 until calendarsJson.length()).map { i ->
            val o = calendarsJson.getJSONObject(i)
            CalendarEntity(
                url = o.getString("url"),
                displayName = o.getString("displayName"),
                colorHex = o.optStringOrNull("colorHex"),
                readOnly = o.getBoolean("readOnly"),
                isSubscription = o.getBoolean("isSubscription"),
                enabled = o.getBoolean("enabled"),
                syncToken = o.optStringOrNull("syncToken"),
                sourceApp = o.optStringOrNull("sourceApp"),
                isTaskList = o.optBoolean("isTaskList", false),
            )
        }

        val eventsJson = root.optJSONArray("events") ?: JSONArray()
        val events = (0 until eventsJson.length()).map { i ->
            val o = eventsJson.getJSONObject(i)
            EventEntity(
                href = o.getString("href"),
                calendarUrl = o.getString("calendarUrl"),
                etag = o.optStringOrNull("etag"),
                uid = o.getString("uid"),
                summary = o.getString("summary"),
                description = o.optStringOrNull("description"),
                location = o.optStringOrNull("location"),
                startEpochMillis = o.getLong("startEpochMillis"),
                endEpochMillis = if (o.isNull("endEpochMillis")) null else o.getLong("endEpochMillis"),
                allDay = o.getBoolean("allDay"),
                rrule = o.optStringOrNull("rrule"),
                recurrenceIdEpochMillis = if (o.isNull("recurrenceIdEpochMillis")) null else o.getLong("recurrenceIdEpochMillis"),
                icsRaw = o.getString("icsRaw"),
                sourceApp = o.optStringOrNull("sourceApp"),
            )
        }

        // Calendars first (events FK onto calendarUrl), REPLACE so a re-import of the same
        // backup overwrites cleanly rather than silently keeping the old rows (insertIgnoring).
        calendarDao.upsertAll(calendars)
        eventDao.upsertAll(events)

        ImportResult(calendarCount = calendars.size, eventCount = events.size)
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name, null)
}

data class ImportResult(val calendarCount: Int, val eventCount: Int)
