package com.midnight.kuira.dapp.wallet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.midnight.kuira.core.designsystem.component.GlassPanel
import com.midnight.kuira.core.designsystem.effect.StarField
import com.midnight.kuira.core.designsystem.theme.MidnightColors
import com.midnight.kuira.dapp.backup.BackupSection
import com.midnight.kuira.dapp.backup.BackupSectionState
import androidx.hilt.navigation.compose.hiltViewModel
import com.midnight.kuira.dapp.dappPressable
import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.ledger.ui.BalanceFormatter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.walletruntime.WalletConfig
import kotlinx.coroutines.launch

// ── Design tokens ──
//
// Centralized so a host app that wants to retheme the panel only has to swap
// [WalletPanelColors] for chroma; geometry/typography stay consistent across
// every example that drops in this panel. Mirrors BBoard's local
// `Spacing`/`Type` objects, which are this module's stylistic reference.

private object PanelDimens {
    // Pill (compact, always-visible status badge).
    val PillCornerRadius = 22.dp
    val PillBorderWidth = 1.5.dp
    val PillHorizontalPadding = 18.dp
    val PillVerticalPadding = 12.dp
    val PillItemGap = 8.dp
    val PillSpinnerSize = 14.dp
    val PillSpinnerStroke = 2.dp

    // Sheet (expanded view on pill tap).
    val SheetHorizontalPadding = 24.dp
    val SheetVerticalPadding = 20.dp
    val SheetActionsTopGap = 28.dp     // Above the action-button row.
    val SheetButtonRowGap = 10.dp      // Between buttons + below the row.
    val SheetSectionGap = 14.dp        // Between address / airdrop / balance sections.
    val SheetLabelGap = 4.dp           // Section label → content.
    val SheetMessageGap = 6.dp         // Before status.message.
    val SheetBusyGap = 8.dp            // Before status.busy line.
    val SheetBottomGap = 8.dp

    // Action buttons.
    val ButtonHeight = 48.dp
    val ButtonCornerRadius = 12.dp
    val ButtonHorizontalPadding = 8.dp
}

private object PanelType {
    val PillText = 14.sp
    val SectionLabel = 11.sp           // Small uppercase: "address", "balance", etc.
    val ButtonText = 13.sp
    val Body = 14.sp                   // Address, balance row.
    val Caption = 13.sp                // Busy + message lines.
    val LoadingText = 16.sp
    val ErrorText = 14.sp
    val AirdropCmd = 13.sp             // Monospace airdrop command.
}

/**
 * Default amount baked into the `mn airdrop` command shown in the Receive
 * screen on UNDEPLOYED. 10,000 NIGHT matches the wider repo's canary
 * convention (see SDK e2e tests and `WalletPanelViewModel`'s comment on
 * `MIN_FUNDING_NIGHT`). Display-only — the actual funding threshold is
 * governed by [WalletPanelViewModel]. Internal so sibling files in the
 * panel module (e.g. [WalletReceiveScreen]) can reference it without
 * duplicating the magic number.
 */
internal const val DEFAULT_AIRDROP_AMOUNT = 10_000

