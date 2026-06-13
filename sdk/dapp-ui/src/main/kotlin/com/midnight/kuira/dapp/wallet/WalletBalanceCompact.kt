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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.midnight.kuira.core.designsystem.effect.ShimmerBlock

/**
 * Display model for [WalletBalanceCompact]. Pre-formatted strings keep the
 * component pure UI (no SDK types) so it previews and reuses freely; the panel
 * maps [com.midnight.kuira.dapp.wallet.WalletStatus] into this.
 *
 * NIGHT is one asset with two pools, so it reads as a single figure: [nightTotal]
 * is the headline (public + private), [privateNight] notes the shielded portion
 * as a chip and is null when there's none. DUST — a separate token (gas) — gets
 * its own row.
 *
 * @param nightTotal hero figure — formatted total NIGHT (public + private).
 * @param statusLabel short status under the hero, e.g. "Synced" / "Syncing…".
 * @param privateNight formatted shielded NIGHT for the "🛡 private" chip, or null
 *   when there is none (chip hidden — no dangling dash).
 * @param dust formatted dust, or null while first-loading.
 * @param dustRegistered true → ✓ on the dust row; false → a Register affordance.
 */
data class WalletBalanceUi(
    val nightTotal: String,
    val statusLabel: String,
    val privateNight: String?,
    val dust: String?,
    val dustRegistered: Boolean,
)

/**
 * Compact wallet balance — the SDK/example-app density of the Kuira balance
 * "flag" (`BalanceWireframe`), condensed for the pill's sheet:
 *  - a NIGHT hero card with an inline ⟳ refresh and the 🛡 private chip,
 *  - a slim DUST row that carries its own Register affordance,
 *  - Send / Receive quick actions.
 * The full-size variant for the Kuira experience app shares this style at scale.
 *
 * Stateless: values from [ui], live sync from [syncProgress] (the branded
 * [WalletSyncIndicator]), theme from [colors]; everything else is callbacks.
 *
 * @param busy disables the refresh / register / send affordances mid-operation.
 * @param onSend null → Send is shown but disabled with a "soon" hint (until #240).
 */
@Composable
fun WalletBalanceCompact(
    ui: WalletBalanceUi,
    syncProgress: WalletSyncProgress?,
    colors: WalletPanelColors,
    onReceive: () -> Unit,
    onRefresh: () -> Unit,
    onRegister: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    onSend: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        // NIGHT hero — headline figure, inline refresh, private chip, live sync.
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "BALANCE",
                    color = colors.onSheetDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W400,
                    letterSpacing = 3.sp,
                    modifier = Modifier.weight(1f),
                )
                IconCircle(glyph = "⟳", colors = colors, enabled = !busy, onClick = onRefresh)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                ui.nightTotal,
                color = colors.onSheet,
                fontSize = 34.sp,
                fontWeight = FontWeight.W200,
                letterSpacing = (-1).sp,
                lineHeight = 38.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("NIGHT · ${ui.statusLabel}", color = colors.onSheetDim, fontSize = 13.sp)
                if (ui.privateNight != null) {
                    Text(
                        "  ·  🛡 ${ui.privateNight} private",
                        color = colors.onSheetDim,
                        fontSize = 13.sp,
                    )
                }
            }
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

        // DUST — separate token; carries its own Register affordance.
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
            ) {
                Text("•", color = colors.onSheetSubtle, fontSize = 14.sp)
                Spacer(Modifier.width(8.dp))
                Text("DUST", color = colors.onSheet, fontSize = 13.sp, fontWeight = FontWeight.W300, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    ui.dust ?: "—",
                    color = colors.onSheet,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.W300,
                    fontFamily = FontFamily.Monospace,
                )
                if (ui.dust != null) {
                    Spacer(Modifier.width(10.dp))
                    if (ui.dustRegistered) {
                        Text("✓", color = colors.accent, fontSize = 14.sp)
                    } else {
                        TextChip("Register", colors = colors, enabled = !busy, onClick = onRegister)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
        ) {
            // Send is always shown; disabled with a "soon" hint until #240 wires it.
            QuickAction(
                glyph = "↑",
                label = "Send",
                colors = colors,
                enabled = onSend != null && !busy,
                hint = if (onSend == null) "soon" else null,
                onClick = onSend ?: {},
            )
            QuickAction(glyph = "↓", label = "Receive", colors = colors, enabled = true, onClick = onReceive)
        }
    }
}

