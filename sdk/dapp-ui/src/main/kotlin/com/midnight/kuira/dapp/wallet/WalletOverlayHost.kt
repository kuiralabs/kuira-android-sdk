package com.midnight.kuira.dapp.wallet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midnight.kuira.dapp.sigil.SigilPanelViewModel

/**
 * Root host for the wallet's full-screen overlays (Send / Receive / Settings, with
 * Recovery nested inside Settings).
 *
 * **Why the activity window, not a Dialog.** The overlays are full-screen SCREENS.
 * Previously each was hosted in a Compose `Dialog`, and a Dialog is a *floating*
 * window that fits inside the system bars by design — which
 * left an unfixable white status-bar strip. The activity window is already
 * edge-to-edge (the same window `SessionLockGate`'s lock cover draws into), so
 * rendering the overlays as plain Composables here makes edge-to-edge free: each
 * screen's own `WindowInsets` padding keeps content clear of the bars while its
 * `fillMaxSize()` StarField draws behind them.
 *
 * Wraps [content] and provides a [WalletOverlayController] over it via
 * [LocalWalletOverlay]; the pill / [com.midnight.kuira.dapp.PanelBar] open overlays
 * through that ambient controller. When an overlay is active it renders ON TOP of
 * [content] in the same [Box] (last child = drawn last = on top).
 *
 * Independently usable: compose it directly, or use [WalletAppShell] to also get the
 * session lock outermost.
 */
@Composable
fun WalletOverlayHost(
    modifier: Modifier = Modifier,
    /**
     * App version for the Settings ABOUT section. The SDK never fabricates this — the
     * host passes its own `BuildConfig.VERSION_NAME`. Null (default) hides the section.
     */
    appVersion: String? = null,
    content: @Composable () -> Unit,
) {
    val controller = remember { WalletOverlayController().also { it.hostAttached = true } }

    // Seed the host-level app version onto the controller. A content-level host
    // (PanelBar) may overwrite it with its own; whichever is set last wins for ABOUT.
    LaunchedEffect(appVersion) {
        if (appVersion != null) controller.appVersion = appVersion
    }

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalWalletOverlay provides controller) {
            content()
        }

        val active = controller.active
        if (active != null) {
            WalletOverlayContent(active = active, controller = controller)
        }
    }
}

/**
 * Renders the active overlay screen as a plain full-screen Composable.
 *
 * Reads the SAME activity-scoped [WalletPanelViewModel] the pill drives ([hiltViewModel]
 * returns the activity instance), so balance / send / recovery state is shared. Theme
 * colors are resolved from the persisted appearance exactly as `SessionLockScreen` does,
 * so the overlays are themed even before the pill composes.
 */
@Composable
private fun WalletOverlayContent(
    active: WalletOverlay,
    controller: WalletOverlayController,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val viewModel: WalletPanelViewModel = hiltViewModel()
    val sigilViewModel: SigilPanelViewModel = hiltViewModel()

    val status by viewModel.status.collectAsStateWithLifecycle()
    val network by viewModel.selectedNetwork.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()

    // Seed the controller's in-memory theme mirror from the durable store on first open, so a
    // standalone-panel path (which never sets it) and a cold start both resolve a real theme.
    LaunchedEffect(Unit) {
        if (controller.selectedThemeId == null) {
            controller.selectedThemeId = ThemeStore.selectedThemeId(context) ?: WalletThemes.Default.id
        }
    }

    // Resolve the appearance the same way the lock surface does, but keyed on the controller's
    // observable theme id (shared with the pill) so picking a theme in Settings re-themes the
    // overlay AND the pill live. Light mode stays durable-only here (the pill owns the toggle).
    val themeId = controller.selectedThemeId ?: WalletThemes.Default.id
    val colors = remember(themeId) {
        val themed = if (themeId == WalletThemes.Default.id) WalletPanelColors.Default else WalletThemes.byId(themeId).colors
        if (ThemeStore.lightMode(context)) WalletPanelColors.Light else themed
    }

    // A session lock drops the SDK and must be the only surface visible. The lock
    // gate covers this window, but close the overlay too so it doesn't linger
    // underneath / reappear on unlock in a stale state.
    LaunchedEffect(status) {
        if (status is WalletStatus.Locked) controller.dismissForLock()
    }

    when (active) {
        is WalletOverlay.Receive -> {
            // Only meaningful when Ready (need addresses). If the wallet drops out of
            // Ready while open, close rather than render a half-built screen.
            val ready = status as? WalletStatus.Ready
            BackHandler(enabled = true) { controller.close() }
            if (ready != null) {
                WalletReceiveScreen(
                    unshieldedAddress = ready.address,
                    shieldedAddress = ready.shieldedAddress,
                    network = network,
                    colors = colors,
                    onBack = { controller.close() },
                )
            } else {
                LaunchedEffect(Unit) { controller.close() }
            }
        }

        is WalletOverlay.Send -> {
            val ready = status as? WalletStatus.Ready
            // The wizard publishes its back logic via registerBack; we let it walk the
            // steps / hide the keyboard, and only an unconsumed back (first step) closes.
            var sendBack by remember { mutableStateOf<() -> Boolean>({ false }) }
            BackHandler(enabled = true) {
                if (!sendBack()) controller.close()
            }
            if (ready != null) {
                WalletSendScreen(
                    // sendNight spends UNSHIELDED NIGHT, so cap the form at that pool.
                    spendableNightRaw = ready.balance.unshieldedNight,
                    network = network,
                    sendState = sendState,
                    colors = colors,
                    onSubmit = { to, amount -> activity?.let { viewModel.sendNight(viewModel.currentConfig(), it, to, amount) } },
                    onResetState = { viewModel.resetSendState() },
                    onBack = { controller.close() },
                    registerBack = { sendBack = it },
                )
            } else {
                LaunchedEffect(Unit) { controller.close() }
            }
        }

        is WalletOverlay.Settings -> {
            WalletSettingsOverlayContent(
                viewModel = viewModel,
                status = status,
                network = network,
                activity = activity,
                colors = colors,
                selectedThemeId = themeId,
                onSelectTheme = { id ->
                    // Write through to the shared observable (re-themes the pill live) AND persist.
                    controller.selectedThemeId = id
                    ThemeStore.setSelectedThemeId(context, id)
                },
                versionLabel = controller.appVersion,
                onSignOut = { activity?.let { sigilViewModel.signOut(it) } },
                onClose = { controller.close() },
            )
        }
    }
}