/**
 * Drop-in wallet panel for example apps.
 *
 * Renders a compact pill anchored top-right (e.g. `10K · 167.3 · ✓`). Tap to
 * expand a [ModalBottomSheet] with the wallet address, full balance, and
 * three actions: read balance, wait for funding, register for dust.
 *
 * **Typical usage:**
 * ```kotlin
 * Box(modifier = Modifier.fillMaxSize()) {
 *     // your app's main content
 *     YourScreen()
 *
 *     // pill anchored top-right; sheet appears on tap
 *     WalletStatusPanel(
 *         network = MidnightNetwork.UNDEPLOYED,
 *         modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
 *     )
 * }
 * ```
 *
 * **State ownership:** the panel ships its own [WalletPanelViewModel] which
 * handles SeedVault unlock + SDK bootstrap. Host apps do not need to hold any
 * wallet state. If a host wants to drive the panel from its own ViewModel
 * (e.g. share the SDK with other features), pass a custom [viewModel].
 *
 * @param initialNetwork Network the panel starts on. The user can change it
 *   via the sheet's network chip row — that selection is owned inside the
 *   panel from then on, the host doesn't need to track it for wallet use.
 * @param modifier Modifier applied to the pill — typical placement is
 *   `Modifier.align(Alignment.TopEnd).padding(...)`.
 * @param colors UI palette; defaults match dark-themed example apps.
 * @param viewModel Custom panel VM. Defaults to one obtained from Hilt via [hiltViewModel].
 * @param onNetworkChange Fires after the user picks a new network in the
 *   sheet's chip row. Defaults to no-op; useful for example apps whose
 *   contract operations (deploy / connect) need to target the same chain
 *   the wallet is on — they can mirror this into their own state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletStatusPanel(
    initialNetwork: MidnightNetwork = MidnightNetwork.UNDEPLOYED,
    modifier: Modifier = Modifier,
    colors: WalletPanelColors = WalletPanelColors.Default,
    viewModel: WalletPanelViewModel = hiltViewModel(),
    onNetworkChange: (MidnightNetwork) -> Unit = {},
    /**
     * Whether the panel is allowed to auto-bootstrap on mount. Default
     * `true` keeps existing call sites working unchanged. Hosts that
     * want the "Problem A" gate — don't auto-create a fresh wallet
     * before the user has chosen Restore vs Start Fresh on the sigil
     * panel — set this to `(sigilStatus is SigilStatus.Forged)`.
     * [PanelBar] wires that automatically for hosts that consume it.
     *
     * Explicit user actions (tapping the `balance` button in the sheet)
     * still trigger a bootstrap regardless of this flag, on the
     * assumption that explicit action means the user has reconciled
     * any pending sigil state.
     */
    enabled: Boolean = true,
    /**
     * One-shot trigger to OPEN the expanded panel from OUTSIDE — e.g. a host routing a
     * "received funds" notification tap to the wallet. Each time this changes to a new
     * non-zero value the sheet opens; `0` (default) never auto-opens. [PanelBar] threads it
     * through, so a host increments a counter and the panel pops open.
     */
    openSheetSignal: Int = 0,
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    val sendState by viewModel.sendState.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var receiveOpen by rememberSaveable { mutableStateOf(false) }
    var sendOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val formatter = remember { BalanceFormatter() }

    // Open the expanded panel when a host fires [openSheetSignal] (e.g. routing a
    // received-funds notification tap to the wallet). Keyed on the value so each new
    // signal re-opens; the initial 0 is a no-op.
    LaunchedEffect(openSheetSignal) {
        if (openSheetSignal > 0) sheetOpen = true
    }

    // Whole-app session lock (#14): a ModalBottomSheet renders in its own window
    // ABOVE the activity content, so if a lock fires while the sheet (or the
    // receive dialog) is open it would float on top of the SessionLockGate. Close
    // them on lock so the gate is the only surface the user sees.
    LaunchedEffect(status) {
        if (status is WalletStatus.Locked) {
            sheetOpen = false
            receiveOpen = false
            sendOpen = false
        }
    }

    // Panel owns the full wallet config now (network + proving mode + proof
    // server URL). User picks any of these in the sheet, the LaunchedEffect
    // below re-fires with the new config and the VM rebuilds the SDK.
    var network by rememberSaveable { mutableStateOf(initialNetwork) }
    var provingMode by rememberSaveable { mutableStateOf(ProvingMode.DEFAULT) }
    var proofServerUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val config = remember(network, provingMode, proofServerUrl) {
        WalletConfig(network = network, provingMode = provingMode, proofServerUrl = proofServerUrl)
    }

    // Light/dark mode is panel-local (rememberSaveable); the toggle lives in the
    // sheet. The host-supplied [colors] is the dark base; light flips to the
    // SDK's light dusk palette. Both pill and sheet render with the result.
    var lightMode by rememberSaveable { mutableStateOf(false) }
    val activeColors = if (lightMode) WalletPanelColors.Light else colors

    // The pill — always rendered. Tap opens the sheet. dappPressable gives it
    // the pressed/hover/focus state layer + press scale the rest of the app has.
    WalletPill(
        status = status,
        network = network,
        formatter = formatter,
        colors = activeColors,
        modifier = modifier.dappPressable(
            shape = RoundedCornerShape(PanelDimens.PillCornerRadius),
        ) { sheetOpen = true },
    )

    // FragmentActivity is required by SeedVault.loadSeed / storeSeed for the
    // biometric prompt. ComponentActivity (the AppCompat base) extends it, but
    // raw Activity doesn't — example apps that integrate this panel must use
    // a FragmentActivity-derived host (ComponentActivity counts).
    val activity = LocalContext.current as? FragmentActivity

    // Auto-bootstrap on panel mount AND on any config change (network,
    // proving mode, proof server URL). The pill should start syncing the
    // moment the host app surfaces it. Intentionally triggers the biometric
    // prompt on first launch — see feedback_sdk_devx_principle.
    //
    // Keyed on (activity, config). WalletPanelViewModel.buildOrReuseSdk
    // compares the full config (not just network) so flipping proving mode
    // also tears down the SDK and builds fresh.
    //
    // Not keyed on status so that a failed bootstrap (Error) doesn't loop —
    // the user retries via the explicit `balance` button.
    LaunchedEffect(activity, config, enabled) {
        if (activity != null && enabled) {
            viewModel.refreshBalance(config, activity)
        }
    }

    // Auto-recover from SigilRequired: when the user signs in via the
    // sigil panel, [WalletPanelViewModel] observes [SigilStateStore]
    // and emits a `retryRequests` event. We collect those here and
    // re-fire `refreshBalance` with the config the VM emitted in the
    // event — NOT the `config` captured at LaunchedEffect launch
    // time. Compose captures `config` lazily, but if the user toggled
    // the network between landing on SigilRequired and signing in,
    // the captured `config` would be stale. The VM's
    // `lastRequestedConfig` is updated on every refreshBalance call,
    // so it always reflects the user's current intent.
    LaunchedEffect(activity) {
        if (activity == null) return@LaunchedEffect
        viewModel.retryRequests.collect { retryConfig ->
            viewModel.refreshBalance(retryConfig, activity)
        }
    }

    // Drive consent: the VM emits an IntentSender when enabling cloud backup
    // needs the first-time grant; launch it and report the result back.
    val backupSection by viewModel.backupSection.collectAsStateWithLifecycle()
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        activity?.let { viewModel.onConsentResult(config, it, result.data) }
    }
    LaunchedEffect(Unit) {
        viewModel.consentRequests.collect { request -> consentLauncher.launch(request) }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = sheetState,
            containerColor = activeColors.sheetBackground,
            dragHandle = null,
        ) {
            WalletSheetContent(
                status = status,
                syncProgress = syncProgress,
                config = config,
                formatter = formatter,
                colors = activeColors,
                lightMode = lightMode,
                onToggleLightMode = { lightMode = !lightMode },
                onNetworkChange = {
                    network = it
                    onNetworkChange(it)
                },
                onProvingModeChange = { provingMode = it },
                onProofServerUrlChange = { proofServerUrl = it },
                onRefreshBalance = { activity?.let { viewModel.refreshBalance(config, it, force = true) } },
                onRegisterDust = { activity?.let { viewModel.registerDust(config, it) } },
                backupSection = backupSection,
                onDustToggle = { enabled -> activity?.let { viewModel.setDustBackup(enabled, config, it) } },
                onDisableBackup = { activity?.let { viewModel.disableBackup(config, it) } },
                onReceive = {
                    // Dismiss the sheet first, then open the Receive screen on
                    // the next frame so the sheet's exit animation isn't
                    // competing with the screen's enter. Without the hide()
                    // step the screen would render *under* the sheet scrim.
                    coroutineScope.launch {
                        sheetState.hide()
                        sheetOpen = false
                        receiveOpen = true
                    }
                },
                onSend = {
                    // Same sheet→screen handoff as Receive. Start the flow clean
                    // (clear any prior success/failure) so the form opens fresh.
                    viewModel.resetSendState()
                    coroutineScope.launch {
                        sheetState.hide()
                        sheetOpen = false
                        sendOpen = true
                    }
                },
                onLockNow = {
                    viewModel.lockNow()
                    coroutineScope.launch {
                        sheetState.hide()
                        sheetOpen = false
                    }
                },
                onClose = {
                    coroutineScope.launch {
                        sheetState.hide()
                        sheetOpen = false
                    }
                },
            )
        }
    }

    // Receive screen — full-screen overlay rendered above the host content
    // (and above the now-dismissed sheet). Only available when the wallet is
    // Ready since we need addresses to display; in other states the sheet's
    // "receive" button is disabled so this branch shouldn't fire.
    //
    // Wrapped in a Popup so the screen escapes whatever Row/Box cell the
    // host stuck the panel pill in. Without this, fillMaxSize() in
    // WalletReceiveScreen resolves to the pill's narrow column and the
    // header text wraps one character per line.
    val readyStatus = status as? WalletStatus.Ready
    if (receiveOpen && readyStatus != null) {
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = { receiveOpen = false },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        ) {
            WalletReceiveScreen(
                unshieldedAddress = readyStatus.address,
                shieldedAddress = readyStatus.shieldedAddress,
                network = network,
                colors = activeColors,
                // Back returns to the sheet we came from (re-open it), instead of
                // dropping all the way to the host content — the stack the user expects.
                onBack = {
                    receiveOpen = false
                    sheetOpen = true
                },
            )
        }
    }

    // Send screen — same full-screen-overlay pattern as Receive (#240). Only
    // available when Ready (we need the sender address + spendable balance); the
    // sheet's Send button is disabled otherwise so this branch shouldn't fire.
    if (sendOpen && readyStatus != null) {
        // A focusable Popup consumes back at the view level (fires onDismissRequest)
        // before any child BackHandler — so the wizard publishes its back logic here
        // and we let it walk the steps / hide the keyboard; only an unconsumed back
        // (first step) closes the Popup.
        val sendBack = remember { mutableStateOf<() -> Boolean>({ false }) }
        Popup(
            alignment = Alignment.TopStart,
            onDismissRequest = {
                if (!sendBack.value()) {
                    sendOpen = false
                    sheetOpen = true
                }
            },
            properties = PopupProperties(focusable = true, dismissOnBackPress = true),
        ) {
            WalletSendScreen(
                // sendNight spends UNSHIELDED NIGHT, so cap the form at that pool.
                spendableNightRaw = readyStatus.balance.unshieldedNight,
                network = network,
                sendState = sendState,
                colors = activeColors,
                onSubmit = { to, amount -> activity?.let { viewModel.sendNight(config, it, to, amount) } },
                onResetState = { viewModel.resetSendState() },
                onBack = {
                    sendOpen = false
                    sheetOpen = true
                },
                registerBack = { sendBack.value = it },
            )
        }
    }
}

