package com.midnight.kuira.dapp.wallet

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.identity.backup.AuthorizeOutcome
import com.midnight.kuira.dapp.backup.AppDataBackupProvider
import com.midnight.kuira.core.identity.backup.DriveAuthManager
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.dapp.backup.BackupLaneState
import com.midnight.kuira.dapp.backup.BackupSectionState
import com.midnight.kuira.sdk.BackupStatusSnapshot
import com.midnight.kuira.sdk.CloudBackupStatus
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.sdk.SyncStatus
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import com.midnight.kuira.sdk.walletruntime.NetworkPreferenceStore
import com.midnight.kuira.sdk.walletruntime.SessionLock
import com.midnight.kuira.sdk.walletruntime.WalletConfig
import com.midnight.kuira.sdk.walletruntime.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger
import java.util.Optional
import javax.inject.Inject

/**
 * Wallet panel presenter: drives the canonical [WalletConfig] and translates
 * SDK / seed state into [WalletStatus] for the panel UI.
 *
 * **The config authority.** The panel is the only surface with the network /
 * proving-mode / proof-server toggles, so it's where config changes enter the
 * system. It passes the current [WalletConfig] to [MidnightSdkProvider.ensureSdk],
 * which builds (or rebuilds) the one shared SDK. Other consumers (BBoard, Kicks)
 * *follow* that SDK via the provider — they never build their own. That's what
 * makes the app sync once instead of once per consumer.
 *
 * **Does NOT own the SDK lifecycle.** [MidnightSdkProvider] is a process
 * singleton that survives activity recreation, so there's no SDK to close in
 * [onCleared]. The provider also owns seed bootstrap (it delegates to
 * `WalletSeedSource`) and wallet proving-key readiness; this VM just requests
 * the SDK and reads balances off it.
 *
 * **Public surface:** [status] (observe), [refreshBalance] / [registerDust]
 * (act). Funding needs no handler — the Receive screen shows the airdrop
 * command and the SDK's subscription picks up the credit on its own.
 */
