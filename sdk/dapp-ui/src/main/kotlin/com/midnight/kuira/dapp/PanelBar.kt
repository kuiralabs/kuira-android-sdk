package com.midnight.kuira.dapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.midnight.kuira.dapp.sigil.SigilPanelColors
import com.midnight.kuira.dapp.sigil.SigilStatus
import com.midnight.kuira.dapp.sigil.SigilStatusPanel
import com.midnight.kuira.dapp.wallet.WalletPanelColors
import com.midnight.kuira.dapp.wallet.WalletStatusPanel
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
 * @param walletColors Palette for the wallet pill + sheet. Defaults to the
 *   on-brand dusk theme ([WalletPanelColors.Default]); a host passes its own
 *   to match its look (e.g. Kicks' World-Cup theme).
 * @param sigilColors Palette for the sigil pill + sheet. Defaults to the
 *   matching dusk theme ([SigilPanelColors.Default]).
 */
@Composable
fun PanelBar(
    network: MidnightNetwork = MidnightNetwork.UNDEPLOYED,
    onNetworkChange: (MidnightNetwork) -> Unit = {},
    onSigilStatusChange: (SigilStatus) -> Unit = {},
    modifier: Modifier = Modifier,
    walletColors: WalletPanelColors = WalletPanelColors.Default,
    sigilColors: SigilPanelColors = SigilPanelColors.Default,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // PanelBar observes sigil status itself so it can gate the wallet
    // panel's auto-bootstrap on Forged — implements "Problem A": don't
    // create a fresh wallet uninvited while a Block Store backup is
    // sitting unclaimed. Hosts still see the public onSigilStatusChange
    // callback for whatever they need; the internal gate is additive.
    //
    // Initial state mirrors the SigilPanelViewModel default
    // (Initializing) so that on first composition — before the panel's
    // LaunchedEffect has had a chance to fire onStatusChange — the
    // wallet stays gated. Otherwise the wallet's LaunchedEffect can
    // dispatch refreshBalance and even show a biometric prompt during
    // the few-hundred-ms window before the probe settles.
    var currentSigilStatus by remember { mutableStateOf<SigilStatus>(SigilStatus.Initializing) }

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
        SigilStatusPanel(
            colors = sigilColors,
            onStatusChange = {
                currentSigilStatus = it
                onSigilStatusChange(it)
            },
        )
        WalletStatusPanel(
            initialNetwork = network,
            colors = walletColors,
            onNetworkChange = onNetworkChange,
            // Sealed-when so adding a new SigilStatus variant later
            // forces an explicit yes/no decision at compile time
            // rather than silently defaulting to gated. Wallet
            // bootstraps in exactly two states:
            //  - None    → probe done, no backup to wait on → safe to
            //              auto-create a fresh wallet (today's default
            //              for users who never used the app).
            //  - Forged  → sigil resolved (either loaded from prefs or
            //              just forged) → wallet can bootstrap;
            //              SeedVault.loadSeed runs silently within the
            //              30s auth window, or creates a fresh seed if
            //              empty.
            // Initializing / BackupAvailable / Creating / Error keep
            // the wallet gated until the sigil situation stabilises.
            enabled = when (currentSigilStatus) {
                is SigilStatus.None,
                is SigilStatus.Forged -> true
                is SigilStatus.Initializing,
                is SigilStatus.BackupAvailable,
                is SigilStatus.Creating,
                is SigilStatus.Error -> false
            },
        )
    }
}

private object PanelBarDimens {
    /** Gap below the system status bar before the chips. Same value the wallet panel used to use solo. */
    val TopGap = 12.dp

    /** Side breathing room. Matches BBoard's `Spacing.ScreenPadding` (24dp) ÷ 2 so the chips don't kiss the edge. */
    val HorizontalPadding = 12.dp
}
