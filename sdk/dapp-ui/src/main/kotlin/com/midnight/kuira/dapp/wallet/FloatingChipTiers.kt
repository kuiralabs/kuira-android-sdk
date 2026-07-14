package com.midnight.kuira.dapp.wallet

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import com.midnight.kuira.dapp.backup.RunnerDustProgress

/**
 * Phase 2 preview surface — the four floating-chip size tiers as REAL composables, so they render
 * in Android Studio @Preview for design review and become the implementation the resize gesture
 * drives. Each tier is a distinct layout chosen by size (not a scaled pill): going bigger ADDS
 * information; going smaller sheds it. Themed via [WalletPanelColors] so they ride the appearance
 * theme; green is the reserved status color (Synced / ✓ / Protected) per the brand standard.
 */
internal enum class ChipTier { Peek, Pill, Card, Panel }

/**
 * Bottom clearance the Panel tier reserves below its action-button row so the [ResizableChip] grip —
 * a 32.dp handle anchored to the chip's bottom-end corner — sits in a free strip instead of over the
 * right-most action button (which made the button hard to hit). Sized to the grip's footprint minus
 * the card's own 16.dp bottom padding.
 */
internal val PanelGripClearance = 16.dp

/** The clean width each tier snaps to (Peek is the docked sliver; Pill wraps, this is its threshold). */
internal fun ChipTier.naturalWidth(): Dp = when (this) {
    ChipTier.Peek -> 26.dp
    ChipTier.Pill -> 150.dp
    ChipTier.Card -> 212.dp
    ChipTier.Panel -> 280.dp
}

/** Which tier a live (mid-drag) width falls into — the layout follows the size across these. */
internal fun tierForWidth(width: Dp): ChipTier = when {
    width < 110.dp -> ChipTier.Peek
    width < 185.dp -> ChipTier.Pill
    width < 255.dp -> ChipTier.Card
    else -> ChipTier.Panel
}

/** Sample-able UI model for the wallet chip's tiers. */
data class WalletChipUi(
    val night: String,
    val unshielded: String,
    val shielded: String,
    val dust: String,
    val dustRegistered: Boolean,
    val synced: Boolean,
    val network: String,
    /** True whenever a sync is in flight — determinate OR indeterminate. Drives the status
     *  indicator; [syncProgress] only carries a fraction when one is measurable. */
    val syncing: Boolean = false,
    /** Live sync fraction (0..1) when a determinate sync is in flight; null = idle/indeterminate →
     *  the Panel tier hides the progress bar rather than showing a frozen mock. */
    val syncProgress: Float? = null,
) {
    companion object {
        val Sample = WalletChipUi(
            night = "1,234.5", unshielded = "1,200.0", shielded = "34.5",
            dust = "167.3", dustRegistered = true, synced = true, network = "PreProd",
        )
    }
}

/** Sample-able UI model for the sigil chip's tiers. */
data class SigilChipUi(
    val label: String,
    val status: String,
    val didShort: String,
    val didFull: String,
    /**
     * Health of the sigil, driving the status dot / status-text color. Only
     * [Protected] earns the reserved green — a forged, active identity. Everything
     * else stays neutral or error so the chip never signals "protected" while the
     * sigil is absent, initializing, or failed (green-when-disconnected was a lie).
     */
    val tone: Tone = Tone.Neutral,
) {
    enum class Tone { Protected, Neutral, Error }

    companion object {
        val Sample = SigilChipUi(
            label = "Sigil", status = "Forged · Protected",
            didShort = "did:key:z6Mk…a1b2",
            didFull = "did:key:z6MkpTHR8VNsBxYAAWHut2Geadd9jSwuBV8xRoAnwWsdvktH",
            tone = Tone.Protected,
        )
    }
}

// ── Wallet chip ──────────────────────────────────────────────────────────────

