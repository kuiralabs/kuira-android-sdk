package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens, palette adapter, and vector icons for the Send wizard
 * (#240). The wizard ports the `app/` design-sprint Send wireframe
 * (StarField + GlassPanel + hero amount) down into the published SDK,
 * which can't reach the app/feature modules where the originals live.
 *
 * Everything here keeps the SDK dependency-clean: colors derive from the
 * [WalletPanelColors] the panel already passes (so the light/dark toggle
 * keeps working), and icons are drawn with [Canvas] rather than pulling in
 * material-icons-extended (dapp-ui uses no Material icons elsewhere).
 */

// ── Spacing / sizing (mirrors the wireframe's DuskTokens) ──

internal object SendDimens {
    val Space4: Dp = 4.dp
    val Space8: Dp = 8.dp
    val Space12: Dp = 12.dp
    val Space16: Dp = 16.dp
    val Space20: Dp = 20.dp
    val Space24: Dp = 24.dp
    val Space32: Dp = 32.dp
    val Space48: Dp = 48.dp

    val Icon16: Dp = 16.dp
    val Icon20: Dp = 20.dp
    val Icon24: Dp = 24.dp
    val Icon32: Dp = 32.dp

    val RadiusSm: Dp = 8.dp
    val RadiusMd: Dp = 12.dp
    val RadiusLg: Dp = 20.dp
    val RadiusFull: Dp = 9999.dp

    val TopBarHeight: Dp = 56.dp
    val ButtonHeight: Dp = 54.dp   // prominent CTA — above the 48dp HIG floor
    val RowMinHeight: Dp = 56.dp
    val RowMinHeightAccessibility: Dp = 48.dp

    val PanelPadding: Dp = 16.dp
    val PanelPaddingHero: Dp = 24.dp

    /** Inset for a tappable glyph so the touch target reaches the 48dp floor. */
    val GlyphHit: Dp = 8.dp
    val FieldVerticalPadding: Dp = 12.dp
    val DividerThickness: Dp = 1.dp
    val BorderWidth: Dp = 1.dp
}

internal object SendType {
    val HeroNumber = 44.sp
    val HeroNumberLg = 60.sp     // amount square + review hero — extra prominence
    val HeroDenom = 18.sp
    val Title = 14.sp
    val SectionLabel = 11.sp
    val Body = 14.sp
    val Caption = 13.sp
    val Hint = 12.sp
    val Badge = 11.sp
    val Button = 15.sp           // was 13 — readable CTA label (Material labelLarge is 14, iOS 17pt)
    val TopBarAction = 16.sp     // top-bar text actions (Continue / Done) — bigger, in a 48dp target
    val Max = 11.sp
    val SuccessHeadline = 18.sp

    /** letterSpacing presets (sp) used by the all-caps labels/badges. */
    val TrackBadge = 3.sp
    val TrackMax = 1.sp
    val HeroTracking = (-1).sp
}

// ── Palette adapter ──
//
// The wireframe used `DuskPalette` (feature module). The published SDK can't
// import that, but [WalletPanelColors] (which the panel already themes for
// light/dark) carries the same semantic poles. This adapts one to the wizard's
// vocabulary and derives the two alpha tokens the panel colors don't expose.

private const val ALPHA_SOFT = 0.80f
private const val LUMINANCE_MIDPOINT = 0.5f

internal class SendPalette(
    val bg: Color,        // surface behind the StarField (DuskPalette.Void)
    val text: Color,      // primary text (Light)
    val textSoft: Color,  // 80% text (LightSoft)
    val textMuted: Color, // 50% text (LightMuted)
    val hairline: Color,  // borders / dividers (LightFaint)
    val barely: Color,    // chip / badge fill (LightBarely)
    val panel: Color,     // GlassPanel fill over stars (contentPanel)
    val confirm: Color,   // primary-button fill (Confirm)
    val onConfirm: Color, // text on the primary button
    val error: Color,     // ErrorText
    val success: Color,   // SuccessText
    val isLight: Boolean, // drives StarField brightness
) {
    companion object {
        fun from(c: WalletPanelColors): SendPalette = SendPalette(
            bg = c.sheetBackground,
            text = c.onSheet,
            textSoft = c.onSheet.copy(alpha = ALPHA_SOFT),
            textMuted = c.onSheetDim,
            hairline = c.onSheetSubtle,
            barely = c.buttonDisabled,
            panel = c.button,
            confirm = c.onSheet,
            onConfirm = c.sheetBackground,
            error = c.error,
            // Success-check hero stays green (a status), not the monochrome accent.
            success = c.positive,
            isLight = c.sheetBackground.luminance() > LUMINANCE_MIDPOINT,
        )
    }
}

/** StarField max-brightness cap — dim in light mode so stars stay texture, not clutter. */
internal const val STAR_COUNT = 60
internal const val STAR_ALPHA_LIGHT = 0.55f
internal const val STAR_ALPHA_DARK = 1f

/**
 * Frosted-glass fill alpha — the translucency that lets the star field show faintly through a
 * [com.midnight.kuira.core.designsystem.component.GlassPanel]. Applied as `onSheet.copy(alpha = …)`
 * so it inverts correctly per mode (white frost on dark, black frost on light). One value → every
 * SDK panel frosts identically.
 */
internal const val GLASS_FILL_ALPHA = 0.055f

// ── Vector icons (Canvas, no material-icons dependency) ──
//
// Paths use normalized 0..1 canvas coordinates scaled by the draw size — the
// idiomatic way to author resolution-independent vector glyphs. Stroke width
// is a fixed fraction of the icon size so weight stays consistent at any Dp.

private const val STROKE_FRACTION = 0.09f

/** ← back arrow. */
@Composable
internal fun BackGlyph(color: Color, size: Dp = SendDimens.Icon24, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.iconSize(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * STROKE_FRACTION
        val cy = h / 2f
        drawLine(color, Offset(w * 0.20f, cy), Offset(w * 0.82f, cy), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.20f, cy), Offset(w * 0.44f, cy - h * 0.22f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.20f, cy), Offset(w * 0.44f, cy + h * 0.22f), sw, StrokeCap.Round)
    }
}

