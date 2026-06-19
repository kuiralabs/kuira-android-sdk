package com.midnight.kuira.dapp.backup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.DustTrail
import com.midnight.kuira.core.designsystem.effect.LottieRunner
import com.midnight.kuira.dapp.wallet.CloudGlyph
import com.midnight.kuira.dapp.wallet.DustGlyph
import com.midnight.kuira.dapp.wallet.Eyebrow
import com.midnight.kuira.dapp.wallet.GLASS_FILL_ALPHA
import com.midnight.kuira.dapp.wallet.KeyGlyph
import com.midnight.kuira.dapp.wallet.WalletPanelColors
import com.midnight.kuira.dapp.wallet.positive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Branded "Backup & recovery" section for the wallet pill (#243/#244).
 *
 * Three lanes, each with its own status:
 *  - Wallet identity → the passkey (the real recovery; always on).
 *  - Dust · balance sync → speed; can be Off / need consent.
 *  - App data → host state; null hides the lane.
 *
 * When a lane is [BackupLaneState.Syncing] the Rarámuri [LottieRunner] runs along
 * a track and the [DustTrail] it kicks up is the progress fill. Themeable via
 * [WalletPanelColors] so each consuming app keeps its look. Animations only run
 * in Interactive Preview / on device.
 */

sealed interface BackupLaneState {
    data class Ok(val label: String, val confirm: Boolean = false) : BackupLaneState
    /** [progress] 0f..1f → determinate (% + fill); null → indeterminate. */
    data class Syncing(val progress: Float?) : BackupLaneState
    /**
     * An on/off capability rendered as a Switch — unambiguous "this is off, flip
     * it on" (vs a label that read as already-enabled). [on] reflects state,
     * [busy] shows a spinner while turning on, [detail] surfaces an error.
     */
    data class Toggle(
        val on: Boolean,
        val busy: Boolean = false,
        val detail: String? = null,
    ) : BackupLaneState
    data class Action(
        val label: String,
        val cta: String,
        val danger: Boolean = false,
        /** Optional actionable detail shown under the lane (e.g. a setup hint). */
        val detail: String? = null,
    ) : BackupLaneState
}

/**
 * [appData] null → the App data lane is omitted. The wallet panel always
 * provides a value (an informational "None yet" empty state when nothing has
 * been saved) so users discover the capability; null stays available for hosts
 * that genuinely have no app-data concept.
 */
data class BackupSectionState(
    val identity: BackupLaneState,
    val dust: BackupLaneState,
    val appData: BackupLaneState?,
)

private val RunnerSize = 30.dp        // smaller — the previous 40dp read as oversized
private val LaneIconGutter = 34.dp
private val TrailLen = 44.dp

@Composable
fun BackupSection(
    state: BackupSectionState,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    /** Turn dust/cloud backup ON (enable + sync). */
    onDustToggle: (Boolean) -> Unit = {},
    /** TRUE disable (#246): delete the cloud blobs + revoke Drive consent. Confirmed first. */
    onDisableBackup: () -> Unit = {},
    onAppDataAction: () -> Unit = {},
) {
    // Turning backup OFF is a real disconnect (deletes the cloud copies + revokes Drive), so it
    // confirms first; turning ON enables immediately.
    var showDisableConfirm by remember { mutableStateOf(false) }
    val dustToggle: (Boolean) -> Unit = { want -> if (want) onDustToggle(true) else showDisableConfirm = true }

    Column(modifier.fillMaxWidth()) {
        Eyebrow("BACKUP & RECOVERY", colors)
        Spacer(Modifier.height(12.dp))
        GlassPanel(
            tint = colors.onSheet.copy(alpha = GLASS_FILL_ALPHA),
            border = colors.onSheetSubtle,
            cornerRadius = 14.dp,
            contentPadding = 4.dp,
        ) {
            Lane(glyph = { KeyGlyph(it) }, title = "Wallet identity", tooltip = TIP_IDENTITY, state = state.identity, colors = colors)
            Divider(colors)
            Lane(glyph = { DustGlyph(it) }, title = "Dust · balance sync", tooltip = TIP_DUST, state = state.dust, colors = colors, onToggle = dustToggle)
            state.appData?.let { appData ->
                Divider(colors)
                Lane(glyph = { CloudGlyph(it) }, title = "App data", tooltip = TIP_APP_DATA, state = appData, colors = colors, onAction = onAppDataAction)
            }
        }
    }

    if (showDisableConfirm) {
        DisableBackupDialog(
            colors = colors,
            onConfirm = { showDisableConfirm = false; onDisableBackup() },
            onDismiss = { showDisableConfirm = false },
        )
    }
}

// Approved copy (#246): lead with the outcome, reassure the sigil/wallet is safe (passkey
// recovery), name the real cost — never "lose funds". Externalized to resources with the rest of
// the pill's strings in #277.
private const val DISABLE_TITLE = "Turn off cloud backup?"
private const val DISABLE_BODY =
    "This deletes your cloud backup from Google Drive and turns it off. Your wallet and sigil " +
        "stay safe — they're recovered by your passkey, not this backup. Nothing changes on this " +
        "device — it keeps its data and still syncs fast. Only a new device or a reinstall is " +
        "affected: it re-syncs from scratch (slower) and without your saved app state. You can " +
        "turn backup back on anytime."

@Composable
private fun DisableBackupDialog(
    colors: WalletPanelColors,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Match the dark wallet sheet — the default Material3 dialog surface is light, which clashed
        // with the app and washed out the onSheet* text colors (those are tuned for the dark sheet).
        containerColor = colors.sheetBackground,
        titleContentColor = colors.onSheet,
        textContentColor = colors.onSheet,
        title = { Text(DISABLE_TITLE, fontWeight = FontWeight.SemiBold) },
        text = { Text(DISABLE_BODY, fontSize = 13.sp, lineHeight = 18.sp) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect", color = colors.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.onSheetDim) } },
    )
}

// One-line-per-lane keeps the section uncluttered; the "how it works" detail
// lives behind the ⓘ tooltip instead of a permanent subtitle sentence.
private const val TIP_IDENTITY =
    "Your passkey recovers this wallet on any device — the only recovery, and it's always on."
private const val TIP_DUST =
    "Saves your balance to the cloud so a new device shows it instantly — without re-scanning the whole chain."
private const val TIP_APP_DATA =
    "Saves this app's data to the cloud so it comes back on a new device. Happens automatically."

@Composable
private fun Lane(
    glyph: @Composable (Color) -> Unit,
    title: String,
    tooltip: String,
    state: BackupLaneState,
    colors: WalletPanelColors,
    onAction: () -> Unit = {},
    onToggle: (Boolean) -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { glyph(colors.onSheet) }
            Spacer(Modifier.width(14.dp))
            Text(title, color = colors.onSheet, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(6.dp))
            InfoDot(tooltip, colors)
            Spacer(Modifier.weight(1f))
            Trailing(state, colors, onAction, onToggle)
        }
        if (state is BackupLaneState.Syncing) {
            Spacer(Modifier.height(12.dp))
            RunnerDustProgress(
                progress = state.progress,
                colors = colors,
                modifier = Modifier.fillMaxWidth().padding(start = LaneIconGutter),
            )
        }
        val detail = (state as? BackupLaneState.Action)?.detail ?: (state as? BackupLaneState.Toggle)?.detail
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                detail,
                color = colors.error,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(start = LaneIconGutter),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InfoDot(text: String, colors: WalletPanelColors) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = tooltipState,
    ) {
        Text(
            "ⓘ",
            color = colors.onSheetDim,
            fontSize = 14.sp,
            modifier = Modifier.clickable { scope.launch { tooltipState.show() } },
        )
    }
}

