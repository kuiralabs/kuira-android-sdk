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
 * component pure UI (no SDK types) so it previews and reuses freely.
 *
 * NIGHT is one asset with two pools, both important: [nightTotal] is the
 * headline (public + private); [publicNight] and [privateNight] are shown as an
 * equal-weight split beneath it (private is null when there's none). DUST — a
 * separate gas token — gets its own row.
 *
 * @param nightTotal hero figure — formatted total NIGHT.
 * @param publicNight formatted unshielded (public) NIGHT.
 * @param privateNight formatted shielded (private) NIGHT, or null when there's none.
 * @param statusLabel short status under the hero, e.g. "Synced" / "Syncing…".
 * @param dust formatted (abbreviated) dust, or null while first-loading.
 * @param dustRegistered true → ✓ on the dust row; false → a Register affordance.
 */
data class WalletBalanceUi(
    val nightTotal: String,
    val publicNight: String,
    val privateNight: String?,
    val statusLabel: String,
    val dust: String?,
    val dustRegistered: Boolean,
)

/**
 * Compact wallet balance — the SDK/example-app density of the Kuira balance
 * "flag": a NIGHT hero card (headline total + an equal-weight public/private
 * split + an elegant ⟳ refresh), a slim DUST row with its own Register
 * affordance, and Send / Receive quick actions. Stateless + themeable.
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
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Eyebrow("BALANCE", colors, modifier = Modifier.weight(1f))
                // Elegant refresh — bare glyph, no container; bright + sized to read.
                Text(
                    "⟳",
                    color = if (busy) colors.onSheetDim else colors.onSheet,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .then(if (busy) Modifier else Modifier.clickable { onRefresh() })
                        .padding(4.dp),
                )
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
            Spacer(Modifier.height(2.dp))
            Text("NIGHT · ${ui.statusLabel}", color = colors.onSheetDim, fontSize = 13.sp)
            // Equal-weight public / private split — both are real balances, both
            // rendered bright (private is NOT a dim afterthought).
            if (ui.privateNight != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    PoolAmount(label = "public", amount = ui.publicNight, colors = colors)
                    Spacer(Modifier.width(18.dp))
                    PoolAmount(label = "private", amount = ui.privateNight, colors = colors, shielded = true)
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
                    fontSize = 15.sp,
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

/** One pool figure — small label, bright amount; private gets a shield. Equal weight to its sibling. */
@Composable
private fun PoolAmount(label: String, amount: String, colors: WalletPanelColors, shielded: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (shielded) {
            Text("🛡", fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
        }
        Text(label, color = colors.onSheetDim, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        Text(amount, color = colors.onSheet, fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
    }
}

/** Readable eyebrow label (~75% on-sheet) — fixes the prior 20–50% HIG-fail dim labels. */
@Composable
internal fun Eyebrow(text: String, colors: WalletPanelColors, modifier: Modifier = Modifier) {
    Text(
        text,
        color = colors.onSheet.copy(alpha = 0.75f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = modifier,
    )
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

/** First-load skeleton — shimmer placeholders shaped like the real cards. */
@Composable
fun WalletBalanceLoading(colors: WalletPanelColors, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
            Eyebrow("BALANCE", colors)
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

/** Balance error — keeps the card frame, states the failure, offers Retry. */
@Composable
fun WalletBalanceError(
    message: String,
    colors: WalletPanelColors,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(tint = colors.button, border = colors.onSheetSubtle, modifier = modifier) {
        Eyebrow("BALANCE", colors)
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
    nightTotal = "20,000",
    publicNight = "10,000",
    privateNight = "10,000",
    statusLabel = "Synced",
    dust = "5.33K",
    dustRegistered = true,
)

@Preview(name = "Balance compact · default", widthDp = 360, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun PreviewBalanceDefault() =
    Column(Modifier.padding(20.dp)) {
        WalletBalanceCompact(SampleDefault, syncProgress = null, colors = WalletPanelColors.Default, onReceive = {}, onRefresh = {}, onRegister = {})
    }

@Preview(name = "Balance compact · light", widthDp = 360, showBackground = true, backgroundColor = 0xFFF7F7F7)
@Composable
private fun PreviewBalanceLight() =
    Column(Modifier.padding(20.dp)) {
        WalletBalanceCompact(SampleDefault, syncProgress = null, colors = WalletPanelColors.Light, onReceive = {}, onRefresh = {}, onRegister = {})
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
