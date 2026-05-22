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
import com.midnight.kuira.core.identity.backup.SeedDeriver
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.dapp.sigil.SigilPanelViewModel
import com.midnight.kuira.sdk.MidnightSdk
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
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
 * **Proving keys:** the SDK's `ProvingKeyManager.ensureWalletKeysAvailable`
 * runs after [MidnightSdk.Builder.build] — `/data/local/tmp/` shortcut on
 * dev devices, ~24MB S3 download on a fresh emulator. No per-host wiring.
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
    private val passkeyManager: PasskeyManager,
) : ViewModel() {

    /**
     * Read-only handle on the sigil panel's prefs file so the wallet
     * bootstrap can detect "no passkey yet" without taking a Hilt
     * dependency on the sigil ViewModel. The schema is owned by
     * [SigilPanelViewModel] — this VM only checks
     * [SigilPanelViewModel.KEY_CREDENTIAL_ID] for presence.
     *
     * Tight-ish coupling, but extracting a SigilStore port would add a
     * new type just for this one read. Revisit when a third consumer
     * needs the same query.
     */
    private val sigilPrefs by lazy {
        context.getSharedPreferences(SigilPanelViewModel.SIGIL_PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Wallet-panel-owned prefs file. Currently stores one flag:
     * [KEY_SEED_IS_PRF_DERIVED], set after a successful PRF derivation
     * so subsequent launches trust the SeedVault cache. Absence of the
     * flag (with a populated SeedVault) signals a legacy random seed
     * that the next launch must wipe + re-derive.
     */
    private val walletPanelPrefs by lazy {
        context.getSharedPreferences(WALLET_PANEL_PREFS_NAME, Context.MODE_PRIVATE)
    }

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

    /**
     * Returns the wallet's BIP-39 seed, deriving it from the user's
     * passkey via PRF if not already cached.
     *
     * **Contract:**
     *  - Throws [SigilRequiredException] if no passkey has been forged.
     *    Action handlers translate this to [WalletStatus.SigilRequired];
     *    the throw is defense-in-depth in case a caller bypasses the
     *    upstream UI gate.
     *  - Cache hit (PRF flag set): biometric prompt to decrypt
     *    SeedVault, return.
     *  - Cache miss OR legacy random-seed cache: biometric prompt
     *    fires the passkey PRF, derives the seed, wipes any legacy
     *    cache, stores the PRF-derived seed, sets the flag.
     *
     * **Legacy migration (decision B):** if SeedVault holds a seed
     * from before this commit (no PRF flag), it gets wiped on first
     * launch. Any funds at that legacy address are abandoned — the
     * user explicitly opted out of preservation.
     */
    internal suspend fun ensureSeedReady(activity: FragmentActivity): ByteArray {
        if (!hasSigil()) {
            throw SigilRequiredException()
        }

        // Cache hit only when BOTH conditions hold: a vault entry exists
        // AND we wrote the PRF flag the last time we populated it.
        // Without the flag a vault entry is treated as legacy random
        // seed and force-rederived (decision B).
        if (seedVault.hasSeed() && isVaultPrfDerived()) {
            Log.i(TAG, "Loading PRF-derived seed from SeedVault (biometric prompt)…")
            val plaintext = seedVault.loadSeed(activity)
            return try {
                plaintext.bip39Seed.copyOf()
            } finally {
                plaintext.wipe()
            }
        }

        // Legacy vault: wipe before storeSeed (which refuses overwrite).
        // The flag was never set on this entry, so by contract it's a
        // pre-PRF random seed — we have no way to migrate it cleanly
        // and the user accepted the drop in decision B.
        if (seedVault.hasSeed()) {
            Log.w(TAG, "Legacy random seed detected — wiping and re-deriving from passkey PRF")
            seedVault.deleteSeed()
        } else {
            Log.i(TAG, "No seed in SeedVault — deriving from passkey PRF…")
        }

        // Keystore master key for SeedVault's AES-GCM encryption.
        // Idempotent: WalletKeyManager doesn't auto-create.
        if (!walletKeyManager.hasKey()) {
            val strongBox = walletKeyManager.generateKey()
            Log.i(TAG, "Generated Keystore master key (${if (strongBox) "StrongBox" else "TEE"})")
        }

        // ONE PRF authentication serves both outputs: the 32-byte
        // entropy (= raw PRF output, fills PlaintextSeed.mnemonicEntropy)
        // and the 64-byte BIP-39 seed (entropy → mnemonic → PBKDF2,
        // pure compute — no second biometric prompt).
        val material = SeedDeriver.derivePrfMaterial(activity, passkeyManager)
        var capturedSeed: ByteArray? = null
        try {
            seedVault.storeSeed(activity) {
                // SeedVault wipes the PlaintextSeed it receives, so we
                // hand it owned copies and stash a separate copy of
                // bip39Seed for the SDK builder.
                capturedSeed = material.bip39Seed.copyOf()
                PlaintextSeed(material.entropy.copyOf(), material.bip39Seed.copyOf())
            }
            // Flag the vault entry as PRF-derived so the next launch
            // hits the cache path instead of force-rederiving.
            walletPanelPrefs.edit().putBoolean(KEY_SEED_IS_PRF_DERIVED, true).commit()
        } finally {
            material.wipe()
        }
        return requireNotNull(capturedSeed) { "Seed lambda ran but didn't capture (cancelled mid-auth?)" }
    }

    /**
     * True when the sigil panel has persisted a forged passkey.
     * Reads sigil_identity prefs directly — see [sigilPrefs] for
     * the coupling rationale.
     */
    private fun hasSigil(): Boolean =
        sigilPrefs.getString(SigilPanelViewModel.KEY_CREDENTIAL_ID, null) != null

    private fun isVaultPrfDerived(): Boolean =
        walletPanelPrefs.getBoolean(KEY_SEED_IS_PRF_DERIVED, false)

    companion object {
        private const val TAG = "WalletPanel"

        /** Upper bound on the post-registration dust-visibility poll. */
        private const val DUST_VISIBLE_TIMEOUT_MS = 20_000L

        /** Cadence of the post-registration poll. */
        private const val DUST_POLL_INTERVAL_MS = 2_000L

        /**
         * Wallet-panel SharedPreferences file. Internal so tests can
         * reach the same name without a magic-string duplication.
         */
        internal const val WALLET_PANEL_PREFS_NAME = "wallet_panel"

        /**
         * True when the SeedVault entry was populated via the passkey
         * PRF path. Absence (with a populated vault) means a legacy
         * random-seed entry that must be wiped + re-derived.
         */
        internal const val KEY_SEED_IS_PRF_DERIVED = "seedIsPrfDerived"
    }
}
