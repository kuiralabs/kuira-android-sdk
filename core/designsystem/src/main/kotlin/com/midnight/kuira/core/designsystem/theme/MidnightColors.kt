package com.midnight.kuira.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Midnight brand palette.
 *
 * The identity is light in darkness. Stars against void. Luminosity is the
 * primary variable — backgrounds and text are white-on-void at varying alpha.
 * The sole exceptions are the two **semantic** action colors ([ErrorText] /
 * [SuccessText]); hue is reserved for "something went wrong / something
 * succeeded" and nothing else. (Mirrors the dusk palette's semantic tokens.)
 */
object MidnightColors {

    // ── Void (backgrounds) ──

    val Void = Color(0xFF000000)
    val VoidSoft = Color(0xFF0A0A0A)
    val VoidElevated = Color(0xFF111111)

    /** Subtle elevated surface for buttons/chips — a step above [VoidElevated]. */
    val ButtonSurface = Color(0xFF1A1A1A)

    // ── Light (text & elements) ──

    val Light = Color(0xFFFFFFFF)
    val LightSoft = Color(0xCCFFFFFF)
    val LightMuted = Color(0x80FFFFFF)   // 50% — bumped from 40% for readability
    val LightFaint = Color(0x33FFFFFF)
    val LightBarely = Color(0x1AFFFFFF)

    // ── Stars ──

    val StarBright = Color(0xCCFFFFFF)
    val StarDim = Color(0x33FFFFFF)

    // ── Actions ──

    val Confirm = Color(0xFFFFFFFF)
    val ConfirmSurface = Color(0x1AFFFFFF)
    val RejectText = Color(0x66FFFFFF)

    // ── Semantic (the only hues in the palette) ──

    val ErrorText = Color(0xFFFF4444)
    val SuccessText = Color(0xFF4CAF50)
}
