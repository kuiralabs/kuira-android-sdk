package com.midnight.kuira.dev.wireframes.shared

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Centralized design tokens matching `_prefix.md`. Every wireframe
 * must reference these instead of inline magic numbers.
 *
 * Naming convention: category + spec token name.
 * e.g., `_prefix.md` "space-32" → `Space32`
 *       `_prefix.md` "icon-24" → `Icon24`
 */
object DuskTokens {

    // ── SPACING SCALE (_prefix.md §SPACING SCALE) ──
    val Space4: Dp = 4.dp
    val Space8: Dp = 8.dp
    val Space12: Dp = 12.dp
    val Space16: Dp = 16.dp
    val Space20: Dp = 20.dp
    val Space24: Dp = 24.dp
    val Space32: Dp = 32.dp
    val Space48: Dp = 48.dp

    // ── ICON SCALE (_prefix.md §ICON SCALE) ──
    val Icon16: Dp = 16.dp
    val Icon20: Dp = 20.dp
    val Icon24: Dp = 24.dp
    val Icon32: Dp = 32.dp
    /** Hero icons on onboarding/feature screens (not in _prefix.md but
     *  used consistently: biometric fingerprint, app symbol). */
    val Icon64: Dp = 64.dp

    // ── SHAPES (_prefix.md §SHAPES) ──
    val RadiusSm: Dp = 8.dp
    val RadiusMd: Dp = 12.dp
    val RadiusLg: Dp = 20.dp
    val RadiusFull: Dp = 9999.dp

    // ── LAYOUT CHROME (_prefix.md §TOP BAR, §LIST ROWS) ──
    val TopBarHeight: Dp = 56.dp
    val ButtonHeight: Dp = 48.dp
    /** Scannable list rows: 56dp minimum per LIST ROWS standard. */
    val RowMinHeight: Dp = 56.dp
    /** Data-display / interactive minimum (accessibility floor). */
    val RowMinHeightAccessibility: Dp = 48.dp

    // ── COMPONENT-SPECIFIC SIZING ──
    // These are NOT _prefix.md tokens but are used consistently across
    // multiple wireframe screens. Named here to avoid magic numbers.

    /** DuskProgressBar track height (Balance sync, Dust generation). */
    val ProgressBarHeight: Dp = 6.dp

    /** RaramuriRunner animation canvas height. */
    val RunnerHeight: Dp = 120.dp
    /** RaramuriRunner width as fraction of parent. */
    const val RunnerWidthFraction: Float = 0.4f

    /** QR code image size on the Receive screen. */
    val QrSize: Dp = 200.dp

    /** Passcode numpad button diameter (onboarding). */
    val NumpadButtonSize: Dp = 72.dp
    /** Passcode dot indicator diameter. */
    val PasscodeDotSize: Dp = 16.dp

    /** GlassPanel default content padding. */
    val PanelPadding: Dp = 16.dp
    /** GlassPanel hero content padding (success, pending, receipt). */
    val PanelPaddingHero: Dp = 24.dp
}
