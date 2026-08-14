package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.midnight.kuira.core.designsystem.component.GlassPanel

/**
 * Reusable primitives for the Send wizard — the dapp-ui port of the app's
 * design-sprint Send components ([com.midnight.kuira.dev.wireframes.send]),
 * rebound onto [SendPalette] so they theme with the panel's light/dark toggle.
 *
 * Kept private to the SDK UI: these aren't a general design-system API (that
 * lives in core:designsystem), they're the Send screen's vocabulary.
 */

// ── Panel ──

/** Frosted GlassPanel pre-bound to the wizard palette — translucent fill (stars show through) + hairline. */
@Composable
internal fun SendPanel(
    palette: SendPalette,
    modifier: Modifier = Modifier,
    contentPadding: Dp = SendDimens.PanelPadding,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassPanel(
        tint = palette.text.copy(alpha = GLASS_FILL_ALPHA),
        border = palette.hairline,
        modifier = modifier,
        cornerRadius = SendDimens.RadiusMd,
        contentPadding = contentPadding,
        content = content,
    )
}

// ── Labels / badges ──

@Composable
internal fun SendSectionHeader(label: String, palette: SendPalette, modifier: Modifier = Modifier) {
    Text(
        text = label,
        color = palette.textMuted,
        fontSize = SendType.SectionLabel,
        fontWeight = FontWeight.W400,
        letterSpacing = SendType.TrackBadge,
        modifier = modifier,
    )
}

/** Rounded all-caps pill — e.g. the "UNSHIELDED" mode badge. */
@Composable
internal fun SendBadge(text: String, palette: SendPalette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(SendDimens.RadiusFull))
            .background(palette.barely)
            .padding(horizontal = SendDimens.Space8, vertical = SendDimens.Space4),
    ) {
        Text(
            text = text,
            color = palette.textSoft,
            fontSize = SendType.Badge,
            fontWeight = FontWeight.W400,
            letterSpacing = SendType.TrackBadge,
        )
    }
}

@Composable
internal fun SendDivider(palette: SendPalette) {
    HorizontalDivider(
        color = palette.hairline,
        thickness = SendDimens.DividerThickness,
        modifier = Modifier.padding(horizontal = SendDimens.Space16),
    )
}

// ── Buttons ──

@Composable
internal fun SendPrimaryButton(
    text: String,
    onClick: () -> Unit,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SendDimens.ButtonHeight)
            .clip(RoundedCornerShape(SendDimens.RadiusMd))
            .background(if (enabled) palette.confirm else palette.barely)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.onConfirm else palette.textMuted,
            fontSize = SendType.Button,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
internal fun SendSecondaryButton(
    text: String,
    onClick: () -> Unit,
    palette: SendPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(SendDimens.ButtonHeight)
            .clip(RoundedCornerShape(SendDimens.RadiusMd))
            .background(palette.barely)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = palette.textSoft,
            fontSize = SendType.Button,
        )
    }
}

@Composable
internal fun SendButtonRow(
    secondaryText: String,
    primaryText: String,
    onSecondary: () -> Unit,
    onPrimary: () -> Unit,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SendDimens.Space8),
    ) {
        SendSecondaryButton(
            text = secondaryText,
            onClick = onSecondary,
            palette = palette,
            modifier = Modifier.weight(1f),
        )
        SendPrimaryButton(
            text = primaryText,
            onClick = onPrimary,
            palette = palette,
            modifier = Modifier.weight(1f),
            enabled = primaryEnabled,
        )
    }
}

/**
 * A top-bar text action (e.g. "Continue", "Done") — larger label than body text and a full 48dp
 * touch target (the bare clickable Text it replaces was ~20dp tall, well under the HIG minimum).
 */
@Composable
internal fun WizardTopBarAction(
    text: String,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = SendDimens.RowMinHeightAccessibility)
            .clip(RoundedCornerShape(SendDimens.RadiusFull))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = SendDimens.Space12, vertical = SendDimens.Space8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.textSoft else palette.hairline,
            fontSize = SendType.TopBarAction,
            fontWeight = FontWeight.W400,
        )
    }
}

