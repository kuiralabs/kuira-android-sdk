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
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import com.midnight.kuira.sdk.walletruntime.SessionLock
import com.midnight.kuira.sdk.walletruntime.WalletConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    @ApplicationContext appContext: Context,
    /**
     * Host-bound app-state source (empty when the host binds none, e.g. BBoard).
     * Connected to [com.midnight.kuira.sdk.MidnightWallet.appStateProvider] so the
     * pill's App-data lane actually backs up — #244's silent path wired its
     * uploader but never its source.
     */
    private val appDataProvider: Optional<AppDataBackupProvider>,
) : ViewModel() {

    /** Adapts the bound provider to the wallet's `appStateProvider` lambda type. */
    private val appStateSnapshot: (suspend () -> ByteArray?)? =
        appDataProvider.orElse(null)?.let { provider -> { provider.snapshot() } }

    /** Persists the dust-backup opt-out across launches + SDK rebuilds. */
    private val backupPrefs = appContext.getSharedPreferences("kuira_dust_backup", Context.MODE_PRIVATE)

    /** True → the user disabled dust cloud backup (we stop uploading). */
    private val _dustOptedOut = MutableStateFlow(backupPrefs.getBoolean(KEY_DUST_OPTED_OUT, false))

    private val _status = MutableStateFlow<WalletStatus>(WalletStatus.None)
    val status: StateFlow<WalletStatus> = _status

    /**
     * Live dust-sync progress for the sheet's [WalletSyncIndicator]. Non-null
     * only while a heavy resync is streaming events; carries a 0..1 fraction
     * (determinate) or null fraction (the unmeasurable Rust-replay tail) plus a
     * stage label. Cleared back to null when the resync finishes. The pill's own
     * spinner still keys off [WalletStatus.Ready.busy]; this drives the richer
     * in-sheet indicator with real digits instead of an endless loop.
     */
    private val _syncProgress = MutableStateFlow<WalletSyncProgress?>(null)
    val syncProgress: StateFlow<WalletSyncProgress?> = _syncProgress

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
        _dustOptedOut,
    ) { backup, sigil, dustEnabling, optedOut ->
        BackupSectionState(
            identity = if (sigil != null) BackupLaneState.Ok("Protected", confirm = true)
            else BackupLaneState.Ok("Not set up"),
            // Opt-out wins (immediate "off"); else the optimistic pending wins
            // until the real status arrives (switch on + spinner, no dead re-tap);
            // else the wallet's real status.
            dust = when {
                optedOut -> BackupLaneState.Toggle(on = false)
                dustEnabling -> BackupLaneState.Toggle(on = true, busy = true)
                else -> backup.dust.toDustLane()
            },
            appData = backup.appData.toAppDataLane(),
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
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
        observeSigilForAutoRetry()
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
                    _syncProgress.value = null
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
     * Reactively unblock the wallet when the user signs in / forges
     * AFTER the wallet panel landed on [WalletStatus.SigilRequired].
     *
     * Flow: [SigilStateStore.snapshotFlow] emits the persisted sigil
     * triple (initially null on a fresh install, non-null after
     * `SigilSession.signIn` lands). When it transitions to non-null
     * AND our status is currently `SigilRequired`, we:
     *  1. Clear status to None so the "sigil required" body
     *     disappears from the sheet.
     *  2. Emit a one-shot `retryRequests` event so the host fires
     *     `refreshBalance` with the last config the user actually
     *     asked for.
     *
     * No-op when the user hasn't yet tried a wallet action — we don't
     * auto-trigger biometric prompts for users who only signed in to
     * the sigil panel. Action stays user-initiated; we just don't
     * leave a stale "sigil required" status hanging around.
     */
    private fun observeSigilForAutoRetry() {
        viewModelScope.launch {
            sigilStateStore.snapshotFlow
                .collect { snapshot ->
                    if (snapshot != null && _status.value is WalletStatus.SigilRequired) {
                        Log.i(TAG, "Sigil became available — clearing SigilRequired and requesting retry")
                        _status.value = WalletStatus.None
                        // Capture the snapshot value into a local so the
                        // smart cast survives the emit (lastRequestedConfig
                        // is a mutable property — Kotlin can't prove
                        // non-null past the suspend point otherwise).
                        val retryConfig = lastRequestedConfig
                        if (retryConfig != null) {
                            _retryRequests.emit(retryConfig)
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
     */
    fun refreshBalance(config: WalletConfig, activity: FragmentActivity, force: Boolean = false) {
        lastRequestedConfig = config
        viewModelScope.launch {
            // Don't overwrite the Ready state on a refresh — that would flash
            // the sheet through Loading and lose the in-screen address. Only
            // show Loading when we're truly bootstrapping from None / Error.
            if (_status.value !is WalletStatus.Ready) {
                val label = if (_status.value is WalletStatus.Locked) "Unlocking…" else "Bootstrapping wallet…"
                _status.value = WalletStatus.Loading(label)
            }
            try {
                val built = sdkProvider.ensureSdk(activity, config)
                // Re-apply the persisted dust-backup opt-out to this (possibly
                // freshly built) wallet so a disabled user never silently resumes
                // uploading after a rebuild.
                built.wallet.dustBackupEnabled = !_dustOptedOut.value
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
                // per FULL_REFRESH_INTERVAL_MS — not on every menu visit.
                val now = System.currentTimeMillis()
                if (force || walletChanged || now - lastFullRefreshAtMs >= FULL_REFRESH_INTERVAL_MS) {
                    (_status.value as? WalletStatus.Ready)?.let {
                        _status.value = it.copy(busy = "Syncing balances…")
                    }
                    try {
                        // Stage-based feedback: even when an individual step is too
                        // fast to emit a percentage (e.g. localnet), the label moves
                        // through the real phases so the user always sees it advance.
                        // Determinate % comes from syncDust's event counts when a
                        // cold/large stream actually happens. syncDust runs
                        // sequentially BEFORE refresh() — never concurrently — so the
                        // two don't both touch the shared DustLocalState (see #228).
                        // Parameterise the bar off the REAL milestones so the runner
                        // always advances — even when a step is too fast to emit its
                        // own % (localnet). The dust stream owns 0.10→0.55 (its true
                        // event-% mapped in when a real stream runs); the refresh
                        // (shielded + dust delta + backup) carries it 0.55→1.0.
                        _syncProgress.value = WalletSyncProgress(SYNC_DUST_START, "Syncing dust…")
                        built.wallet.syncDust { processed, total ->
                            _syncProgress.value = when {
                                processed < 0 -> WalletSyncProgress(SYNC_DUST_END, "Finalizing dust…")
                                total > 0 -> WalletSyncProgress(
                                    SYNC_DUST_START + (processed.toFloat() / total).coerceIn(0f, 1f) * SYNC_DUST_SPAN,
                                    "Syncing dust",
                                )
                                else -> WalletSyncProgress(SYNC_DUST_END, "Syncing dust…")
                            }
                        }
                        _syncProgress.value = WalletSyncProgress(SYNC_REFRESH, "Refreshing balances…")
                        runCatching { built.wallet.refresh() }
                            .onFailure { Log.w(TAG, "wallet.refresh failed (showing cached): ${it.message}") }
                        _syncProgress.value = WalletSyncProgress(SYNC_COMPLETE, "Up to date")
                    } finally {
                        _syncProgress.value = null
                    }
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
            } catch (e: Exception) {
                Log.e(TAG, "refreshBalance failed", e)
                _status.value = WalletStatus.Error(e.message ?: "Balance read failed")
            }
        }
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
     * Two-way dust-backup toggle from the pill's Switch.
     *  - on  → clear the opt-out + enable on the live wallet, then run the Drive
     *          consent + sync ([enableCloudBackup]).
     *  - off → opt out (persisted): flip the live wallet so [com.midnight.kuira.sdk.MidnightWallet.refresh]
     *          stops uploading, and reflect "off" immediately. "No longer uploading"
     *          is the disable — no consent revoke.
     */
    fun setDustBackup(enabled: Boolean, config: WalletConfig, activity: FragmentActivity) {
        _dustOptedOut.value = !enabled
        backupPrefs.edit().putBoolean(KEY_DUST_OPTED_OUT, !enabled).apply()
        sdkProvider.sdk.value?.wallet?.let { it.dustBackupEnabled = enabled }
        if (enabled) {
            enableCloudBackup(config, activity)
        } else {
            Log.i(TAG, "dust backup disabled (opted out — no further uploads)")
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
        Log.i(TAG, "cloudSyncNow: Drive sync complete")
    }

    companion object {
        private const val TAG = "WalletPanel"

        /** Prefs key: true → user opted out of dust cloud backup. */
        private const val KEY_DUST_OPTED_OUT = "dust_backup_opted_out"

        /** Upper bound on the post-registration Dust-visibility poll. */
        private const val DUST_VISIBLE_TIMEOUT_MS = 20_000L

        /** Cadence of the post-registration poll. */
        private const val DUST_POLL_INTERVAL_MS = 2_000L

        /** Throttle on the heavy zswap + Dust resync. Between syncs the live
         *  balanceFlow observer keeps the panel current, so the expensive resync
         *  only needs to run periodically (or on an explicit/forced refresh). */
        private const val FULL_REFRESH_INTERVAL_MS = 5 * 60_000L

        // ── Sync progress milestones ──
        // The runner's fraction at each real phase, so it always advances even
        // when a step emits no sub-progress of its own.
        /** Dust stream begins. */
        private const val SYNC_DUST_START = 0.10f
        /** Fraction the dust stream owns: it fills [SYNC_DUST_START] → [SYNC_DUST_END]. */
        private const val SYNC_DUST_SPAN = 0.45f
        /** Dust streaming done / finalizing. */
        private const val SYNC_DUST_END = SYNC_DUST_START + SYNC_DUST_SPAN
        /** During the refresh (shielded + dust-delta + backup). */
        private const val SYNC_REFRESH = 0.70f
        /** Fully synced. */
        private const val SYNC_COMPLETE = 1f
    }
}

/**
 * Wallet-sync progress for the sheet's [WalletSyncIndicator].
 * @param fraction 0f..1f when a count is known (determinate); null for the
 *   unmeasurable tail (e.g. Rust replay) — render as indeterminate.
 * @param label Stage / detail text, e.g. "Syncing dust".
 */
data class WalletSyncProgress(val fraction: Float?, val label: String)

/**
 * Maps the **dust** lane's [CloudBackupStatus]. Dust cloud sync is **opt-in** —
 * it needs the one-time Drive `drive.appdata` grant — so the pre-setup default
 * ([CloudBackupStatus.Idle]) and [CloudBackupStatus.NeedsConsent] both surface
 * the same "Off · Enable" call-to-action, not a passive "done" label. (A neutral
 * dash here read as "already on" — the exact confusion #243 removes.)
 */
private fun CloudBackupStatus.toDustLane(): BackupLaneState = when (this) {
    // Rendered as a Switch — unambiguous on/off (a label read as already-enabled).
    CloudBackupStatus.Idle -> BackupLaneState.Toggle(on = false)
    CloudBackupStatus.NeedsConsent -> BackupLaneState.Toggle(on = false)
    CloudBackupStatus.Syncing -> BackupLaneState.Toggle(on = true, busy = true)
    is CloudBackupStatus.UpToDate -> BackupLaneState.Toggle(on = true)
    is CloudBackupStatus.Failed -> BackupLaneState.Toggle(on = false, detail = friendlyBackupError(message))
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
        msg.contains("cancel", ignoreCase = true) -> "Cloud sync cancelled."
        else -> msg.ifBlank { "Cloud sync failed" }
    }
}