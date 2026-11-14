package com.brbrs.zaman.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.brbrs.zaman.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.Locale

/** One row the widget actually draws — either a day separator or a single event, interleaved
 *  in display order so the LazyColumn below can just walk one flat list. */
private sealed class WidgetRow {
    // headingBitmap/widthDp/heightDp: the date label drawn with the app's actual DM Serif
    // Display font (see renderHeadingBitmap's doc comment) rather than Glance's limited set of
    // system font families — rendered once here, up front, since Glance content can't do its
    // own Canvas work inside provideContent. plainText is the same label as a plain string, used
    // if the Canvas render ever fails — see buildRows — so a day group still gets *some* header
    // instead of silently vanishing.
    data class DayHeader(val headingBitmap: Bitmap?, val widthDp: Dp, val heightDp: Dp, val plainText: String) : WidgetRow()
    data class EventRow(
        val href: String,
        val occurrenceStartMillis: Long,
        val occurrenceEndMillis: Long?,
        val summary: String,
        val allDay: Boolean,
        val location: String?,
        val colorHex: String?,
        val coverBitmap: Bitmap?,
    ) : WidgetRow()
}

/**
 * Home-screen agenda widget — the next couple of weeks' events, grouped by day, each row
 * carrying its own calendar's color and (when set) cover-photo thumbnail, same as the app's own
 * agenda rows. Tapping a row deep-links into Zaman's event-detail popup (see ZamanNavHost's
 * EVENT_DETAIL_ROUTE deep link) exactly the way an in-app tap does now.
 *
 * Glance renders this into RemoteViews, which only supports a small, fixed subset of layouts —
 * there's no live Flow collection inside it, so [provideGlance] takes one snapshot of the data
 * per render. Real-time-ish freshness instead comes from [WidgetRefresher] being called at the
 * end of every sync, plus Android's own periodic update schedule (zaman_agenda_widget_info.xml).
 */
class ZamanAgendaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Wrapped end-to-end: if anything in here throws (bad calendar data, a Canvas/font
        // hiccup in renderHeadingBitmap, whatever), the widget must still reach provideContent —
        // otherwise it's stuck showing zaman_agenda_widget_info's initialLayout (the loading
        // spinner) forever, with no way for the user to tell something went wrong. Falling back
        // to an empty list at worst shows "No upcoming events" instead of hanging.
        val rows = runCatching { buildRows(context, id) }.getOrDefault(emptyList())
        provideContent {
            WidgetAgendaContent(context, rows)
        }
    }

    private suspend fun buildRows(context: Context, id: GlanceId): List<WidgetRow> {
        val repository = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).calendarRepository()
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val startMillis = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = now.toLocalDate().plusDays(14).atStartOfDay(zone).toInstant().toEpochMilli()

        // This widget INSTANCE's own calendar selection (see WidgetCalendarSelection.kt) — null
        // means "never configured, show everything", matching a widget placed before this
        // feature existed. Deliberately reads repository.observeEventsInRange (unfiltered), not
        // observeVisibleEventsInRange — the in-app drawer's global visibility is a separate
        // concern from what any one widget instance was set up to show.
        val selectedUrls = runCatching { getSelectedCalendarUrls(context, id) }.getOrNull()

        val entries = runCatching { repository.observeEventsInRange(startMillis, endMillis).first() }
            .getOrDefault(emptyList())
            .let { all -> if (selectedUrls == null) all else all.filter { it.event.calendarUrl in selectedUrls } }
            .sortedBy { it.occurrenceStartMillis }
            .take(40)
        val calendars = runCatching { repository.observeCalendars().first() }.getOrDefault(emptyList())
        val colorByUrl = calendars.associate { it.url to it.colorHex }
        val coverByUrl = calendars.associate { it.url to it.coverImagePath }

        val rows = mutableListOf<WidgetRow>()
        // Decoded ONCE per calendar (not per event row — several events a day can share the same
        // calendar's cover) and downsampled to the actual 26dp display size, same bounds-first
        // inSampleSize technique as FrostedCoverBackground/TileFrostedCover elsewhere in the app.
        // Previously this decoded every cover photo at its full original resolution for every
        // single row, which — depending on which calendars happened to fall in the visible
        // window — could push the widget's total RemoteViews bitmap memory past Android's hard
        // cap (~36MB), surfacing as the host's generic "Can't show content" error.
        val coverBitmapCache = mutableMapOf<String, Bitmap?>()
        fun decodedCoverFor(path: String): Bitmap? = coverBitmapCache.getOrPut(path) {
            runCatching { decodeSampledBitmap(path, targetEdgePx = (26 * context.resources.displayMetrics.density).toInt()) }.getOrNull()
        }
        var lastDate: LocalDate? = null
        entries.forEach { entry ->
            val date = Instant.ofEpochMilli(entry.occurrenceStartMillis).atZone(zone).toLocalDate()
            if (date != lastDate) {
                val weekNumber = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                val label = "${fullDateFormatter.format(date)} | W$weekNumber"
                // Falls back to a plain-text header (rendered by DayHeaderRow with Glance's own
                // Text, no custom font) rather than letting a Canvas/font failure take down the
                // whole widget — see provideGlance's own doc comment above.
                val rendered = runCatching { renderHeadingBitmap(context, label) }.getOrNull()
                rows += WidgetRow.DayHeader(rendered?.first, rendered?.second ?: 0.dp, rendered?.third ?: 0.dp, label)
                lastDate = date
            }
            // Decoding the cover photo here (once per render, off the UI thread — provideGlance
            // already runs on a background dispatcher) rather than inside the composable below:
            // Glance content isn't allowed to do its own file I/O, every Image needs an
            // already-resolved ImageProvider by the time provideContent runs.
            val bitmap = coverByUrl[entry.event.calendarUrl]?.let { path -> decodedCoverFor(path) }
            rows += WidgetRow.EventRow(
                href = entry.event.href,
                occurrenceStartMillis = entry.occurrenceStartMillis,
                occurrenceEndMillis = entry.occurrenceEndMillis,
                summary = entry.event.summary,
                allDay = entry.event.allDay,
                location = entry.event.location?.takeIf { it.isNotBlank() },
                colorHex = colorByUrl[entry.event.calendarUrl],
                coverBitmap = bitmap,
            )
        }
        return rows
    }
}

class ZamanAgendaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ZamanAgendaWidget()
}

// Same "glass card" aesthetic as the in-app agenda rows (see TodayScreen's AgendaRow /
// ZamanColors.GlassFillDark+GlassBorderDark) — Glance can't read app theme/Compose state, so
// these are the same fixed dark-mode values the app itself falls back to, hard-coded here.
// Frosted-glass look: a translucent tint over whatever's behind the widget (home screen
// wallpaper) rather than a solid fill — Glance/RemoteViews has no real blur, so translucency is
// the practical stand-in. Text is explicit light gray/white (ColorProvider wraps a fixed Color
// for Glance's TextStyle — safe, unlike GlanceTheme's dynamic tokens) since there's no guarantee
// what a default/unset text color resolves to against a dark, partly-transparent background.
private val WidgetBackground = Color(0x992A2E35)
private val TextPrimary = ColorProvider(Color(0xFFF5F6F8))
private val TextSecondary = ColorProvider(Color(0xFFB7BCC4))

// The app's own Inter Tight (see ui/theme/Type.kt) can't be loaded into Glance's native Text:
// its TextStyle.fontFamily is androidx.glance.text.FontFamily, whose constructor is internal —
// only the four predefined system families below are publicly reachable, a real RemoteViews/
// Glance limitation rather than a missing import. SansSerif is the closest available match for
// body copy. The one heading line (the date label) instead renders the real DM Serif Display
// font onto a bitmap — see [renderHeadingBitmap] — since it's a single short string where that
// extra cost is worth it, unlike every event row's title.
private val BodyFont = FontFamily.SansSerif

@Composable
private fun WidgetAgendaContent(context: Context, rows: List<WidgetRow>) {
    Box(
        modifier = GlanceModifier.fillMaxSize().background(WidgetBackground).padding(10.dp),
    ) {
        if (rows.isEmpty()) {
            Text(
                "No upcoming events",
                style = TextStyle(color = TextSecondary, fontSize = 13.sp, fontFamily = BodyFont),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                rows.forEachIndexed { index, row ->
                    item(itemId = index.toLong()) {
                        when (row) {
                            is WidgetRow.DayHeader -> DayHeaderRow(row)
                            is WidgetRow.EventRow -> EventRow(context, row)
                        }
                    }
                }
            }
        }
    }
}

private val fullDateFormatter = DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", Locale.getDefault())

/**
 * Bounds-first, downsampled decode — same inSampleSize technique as FrostedCoverBackground/
 * TileFrostedCover (ui/theme/Background.kt). Decoding a cover photo at its full original
 * resolution (what this widget used to do) is fine for the one or two covers an in-app screen
 * shows, but a RemoteViews widget can easily carry dozens of event rows at once, and Android caps
 * a single widget update's total bitmap memory — see the doc comment on [decodedCoverFor]-style
 * usage in buildRows.
 */
private fun decodeSampledBitmap(path: String, targetEdgePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    val opts = BitmapFactory.Options().apply {
        var s = 1
        while (bounds.outHeight / (s * 2) >= targetEdgePx && bounds.outWidth / (s * 2) >= targetEdgePx) s *= 2
        inSampleSize = s
    }
    return BitmapFactory.decodeFile(path, opts)
}

