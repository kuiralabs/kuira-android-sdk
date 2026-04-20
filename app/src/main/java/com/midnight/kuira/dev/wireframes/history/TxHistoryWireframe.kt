package com.midnight.kuira.dev.wireframes.history

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.dev.wireframes.shared.DuskTokens
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.dev.wireframes.settings.SettingsDivider
import com.midnight.kuira.dev.wireframes.settings.SettingsRow
import com.midnight.kuira.dev.wireframes.settings.SettingsSectionHeader
import com.midnight.kuira.feature.balance.redesign.DuskPalette
import com.midnight.kuira.feature.balance.redesign.ShimmerBlock

enum class TxHistoryScreen { LIST, DETAIL }

enum class TxHistoryState {
    DEFAULT,
    LOADING_FIRST,
    SYNCING,
    EMPTY,
    ERROR,
    OFFLINE,
}

@Composable
fun TxHistoryWireframe(
    screen: TxHistoryScreen = TxHistoryScreen.LIST,
    state: TxHistoryState = TxHistoryState.DEFAULT,
    palette: DuskPalette = DuskPalette.DarkMode,
    onBack: () -> Unit = {},
) {
    when (screen) {
        TxHistoryScreen.LIST -> HistoryListScreen(state = state, palette = palette, onBack = onBack)
        TxHistoryScreen.DETAIL -> TxDetailScreen(palette = palette, onBack = onBack)
    }
}

// ═══════════════════════════════════════════════════════════════════
// History List
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun HistoryListScreen(
    state: TxHistoryState,
    palette: DuskPalette,
    onBack: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    val title = when (state) {
        TxHistoryState.SYNCING -> "Activity \u00B7"
        TxHistoryState.OFFLINE -> "Activity \u00B7 Offline"
        else -> "Activity"
    }

    Box(
        modifier = Modifier.fillMaxSize().background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            TopBar(title = title, palette = palette, onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DuskTokens.Space16)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + DuskTokens.Space24),
            ) {
                when (state) {
                    TxHistoryState.EMPTY -> EmptyContent(palette)
                    TxHistoryState.LOADING_FIRST -> LoadingContent(palette)
                    else -> DefaultListContent(state, palette)
                }
            }
        }
    }
}

@Composable
private fun DefaultListContent(state: TxHistoryState, palette: DuskPalette) {
    Spacer(modifier = Modifier.height(DuskTokens.Space16))

    // Day group: TODAY
    SettingsSectionHeader(label = "TODAY", palette = palette)
    Spacer(modifier = Modifier.size(DuskTokens.Space12))
    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
        TxRow(
            typeBadge = "SENT",
            amount = "\u22125.5 NIGHT",
            detail = "To mn_addr\u2026f5a2",
            status = "Confirmed",
            palette = palette,
        )
        SettingsDivider(palette = palette)
        TxRow(
            typeBadge = "RECEIVED",
            amount = "+10.0 NIGHT",
            detail = "From mn_addr\u2026b3c1",
            status = "Confirmed",
            palette = palette,
        )
    }

    Spacer(modifier = Modifier.height(DuskTokens.Space32))

    // Day group: YESTERDAY
    SettingsSectionHeader(label = "YESTERDAY", palette = palette)
    Spacer(modifier = Modifier.size(DuskTokens.Space12))
    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
        TxRow(
            typeBadge = "DUST",
            amount = "+0.000001 DUST",
            detail = "Generation",
            status = "Confirmed",
            palette = palette,
        )
        SettingsDivider(palette = palette)
        TxRow(
            typeBadge = "SHIELDED",
            amount = "\u22122.0 NIGHT",
            detail = "To mn_shield\u20263f8e",
            status = "Confirmed",
            palette = palette,
        )
    }

    // Error inline
    if (state == TxHistoryState.ERROR) {
        Spacer(modifier = Modifier.height(DuskTokens.Space24))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Could not refresh",
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
private fun EmptyContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(DuskTokens.Space48))

    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = DuskTokens.PanelPaddingHero) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.History,
                contentDescription = null,
                tint = palette.LightMuted,
                modifier = Modifier.size(DuskTokens.Icon32),
            )
            Spacer(modifier = Modifier.height(DuskTokens.Space20))
            Text(
                text = "No activity yet",
                color = palette.Light,
                fontSize = 18.sp,
                fontWeight = FontWeight.W300,
                lineHeight = 24.sp,
            )
            Spacer(modifier = Modifier.height(DuskTokens.Space8))
            Text(
                text = "Send or receive NIGHT to see your transactions here.",
                color = palette.LightMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
                lineHeight = 18.sp,
            )
        }
    }
}

