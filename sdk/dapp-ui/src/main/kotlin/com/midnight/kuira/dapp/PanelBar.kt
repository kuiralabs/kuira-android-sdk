package com.midnight.kuira.dapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midnight.kuira.dapp.sigil.SigilPanelColors
import com.midnight.kuira.dapp.sigil.SigilPanelViewModel
import com.midnight.kuira.dapp.sigil.SigilStatus
import com.midnight.kuira.dapp.sigil.SigilStatusPanel
import com.midnight.kuira.dapp.wallet.WalletPanelColors
import com.midnight.kuira.dapp.wallet.WalletPanelViewModel
import com.midnight.kuira.dapp.wallet.WalletRecoveryScreen
import com.midnight.kuira.dapp.wallet.WalletSettingsScreen
import com.midnight.kuira.dapp.wallet.WalletStatus
import com.midnight.kuira.dapp.wallet.WalletStatusPanel
import com.midnight.kuira.dapp.wallet.pillName
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
    /**
     * One-shot trigger to OPEN the wallet panel from outside — e.g. a host routing a
     * "received funds" notification tap to the wallet. Each change to a new non-zero value
     * pops the panel open; `0` (default) never auto-opens.
     */
    openWalletSignal: Int = 0,
    /**
     * App version shown in the Settings panel's ABOUT section. The SDK never fabricates this —
     * a host passes its own `BuildConfig.VERSION_NAME`. Blank (default) hides the ABOUT section.
     */
    appVersion: String = "",
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Both panel VMs are resolved here so the gear in EITHER expanded panel and the overlays
    // hosted below all read/drive the SAME instances the panels render from (Hilt returns the
    // activity-scoped instance; passing them in makes that identity explicit rather than implicit).
    val walletViewModel: WalletPanelViewModel = hiltViewModel()
    val sigilViewModel: SigilPanelViewModel = hiltViewModel()
    val activity = LocalContext.current as? FragmentActivity

    // Settings + recovery-reveal overlays live at the bar level (above both panels) so a single
    // Settings surface is reachable from the wallet pill's gear and the sigil pill's gear alike.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var recoveryRevealOpen by rememberSaveable { mutableStateOf(false) }

    // A session lock drops the SDK and must hide EVERYTHING. These overlays are Popups, which
    // render in their own windows ABOVE the host's SessionLockGate cover — so on lock (which also
    // fires on backgrounding, #251) we must close them ourselves and scrub any revealed phrase
    // from memory, mirroring how WalletStatusPanel closes its sheet on Locked.
    val walletStatus by walletViewModel.status.collectAsStateWithLifecycle()
    LaunchedEffect(walletStatus) {
        if (walletStatus is WalletStatus.Locked) {
            recoveryRevealOpen = false
            settingsOpen = false
            walletViewModel.clearRevealedPhrase()
        }
    }

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
            viewModel = sigilViewModel,
            colors = sigilColors,
            onStatusChange = {
                currentSigilStatus = it
                onSigilStatusChange(it)
            },
            onOpenSettings = { settingsOpen = true },
        )
        WalletStatusPanel(
            viewModel = walletViewModel,
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
            openSheetSignal = openWalletSignal,
            onOpenSettings = { settingsOpen = true },
        )
    }

    // ── Settings overlay ──
    // Full-screen Popup (escapes the bar's Row) hosting the bundled WalletSettingsScreen.
    if (settingsOpen) {
        val selectedNetwork by walletViewModel.selectedNetwork.collectAsStateWithLifecycle()
        val syncProgress by walletViewModel.syncProgress.collectAsStateWithLifecycle()
        val recoveryPhraseSaved by walletViewModel.recoveryPhraseSaved.collectAsStateWithLifecycle()
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = { settingsOpen = false },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        ) {
            WalletSettingsScreen(
                networkLabel = selectedNetwork.pillName.replaceFirstChar { it.uppercase() },
                syncLabel = settingsSyncLabel(walletStatus, syncing = syncProgress != null),
                recoveryPhraseSaved = recoveryPhraseSaved,
                onViewRecoveryPhrase = { recoveryRevealOpen = true },
                onLockNow = {
                    walletViewModel.lockNow()
                    settingsOpen = false
                },
                onSignOut = {
                    activity?.let { sigilViewModel.signOut(it) }
                    settingsOpen = false
                },
                versionLabel = appVersion.ifBlank { null },
                colors = walletColors,
                onBack = { settingsOpen = false },
            )
        }
    }

    // ── Recovery-phrase reveal overlay ──
    // Nested above Settings (own focusable Popup) so Back returns to Settings, not host content.
    if (recoveryRevealOpen) {
        val revealedPhrase by walletViewModel.revealedPhrase.collectAsStateWithLifecycle()
        val recoveryError by walletViewModel.recoveryError.collectAsStateWithLifecycle()
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = {
                walletViewModel.clearRevealedPhrase()
                recoveryRevealOpen = false
            },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        ) {
            WalletRecoveryScreen(
                phrase = revealedPhrase,
                error = recoveryError,
                colors = walletColors,
                onReveal = { activity?.let { walletViewModel.revealRecoveryPhrase(it) } },
                onConfirmSaved = {
                    walletViewModel.markRecoveryPhraseSaved()
                    walletViewModel.clearRevealedPhrase()
                    recoveryRevealOpen = false
                },
                onBack = {
                    walletViewModel.clearRevealedPhrase()
                    recoveryRevealOpen = false
                },
            )
        }
    }
}

/**
 * Coarse, truthful sync state for the Settings NETWORK section. Intentionally a STATE, not a
 * fabricated "5 min ago" timestamp (the VM tracks no sync clock) — mirrors the sheet's own
 * Synced/Syncing… derivation.
 */
private fun settingsSyncLabel(status: WalletStatus, syncing: Boolean): String = when (status) {
    is WalletStatus.Ready -> if (syncing || status.busy != null) "Syncing…" else "Synced"
    is WalletStatus.Loading -> "Syncing…"
    is WalletStatus.Locked -> "Locked"
    is WalletStatus.Error -> "Error"
    is WalletStatus.SigilRequired -> "Sigil required"
    is WalletStatus.None -> "Idle"
}

private object PanelBarDimens {
    /** Gap below the system status bar before the chips. Same value the wallet panel used to use solo. */
    val TopGap = 12.dp

    /** Side breathing room. Matches BBoard's `Spacing.ScreenPadding` (24dp) ÷ 2 so the chips don't kiss the edge. */
    val HorizontalPadding = 12.dp
}
