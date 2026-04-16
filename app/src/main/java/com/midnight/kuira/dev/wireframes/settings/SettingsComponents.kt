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
 * Row inside a GlassPanel section. [readOnly] = true drops the chevron
 * and dims the right-value to `LightMuted`. Tapping a read-only row is
 * a no-op.
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
    contentDesc: String? = null,
    onClick: () -> Unit = {},
) {
    val clickModifier = if (readOnly) Modifier else Modifier.clickable(onClick = onClick)
    val rightValueColor = if (readOnly) palette.LightMuted else palette.LightSoft

    // Row sizing follows Material 3 ListItem single-line defaults (56dp min,
    // 16dp inner padding) and iOS HIG grouped-list rhythm (~60pt rows). The
    // Dusk spec mandates ≥48dp as a floor; 56dp + 16dp vertical padding is
    // where production wallets (Phantom, Rainbow, MetaMask) actually sit.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(clickModifier)
            .then(
                if (contentDesc != null) Modifier.semantics { contentDescription = contentDesc }
                else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = palette.Light,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Text(
            text = label,
            color = palette.Light,
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
        if (!readOnly) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = palette.LightMuted,
                modifier = Modifier.size(16.dp),
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
        modifier = Modifier.padding(horizontal = 16.dp),
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
    Spacer(modifier = Modifier.size(12.dp))
    content()
}

/** Empty Box used as a plain spacer column wrapper. */
@Composable
fun SettingsGap(size: Int) {
    Box(modifier = Modifier.size(size.dp))
}
