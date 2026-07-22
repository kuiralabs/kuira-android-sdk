package com.midnight.kuira.core.compact

import android.content.Context
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Configuration for Midnight contract operations.
 *
 * Created once per app, shared across all [MidnightContract] instances.
 * Manages indexer connectivity, transaction balancing, and proving.
 *
 * Two modes:
 * - **Remote wallet:** provide `walletUrl` to delegate balancing to `mn serve` via WebSocket
 * - **Embedded wallet:** provide a [TransactionBalancer] directly (e.g., `MidnightWallet`)
 *
 * ```kotlin
 * val previewConfig = NetworkConfig.forNetwork(MidnightNetwork.PREVIEW)
 *
 * // Remote wallet (existing pattern)
 * val config = MidnightConfig.Builder(context)
 * .indexerUrl(previewConfig.indexerBaseUrl)
 * .walletUrl("ws://10.0.2.2:9932")
 * .networkId(MidnightNetwork.PREVIEW.rustNetworkId)
 * .build()
 *
 * // Embedded wallet (new pattern)
 * val config = MidnightConfig.Builder(context)
 * .indexerUrl(previewConfig.indexerBaseUrl)
 * .transactionBalancer(myWallet)
 * .networkId(MidnightNetwork.PREVIEW.rustNetworkId)
 * .build()
 * ```
 */
