package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel

/**
 * Display model for [WalletBalanceCompact]. Pre-formatted strings keep the
 * component pure UI (no SDK types) so it previews and reuses freely; the panel
 * maps [com.midnight.kuira.dapp.wallet.WalletStatus] into this.
 *
 * @param nightPrimary hero figure — formatted unshielded NIGHT.
 * @param detail status line under the hero (e.g. "NIGHT · Synced", "NIGHT · Syncing…").
 * @param dust formatted dust, or null to show a placeholder while first-loading.
 * @param dustRegistered append a ✓ to the dust row when the key is registered.
 * @param shielded formatted shielded NIGHT, or null when there is none (row shows "—").
 */
data class WalletBalanceUi(
    val nightPrimary: String,
    val detail: String,
    val dust: String?,
    val dustRegistered: Boolean,
    val shielded: String?,
)

/**
 * Compact wallet balance — the SDK/example-app density of the Kuira balance
 * "flag" (`BalanceWireframe`), condensed to live inside the pill's sheet: a
 * hero figure, a dust/shielded token card, and Send/Receive quick actions. The
 * full-size variant for the Kuira experience app shares this style at larger
 * scale.
 *
 * Stateless: render values come from [ui], live sync from [syncProgress] (the
 * branded [WalletSyncIndicator]), theme from [colors]. Actions are callbacks.
 */
@Composable
fun WalletBalanceCompact(
    ui: WalletBalanceUi,
    syncProgress: WalletSyncProgress?,
    colors: WalletPanelColors,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        // Hero card — the headline figure + status, lifted off the sheet.
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            Text(
                "BALANCE",
                color = colors.onSheetDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.W400,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                ui.nightPrimary,
                color = colors.onSheet,
                fontSize = 34.sp,
                fontWeight = FontWeight.W200,
                letterSpacing = (-1).sp,
                lineHeight = 38.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                ui.detail,
                color = colors.onSheetDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.W400,
            )
            if (syncProgress != null) {
                Spacer(Modifier.height(12.dp))
                WalletSyncIndicator(
                    progress = syncProgress.fraction,
                    label = syncProgress.label,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Token card — secondary pools.
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            TokenRow(
                "DUST",
                value = ui.dust?.let { if (ui.dustRegistered) "$it ✓" else it } ?: "—",
                colors = colors,
            )
            TokenRow("SHIELDED", value = ui.shielded ?: "—", colors = colors)
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
        ) {
            QuickAction("↑", "Send", colors, onSend)
            QuickAction("↓", "Receive", colors, onReceive)
        }
    }
}

@Composable
private fun TokenRow(label: String, value: String, colors: WalletPanelColors) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
    ) {
        Text("•", color = colors.onSheetSubtle, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, color = colors.onSheet, fontSize = 13.sp, fontWeight = FontWeight.W300, letterSpacing = 1.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = colors.onSheet, fontSize = 14.sp, fontWeight = FontWeight.W300, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun QuickAction(glyph: String, label: String, colors: WalletPanelColors, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.button)
                .border(1.dp, colors.onSheetSubtle, CircleShape),
        ) {
            Text(glyph, color = colors.onSheet, fontSize = 20.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = colors.onSheetDim, fontSize = 12.sp)
    }
}

// ── Previews ──

private val SampleDefault = WalletBalanceUi(
    nightPrimary = "1,234.567890",
    detail = "NIGHT · Synced",
    dust = "98,765.432109",
    dustRegistered = true,
    shielded = null,
)

@Preview(name = "Balance compact · default", widthDp = 360, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun PreviewBalanceDefault() =
    Column(Modifier.padding(20.dp)) { WalletBalanceCompact(SampleDefault, null, WalletPanelColors.Default, {}, {}) }

@Preview(name = "Balance compact · syncing", widthDp = 360, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun PreviewBalanceSyncing() =
    Column(Modifier.padding(20.dp)) {
        WalletBalanceCompact(SampleDefault.copy(detail = "NIGHT · Syncing…"), WalletSyncProgress(0.68f, "Syncing dust"), WalletPanelColors.Default, {}, {})
    }
