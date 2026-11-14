package com.brbrs.zaman.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val DarkScheme = darkColorScheme(
    background = ZamanColors.BgDark,
    surface = ZamanColors.BgSurfaceDark,
    onBackground = ZamanColors.InkDark,
    onSurface = ZamanColors.InkDark,
    primary = ZamanColors.AccentDark,
    onPrimary = ZamanColors.OnAccent,
    secondary = ZamanColors.AccentSecondaryDark,
    error = ZamanColors.Danger,
)

private val LightScheme = lightColorScheme(
    background = ZamanColors.BgLight,
    surface = ZamanColors.BgSurfaceLight,
    onBackground = ZamanColors.InkLight,
    onSurface = ZamanColors.InkLight,
    primary = ZamanColors.AccentLight,
    onPrimary = ZamanColors.OnAccent,
    secondary = ZamanColors.AccentSecondaryLight,
    error = ZamanColors.Danger,
)

// True-black variant for OLED panels — same ink/accent as Dark, just a pure-black
// background/surface so OLED pixels can switch off entirely. Matches the suite-wide
// Settings → Display → Theme pattern (Vinci/Tavla/Koto all offer this 4th option).
private val OledScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    onBackground = ZamanColors.InkDark,
    onSurface = ZamanColors.InkDark,
    primary = ZamanColors.AccentDark,
    onPrimary = ZamanColors.OnAccent,
    secondary = ZamanColors.AccentSecondaryDark,
    error = ZamanColors.Danger,
)

/** The theme actually in effect, after resolving ThemeMode.SYSTEM against the OS setting. */
enum class EffectiveTheme { LIGHT, DARK, OLED }

@Composable
fun ZamanTheme(
    effectiveTheme: EffectiveTheme = if (isSystemInDarkTheme()) EffectiveTheme.DARK else EffectiveTheme.LIGHT,
    // Settings → Display → Text size; 1f = Default. Applied as a LocalDensity override so
    // every sp-based size in the app scales together, instead of threading a scale param
    // through each screen's styles.
    textScaleMultiplier: Float = 1f,
    content: @Composable () -> Unit,
) {
    val scheme = when (effectiveTheme) {
        EffectiveTheme.LIGHT -> LightScheme
        EffectiveTheme.DARK -> DarkScheme
        EffectiveTheme.OLED -> OledScheme
    }
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, density.fontScale * textScaleMultiplier),
        LocalZamanIsDark provides (effectiveTheme != EffectiveTheme.LIGHT),
    ) {
        MaterialTheme(
            colorScheme = scheme,
            // Built fresh each composition (not a top-level val) so it reflects the current
            // custom-font toggle — see FontPrefs/zamanMaterialTypography in Type.kt.
            typography = zamanMaterialTypography(),
            content = content,
        )
    }
}

/**
 * Whether the app is currently rendering Dark or OLED (vs. Light) — set once by [ZamanTheme]
 * at the root. glassFill/glassBorder/inkMuted below read this instead of re-deriving light/dark
 * from isSystemInDarkTheme(), which ignored an explicit Dark/OLED/Light choice and only ever
 * reflected the OS setting.
 */
val LocalZamanIsDark = androidx.compose.runtime.compositionLocalOf { true }

// Convenience accessors mirroring the mockup's --glass-fill / --glass-border / --ink-muted,
// which have no direct Material3 ColorScheme slot.
@Composable
fun glassFill(darkTheme: Boolean = LocalZamanIsDark.current): Color =
    if (darkTheme) ZamanColors.GlassFillDark else Color(0x12066EA8)

@Composable
fun glassBorder(darkTheme: Boolean = LocalZamanIsDark.current): Color =
    if (darkTheme) ZamanColors.GlassBorderDark else Color(0x2E066EA8)

@Composable
fun inkMuted(darkTheme: Boolean = LocalZamanIsDark.current): Color =
    if (darkTheme) ZamanColors.InkMutedDark else ZamanColors.InkMutedLight
