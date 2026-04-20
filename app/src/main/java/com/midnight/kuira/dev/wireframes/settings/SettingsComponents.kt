package com.midnight.kuira.dev.wireframes.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.dev.wireframes.shared.DuskTokens
import com.midnight.kuira.feature.balance.redesign.DuskPalette

/** Uppercase letter-spaced section heading placed above a GlassPanel. */
@Composable
fun SettingsSectionHeader(
    label: String,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = palette.LightMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 3.sp,
        modifier = modifier,
    )
}

/**
 * Row inside a GlassPanel section.
 *
 * [readOnly] controls visual treatment only: `true` dims the right-value
 * to `LightMuted` and drops the default chevron. Interaction is governed
 * by whether a [trailingIcon] is provided — a read-only-looking row can
 * still be actionable (e.g., Send's FROM address row copies on tap).
 *
 * [trailingIcon] overrides the default trailing slot:
 *  - `null` + nav (`!readOnly`)       → default chevron (tap navigates)
 *  - `null` + read-only               → no trailing, no tap
 *  - explicit + either mode           → custom icon (tap fires `onClick`)
 */
@Composable
fun SettingsRow(
    label: String,
    palette: DuskPalette,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    rightValue: String? = null,
    rightValueMono: Boolean = false,
    readOnly: Boolean = false,
    trailingIcon: ImageVector? = null,
    contentDesc: String? = null,
    onClick: () -> Unit = {},
) {
    // Row is clickable when it's a nav row OR when an explicit trailing
    // action is present — so FROM-style rows (readOnly-looking + copy
    // icon) remain tappable.
    val isClickable = !readOnly || trailingIcon != null
    val clickModifier = if (isClickable) Modifier.clickable(onClick = onClick) else Modifier
    // Read-only rows flip the emphasis: label recedes (LightSoft),
    // value pops (Light). Labels are context; values are data.
    // Nav rows keep label bright (you're scanning what to tap).
    val labelColor = if (readOnly) palette.LightSoft else palette.Light
    val rightValueColor = if (readOnly) palette.Light else palette.LightSoft
    val resolvedTrailing: ImageVector? = trailingIcon
        ?: if (!readOnly) Icons.AutoMirrored.Filled.ArrowForward else null

    // Row sizing follows Material 3 ListItem single-line defaults (56dp min,
    // 16dp inner padding) and iOS HIG grouped-list rhythm (~60pt rows). The
    // Dusk spec mandates ≥48dp as a floor; 56dp + 16dp vertical padding is
    // where production wallets (Phantom, Rainbow, MetaMask) actually sit.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = DuskTokens.RowMinHeight)
            .then(clickModifier)
            .then(
                if (contentDesc != null) Modifier.semantics { contentDescription = contentDesc }
                else Modifier,
            )
            .padding(horizontal = DuskTokens.Space16, vertical = DuskTokens.Space16),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = palette.Light,
                modifier = Modifier.size(DuskTokens.Icon24),
            )
            Spacer(modifier = Modifier.width(DuskTokens.Space12))
        }
        Text(
            text = label,
            color = labelColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f),
        )
        if (rightValue != null) {
            Text(
                text = rightValue,
                color = rightValueColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 20.sp,
                fontFamily = if (rightValueMono) FontFamily.Monospace else FontFamily.Default,
            )
        }
        if (resolvedTrailing != null) {
            Spacer(modifier = Modifier.width(DuskTokens.Space8))
            Icon(
                imageVector = resolvedTrailing,
                contentDescription = null,
                tint = palette.LightMuted,
                modifier = Modifier.size(DuskTokens.Icon16),
            )
        }
    }
}

/**
 * DangerRow — identical typography and color to SettingsRow. Destructive
 * signal is carried by the [leadingIcon] choice (trash / delete / warning)
 * and the ConfirmationSheet that the caller opens in [onClick]. TalkBack
 * announcement is prefixed "Destructive action, " via contentDesc.
 */
@Composable
fun DangerRow(
    label: String,
    leadingIcon: ImageVector,
    palette: DuskPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        label = label,
        palette = palette,
        modifier = modifier,
        leadingIcon = leadingIcon,
        contentDesc = "Destructive action, $label",
        onClick = onClick,
    )
}

/** 1dp LightFaint hairline divider used between rows inside a GlassPanel. */
@Composable
fun SettingsDivider(palette: DuskPalette) {
    HorizontalDivider(
        color = palette.LightFaint,
        thickness = 1.dp,
        modifier = Modifier.padding(horizontal = DuskTokens.Space16),
    )
}

/** Helper: wraps the section header + panel in a single Column with space-12 gap
 *  (sectioned-list rhythm per `_prefix.md` LIST ROWS). */
@Composable
fun SettingsSection(
    label: String,
    palette: DuskPalette,
    content: @Composable () -> Unit,
) {
    SettingsSectionHeader(label = label, palette = palette)
    Spacer(modifier = Modifier.size(DuskTokens.Space12))
    content()
}

/** Empty Box used as a plain spacer column wrapper. */
@Composable
fun SettingsGap(size: Int) {
    Box(modifier = Modifier.size(size.dp))
}