// ── Pill ──

@Composable
private fun WalletPill(
    status: WalletStatus,
    network: MidnightNetwork,
    formatter: BalanceFormatter,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
) {
    val label = pillLabel(status, network, formatter)
    val isError = status is WalletStatus.Error
    val borderColor = if (isError) colors.error else colors.pillBorder

    val pillShape = RoundedCornerShape(PanelDimens.PillCornerRadius)
    Row(
        modifier = modifier
            .clip(pillShape)
            .background(colors.pillBackground)
            .border(width = PanelDimens.PillBorderWidth, color = borderColor, shape = pillShape)
            .padding(
                horizontal = PanelDimens.PillHorizontalPadding,
                vertical = PanelDimens.PillVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PanelDimens.PillItemGap),
    ) {
        if (status is WalletStatus.Loading || (status is WalletStatus.Ready && status.busy != null)) {
            CircularProgressIndicator(
                modifier = Modifier.size(PanelDimens.PillSpinnerSize),
                strokeWidth = PanelDimens.PillSpinnerStroke,
                color = colors.accent,
            )
        }
        Text(
            text = label,
            color = if (isError) colors.error else colors.onPill,
            fontSize = PanelType.PillText,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "▾",
            color = colors.onPillDim,
            fontSize = PanelType.PillText,
        )
    }
}

