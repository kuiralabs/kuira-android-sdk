package com.midnight.kuira.dapp.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.luminance
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Hosts the bundled Settings screen (and its nested recovery-phrase reveal) in full-screen,
 * edge-to-edge Dialogs ([EdgeToEdgeOverlay]).
 * [WalletSettingsScreen] is `internal`, so only the SDK can render it — this IS that host, shared by
 * [com.midnight.kuira.dapp.PanelBar] AND the standalone [WalletStatusPanel] self-host path, so a
 * raw-panel integration (no PanelBar) still reaches settings without duplicating the wiring.
 */
@Composable
internal fun WalletSettingsOverlay(
    visible: Boolean,
    viewModel: WalletPanelViewModel,
    status: WalletStatus,
    activity: FragmentActivity?,
    colors: WalletPanelColors,
    selectedThemeId: String,
    onSelectTheme: (String) -> Unit,
    versionLabel: String?,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
) {
    var recoveryRevealOpen by rememberSaveable { mutableStateOf(false) }

    // Privacy: when the host hides the overlay (session lock / backgrounding, #251), close the nested
    // recovery reveal and scrub any revealed phrase from memory. These are own-window Dialogs that sit
    // ABOVE the host's lock cover, so they must self-close.
    LaunchedEffect(visible) {
        if (!visible) {
            recoveryRevealOpen = false
            viewModel.clearRevealedPhrase()
        }
    }

    if (visible) {
        val network by viewModel.selectedNetwork.collectAsStateWithLifecycle()
        val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
        val recoveryPhraseSaved by viewModel.recoveryPhraseSaved.collectAsStateWithLifecycle()
        EdgeToEdgeOverlay(
            isLight = colors.sheetBackground.luminance() > 0.5f,
            onDismiss = onDismiss,
        ) {
            WalletSettingsScreen(
                networkLabel = network.pillName.replaceFirstChar { it.uppercase() },
                syncLabel = settingsSyncLabel(status, syncing = syncProgress != null),
                recoveryPhraseSaved = recoveryPhraseSaved,
                onViewRecoveryPhrase = { recoveryRevealOpen = true },
                onResyncBalance = {
                    // Rebuild the unshielded UTXO cache from genesis (#52). Dismiss back to the wallet
                    // panel so the user watches the balance self-correct via the live sync indicator.
                    activity?.let { viewModel.forceResyncBalance(it) }
                    onDismiss()
                },
                onLockNow = {
                    viewModel.lockNow()
                    onDismiss()
                },
                onSignOut = {
                    onSignOut()
                    onDismiss()
                },
                versionLabel = versionLabel,
                colors = colors,
                selectedThemeId = selectedThemeId,
                onSelectTheme = onSelectTheme,
                onBack = onDismiss,
            )
        }
    }

    // Nested above Settings (own full-screen Dialog) so Back returns to Settings, not host content.
    if (recoveryRevealOpen) {
        val revealedPhrase by viewModel.revealedPhrase.collectAsStateWithLifecycle()
        val recoveryError by viewModel.recoveryError.collectAsStateWithLifecycle()
        EdgeToEdgeOverlay(
            isLight = colors.sheetBackground.luminance() > 0.5f,
            onDismiss = {
                viewModel.clearRevealedPhrase()
                recoveryRevealOpen = false
            },
        ) {
            WalletRecoveryScreen(
                phrase = revealedPhrase,
                error = recoveryError,
                colors = colors,
                onReveal = { activity?.let { viewModel.revealRecoveryPhrase(it) } },
                onConfirmSaved = {
                    viewModel.markRecoveryPhraseSaved()
                    viewModel.clearRevealedPhrase()
                    recoveryRevealOpen = false
                },
                onBack = {
                    viewModel.clearRevealedPhrase()
                    recoveryRevealOpen = false
                },
            )
        }
    }
}

/**
 * Coarse, truthful sync state for the Settings NETWORK section — a STATE, not a fabricated
 * "5 min ago" timestamp (the VM tracks no sync clock); mirrors the sheet's Synced/Syncing… derivation.
 */
internal fun settingsSyncLabel(status: WalletStatus, syncing: Boolean): String = when (status) {
    is WalletStatus.Ready -> if (syncing || status.busy != null) "Syncing…" else "Synced"
    is WalletStatus.Loading -> "Syncing…"
    is WalletStatus.Locked -> "Locked"
    is WalletStatus.Error -> "Error"
    is WalletStatus.SigilRequired -> "Sigil required"
    is WalletStatus.None -> "—"
}
