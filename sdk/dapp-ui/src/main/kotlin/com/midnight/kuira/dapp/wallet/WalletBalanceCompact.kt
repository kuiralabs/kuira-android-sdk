package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.ShimmerBlock
import kotlinx.coroutines.launch

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
 * @param dustRegistered true → ✓ on the dust row (only once a sync has settled, so
 *  it never contradicts an in-flight "Syncing dust…"); false → a Register affordance.
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
        BalanceCard(colors, contentPadding = 14.dp) {
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
            Spacer(Modifier.height(6.dp))
            Text(
                ui.nightTotal,
                color = colors.onSheet,
                fontSize = 34.sp,
                fontWeight = FontWeight.W200,
                letterSpacing = (-1).sp,
                lineHeight = 36.sp,
            )
            Spacer(Modifier.height(2.dp))
            // "Synced" is the one sync-complete status → green; "Syncing…" and the
            // "NIGHT ·" prefix stay monochrome (progress is never green mid-flight).
            val synced = syncProgress == null
            // Status line + the ⓘ pinned bottom-RIGHT on the SAME row, so the ⓘ adds NO vertical space
            // to the card (it only grows for the sync indicator). The ⓘ explains the live-event NIGHT
            // count can drift and points at Settings → Re-sync balance; gone once lands.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.onSheetDim)) { append("NIGHT · ") }
                        withStyle(SpanStyle(color = if (synced) colors.positive else colors.onSheetDim)) {
                            append(ui.statusLabel)
                        }
                    },
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                BalanceInfoDot(colors)
            }
            // Equal-weight public / private split — both are real balances, both
            // rendered bright (private is NOT a dim afterthought).
            if (ui.privateNight != null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    PoolAmount(label = "unshielded", amount = ui.publicNight, colors = colors)
                    Spacer(Modifier.width(18.dp))
                    PoolAmount(label = "shielded", amount = ui.privateNight, colors = colors)
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

        BalanceCard(colors) {
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
                    when {
                        // Mid-sync the figure is still provisional — don't assert a
                        // settled ✓ while the hero card reads "Syncing dust…" (the two
                        // contradict). The ✓ returns once the sync settles.
                        ui.dustRegistered && syncProgress == null -> {
                            Spacer(Modifier.width(10.dp))
                            // Settled confirmation → the reserved success green.
                            Text("✓", color = colors.positive, fontSize = 14.sp)
                        }
                        !ui.dustRegistered -> {
                            Spacer(Modifier.width(10.dp))
                            TextChip("Register", colors = colors, enabled = !busy, onClick = onRegister)
                        }
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

/**
 * The bottom-left ⓘ on the balance card. Tap → a persistent tooltip explaining the live-event NIGHT
 * count can drift out of sync, pointing at the Settings → Re-sync balance recovery. Mirrors the
 * Backup section's InfoDot; extensible (add lines as more wallet info is worth surfacing) and easy to
 * delete once reconciliation makes the drift impossible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BalanceInfoDot(colors: WalletPanelColors, modifier: Modifier = Modifier) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip {
                Text(
                    "Your NIGHT balance is rebuilt from live chain events, so it can occasionally drift " +
                        "out of sync. If it looks off, re-sync it from Settings.",
                )
            }
        },
        state = tooltipState,
        modifier = modifier,
    ) {
        Text(
            "ⓘ",
            color = colors.onSheetDim,
            fontSize = 14.sp,
            modifier = Modifier.clickable { scope.launch { tooltipState.show() } }.padding(2.dp),
        )
    }
}

/** The standard balance-card frame — a GlassPanel with the panel's button-surface tint + hairline. */
@Composable
private fun BalanceCard(
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) = GlassPanel(
    // Frosted: a translucent fill so the star field shows faintly through the card.
    tint = colors.onSheet.copy(alpha = GLASS_FILL_ALPHA),
    border = colors.onSheetSubtle,
    modifier = modifier,
    contentPadding = contentPadding,
    content = content,
)

/** One pool figure — small label, bright amount; private gets a shield. Equal weight to its sibling. */
@Composable
private fun PoolAmount(label: String, amount: String, colors: WalletPanelColors) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // "shielded" / "unshielded" label carries the distinction — no emoji.
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
                .background(colors.onSheet.copy(alpha = GLASS_FILL_ALPHA))
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
        BalanceCard(colors) {
            Eyebrow("BALANCE", colors)
            Spacer(Modifier.height(16.dp))
            ShimmerBlock(height = 40.dp, widthFraction = 0.6f)
            Spacer(Modifier.height(10.dp))
            ShimmerBlock(height = 14.dp, widthFraction = 0.35f)
        }
        Spacer(Modifier.height(10.dp))
        BalanceCard(colors) {
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
    BalanceCard(colors, modifier = modifier) {
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