@Composable
internal fun WalletChip(
    tier: ChipTier,
    ui: WalletChipUi,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    width: Dp = tier.naturalWidth(),
    onSend: () -> Unit = {},
    onReceive: () -> Unit = {},
) {
    when (tier) {
        ChipTier.Peek -> PeekTab(colors, modifier)
        ChipTier.Pill -> FrostPill(colors, modifier) {
            if (ui.syncing) {
                SyncPulseDot(colors.accent)
                Spacer(Modifier.width(8.dp))
            }
            Text(ui.night, color = colors.onSheet, fontSize = 13.sp, fontWeight = FontWeight.W300)
            Spacer(Modifier.width(4.dp))
            Text("NIGHT", color = colors.onSheetDim, fontSize = 11.sp, letterSpacing = 1.sp)
            if (ui.synced) {
                Spacer(Modifier.width(8.dp))
                Text("✓", color = colors.positive, fontSize = 13.sp)
            }
        }
        ChipTier.Card -> GlassCard(colors, modifier.width(width)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Label("NIGHT", colors)
                when {
                    ui.syncing -> Text("Syncing", color = colors.accent, fontSize = 12.sp)
                    ui.synced -> Text("Synced", color = colors.positive, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(ui.night, color = colors.onSheet, fontSize = 30.sp, fontWeight = FontWeight.W200, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DUST ", color = colors.onSheetDim, fontSize = 12.sp)
                    Text(ui.dust, color = colors.onSheet, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                    if (ui.dustRegistered) Text(" ✓", color = colors.positive, fontSize = 12.sp)
                }
                Text(ui.network, color = colors.onSheetDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false)
            }
        }
        ChipTier.Panel -> GlassCard(colors, modifier.width(width)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label("NIGHT · ", colors)
                    when {
                        ui.syncing -> Text("Syncing", color = colors.accent, fontSize = 11.sp, letterSpacing = 1.sp)
                        ui.synced -> Text("Synced", color = colors.positive, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
                if (ui.syncing) SyncPulseDot(colors.accent)
                else if (ui.synced) Text("✓", color = colors.positive, fontSize = 13.sp)
            }
            Text(ui.night, color = colors.onSheet, fontSize = 40.sp, fontWeight = FontWeight.W200, letterSpacing = (-1).sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth()) {
                Pool("unshielded", ui.unshielded, colors)
                Spacer(Modifier.width(20.dp))
                Pool("shielded", ui.shielded, colors)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("dust ", colors)
                Text(ui.dust, color = colors.onSheet, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                if (ui.dustRegistered) Text("  ✓ registered", color = colors.positive, fontSize = 12.sp)
            }
            // Real sync progress — the brand runner, driven by the live fraction and width-relative
            // (BoxWithConstraints inside), shown ONLY while a determinate sync is in flight.
            ui.syncProgress?.let { frac ->
                Spacer(Modifier.height(12.dp))
                RunnerDustProgress(progress = frac, colors = colors, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Send", colors, primary = true, onClick = onSend, modifier = Modifier.weight(1f))
                ActionButton("Receive", colors, primary = false, onClick = onReceive, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(PanelGripClearance))
        }
    }
}

@Composable
private fun SyncPulseDot(color: Color) {
    val alpha by rememberInfiniteTransition(label = "syncPulse").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "syncPulseAlpha",
    )
    Box(Modifier.size(6.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}

// ── Sigil chip ───────────────────────────────────────────────────────────────

@Composable
internal fun SigilChip(
    tier: ChipTier,
    ui: SigilChipUi,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    width: Dp = tier.naturalWidth(),
    onSettings: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    when (tier) {
        ChipTier.Peek -> PeekTab(colors, modifier)
        ChipTier.Pill -> FrostPill(colors, modifier) {
            Avatar(colors, 24.dp)
            Spacer(Modifier.width(8.dp))
            Text(ui.label, color = colors.onSheet, fontSize = 13.sp, fontWeight = FontWeight.W300)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(6.dp).clip(CircleShape).background(ui.tone.color(colors)))
        }
        ChipTier.Card -> GlassCard(colors, modifier.width(width)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(colors, 34.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(ui.label, color = colors.onSheet, fontSize = 14.sp, fontWeight = FontWeight.W300)
                    Text(ui.status, color = ui.tone.color(colors), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            MonoField(ui.didShort, colors)
        }
        ChipTier.Panel -> GlassCard(colors, modifier.width(width)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RunnerGlyph(colors.onSheet, size = 56.dp)
                Spacer(Modifier.height(6.dp))
                Text("Your Sigil", color = colors.onSheet, fontSize = 16.sp, fontWeight = FontWeight.W300)
                Text(ui.status, color = ui.tone.color(colors), fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            MonoField(ui.didFull, colors)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Settings", colors, primary = false, onClick = onSettings, modifier = Modifier.weight(1f))
                ActionButton("Sign out", colors, primary = false, onClick = onSignOut, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(PanelGripClearance))
        }
    }
}

// ── Shared bits ──────────────────────────────────────────────────────────────

/**
 * Status-indicator color for a sigil chip's tone. [Protected] is the reserved
 * green (a forged, active identity); [Error] is the semantic error red; every
 * other state is neutral-dim so the chip never falsely reads as "protected".
 */
private fun SigilChipUi.Tone.color(colors: WalletPanelColors): Color = when (this) {
    SigilChipUi.Tone.Protected -> colors.positive
    SigilChipUi.Tone.Error -> colors.error
    SigilChipUi.Tone.Neutral -> colors.onSheetDim
}

/**
 * Chip surface opacity: mostly opaque so the chip stays readable over ANY host (e.g. BBoard's
 * cream), with a hint of translucency so a little of a dark backdrop shows through as frost — best
 * of both. The GlassPanel sheen adds the specular glass cue on top.
 */
private const val SHEET_FROST_ALPHA = 0.92f

@Composable
private fun PeekTab(colors: WalletPanelColors, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(26.dp).height(52.dp)
            .clip(RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp))
            .background(colors.sheetBackground.copy(alpha = SHEET_FROST_ALPHA))
            .border(1.dp, colors.onSheetSubtle, RoundedCornerShape(topEnd = 13.dp, bottomEnd = 13.dp)),
        contentAlignment = Alignment.Center,
    ) { Text("›", color = colors.onSheet, fontSize = 18.sp) }
}

@Composable
private fun FrostPill(colors: WalletPanelColors, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(colors.sheetBackground.copy(alpha = SHEET_FROST_ALPHA))
            .border(1.dp, colors.onSheetSubtle, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun GlassCard(colors: WalletPanelColors, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    GlassPanel(
        // Near-opaque themed surface (a chip floats over arbitrary host content, e.g. BBoard's cream
        // theme, so it carries its own readable background) with a hint of frost. sheetBackground +
        // onSheet is the exact light/dark-aware combo the real sheet uses.
        tint = colors.sheetBackground.copy(alpha = SHEET_FROST_ALPHA),
        border = colors.onSheetSubtle,
        modifier = modifier,
        cornerRadius = 16.dp,
        contentPadding = 16.dp,
    ) { content() }
}

@Composable
private fun Label(text: String, colors: WalletPanelColors) =
    Text(text, color = colors.onSheetDim, fontSize = 11.sp, letterSpacing = 1.5.sp)

@Composable
private fun Pool(label: String, value: String, colors: WalletPanelColors) {
    Column {
        Text(label.uppercase(), color = colors.onSheetDim, fontSize = 10.sp, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(3.dp))
        Text(value, color = colors.onSheet, fontSize = 14.sp, fontWeight = FontWeight.W300, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MonoField(text: String, colors: WalletPanelColors) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.onSheet.copy(alpha = GLASS_FILL_ALPHA)).padding(10.dp),
    ) { Text(text, color = colors.onSheet.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
}

@Composable
private fun ActionButton(text: String, colors: WalletPanelColors, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (primary) colors.onSheet else colors.onSheet.copy(alpha = GLASS_FILL_ALPHA))
            .border(1.dp, if (primary) Color.Transparent else colors.onSheetSubtle, RoundedCornerShape(10.dp))
            // .clickable CONSUMES the tap, so it doesn't fall through to the chip-wide
            // detectTapGestures in ResizableChip (which opens the sheet).
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text.uppercase(),
            color = if (primary) colors.sheetBackground else colors.onSheet,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun Avatar(colors: WalletPanelColors, size: Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(colors.onSheet.copy(alpha = GLASS_FILL_ALPHA)).border(1.dp, colors.onSheetSubtle, CircleShape),
        contentAlignment = Alignment.Center,
    ) { RunnerGlyph(colors.onSheet, size = size * 0.6f) }
}

/** The Rarámuri runner brand mark (line-art), geometry from kuira_runner.xml. */
@Composable
internal fun RunnerGlyph(color: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        fun p(x: Float, y: Float) = Offset(x / 24f * s, y / 24f * s)
        val sw = s * 0.046f
        drawCircle(color, radius = 1.4f / 24f * s, center = p(14.8f, 4.8f), style = Stroke(sw))
        fun seg(a: Offset, b: Offset) = drawLine(color, a, b, sw, cap = StrokeCap.Round)
        seg(p(14f, 6.6f), p(10.4f, 12.8f))
        seg(p(12.8f, 8.2f), p(17.2f, 7.2f))
        seg(p(12.8f, 8.2f), p(9.2f, 10f))
        seg(p(10.4f, 12.8f), p(14f, 14.4f)); seg(p(14f, 14.4f), p(14.8f, 18f))
        seg(p(10.4f, 12.8f), p(8f, 16.8f)); seg(p(8f, 16.8f), p(10.4f, 19.2f))
    }
}

// ── Previews ─────────────────────────────────────────────────────────────────
// Each chip sits on a Void surface so it reads dark in the AS preview pane AND when a preview is
// deployed to a device (the @Preview `backgroundColor` only tints the pane, not a device run).

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().background(MidnightColors.Void).padding(20.dp), contentAlignment = Alignment.Center) { content() }

@Preview(name = "Wallet · Peek", showBackground = true)
@Composable
private fun PreviewWalletPeek() = PreviewSurface { WalletChip(ChipTier.Peek, WalletChipUi.Sample, WalletPanelColors.Default) }

@Preview(name = "Wallet · Pill", showBackground = true)
@Composable
private fun PreviewWalletPill() = PreviewSurface { WalletChip(ChipTier.Pill, WalletChipUi.Sample, WalletPanelColors.Default) }

@Preview(name = "Wallet · Card", showBackground = true)
@Composable
private fun PreviewWalletCard() = PreviewSurface { WalletChip(ChipTier.Card, WalletChipUi.Sample, WalletPanelColors.Default) }

@Preview(name = "Wallet · Panel", showBackground = true)
@Composable
private fun PreviewWalletPanel() = PreviewSurface { WalletChip(ChipTier.Panel, WalletChipUi.Sample, WalletPanelColors.Default) }

@Preview(name = "Sigil · Pill", showBackground = true)
@Composable
private fun PreviewSigilPill() = PreviewSurface { SigilChip(ChipTier.Pill, SigilChipUi.Sample, WalletPanelColors.Default) }

@Preview(name = "Sigil · Card", showBackground = true)
@Composable
private fun PreviewSigilCard() = PreviewSurface { SigilChip(ChipTier.Card, SigilChipUi.Sample, WalletPanelColors.Default) }

@Preview(name = "Sigil · Panel", showBackground = true)
@Composable
private fun PreviewSigilPanel() = PreviewSurface { SigilChip(ChipTier.Panel, SigilChipUi.Sample, WalletPanelColors.Default) }

@Preview(name = "Wallet · all tiers", showBackground = true, widthDp = 780)
@Composable
private fun PreviewWalletAll() = PreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
        WalletChip(ChipTier.Peek, WalletChipUi.Sample, WalletPanelColors.Default)
        WalletChip(ChipTier.Pill, WalletChipUi.Sample, WalletPanelColors.Default)
        WalletChip(ChipTier.Card, WalletChipUi.Sample, WalletPanelColors.Default)
        WalletChip(ChipTier.Panel, WalletChipUi.Sample, WalletPanelColors.Default)
    }
}

@Preview(name = "Sigil · all tiers", showBackground = true, widthDp = 720)
@Composable
private fun PreviewSigilAll() = PreviewSurface {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
        SigilChip(ChipTier.Peek, SigilChipUi.Sample, WalletPanelColors.Default)
        SigilChip(ChipTier.Pill, SigilChipUi.Sample, WalletPanelColors.Default)
        SigilChip(ChipTier.Card, SigilChipUi.Sample, WalletPanelColors.Default)
        SigilChip(ChipTier.Panel, SigilChipUi.Sample, WalletPanelColors.Default)
    }
}