@Composable
private fun LoadingContent(palette: DuskPalette) {
    Spacer(modifier = Modifier.height(DuskTokens.Space16))

    SettingsSectionHeader(label = "TODAY", palette = palette)
    Spacer(modifier = Modifier.size(DuskTokens.Space12))
    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
        repeat(3) { i ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DuskTokens.Space16, vertical = DuskTokens.Space12),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ShimmerBlock(height = 20.dp, widthFraction = 0.25f, palette = palette)
                    ShimmerBlock(height = 20.dp, widthFraction = 0.3f, palette = palette)
                }
                Spacer(modifier = Modifier.height(DuskTokens.Space8))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ShimmerBlock(height = 14.dp, widthFraction = 0.4f, palette = palette)
                    ShimmerBlock(height = 14.dp, widthFraction = 0.2f, palette = palette)
                }
            }
            if (i < 2) SettingsDivider(palette = palette)
        }
    }

    Spacer(modifier = Modifier.height(DuskTokens.Space32))

    ShimmerBlock(height = 14.dp, widthFraction = 0.3f, palette = palette)
    Spacer(modifier = Modifier.size(DuskTokens.Space12))
    GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
        repeat(2) { i ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DuskTokens.Space16, vertical = DuskTokens.Space12),
            ) {
                ShimmerBlock(height = 20.dp, widthFraction = 0.5f, palette = palette)
                Spacer(modifier = Modifier.height(DuskTokens.Space8))
                ShimmerBlock(height = 14.dp, widthFraction = 0.35f, palette = palette)
            }
            if (i < 1) SettingsDivider(palette = palette)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════
// Tx Detail
// ═══════════════════════════════════════════════════════════════════

@Composable
private fun TxDetailScreen(palette: DuskPalette, onBack: () -> Unit) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()

    Box(
        modifier = Modifier.fillMaxSize().background(palette.Void),
    ) {
        StarField(
            modifier = Modifier.fillMaxSize(),
            color = palette.Light,
            alpha = if (palette === DuskPalette.LightMode) 0.55f else 1f,
            starCount = 60,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = statusBarPadding.calculateTopPadding()),
        ) {
            TopBar(title = "Transaction", palette = palette, onBack = onBack)

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DuskTokens.Space16)
                    .padding(bottom = navBarPadding.calculateBottomPadding() + DuskTokens.Space24),
            ) {
                Spacer(modifier = Modifier.height(DuskTokens.Space32))

                // Amount hero
                GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = DuskTokens.PanelPaddingHero) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "\u22125.5",
                            color = palette.Light,
                            fontSize = 44.sp,
                            fontWeight = FontWeight.W200,
                            letterSpacing = (-1).sp,
                            lineHeight = 48.sp,
                        )
                        Spacer(modifier = Modifier.height(DuskTokens.Space4))
                        Text(
                            text = "NIGHT",
                            color = palette.LightMuted,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.W300,
                        )
                        Spacer(modifier = Modifier.height(DuskTokens.Space8))
                        TxTypeBadge(label = "SENT", palette = palette)
                        Spacer(modifier = Modifier.height(DuskTokens.Space4))
                        Text(
                            text = "Confirmed",
                            color = palette.LightMuted,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W400,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(DuskTokens.Space32))

                SettingsSectionHeader(label = "DETAILS", palette = palette)
                Spacer(modifier = Modifier.size(DuskTokens.Space12))

                GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
                    SettingsRow(label = "Date", rightValue = "2026-04-18T03:27:11Z", readOnly = true, palette = palette)
                    SettingsDivider(palette = palette)
                    SettingsRow(label = "Block", rightValue = "1,234,567", readOnly = true, palette = palette)
                    SettingsDivider(palette = palette)
                    SettingsRow(
                        label = "Hash", rightValue = "abc12345\u2026def678", rightValueMono = true,
                        readOnly = true, trailingIcon = Icons.Filled.ContentCopy, palette = palette,
                    )
                    SettingsDivider(palette = palette)
                    SettingsRow(
                        label = "From", rightValue = "mn_addr\u2026b3c1", rightValueMono = true,
                        readOnly = true, trailingIcon = Icons.Filled.ContentCopy, palette = palette,
                    )
                    SettingsDivider(palette = palette)
                    SettingsRow(
                        label = "To", rightValue = "mn_addr\u2026f5a2", rightValueMono = true,
                        readOnly = true, trailingIcon = Icons.Filled.ContentCopy, palette = palette,
                    )
                    SettingsDivider(palette = palette)
                    SettingsRow(label = "Fee", rightValue = "0.001 NIGHT", readOnly = true, palette = palette)
                }

                Spacer(modifier = Modifier.height(DuskTokens.Space32))

                GlassPanel(tint = palette.contentPanel, border = palette.LightFaint, contentPadding = 0.dp) {
                    SettingsRow(label = "View on explorer", palette = palette)
                }
            }
        }
    }
}

@Composable
private fun TopBar(title: String, palette: DuskPalette, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(DuskTokens.TopBarHeight)
            .padding(horizontal = DuskTokens.Space16),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = palette.Light,
            modifier = Modifier.size(DuskTokens.Icon24).clickable { onBack() },
        )
        Spacer(modifier = Modifier.width(DuskTokens.Space16))
        Text(
            text = title,
            color = palette.Light,
            fontSize = 14.sp,
            fontWeight = FontWeight.W300,
        )
    }
    HorizontalDivider(color = palette.LightFaint, thickness = 1.dp)
}
