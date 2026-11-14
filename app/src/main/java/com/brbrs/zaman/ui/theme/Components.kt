package com.brbrs.zaman.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brbrs.zaman.R

val RadiusMd = 16.dp
val RadiusLg = 20.dp
val SpaceGutter = 20.dp

/** The suite's standard glass card: translucent fill + hairline border, radius-md by default. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: androidx.compose.ui.unit.Dp = RadiusMd,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.border(BorderStroke(1.dp, glassBorder()), RoundedCornerShape(radius)),
        shape = RoundedCornerShape(radius),
        color = glassFill(),
        content = content,
    )
}

/** A small source badge for an item that originated in another suite app (e.g. KOTO, TAVLA). */
@Composable
fun SourceBadge(label: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = tint.copy(alpha = 0.12f),
    ) {
        Text(
            text = label,
            style = ZamanType.labelSmall,
            color = tint,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

/** Which logo [SourceLogo] should show for an item: its real sourceApp (KOTO/TAVLA, set at
 *  sync time) if it's one of those, otherwise VINCI when the event is a Zaman-native one the
 *  user linked to a Vinci contact — the only other way an event is "from" one of the suite apps. */
fun com.brbrs.zaman.data.local.EventEntity.effectiveSourceBadge(): String? =
    sourceApp ?: if (linkedContactUid != null) "VINCI" else null

/**
 * The small round suite-app logo (Koto/Tavla/Vinci's own app icon) shown before an item's
 * title when it originated in — or, for Vinci, is linked to — another app, instead of the
 * old text [SourceBadge]. Returns null (renders nothing) for a native Zaman item.
 */
@Composable
fun SourceLogo(sourceApp: String?, size: Dp = 16.dp) {
    val drawableRes = when (sourceApp) {
        "KOTO" -> R.drawable.ic_logo_koto
        "TAVLA" -> R.drawable.ic_logo_tavla
        "VINCI" -> R.drawable.ic_logo_vinci
        else -> return
    }
    Image(
        painter = painterResource(drawableRes),
        contentDescription = sourceApp,
        modifier = Modifier.size(size).clip(CircleShape),
    )
}

/** Calendar/category color stripe, matching the mockup's --stripe pattern. */
@Composable
fun ColorStripe(color: Color, height: androidx.compose.ui.unit.Dp = 1.dp) {
    Surface(color = color, modifier = Modifier) {}
}

/**
 * A calendar's user-picked cover photo, loaded from its private-storage path. Every calendar's
 * cover always lives at the SAME deterministic filename (derived from its calendar URL, not its
 * content — see CalendarsViewModel.setCoverImage), so re-picking a cover overwrites that exact
 * path. Coil's default memory/disk cache keys off the model (the File's path) alone, which would
 * happily keep serving the OLD bitmap for that path after a replace. Caching a file that's
 * already sitting on fast local disk buys nothing anyway, so caching is just turned off entirely
 * here rather than hand-rolling a cache-busting key.
 */
@Composable
fun CoverImage(path: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    coil.compose.AsyncImage(
        model = coil.request.ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
            .data(java.io.File(path))
            .memoryCachePolicy(coil.request.CachePolicy.DISABLED)
            .diskCachePolicy(coil.request.CachePolicy.DISABLED)
            .build(),
        contentDescription = contentDescription,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = modifier,
    )
}

/**
 * An attendee's real profile picture — feature: the single event screen shows actual contact
 * photos (when the matched Vinci contact has one), not a generic Vinci logo. Three cases, tried
 * in order: a decoded vCard PHOTO ([photoBase64], the common case for a Nextcloud-synced
 * contact); an external PHOTO URL ([photoUrl], loaded via Coil same as [CoverImage]); or, when
 * neither exists (including an attendee with no matching Vinci contact at all), an initials
 * circle — never a blank space, so every attendee chip has SOME visual anchor.
 */
@Composable
fun AttendeeAvatar(
    photoBase64: String?,
    photoUrl: String?,
    displayName: String?,
    size: Dp = 28.dp,
) {
    val bitmap = remember(photoBase64) {
        photoBase64?.let { encoded ->
            runCatching {
                val clean = encoded.filterNot { it.isWhitespace() }
                val bytes = android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        photoUrl != null -> coil.compose.AsyncImage(
            model = photoUrl,
            contentDescription = displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        else -> {
            val initial = displayName?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            Surface(
                shape = CircleShape,
                color = ZamanColors.AccentVinci.copy(alpha = 0.18f),
                modifier = Modifier.size(size),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(initial, style = ZamanType.labelSmall, color = ZamanColors.AccentVinci)
                }
            }
        }
    }
}