/** QR scan frame — four corner brackets + a faint center module hint. */
@Composable
internal fun ScanGlyph(color: Color, size: Dp = SendDimens.Icon20, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.iconSize(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * STROKE_FRACTION
        val inset = w * 0.14f
        val arm = w * 0.22f
        fun corner(cx: Float, cy: Float, dx: Float, dy: Float) {
            drawLine(color, Offset(cx, cy), Offset(cx + dx, cy), sw, StrokeCap.Round)
            drawLine(color, Offset(cx, cy), Offset(cx, cy + dy), sw, StrokeCap.Round)
        }
        corner(inset, inset, arm, arm)                       // top-left
        corner(w - inset, inset, -arm, arm)                  // top-right
        corner(inset, h - inset, arm, -arm)                  // bottom-left
        corner(w - inset, h - inset, -arm, -arm)             // bottom-right
        // Center module hint (a small QR-like square).
        val s = w * 0.16f
        drawRect(color, Offset(w / 2f - s / 2f, h / 2f - s / 2f), Size(s, s))
    }
}

/** Clipboard (paste). */
@Composable
internal fun PasteGlyph(color: Color, size: Dp = SendDimens.Icon20, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.iconSize(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * STROKE_FRACTION
        val corner = CornerRadius(w * 0.10f, w * 0.10f)
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.24f, h * 0.20f),
            size = Size(w * 0.52f, h * 0.66f),
            cornerRadius = corner,
            style = Stroke(sw),
        )
        // Clip tab.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.37f, h * 0.12f),
            size = Size(w * 0.26f, h * 0.14f),
            cornerRadius = CornerRadius(w * 0.05f, w * 0.05f),
        )
    }
}

/** ✓ check. */
@Composable
internal fun CheckGlyph(color: Color, size: Dp = SendDimens.Icon32, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.iconSize(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * STROKE_FRACTION
        val path = Path().apply {
            moveTo(w * 0.22f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.78f, h * 0.30f)
        }
        drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Copy (two stacked sheets). */
@Composable
internal fun CopyGlyph(color: Color, size: Dp = SendDimens.Icon16, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.iconSize(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = w * STROKE_FRACTION
        val corner = CornerRadius(w * 0.12f, w * 0.12f)
        // Back sheet.
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.34f, h * 0.14f),
            size = Size(w * 0.44f, h * 0.50f),
            cornerRadius = corner,
            style = Stroke(sw),
        )
        // Front sheet (overlaps, drawn over a bg-cleared region for legibility).
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.20f, h * 0.34f),
            size = Size(w * 0.44f, h * 0.50f),
            cornerRadius = corner,
            style = Stroke(sw),
        )
    }
}

private fun Modifier.iconSize(size: Dp): Modifier = this.size(size)