@HiltViewModel
class WalletPanelViewModel @Inject constructor(
    private val sdkProvider: MidnightSdkProvider,
    private val sigilStateStore: SigilStateStore,
    private val driveAuth: DriveAuthManager,
    private val sessionLock: SessionLock,
    private val networkStore: NetworkPreferenceStore,
    @ApplicationContext private val appContext: Context,
    /**
     * Host-bound app-state source (empty when the host binds none, e.g. BBoard).
     * Connected to [com.midnight.kuira.sdk.MidnightWallet.appStateProvider] so the
     * pill's App-data lane actually backs up — #244's silent path wired its
     * uploader but never its source.
     */
    private val appDataProvider: Optional<AppDataBackupProvider>,
) : ViewModel() {

    /**
     * The selected network — the DURABLE single source of truth ([NetworkPreferenceStore]),
     * surviving process death so a session-lock kill can't revert the choice to the localnet
     * default. The panel observes this instead of an in-memory selection.
     */
    val selectedNetwork: StateFlow<MidnightNetwork> = networkStore.selected

    /** Persist the user's network choice durably (synchronous — survives an immediate kill). */
    fun selectNetwork(network: MidnightNetwork) = networkStore.set(network)

    /** Seed the first-run network from a host default; ignored once the user has ever chosen. */
    fun seedNetworkDefault(network: MidnightNetwork) = networkStore.seedDefaultIfUnset(network)

    /** Adapts the bound provider to the wallet's `appStateProvider` lambda type. */
    private val appStateSnapshot: (suspend () -> ByteArray?)? =
        appDataProvider.orElse(null)?.let { provider -> { provider.snapshot() } }

    /** Persists the dust-backup opt-out across launches + SDK rebuilds. */
    private val backupPrefs = appContext.getSharedPreferences("kuira_dust_backup", Context.MODE_PRIVATE)

    /** True → the user disabled dust cloud backup (we stop uploading). */
    private val _dustOptedOut = MutableStateFlow(backupPrefs.getBoolean(KEY_DUST_OPTED_OUT, false))

    /**
     * True → the user has dust cloud backup ENABLED — a durable choice, persisted across launches.
     * Drives the toggle's on/off position so the live sync status (which churns on every balance
     * refresh) never moves the Switch. Set on a successful enable+sync, cleared on opt-out/disable.
     */
    private val _dustBackupEnabled = MutableStateFlow(backupPrefs.getBoolean(KEY_DUST_BACKUP_ENABLED, false))

    private val _status = MutableStateFlow<WalletStatus>(WalletStatus.None)
    val status: StateFlow<WalletStatus> = _status

    /**
     * Send-flow state for the Send screen (#240). Idle until the user submits;
     * then Sending (with the coarse [MidnightSdk.SendProgress] stage as a label),
     * resolving to Success (tx hash) or Failure (a user-facing reason). Reset to
     * Idle when the Send screen closes.
     */
    private val _sendState = MutableStateFlow<SendUiState>(SendUiState.Idle)
    val sendState: StateFlow<SendUiState> = _sendState

    /**
     * Live sync progress for the sheet's [WalletSyncIndicator] — derived from the
     * SDK's [com.midnight.kuira.sdk.MidnightWallet.syncStatus], the SAME signal the
     * background Live-Update notification uses (#259). So the in-app pill and the
     * notification are always consistent: it shows for EVERY sync (foreground
     * refresh, proactive background tracker, genesis), with the fraction + the
     * phase label resolved through the one shared [labelRes] mapping. Non-null only
     * while [SyncStatus.Syncing]; null otherwise (the indicator hides).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val syncProgress: StateFlow<WalletSyncProgress?> = sdkProvider.sdk
        .flatMapLatest { it?.wallet?.syncStatus ?: flowOf(SyncStatus.Idle) }
        .map { status ->
            (status as? SyncStatus.Syncing)?.let {
                WalletSyncProgress(it.fraction, appContext.getString(it.phase.labelRes()))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SYNC_SUBSCRIBE_TIMEOUT_MS), null)

    /**
     * Optimistic "turning dust backup on" flag. Flipped true the instant the user
     * taps the switch, BEFORE the Drive consent + sync round-trip (which is slow
     * and network-bound) reports any status. Without it the controlled Switch
     * snaps back to off and the action looks dead. Cleared when the flow resolves.
     */
    private val _dustEnabling = MutableStateFlow(false)

    /**
     * Branded backup-section state for the pill — the real per-lane status from
     * the wallet ([com.midnight.kuira.sdk.MidnightWallet.backupStatus]) plus the
     * sigil-presence identity lane. Surfaces "needs consent"/syncing/up-to-date
     * instead of the old silent "cloud sync" label.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val backupSection: StateFlow<BackupSectionState> = combine(
        sdkProvider.sdk.flatMapLatest { it?.wallet?.backupStatus ?: flowOf(BackupStatusSnapshot()) },
        sigilStateStore.snapshotFlow,
        _dustEnabling,
        _dustBackupEnabled,
    ) { backup, sigil, dustEnabling, enabled ->
        BackupSectionState(
            identity = if (sigil != null) BackupLaneState.Ok("Protected", confirm = true)
            else BackupLaneState.Ok("Not set up"),
            // [dustToggleLane] keeps the Switch on the user's DURABLE choice, never the live sync
            // status — so a balance refresh's status churn can't move or spin it.
            dust = dustToggleLane(enabled = enabled, enabling = dustEnabling, status = backup.dust),
            appData = backup.appData.toAppDataLane(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(SYNC_SUBSCRIBE_TIMEOUT_MS),
        BackupSectionState(
            identity = BackupLaneState.Ok("Protected", confirm = true),
            dust = BackupLaneState.Toggle(on = false),
            appData = BackupLaneState.Ok("None yet"),
        ),
    )

    /**
     * One-shot Drive consent requests. When enabling cloud backup needs the
     * first-time `drive.appdata` grant, the VM emits the IntentSender here; the
     * panel launches it via `rememberLauncherForActivityResult` and reports the
     * result back to [onConsentResult]. SharedFlow (not State) — it's an action.
     */
    private val _consentRequests = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)
    val consentRequests: SharedFlow<IntentSenderRequest> = _consentRequests.asSharedFlow()

    /**
     * Live balance observer (collects [com.midnight.kuira.sdk.MidnightWallet.balanceFlow]).
     * Re-armed (cancel + relaunch) on each [refreshBalance] so only one runs;
     * [viewModelScope] cancels it when the VM is cleared. This is what makes the
     * panel update automatically when funds land (airdrop / incoming tx) instead
     * of only on a manual refresh.
     */
    private var observeBalanceJob: Job? = null

    /**
     * One-shot events asking the host to re-trigger
     * [refreshBalance]. Emitted when the sigil becomes available
     * while the wallet is stuck on [WalletStatus.SigilRequired] —
     * closes the loop so the user doesn't have to re-tap "balance"
     * after signing in.
     *
     * **Carries the [WalletConfig] inside the event** so the host
     * uses the VM's last-known-good config rather than whatever
     * Compose captured at LaunchedEffect launch time. Without this,
     * a user who toggles network between hitting SigilRequired and
     * signing in would trigger an auto-retry against the stale
     * pre-toggle config — wrong network, wrong balance.
     *
     * `SharedFlow` (not `StateFlow`) because each emission is an
     * action signal, not state to render. The host's
     * [WalletStatusPanel] subscribes via `LaunchedEffect` and
     * dispatches a refresh — only one collector is expected.
     */
    private val _retryRequests = MutableSharedFlow<WalletConfig>(extraBufferCapacity = 1)
    val retryRequests: SharedFlow<WalletConfig> = _retryRequests.asSharedFlow()

    /**
     * Last [WalletConfig] passed into [refreshBalance] / [registerDust] —
     * the config the user actually asked for. Drives the sigil auto-retry:
     * if a wallet action got gated to [WalletStatus.SigilRequired], this is
     * the config the retry re-fires with once the sigil arrives.
     *
     * Tracked here rather than read back from [MidnightSdkProvider.activeConfig]
     * because a bootstrap that failed on SigilRequired never reached the
     * provider's build — `activeConfig` would still be null, but we know what
     * the user wanted.
     */
    private var lastRequestedConfig: WalletConfig? = null

    /** Wallet the live [observeBalanceJob] is bound to — so it's re-armed only on
     *  bootstrap / network switch, not on every refresh. */
    private var observedWalletAddress: String? = null

    /** Wall-clock of the last heavy resync ([com.midnight.kuira.sdk.MidnightWallet.refresh]).
     *  The resync is throttled to [FULL_REFRESH_INTERVAL_MS]: the live observer keeps
     *  the balance current in between, so we don't re-sync on every menu visit. */
    private var lastFullRefreshAtMs = 0L

    init {
        observeSigilLifecycle()
        observeSessionLock()
    }

    /**
     * Manually lock the session (the wallet sheet's "Lock now"). Drops the
     * cached SDK; the balance hides and the next action re-authenticates.
     */
    fun lockNow() = sessionLock.lockNow()

    /**
     * Hide the balance the moment the session locks (idle / background /
     * screen-off / manual). We reset to [WalletStatus.None] rather than read
     * `sdk == null` directly, because that's also briefly true during a
     * network-switch rebuild — [SessionLock.locked] fires only on a real lock.
     */
    private fun observeSessionLock() {
        viewModelScope.launch {
            sessionLock.locked.collect { locked ->
                if (locked) {
                    Log.i(TAG, "Session locked — showing Locked state until re-auth")
                    observeBalanceJob?.cancel()
                    // syncProgress is derived from the SDK's syncStatus; the lock
                    // drops the SDK (provider.sdk → null) so it falls to null on its own.
                    _status.value = WalletStatus.Locked
                } else if (_status.value is WalletStatus.Locked) {
                    // Unlocked: the SessionLockGate re-authenticated (the seed is
                    // warm now). Clear Locked and ask the host to refresh so the
                    // pill/sheet show the real balance — this reuses the open
                    // Keystore window, so it does NOT prompt a second biometric.
                    Log.i(TAG, "Session unlocked — clearing Locked and requesting a refresh")
                    _status.value = WalletStatus.None
                    lastRequestedConfig?.let { _retryRequests.emit(it) }
                }
            }
        }
    }

    /**
     * Keep the wallet in lock-step with the sigil's lifecycle.
     *
     * [SigilStateStore.snapshotFlow] emits the persisted sigil triple — null on a
     * fresh install, non-null after `SigilSession.signIn`/forge lands, and back to
     * null when the user signs out (`SigilStateStore.clear`). Two transitions matter:
     *
     *  - **null → non-null (signed in):** if the wallet was parked on
     *    [WalletStatus.SigilRequired], clear it to [WalletStatus.None] and emit a
     *    one-shot retry so the host re-runs `refreshBalance` with the last config the
     *    user asked for. No-op otherwise, so we never auto-trigger a biometric for a
     *    user who only touched the sigil panel.
     *  - **non-null → null (signed out):** the seed is gone and the SDK is closed, so
     *    a live wallet must NOT keep showing a balance. Cancel the balance observer and
     *    reset to [WalletStatus.SigilRequired] — the accurate "forge your sigil first"
     *    state the sheet already renders. Re-forging takes the first branch back to a
     *    live wallet. Skipped when already None/SigilRequired (fresh install's initial
     *    null isn't a sign-out).
     */
    private fun observeSigilLifecycle() {
        viewModelScope.launch {
            sigilStateStore.snapshotFlow
                .collect { snapshot ->
                    when {
                        snapshot != null && _status.value is WalletStatus.SigilRequired -> {
                            Log.i(TAG, "Sigil became available — clearing SigilRequired and requesting retry")
                            _status.value = WalletStatus.None
                            // Capture into a local so the smart cast survives the emit
                            // (lastRequestedConfig is mutable — Kotlin can't prove
                            // non-null past the suspend point otherwise).
                            val retryConfig = lastRequestedConfig
                            if (retryConfig != null) {
                                _retryRequests.emit(retryConfig)
                            }
                        }
                        snapshot == null &&
                            _status.value !is WalletStatus.None &&
                            _status.value !is WalletStatus.SigilRequired -> {
                            Log.i(TAG, "Sigil cleared (signed out) — tearing the wallet down to SigilRequired")
                            observeBalanceJob?.cancel()
                            observedWalletAddress = null
                            _status.value = WalletStatus.SigilRequired
                        }
                    }
                }
        }
    }

    /**
     * Bootstrap (if needed) and refresh balances. Progressive: emits Ready as
     * soon as the SDK is built so the user sees their addresses immediately,
     * then re-emits Ready after the full resync lands so the values catch up.
     *
     * **Why two emissions:**
     *  - Addresses are deterministic from the seed and available the moment
     *    `MidnightSdk.Builder.build()` returns — no network round-trip needed.
     *  - The shielded NIGHT resync replays every zswap event the wallet has
     *    seen; on PREPROD/PREVIEW that's potentially thousands of events and
     *    takes seconds. Forcing the user to stare at "Reading balance..." for
     *    that whole window — when the addresses they need are already
     *    derivable — is the bad UX `wallet-cli` already avoids by showing
     *    unshielded first.
     *
     * The intermediate Ready carries a `busy = "Syncing balances…"` so the UI
     * can show a subtle "values are catching up" indicator without blocking
     * the rest of the screen.
     *
     * Triggers seed unlock (biometric) on first call per session; subsequent
     * calls reuse the existing SDK as long as every field of [config] matches
     * what the SDK was built with.
     *
     * @param force bypass the heavy-resync throttle and run [com.midnight.kuira.sdk.MidnightWallet.refresh]
     *   (shielded + dust delta) now. The sheet's ⟳ button passes this — it's a "sync now", not a wipe.
     * @param resyncUnshielded ALSO rebuild the unshielded UTXO cache from genesis via
     *   [com.midnight.kuira.sdk.MidnightWallet.forceResyncUnshielded] — the deliberate "Re-sync
     *   balance" recovery for a stale NIGHT count (ghost AVAILABLE coins from a missed spent-event /
     *   cross-app spend, roadmap #52). UTXO-cache only (never touches dust/shielded); the balance
     *   momentarily drops toward 0 then rebuilds from the indexer. Routine refresh leaves this false.
     */
    fun refreshBalance(
        config: WalletConfig,
        activity: FragmentActivity,
        force: Boolean = false,
        resyncUnshielded: Boolean = false,
    ) {
        lastRequestedConfig = config
        viewModelScope.launch {
            // While the session is locked, the SessionLockGate owns re-auth — it
            // runs the single biometric (ensureSeedReady). Don't ALSO bootstrap
            // here: a second concurrent ensureSeedReady contends with the gate's on
            // bootstrapMutex and neither biometric shows (the gate spinner hangs).
            // After unlock, observeSessionLock emits a retry that re-runs this with
            // the auth window already open (silent). Status stays Locked meanwhile.
            if (sessionLock.locked.value) return@launch

            // Hold off the session auto-lock while bootstrapping/syncing so a
            // backgrounded refresh isn't torn down mid-flight (#235 fix). The
            // finally releases it on normal completion OR cancellation.
            val hold = sessionLock.acquireHold()
            try {
            // Don't overwrite the Ready state on a refresh — that would flash
            // the sheet through Loading and lose the in-screen address. Only
            // show Loading when we're truly bootstrapping from None / Error.
            if (_status.value !is WalletStatus.Ready) {
                val label = if (_status.value is WalletStatus.Locked) "Unlocking…" else "Bootstrapping wallet…"
                _status.value = WalletStatus.Loading(label)
            }
            try {
                val built = sdkProvider.ensureSdk(activity, config)
                // Re-apply the persisted backup opt-out to this (possibly freshly built)
                // wallet so a disabled user never silently resumes uploading after a rebuild —
                // BOTH the dust checkpoint and the app-state blob (#246).
                built.wallet.dustBackupEnabled = !_dustOptedOut.value
                built.wallet.appStateBackupEnabled = !_dustOptedOut.value
                // Connect the host's app-state source so refresh() actually backs
                // it up (null host provider → lane stays "None yet", e.g. BBoard).
                built.wallet.appStateProvider = appStateSnapshot
                // A different wallet (first bootstrap / network switch) always
                // re-arms the observer and forces a fresh resync.
                val walletChanged = observedWalletAddress != built.walletAddress

                // Phase 1 — addresses + cached balance, instant.
                val initial = built.wallet.balance()
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
                    shieldedAddress = built.shieldedWalletAddress,
                    balance = initial,
                )
                Log.i(TAG, "bootstrap: addresses ready (unshielded=${built.walletAddress.take(40)}…)")

                // Live observer — balanceFlow pushes incoming funds (airdrop /
                // incoming tx) and shielded changes automatically, so the balance
                // stays current WITHOUT a heavy resync. Armed once per wallet.
                if (observeBalanceJob?.isActive != true || walletChanged) {
                    observeBalanceJob?.cancel()
                    observedWalletAddress = built.walletAddress
                    observeBalanceJob = viewModelScope.launch {
                        built.wallet.balanceFlow().collect { live ->
                            (_status.value as? WalletStatus.Ready)?.let { ready ->
                                _status.value = ready.copy(balance = live)
                            }
                        }
                    }
                }

                // Phase 2 — heavy zswap + Dust resync, THROTTLED. The observer
                // keeps the balance live between syncs, so the expensive resync
                // only runs on a forced/explicit refresh, a wallet change, or once
                // per FULL_REFRESH_INTERVAL_MS — not on every menu visit. An explicit
                // unshielded re-sync is always a deliberate "sync now", so it bypasses
                // the throttle too.
                val now = System.currentTimeMillis()
                if (force || resyncUnshielded || walletChanged || now - lastFullRefreshAtMs >= FULL_REFRESH_INTERVAL_MS) {
                    // Deliberate "Re-sync balance": rebuild the unshielded UTXO cache from
                    // genesis FIRST, so the heavy refresh below and the post-refresh balance
                    // read see the corrected set. UTXO-cache only — the dust checkpoint stays
                    // intact (no PreProd-fatal dust genesis replay). The live balance observer
                    // shows the count drop toward 0 then climb back as the indexer replays.
                    if (resyncUnshielded) {
                        Log.i(TAG, "refreshBalance: force-resyncing the unshielded UTXO cache from genesis (#52)")
                        built.wallet.forceResyncUnshielded()
                    }
                    // The in-sheet indicator is driven reactively by [syncProgress]
                    // (← wallet.syncStatus), so this no longer choreographs progress
                    // labels by hand — syncDust() and refresh() publish their own
                    // phases (dust → shielded → genesis) to syncStatus, which both
                    // this pill and the background notification render identically.
                    // They run sequentially (never concurrently) so they don't both
                    // touch the shared DustLocalState (see #228).
                    built.wallet.syncDust()
                    runCatching { built.wallet.refresh() }
                        .onFailure { Log.w(TAG, "wallet.refresh failed (showing cached): ${it.message}") }
                    lastFullRefreshAtMs = now
                    val fresh = built.wallet.balance()
                    _status.value = WalletStatus.Ready(
                        address = built.walletAddress,
                        shieldedAddress = built.shieldedWalletAddress,
                        balance = fresh,
                    )
                    Log.i(
                        TAG,
                        "balance: unshieldedNight=${fresh.unshieldedNight} " +
                            "shieldedNight=${fresh.shieldedNight} " +
                            "dust=${fresh.dust} registered=${fresh.dustRegistered}",
                    )
                } else {
                    Log.i(TAG, "refreshBalance: resync throttled (${(now - lastFullRefreshAtMs) / 1000}s since last) — live observer keeps balance current")
                }
            } catch (e: SigilRequiredException) {
                // Bootstrap blocked because no passkey is forged. Host UI
                // observes SigilRequired and renders a "forge sigil first"
                // affordance; once the user forges, the next refresh
                // succeeds.
                Log.i(TAG, "refreshBalance gated on sigil — emitting SigilRequired")
                _status.value = WalletStatus.SigilRequired
            } catch (e: AuthenticationCancelledException) {
                // User dismissed the unlock biometric — stay Locked (the seed
                // force-reauth flag is still set), not Error. If we weren't locked
                // (e.g. a first bootstrap they cancelled), fall back to None so the
                // sheet stays actionable rather than red.
                Log.i(TAG, "refreshBalance: biometric cancelled — ${if (sessionLock.locked.value) "staying Locked" else "back to None"}")
                _status.value = if (sessionLock.locked.value) WalletStatus.Locked else WalletStatus.None
            } catch (e: CancellationException) {
                // Benign: this refresh was superseded — a network/config switch or
                // session lock closed the SDK out from under it, cancelling its
                // scope. The new config's refresh shows the real result, so DON'T
                // paint "Job was cancelled" as an error; let cancellation propagate.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "refreshBalance failed", e)
                _status.value = WalletStatus.Error(e.message ?: "Balance read failed")
            }
            } finally {
                hold.close()
            }
        }
    }

    /**
     * Deliberate "Re-sync balance" recovery: rebuild the unshielded UTXO cache from genesis to fix a
     * stale NIGHT count (ghost AVAILABLE coins left by a missed spent-event / cross-app spend on the
     * shared wallet, roadmap #52). Reuses [lastRequestedConfig] — the config the user last acted on —
     * so the Settings affordance can fire this without re-threading the config through the overlay.
     * No-op if no wallet action has run yet (nothing to resync, and no config to bootstrap with).
     *
     * Delegates to [refreshBalance] with `resyncUnshielded = true`, so the existing busy state +
     * [syncProgress] indicator cover the rebuild and the live observer shows the count self-correct.
     */
    fun forceResyncBalance(activity: FragmentActivity) {
        val config = lastRequestedConfig ?: run {
            Log.i(TAG, "forceResyncBalance: no prior config — nothing to re-sync")
            return
        }
        refreshBalance(config, activity, force = true, resyncUnshielded = true)
    }

    /**
     * Register the wallet's NIGHT key for Dust generation, then poll until the
     * first Dust UTXO surfaces (or [DUST_VISIBLE_TIMEOUT_MS] elapses). Must run
     * once after the wallet first holds NIGHT — until then the chain won't
     * release spendable Dust and contract calls (fee-paying) fail.
     */
    fun registerDust(config: WalletConfig, activity: FragmentActivity) {
        lastRequestedConfig = config
        viewModelScope.launch {
            try {
                val built = sdkProvider.ensureSdk(activity, config)
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
                    shieldedAddress = built.shieldedWalletAddress,
                    balance = built.wallet.balance(),
                    busy = "Registering for dust generation…",
                )
                val result = built.registerForDustGeneration()
                Log.i(TAG, "registerDust result: $result")

                val ok = result is TransactionSubmitter.SubmissionResult.Success ||
                    result is TransactionSubmitter.SubmissionResult.Pending
                if (!ok) {
                    val reason = when (result) {
                        is TransactionSubmitter.SubmissionResult.Failed -> result.reason
                        is TransactionSubmitter.SubmissionResult.StaleUtxo -> "stale UTXO"
                        is TransactionSubmitter.SubmissionResult.Success,
                        is TransactionSubmitter.SubmissionResult.Pending -> "unexpected: $result"
                    }
                    _status.value = WalletStatus.Ready(
                        address = built.walletAddress,
                        shieldedAddress = built.shieldedWalletAddress,
                        balance = built.wallet.balance(),
                        message = "Registration failed: $reason",
                    )
                    return@launch
                }

                // Registration accepted — dust generates from the NEXT block. Poll
                // so the sheet shows dust climb instead of a stale "dust 0".
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
                    shieldedAddress = built.shieldedWalletAddress,
                    balance = built.wallet.balance(),
                    busy = "Waiting for first dust generation…",
                )
                val deadline = System.currentTimeMillis() + DUST_VISIBLE_TIMEOUT_MS
                var latest = built.wallet.balance()
                while (latest.dust == BigInteger.ZERO && System.currentTimeMillis() < deadline) {
                    delay(DUST_POLL_INTERVAL_MS)
                    runCatching { built.wallet.refresh() }
                        .onFailure { Log.w(TAG, "wallet.refresh failed during poll: ${it.message}") }
                    latest = built.wallet.balance()
                    _status.value = WalletStatus.Ready(
                        address = built.walletAddress,
                        shieldedAddress = built.shieldedWalletAddress,
                        balance = latest,
                        busy = if (latest.dust == BigInteger.ZERO) "Waiting for first dust generation…" else null,
                    )
                }
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
                    shieldedAddress = built.shieldedWalletAddress,
                    balance = latest,
                    message = if (latest.dust > BigInteger.ZERO) "✓ Registered"
                    else "Registered — dust still propagating, tap balance again in a moment.",
                )
            } catch (e: SigilRequiredException) {
                Log.i(TAG, "registerDust gated on sigil — emitting SigilRequired")
                _status.value = WalletStatus.SigilRequired
            } catch (e: Exception) {
                Log.e(TAG, "registerDust failed", e)
                _status.value = WalletStatus.Error(e.message ?: "Dust registration failed")
            }
        }
    }

    /**
     * Send unshielded NIGHT to [toAddress] (#240). Bootstraps/reuses the shared SDK,
     * then calls [MidnightSdk.sendNight], which validates, selects fewest coins,
     * auto-consolidates when needed, signs, pays dust fees, and submits. Surfaces
     * coarse progress (consolidating / submitting) and the typed outcome through
     * [sendState]; the live balance observer + a post-send refresh update the pill.
     *
     * @param amount NIGHT in the smallest unit (1 NIGHT = 1,000,000); the Send
     *   screen parses the user's decimal NIGHT into this.
     */
    fun sendNight(config: WalletConfig, activity: FragmentActivity, toAddress: String, amount: BigInteger) {
        lastRequestedConfig = config
        viewModelScope.launch {
            // Hold the auto-lock across the (interactive) bootstrap; the send itself is then
            // held by the operation registry (SessionLock.bindOperationHold), so we release
            // this one the moment the durable send is launched.
            val hold = sessionLock.acquireHold()
            try {
                _sendState.value = SendUiState.Sending("Preparing…")
                val built = try {
                    sdkProvider.ensureSdk(activity, config)
                } catch (e: SigilRequiredException) {
                    _sendState.value = SendUiState.Failure("Sign in to your sigil first, then try again.")
                    return@launch
                } catch (e: AuthenticationCancelledException) {
                    _sendState.value = SendUiState.Idle // user dismissed the unlock biometric
                    return@launch
                } catch (e: CancellationException) {
                    // A network/config switch or session lock cancelled the scope mid-bootstrap.
                    // Don't paint "Job was cancelled" as a Failure — let cancellation propagate
                    // (mirrors refreshBalance; the generic catch below must not swallow it).
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "send bootstrap failed", e)
                    _sendState.value = SendUiState.Failure(e.message ?: "Send failed")
                    return@launch
                }
                // Fire-and-forget on the SDK scope so the transfer survives the user leaving
                // the app (#263). The registry / foreground service / finalization push own
                // the durable lifecycle; this VM only projects progress + the result while the
                // Send screen is open (callbacks land on the SDK scope, so a dead VM is a no-op).
                built.launchSendNight(
                    toAddress = toAddress,
                    amount = amount,
                    onProgress = { stage ->
                        _sendState.value = SendUiState.Sending(
                            when (stage) {
                                MidnightSdk.SendProgress.CONSOLIDATING -> "Consolidating coins…"
                                MidnightSdk.SendProgress.SUBMITTING -> "Submitting transaction…"
                            },
                        )
                    },
                    onResult = { result ->
                        Log.i(TAG, "sendNight result: $result")
                        _sendState.value = result.toSendUiState()
                    },
                )
                // Don't release the bootstrap hold until the durable op is observably enrolled, so
                // SessionLock.bindOperationHold has taken over: otherwise a hard auto-lock landing
                // in the gap between launch and the (async) enrollment could close the SDK out from
                // under the just-launched send. Bounded so a never-enrolling launch can't hang us.
                withTimeoutOrNull(SEND_HOLD_HANDOFF_TIMEOUT_MS) {
                    built.operations.active.first { it.isNotEmpty() }
                }
            } finally {
                hold.close()
            }
        }
    }

    /** Map a typed [MidnightSdk.SendResult] onto the pill's send UI state. */
    private fun MidnightSdk.SendResult.toSendUiState(): SendUiState = when (this) {
        is MidnightSdk.SendResult.Success -> SendUiState.Success(txHash)
        // Submitted; finalization confirmation timed out — treat as sent.
        is MidnightSdk.SendResult.Pending -> SendUiState.Success(txHash)
        is MidnightSdk.SendResult.InvalidAddress -> SendUiState.Failure(reason)
        is MidnightSdk.SendResult.InsufficientFunds -> {
            val short = shortfall.toBigDecimal().movePointLeft(NIGHT_DECIMALS)
                .stripTrailingZeros().toPlainString()
            SendUiState.Failure("Not enough NIGHT — you're short $short.")
        }
        is MidnightSdk.SendResult.Failed -> SendUiState.Failure(reason)
    }

    /** Reset the send flow to Idle (on Send-screen close, or to re-edit after a failure). */
    fun resetSendState() {
        _sendState.value = SendUiState.Idle
    }

    // ── Sovereign recovery phrase (#252) ────────────────────────────────────────
    //
    // The panel is one consumer of the SDK's [com.midnight.kuira.sdk.walletseed.WalletRecovery]
    // contract; everything here just projects that contract into UI state. A dApp building its own
    // recovery screens can skip this VM and use the contract directly (inject it / read it off
    // MidnightSdkProvider.recovery).

    /** Whether the user has saved their phrase — drives the backup-status chip. */
    val recoveryPhraseSaved: StateFlow<Boolean> = sdkProvider.recovery.isPhraseSaved

    private val _revealedPhrase = MutableStateFlow<List<String>?>(null)

    /** The revealed 24 words while the reveal screen is open; null otherwise. Cleared on exit. */
    val revealedPhrase: StateFlow<List<String>?> = _revealedPhrase

    private val _recoveryError = MutableStateFlow<String?>(null)
    val recoveryError: StateFlow<String?> = _recoveryError

    private val _restoreState = MutableStateFlow<RestoreUiState>(RestoreUiState.Idle)
    val restoreState: StateFlow<RestoreUiState> = _restoreState

    /** Reveal the recovery phrase (biometric-gated). Result lands in [revealedPhrase]. */
    fun revealRecoveryPhrase(activity: FragmentActivity) {
        viewModelScope.launch {
            _recoveryError.value = null
            try {
                _revealedPhrase.value = sdkProvider.recovery.revealPhrase(activity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationCancelledException) {
                // User dismissed the biometric — no error surface, just stay on the consent step.
            } catch (e: Exception) {
                Log.w(TAG, "reveal recovery phrase failed: ${e.message}")
                _recoveryError.value = e.message ?: "Couldn't reveal the recovery phrase"
            }
        }
    }

    /** Drop the in-memory revealed words (call when the reveal screen closes). */
    fun clearRevealedPhrase() {
        _revealedPhrase.value = null
        _recoveryError.value = null
    }

    /** Record that the user has saved their phrase (their "I've written it down" confirm). */
    fun markRecoveryPhraseSaved() = sdkProvider.recovery.markPhraseSaved()

    /** Validate a phrase without restoring — for live input feedback in the restore screen. */
    fun isValidRecoveryPhrase(words: List<String>): Boolean =
        sdkProvider.recovery.isValidPhrase(words)

    /** Restore a wallet from a 24-word phrase. Result lands in [restoreState]. */
    fun restoreFromPhrase(activity: FragmentActivity, words: List<String>) {
        viewModelScope.launch {
            _restoreState.value = RestoreUiState.Restoring
            try {
                sdkProvider.recovery.restoreFromPhrase(activity, words)
                _restoreState.value = RestoreUiState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationCancelledException) {
                _restoreState.value = RestoreUiState.Idle // cancelled biometric — let them retry
            } catch (e: Exception) {
                Log.w(TAG, "restore from phrase failed: ${e.message}")
                _restoreState.value = RestoreUiState.Error(e.message ?: "Restore failed")
            }
        }
    }

    /** Reset the restore flow to Idle (on screen close / re-edit after an error). */
    fun resetRestoreState() {
        _restoreState.value = RestoreUiState.Idle
    }

    /**
     * Two-way dust-backup toggle from the pill's Switch.
     *  - on  → clear the opt-out + enable on the live wallet, then run the Drive
     *          consent + sync ([enableCloudBackup]).
     *  - off → opt out (persisted): flip the live wallet so [com.midnight.kuira.sdk.MidnightWallet.refresh]
     *          stops uploading, and reflect "off" immediately. "No longer uploading"
     *          is the disable — no consent revoke.
     */
    fun setDustBackup(enabled: Boolean, config: WalletConfig, activity: FragmentActivity) {
        _dustOptedOut.value = !enabled
        // Turning OFF is durable immediately; turning ON only commits [_dustBackupEnabled] once the
        // enable+sync actually succeeds (see [cloudSyncNow]) — until then [_dustEnabling] drives the
        // optimistic on+spinner, and a failed enable falls back to off.
        if (!enabled) _dustBackupEnabled.value = false
        val edit = backupPrefs.edit().putBoolean(KEY_DUST_OPTED_OUT, !enabled)
        if (!enabled) edit.putBoolean(KEY_DUST_BACKUP_ENABLED, false)
        edit.apply()
        sdkProvider.sdk.value?.wallet?.let { it.dustBackupEnabled = enabled }
        if (enabled) {
            enableCloudBackup(config, activity)
        } else {
            Log.i(TAG, "dust backup disabled (opted out — no further uploads)")
        }
    }

    /**
     * TRUE backup disable (#246): delete BOTH cloud blobs (dust + app-state), then revoke the Drive
     * grant, and opt out locally. Order matters — delete the blobs (which need Drive access) BEFORE
     * revoking consent. The wallet + sigil are unaffected (recovery is the passkey); this only drops
     * the cloud copies. The host confirms first via the BackupSection dialog.
     */
    fun disableBackup(config: WalletConfig, activity: FragmentActivity) {
        viewModelScope.launch {
            val built = try {
                sdkProvider.ensureSdk(activity, config)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthenticationCancelledException) {
                // User dismissed the unlock — nothing was deleted/revoked, so DON'T flip the lane
                // to "off" (that would falsely claim backup is disabled). Leave state unchanged.
                Log.i(TAG, "disableBackup: unlock cancelled — backup state unchanged")
                return@launch
            } catch (e: Exception) {
                // No sigil / bootstrap failure → can't reach the wallet to delete the blobs; abort
                // without claiming "disabled".
                Log.w(TAG, "disableBackup: SDK unavailable — aborting", e)
                return@launch
            }
            // Each leg is INDEPENDENT — a failure (e.g. a never-granted Drive token throwing on the
            // dust delete) must not skip the other. Delete both cloud blobs so the user's data is
            // gone from Drive.
            //
            // We deliberately DON'T revoke the Drive grant here. Revoking tears down the Play
            // Services authorization, so re-enabling would have to re-authorize from scratch — which
            // right after a revoke hangs ~20s and fails with ApiException 22 ("connection suspended
            // during call"), stranding the toggle in the off state. Keeping the (now dormant) grant
            // makes re-enable instant: authorize() returns Authorized immediately, no re-consent. The
            // user's data is already deleted; anyone who wants to fully revoke the app's Drive access
            // can do so from their Google account.
            bestEffortDisable("dust blob delete") { built.wallet.disableDustCloudBackup() }
            bestEffortDisable("app-state blob delete") { built.wallet.disableAppStateCloudBackup() }
            // Opt out locally (re-applied on every rebuild to gate BOTH blobs — see ensureSdk above).
            _dustOptedOut.value = true
            _dustBackupEnabled.value = false
            backupPrefs.edit()
                .putBoolean(KEY_DUST_OPTED_OUT, true)
                .putBoolean(KEY_DUST_BACKUP_ENABLED, false)
                .apply()
            Log.i(TAG, "disableBackup: deleted both cloud blobs, opted out (Drive grant kept for instant re-enable)")
        }
    }

    /** Run one disable leg, isolating its failure so the remaining legs still execute (#246). */
    private suspend fun bestEffortDisable(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "disableBackup: $label failed (continuing)", e)
        }
    }

    /**
     * Enable cross-device Dust cloud sync: obtain the Drive `drive.appdata`
     * grant, then run a full sync. This is **bidirectional** — once consent
     * exists, `wallet.refresh()` first restores from the cloud checkpoint if
     * this device has none (so a fresh device deltas instead of replaying
     * genesis), then uploads the latest checkpoint. If consent is already
     * granted it runs silently; otherwise it emits an [IntentSenderRequest]
     * via [consentRequests] for the panel to launch — the result returns
     * through [onConsentResult].
     */
    fun enableCloudBackup(config: WalletConfig, activity: FragmentActivity) {
        viewModelScope.launch {
            // Immediate optimistic feedback (switch flips on + spinner) before the
            // slow Drive round-trip reports anything.
            _dustEnabling.value = true
            Log.i(TAG, "enableCloudBackup: requesting Drive authorize()…")
            try {
                when (val outcome = driveAuth.authorize()) {
                    is AuthorizeOutcome.Authorized -> {
                        Log.i(TAG, "enableCloudBackup: Drive already authorized — syncing now")
                        cloudSyncNow(config, activity)
                        _dustEnabling.value = false
                    }
                    is AuthorizeOutcome.NeedsConsent -> {
                        // Consent UI launches; stay "enabling" until onConsentResult.
                        Log.i(TAG, "enableCloudBackup: Drive needs consent — launching grant UI")
                        _consentRequests.emit(IntentSenderRequest.Builder(outcome.intentSender).build())
                    }
                }
            } catch (e: Exception) {
                // The user-facing outcome surfaces on the dust lane via
                // wallet.backupStatus (NeedsConsent / Failed); just log here.
                Log.w(TAG, "enableCloudBackup failed", e)
                _dustEnabling.value = false
            }
        }
    }

    /** Continue after the Drive consent activity returns. */
    fun onConsentResult(config: WalletConfig, activity: FragmentActivity, data: Intent?) {
        viewModelScope.launch {
            try {
                // Confirms the grant; throws if the user dismissed consent.
                Log.i(TAG, "onConsentResult: confirming Drive grant…")
                driveAuth.tokenFromConsent(data)
                Log.i(TAG, "onConsentResult: grant confirmed — syncing to Drive")
                cloudSyncNow(config, activity)
            } catch (e: Exception) {
                Log.w(TAG, "Drive consent not completed", e)
            } finally {
                _dustEnabling.value = false
            }
        }
    }

    /**
     * Full bidirectional cloud sync, run once consent exists. [com.midnight.kuira.sdk.MidnightWallet.refresh]
     * restores from the Drive checkpoint when this device has no local one (cold
     * start / fresh device → delta instead of genesis), then uploads the latest
     * checkpoint (hash-guarded). So the single "cloud sync" action covers both
     * directions — back up on the device that has data, restore on the one that
     * doesn't.
     */
    private suspend fun cloudSyncNow(config: WalletConfig, activity: FragmentActivity) {
        Log.i(TAG, "cloudSyncNow: syncing + uploading dust checkpoint to Drive…")
        val built = sdkProvider.ensureSdk(activity, config)
        built.wallet.dustBackupEnabled = true  // enabling — make sure uploads are on
        built.wallet.refresh()
        // The enable+sync succeeded — commit the durable ON so the toggle stays on through later
        // background-sync status churn (a balance refresh must not flip it).
        _dustBackupEnabled.value = true
        backupPrefs.edit().putBoolean(KEY_DUST_BACKUP_ENABLED, true).apply()
        Log.i(TAG, "cloudSyncNow: Drive sync complete")
    }

    companion object {
        private const val TAG = "WalletPanel"

        /** Prefs key: true → user opted out of dust cloud backup. */
        private const val KEY_DUST_OPTED_OUT = "dust_backup_opted_out"

        /** Prefs key: true → user has dust cloud backup enabled (durable toggle position). */
        private const val KEY_DUST_BACKUP_ENABLED = "dust_backup_enabled"

        /** Upper bound on the post-registration Dust-visibility poll. */
        private const val DUST_VISIBLE_TIMEOUT_MS = 20_000L

        /** Bound on waiting for the fire-and-forget send to enroll before releasing the
         *  bootstrap session-hold (closes the lock-handoff gap without risking a hang). */
        private const val SEND_HOLD_HANDOFF_TIMEOUT_MS = 5_000L

        /** Cadence of the post-registration poll. */
        private const val DUST_POLL_INTERVAL_MS = 2_000L

        /** Throttle on the heavy zswap + Dust resync. Between syncs the live
         *  balanceFlow observer keeps the panel current, so the expensive resync
         *  only needs to run periodically (or on an explicit/forced refresh). */
        private const val FULL_REFRESH_INTERVAL_MS = 5 * 60_000L

        /** Keep the derived [syncProgress] flow warm briefly after the last
         *  collector leaves (config change / sheet close), so a sync in flight
         *  isn't dropped and re-collected. */
        private const val SYNC_SUBSCRIBE_TIMEOUT_MS = 5_000L
    }
}