// ── Recipient input (bordered field with a trailing action slot) ──

@Composable
internal fun SendInputField(
    value: String,
    onValueChange: (String) -> Unit,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    monospace: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    onFocusChanged: (Boolean) -> Unit = {},
    trailingSlot: (@Composable RowScope.() -> Unit)? = null,
) {
    val borderColor = if (error != null) palette.error else palette.hairline
    val family = if (monospace) FontFamily.Monospace else FontFamily.Default
    val selectionColors = TextSelectionColors(handleColor = palette.text, backgroundColor = palette.hairline)
    // External sets (Paste / Scan) move the cursor to the END so backspace works;
    // normal typing keeps the IME's own selection (mid-string edits still work).
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (value != field.text) field = field.copy(text = value, selection = TextRange(value.length))
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(SendDimens.RadiusMd))
                .border(BorderStroke(SendDimens.BorderWidth, borderColor), RoundedCornerShape(SendDimens.RadiusMd))
                .heightIn(min = SendDimens.RowMinHeight)
                .padding(horizontal = SendDimens.Space16, vertical = SendDimens.FieldVerticalPadding),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = palette.hairline,
                        fontSize = SendType.Body,
                        fontWeight = FontWeight.W300,
                        fontFamily = family,
                    )
                }
                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                    BasicTextField(
                        value = field,
                        onValueChange = { field = it; onValueChange(it.text) },
                        enabled = enabled,
                        textStyle = TextStyle(
                            color = palette.text,
                            fontSize = SendType.Body,
                            fontWeight = FontWeight.W300,
                            fontFamily = family,
                        ),
                        cursorBrush = SolidColor(palette.text),
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { onFocusChanged(it.isFocused) },
                    )
                }
            }
            if (trailingSlot != null) {
                Spacer(modifier = Modifier.width(SendDimens.Space8))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SendDimens.Space8),
                    content = trailingSlot,
                )
            }
        }
        if (error != null) {
            Spacer(modifier = Modifier.height(SendDimens.Space4))
            Text(
                text = error,
                color = palette.error,
                fontSize = SendType.Hint,
                fontWeight = FontWeight.W400,
                modifier = Modifier.padding(horizontal = SendDimens.Space4),
            )
        }
    }
}

// ── Amount hero (NIGHT-only; the wireframe's USD↔NIGHT toggle is dropped —
//    no price oracle, so we never show a fabricated rate) ──

@Composable
internal fun AmountHero(
    value: String,
    onValueChange: (String) -> Unit,
    error: String?,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    numberSize: androidx.compose.ui.unit.TextUnit = SendType.HeroNumber,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val numberColor = if (error != null) palette.error else palette.text
    val denomColor = if (error != null) palette.error else palette.textMuted
    val focusRequester = remember { FocusRequester() }
    // Drive the hidden field by TextFieldValue so an external set (MAX) lands
    // the cursor at the END — otherwise it sits at index 0 and backspace is a no-op.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (value != field.text) field = field.copy(text = value, selection = TextRange(value.length))
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Tappable display — focuses the hidden field to raise the keyboard.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { focusRequester.requestFocus() },
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
                Text(
                    text = value.ifEmpty { "0" },
                    color = if (value.isEmpty()) palette.hairline else numberColor,
                    fontSize = numberSize,
                    fontWeight = FontWeight.W200,
                    letterSpacing = SendType.HeroTracking,
                )
                Spacer(modifier = Modifier.width(SendDimens.Space8))
                Text(
                    text = "NIGHT",
                    color = denomColor,
                    fontSize = SendType.HeroDenom,
                    fontWeight = FontWeight.W300,
                    modifier = Modifier.padding(bottom = SendDimens.Space8),
                )
            }
        }
        // Hidden 1dp capture field.
        BasicTextField(
            value = field,
            onValueChange = { field = it; onValueChange(it.text) },
            textStyle = TextStyle(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .size(SendDimens.BorderWidth)
                .focusRequester(focusRequester)
                .onFocusChanged { onFocusChanged(it.isFocused) },
        )
        if (error != null) {
            Spacer(modifier = Modifier.height(SendDimens.Space8))
            Text(
                text = error,
                color = palette.error,
                fontSize = SendType.Caption,
                fontWeight = FontWeight.W400,
            )
        }
    }
}

