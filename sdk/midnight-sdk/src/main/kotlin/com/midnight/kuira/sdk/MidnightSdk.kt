package com.midnight.kuira.sdk

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.midnight.kuira.core.compact.MidnightConfig
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.dust.DustKeyDeriver
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.crypto.shielded.ShieldedKeyDeriver
import com.midnight.kuira.core.identity.accesskey.AccessKeyManager
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.repository.ShieldedRepository
import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.api.NodeRpcClientImpl
import com.midnight.kuira.core.ledger.api.ProofServerClientImpl
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.ledger.api.TransactionSubmitter.SubmissionResult
import com.midnight.kuira.core.ledger.dust.DustRegistrationBuilder
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Arrays

/**
 * Top-level entry point for the Midnight Android SDK.
 *
 * Provides a fully standalone environment for dApps: contract execution,
 * local proving, embedded wallet (dust fee payment), and node submission.
 * No external wallet process (`mn serve`) required.
 *
 * ```kotlin
 * val sdk = MidnightSdk.Builder(context)
 *     .network(MidnightNetwork.PREPROD)
 *     .seed(mnemonicSeed)
 *     .build()
 *
 * val contract = MidnightContract.create(sdk.config) {
 *     contractJs = assets.open("runtime/my-contract-iife.js")
 *     address = contractAddress
 *     coinPublicKey = sdk.coinPublicKey
 *     // ... witnesses, private state
 * }
 *
 * val receipt = contract.call("myCircuit", arg1) // Fully standalone
 * sdk.close()
 * ```
 */
