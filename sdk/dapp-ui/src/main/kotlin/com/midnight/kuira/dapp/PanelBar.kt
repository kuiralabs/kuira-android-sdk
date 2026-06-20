package com.midnight.kuira.dapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midnight.kuira.dapp.sigil.SigilPanelColors
import com.midnight.kuira.dapp.sigil.SigilPanelViewModel
import com.midnight.kuira.dapp.sigil.SigilStatus
import com.midnight.kuira.dapp.sigil.SigilStatusPanel
import com.midnight.kuira.dapp.wallet.ResizableChip
import com.midnight.kuira.dapp.wallet.SigilChip
import com.midnight.kuira.dapp.wallet.SigilChipUi
import com.midnight.kuira.dapp.wallet.ThemeStore
import com.midnight.kuira.dapp.wallet.WalletChip
import com.midnight.kuira.dapp.wallet.WalletChipUi
import com.midnight.kuira.dapp.wallet.WalletPanelColors
import com.midnight.kuira.dapp.wallet.WalletPanelViewModel
import com.midnight.kuira.dapp.wallet.WalletSettingsOverlay
import com.midnight.kuira.dapp.wallet.WalletStatus
import com.midnight.kuira.dapp.wallet.WalletStatusPanel
import com.midnight.kuira.dapp.wallet.WalletThemes
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
    /**
     * Opt-in floating mode (Phase 1): instead of a fixed top row, the sigil + wallet chips become
     * independent draggable floaters the user can place anywhere and dock to a screen edge as a
     * peek tab (tap to restore). IN-APP only — they float over the host's own content, so the host
     * must place this PanelBar as the LAST child of a `Box` that wraps its content (it renders a
     * full-screen, pass-through overlay). Default `false` keeps the classic fixed top bar, and
     * existing `Column { PanelBar(); content }` call sites are unaffected.
     */
    floating: Boolean = false,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    // Both panel VMs are resolved here so the gear in EITHER expanded panel and the overlays
    // hosted below all read/drive the SAME instances the panels render from (Hilt returns the
    // activity-scoped instance; passing them in makes that identity explicit rather than implicit).
    val walletViewModel: WalletPanelViewModel = hiltViewModel()
    val sigilViewModel: SigilPanelViewModel = hiltViewModel()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    // Appearance theme is hoisted here so the pill, its sheet, and the Settings/recovery overlays
    // all render from ONE resolved palette. Persisted in [ThemeStore] (durable across restart);
    // rememberSaveable mirrors it for instant recomposition. Picking "Monochrome" (the brand
    // default) falls back to the host-supplied [walletColors] so a host's own theming still wins;
    // any other pick overrides with that theme's palette.
    var selectedThemeId by rememberSaveable {
        mutableStateOf(ThemeStore.selectedThemeId(context) ?: WalletThemes.Default.id)
    }
    val themedWalletColors = remember(selectedThemeId, walletColors) {
        if (selectedThemeId == WalletThemes.Default.id) walletColors else WalletThemes.byId(selectedThemeId).colors
    }
    // Light/dark is hoisted too so the pill, sheet, AND the overlays below resolve to ONE palette
    // (the pill self-resolves the same flip from the controlled lightMode). Light mode shows the
    // Kuira light palette and takes precedence over a chosen color theme.
    var lightMode by rememberSaveable { mutableStateOf(false) }
    val activeWalletColors = if (lightMode) WalletPanelColors.Light else themedWalletColors

    // The sigil pill rethemes in lockstep with the wallet — both pills are "one themed unit".
    // Its palette is derived from the SAME appearance theme (Default defers to the host-supplied
    // [sigilColors]); light mode maps the Kuira light wallet palette into the sigil's vocabulary.
    val themedSigilColors = remember(selectedThemeId, sigilColors) {
        if (selectedThemeId == WalletThemes.Default.id) sigilColors
        else SigilPanelColors.from(WalletThemes.byId(selectedThemeId).colors)
    }
    val activeSigilColors = remember(lightMode, themedSigilColors) {
        if (lightMode) SigilPanelColors.from(WalletPanelColors.Light) else themedSigilColors
    }

    // Settings + recovery-reveal overlays live at the bar level (above both panels) so a single
    // Settings surface is reachable from the wallet pill's gear and the sigil pill's gear alike.
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    // A session lock drops the SDK and must hide EVERYTHING. These overlays are Popups, which
    // render in their own windows ABOVE the host's SessionLockGate cover — so on lock (which also
    // fires on backgrounding, #251) we must close them ourselves and scrub any revealed phrase
    // from memory, mirroring how WalletStatusPanel closes its sheet on Locked.
    val walletStatus by walletViewModel.status.collectAsStateWithLifecycle()
    LaunchedEffect(walletStatus) {
        if (walletStatus is WalletStatus.Locked) {
            // Hiding the overlay makes it self-close its recovery reveal + scrub the phrase
            // (see WalletSettingsOverlay's visibility guard).
            settingsOpen = false
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

    // The two chips, defined once and laid out either as a fixed top row (default) or as
    // independent floaters (opt-in). Same VM / theme / overlay wiring drives both layouts.
    // Each panel takes an optional `pill` slot: docked mode passes null (built-in pill); floating
    // mode passes the live resizable widget. The panel keeps its sheet/bootstrap either way.
    val sigilPanel: @Composable (pill: (@Composable (SigilChipUi, () -> Unit) -> Unit)?) -> Unit = { pillSlot ->
        SigilStatusPanel(
            viewModel = sigilViewModel,
            colors = activeSigilColors,
            onStatusChange = {
                currentSigilStatus = it
                onSigilStatusChange(it)
            },
            onOpenSettings = { settingsOpen = true },
            pill = pillSlot,
        )
    }
    val walletPanel: @Composable (pill: (@Composable (WalletChipUi, () -> Unit) -> Unit)?) -> Unit = { pillSlot ->
        WalletStatusPanel(
            viewModel = walletViewModel,
            initialNetwork = network,
            colors = themedWalletColors,
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
            lightMode = lightMode,
            onToggleLightMode = { lightMode = !lightMode },
            pill = pillSlot,
        )
    }

    if (floating) {
        // Full-screen, pass-through overlay: only the chips themselves consume touches, so the
        // host's content stays interactive everywhere else. Each chip drags + docks independently
        // and resizes (long-press the grip). systemBarsPadding keeps the float/dock range within
        // the safe area. Both widgets use the wallet palette so they read as one themed unit; each
        // panel still themes its own SHEET. Tap → the panel's sheet; the panel keeps state/bootstrap.
        BoxWithConstraints(modifier = modifier.fillMaxSize().systemBarsPadding()) {
            val widthPx = constraints.maxWidth
            val heightPx = constraints.maxHeight
            FloatingChip(
                persistKey = "kuira_chip_sigil",
                containerWidthPx = widthPx,
                containerHeightPx = heightPx,
                initialCorner = FloatingCorner.TopStart,
            ) {
                sigilPanel { ui, onTap ->
                    ResizableChip(activeWalletColors, onTap) { tier, w -> SigilChip(tier, ui, activeWalletColors, width = w) }
                }
            }
            FloatingChip(
                persistKey = "kuira_chip_wallet",
                containerWidthPx = widthPx,
                containerHeightPx = heightPx,
                initialCorner = FloatingCorner.TopEnd,
            ) {
                walletPanel { ui, onTap ->
                    ResizableChip(activeWalletColors, onTap) { tier, w -> WalletChip(tier, ui, activeWalletColors, width = w) }
                }
            }
        }
    } else {
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
            sigilPanel(null)
            walletPanel(null)
        }
    }

    // ── Settings (+ nested recovery reveal) ── via the shared host, also used by standalone panels.
    WalletSettingsOverlay(
        visible = settingsOpen,
        viewModel = walletViewModel,
        status = walletStatus,
        activity = activity,
        colors = activeWalletColors,
        selectedThemeId = selectedThemeId,
        onSelectTheme = { id ->
            selectedThemeId = id
            ThemeStore.setSelectedThemeId(context, id)
        },
        versionLabel = appVersion.ifBlank { null },
        onSignOut = { activity?.let { sigilViewModel.signOut(it) } },
        onDismiss = { settingsOpen = false },
    )
}

private object PanelBarDimens {
    /** Gap below the system status bar before the chips. Same value the wallet panel used to use solo. */
    val TopGap = 12.dp

    /** Side breathing room. Matches BBoard's `Spacing.ScreenPadding` (24dp) ÷ 2 so the chips don't kiss the edge. */
    val HorizontalPadding = 12.dp
}
