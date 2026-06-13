package com.midnight.kuira.dapp

import androidx.compose.ui.graphics.Color

/**
 * Canonical Kuira **dusk** brand tokens (dark mode), the on-brand default for
 * the SDK's panels.
 *
 * These are literal copies of `DuskPalette.DarkMode` (the design-system source
 * of truth). They're duplicated here because `core:designsystem` is **not
 * published** — the SDK's `dapp-ui` can't depend on it — so a host that drops
 * in the panels still gets the brand without pulling the app's design module.
 * Keep in sync with `feature/balance/.../DuskPalette.kt`.
 *
 * Brand ethos (`MidnightColors`): *"stars against void — luminosity is the only
 * variable."* Hue appears only as the two semantic tokens [ErrorText] /
 * [SuccessText]; everything else is white-on-void at varying alpha.
 */
internal object DuskBrand {
    val VoidElevated = Color(0xFF111111)
    val Light = Color(0xFFFFFFFF)
    val LightSoft = Color(0xCCFFFFFF)
    val LightMuted = Color(0x80FFFFFF)   // 50% — bumped from 40% for readability
    val LightFaint = Color(0x33FFFFFF)
    val LightBarely = Color(0x1AFFFFFF)
    val ConfirmSurface = Color(0x1AFFFFFF)
    val ButtonSurface = Color(0xFF1A1A1A)
    val ErrorText = Color(0xFFFF4444)
    val SuccessText = Color(0xFF4CAF50)
}
