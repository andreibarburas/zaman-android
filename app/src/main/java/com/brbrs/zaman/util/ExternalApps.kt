package com.brbrs.zaman.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.brbrs.zaman.data.carddav.VinciContact

/**
 * Opens Vinci's contact-detail screen for [contactUid] — same plain ACTION_VIEW on Vinci's own
 * deep-link scheme the "Linked contact" row in EventEditScreen already uses (vinci://contact/…),
 * and deliberately with NO extra intent flags, matching that known-working call exactly —
 * FLAG_ACTIVITY_NEW_TASK isn't needed when starting from an Activity Context (LocalContext.current
 * inside a Composable always is one here) and was actually the bug: it was causing taps to appear
 * to do nothing on some launch-mode/task-affinity combinations. A no-op (caught, not crashed) if
 * Vinci isn't installed on this device.
 */
fun openVinciContact(context: Context, contactUid: String) {
    // Temporary diagnostic — filter logcat for "ZamanVinciLink" to confirm whether a tap actually
    // reaches this function and whether startActivity succeeds or throws. Remove once the
    // attendee-tap-does-nothing issue is actually root-caused.
    android.util.Log.d("ZamanVinciLink", "openVinciContact called, uid=$contactUid")
    val result = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("vinci://contact/$contactUid")))
    }
    android.util.Log.d("ZamanVinciLink", "startActivity result: ${if (result.isSuccess) "launched" else "failed: ${result.exceptionOrNull()}"}")
    if (result.isFailure) {
        runCatching {
            android.widget.Toast.makeText(context, "Couldn't open Vinci for this contact", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Matches an attendee's email against the Vinci contacts list — case-insensitive, since vCard
 * EMAIL values and calendar ATTENDEE mailto: values aren't guaranteed the same case. Feature:
 * tapping an attendee (EventDetailScreen/EventEditScreen) opens their Vinci profile directly when
 * a match exists; attendees with no matching contact just aren't tappable.
 */
fun List<VinciContact>.findByEmail(email: String): VinciContact? {
    val needle = email.trim().lowercase()
    if (needle.isEmpty()) return null
    return firstOrNull { contact -> contact.emails.any { it.trim().lowercase() == needle } }
}

/**
 * Opens whatever navigation app is installed to get directions to [address] — a plain geo: query
 * intent, which every real navigation app (Google Maps, Waze, HERE, OsmAnd, …) registers an
 * intent-filter for, so Android's own chooser (or a direct launch, if only one app qualifies)
 * picks among whatever the user actually has — this deliberately does NOT hardcode "Google Maps".
 *
 * Fires startActivity directly and only falls back to a Maps web-search URL (opens in any
 * browser) if that actually throws ActivityNotFoundException — it deliberately does NOT
 * pre-check with PackageManager.resolveActivity() first. On Android 11+ (API 30),
 * resolveActivity()/queryIntentActivities() are subject to package-visibility restrictions and
 * silently return null for another app's intent-filter unless it's visibility-exempt or declared
 * in a <queries> manifest block (now added — see AndroidManifest.xml) — without that, the old
 * resolveActivity() gate could see Google Maps (often visibility-exempt) but miss a perfectly
 * installed Waze/HERE/OsmAnd and fall straight to the web-search fallback instead of actually
 * opening it. Calling startActivity directly sidesteps the whole visibility question: launching
 * an implicit intent a user action explicitly asked for was never restricted, only introspecting
 * the list of candidates was.
 */
fun openNavigationTo(context: Context, address: String) {
    val query = Uri.encode(address)
    val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query"))
    val launched = runCatching { context.startActivity(geoIntent) }.isSuccess
    if (!launched) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$query")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}