/**
 * NIGHT has 6 decimals (1 NIGHT = 1,000,000 base units). Shared across the wallet
 * package — the VM (shortfall formatting) and the Send screen (amount parsing) both
 * reference it. Aliases the SDK's [com.midnight.kuira.sdk.NIGHT_DECIMALS] so the scale
 * has ONE source of truth (the SDK domain layer), not a re-hardcoded `6`.
 */
internal const val NIGHT_DECIMALS = com.midnight.kuira.sdk.NIGHT_DECIMALS

/**
 * Send-flow UI state for the Send screen (#240).
 */
sealed interface SendUiState {
    /** No send in progress — the recipient/amount form is editable. */
    data object Idle : SendUiState

    /** A send is running; [stage] is the coarse step (e.g. "Submitting transaction…"). */
    data class Sending(val stage: String) : SendUiState

    /** The transfer finalized (or was accepted and is finalizing); [txHash] may be empty. */
    data class Success(val txHash: String) : SendUiState

    /** The send failed; [message] is a user-facing reason. */
    data class Failure(val message: String) : SendUiState
}

/**
 * Wallet-sync progress for the sheet's [WalletSyncIndicator].
 * @param fraction 0f..1f when a count is known (determinate); null for the
 *   unmeasurable tail (e.g. Rust replay) — render as indeterminate.
 * @param label Stage / detail text, e.g. "Syncing dust".
 */
