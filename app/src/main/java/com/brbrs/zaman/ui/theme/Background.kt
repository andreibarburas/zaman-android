package com.brbrs.zaman.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Direct port of Tavla's ScreenBackground pattern (see ui/components/Shared.kt there), so every
 * Zaman screen gets the same subtle app-wide gradient the rest of the BarburasLab suite uses —
 * previously Zaman screens just sat on MaterialTheme's flat background color. A per-calendar
 * screen (the Edit Event screen) can tint toward that calendar's own color or cover photo instead,
 * frosted, exactly like Tavla tints a card screen toward its board.
 */
@Composable
fun ScreenBackground(
    modifier: Modifier = Modifier,
    calendarColorHex: String? = null,
    coverImagePath: String? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val isDark = LocalZamanIsDark.current
    Box(modifier = modifier.fillMaxSize()) {
        when {
            coverImagePath != null -> FrostedCoverBackground(coverImagePath, isDark)
            calendarColorHex != null -> CalendarColorBackground(
                runCatching { Color(android.graphics.Color.parseColor(calendarColorHex)) }
                    .getOrDefault(if (isDark) ZamanColors.AccentDark else ZamanColors.AccentLight),
                isDark,
            )
            else -> DefaultAppBackground(isDark)
        }
        content()
    }
}

@Composable
private fun DefaultAppBackground(isDark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDark) Brush.verticalGradient(0f to ZamanColors.BgDark, 0.45f to ZamanColors.BgSurfaceDark, 1f to ZamanColors.BgGradientEndDark)
                else Brush.verticalGradient(0f to ZamanColors.BgLight, 0.5f to ZamanColors.BgSurfaceLight, 1f to ZamanColors.BgGradientEndLight)
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(
                            (if (isDark) ZamanColors.AccentDark else ZamanColors.AccentLight).copy(alpha = if (isDark) 0.16f else 0.10f),
                            Color.Transparent,
                        ),
                        radius = if (isDark) 650f else 750f,
                    )
                )
        )
    }
}

/** Same base gradient as [DefaultAppBackground], but the glow is tinted to the calendar's own color. */
@Composable
private fun CalendarColorBackground(color: Color, isDark: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isDark) Brush.verticalGradient(0f to ZamanColors.BgDark, 0.45f to ZamanColors.BgSurfaceDark, 1f to ZamanColors.BgGradientEndDark)
                else Brush.verticalGradient(0f to ZamanColors.BgLight, 0.5f to ZamanColors.BgSurfaceLight, 1f to ZamanColors.BgGradientEndLight)
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = if (isDark) 0.38f else 0.22f), Color.Transparent),
                        radius = if (isDark) 900f else 1000f,
                    )
                )
        )
    }
}

/**
 * A calendar's cover image, blurred and tinted so it reads as a frosted pane behind the edit form
 * rather than a photo competing with it — direct port of Tavla's FrostedCoverBackground. True
 * gaussian blur ([Modifier.blur]) only renders on API 31+; below that we decode the source at a
 * tiny resolution and let the upscale itself stand in for the blur.
 */
@Composable
private fun FrostedCoverBackground(path: String, isDark: Boolean) {
    val blurSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val targetEdge = if (blurSupported) 480 else 48
    val bitmap = remember(path, targetEdge) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            val opts = android.graphics.BitmapFactory.Options().apply {
                var s = 1
                while (bounds.outHeight / (s * 2) >= targetEdge && bounds.outWidth / (s * 2) >= targetEdge) s *= 2
                inSampleSize = s
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        }.getOrNull()
    }

    Box(modifier = Modifier.fillMaxSize().background(if (isDark) ZamanColors.BgDark else ZamanColors.BgLight)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = if (blurSupported) FilterQuality.High else FilterQuality.Low,
                modifier = Modifier
                    .fillMaxSize()
                    .let { if (blurSupported) it.blur(60.dp) else it },
            )
        }
        // Tints the blurred photo to match the rest of the UI and keeps foreground text legible.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isDark) {
                        Brush.verticalGradient(
                            listOf(
                                ZamanColors.BgDark.copy(alpha = 0.72f),
                                ZamanColors.BgSurfaceDark.copy(alpha = 0.78f),
                                ZamanColors.BgGradientEndDark.copy(alpha = 0.82f),
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                ZamanColors.BgLight.copy(alpha = 0.78f),
                                ZamanColors.BgSurfaceLight.copy(alpha = 0.72f),
                                ZamanColors.BgGradientEndLight.copy(alpha = 0.82f),
                            )
                        )
                    }
                )
        )
    }
}

/**
 * Row/tile-scale sibling of [ScreenBackground] — same cover-or-color-glow recipe, but sized down
 * to sit behind one calendar row in a list (the left-hand "which calendars show" drawer), direct
 * port of Tavla's BoardTileBackground/TileFrostedCover (ui/components/Shared.kt there), which is
 * what "same menu style we used in Tavla" refers to since Tavla has no actual navigation-drawer
 * widget of its own — just this tile look reused for every board row.
 */
