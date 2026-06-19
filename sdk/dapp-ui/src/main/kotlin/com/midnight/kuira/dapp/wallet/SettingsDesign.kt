package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.dapp.dappPressable

/**
 * Settings panel design (#252) — section headers, glass rows, and original line-art glyphs drawn
 * in the same Dusk idiom as the Send wizard's [BackGlyph]/[ScanGlyph]/… (fractional-coordinate
 * Canvas strokes, round caps). No Material icons, no emoji — every glyph is hand-drawn to the
 * theme. Themed via [SendPalette] so the panel's light/dark toggle carries.
 */

private const val SETTINGS_STROKE = 0.09f

// ── Accessible icon button ───────────────────────────────────────────────────

/**
 * 48dp-minimum, labelled tappable wrapper for an icon-only control (back arrow, gear, theme
 * toggle). [dappPressable] already reserves the 48dp touch target (via
 * `minimumInteractiveComponentSize`) and the press/hover/focus feedback; this adds the
 * [contentDescription] + Button [Role] that TalkBack needs because the [glyph] carries no text
 * of its own. The visual glyph stays its natural size, centered in the larger target.
 */
@Composable
internal fun GlyphButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    glyph: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .dappPressable(shape = shape, onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            }
            .padding(SendDimens.GlyphHit),
        contentAlignment = Alignment.Center,
        content = { glyph() },
    )
}

// ── Section + rows ──────────────────────────────────────────────────────────

/** Uppercase, letter-spaced section heading above a [GlassPanel]. */
@Composable
internal fun SettingsSection(
    label: String,
    palette: SendPalette,
    content: @Composable () -> Unit,
) {
    Text(
        text = label,
        color = palette.textMuted,
        fontSize = SendType.SectionLabel,
        fontWeight = FontWeight.Normal,
        letterSpacing = 3.sp,
    )
    Spacer(Modifier.size(SendDimens.Space12))
    GlassPanel(tint = palette.text.copy(alpha = GLASS_FILL_ALPHA), border = palette.hairline, contentPadding = SendDimens.Space4) {
        content()
    }
}

/**
 * A row inside a settings [GlassPanel]. [readOnly] dims the value + drops the chevron;
 * [leadingGlyph] is a theme glyph drawer (takes the tint). Tappable when [onClick] is non-null.
 */
@Composable
internal fun SettingsRow(
    label: String,
    palette: SendPalette,
    leadingGlyph: (@Composable (Color) -> Unit)? = null,
    rightValue: String? = null,
    rightValueMono: Boolean = false,
    readOnly: Boolean = false,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    val labelColor = when {
        danger -> palette.error
        readOnly -> palette.textSoft
        else -> palette.text
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SendDimens.RowMinHeight)
            .then(clickable)
            .padding(horizontal = SendDimens.Space16, vertical = SendDimens.Space12),
    ) {
        if (leadingGlyph != null) {
            leadingGlyph(if (danger) palette.error else palette.text)
            Spacer(Modifier.width(SendDimens.Space12))
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = SendType.Body,
            fontWeight = FontWeight.Light,
            modifier = Modifier.weight(1f),
        )
        if (rightValue != null) {
            Text(
                text = rightValue,
                color = if (readOnly) palette.text else palette.textSoft,
                fontSize = SendType.Body,
                fontWeight = FontWeight.Light,
                fontFamily = if (rightValueMono) FontFamily.Monospace else FontFamily.Default,
            )
        }
        if (onClick != null && !readOnly) {
            Spacer(Modifier.width(SendDimens.Space8))
            ChevronGlyph(palette.textMuted)
        }
    }
}

/** Hairline divider between rows inside a panel. */
@Composable
internal fun SettingsDivider(palette: SendPalette) {
    HorizontalDivider(
        color = palette.hairline,
        thickness = SendDimens.DividerThickness,
        modifier = Modifier.padding(horizontal = SendDimens.Space16),
    )
}

// ── Appearance theme picker ──────────────────────────────────────────────────

