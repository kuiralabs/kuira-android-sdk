package com.midnight.kuira.dapp.wallet

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.walletseed.WalletSeedSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * Self-contained wallet bootstrap + lifecycle for example apps.
 *
 * Owns:
 *  - The [WalletConfig] toggles (network, proving mode, proof-server URL)
 *    and rebuilds the SDK whenever any of them changes.
 *  - SDK construction + lifetime — closes the SDK in [onCleared] so the
 *    indexer WebSocket doesn't outlive the host activity.
 *  - UI status translation — converts [SigilRequiredException] from the
 *    seed source into [WalletStatus.SigilRequired].
 *
 * Delegates to [WalletSeedSource] (sdk:wallet-seed) for the seed
 * bootstrap — passkey PRF derivation, SeedVault caching, legacy-seed
 * migration, dev-seed override. The same seed source is what
 * non-panel hosts (Kicks's `MatchManager`, future agent runtimes)
 * consume, so every Kuira ecosystem dApp lands on identical seed
 * behavior.
 *
 * **Public surface:** [status] (observe), [refreshBalance] /
 * [registerDust] (act). Funding doesn't need a panel-side handler —
 * the Receive screen shows the airdrop command, the SDK's
 * subscription picks up the credit on its own.
 *
 * **Proving keys:** the SDK's `ProvingKeyManager.ensureWalletKeysAvailable`
 * runs after [MidnightSdk.Builder.build] — `/data/local/tmp/` shortcut on
 * dev devices, ~24MB S3 download on a fresh emulator. No per-host wiring.
 */
@HiltViewModel
class WalletPanelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletSeedSource: WalletSeedSource,
    private val sigilStateStore: SigilStateStore,
) : ViewModel() {

    private val _status = MutableStateFlow<WalletStatus>(WalletStatus.None)
    val status: StateFlow<WalletStatus> = _status

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

    private var sdk: MidnightSdk? = null
    /**
     * Config the current [sdk] was built with. Null when no SDK exists yet.
     * A `null` here OR a `sdkConfig != requestedConfig` triggers a full
     * rebuild in [buildOrReuseSdk] — same handler covers initial bootstrap
     * AND any subsequent user toggle (network, proving mode, proof URL).
     *
     * Doubles as the "last known config" the auto-retry uses when the
     * sigil arrives: if the user previously tried `refreshBalance` and
     * got gated to SigilRequired, the [WalletConfig] they used is
     * captured here. The auto-retry signals via [retryRequests] and
     * the host calls back with the same config.
     */
    private var sdkConfig: WalletConfig? = null

    /**
     * Last [WalletConfig] passed into [refreshBalance] / [registerDust].
     * Kept independently of [sdkConfig] because [sdkConfig] only updates
     * after a successful SDK build — when bootstrap failed with
     * SigilRequired, [sdkConfig] is null but we still know what config
     * the user asked for. The retry needs that.
     */
    private var lastRequestedConfig: WalletConfig? = null

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
    fun refreshBalance(config: WalletConfig, activity: FragmentActivity) {
        lastRequestedConfig = config
        viewModelScope.launch {
            // Don't overwrite the Ready state on a refresh — that would flash
            // the sheet through Loading and lose the in-screen address. Only
            // show Loading when we're truly bootstrapping from None / Error.
            if (_status.value !is WalletStatus.Ready) {
                _status.value = WalletStatus.Loading("Bootstrapping wallet…")
            }
            try {
                val built = buildOrReuseSdk(config, activity)
                // Phase 1 — addresses up immediately. balance() is a cheap
                // read against already-populated state; whatever it returns
                // (often zero on a fresh wallet, or stale on a long-idle
                // wallet) is fine because we re-emit after refresh.
                val initial = built.wallet.balance()
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
                    shieldedAddress = built.shieldedWalletAddress,
                    balance = initial,
                    busy = "Syncing balances…",
                )
                Log.i(TAG, "bootstrap: addresses ready (unshielded=${built.walletAddress.take(40)}…)")

                // Phase 2 — full resync. On PREPROD this can take a few
                // seconds (zswap replay); on localnet it's near-instant.
                // Failures don't abort: the cached values from Phase 1 stay
                // visible and the user can hit balance again to retry.
                runCatching { built.wallet.refresh() }
                    .onFailure { Log.w(TAG, "wallet.refresh failed (showing cached): ${it.message}") }
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
                val built = buildOrReuseSdk(config, activity)
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

    override fun onCleared() {
        super.onCleared()
        sdk?.close()
        sdk = null
    }

    // ── Internal: SDK + seed plumbing ──

    private suspend fun buildOrReuseSdk(config: WalletConfig, activity: FragmentActivity): MidnightSdk {
        sdk?.let { existing ->
            // Full-config match → reuse. Any toggle (network / proving mode /
            // proof URL) forces a rebuild because each changes how the SDK
            // routes transactions or which chain it talks to.
            if (sdkConfig == config) return existing
            existing.close()
            sdk = null
        }
        val seed = walletSeedSource.ensureSeedReady(activity)
        return try {
            val built = MidnightSdk.Builder(context)
                .network(config.network)
                .seed(seed)
                .provingMode(config.provingMode)
                .also { builder ->
                    config.proofServerUrl?.let { builder.proofServerUrl(it) }
                }
                .build()
            sdk = built
            sdkConfig = config
            // Proving keys are network-agnostic — the same S3 bundle drives
            // local proving on UNDEPLOYED, PREPROD, and PREVIEW. The SDK
            // owns the recipe (local-tmp shortcut → S3 fallback) so a
            // fresh install on any device self-recovers without the
            // user having to know about adb push or local proving.
            built.provingKeyManager.ensureWalletKeysAvailable(
                logger = { Log.i(TAG, it) },
            )
            built
        } finally {
            // The SDK builder copies the seed internally; wipe our local view.
            seed.fill(0)
        }
    }

    companion object {
        private const val TAG = "WalletPanel"

        /** Upper bound on the post-registration dust-visibility poll. */
        private const val DUST_VISIBLE_TIMEOUT_MS = 20_000L

        /** Cadence of the post-registration poll. */
        private const val DUST_POLL_INTERVAL_MS = 2_000L
    }
}