// `internal` (not `private`) so PillLabelTest can lock down the formatting
// contract — see `sdk/dapp-ui/src/test/.../PillLabelTest.kt`.
internal fun pillLabel(
    status: WalletStatus,
    network: MidnightNetwork,
    formatter: BalanceFormatter,
): String = when (status) {
    is WalletStatus.None -> "${network.pillName} · wallet"
    is WalletStatus.Loading -> "${network.pillName} · loading…"
    is WalletStatus.Ready -> buildString {
        // Network prefix anchors the rest of the pill. Knowing which chain
        // these numbers are for matters more than the numbers themselves
        // once you start switching networks during dev.
        append(network.pillName)
        append(" · ")
        // Asymmetric format: NIGHT is the primary asset (unmarked), DUST
        // gets a "D" suffix. K/M/B abbreviations so the pill width stays
        // predictable as amounts grow; integer-only below 1K. Full
        // precision is in the sheet.
        //
        // **Unshielded vs shielded split:** the pill shows unshielded NIGHT
        // as its own slot, and only inserts a separate shielded slot when
        // there's non-zero shielded NIGHT. Earlier iterations aggregated
        // into `totalNight` and used a single shield glyph, which was
        // ambiguous — you couldn't tell how much of the number was private.
        // Two slots are clearer at a glance and degrade cleanly to a single
        // slot for users without any shielded balance.
        append(formatter.formatAbbreviated(status.balance.unshieldedNight, "NIGHT"))
        if (status.balance.hasShielded) {
            append(" · ")
            append(SHIELD_GLYPH)
            append(" ")
            append(formatter.formatAbbreviated(status.balance.shieldedNight, "NIGHT"))
        }
        append(" · ")
        append(formatter.formatAbbreviated(status.balance.dust, "DUST"))
        append("D")
        if (status.balance.dustRegistered) append(" · ✓")
    }
    is WalletStatus.Error -> "${network.pillName} · error"
    is WalletStatus.SigilRequired -> "${network.pillName} · sigil required"
    is WalletStatus.Locked -> "${network.pillName} · 🔒 locked"
}