class MidnightSdk private constructor(
    /** MidnightConfig pre-wired with the embedded wallet. */
    val config: MidnightConfig,

    /** Embedded wallet for direct balance/submit operations. */
    val wallet: MidnightWallet,

    /** Coin public key for circuit execution (32 bytes). */
    val coinPublicKey: ByteArray,

    /** Unshielded wallet address (Bech32m-encoded). */
    val walletAddress: String,

    /**
     * Shielded receive address (Bech32m-encoded, HRP `mn_shield-addr_<network>`).
     *
     * Senders use this to dispatch shielded NIGHT to the wallet; only the
     * holder of the SDK's zswap key can decrypt the incoming coins. Public,
     * safe to share / display / encode in a QR. Derived deterministically
     * from the seed at build time.
     */
    val shieldedWalletAddress: String,

    /** Proving key manager (for checking/downloading keys). */
    val provingKeyManager: ProvingKeyManager,

    /**
     * Access key for sigil identity — 33-byte compressed secp256k1 public key.
     * Use this with [KeyAuthorization] to build delegation payloads.
     */
    val accessKeyPublicKey: ByteArray,

    /** HD derivation path of the access key (e.g., "m/44'/2400'/0'/5/0"). */
    val accessKeyPath: String,

    // ── Internals held for [registerForDustGeneration] and lifecycle cleanup ──
    private val nightPrivateKey: ByteArray,
    private val dustSeed: ByteArray,
    private val networkId: String,
    private val utxoManager: UtxoManager,
    private val transactionSubmitter: TransactionSubmitter,
    private val subscriptionScope: CoroutineScope,
    private val subscriptionJob: Job,
    private val database: UtxoDatabase,
    private val indexerClient: IndexerClientImpl,
    private val shieldedTracker: ShieldedBalanceTracker,
) {
    /**
     * Register this wallet's NIGHT key to generate dust against its public dust key.
     *
     * Dust is the fee token Midnight generates from NIGHT holdings over time, but the
     * generated dust is only *spendable* by a wallet that has registered its DUST
     * public key against its NIGHT signing key on-chain. Newly-funded wallets must
     * call this once before they can pay fees on any subsequent transaction —
     * including contract calls.
     *
     * Inputs are pulled from the SDK's already-derived state: NIGHT private key
     * for the signature, dust seed for the dust pubkey, current NIGHT UTXOs for
     * the `guaranteed_unshielded_offer`. The unshielded-tx subscription started
     * at SDK build time keeps [utxoManager] populated, so this method picks up
     * NIGHT UTXOs the moment a faucet/transfer credits the wallet.
     *
     * Submission uses [TransactionSubmitter.submitPrebuiltTransaction] with
     * [ProvingMode.LOCAL] — same local-prove → seal → submit path that contract
     * calls already use; no proof server required.
     *
     * **Precondition:** the wallet address must hold at least one NIGHT UTXO.
     * The chain rejects registration with no NIGHT to back the generation. Call
     * [MidnightWallet.waitForFunding] first if the wallet is fresh.
     *
     * @return [SubmissionResult.Success] when the registration tx is finalized on
     *   chain. [SubmissionResult.Pending] if the tx made it into a block but the
     *   wait for finalization timed out (it'll usually finalize shortly after).
     *   [SubmissionResult.Failed] if the chain rejected the tx, or no NIGHT UTXOs
     *   are available, or the FFI builder returned null.
     */
    suspend fun registerForDustGeneration(): SubmissionResult {
        val nightUtxos = utxoManager.getUnspentUtxos(walletAddress)
            .filter { it.tokenType == UtxoSpend.NATIVE_TOKEN_TYPE }

        if (nightUtxos.isEmpty()) {
            return SubmissionResult.Failed(
                txHash = null,
                reason = "No NIGHT UTXOs at $walletAddress. Fund the wallet first.",
            )
        }

        val utxosJson = JSONArray().apply {
            nightUtxos.forEach { utxo ->
                put(
                    JSONObject().apply {
                        put("value", utxo.value)
                        put("intent_hash", utxo.intentHash)
                        put("output_no", utxo.outputIndex)
                        put("ctime", utxo.ctime)
                    }
                )
            }
        }.toString()

        val dustPublicKeyHex = DustKeyDeriver.derivePublicKey(dustSeed)
            ?: return SubmissionResult.Failed(
                txHash = null,
                reason = "DustKeyDeriver returned null — native library not loaded?",
            )

        // Chain-anchored time, NOT wall-clock — same reason as the Error 170 fix
        // in MidnightWallet.tryBalance (commit 868e0d9). The registration tx
        // doesn't pay a dust fee but the ctime is still validated against
        // chain block times.
        val blockTimestamp = wallet.indexerBlockTimestampMs()

        val unprovenHex = DustRegistrationBuilder.build(
            nightPrivateKey = nightPrivateKey,
            dustPublicKeyHex = dustPublicKeyHex,
            utxosJson = utxosJson,
            ttlMillis = blockTimestamp + REGISTRATION_TTL_MS,
            networkId = networkId,
            currentTimeMillis = blockTimestamp,
        ) ?: return SubmissionResult.Failed(
            txHash = null,
            reason = "DustRegistrationBuilder.build returned null",
        )

        Log.i(TAG, "Submitting dust registration: ${nightUtxos.size} NIGHT UTXOs, ${unprovenHex.length} hex chars")
        return transactionSubmitter.submitPrebuiltTransaction(unprovenHex)
    }

    /** Release all resources. */
    fun close() {
        subscriptionJob.cancel()
        subscriptionScope.cancel()
        shieldedTracker.close()
        wallet.close()
        indexerClient.close()
        database.close()
        Arrays.fill(nightPrivateKey, 0.toByte())
        Arrays.fill(dustSeed, 0.toByte())
    }

    /**
     * Builder for [MidnightSdk].
     *
     * Required: [network] and [seed]. Optional: [accountIndex] (default 0).
     */
    class Builder(private val context: Context) {
        private var network: MidnightNetwork? = null
        private var seed: ByteArray? = null
        private var accountIndex: Int = 0
        private var provingMode: ProvingMode = ProvingMode.DEFAULT
        private var proofServerUrl: String? = null

        /** Set the Midnight network (PREPROD, PREVIEW, UNDEPLOYED). */
        fun network(network: MidnightNetwork) = apply { this.network = network }

        /** Set the HD wallet seed (from BIP-39 mnemonic). Copied internally — caller should wipe. */
        fun seed(seed: ByteArray) = apply { this.seed = seed.copyOf() }

        /** Set the HD wallet account index (default 0). */
        fun accountIndex(index: Int) = apply { this.accountIndex = index }

        /**
         * Pick how transactions are proved. Defaults to [ProvingMode.LOCAL] —
         * on-device proving with cached keys, no network round-trip per tx.
         *
         * [ProvingMode.REMOTE] off-loads proving to a proof server reachable
         * at the URL supplied via [proofServerUrl]; useful on devices that
         * can't carry the proving-key bundle, or when the host wants to keep
         * the keys out of process memory. Set the URL **before** [build]
         * when picking REMOTE — otherwise we fall back to the network's
         * default proof-server URL (`NetworkConfig.proofServerUrl`).
         */
        fun provingMode(mode: ProvingMode) = apply { this.provingMode = mode }

        /**
         * Override the proof-server URL used when [provingMode] is REMOTE.
         * Null (default) means "use the network's default proof-server URL"
         * (`NetworkConfig.forNetwork(network).proofServerUrl`, which points
         * at a local proof server on `localhost:6300` for every network the
         * SDK supports). Pass an explicit URL to point at a hosted prover.
         */
        fun proofServerUrl(url: String?) = apply { this.proofServerUrl = url }

        /**
         * Build the SDK. This is a blocking operation on first launch:
         * - Derives HD wallet keys
         * - Creates database and network clients
         * - Does NOT sync dust or download proving keys — call those explicitly.
         */
        fun build(): MidnightSdk {
            val net = requireNotNull(network) { "network is required" }
            val seedBytes = requireNotNull(seed) { "seed is required" }
            require(seedBytes.size >= 32) { "seed must be at least 32 bytes" }

            val appContext = context.applicationContext
            val networkConfig = NetworkConfig.forNetwork(net)

            // ── Derive keys ──

            val hdWallet = HDWallet.fromSeed(seedBytes)
            val keys: DerivedKeys
            try {
                keys = deriveKeys(hdWallet, accountIndex, net)
            } finally {
                hdWallet.clear()
                Arrays.fill(seedBytes, 0.toByte())
                seed = null
            }

            // ── Create infrastructure ──

            val indexerClient = IndexerClientImpl(
                baseUrl = networkConfig.indexerBaseUrl,
                developmentMode = networkConfig.developmentMode,
            )

            val nodeRpcClient = NodeRpcClientImpl(
                nodeUrl = networkConfig.nodeRpcUrl,
                developmentMode = networkConfig.developmentMode,
            )

            val database = Room.databaseBuilder(
                appContext,
                UtxoDatabase::class.java,
                "kuira-sdk-utxo-database", // Separate DB name to avoid conflict with Kuira app
            ).build()

            val dustRepository = DustRepository(
                dustDao = database.dustDao(),
                dustStateDataStore = sdkDustStateDataStore(appContext),
                balanceCalculator = DustBalanceCalculator(),
                indexerClient = indexerClient,
            )

            val provingKeyManager = ProvingKeyManager(appContext)

            // ── Unshielded UTXO tracking (NIGHT balance + registration inputs) ──
            //
            // Lifts the same pieces the Kuira app uses: UtxoManager observes the
            // Room DAO, SubscriptionManager runs the indexer's unshielded-tx
            // subscription and feeds UtxoManager. BalanceRepository wraps both
            // for the Flow-based balance API. Nothing duplicated — see
            // core/indexer/sync/SubscriptionManager.kt header for the same pattern
            // used by the parent app.
            val utxoManager = UtxoManager(database.unshieldedUtxoDao())
            val syncStateManager = SyncStateManager(appContext)
            val subscriptionManager = SubscriptionManager(
                context = appContext,
                indexerClient = indexerClient,
                utxoManager = utxoManager,
                syncStateManager = syncStateManager,
            )
            val balanceRepository = BalanceRepository(utxoManager, indexerClient)

            // Launch the long-lived unshielded subscription. The scope lives
            // for the SDK's lifetime; close() cancels it. SupervisorJob means a
            // sub-failure (e.g. transient indexer disconnect — already handled
            // with backoff inside SubscriptionManager) doesn't kill sibling work.
            val subscriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val subscriptionJob = subscriptionScope.launch {
                subscriptionManager.startSubscription(keys.address).collect { /* state observed inside */ }
            }

            // ── Shielded NIGHT tracker (auto-syncs in background) ──
            //
            // The SDK fully owns shielded balance freshness so dApps don't
            // have to wire ShieldedRepository themselves. ShieldedBalanceTracker
            // runs an initial replay and then re-runs it on chain-tip advance.
            // Construction is direct (no Hilt) — ShieldedRepository's Hilt
            // annotations are just metadata, the class is fine to `new` here.
            val shieldedRepository = ShieldedRepository(
                dataStore = sdkShieldedStateDataStore(appContext),
                indexerClient = indexerClient,
                networkConfig = networkConfig,
            )
            val shieldedTracker = ShieldedBalanceTracker(
                shieldedRepository = shieldedRepository,
                walletAddress = keys.address,
                zswapSeed = keys.zswapSeed,
            )
            // Launch on the same subscriptionScope so close() cancels it.
            shieldedTracker.start(subscriptionScope)

            // ── Create wallet with tip-aware dust sync + shielded tracker ──

            val dustSyncManager = DustSyncManager(
                dustRepository = dustRepository,
                nodeRpcClient = nodeRpcClient,
                walletAddress = keys.address,
                dustSeed = keys.dustSeed,
            )

            val wallet = MidnightWallet(
                dustSyncManager = dustSyncManager,
                dustRepository = dustRepository,
                indexerClient = indexerClient,
                nodeRpcClient = nodeRpcClient,
                balanceRepository = balanceRepository,
                shieldedTracker = shieldedTracker,
                walletAddress = keys.address,
                dustSeed = keys.dustSeed,
                provingKeysDir = provingKeyManager.keysDir.absolutePath,
                networkId = net.rustNetworkId,
            )

            // ── Transaction submitter for non-balanced txs (e.g. dust registration) ──
            //
            // [provingMode] picks where ZK proofs are generated:
            //   - LOCAL  → on-device prover using cached keys; no proof-server
            //              round-trip per transaction. Default.
            //   - REMOTE → off-load to a proof server reachable at
            //              [proofServerUrl] (falls back to the network's
            //              local-dev proof server on `localhost:6300` when null).
            //
            // [ProofServerClientImpl] always gets a non-null URL even in LOCAL
            // mode because the [TransactionSubmitter] constructor requires a
            // non-null client. In LOCAL mode the client is constructed but
            // never used; the URL just has to be parseable.
            val effectiveProofServerUrl = this.proofServerUrl ?: networkConfig.proofServerUrl
            val serializer = FfiTransactionSerializer(net.rustNetworkId)
            val proofServerClient = ProofServerClientImpl(
                proofServerUrl = effectiveProofServerUrl,
                developmentMode = networkConfig.developmentMode,
            )
            val transactionSubmitter = TransactionSubmitter(
                nodeRpcClient = nodeRpcClient,
                proofServerClient = proofServerClient,
                indexerClient = indexerClient,
                serializer = serializer,
                utxoManager = utxoManager,
                provingKeyManager = provingKeyManager,
                provingMode = provingMode,
            )

            // ── Create config with embedded wallet ──

            val config = MidnightConfig.Builder(appContext)
                .indexerUrl(networkConfig.indexerBaseUrl)
                .transactionBalancer(wallet)
                .networkId(net.rustNetworkId)
                .build()

            return MidnightSdk(
                config = config,
                wallet = wallet,
                coinPublicKey = keys.coinPublicKey,
                walletAddress = keys.address,
                shieldedWalletAddress = keys.shieldedAddress,
                provingKeyManager = provingKeyManager,
                accessKeyPublicKey = keys.accessKeyPublicKey,
                accessKeyPath = keys.accessKeyPath,
                nightPrivateKey = keys.nightPrivateKey,
                dustSeed = keys.dustSeed,
                networkId = net.rustNetworkId,
                utxoManager = utxoManager,
                transactionSubmitter = transactionSubmitter,
                subscriptionScope = subscriptionScope,
                subscriptionJob = subscriptionJob,
                database = database,
                indexerClient = indexerClient,
                shieldedTracker = shieldedTracker,
            )
        }
    }

    companion object {
        private const val TAG = "MidnightSdk"

        /**
         * TTL for the dust registration transaction. 30 minutes mirrors the SDK's
         * default contract-call TTL — long enough that a sluggish indexer or
         * slow user interaction (e.g. waiting for a faucet) doesn't expire it,
         * short enough that a forgotten tx doesn't linger in the mempool.
         */
        private const val REGISTRATION_TTL_MS = 30L * 60L * 1_000L

        /** DataStore for SDK dust state (separate from Kuira app). */
        private val Context.sdkDustDataStore by preferencesDataStore(name = "sdk_dust_state")

        internal fun sdkDustStateDataStore(context: Context) = context.sdkDustDataStore

        /** DataStore for SDK shielded (zswap) state. Separate file from the Kuira app's. */
        private val Context.sdkShieldedDataStore by preferencesDataStore(name = "sdk_shielded_state")

        internal fun sdkShieldedStateDataStore(context: Context) = context.sdkShieldedDataStore
    }
}