class MidnightConfig private constructor(
    val context: Context,
    val indexerUrl: String,
    private val walletUrl: String?,
    val networkId: String,
    val provingKeyManager: ProvingKeyManager,
    private val externalBalancer: TransactionBalancer?,
    private val operationListener: ContractOperationListener?,
    private val blockSignals: Flow<Unit>?,
) {
    internal val executor = CircuitExecutor(context)
    internal val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)

    /**
     * Auto-fund the unshielded money path (kuira-sdk-android). When [autoFundUnshieldedDeposits]
     * is on and a circuit receives unshielded value with no offer supplied, [MidnightContract]
     * calls this provider to build the funding offer from the wallet and re-runs the call. Set by
     * the SDK after construction (the provider needs the wallet). `null` provider or a `false`
     * policy → the caller must supply the offer, which surfaces as the Layer 1 clear error.
     */
    internal var unshieldedFundingProvider: (suspend (tokenHex: String, amount: java.math.BigInteger) -> String)? = null
    internal var autoFundUnshieldedDeposits: Boolean = false

    /**
     * Wire unshielded auto-funding (kuira-sdk-android). Called by the SDK, which owns the wallet
     * the [provider] draws from — dApps don't call this directly. A `null` provider or `enabled =
     * false` leaves the caller responsible for the offer (the Layer 1 clear error).
     */
    fun configureUnshieldedAutoFund(
        enabled: Boolean,
        provider: (suspend (tokenHex: String, amount: java.math.BigInteger) -> String)?,
    ) {
        this.autoFundUnshieldedDeposits = enabled
        this.unshieldedFundingProvider = provider
    }

    /**
     * A tick each time the chain may have advanced — drives [MidnightContract.observeLedger].
     * Rides the SDK-injected block subscription ([Builder.blockSignals]); when none is wired (a
     * raw compact-engine consumer), falls back to a poll so observeLedger still works. Neither
     * source emits on subscribe — observeLedger prepends its own initial read.
     */
    internal fun ledgerSignals(): Flow<Unit> {
        val poll = pollTicks()
        // Prefer block-driven ticks; if the block stream errors (WS unavailable / not yet
        // implemented server-side), DEGRADE to polling so observeLedger keeps working instead
        // of terminating. No block stream wired at all → poll directly.
        return blockSignals?.catch { emitAll(poll) } ?: poll
    }

    private fun pollTicks(): Flow<Unit> = flow {
        while (true) {
            delay(LEDGER_POLL_INTERVAL_MS)
            emit(Unit)
        }
    }

    /**
     * Bracket [block] as a tracked foreground operation when an [operationListener]
     * is wired (the SDK injects one), else run it directly. Used by [MidnightContract]
     * to keep the process alive across a whole call/deploy — proving included.
     */
    internal suspend fun <T> runTracked(circuitName: String, block: suspend () -> T): T =
        operationListener?.trackContractCall(circuitName, block) ?: block()

    private var connectorClient: DAppConnectorClient? = null
    private val balancerMutex = Mutex()

    /**
     * Get the [TransactionBalancer] for this config.
     *
     * If an external balancer was provided via [Builder.transactionBalancer], returns it directly.
     * Otherwise creates/reuses a [DAppConnectorClient] connected to [walletUrl].
     */
    internal suspend fun getBalancer(): TransactionBalancer = balancerMutex.withLock {
        externalBalancer?.let { return@withLock it }

        connectorClient?.let { return@withLock it }

        val url = requireNotNull(walletUrl) { "No TransactionBalancer or walletUrl configured" }
        val client = DAppConnectorClient(url)
        client.connect()
        connectorClient = client
        client
    }

    /** Fetch contract state from the indexer. */
    internal suspend fun fetchContractState(address: String): String =
        graphqlQuery("query { contractAction(address: \\\"$address\\\") { state } }")
            .let { data ->
                data.optJSONObject("contractAction")
                    ?.getString("state")
                    ?: throw ContractCallException.StateFetchFailed(
                        "Contract not found at address: $address"
                    )
            }

    /** Fetch ledger parameters (cost model) from the indexer. */
    internal suspend fun fetchLedgerParameters(): String =
        graphqlQuery("query { block { ledgerParameters } }")
            .getJSONObject("block")
            .getString("ledgerParameters")

    /**
     * The chain tip's ledger parameters AND block time, in one indexer round-trip.
     *
     * Both feed contract-call/deploy assembly: the cost model sizes gas, and the block time
     * (Unix epoch MILLIS) anchors the intent TTL + the native gas-query context to CHAIN time
     * rather than the device wall-clock — wall-clock drift past the chain's `global_ttl` is what
     * the node rejects (custom error 182). See [ChainTip].
     */
    internal suspend fun fetchChainTip(): ChainTip =
        graphqlQuery("query { block { ledgerParameters timestamp } }")
            .getJSONObject("block")
            .let { block ->
                ChainTip(
                    ledgerParametersHex = block.getString("ledgerParameters"),
                    blockTimeMillis = block.getLong("timestamp"),
                )
            }

    /** Chain-tip values used to build a contract transaction against live chain state. */
    internal data class ChainTip(
        val ledgerParametersHex: String,
        /** Block timestamp, Unix epoch MILLIS (as the indexer reports it). */
        val blockTimeMillis: Long,
    )

    /**
     * Read a deployed contract's on-chain state as parsed JSON.
     *
     * Returns the state tree with cells decoded as hex, text (if UTF-8), and numbers.
     * Example for bboard: `[{"type":"cell","hex":"01...","number":1}, {"type":"cell","hex":"...","text":"Hello"},...]`
     *
     * @param address Contract address (64 hex chars)
     * @return Parsed state as a JSONArray or JSONObject
     */
    suspend fun queryState(address: String): org.json.JSONArray? = withContext(Dispatchers.IO) {
        val stateHex = fetchContractState(address)
        val handle = ContractRuntime.stateCreate(stateHex)
        if (handle == 0L) return@withContext null

        try {
            val json = ContractRuntime.stateReadFields(handle)
            if (json == null || json.startsWith("{\"error")) return@withContext null
            org.json.JSONArray(json)
        } finally {
            ContractRuntime.stateFree(handle)
        }
    }

    /** Submit a previously prepared transaction. */
    suspend fun submit(prepared: PreparedTransaction): TransactionReceipt = runTracked(prepared.circuitName) {
        val balancer = getBalancer()
        val start = System.currentTimeMillis()

        val balancedTxHex = balancer.balanceTransaction(prepared.provenTxHex)
        val balanceMs = System.currentTimeMillis() - start

        val submitStart = System.currentTimeMillis()
        balancer.submitTransaction(balancedTxHex)
        val submitMs = System.currentTimeMillis() - submitStart

        TransactionReceipt(
            txHash = null,
            status = TransactionStatus.SUBMITTED,
            timings = prepared.timings.copy(
                balanceMs = balanceMs,
                submitMs = submitMs,
            ),
            provenTxHex = prepared.provenTxHex,
        )
    }

    /** Close connections and release resources. */
    fun close() {
        connectorClient?.disconnect()
        connectorClient = null
    }

    private suspend fun graphqlQuery(query: String): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("$indexerUrl/graphql")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.outputStream.use { it.write("""{"query":"$query"}""".toByteArray()) }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().readText()
            if (responseCode != 200) {
                throw ContractCallException.StateFetchFailed(
                    "Indexer HTTP $responseCode: $body"
                )
            }

            val json = JSONObject(body)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                throw ContractCallException.StateFetchFailed("GraphQL errors: $errors")
            }

            json.getJSONObject("data")
        } catch (e: ContractCallException) {
            throw e
        } catch (e: Exception) {
            throw ContractCallException.StateFetchFailed(
                "Indexer query failed: ${e.message}", e
            )
        } finally {
            conn.disconnect()
        }
    }

    class Builder(private val context: Context) {
        private var indexerUrl: String? = null
        private var walletUrl: String? = null
        private var networkId: String? = null
        private var balancer: TransactionBalancer? = null
        private var operationListener: ContractOperationListener? = null
        private var blockSignals: Flow<Unit>? = null

        fun indexerUrl(url: String) = apply { this.indexerUrl = url }
        fun walletUrl(url: String) = apply { this.walletUrl = url }
        fun networkId(id: String) = apply { this.networkId = id }

        /** Provide an embedded [TransactionBalancer] instead of a remote wallet URL. */
        fun transactionBalancer(balancer: TransactionBalancer) = apply { this.balancer = balancer }

        /**
         * Wire a [ContractOperationListener] so every contract call/deploy through this
         * config runs as a tracked foreground operation (the SDK injects one). Optional —
         * configs without it behave exactly as before.
         */
        fun operationListener(listener: ContractOperationListener?) = apply { this.operationListener = listener }

        /**
         * Wire a chain block-tick [Flow] (emit once per new block) so [MidnightContract.observeLedger]
         * refreshes reactively instead of polling. The SDK injects one mapped from the indexer's
         * block subscription. Optional — without it observeLedger falls back to a poll.
         */
        fun blockSignals(signals: Flow<Unit>?) = apply { this.blockSignals = signals }

        fun build(): MidnightConfig {
            val indexer = requireNotNull(indexerUrl) { "indexerUrl is required" }
            val network = requireNotNull(networkId) { "networkId is required" }

            require(indexer.startsWith("http://") || indexer.startsWith("https://")) {
                "indexerUrl must start with http:// or https://"
            }

            // Must have either a TransactionBalancer or a walletUrl
            val wallet = walletUrl
            if (balancer == null) {
                requireNotNull(wallet) {
                    "Either transactionBalancer() or walletUrl() is required"
                }
                require(wallet.startsWith("ws://") || wallet.startsWith("wss://")) {
                    "walletUrl must start with ws:// or wss://"
                }
            }

            return MidnightConfig(
                context = context.applicationContext,
                indexerUrl = indexer,
                walletUrl = wallet,
                networkId = network,
                provingKeyManager = ProvingKeyManager(context.applicationContext),
                externalBalancer = balancer,
                operationListener = operationListener,
                blockSignals = blockSignals,
            )
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Poll cadence for [ledgerSignals] when no block subscription is wired. */
        private const val LEDGER_POLL_INTERVAL_MS = 5_000L

        /** Convenience factory for local development (Android emulator). */
        fun localDev(context: Context): MidnightConfig {
            val local = com.midnight.kuira.core.network.NetworkConfig.forNetwork(
                com.midnight.kuira.core.network.MidnightNetwork.UNDEPLOYED,
            )
            return Builder(context)
                .indexerUrl(local.indexerBaseUrl)
                .walletUrl("ws://10.0.2.2:9932")
                .networkId(com.midnight.kuira.core.network.MidnightNetwork.UNDEPLOYED.rustNetworkId)
                .build()
        }
    }
}
