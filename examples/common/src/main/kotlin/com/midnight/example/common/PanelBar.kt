package com.midnight.example.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.midnight.example.common.sigil.SigilStatus
import com.midnight.example.common.sigil.SigilStatusPanel
import com.midnight.example.common.wallet.WalletStatusPanel
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * Top-of-screen row that hosts the panel chips: sigil identity on the left,
 * wallet status on the right.
 *
 * **Why a dedicated bar:** when two pills float above content (anchored
 * top-left + top-right via `Modifier.align`), they fight the host's title
 * row on narrow widths and the title gets squeezed. A dedicated row at
 * the top — title moves below — guarantees breathing room for both pills
 * and gives the host a single composable to drop in instead of two
 * `Box`-and-`align` recipes.
 *
 * **Anchoring + sheet direction (mental model):**
 *  - Sigil pill on the LEFT → top sheet slides DOWN from top (sheet
 *    emerges adjacent to the chip).
 *  - Wallet pill on the RIGHT → bottom sheet slides UP from bottom.
 *
 *  Opposite gestures, no animation collision, each chip's expansion
 *  starts near where it lives.
 *
 * **Usage (typical BBoard-style host):**
 *
 * ```kotlin
 * Column {
 *     PanelBar(network = midnightNetwork, onNetworkChange = { midnightNetwork = it })
 *     YourHeader(...)
 *     // ... rest of the host's content
 * }
 * ```
 *
 * The bar pulls its top inset from [WindowInsets.statusBars] so the chips
 * sit safely below the system status bar without each call site repeating
 * the math.
 *
 * @param network Initial network for the wallet panel; the panel owns the
 *   selection from then on. Host should mirror updates via [onNetworkChange]
 *   so contract operations target the same chain the wallet is on.
 * @param onNetworkChange Fires when the user picks a different network in
 *   the wallet sheet's chip row. Default no-op.
 * @param onSigilStatusChange Fires when the sigil panel's status transitions
 *   (None → Creating → Forged, restore, etc.). Hosts that need to gate UI
 *   on "is there a sigil?" — e.g. an onboarding banner that pushes the
 *   user to forge before they can use the dApp — mirror this into their
 *   own state. Default no-op.
 * @param modifier Modifier applied to the bar's outer Row.
 */
@Composable
fun PanelBar(
    network: MidnightNetwork = MidnightNetwork.UNDEPLOYED,
    onNetworkChange: (MidnightNetwork) -> Unit = {},
    onSigilStatusChange: (SigilStatus) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                top = statusBarPadding + PanelBarDimens.TopGap,
                start = PanelBarDimens.HorizontalPadding,
                end = PanelBarDimens.HorizontalPadding,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SigilStatusPanel(onStatusChange = onSigilStatusChange)
        WalletStatusPanel(
            initialNetwork = network,
            onNetworkChange = onNetworkChange,
        )
    }
}

private object PanelBarDimens {
    /** Gap below the system status bar before the chips. Same value the wallet panel used to use solo. */
    val TopGap = 12.dp

    /** Side breathing room. Matches BBoard's `Spacing.ScreenPadding` (24dp) ÷ 2 so the chips don't kiss the edge. */
    val HorizontalPadding = 12.dp
}
