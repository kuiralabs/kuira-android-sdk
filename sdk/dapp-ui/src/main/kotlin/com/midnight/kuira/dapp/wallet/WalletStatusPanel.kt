package com.midnight.kuira.dapp.wallet

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    val SheetMinHeight = 420.dp        // Forces a real surface, not a sliver.
    val SheetHorizontalPadding = 24.dp
    val SheetVerticalPadding = 20.dp
    val SheetTitleGap = 20.dp          // Below "wallet status" header.
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
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val syncProgress by viewModel.syncProgress.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var receiveOpen by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val coroutineScope = rememberCoroutineScope()
    val formatter = remember { BalanceFormatter() }

    // Panel owns the full wallet config now (network + proving mode + proof
    // server URL). User picks any of these in the sheet, the LaunchedEffect
    // below re-fires with the new config and the VM rebuilds the SDK.
    var network by rememberSaveable { mutableStateOf(initialNetwork) }
    var provingMode by rememberSaveable { mutableStateOf(ProvingMode.DEFAULT) }
    var proofServerUrl by rememberSaveable { mutableStateOf<String?>(null) }
    val config = remember(network, provingMode, proofServerUrl) {
        WalletConfig(network = network, provingMode = provingMode, proofServerUrl = proofServerUrl)
    }

    // The pill — always rendered. Tap opens the sheet. dappPressable gives it
    // the pressed/hover/focus state layer + press scale the rest of the app has.
    WalletPill(
        status = status,
        network = network,
        formatter = formatter,
        colors = colors,
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
            containerColor = colors.sheetBackground,
            dragHandle = null,
        ) {
            WalletSheetContent(
                status = status,
                syncProgress = syncProgress,
                config = config,
                formatter = formatter,
                colors = colors,
                onNetworkChange = {
                    network = it
                    onNetworkChange(it)
                },
                onProvingModeChange = { provingMode = it },
                onProofServerUrlChange = { proofServerUrl = it },
                onRefreshBalance = { activity?.let { viewModel.refreshBalance(config, it, force = true) } },
                onRegisterDust = { activity?.let { viewModel.registerDust(config, it) } },
                backupSection = backupSection,
                onEnableCloudBackup = { activity?.let { viewModel.enableCloudBackup(config, it) } },
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
                colors = colors,
                onBack = { receiveOpen = false },
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
    onNetworkChange: (MidnightNetwork) -> Unit,
    onProvingModeChange: (ProvingMode) -> Unit,
    onProofServerUrlChange: (String?) -> Unit,
    onRefreshBalance: () -> Unit,
    onRegisterDust: () -> Unit,
    backupSection: BackupSectionState,
    onEnableCloudBackup: () -> Unit,
    onReceive: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PanelDimens.SheetMinHeight)
            .padding(
                horizontal = PanelDimens.SheetHorizontalPadding,
                vertical = PanelDimens.SheetVerticalPadding,
            ),
    ) {
        Text(
            "wallet status",
            color = colors.onSheetDim,
            fontSize = PanelType.Body,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(PanelDimens.SheetTitleGap))

        when (status) {
            is WalletStatus.None -> Text(
                "Read balance to bootstrap the wallet. First press shows a biometric prompt to seal/load the seed via SeedVault. Then tap receive to see your addresses.",
                color = colors.onSheetSubtle,
                fontSize = PanelType.Body,
            )
            is WalletStatus.Loading -> Text(status.stage, color = colors.onSheetDim, fontSize = PanelType.LoadingText)
            is WalletStatus.Ready -> ReadyBody(status, syncProgress, formatter, colors, onReceive)
            is WalletStatus.Error -> Text("error: ${status.message}", color = colors.error, fontSize = PanelType.ErrorText)
            is WalletStatus.SigilRequired -> Text(
                "Forge your sigil first — the wallet derives its seed from your passkey. " +
                    "Open the sigil panel above, tap “forge sigil”, then come back and read balance.",
                color = colors.onSheetSubtle,
                fontSize = PanelType.Body,
            )
        }

        Spacer(modifier = Modifier.height(PanelDimens.SheetSectionGap))

        // Config controls — network + proving mode. Picking any of these
        // tears down the in-memory SDK and rebuilds for the new config.
        NetworkChipRow(
            selected = config.network,
            colors = colors,
            onSelect = onNetworkChange,
        )
        Spacer(modifier = Modifier.height(PanelDimens.SheetSectionGap))
        ProvingModeToggle(
            selected = config.provingMode,
            proofServerUrl = config.proofServerUrl,
            colors = colors,
            onSelect = onProvingModeChange,
            onUrlChange = onProofServerUrlChange,
        )

        Spacer(modifier = Modifier.height(PanelDimens.SheetActionsTopGap))

        val busy = status is WalletStatus.Loading ||
            (status is WalletStatus.Ready && status.busy != null)
        // Secondary utility actions: refresh balance + register for dust. Receive
        // moved to the compact balance's quick action (and Send arrives there with
        // #240), so it's no longer duplicated here. The previous "fund" button was
        // removed once the Receive screen started showing the `mn airdrop` command
        // directly — the SDK's subscription picks up the credit automatically.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PanelDimens.SheetButtonRowGap),
        ) {
            PanelButton("balance", enabled = !busy, modifier = Modifier.weight(1f), colors = colors, onClick = onRefreshBalance)
            PanelButton("register", enabled = !busy, modifier = Modifier.weight(1f), colors = colors, onClick = onRegisterDust)
        }
        Spacer(modifier = Modifier.height(PanelDimens.SheetButtonRowGap))
        // Branded backup & recovery — per-lane status (identity / dust / app-data)
        // from the wallet's real backupStatus. The dust "Enable" pill runs the
        // existing Drive-consent flow; on consent the next refresh uploads and the
        // lane flips to synced. Replaces the old single "cloud sync" button.
        BackupSection(
            state = backupSection,
            colors = colors,
            onDustAction = onEnableCloudBackup,
            // App-data backup is automatic; the only actionable app-data state is
            // a failed save, whose "Retry" re-runs a forced refresh (which re-
            // attempts the app-state upload). The empty "None yet" state has no CTA.
            onAppDataAction = onRefreshBalance,
        )
        Spacer(modifier = Modifier.height(PanelDimens.SheetButtonRowGap))
        PanelButton("close", enabled = true, modifier = Modifier.fillMaxWidth(), colors = colors, onClick = onClose)
        Spacer(modifier = Modifier.height(PanelDimens.SheetBottomGap))
    }
}