data class WalletSyncProgress(val fraction: Float?, val label: String)

/**
 * Pure derivation of the dust-backup **Switch** state — extracted so it's unit-testable, and so the
 * Switch reflects the user's DURABLE choice rather than the live sync status.
 *
 * - [enabling] (the user just tapped to enable) → on + spinner. This is the ONLY busy state, so a
 *   background/refresh-triggered sync never spins the Switch.
 * - else [enabled] (the persisted choice) → on, stable across refreshes: when the live [status]
 *   churns UpToDate→Syncing→Idle during a balance refresh, the Switch does NOT off→on snap. A
 *   persistent [status] failure surfaces as a quiet detail — no spinner, no off-flip.
 * - else → off.
 *
 * The live [status] deliberately does NOT decide on/off or busy; it only contributes a failure detail.
 */
internal fun dustToggleLane(
    enabled: Boolean,
    enabling: Boolean,
    status: CloudBackupStatus,
): BackupLaneState = when {
    enabling -> BackupLaneState.Toggle(on = true, busy = true)
    enabled -> BackupLaneState.Toggle(
        on = true,
        detail = (status as? CloudBackupStatus.Failed)?.let { friendlyBackupError(it.message) },
    )
    else -> BackupLaneState.Toggle(on = false)
}

/**
 * Maps the **app-data** lane's [CloudBackupStatus]. App-data backup is
 * **automatic** (Block Store, no consent), so there's nothing to "enable":
 * [CloudBackupStatus.Idle] is an informational empty state ("None yet"), not a
 * CTA. The lane is **always shown** — even empty — so users discover the
 * capability instead of it silently appearing only after the first save.
 */