// ── Internal key derivation ──

internal data class DerivedKeys(
    val address: String,
    /**
     * Shielded receive address — Bech32m of `coinPublicKey || encryptionPublicKey`
     * with HRP `mn_shield-addr_<network>`. Senders use this to dispatch shielded
     * NIGHT to the wallet; only the holder of [zswapSeed] can decrypt the
     * incoming coins. Public, safe to share / display / encode in a QR.
     */
    val shieldedAddress: String,
    /**
     * 32-byte NIGHT signing key (secp256k1 private bytes) at m/44'/2400'/account'/0/0.
     * Same sensitivity envelope as [dustSeed] — held in memory for the lifetime of
     * the SDK instance so that operations like dust registration (which the chain
     * requires the NIGHT key to sign) don't need to round-trip through SeedVault.
     * Wiped in [MidnightSdk.close].
     */
    val nightPrivateKey: ByteArray,
    val dustSeed: ByteArray,
    /**
     * 32-byte zswap seed at m/44'/2400'/account'/3/0. Retained for the SDK's
     * lifetime because [ShieldedBalanceTracker] needs it to decrypt zswap
     * events every time the shielded subscription re-syncs. Wiped by
     * `ShieldedBalanceTracker.close()` (called from [MidnightSdk.close]).
     */
    val zswapSeed: ByteArray,
    val coinPublicKey: ByteArray,
    val accessKeyPublicKey: ByteArray,
    val accessKeyPath: String,
)