/**
 * Short label used in the pill (`localnet`, `preview`, `preprod`). Lower-case
 * so it sits visually below the numeric portion of the pill rather than
 * competing with it. `internal` for testability — see PillLabelTest.
 */
internal val MidnightNetwork.pillName: String
    get() = when (this) {
        MidnightNetwork.UNDEPLOYED -> "localnet"
        MidnightNetwork.PREVIEW -> "preview"
        MidnightNetwork.PREPROD -> "preprod"
    }

/**
 * Prefix glyph signaling "some of this NIGHT is shielded". Only rendered when
 * [WalletBalance.hasShielded] is true, so non-shielded users never see it.
 */
private const val SHIELD_GLYPH = "🛡"

// ── Sheet content ──

@Composable
private fun WalletSheetContent(
    status: WalletStatus,
    syncProgress: WalletSyncProgress?,
    config: WalletConfig,
    formatter: BalanceFormatter,
    colors: WalletPanelColors,
    lightMode: Boolean,
    onToggleLightMode: () -> Unit,
    onNetworkChange: (MidnightNetwork) -> Unit,
    onProvingModeChange: (ProvingMode) -> Unit,
    onProofServerUrlChange: (String?) -> Unit,
    onRefreshBalance: () -> Unit,
    onRegisterDust: () -> Unit,
    backupSection: BackupSectionState,
    onDustToggle: (Boolean) -> Unit,
    onDisableBackup: () -> Unit,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onLockNow: () -> Unit,
    onClose: () -> Unit,
) {
    val busy = status is WalletStatus.Loading ||
        (status is WalletStatus.Ready && status.busy != null)
    Box(modifier = Modifier.fillMaxWidth()) {
        // Ambient star field — the brand texture ("stars against void") behind
        // the whole sheet, the same treatment the balance wireframe uses.
        StarField(
            modifier = Modifier.matchParentSize(),
            color = colors.onSheet,
            alpha = 0.5f,
            starCount = 40,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Scrollable so nothing (esp. the close button) is ever clipped,
                // regardless of content height / device size.
                .verticalScroll(rememberScrollState())
                .padding(
                    start = PanelDimens.SheetHorizontalPadding,
                    end = PanelDimens.SheetHorizontalPadding,
                    top = 4.dp,                          // tight top — was 20dp of dead space
                    bottom = PanelDimens.SheetVerticalPadding,
                ),
        ) {
            // No title (redundant — the pill already names the wallet). Just the
            // light/dark toggle, top-right; shows the icon for the mode you'd switch to.
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    if (lightMode) "☾" else "☀",
                    color = colors.onSheetDim,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(PanelDimens.PillCornerRadius))
                        .dappPressable(shape = RoundedCornerShape(PanelDimens.PillCornerRadius)) { onToggleLightMode() }
                        .padding(6.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Every wallet state gets a branded treatment, never bare text:
            // a shimmer skeleton while bootstrapping/loading, the compact balance
            // when ready, a retry card on error, a forge prompt when the sigil
            // is missing.
            when (status) {
                is WalletStatus.None,
                is WalletStatus.Loading -> WalletBalanceLoading(colors)
                is WalletStatus.Ready -> ReadyBody(
                    status, syncProgress, formatter, colors, onReceive, onSend, onRefreshBalance, onRegisterDust, busy,
                )
                is WalletStatus.Error -> WalletBalanceError(status.message, colors, onRetry = onRefreshBalance)
                is WalletStatus.SigilRequired -> SigilPrompt(colors)
                is WalletStatus.Locked -> WalletLocked(colors, onUnlock = onRefreshBalance)
            }

            Spacer(modifier = Modifier.height(PanelDimens.SheetSectionGap))

            // Compact config — network + proving as dropdown selectors (one row
            // instead of five chip-rows). Picking either rebuilds the SDK.
            ConfigRow(
                network = config.network,
                provingMode = config.provingMode,
                proofServerUrl = config.proofServerUrl,
                colors = colors,
                onNetworkChange = onNetworkChange,
                onProvingModeChange = onProvingModeChange,
                onProofServerUrlChange = onProofServerUrlChange,
            )

            Spacer(modifier = Modifier.height(PanelDimens.SheetActionsTopGap))

            // Branded backup & recovery — per-lane status (identity / dust /
            // app-data) from the wallet's real backupStatus.
            BackupSection(
                state = backupSection,
                colors = colors,
                onDustToggle = onDustToggle,
                onDisableBackup = onDisableBackup,
                // App-data backup is automatic; its only actionable state is a
                // failed save, whose "Retry" re-runs a forced refresh.
                onAppDataAction = onRefreshBalance,
            )
            Spacer(modifier = Modifier.height(PanelDimens.SheetButtonRowGap))
            // Manual session lock — only meaningful with a live (unlocked) wallet.
            // Understated so it's not confused with the primary actions.
            if (status is WalletStatus.Ready) {
                Text(
                    text = "lock now",
                    color = colors.onSheetDim,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clickable { onLockNow() }
                        .padding(8.dp),
                )
                Spacer(modifier = Modifier.height(PanelDimens.SheetButtonRowGap))
            }
            PanelButton("close", enabled = true, modifier = Modifier.fillMaxWidth(), colors = colors, onClick = onClose)
            Spacer(modifier = Modifier.height(PanelDimens.SheetBottomGap))
        }
    }
}