private fun CloudBackupStatus.toAppDataLane(): BackupLaneState = when (this) {
    CloudBackupStatus.Idle -> BackupLaneState.Ok("None yet")
    CloudBackupStatus.Syncing -> BackupLaneState.Syncing(progress = null)
    is CloudBackupStatus.UpToDate -> BackupLaneState.Ok("On", confirm = true)
    // Transient offline blip — keep the lane calm ("On"), no red; retries next sync.
    CloudBackupStatus.Offline -> BackupLaneState.Ok("On", confirm = true)
    // Block Store needs no consent — NeedsConsent shouldn't occur here, but map
    // it defensively rather than crash on a future status.
    CloudBackupStatus.NeedsConsent -> BackupLaneState.Action("Off", "Enable")
    is CloudBackupStatus.Failed ->
        BackupLaneState.Action("Failed", "Retry", danger = true, detail = friendlyBackupError(message))
}

/**
 * Map a Drive/auth failure to an actionable message. The most common one in a
 * fresh setup is the app's OAuth client not registered in a Google Cloud project
 * (UNREGISTERED_ON_API_CONSOLE) — a one-time console setup, not a user error — so
 * we say so rather than echoing the raw GMS code.
 */
private fun friendlyBackupError(raw: String?): String {
    val msg = raw.orEmpty()
    return when {
        msg.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ->
            "Drive not set up for this app — register its OAuth client (package + SHA-1) in Google Cloud Console."
        // Network/DNS should normally surface as CloudBackupStatus.Offline (no error),
        // but translate it here too in case it reaches Failed via another path.
        msg.contains("resolve host", ignoreCase = true) ||
            msg.contains("No address associated", ignoreCase = true) ||
            msg.contains("Unable to resolve", ignoreCase = true) ->
            "Offline — will sync when you're back online."
        msg.contains("cancel", ignoreCase = true) -> "Cloud sync cancelled."
        else -> msg.ifBlank { "Cloud sync failed" }
    }
}