// ── Recipient chip (Amount screen header — tap to go back and edit) ──

@Composable
internal fun RecipientChip(
    addressShort: String,
    palette: SendPalette,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SendDimens.RowMinHeightAccessibility)
            .clip(RoundedCornerShape(SendDimens.RadiusMd))
            .background(palette.barely)
            .clickable(onClick = onEdit)
            .padding(horizontal = SendDimens.Space16, vertical = SendDimens.FieldVerticalPadding),
    ) {
        Text(
            text = "To: $addressShort",
            color = palette.textMuted,
            fontSize = SendType.Hint,
            fontWeight = FontWeight.W400,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Edit",
            color = palette.textSoft,
            fontSize = SendType.Hint,
            fontWeight = FontWeight.W400,
        )
    }
}

// ── Token glyph (select-token rows) ──

/**
 * The token glyph for a select-token row — Midnight's symbol on a frosted disc, with a corner badge
 * marking the privacy mode (an eye for unshielded/visible on chain, a lock for shielded/private).
 * Monochrome so it sits in the palette. Mirrors the iOS `TokenAvatar`.
 */
@Composable
internal fun TokenAvatar(
    shielded: Boolean,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val tint = if (enabled) palette.textSoft else palette.textMuted
    Box(modifier = modifier.size(SendDimens.TokenAvatar)) {
        Box(
            modifier = Modifier
                .size(SendDimens.TokenAvatar)
                .clip(CircleShape)
                .background(palette.text.copy(alpha = GLASS_FILL_ALPHA))
                .border(BorderStroke(SendDimens.BorderWidth, palette.hairline), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MidnightSymbolGlyph(color = tint, size = SendDimens.Icon24)
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(SendDimens.TokenBadge)
                .clip(CircleShape)
                .background(palette.panel),
            contentAlignment = Alignment.Center,
        ) {
            if (shielded) LockGlyph(color = tint, size = SendDimens.TokenBadgeGlyph)
            else EyeGlyph(color = tint, size = SendDimens.TokenBadgeGlyph)
        }
    }
}

// ── Token + mode sub-row (Token+Mode screen) ──

@Composable
internal fun TokenModeRow(
    label: String,
    hint: String,
    palette: SendPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SendDimens.RowMinHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = SendDimens.Space16, vertical = SendDimens.FieldVerticalPadding),
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(SendDimens.Space12))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = if (enabled) palette.text else palette.textMuted,
                fontSize = SendType.Body,
                fontWeight = FontWeight.W300,
            )
            Spacer(modifier = Modifier.height(SendDimens.Space4 / 2))
            Text(
                text = hint,
                color = palette.textMuted,
                fontSize = SendType.Hint,
                fontWeight = FontWeight.W400,
            )
        }
        if (trailing != null) {
            Spacer(modifier = Modifier.width(SendDimens.Space8))
            trailing()
        }
    }
}

// ── Read-only detail row (Review / Success details) ──

@Composable
internal fun SendDetailRow(
    label: String,
    value: String,
    palette: SendPalette,
    modifier: Modifier = Modifier,
    valueMono: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SendDimens.RowMinHeightAccessibility)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = SendDimens.Space16, vertical = SendDimens.FieldVerticalPadding),
    ) {
        Text(
            text = label,
            color = palette.textSoft,
            fontSize = SendType.Caption,
            fontWeight = FontWeight.W300,
        )
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = palette.text,
            fontSize = SendType.Caption,
            fontWeight = FontWeight.W300,
            fontFamily = if (valueMono) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.End,
        )
        if (trailing != null) {
            Spacer(modifier = Modifier.width(SendDimens.Space8))
            trailing()
        }
    }
}
