package com.midnight.kuira.sdk

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.midnight.kuira.core.compact.MidnightConfig
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.shielded.ShieldedKeyDeriver
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClientImpl
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
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

    /** Proving key manager (for checking/downloading keys). */
    val provingKeyManager: ProvingKeyManager,

    // Resources to close
    private val database: UtxoDatabase,
    private val indexerClient: IndexerClientImpl,
) {
    /** Release all resources. */
    fun close() {
        wallet.close()
        indexerClient.close()
        database.close()
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

        /** Set the Midnight network (PREPROD, PREVIEW, UNDEPLOYED). */
        fun network(network: MidnightNetwork) = apply { this.network = network }

        /** Set the HD wallet seed (from BIP-39 mnemonic). Copied internally — caller should wipe. */
        fun seed(seed: ByteArray) = apply { this.seed = seed.copyOf() }

        /** Set the HD wallet account index (default 0). */
        fun accountIndex(index: Int) = apply { this.accountIndex = index }

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

            // ── Create wallet ──

            val wallet = MidnightWallet(
                dustRepository = dustRepository,
                indexerClient = indexerClient,
                nodeRpcClient = nodeRpcClient,
                walletAddress = keys.address,
                dustSeed = keys.dustSeed,
                provingKeysDir = provingKeyManager.keysDir.absolutePath,
                networkId = net.rustNetworkId,
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
                provingKeyManager = provingKeyManager,
                database = database,
                indexerClient = indexerClient,
            )
        }
    }

    companion object {
        /** DataStore for SDK dust state (separate from Kuira app). */
        private val Context.sdkDustDataStore by preferencesDataStore(name = "sdk_dust_state")

        internal fun sdkDustStateDataStore(context: Context) = context.sdkDustDataStore
    }
}

// ── Internal key derivation ──

internal data class DerivedKeys(
    val address: String,
    val dustSeed: ByteArray,
    val coinPublicKey: ByteArray,
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

    // Dust seed: m/44'/2400'/account'/2/0
    val dustKey = account.selectRole(MidnightKeyRole.DUST).deriveKeyAt(0)
    val dustSeed = dustKey.privateKeyBytes.copyOf()

    // Coin public key: m/44'/2400'/account'/3/0 → ShieldedKeyDeriver (FFI)
    val zswapKey = account.selectRole(MidnightKeyRole.ZSWAP).deriveKeyAt(0)
    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(zswapKey.privateKeyBytes)
        ?: throw IllegalStateException("ShieldedKeyDeriver.deriveKeys failed — is native library loaded?")
    val coinPublicKey = hexToBytes(shieldedKeys.coinPublicKey)

    // Individual key.clear() is internal — hdWallet.clear() handles hierarchical cleanup

    return DerivedKeys(
        address = address,
        dustSeed = dustSeed,
        coinPublicKey = coinPublicKey,
    )
}