@Composable
private fun Trailing(
    state: BackupLaneState,
    colors: WalletPanelColors,
    onAction: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    when (state) {
        is BackupLaneState.Ok ->
            Text(
                if (state.confirm) "${state.label} ✓" else state.label,
                // Confirmed/protected → the reserved success green; otherwise neutral.
                color = if (state.confirm) colors.positive else colors.onSheetDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        is BackupLaneState.Syncing ->
            Text(
                if (state.progress != null) "${(state.progress.coerceIn(0f, 1f) * 100).roundToInt()}%" else "Backing up…",
                color = colors.onSheet,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        is BackupLaneState.Toggle ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = colors.accent,
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Switch(
                    checked = state.on,
                    onCheckedChange = { want -> onToggle(want) },
                    enabled = !state.busy,
                    colors = SwitchDefaults.colors(
                        // ON = backup protected, a positive status → the reserved success green,
                        // with the knob in the SURFACE pole so it reads as a cut-out (not a solid
                        // blob — the failure mode when track and thumb share a color).
                        checkedThumbColor = colors.sheetBackground,
                        checkedTrackColor = colors.positive,
                        uncheckedThumbColor = colors.onSheetDim,
                        uncheckedTrackColor = colors.onSheet.copy(alpha = GLASS_FILL_ALPHA),
                        uncheckedBorderColor = colors.onSheetSubtle,
                    ),
                )
            }
        is BackupLaneState.Action ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.label,
                    color = if (state.danger) colors.error else colors.onSheetDim,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.width(10.dp))
                Pill(state.cta, colors, onAction)
            }
    }
}

