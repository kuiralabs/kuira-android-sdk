package com.midnight.kuira.dapp.wallet

import android.content.Intent
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.identity.backup.AuthorizeOutcome
import com.midnight.kuira.core.identity.backup.DriveAuthManager
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import com.midnight.kuira.sdk.walletruntime.WalletConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
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
) : ViewModel() {

    private val _status = MutableStateFlow<WalletStatus>(WalletStatus.None)
    val status: StateFlow<WalletStatus> = _status

    /** UI feedback for the "enable cloud backup" affordance. */
    private val _backupStatus = MutableStateFlow<DustBackupUiState>(DustBackupUiState.Idle)
    val backupStatus: StateFlow<DustBackupUiState> = _backupStatus

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
                _status.value = WalletStatus.Loading("Bootstrapping wallet…")
            }
            try {
                val built = sdkProvider.ensureSdk(activity, config)
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

                // Phase 2 — heavy zswap + dust resync, THROTTLED. The observer
                // keeps the balance live between syncs, so the expensive resync
                // only runs on a forced/explicit refresh, a wallet change, or once
                // per FULL_REFRESH_INTERVAL_MS — not on every menu visit.
                val now = System.currentTimeMillis()
                if (force || walletChanged || now - lastFullRefreshAtMs >= FULL_REFRESH_INTERVAL_MS) {
                    (_status.value as? WalletStatus.Ready)?.let {
                        _status.value = it.copy(busy = "Syncing balances…")
                    }
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
            } catch (e: Exception) {
                Log.e(TAG, "refreshBalance failed", e)
                _status.value = WalletStatus.Error(e.message ?: "Balance read failed")
            }
        }
    }

    /**
     * Register the wallet's NIGHT key for dust generation, then poll until the
     * first dust UTXO surfaces (or [DUST_VISIBLE_TIMEOUT_MS] elapses). Must run
     * once after the wallet first holds NIGHT — until then the chain won't
     * release spendable dust and contract calls (fee-paying) fail.
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
     * Enable cross-device dust cloud sync: obtain the Drive `drive.appdata`
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
            _backupStatus.value = DustBackupUiState.Working
            try {
                when (val outcome = driveAuth.authorize()) {
                    is AuthorizeOutcome.Authorized -> cloudSyncNow(config, activity)
                    is AuthorizeOutcome.NeedsConsent ->
                        _consentRequests.emit(
                            IntentSenderRequest.Builder(outcome.intentSender).build(),
                        )
                }
            } catch (e: Exception) {
                Log.w(TAG, "enableCloudBackup failed", e)
                _backupStatus.value = DustBackupUiState.Failed(backupErrorMessage(e))
            }
        }
    }

    /** Continue after the Drive consent activity returns. */
    fun onConsentResult(config: WalletConfig, activity: FragmentActivity, data: Intent?) {
        viewModelScope.launch {
            try {
                // Confirms the grant; throws if the user dismissed consent.
                driveAuth.tokenFromConsent(data)
                cloudSyncNow(config, activity)
            } catch (e: Exception) {
                Log.w(TAG, "Drive consent not completed", e)
                _backupStatus.value = DustBackupUiState.Failed(backupErrorMessage(e))
            }
        }
    }

    /**
     * Map a Drive/auth failure to an actionable message. The most common one in
     * a fresh setup is the app's OAuth client not being registered in a Google
     * Cloud project (status UNREGISTERED_ON_API_CONSOLE) — which is a one-time
     * console setup, not a user error — so we say so rather than echoing the raw
     * GMS code or a misleading "consent cancelled".
     */
    private fun backupErrorMessage(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when {
            raw.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ->
                "Drive not set up for this app — register its OAuth client (package + SHA-1) in Google Cloud Console."
            raw.contains("cancel", ignoreCase = true) ->
                "Cloud sync cancelled."
            else -> raw.ifBlank { "Cloud sync failed" }
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
        val built = sdkProvider.ensureSdk(activity, config)
        built.wallet.refresh()
        _backupStatus.value = DustBackupUiState.Enabled
    }

    companion object {
        private const val TAG = "WalletPanel"

        /** Upper bound on the post-registration dust-visibility poll. */
        private const val DUST_VISIBLE_TIMEOUT_MS = 20_000L

        /** Cadence of the post-registration poll. */
        private const val DUST_POLL_INTERVAL_MS = 2_000L

        /** Throttle on the heavy zswap + dust resync. Between syncs the live
         *  balanceFlow observer keeps the panel current, so the expensive resync
         *  only needs to run periodically (or on an explicit/forced refresh). */
        private const val FULL_REFRESH_INTERVAL_MS = 5 * 60_000L
    }
}

/** UI state for the dust cloud-backup affordance. */
sealed interface DustBackupUiState {
    data object Idle : DustBackupUiState
    data object Working : DustBackupUiState
    data object Enabled : DustBackupUiState
    data class Failed(val message: String) : DustBackupUiState
}