@Composable
private fun ReadyBody(
    status: WalletStatus.Ready,
    syncProgress: WalletSyncProgress?,
    formatter: BalanceFormatter,
    colors: WalletPanelColors,
    onReceive: () -> Unit,
) {
    // Address + airdrop command live in [WalletReceiveScreen]; the sheet shows
    // the branded compact balance ([WalletBalanceCompact]) — hero figure, the
    // dust/shielded token card, and the Receive quick action. (Send arrives with
    // #240.) The detail line reflects sync/working state; the runner shows the
    // real progress digits when a resync is streaming.
    val ui = WalletBalanceUi(
        nightPrimary = formatter.formatCompact(status.balance.unshieldedNight, "NIGHT", includeSymbol = false),
        detail = when {
            syncProgress != null -> "NIGHT · Syncing…"
            status.busy != null -> "NIGHT · ${status.busy}"
            else -> "NIGHT · Synced"
        },
        dust = formatter.formatCompact(status.balance.dust, "DUST", includeSymbol = false),
        dustRegistered = status.balance.dustRegistered,
        shielded = if (status.balance.hasShielded) {
            formatter.formatCompact(status.balance.shieldedNight, "NIGHT", includeSymbol = false)
        } else {
            null
        },
    )
    WalletBalanceCompact(
        ui = ui,
        syncProgress = syncProgress,
        colors = colors,
        onReceive = onReceive,
    )

    if (status.message != null) {
        Spacer(modifier = Modifier.height(PanelDimens.SheetMessageGap))
        Text(status.message, color = colors.accent.copy(alpha = MESSAGE_ALPHA), fontSize = PanelType.Caption)
    }
}

