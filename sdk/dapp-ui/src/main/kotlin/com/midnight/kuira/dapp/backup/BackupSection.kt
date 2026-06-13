package com.midnight.kuira.dapp.backup

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.dapp.wallet.WalletPanelColors
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
    data class Action(val label: String, val cta: String, val danger: Boolean = false) : BackupLaneState
}

/** [appData] null → the App data lane is omitted. */
data class BackupSectionState(
    val identity: BackupLaneState,
    val dust: BackupLaneState,
    val appData: BackupLaneState?,
)

private val RunnerSize = 40.dp
private val LaneIconGutter = 34.dp
private val TrailLen = 56.dp

@Composable
fun BackupSection(
    state: BackupSectionState,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    onDustAction: () -> Unit = {},
    onAppDataAction: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            "BACKUP & RECOVERY",
            color = colors.onSheetSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(1.dp, colors.onSheetSubtle, RoundedCornerShape(14.dp))
                .padding(vertical = 4.dp),
        ) {
            Lane("🛡", "Wallet identity", "Passkey recovers your wallet on any device", state.identity, colors) {}
            Divider(colors)
            Lane("⟳", "Dust · balance sync", "Your balance loads instantly", state.dust, colors, onDustAction)
            state.appData?.let { appData ->
                Divider(colors)
                Lane("☁", "App data", "Your in-app data", appData, colors, onAppDataAction)
            }
        }
    }
}

@Composable
private fun Lane(
    icon: String,
    title: String,
    subtitle: String,
    state: BackupLaneState,
    colors: WalletPanelColors,
    onAction: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 15.sp, modifier = Modifier.width(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = colors.onSheet, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = colors.onSheetDim, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Trailing(state, colors, onAction)
        }
        if (state is BackupLaneState.Syncing) {
            Spacer(Modifier.height(12.dp))
            RunnerDustProgress(
                progress = state.progress,
                colors = colors,
                modifier = Modifier.fillMaxWidth().padding(start = LaneIconGutter),
            )
        }
    }
}

@Composable
private fun Trailing(state: BackupLaneState, colors: WalletPanelColors, onAction: () -> Unit) {
    when (state) {
        is BackupLaneState.Ok ->
            Text(
                if (state.confirm) "${state.label} ✓" else state.label,
                color = if (state.confirm) colors.accent else colors.onSheetDim,
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

@Composable
private fun RunnerDustProgress(progress: Float?, colors: WalletPanelColors, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.height(RunnerSize), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(2.dp).align(Alignment.Center).background(colors.onSheetSubtle))

        val frac = if (progress != null) {
            progress.coerceIn(0f, 1f)
        } else {
            val transition = rememberInfiniteTransition(label = "runner")
            val animated by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 2400), RepeatMode.Restart),
                label = "runnerX",
            )
            animated
        }
        val runnerX = (maxWidth - RunnerSize) * frac

        if (progress != null) {
            Box(
                Modifier
                    .width(runnerX + RunnerSize / 2)
                    .height(3.dp)
                    .align(Alignment.CenterStart)
                    .background(colors.onSheet, RoundedCornerShape(2.dp)),
            )
        }

        val trailX = (runnerX + RunnerSize / 2 - TrailLen).coerceAtLeast(0.dp)
        DustTrail(
            modifier = Modifier.width(TrailLen).height(RunnerSize).align(Alignment.CenterStart).offset(x = trailX),
            color = colors.onSheet,
            particleCount = 14,
            maxAlpha = 0.55f,
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
