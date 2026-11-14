package com.brbrs.zaman.data.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

/** Flat assumed average speed for the straight-line estimate — a mix of urban/suburban driving,
 *  deliberately conservative (real roads are never as direct as a straight line). */
private const val ASSUMED_KMH = 40.0

/**
 * Best-effort travel-time estimate for an event with a location set (feature: travel-time
 * estimate). Deliberately scoped down from a "real" routing API: the suite has no Google/
 * Mapbox API key anywhere (Maps elsewhere in the suite use plain OSM tiles, no key), and adding
 * one just for this would be a real ongoing cost/dependency for a suite whose whole pitch is
 * free and self-hosted-friendly. Nominatim (OpenStreetMap, no API key — same service the suite's
 * other apps already use for place search) geocodes the two addresses, and the "travel time" is
 * then a straight-line (Haversine) distance divided by a flat assumed speed — NOT a real route,
 * so it ignores roads, traffic, and terrain entirely. EventEditScreen labels it accordingly
 * ("as the crow flies") rather than presenting it as a real driving-time estimate.
 *
 * Deliberately does NOT use Android's location APIs (no ACCESS_FINE_LOCATION permission exists
 * anywhere in this app, and this suite tries hard to avoid adding location permissions — see
 * Qarib's geofencing being the one deliberate exception, scoped to that app's actual purpose).
 * Instead it estimates from a fixed "starting point" address the user types once in Settings
 * (TravelPreferences.startingAddress) — e.g. home or their usual office.
 */
@Singleton
class TravelTimeEstimator @Inject constructor() {

    // A separate, plain client — never the app's main OkHttpClient, which carries Nextcloud auth
    // headers via LoginFlowV2Auth's interceptor. Those must never go anywhere near a third-party
    // host like Nominatim.
    private val client = OkHttpClient.Builder().build()
    private val cache = mutableMapOf<String, LatLon?>()

    /** Nominatim's usage policy requires a real identifying User-Agent, not a generic/default
     *  one, and caps anonymous usage at ~1 request/second -- fine for this, one-off lookups
     *  triggered by a user editing an event, never a batch. */
    private suspend fun geocode(address: String): LatLon? = withContext(Dispatchers.IO) {
        val key = address.trim().lowercase()
        if (key.isBlank()) return@withContext null
        cache[key]?.let { return@withContext it }
        if (cache.containsKey(key)) return@withContext null // cached "not found"
        runCatching {
            val url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&q=" +
                java.net.URLEncoder.encode(address, "UTF-8")
            val request = Request.Builder().url(url)
                .header("User-Agent", "Zaman/1.0 (+https://barburas.com; BarburasLab self-hosted calendar app)")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string().orEmpty()
                val arr = JSONArray(body)
                if (arr.length() == 0) null else {
                    val obj = arr.getJSONObject(0)
                    LatLon(obj.getString("lat").toDouble(), obj.getString("lon").toDouble())
                }
            }
        }.getOrNull().also { cache[key] = it }
    }

    private fun haversineKm(a: LatLon, b: LatLon): Double {
        val r = 6371.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return r * 2 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** @return a short label like "12 km, ~18 min (straight-line estimate)", or null if either
     *  address couldn't be geocoded. */
    suspend fun estimate(fromAddress: String, toAddress: String): String? {
        val from = geocode(fromAddress) ?: return null
        val to = geocode(toAddress) ?: return null
        val km = haversineKm(from, to)
        val minutes = (km / ASSUMED_KMH * 60).toInt().coerceAtLeast(1)
        val kmLabel = if (km < 10) "%.1f".format(km) else km.toInt().toString()
        return "$kmLabel km, ~$minutes min (straight-line estimate)"
    }
}
