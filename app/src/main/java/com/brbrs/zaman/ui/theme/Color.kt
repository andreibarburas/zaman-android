package com.brbrs.zaman.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Direct ports of the BarburasLab design-system tokens (Zaman tint).
 * Keep these in sync with the design system artifact's tokens.json —
 * do not hand-tune a value here without updating the source of truth there.
 */
object ZamanColors {
    // bg / surface — dark
    val BgDark = Color(0xFF080C18)
    val BgGradientEndDark = Color(0xFF0E1525)
    val BgSurfaceDark = Color(0xFF161D30)
    val InkDark = Color(0xFFF1F5F9)
    val InkMutedDark = Color(0xFF94A3B8)

    // bg / surface — light
    val BgLight = Color(0xFFF2EEFB)
    val BgGradientEndLight = Color(0xFFE9E2F5)
    val BgSurfaceLight = Color(0xFFFBF9FE)
    val InkLight = Color(0xFF180F22)
    val InkMutedLight = Color(0xFF5D4E75)

    // glass card — dark
    val GlassFillDark = Color(0x12FFFFFF)   // rgba(255,255,255,0.07)
    val GlassBorderDark = Color(0x1AFFFFFF) // rgba(255,255,255,0.10)

    // accent-zaman
    val AccentDark = Color(0xFF066EA8)
    val AccentLight = Color(0xFF045583)
    val AccentSecondaryDark = Color(0xFFC4485B)
    val AccentSecondaryLight = Color(0xFFA03B4A)
    val OnAccent = Color(0xFFFFFFFF)

    // status
    val Success = Color(0xFF4CAF78)
    val Danger = Color(0xFFEF5350)
    val Warning = Color(0xFFFFB300)
    val Info = Color(0xFF6FA8C0)

    // other suite apps' accents — used only for cross-app source badges (KOTO, TAVLA, …)
    val AccentVinci = Color(0xFFAB47BC)
    val AccentTavla = Color(0xFF3BB8C8)
    val AccentKoto = Color(0xFF6FA8C0)
    val AccentBlik = Color(0xFFFF8F00)
}
