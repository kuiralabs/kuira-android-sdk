package com.midnight.kuira.dev.wireframes.send

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.feature.balance.redesign.DuskPalette

// ── Palette-aware buttons (production DuskButton hardcodes MidnightColors) ──

@Composable
fun DuskPrimaryButtonPaletted(
    text: String,
    onClick: () -> Unit,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) palette.Confirm else palette.LightBarely)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) palette.Void else palette.LightMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun DuskSecondaryButtonPaletted(
    text: String,
    onClick: () -> Unit,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.ConfirmSurface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = palette.RejectText,
            fontSize = 13.sp,
        )
    }
}

@Composable
fun DuskButtonRowPaletted(
    secondaryText: String,
    primaryText: String,
    onSecondary: () -> Unit,
    onPrimary: () -> Unit,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        DuskSecondaryButtonPaletted(
            text = secondaryText,
            onClick = onSecondary,
            palette = palette,
            modifier = Modifier.weight(1f),
        )
        DuskPrimaryButtonPaletted(
            text = primaryText,
            onClick = onPrimary,
            palette = palette,
            modifier = Modifier.weight(1f),
            enabled = primaryEnabled,
        )
    }
}

// ── DuskInputField (address input on Screen 2b) ──

@Composable
fun DuskInputField(
    value: String,
    onValueChange: (String) -> Unit,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    error: String? = null,
    monospace: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    trailingSlot: (@Composable RowScope.() -> Unit)? = null,
) {
    val borderColor = if (error != null) palette.Light else palette.LightFaint
    val valueFontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
    val selectionColors = TextSelectionColors(
        handleColor = palette.Light,
        backgroundColor = palette.LightFaint,
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        color = palette.LightFaint,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.W300,
                        lineHeight = 20.sp,
                        fontFamily = valueFontFamily,
                    )
                }
                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = TextStyle(
                            color = palette.Light,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.W300,
                            lineHeight = 20.sp,
                            fontFamily = valueFontFamily,
                        ),
                        cursorBrush = SolidColor(palette.Light),
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (trailingSlot != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    content = trailingSlot,
                )
            }
        }

        if (error != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error,
                color = palette.LightMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 16.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

// ── TokenModeCard (Screen 2a) ──

@Composable
fun TokenModeSubRow(
    label: String,
    hint: String,
    palette: DuskPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = palette.Light,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 20.sp,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = hint,
                color = palette.LightMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 16.sp,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = palette.LightMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

// ── AmountHeroInput (Screen 2c) ──

/**
 * Hero-scale amount entry with denomination swap toggle (↕).
 *
 * Two input modes:
 * - `NIGHT`: type in NIGHT, see "~$X.XX" below
 * - `USD`:   type in USD, see "~X.XX NIGHT" below
 *
 * The ↕ icon flips between modes. The wireframe uses a mock exchange
 * rate ($1 = 1 NIGHT) for display purposes; real rate comes from an
 * oracle in production.
 */
@Composable
fun AmountHeroInput(
    value: String,
    onValueChange: (String) -> Unit,
    inputMode: AmountInputMode,
    onToggleMode: () -> Unit,
    conversionHint: String?,
    error: String?,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
) {
    val denomination = if (inputMode == AmountInputMode.NIGHT) "NIGHT" else "USD"

    val numberColor: Color
    val denomColor: Color
    if (error != null) {
        numberColor = palette.ErrorText
        denomColor = palette.ErrorText
    } else {
        numberColor = palette.Light
        denomColor = palette.LightMuted
    }

    val focusRequester = remember { FocusRequester() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Tappable display area — tapping focuses the hidden text field
        // to open the keyboard. Display is a regular Row so it centers
        // naturally without BasicTextField's fill-width measurement.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { focusRequester.requestFocus() },
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (value.isEmpty()) "0" else value,
                    color = if (value.isEmpty()) palette.LightFaint else numberColor,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.W200,
                    letterSpacing = (-1).sp,
                    lineHeight = 48.sp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = denomination,
                    color = denomColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.W300,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                // Swap toggle (↕)
                Box(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(9999.dp))
                        .background(palette.LightBarely)
                        .clickable { onToggleMode() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "\u21C5",
                        color = palette.LightMuted,
                        fontSize = 16.sp,
                    )
                }
            }
        }

        // Hidden BasicTextField — captures keyboard input. 0-height so
        // it doesn't affect layout; focusRequester opens the keyboard
        // when the display area is tapped.
        BasicTextField(
            value = value,
            onValueChange = { newVal ->
                val parts = newVal.split(".")
                if (parts.size <= 1 || parts[1].length <= 6) {
                    onValueChange(newVal.filter { it.isDigit() || it == '.' })
                }
            },
            textStyle = TextStyle(
                color = Color.Transparent,
                fontSize = 1.sp,
            ),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester),
        )

        // Conversion hint (e.g., "~$5.50" or "~5.5 NIGHT")
        if (conversionHint != null && error == null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = conversionHint,
                color = palette.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
            )
        }

        // Error caption
        if (error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                color = palette.ErrorText,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
            )
        }
    }
}

enum class AmountInputMode { NIGHT, USD }

// ── RecipientChip (Screen 2c, below top bar) ──

@Composable
fun RecipientChip(
    addressShort: String,
    palette: DuskPalette,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.LightBarely)
            .clickable(onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "To: $addressShort",
            color = palette.LightMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.W400,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.Edit,
            contentDescription = "Edit recipient",
            tint = palette.LightMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}
