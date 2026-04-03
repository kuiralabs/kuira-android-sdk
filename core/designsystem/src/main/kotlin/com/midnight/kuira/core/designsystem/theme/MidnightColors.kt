package com.midnight.kuira.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Midnight brand palette — black and white only.
 *
 * The identity is light in darkness. Stars against void.
 * No color — luminosity is the only variable.
 */
object MidnightColors {

    // ── Void (backgrounds) ──

    val Void = Color(0xFF000000)
    val VoidSoft = Color(0xFF0A0A0A)
    val VoidElevated = Color(0xFF111111)

    // ── Light (text & elements) ──

    val Light = Color(0xFFFFFFFF)
    val LightSoft = Color(0xCCFFFFFF)
    val LightMuted = Color(0x66FFFFFF)
    val LightFaint = Color(0x33FFFFFF)
    val LightBarely = Color(0x1AFFFFFF)

    // ── Stars ──

    val StarBright = Color(0xCCFFFFFF)
    val StarDim = Color(0x33FFFFFF)

    // ── Actions ──

    val Confirm = Color(0xFFFFFFFF)
    val ConfirmSurface = Color(0x1AFFFFFF)
    val RejectText = Color(0x66FFFFFF)
}
