package com.brbrs.zaman.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.brbrs.zaman.R

// DM Serif Display + Inter Tight, resource-named per suite convention
// (underscores, e.g. dm_serif_display_regular / inter_tight_semi_bold).
private val CustomDisplayFont = FontFamily(Font(R.font.dm_serif_display_regular))
private val CustomBodyFont = FontFamily(
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_medium, FontWeight.Medium),
    Font(R.font.inter_tight_semi_bold, FontWeight.SemiBold),
    Font(R.font.inter_tight_bold, FontWeight.Bold),
)

/**
 * Settings → Display → "DM Serif + Inter Tight" toggle. A plain mutable-state singleton
 * rather than a CompositionLocal/ViewModel plumbed through every screen: [ZamanType]'s
 * styles read it via a `get()`, so any Text composed with a Zaman style recomposes
 * automatically when this flips, with no per-screen wiring. MainActivity is the only
 * writer, synced from DisplayViewModel.
 */
object FontPrefs {
    var useCustomFont by mutableStateOf(true)
}

private val DisplayFont: FontFamily get() = if (FontPrefs.useCustomFont) CustomDisplayFont else FontFamily.Default
private val BodyFont: FontFamily get() = if (FontPrefs.useCustomFont) CustomBodyFont else FontFamily.Default

// Direct port of the design system's type scale (type.groups → Display / Text).
// Each entry is a computed property (not a val) so it re-reads DisplayFont/BodyFont —
// and therefore FontPrefs — on every access, which is what makes the font toggle live.
object ZamanType {
    val headlineLarge: TextStyle get() = TextStyle(fontFamily = DisplayFont, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.3).sp)
    val headlineMedium: TextStyle get() = TextStyle(fontFamily = DisplayFont, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-0.2).sp)
    val headlineSmall: TextStyle get() = TextStyle(fontFamily = DisplayFont, fontSize = 22.sp, lineHeight = 30.sp)

    val titleLarge: TextStyle get() = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.2).sp)
    val titleMedium: TextStyle get() = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.15).sp)
    val bodyLarge: TextStyle get() = TextStyle(fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp)
    val bodyMedium: TextStyle get() = TextStyle(fontFamily = BodyFont, fontSize = 13.sp, lineHeight = 20.sp, letterSpacing = (-0.05).sp)
    val bodySmall: TextStyle get() = TextStyle(fontFamily = BodyFont, fontSize = 12.sp, lineHeight = 18.sp)
    val labelMedium: TextStyle get() = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.06.sp)
    val labelSmall: TextStyle get() = TextStyle(fontFamily = BodyFont, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.08.sp)
}

/** Built fresh (not a top-level val) so it picks up the current FontPrefs state — see ZamanTheme. */
fun zamanMaterialTypography() = Typography(
    headlineLarge = ZamanType.headlineLarge,
    headlineMedium = ZamanType.headlineMedium,
    headlineSmall = ZamanType.headlineSmall,
    titleLarge = ZamanType.titleLarge,
    titleMedium = ZamanType.titleMedium,
    bodyLarge = ZamanType.bodyLarge,
    bodyMedium = ZamanType.bodyMedium,
    bodySmall = ZamanType.bodySmall,
    labelMedium = ZamanType.labelMedium,
    labelSmall = ZamanType.labelSmall,
)