/** Branded "sigil required" prompt — the wallet can't bootstrap without a passkey. */
@Composable
private fun SigilPrompt(colors: WalletPanelColors) {
    GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
        Text("SIGIL REQUIRED", color = colors.onSheetDim, fontSize = 11.sp, fontWeight = FontWeight.W400, letterSpacing = 3.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Forge your sigil first — the wallet derives its seed from your passkey. " +
                "Open the sigil panel above, tap “forge sigil”, then return.",
            color = colors.onSheetSubtle,
            fontSize = PanelType.Body,
            lineHeight = 20.sp,
        )
    }
}

/** Branded "session locked" body (#14) — explicit, actionable; not a shimmer. */
@Composable
private fun WalletLocked(colors: WalletPanelColors, onUnlock: () -> Unit) {
    GlassPanel(tint = colors.button, border = colors.onSheetSubtle) {
        Text("🔒 LOCKED", color = colors.onSheetDim, fontSize = 11.sp, fontWeight = FontWeight.W400, letterSpacing = 3.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "Your session is locked. Unlock with your device biometric to see your " +
                "balance and act. Your identity stays signed in.",
            color = colors.onSheetSubtle,
            fontSize = PanelType.Body,
            lineHeight = 20.sp,
        )
        Spacer(modifier = Modifier.height(14.dp))
        PanelButton("unlock", enabled = true, modifier = Modifier.fillMaxWidth(), colors = colors, onClick = onUnlock)
    }
}