/**
 * Branded progress: the Rarámuri [LottieRunner] runs along a track, the [DustTrail]
 * kicking up behind it. **The runner's position is the progress** —
 *  - [progress] 0f..1f → the runner advances to that point and a bright accent
 *    fill highlights the distance covered.
 *  - null → the runner runs **in place at the start** (legs still animate, but it
 *    does NOT crawl across and reset) — we never fake motion we can't measure.
 *
 * Public; the labelled variant is [WalletSyncIndicator].
 */
@Composable
fun RunnerDustProgress(progress: Float?, colors: WalletPanelColors, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.height(RunnerSize), contentAlignment = Alignment.CenterStart) {
        // Faint full-width track.
        Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(colors.onSheetSubtle))

        // Determinate → runner sits at the fraction; indeterminate → parked at the
        // start (frac 0), running in place, no crawl/reset loop.
        val frac = progress?.coerceIn(0f, 1f) ?: 0f
        val runnerX = (maxWidth - RunnerSize) * frac

        if (progress != null) {
            // Bright accent fill = highlighted distance covered.
            Box(
                Modifier
                    .width(runnerX + RunnerSize / 2)
                    .height(3.dp)
                    .align(Alignment.CenterStart)
                    .background(colors.accent, RoundedCornerShape(2.dp)),
            )
        }

        val trailX = (runnerX + RunnerSize / 2 - TrailLen).coerceAtLeast(0.dp)
        DustTrail(
            modifier = Modifier.width(TrailLen).height(RunnerSize).align(Alignment.CenterStart).offset(x = trailX),
            color = colors.onSheet,
            particleCount = 12,
            maxAlpha = 0.5f,
        )
        LottieRunner(
            modifier = Modifier.size(RunnerSize).align(Alignment.CenterStart).offset(x = runnerX),
            color = colors.onSheet,
        )
    }
}

@Composable
private fun Pill(text: String, colors: WalletPanelColors, onClick: () -> Unit) {
    Text(
        text,
        color = colors.onSheet,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .border(1.dp, colors.onSheetSubtle, RoundedCornerShape(8.dp))
            .background(colors.button, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun Divider(colors: WalletPanelColors) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(1.dp).background(colors.onSheetSubtle))
}

// ── Previews ──

private val SampleSyncing = BackupSectionState(
    identity = BackupLaneState.Ok("Protected", confirm = true),
    dust = BackupLaneState.Syncing(0.68f),
    appData = BackupLaneState.Syncing(null),
)
private val SampleNeedsAction = BackupSectionState(
    identity = BackupLaneState.Ok("Protected", confirm = true),
    dust = BackupLaneState.Action("Off", "Enable"),
    appData = null,
)

@Composable
private fun Frame(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(WalletPanelColors.Default.sheetBackground).padding(20.dp),
    ) { content() }
}

@Preview(name = "Pill backup · syncing", widthDp = 400, heightDp = 380, showBackground = true)
@Composable
private fun PreviewSyncing() = Frame { BackupSection(SampleSyncing, WalletPanelColors.Default) }

@Preview(name = "Pill backup · needs action", widthDp = 400, heightDp = 300, showBackground = true)
@Composable
private fun PreviewNeedsAction() = Frame { BackupSection(SampleNeedsAction, WalletPanelColors.Default) }
