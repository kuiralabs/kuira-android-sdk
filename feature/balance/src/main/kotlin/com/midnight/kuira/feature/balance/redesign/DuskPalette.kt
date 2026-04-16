package com.midnight.kuira.feature.balance.redesign

import androidx.compose.ui.graphics.Color

/**
 * Mode-aware palette. Preview-only helper so the Balance wireframe can
 * render in light mode without waiting for the engineering task that
 * makes `MidnightColors` semantic (STANDARDS.md §16).
 *
 * Values mirror the dark + light tables in the prefix + STANDARDS.md.
 * Same token names, values swap per mode.
 */
data class DuskPalette(
    val Void: Color,
    val VoidSoft: Color,
    val VoidElevated: Color,
    val Light: Color,
    val LightSoft: Color,
    val LightMuted: Color,
    val LightFaint: Color,
    val LightBarely: Color,
    val StarBright: Color,
    val StarDim: Color,
    val Confirm: Color,
    val ConfirmSurface: Color,
    val RejectText: Color,
) {
    /**
     * Fill color for content panels sitting over the StarField. Picks the
     * elevation token that gives the strongest perceived lift against the
     * bg per mode:
     *   Dark mode  → VoidElevated (#111111, ~7% luminance delta vs #000)
     *   Light mode → VoidSoft (#FFFFFF, pure white vs #F7F7F7 bg)
     *
     * Using a single "VoidSoft" token for both modes gives only a 4%
     * delta in dark mode — too subtle. This helper picks the right
     * token so content cards feel appropriately elevated in both modes.
     */
    val contentPanel: Color
        get() = if (this === DarkMode) VoidElevated else VoidSoft

    companion object {
        val DarkMode = DuskPalette(
            Void = Color(0xFF000000),
            VoidSoft = Color(0xFF0A0A0A),
            VoidElevated = Color(0xFF111111),
            Light = Color(0xFFFFFFFF),
            LightSoft = Color(0xCCFFFFFF),
            LightMuted = Color(0x66FFFFFF),
            LightFaint = Color(0x33FFFFFF),
            LightBarely = Color(0x1AFFFFFF),
            StarBright = Color(0xCCFFFFFF),
            StarDim = Color(0x33FFFFFF),
            Confirm = Color(0xFFFFFFFF),
            ConfirmSurface = Color(0x1AFFFFFF),
            RejectText = Color(0x66FFFFFF),
        )

        val LightMode = DuskPalette(
            Void = Color(0xFFF7F7F7),
            VoidSoft = Color(0xFFFFFFFF),
            VoidElevated = Color(0xFFFAFAFA),
            Light = Color(0xFF000000),
            LightSoft = Color(0xCC000000),
            LightMuted = Color(0x66000000),
            LightFaint = Color(0x33000000),
            LightBarely = Color(0x0A000000),
            StarBright = Color(0x33000000),
            StarDim = Color(0x14000000),
            Confirm = Color(0xFF000000),
            // Button text on Confirm fill = opposite-pole token (Void in light mode).
            ConfirmSurface = Color(0x0A000000),
            RejectText = Color(0x66000000),
        )
    }
}
