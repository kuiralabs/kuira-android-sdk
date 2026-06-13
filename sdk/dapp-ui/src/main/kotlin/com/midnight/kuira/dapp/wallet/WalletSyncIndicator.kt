package com.midnight.kuira.dapp.wallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.midnight.kuira.dapp.backup.RunnerDustProgress
import kotlin.math.roundToInt

/**
 * Labelled wallet-sync progress: a stage label on the left, the live percentage
 * on the right (when determinate), and the branded [RunnerDustProgress] track
 * below. This is the **one reusable sync indicator** — the wallet pill and
 * example apps feed it real progress digits instead of each rolling its own bar.
 *
 * Determinate vs indeterminate is driven entirely by [progress]:
 *  - `0f..1f` → the runner fills to that fraction and the percentage shows. Use
 *    whenever a count is available (e.g. `eventsProcessed / totalEvents` from
 *    [com.midnight.kuira.sdk.MidnightWallet.syncDust]).
 *  - `null` → the runner loops and no percentage shows. Reserve this for phases
 *    with no measurable total (e.g. the Rust replay after streaming completes) —
 *    not as the default, so users don't see an endless loop for a finite task.
 *
 * Themeable via [WalletPanelColors] so each consuming app keeps its look.
 *
 * @param progress 0f..1f for determinate, or null for indeterminate.
 * @param label Stage / detail text, e.g. "Syncing dust" or "Replaying events".
 */
@Composable
fun WalletSyncIndicator(
    progress: Float?,
    label: String,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                label,
                color = colors.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            if (progress != null) {
                Text(
                    "${(progress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                    color = colors.onSheet,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        RunnerDustProgress(progress = progress, colors = colors, modifier = Modifier.fillMaxWidth())
    }
}

// ── Previews ──

@Preview(name = "Sync · determinate", widthDp = 360, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun PreviewSyncDeterminate() =
    WalletSyncIndicator(progress = 0.45f, label = "Syncing dust", colors = WalletPanelColors.Default, modifier = Modifier.height(64.dp))

@Preview(name = "Sync · replaying (indeterminate)", widthDp = 360, showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun PreviewSyncReplaying() =
    WalletSyncIndicator(progress = null, label = "Replaying events", colors = WalletPanelColors.Default, modifier = Modifier.height(64.dp))