@Composable
private fun IconCircle(glyph: String, colors: WalletPanelColors, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) colors.onSheet else colors.onSheetSubtle
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .border(1.dp, colors.onSheetSubtle, CircleShape)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
    ) {
        Text(glyph, color = tint, fontSize = 16.sp)
    }
}

@Composable
private fun TextChip(text: String, colors: WalletPanelColors, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) colors.onSheet else colors.onSheetSubtle,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.onSheetSubtle, RoundedCornerShape(8.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun QuickAction(
    glyph: String,
    label: String,
    colors: WalletPanelColors,
    enabled: Boolean,
    onClick: () -> Unit,
    hint: String? = null,
) {
    val tint = if (enabled) colors.onSheet else colors.onSheetSubtle
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = if (enabled) Modifier.clickable { onClick() } else Modifier) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(colors.button)
                .border(1.dp, colors.onSheetSubtle, CircleShape),
        ) {
            Text(glyph, color = tint, fontSize = 20.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) colors.onSheetDim else colors.onSheetSubtle, fontSize = 12.sp)
        if (hint != null) {
            Text(hint, color = colors.onSheetSubtle, fontSize = 10.sp)
        }
    }
}

/**
 * First-load skeleton — shimmer placeholders shaped like the real hero + dust
 * cards, so the layout doesn't jump when data lands. Used for None/Loading.
 */
@Composable
fun WalletBalanceLoading(colors: WalletPanelColors, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            Text("BALANCE", color = colors.onSheetDim, fontSize = 11.sp, fontWeight = FontWeight.W400, letterSpacing = 3.sp)
            Spacer(Modifier.height(16.dp))
            ShimmerBlock(height = 40.dp, widthFraction = 0.6f)
            Spacer(Modifier.height(10.dp))
            ShimmerBlock(height = 14.dp, widthFraction = 0.35f)
        }
        Spacer(Modifier.height(10.dp))
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
                ShimmerBlock(height = 16.dp, widthFraction = 0.45f)
            }
        }
    }
}

/**
 * Balance error — keeps the hero card frame but states the failure and offers a
 * Retry, mirroring the wireframe's ERROR treatment (never a bare red string).
 */
@Composable
fun WalletBalanceError(
    message: String,
    colors: WalletPanelColors,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(tint = colors.button, border = colors.onSheetSubtle, modifier = modifier) {
        Text("BALANCE", color = colors.onSheetDim, fontSize = 11.sp, fontWeight = FontWeight.W400, letterSpacing = 3.sp)
        Spacer(Modifier.height(12.dp))
        Text("Couldn't load balance", color = colors.error, fontSize = 18.sp, fontWeight = FontWeight.W300)
        Spacer(Modifier.height(6.dp))
        Text(message, color = colors.onSheetDim, fontSize = 12.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(14.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextChip("Retry", colors = colors, enabled = true, onClick = onRetry)
        }
    }
}

// ── Previews ──

private val SampleDefault = WalletBalanceUi(
    nightTotal = "10,000",
    statusLabel = "Synced",
    privateNight = "250",
    dust = "5,195.065469",
    dustRegistered = true,
)

@Preview(name = "Balance compact · default", widthDp = 360, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun PreviewBalanceDefault() =
    Column(Modifier.padding(20.dp)) {
        WalletBalanceCompact(SampleDefault, syncProgress = null, colors = WalletPanelColors.Default, onReceive = {}, onRefresh = {}, onRegister = {})
    }

@Preview(name = "Balance compact · syncing + unregistered", widthDp = 360, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun PreviewBalanceSyncing() =
    Column(Modifier.padding(20.dp)) {
        WalletBalanceCompact(
            SampleDefault.copy(statusLabel = "Syncing…", privateNight = null, dustRegistered = false),
            syncProgress = WalletSyncProgress(0.68f, "Syncing dust"),
            colors = WalletPanelColors.Default,
            onReceive = {},
            onRefresh = {},
            onRegister = {},
        )
    }
