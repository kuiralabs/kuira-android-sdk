package com.midnight.kuira.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Midnight brand palette — black and white only.
 *
 * The identity is light in darkness. Stars against void.
 * No color — luminosity is the only variable.
 */
object MidnightColors {

    // ── Void (backgrounds) ──

    val Void = Color(0xFF000000)            // Pure black
    val VoidSoft = Color(0xFF0A0A0A)        // Near-black (sheet background)
    val VoidElevated = Color(0xFF111111)     // Slightly lifted surface

    // ── Light (text & elements) ──

    val Light = Color(0xFFFFFFFF)            // Pure white — the amount, the thing that matters
    val LightSoft = Color(0xCCFFFFFF)        // 80% — secondary info
    val LightMuted = Color(0x66FFFFFF)       // 40% — details, fees
    val LightFaint = Color(0x33FFFFFF)       // 20% — dividers, structure
    val LightBarely = Color(0x1AFFFFFF)      // 10% — surfaces, subtle containers

    // ── Stars ──

    val StarBright = Color(0xCCFFFFFF)       // 80%
    val StarDim = Color(0x33FFFFFF)          // 20%

    // ── Actions ──

    /** Confirm — just brighter white on a slightly visible surface */
    val Confirm = Color(0xFFFFFFFF)
    val ConfirmSurface = Color(0x1AFFFFFF)   // 10% white background

    /** Reject — nearly invisible */
    val RejectText = Color(0x66FFFFFF)       // 40% white
}
