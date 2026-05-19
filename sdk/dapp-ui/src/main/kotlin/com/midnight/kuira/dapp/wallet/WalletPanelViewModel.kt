package com.midnight.kuira.dapp.wallet

import android.content.Context
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Self-contained wallet bootstrap + lifecycle for example apps.
 *
 * The panel's contract with the host app:
 *  - host owns nothing wallet-related — the panel manages its own
 *    [WalletConfig] (network, proving mode, proof-server URL) and rebuilds
 *    the SDK whenever the user changes any of them
 *  - first action triggers a biometric prompt to seal a freshly-generated BIP-39
 *    seed via [SeedVault]; subsequent runs reuse it transparently
 *  - the SDK is built lazily on first action, reused while [WalletConfig]
 *    matches what the in-memory SDK was built with, rebuilt on any mismatch
 *
 * **Public surface:** [status] (observe), [refreshBalance] / [registerDust]
 * (act). Funding doesn't need a panel-side handler — the Receive screen shows
 * the airdrop command, the SDK's subscription picks up the credit on its own.
 *
 * **Not for production:** [installProvingKeys] reads from `/data/local/tmp/`,
 * which is the same adb-push convention the SDK e2e tests use. Production
 * apps download keys at runtime via `ProvingKeyManager.downloadWalletKeys`.
 *
 * **Lifecycle:** the SDK is closed in [onCleared] so the indexer WebSocket
 * doesn't outlive the host activity.
 */
@HiltViewModel
class WalletPanelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletKeyManager: WalletKeyManager,
    private val biometricGate: BiometricGate,
    private val seedVault: SeedVault,
) : ViewModel() {

    private val _status = MutableStateFlow<WalletStatus>(WalletStatus.None)
    val status: StateFlow<WalletStatus> = _status

    private var sdk: MidnightSdk? = null
    /**
     * Config the current [sdk] was built with. Null when no SDK exists yet.
     * A `null` here OR a `sdkConfig != requestedConfig` triggers a full
     * rebuild in [buildOrReuseSdk] — same handler covers initial bootstrap
     * AND any subsequent user toggle (network, proving mode, proof URL).
     */
    private var sdkConfig: WalletConfig? = null

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
        installProvingKeys()
        val seed = ensureSeedReady(activity)
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
            // local proving on UNDEPLOYED, PREPROD, and PREVIEW. We try
            // `installFromLocalTmp` first (canary path: ~24MB saved when
            // keys are adb-pushed) but always fall back to the download for
            // any network, so a fresh install on any device self-recovers
            // without the user having to know about adb push or local proving.
            if (!built.provingKeyManager.hasWalletKeys()) {
                Log.i(TAG, "Proving keys missing — downloading wallet keys (~24MB)")
                built.provingKeyManager.downloadWalletKeys { /* progress ignored — host decides UX */ }
            }
            built
        } finally {
            // The SDK builder copies the seed internally; wipe our local view.
            seed.fill(0)
        }
    }

    private suspend fun ensureSeedReady(activity: FragmentActivity): ByteArray {
        if (seedVault.hasSeed()) {
            Log.i(TAG, "Loading existing seed from SeedVault (biometric prompt)…")
            val plaintext = seedVault.loadSeed(activity)
            return try {
                plaintext.bip39Seed.copyOf()
            } finally {
                plaintext.wipe()
            }
        }
        Log.i(TAG, "No seed in SeedVault — generating a fresh wallet (biometric prompt)…")
        // SeedVault.storeSeed needs a Keystore master key to encrypt against.
        // Idempotent: skip if already present. WalletKeyManager doesn't
        // auto-create — without this the next call throws "Master key not found".
        if (!walletKeyManager.hasKey()) {
            val strongBox = walletKeyManager.generateKey()
            Log.i(TAG, "Generated Keystore master key (${if (strongBox) "StrongBox" else "TEE"})")
        }
        var capturedSeed: ByteArray? = null
        seedVault.storeSeed(activity) {
            val entropy = ByteArray(PlaintextSeed.ENTROPY_SIZE).also { SecureRandom().nextBytes(it) }
            val mnemonic = BIP39.entropyToMnemonic(entropy)
            val bip39Seed = BIP39.mnemonicToSeed(mnemonic)
            // Save a copy before SeedVault wipes the PlaintextSeed it receives —
            // we hand it to MidnightSdk.Builder in the caller.
            capturedSeed = bip39Seed.copyOf()
            PlaintextSeed(entropy, bip39Seed)
        }
        return requireNotNull(capturedSeed) { "Seed lambda ran but didn't capture (cancelled mid-auth?)" }
    }

    private fun installProvingKeys() {
        val pkm = ProvingKeyManager(context)
        val ok = pkm.installFromLocalTmp()
        if (!ok) {
            Log.w(TAG, "installFromLocalTmp: hasWalletKeys() still false — adb-push keys to /data/local/tmp")
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