@Composable
private fun ReadyBody(
    status: WalletStatus.Ready,
    syncProgress: WalletSyncProgress?,
    formatter: BalanceFormatter,
    colors: WalletPanelColors,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onRegister: () -> Unit,
    busy: Boolean,
) {
    // NIGHT is one asset, two pools: the hero is the total (public + private),
    // with the shielded portion noted as a chip. DUST is its own row. The ⟳
    // refresh + Register affordances live inside the card; the runner shows real
    // progress digits when a resync is streaming.
    val b = status.balance
    // The sheet has room for accuracy (the pill uses the abbreviated "6K"): show
    // the grouped integer + 2 decimals, not the 15-decimal sprawl.
    val dustFull = formatter.format(b.dust, "DUST", includeSymbol = false)
    val dustExact = dustFull.substringBefore('.') +
        dustFull.substringAfter('.', "").take(2).let { if (it.isNotEmpty()) ".$it" else "" }
    val balanceUi = WalletBalanceUi(
        nightTotal = formatter.formatCompact(b.unshieldedNight + b.shieldedNight, "NIGHT", includeSymbol = false),
        publicNight = formatter.formatCompact(b.unshieldedNight, "NIGHT", includeSymbol = false),
        privateNight = if (b.hasShielded) formatter.formatCompact(b.shieldedNight, "NIGHT", includeSymbol = false) else null,
        statusLabel = if (syncProgress != null || status.busy != null) "Syncing…" else "Synced",
        dust = dustExact,
        dustRegistered = b.dustRegistered,
    )
    // Any busy state drives the branded runner (sync → real digits; other work
    // like dust-registration polling → indeterminate with its own label).
    val working = syncProgress ?: status.busy?.let { WalletSyncProgress(null, it) }
    WalletBalanceCompact(
        ui = balanceUi,
        syncProgress = working,
        colors = colors,
        onReceive = onReceive,
        onRefresh = onRefresh,
        onRegister = onRegister,
        busy = busy,
        onSend = onSend,
    )

    if (status.message != null) {
        Spacer(modifier = Modifier.height(PanelDimens.SheetMessageGap))
        Text(status.message, color = colors.accent.copy(alpha = MESSAGE_ALPHA), fontSize = PanelType.Caption)
    }
}

/** Small uppercase-style header text used above every section in the sheet.
 *  ~75% on-sheet so it's actually readable (the old 20% onSheetSubtle failed HIG). */
@Composable
private fun SectionLabel(text: String, colors: WalletPanelColors) {
    Text(
        text = text,
        color = colors.onSheet.copy(alpha = 0.75f),
        fontSize = PanelType.SectionLabel,
        fontWeight = FontWeight.Medium,
    )
}

/** Slight de-emphasis for the secondary message line (vs the busy line). */
private const val MESSAGE_ALPHA = 0.8f

/**
 * Compact wallet config — network + proving as dropdown selectors in one row,
 * plus the proof-server URL field when REMOTE proving is picked. Replaces the
 * old full-width chip rows (5 rows → 1); picking either rebuilds the SDK via the
 * panel's LaunchedEffect on `config`.
 */
@Composable
private fun ConfigRow(
    network: MidnightNetwork,
    provingMode: ProvingMode,
    proofServerUrl: String?,
    colors: WalletPanelColors,
    onNetworkChange: (MidnightNetwork) -> Unit,
    onProvingModeChange: (ProvingMode) -> Unit,
    onProofServerUrlChange: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PanelDimens.SheetButtonRowGap),
        ) {
            PanelSelector(
                label = "network",
                selected = network,
                options = MidnightNetwork.entries,
                optionLabel = { it.pillName },
                colors = colors,
                modifier = Modifier.weight(1f),
                onSelect = onNetworkChange,
            )
            PanelSelector(
                label = "proving",
                selected = provingMode,
                options = ProvingMode.entries,
                // "on-device" is clearer than "local" for where the proof runs.
                optionLabel = { mode ->
                    when (mode) {
                        ProvingMode.LOCAL -> "on-device"
                        ProvingMode.REMOTE -> "remote"
                    }
                },
                colors = colors,
                modifier = Modifier.weight(1f),
                onSelect = onProvingModeChange,
            )
        }
        if (provingMode == ProvingMode.REMOTE) {
            Spacer(modifier = Modifier.height(PanelDimens.SheetLabelGap))
            OutlinedTextField(
                value = proofServerUrl.orEmpty(),
                onValueChange = { onProofServerUrlChange(it.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        "proof server url (default: localhost:6300)",
                        color = colors.onSheetSubtle,
                        fontSize = PanelType.AirdropCmd,
                    )
                },
                textStyle = TextStyle(
                    color = colors.onSheet,
                    fontSize = PanelType.AirdropCmd,
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.pillBorder,
                    cursorColor = colors.accent,
                ),
            )
        }
    }
}

/**
 * Compact labelled dropdown selector — a chip showing the current [selected]
 * value; tap opens a [DropdownMenu] of [options]. Generic so it drives both the
 * network and proving pickers.
 */