@Composable
fun CalendarTileBackground(
    colorHex: String?,
    coverImagePath: String?,
    isDark: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val tileModifier = if (isDark) {
        modifier.clip(shape).border(1.dp, ZamanColors.GlassBorderDark, shape)
    } else {
        modifier
            .shadow(elevation = 2.dp, shape = shape, ambientColor = Color(0x282091A3), spotColor = Color(0x142091A3))
            .clip(shape)
            .border(1.dp, Color(0x14066EA8), shape)
    }
    // CenterStart, not the Box default (top-start): content() is usually a single Row shorter
    // than the tile's own fixed height, which otherwise left it pinned to the top with all the
    // slack showing up as extra space underneath instead of split evenly above and below.
    Box(modifier = tileModifier, contentAlignment = Alignment.CenterStart) {
        if (coverImagePath != null) {
            TileFrostedCover(coverImagePath, isDark)
        } else {
            val color = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
                .getOrDefault(if (isDark) ZamanColors.AccentDark else ZamanColors.AccentLight)
            Box(modifier = Modifier.fillMaxSize().background(if (isDark) ZamanColors.GlassFillDark else ZamanColors.BgSurfaceLight))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            listOf(color.copy(alpha = if (isDark) 0.40f else 0.26f), Color.Transparent),
                            radius = 220f,
                        )
                    )
            )
        }
        content()
    }
}

/** Tile-scale analog of [FrostedCoverBackground] — same blur-on-S+/tiny-upscale-fallback recipe,
 *  just at Tavla's row-tile numbers (smaller decode target, lighter blur) instead of a full screen. */
@Composable
private fun TileFrostedCover(path: String, isDark: Boolean) {
    val blurSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val targetEdge = if (blurSupported) 220 else 32
    val bitmap = remember(path, targetEdge) {
        runCatching {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            val opts = android.graphics.BitmapFactory.Options().apply {
                var s = 1
                while (bounds.outHeight / (s * 2) >= targetEdge && bounds.outWidth / (s * 2) >= targetEdge) s *= 2
                inSampleSize = s
            }
            android.graphics.BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
        }.getOrNull()
    }
    Box(modifier = Modifier.fillMaxSize().background(if (isDark) ZamanColors.GlassFillDark else ZamanColors.BgSurfaceLight)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                filterQuality = if (blurSupported) FilterQuality.High else FilterQuality.Low,
                modifier = Modifier.fillMaxSize().let { if (blurSupported) it.blur(28.dp) else it },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDark) Color.Black.copy(alpha = 0.42f) else Color.White.copy(alpha = 0.55f))
        )
    }
}

// ── Direct ports of Tavla's header-icon and card-surface patterns ──────────────
// (ui/components/Shared.kt's TopBarIcon, ui/theme/GlassModifiers.kt's tavlaCard/lightCard) —
// used to replace Material3's default TopAppBar/OutlinedTextField chrome, which is what made
// Zaman's edit screen read as "rough around the edges" next to Tavla's borderless icon row and
// frosted card stack.

/** A 44dp borderless icon button, tinted primary when [active] — Zaman's header icons now use
 *  this instead of a plain IconButton, matching Tavla's TopBarIcon exactly. */
@Composable
fun TopBarIcon(icon: ImageVector, contentDescription: String, active: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Light-mode card: near-white surface with a soft shadow, matching Tavla's lightCard — Zaman's
 *  own GlassCard only ever had the dark "glass" look; in light mode it read flat and grey. */
fun Modifier.zamanLightCard(cornerRadius: Dp = RadiusMd, elevated: Boolean = false): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .shadow(elevation = if (elevated) 4.dp else 2.dp, shape = shape, ambientColor = Color(0x28066EA8), spotColor = Color(0x14066EA8))
        .clip(shape)
        .background(ZamanColors.BgSurfaceLight)
        .border(1.dp, Color(0x14066EA8), shape)
}

/** Picks the right card surface for the current theme — dark glass, or the light card above. */
@Composable
fun Modifier.zamanCard(cornerRadius: Dp = RadiusMd, isDark: Boolean = LocalZamanIsDark.current): Modifier =
    if (isDark) {
        val shape = RoundedCornerShape(cornerRadius)
        this.clip(shape).background(ZamanColors.GlassFillDark).border(1.dp, ZamanColors.GlassBorderDark, shape)
    } else {
        this.zamanLightCard(cornerRadius)
    }

/** Small uppercase, primary-tinted section label — previously private to SettingsScreen (its
 *  "ACCOUNT"/"SECURITY"/"DISPLAY" headers), now shared so other screens' field-group headers (the
 *  Edit Event screen's cards, for one) use the exact same treatment instead of plain body text —
 *  two different "heading" styles side by side was the inconsistency being reported. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text.uppercase(),
        style = ZamanType.labelMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
        modifier = modifier.padding(horizontal = 4.dp),
    )
}