/**
 * Settings screen (plus its nested recovery-phrase reveal) rendered as plain
 * full-screen Composables — the activity-window successor to the old
 * `WalletSettingsOverlay`, with the full-screen Dialog wrappers stripped.
 *
 * Back routing: when the recovery reveal is open, Back returns to Settings (and
 * scrubs the revealed phrase); otherwise Back closes the whole Settings overlay.
 */
@Composable
private fun WalletSettingsOverlayContent(
    viewModel: WalletPanelViewModel,
    status: WalletStatus,
    network: com.midnight.kuira.core.network.MidnightNetwork,
    activity: FragmentActivity?,
    colors: WalletPanelColors,
    selectedThemeId: String,
    onSelectTheme: (String) -> Unit,
    versionLabel: String?,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
) {
    var recoveryRevealOpen by rememberSaveable { mutableStateOf(false) }

    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val recoveryPhraseSaved by viewModel.recoveryPhraseSaved.collectAsStateWithLifecycle()

    // Back returns to Settings from the reveal; otherwise closes Settings entirely.
    BackHandler(enabled = true) {
        if (recoveryRevealOpen) {
            viewModel.clearRevealedPhrase()
            recoveryRevealOpen = false
        } else {
            onClose()
        }
    }

    if (recoveryRevealOpen) {
        val revealedPhrase by viewModel.revealedPhrase.collectAsStateWithLifecycle()
        val recoveryError by viewModel.recoveryError.collectAsStateWithLifecycle()
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
    } else {
        WalletSettingsScreen(
            networkLabel = network.pillName.replaceFirstChar { it.uppercase() },
            syncLabel = settingsSyncLabel(status, syncing = syncProgress != null),
            recoveryPhraseSaved = recoveryPhraseSaved,
            onViewRecoveryPhrase = { recoveryRevealOpen = true },
            onResyncBalance = {
                // Rebuild the unshielded UTXO cache from genesis (#52). Close back to the
                // wallet so the user watches the balance self-correct via the live indicator.
                activity?.let { viewModel.forceResyncBalance(it) }
                onClose()
            },
            onLockNow = {
                viewModel.lockNow()
                onClose()
            },
            onSignOut = {
                onSignOut()
                onClose()
            },
            versionLabel = versionLabel,
            colors = colors,
            selectedThemeId = selectedThemeId,
            onSelectTheme = onSelectTheme,
            onBack = onClose,
        )
    }
}

/**
 * Coarse, truthful sync state for the Settings NETWORK section — a STATE, not a
 * fabricated "5 min ago" timestamp (the VM tracks no sync clock); mirrors the sheet's
 * Synced/Syncing… derivation.
 */
internal fun settingsSyncLabel(status: WalletStatus, syncing: Boolean): String = when (status) {
    is WalletStatus.Ready -> if (syncing || status.busy != null) "Syncing…" else "Synced"
    is WalletStatus.Loading -> "Syncing…"
    is WalletStatus.Locked -> "Locked"
    is WalletStatus.Error -> "Error"
    is WalletStatus.SigilRequired -> "Sigil required"
    is WalletStatus.None -> "—"
}