/**
 * Horizontally-scrolling row of theme swatches. Each swatch previews a [WalletTheme]'s own surface
 * + text + accent (not the active palette), so the user picks by eye; the selected one is ringed.
 * Tapping applies immediately (the panel re-themes live).
 */
@Composable
internal fun ThemeSwatchRow(
    themes: List<WalletTheme>,
    selectedId: String,
    palette: SendPalette,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = SendDimens.Space12, vertical = SendDimens.Space12),
        horizontalArrangement = Arrangement.spacedBy(SendDimens.Space16),
    ) {
        themes.forEach { theme ->
            ThemeSwatch(
                theme = theme,
                selected = theme.id == selectedId,
                palette = palette,
                onClick = { onSelect(theme.id) },
            )
        }
    }
}

@Composable
private fun ThemeSwatch(
    theme: WalletTheme,
    selected: Boolean,
    palette: SendPalette,
    onClick: () -> Unit,
) {
    val c = theme.colors
    val swatchShape = RoundedCornerShape(SendDimens.RadiusMd)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(swatchShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "Theme ${theme.label}" }
            .padding(SendDimens.Space4),
    ) {
        Box(
            modifier = Modifier
                .size(THEME_SWATCH_SIZE)
                .clip(swatchShape)
                .background(c.sheetBackground)
                .border(
                    width = if (selected) THEME_SWATCH_RING else SendDimens.DividerThickness,
                    color = if (selected) palette.text else palette.hairline,
                    shape = swatchShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Miniature "card": a text-tone bar over an accent bar — the same hierarchy the
            // real surface uses, so the swatch reads as a tiny preview rather than a color chip.
            Column(verticalArrangement = Arrangement.spacedBy(THEME_SWATCH_BAR_GAP)) {
                Box(Modifier.width(THEME_SWATCH_BAR_TEXT).height(THEME_SWATCH_BAR_HEIGHT).clip(CircleShape).background(c.onSheet))
                Box(Modifier.width(THEME_SWATCH_BAR_ACCENT).height(THEME_SWATCH_BAR_HEIGHT).clip(CircleShape).background(c.accent))
            }
        }
        Spacer(Modifier.height(SendDimens.Space8))
        Text(
            text = theme.label,
            color = if (selected) palette.text else palette.textMuted,
            fontSize = SendType.Hint,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

private val THEME_SWATCH_SIZE = 56.dp
private val THEME_SWATCH_RING = 2.dp
private val THEME_SWATCH_BAR_TEXT = 28.dp
private val THEME_SWATCH_BAR_ACCENT = 18.dp
private val THEME_SWATCH_BAR_HEIGHT = 5.dp
private val THEME_SWATCH_BAR_GAP = 6.dp

// ── Original glyphs (Dusk line-art) ─────────────────────────────────────────

/** Recovery phrase — a key: ring + stem + two teeth. */
@Composable
internal fun KeyGlyph(color: Color, size: Dp = SendDimens.Icon24, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        drawCircle(color, radius = w * 0.15f, center = Offset(w * 0.30f, h * 0.50f), style = Stroke(sw))
        drawLine(color, Offset(w * 0.44f, h * 0.50f), Offset(w * 0.82f, h * 0.50f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.66f, h * 0.50f), Offset(w * 0.66f, h * 0.64f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.80f, h * 0.50f), Offset(w * 0.80f, h * 0.62f), sw, StrokeCap.Round)
    }
}

/** Lock now — a padlock: body + shackle arc. */
@Composable
internal fun LockGlyph(color: Color, size: Dp = SendDimens.Icon24, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.26f, h * 0.46f),
            size = Size(w * 0.48f, h * 0.34f),
            cornerRadius = CornerRadius(w * 0.07f, w * 0.07f),
            style = Stroke(sw),
        )
        drawArc(
            color = color,
            startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.35f, h * 0.24f),
            size = Size(w * 0.30f, w * 0.30f),
            style = Stroke(sw),
        )
    }
}

