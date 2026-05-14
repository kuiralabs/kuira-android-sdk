package com.midnight.example.common.wallet

import android.app.Application
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.SecureRandom

/**
 * Self-contained wallet bootstrap + lifecycle for example apps.
 *
 * The panel's contract with the host app:
 *  - host owns nothing wallet-related — pass [MidnightNetwork], get a usable SDK
 *  - first action triggers a biometric prompt to seal a freshly-generated BIP-39
 *    seed via [SeedVault]; subsequent runs reuse it transparently
 *  - the SDK is built lazily on first action, reused while [network] stays the
 *    same, rebuilt when it changes
 *
 * **Public surface:** [status] (observe), [refreshBalance] / [waitForFunding] /
 * [registerDust] (act). The sheet's three buttons map 1:1 to those actions.
 *
 * **Not for production:** [installProvingKeys] reads from `/data/local/tmp/`,
 * which is the same adb-push convention the SDK e2e tests use. Production
 * apps download keys at runtime via `ProvingKeyManager.downloadWalletKeys`.
 *
 * **Lifecycle:** the SDK is closed in [onCleared] so the indexer WebSocket
 * doesn't outlive the host activity.
 */
class WalletPanelViewModel(app: Application) : AndroidViewModel(app) {

    private val walletKeyManager = WalletKeyManager()
    private val biometricGate = BiometricGate(walletKeyManager)
    private val seedVault = SeedVault(app, biometricGate)

    private val _status = MutableStateFlow<WalletStatus>(WalletStatus.None)
    val status: StateFlow<WalletStatus> = _status

    private var sdk: MidnightSdk? = null
    private var sdkNetwork: MidnightNetwork? = null

    /**
     * Read NIGHT/dust balance from chain. Triggers seed unlock (biometric) on
     * first call per session, then reuses the SDK while [network] stays the same.
     */
    fun refreshBalance(network: MidnightNetwork, activity: FragmentActivity) {
        viewModelScope.launch {
            _status.value = WalletStatus.Loading("Reading balance...")
            try {
                val built = buildOrReuseSdk(network, activity)
                // Best-effort dust resync. NIGHT is subscription-driven and live
                // without this; dust state is local-replay-driven and can be
                // stale. A transient WS hiccup shouldn't fail the whole read.
                runCatching { built.wallet.refresh() }
                    .onFailure { Log.w(TAG, "wallet.refresh failed (showing cached): ${it.message}") }
                val balance = built.wallet.balance()
                _status.value = WalletStatus.Ready(address = built.walletAddress, balance = balance)
                Log.i(
                    TAG,
                    "balance: unshieldedNight=${balance.unshieldedNight} " +
                        "shieldedNight=${balance.shieldedNight} " +
                        "dust=${balance.dust} registered=${balance.dustRegistered}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "refreshBalance failed", e)
                _status.value = WalletStatus.Error(e.message ?: "Balance read failed")
            }
        }
    }

    /**
     * Suspend until NIGHT >= [MIN_FUNDING_NIGHT]. While waiting the sheet shows
     * the wallet address so the user can run `mn airdrop <amount> --wallet <addr>`
     * from a host terminal.
     */
    fun waitForFunding(network: MidnightNetwork, activity: FragmentActivity) {
        viewModelScope.launch {
            try {
                val built = buildOrReuseSdk(network, activity)
                val current = built.wallet.balance()
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
                    balance = current,
                    busy = "Waiting for funds…",
                )
                Log.i(TAG, "waitForFunding: unshieldedNight=${current.unshieldedNight}")
                val funded = built.wallet.waitForFunding(MIN_FUNDING_NIGHT)
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
                    balance = funded,
                    // External funding lands on the unshielded address, so the
                    // funded edge is signaled by unshieldedNight crossing the threshold.
                    message = "Funded — NIGHT=${funded.unshieldedNight}",
                )
            } catch (e: Exception) {
                Log.e(TAG, "waitForFunding failed", e)
                _status.value = WalletStatus.Error(e.message ?: "Wait for funding failed")
            }
        }
    }

    /**
     * Register the wallet's NIGHT key for dust generation, then poll until the
     * first dust UTXO surfaces (or [DUST_VISIBLE_TIMEOUT_MS] elapses). Must run
     * once after the wallet first holds NIGHT — until then the chain won't
     * release spendable dust and contract calls (fee-paying) fail.
     */
    fun registerDust(network: MidnightNetwork, activity: FragmentActivity) {
        viewModelScope.launch {
            try {
                val built = buildOrReuseSdk(network, activity)
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
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
                        balance = built.wallet.balance(),
                        message = "Registration failed: $reason",
                    )
                    return@launch
                }

                // Registration accepted — dust generates from the NEXT block. Poll
                // so the sheet shows dust climb instead of a stale "dust 0".
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
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
                        balance = latest,
                        busy = if (latest.dust == BigInteger.ZERO) "Waiting for first dust generation…" else null,
                    )
                }
                _status.value = WalletStatus.Ready(
                    address = built.walletAddress,
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

    private suspend fun buildOrReuseSdk(network: MidnightNetwork, activity: FragmentActivity): MidnightSdk {
        sdk?.let { existing ->
            if (sdkNetwork == network) return existing
            // Network changed — tear down the old subscription/db before rebuilding.
            existing.close()
            sdk = null
        }
        installProvingKeys()
        val seed = ensureSeedReady(activity)
        return try {
            val built = MidnightSdk.Builder(getApplication())
                .network(network)
                .seed(seed)
                .build()
            sdk = built
            sdkNetwork = network
            // Non-zero-fee networks need wallet proving keys downloaded at
            // runtime; UNDEPLOYED ships them via adb-push for the canary loop.
            if (!built.provingKeyManager.hasWalletKeys() && network != MidnightNetwork.UNDEPLOYED) {
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
        val pkm = ProvingKeyManager(getApplication())
        val ok = pkm.installFromLocalTmp()
        if (!ok) {
            Log.w(TAG, "installFromLocalTmp: hasWalletKeys() still false — adb-push keys to /data/local/tmp")
        }
    }

    companion object {
        private const val TAG = "WalletPanel"

        /**
         * Minimum NIGHT (u128 base units) before we consider the wallet "funded
         * enough to register". One base unit is enough — the canary uses
         * `mn airdrop 10000` which credits 10_000 * 10^6 = 10^10 base units.
         */
        private val MIN_FUNDING_NIGHT = BigInteger.ONE

        /** Upper bound on the post-registration dust-visibility poll. */
        private const val DUST_VISIBLE_TIMEOUT_MS = 20_000L

        /** Cadence of the post-registration poll. */
        private const val DUST_POLL_INTERVAL_MS = 2_000L

        /**
         * Factory for `viewModel(factory = WalletPanelViewModel.Factory)` — saves
         * host apps from defining their own `AndroidViewModelFactory`.
         */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                WalletPanelViewModel(app)
            }
        }
    }
}