@Composable
private fun <T> PanelSelector(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(PanelDimens.ButtonCornerRadius)
    Column(modifier = modifier) {
        SectionLabel(label, colors)
        Spacer(modifier = Modifier.height(PanelDimens.SheetLabelGap))
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(PanelDimens.ButtonHeight)
                    .clip(shape)
                    .background(colors.button)
                    .border(1.dp, colors.pillBorder, shape)
                    .dappPressable(shape = shape) { expanded = true }
                    .padding(horizontal = 14.dp),
            ) {
                Text(
                    optionLabel(selected),
                    color = colors.onSheet,
                    fontSize = PanelType.ButtonText,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", color = colors.onSheetDim, fontSize = PanelType.ButtonText)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(colors.sheetBackground),
            ) {
                options.forEach { opt ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                optionLabel(opt),
                                color = if (opt == selected) colors.accent else colors.onSheet,
                                fontSize = PanelType.ButtonText,
                                fontWeight = if (opt == selected) FontWeight.Medium else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            onSelect(opt)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PanelButton(
    text: String,
    enabled: Boolean,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(PanelDimens.ButtonHeight),
        shape = RoundedCornerShape(PanelDimens.ButtonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.button,
            contentColor = colors.onButton,
            disabledContainerColor = colors.buttonDisabled,
            disabledContentColor = colors.onButtonDisabled,
        ),
        contentPadding = PaddingValues(horizontal = PanelDimens.ButtonHorizontalPadding),
    ) {
        Text(text, fontSize = PanelType.ButtonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Color palette ──

/**
 * Color tokens for [WalletStatusPanel]. Host apps can pass a custom
 * palette to theme the wallet panel; [Default] matches the dark-themed
 * example apps already in this repo.
 *
 * **API contract:** part of the dapp-ui public surface. `@Immutable` so
 * Compose treats the type as stable across recompositions.
 */
@Immutable
data class WalletPanelColors(
    val pillBackground: Color,
    val pillBorder: Color,
    val onPill: Color,
    val onPillDim: Color,
    val sheetBackground: Color,
    val onSheet: Color,
    val onSheetDim: Color,
    val onSheetSubtle: Color,
    val accent: Color,
    val error: Color,
    val button: Color,
    val onButton: Color,
    val buttonDisabled: Color,
    val onButtonDisabled: Color,
) {
    companion object {
        // On-brand "dusk" dark default, sourced from the shared brand palette
        // ([MidnightColors] in core:designsystem). Accent = semantic SuccessText
        // (replaced the prior off-brand blue), error = semantic ErrorText.
        val Default = WalletPanelColors(
            pillBackground = MidnightColors.VoidElevated,
            pillBorder = MidnightColors.LightFaint,
            onPill = MidnightColors.LightSoft,
            onPillDim = MidnightColors.LightMuted,
            sheetBackground = MidnightColors.VoidElevated,
            onSheet = MidnightColors.Light,
            onSheetDim = MidnightColors.LightMuted,
            onSheetSubtle = MidnightColors.LightFaint,
            accent = MidnightColors.SuccessText,
            error = MidnightColors.ErrorText,
            button = MidnightColors.ButtonSurface,
            onButton = MidnightColors.Light,
            buttonDisabled = MidnightColors.LightBarely,
            onButtonDisabled = MidnightColors.LightFaint,
        )

        // Light "dusk" variant (mirrors DuskPalette.LightMode) — the panel's
        // sun/moon toggle swaps to this. Black-on-off-white at varying alpha;
        // semantic Error/Success darkened for contrast on a light surface.
        val Light = WalletPanelColors(
            pillBackground = Color(0xFFFFFFFF),
            pillBorder = Color(0x33000000),
            onPill = Color(0xCC000000),
            onPillDim = Color(0x80000000),
            sheetBackground = Color(0xFFF7F7F7),
            onSheet = Color(0xFF000000),
            onSheetDim = Color(0x80000000),
            onSheetSubtle = Color(0x33000000),
            accent = Color(0xFF2E7D32),
            error = Color(0xFFCC0000),
            button = Color(0xFFFFFFFF),
            onButton = Color(0xFF000000),
            buttonDisabled = Color(0x0A000000),
            onButtonDisabled = Color(0x33000000),
        )
    }
}
