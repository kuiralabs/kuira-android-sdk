package com.midnight.kuira.feature.balance.redesign

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField

enum class WireframeState { DEFAULT, LOADING_FIRST, SYNCING, ERROR, OFFLINE }

@Composable
fun BalanceWireframe(
    state: WireframeState = WireframeState.DEFAULT,
    showBackupBanner: Boolean = true,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.Void),
    ) {
        // Ambient star background — palette-aware so light mode renders
        // faint dark dots on the off-white surface instead of invisible
        // white-on-white.
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            // Light mode needs more aggressive alpha because dark dots on
            // off-white have much less perceptual weight than white dots on
            // pure black. 0.55 puts peak stars around 40% black — visible
            // but below the LightMuted 40% label text so they don't compete.
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            // Top bar
            TopBar(palette = palette, onBack = onBack)

            // Scrollable content
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + 48.dp),
            ) {
                // Backup banner
                if (showBackupBanner) {
                    Spacer(modifier = Modifier.height(16.dp))
                    BackupBanner(palette = palette)
                    Spacer(modifier = Modifier.height(24.dp))
                } else {
                    Spacer(modifier = Modifier.height(32.dp))
                }

                // Hero — wrapped in a glass panel so the numeric hero
                // doesn't sit directly on noisy stars.
                GlassPanel(
                    tint = palette.contentPanel,
                    border = palette.LightFaint,
                ) {
                    BalanceHero(state = state, palette = palette)
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Secondary tokens — their own glass panel.
                val isLoading = state == WireframeState.LOADING_FIRST
                GlassPanel(
                    tint = palette.contentPanel,
                    border = palette.LightFaint,
                ) {
                    TokenRow(
                        label = "DUST",
                        value = dustValue(state),
                        denomination = "DUST",
                        isLoading = isLoading,
                        palette = palette,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TokenRow(
                        label = "SHIELDED",
                        value = null,
                        denomination = null,
                        lockedText = if (isLoading) null else "locked — tap to unlock",
                        isLoading = isLoading,
                        palette = palette,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Quick actions
                QuickActionsRow(palette = palette)

                Spacer(modifier = Modifier.height(32.dp))

                // Address chip
                AddressChipSection(palette = palette)
            }
        }
    }
}

@Composable
private fun TopBar(palette: DuskPalette, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back to dev portal",
            tint = palette.Light,
            modifier = Modifier
                .size(24.dp)
                .clickable { onBack() },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = "KUIRA",
            color = palette.Light,
            fontSize = 18.sp,
            fontWeight = FontWeight.W300,
            letterSpacing = 3.sp,
        )
        Spacer(modifier = Modifier.weight(1f))

        // Network badge
        NetworkBadge(network = "PREPROD", palette = palette)
        Spacer(modifier = Modifier.width(12.dp))

        // Settings icon
        Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = "Open settings",
            tint = palette.Light,
            modifier = Modifier.size(24.dp),
        )
    }
    HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)
}

@Composable
private fun NetworkBadge(network: String, palette: DuskPalette) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(9999.dp))
            .background(palette.LightBarely)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = network,
            color = palette.LightSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.W400,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun BackupBanner(palette: DuskPalette) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.LightBarely)
            .clickable { }
            .padding(horizontal = 16.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Key,
            contentDescription = null,
            tint = palette.Light,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Back up your recovery phrase",
            color = palette.Light,
            fontSize = 13.sp,
            fontWeight = FontWeight.W400,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = palette.LightMuted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun BalanceHero(state: WireframeState, palette: DuskPalette) {
    // Label
    Text(
        text = "BALANCE",
        color = palette.LightMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.W400,
        letterSpacing = 3.sp,
    )
    Spacer(modifier = Modifier.height(20.dp))

    // Headline (numeric)
    if (state == WireframeState.LOADING_FIRST) {
        ShimmerBlock(height = 48.dp, widthFraction = 0.6f, palette = palette)
    } else {
        Text(
            text = "1,234.567890",
            color = palette.Light,
            fontSize = 44.sp,
            fontWeight = FontWeight.W200,
            letterSpacing = (-1).sp,
            lineHeight = 48.sp,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))

    // Detail
    Text(
        text = detailText(state),
        color = if (state == WireframeState.DEFAULT || state == WireframeState.LOADING_FIRST)
            palette.LightMuted else palette.LightSoft,
        fontSize = 13.sp,
        fontWeight = FontWeight.W400,
        lineHeight = 18.sp,
        modifier = Modifier.animateContentSize(),
    )

    // Error retry row
    if (state == WireframeState.ERROR) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Could not update balance",
                color = palette.LightMuted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Retry",
                color = palette.Light,
                fontSize = 13.sp,
                modifier = Modifier.clickable { },
            )
        }
    }
}

@Composable
private fun TokenRow(
    label: String,
    value: String?,
    denomination: String?,
    lockedText: String? = null,
    isLoading: Boolean = false,
    palette: DuskPalette,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clickable { },
    ) {
        Text(
            text = "\u2022",
            color = palette.LightFaint,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                text = value,
                color = palette.Light,
                fontSize = 14.sp,
                fontWeight = FontWeight.W300,
            )
            if (denomination != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = denomination,
                    color = palette.LightMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W400,
                )
            }
        } else if (isLoading) {
            ShimmerBlock(height = 16.dp, widthFraction = 0.35f, palette = palette)
        } else if (lockedText != null) {
            Text(
                text = lockedText,
                color = palette.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
            )
        }
    }
}

@Composable
private fun QuickActionsRow(palette: DuskPalette) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth(),
    ) {
        QuickActionCircle(
            icon = Icons.Filled.ArrowUpward,
            label = "Send",
            contentDesc = "Send a transaction",
            palette = palette,
            onClick = { },
        )
        QuickActionCircle(
            icon = Icons.Filled.QrCode2,
            label = "Receive",
            contentDesc = "Open receive screen",
            palette = palette,
            onClick = { },
        )
    }
}

@Composable
private fun AddressChipSection(palette: DuskPalette) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Unshielded", "Shielded")
    val addresses = listOf("mn_add…f5a2", "shield…3f8e")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.LightBarely)
            .padding(4.dp),
    ) {
        tabs.forEachIndexed { index, tab ->
            val isActive = index == selectedTab
            // Inverted-pole active treatment: active segment uses the opposite
            // base color (Light fill + Void text in dark mode; Void fill +
            // Light text in light mode). Inactive segments fade their text
            // using LightMuted / LightFaint so the active one "wins" visually.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .then(
                        if (isActive) Modifier.background(palette.Light)
                        else Modifier
                    )
                    .clickable { selectedTab = index }
                    .padding(vertical = 12.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = tab,
                        color = if (isActive) palette.Void else palette.LightMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W400,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = addresses[index],
                        color = if (isActive) palette.Void else palette.LightMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W400,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

private fun detailText(state: WireframeState): String = when (state) {
    WireframeState.DEFAULT -> "NIGHT \u00B7 Synced 12s ago"
    WireframeState.LOADING_FIRST -> "Loading\u2026"
    WireframeState.SYNCING -> "NIGHT \u00B7 Syncing\u2026"
    WireframeState.ERROR -> "NIGHT \u00B7 Sync failed"
    WireframeState.OFFLINE -> "NIGHT \u00B7 Offline \u00B7 showing cached"
}

private fun dustValue(state: WireframeState): String? = when (state) {
    WireframeState.LOADING_FIRST -> null
    else -> "98,765.432109876543"
}