/** Sign out — a power mark: broken ring (gap at top) + a vertical bar through the gap. */
@Composable
internal fun SignOutGlyph(color: Color, size: Dp = SendDimens.Icon24, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        drawArc(
            color = color,
            startAngle = 290f, sweepAngle = 320f, useCenter = false,
            topLeft = Offset(w * 0.24f, h * 0.26f),
            size = Size(w * 0.52f, w * 0.52f),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w * 0.50f, h * 0.18f), Offset(w * 0.50f, h * 0.50f), sw, StrokeCap.Round)
    }
}

/** Keep-secret mark — an eye with a slash through it (don't let anyone see this). */
@Composable
internal fun EyeOffGlyph(color: Color, size: Dp = SendDimens.Icon20, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        drawOval(color, topLeft = Offset(w * 0.14f, h * 0.32f), size = Size(w * 0.72f, h * 0.36f), style = Stroke(sw))
        drawCircle(color, radius = w * 0.11f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(sw))
        drawLine(color, Offset(w * 0.20f, h * 0.20f), Offset(w * 0.80f, h * 0.80f), sw, StrokeCap.Round)
    }
}

/** Dust — a loose cluster of particles (the brand's dust motif), no emoji. */
@Composable
internal fun DustGlyph(color: Color, size: Dp = SendDimens.Icon20, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        drawCircle(color, radius = w * 0.075f, center = Offset(w * 0.34f, h * 0.40f))
        drawCircle(color, radius = w * 0.060f, center = Offset(w * 0.58f, h * 0.30f))
        drawCircle(color, radius = w * 0.050f, center = Offset(w * 0.48f, h * 0.60f))
        drawCircle(color, radius = w * 0.045f, center = Offset(w * 0.72f, h * 0.56f))
        drawCircle(color, radius = w * 0.040f, center = Offset(w * 0.30f, h * 0.66f))
    }
}

/** App data — a cloud: two stroked puffs over a baseline. */
@Composable
internal fun CloudGlyph(color: Color, size: Dp = SendDimens.Icon20, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        drawLine(color, Offset(w * 0.26f, h * 0.66f), Offset(w * 0.74f, h * 0.66f), sw, StrokeCap.Round)
        drawArc(color, 70f, 200f, false, Offset(w * 0.18f, h * 0.42f), Size(w * 0.28f, h * 0.30f), style = Stroke(sw, cap = StrokeCap.Round))
        drawArc(color, 150f, 240f, false, Offset(w * 0.38f, h * 0.30f), Size(w * 0.38f, h * 0.42f), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/** Network — a globe: circle + equator + meridian oval. */
@Composable
internal fun GlobeGlyph(color: Color, size: Dp = SendDimens.Icon24, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        drawCircle(color, radius = w * 0.30f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(sw))
        drawLine(color, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.80f, h * 0.50f), sw)
        drawOval(
            color = color,
            topLeft = Offset(w * 0.38f, h * 0.20f),
            size = Size(w * 0.24f, h * 0.60f),
            style = Stroke(sw),
        )
    }
}

/** Trailing nav chevron (›). */
@Composable
internal fun ChevronGlyph(color: Color, size: Dp = SendDimens.Icon16, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        drawLine(color, Offset(w * 0.42f, h * 0.30f), Offset(w * 0.62f, h * 0.50f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.62f, h * 0.50f), Offset(w * 0.42f, h * 0.70f), sw, StrokeCap.Round)
    }
}

/** Settings — three "tuning sliders": horizontal rails with offset knobs (distinct from a cog). */
@Composable
internal fun GearGlyph(color: Color, size: Dp = SendDimens.Icon20, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * SETTINGS_STROKE
        val knob = w * 0.08f
        fun slider(y: Float, knobX: Float) {
            drawLine(color, Offset(w * 0.18f, h * y), Offset(w * 0.82f, h * y), sw, StrokeCap.Round)
            drawCircle(color, radius = knob, center = Offset(w * knobX, h * y))
        }
        slider(0.30f, 0.66f)
        slider(0.50f, 0.36f)
        slider(0.70f, 0.60f)
    }
}