/**
 * Derive wallet keys from HD wallet.
 *
 * - Unshielded address: m/44'/2400'/account'/0/0 → SHA-256(x-only pubkey) → Bech32m
 * - Dust seed: m/44'/2400'/account'/2/0 → 32-byte private key
 * - Coin public key: derived via ShieldedKeyDeriver from m/44'/2400'/account'/3/0
 */
private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun deriveKeys(
    hdWallet: HDWallet,
    accountIndex: Int,
    network: MidnightNetwork,
): DerivedKeys {
    val account = hdWallet.selectAccount(accountIndex)

    // Unshielded address: m/44'/2400'/account'/0/0
    val nightKey = account.selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)
    val xOnlyPubKey = nightKey.publicKeyBytes.copyOfRange(1, 33) // Skip prefix byte
    val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPubKey)
    val address = Bech32m.encode(network.addressPrefix, addressData)
    // Hold the NIGHT signing key for the lifetime of the SDK — needed when the
    // dApp calls operations that the chain requires the NIGHT key to sign
    // (e.g. dust registration). Symmetric to the existing [dustSeed] retention.
    val nightPrivateKey = nightKey.privateKeyBytes.copyOf()

    // Dust seed: m/44'/2400'/account'/2/0
    val dustKey = account.selectRole(MidnightKeyRole.DUST).deriveKeyAt(0)
    val dustSeed = dustKey.privateKeyBytes.copyOf()

    // Coin public key: m/44'/2400'/account'/3/0 → ShieldedKeyDeriver (FFI)
    val zswapKey = account.selectRole(MidnightKeyRole.ZSWAP).deriveKeyAt(0)
    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(zswapKey.privateKeyBytes)
        ?: throw IllegalStateException("ShieldedKeyDeriver.deriveKeys failed — is native library loaded?")
    val coinPublicKey = hexToBytes(shieldedKeys.coinPublicKey)
    // Shielded receive address — Bech32m-encoded (coinPubKey || encryptionPubKey)
    // with network-specific HRP `mn_shield-addr_<network>`. Lace-compatible,
    // verified by LaceCompatibilityTest in core:crypto.
    val encryptionPublicKey = hexToBytes(shieldedKeys.encryptionPublicKey)
    val shieldedAddressData = coinPublicKey + encryptionPublicKey
    val shieldedAddress = Bech32m.encode(network.shieldedAddressPrefix, shieldedAddressData)
    // Retain the zswap seed for the SDK's lifetime — ShieldedBalanceTracker
    // needs it on every shielded resync to decrypt zswap events.
    val zswapSeed = zswapKey.privateKeyBytes.copyOf()

    // Access key for sigil identity: m/44'/2400'/account'/5/0
    val accessKeyManager = AccessKeyManager(hdWallet, accountIndex)
    val accessKey = accessKeyManager.deriveDefaultAccessKey()
    val accessKeyPublicKey = accessKey.publicKeyBytes.copyOf()
    val accessKeyPath = accessKey.path

    // Individual key.clear() is internal — hdWallet.clear() handles hierarchical cleanup

    return DerivedKeys(
        address = address,
        shieldedAddress = shieldedAddress,
        nightPrivateKey = nightPrivateKey,
        dustSeed = dustSeed,
        zswapSeed = zswapSeed,
        coinPublicKey = coinPublicKey,
        accessKeyPublicKey = accessKeyPublicKey,
        accessKeyPath = accessKeyPath,
    )
}