/** Small uppercase-style header text used above every section in the sheet. */
@Composable
private fun SectionLabel(text: String, colors: WalletPanelColors) {
    Text(
        text = text,
        color = colors.onSheetSubtle,
        fontSize = PanelType.SectionLabel,
        fontWeight = FontWeight.Medium,
    )
}

/** Slight de-emphasis for the secondary message line (vs the busy line). */
private const val MESSAGE_ALPHA = 0.8f

/**
 * Three-way chip row for [MidnightNetwork]. Picking a chip propagates up
 * via [onSelect]; the panel's LaunchedEffect then re-fires with the new
 * config and the VM rebuilds the SDK for that network.
 */
@Composable
private fun NetworkChipRow(
    selected: MidnightNetwork,
    colors: WalletPanelColors,
    onSelect: (MidnightNetwork) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("network", colors)
        Spacer(modifier = Modifier.height(PanelDimens.SheetLabelGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PanelDimens.SheetButtonRowGap),
        ) {
            MidnightNetwork.entries.forEach { network ->
                ChipButton(
                    text = network.pillName,
                    selected = network == selected,
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(network) },
                )
            }
        }
    }
}

/**
 * Two-way chip row for [ProvingMode] plus an optional URL field that
 * appears only when REMOTE is selected. URL is debounced on focus-loss /
 * blur (text edits propagate as typed; the LaunchedEffect on `config` will
 * rebuild the SDK each keystroke, so prefer settling on a value before
 * picking REMOTE).
 */
@Composable
private fun ProvingModeToggle(
    selected: ProvingMode,
    proofServerUrl: String?,
    colors: WalletPanelColors,
    onSelect: (ProvingMode) -> Unit,
    onUrlChange: (String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionLabel("proving", colors)
        Spacer(modifier = Modifier.height(PanelDimens.SheetLabelGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PanelDimens.SheetButtonRowGap),
        ) {
            ProvingMode.entries.forEach { mode ->
                ChipButton(
                    // "on-device" is clearer than "local" for where the proof runs.
                    text = when (mode) {
                        ProvingMode.LOCAL -> "on-device"
                        ProvingMode.REMOTE -> "remote"
                    },
                    selected = mode == selected,
                    colors = colors,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(mode) },
                )
            }
        }
        if (selected == ProvingMode.REMOTE) {
            Spacer(modifier = Modifier.height(PanelDimens.SheetLabelGap))
            OutlinedTextField(
                value = proofServerUrl.orEmpty(),
                onValueChange = { onUrlChange(it.ifBlank { null }) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        "proof server url (default: localhost:6300)",
                        color = colors.onSheetSubtle,
                        fontSize = PanelType.AirdropCmd,
                    )
                },
                textStyle = androidx.compose.ui.text.TextStyle(
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
 * Compact chip rendered as a filled button when selected, outlined when
 * not. Shared between [NetworkChipRow] and [ProvingModeToggle].
 */
@Composable
private fun ChipButton(
    text: String,
    selected: Boolean,
    colors: WalletPanelColors,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bg = if (selected) colors.onSheet else colors.button
    val fg = if (selected) colors.sheetBackground else colors.onSheetDim
    val chipShape = RoundedCornerShape(PanelDimens.ButtonCornerRadius)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(PanelDimens.ButtonHeight)
            // dappPressable is the OUTER modifier so the whole chip scales on
            // press and the state layer draws over the fill; the filled-vs-
            // outlined bg/fg above remains the at-rest SELECTED style.
            .dappPressable(shape = chipShape, selected = selected, onClick = onClick)
            .clip(chipShape)
            .background(bg),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = PanelType.ButtonText,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    }
}
