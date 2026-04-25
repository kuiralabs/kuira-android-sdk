package com.midnight.kuira.core.designsystem.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Dusk typography scale — maps the design spec tokens to [TextStyle] values.
 *
 * Font: system sans. No serif. Never bold (W700+). Hierarchy is size +
 * opacity, not weight.
 *
 * Usage:
 * ```kotlin
 * Text(
 *     text = "BALANCE",
 *     style = DuskTypography.labelTiny,
 *     color = MidnightColors.LightMuted,
 * )
 * ```
 *
 * Color is NOT baked into the styles — callers set it via `color` param
 * or `.copy(color = ...)`. This keeps styles reusable across contexts
 * where the same type scale applies with different colors.
 */
object DuskTypography {

    /** Section headers, badges, small labels. 11sp W400, 3sp tracking. */
    val labelTiny = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 3.sp,
    )

    /** Balance hero — large numeric display. 44sp W200, tight tracking. */
    val numericHero = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W200,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1).sp,
    )

    /** Primary headlines. 18sp W300. */
    val headlineSm = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W300,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    )

    /** Larger headlines. 22sp W300. */
    val headlineMd = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W300,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    )

    /** Supporting text, timestamps, secondary info. 13sp W400. */
    val detail = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** Primary content text, row labels. 14sp W300. */
    val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W300,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Small annotations, token denominations. 12sp W400. */
    val caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Monospace for addresses and hashes. 12sp W400 mono. */
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.W400,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )

    /** Text input fields. 14sp W300. */
    val input = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W300,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Input placeholder text. 14sp W300. */
    val inputPlaceholder = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W300,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Primary button label. 14sp W500, 0.5sp tracking. */
    val buttonPrimary = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W500,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
    )

    /** Secondary button label. 14sp W400, 0.5sp tracking. */
    val buttonSecondary = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.W400,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
    )
}