/**
 * Draws [text] with the app's real DM Serif Display font onto a bitmap, since Glance's native
 * Text can only pick from four generic system font families (see the doc comment on
 * [BodyFont] above) — there's no public API to point it at a bundled .ttf. This is
 * the documented workaround: load the typeface, measure+draw it with a Canvas/TextPaint, then
 * show the result as a plain Image. Used only for this one heading line, not event body text —
 * a bitmap can't reflow for a launcher's font-scale setting the way real Text does, so reserving
 * it for a single short label (rather than every row's title) keeps that accessibility cost small.
 * Returns the bitmap plus its size already converted to dp, so the caller can size the Glance
 * Image to exactly match (otherwise a raw pixel bitmap has no inherent dp size in RemoteViews).
 */
private fun renderHeadingBitmap(context: Context, text: String): Triple<Bitmap, Dp, Dp> {
    val density = context.resources.displayMetrics.density
    val typeface = runCatching {
        ResourcesCompat.getFont(context, R.font.dm_serif_display_regular)
    }.getOrNull() ?: Typeface.SERIF

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        this.textSize = 21.sp.value * density
        this.color = android.graphics.Color.rgb(0xF5, 0xF6, 0xF8)
    }
    val textWidth = paint.measureText(text)
    val metrics = paint.fontMetrics
    val widthPx = textWidth.coerceAtLeast(1f)
    val heightPx = (metrics.descent - metrics.ascent).coerceAtLeast(1f)

    val bitmap = Bitmap.createBitmap(
        kotlin.math.ceil(widthPx).toInt(),
        kotlin.math.ceil(heightPx).toInt(),
        Bitmap.Config.ARGB_8888,
    )
    Canvas(bitmap).drawText(text, 0f, -metrics.ascent, paint)

    return Triple(bitmap, (widthPx / density).dp, (heightPx / density).dp)
}

@Composable
private fun DayHeaderRow(row: WidgetRow.DayHeader) {
    // "Monday, 09 November 2026 | W45" — full weekday/month name plus the ISO week-of-year, drawn
    // with the real heading font (see renderHeadingBitmap), in place of the earlier compact
    // "MON 9" block. Falls back to plain Glance Text (system SansSerif) if the Canvas render
    // failed for this date — see buildRows — rather than showing nothing at all for that day.
    Box(
        modifier = GlanceModifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
    ) {
        if (row.headingBitmap != null) {
            Image(
                provider = ImageProvider(row.headingBitmap),
                contentDescription = null,
                modifier = GlanceModifier.width(row.widthDp).height(row.heightDp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = row.plainText,
                style = TextStyle(color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = BodyFont),
            )
        }
    }
}

@Composable
private fun EventRow(context: Context, row: WidgetRow.EventRow) {
    val stripe = runCatching { Color(android.graphics.Color.parseColor(row.colorHex)) }.getOrDefault(Color(0xFF066EA8))
    val zone = ZoneId.systemDefault()
    val timeLabel = when {
        row.allDay -> "All day"
        row.occurrenceEndMillis != null -> {
            val fmt = DateTimeFormatter.ofPattern("HH:mm")
            val start = fmt.format(Instant.ofEpochMilli(row.occurrenceStartMillis).atZone(zone))
            val end = fmt.format(Instant.ofEpochMilli(row.occurrenceEndMillis).atZone(zone))
            if (end != start) "$start – $end" else start
        }
        else -> DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(row.occurrenceStartMillis).atZone(zone))
    }

    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse("zaman://event/${Uri.encode(row.href)}"))
        .setPackage(context.packageName)

    // The real gap between cards: a plain outer Box with no background, just vertical padding —
    // on the card itself, padding-then-background apparently doesn't read as a true margin in
    // Glance's RemoteViews translation (it grew the card taller instead of spacing cards apart),
    // so the margin now comes from this separate, background-less wrapper instead.
    Box(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // CenterVertically matters here, not just cosmetics: the text column's height now varies
        // (one line vs. two, once a location is present), but the stripe/cover-image children
        // have their own fixed heights — left at Row's default top alignment, they'd sit flush
        // with the title while the text block grew downward past them, reading as "not aligned".
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(R.drawable.widget_card_bg))
                .clickable(actionStartActivity(openIntent)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier.width(3.dp).height(if (row.location != null) 56.dp else 44.dp)
                    .background(stripe).cornerRadius(2.dp),
            ) {}
            Spacer(modifier = GlanceModifier.width(10.dp))
            if (row.coverBitmap != null) {
                Image(
                    provider = ImageProvider(row.coverBitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.size(26.dp).cornerRadius(7.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = GlanceModifier.width(10.dp))
            }
            Column(modifier = GlanceModifier.padding(vertical = 10.dp)) {
                Text(
                    text = row.summary,
                    style = TextStyle(color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = BodyFont),
                    maxLines = 1,
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = timeLabel,
                    style = TextStyle(color = TextSecondary, fontSize = 12.sp, fontFamily = BodyFont),
                )
                row.location?.let {
                    Spacer(modifier = GlanceModifier.height(1.dp))
                    Text(
                        text = it,
                        style = TextStyle(color = TextSecondary, fontSize = 11.sp, fontFamily = BodyFont),
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = GlanceModifier.width(10.dp))
        }
    }
}
