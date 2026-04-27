package com.midnight.kuira.core.compact

import android.content.Context
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import kotlinx.coroutines.Dispatchers
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
 * // Remote wallet (existing pattern)
 * val config = MidnightConfig.Builder(context)
 *     .indexerUrl("https://indexer.preview.midnight.network/api/v3")
 *     .walletUrl("ws://10.0.2.2:9932")
 *     .networkId("preview")
 *     .build()
 *
 * // Embedded wallet (new pattern)
 * val config = MidnightConfig.Builder(context)
 *     .indexerUrl("https://indexer.preview.midnight.network/api/v3")
 *     .transactionBalancer(myWallet)
 *     .networkId("preview")
 *     .build()
 * ```
 */
class MidnightConfig private constructor(
    val context: Context,
    val indexerUrl: String,
    private val walletUrl: String?,
    val networkId: String,
    val provingKeyManager: ProvingKeyManager,
    private val externalBalancer: TransactionBalancer?,
) {
    internal val executor = CircuitExecutor(context)
    internal val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)

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
     * Read a deployed contract's on-chain state as parsed JSON.
     *
     * Returns the state tree with cells decoded as hex, text (if UTF-8), and numbers.
     * Example for bboard: `[{"type":"cell","hex":"01...","number":1}, {"type":"cell","hex":"...","text":"Hello"}, ...]`
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
    suspend fun submit(prepared: PreparedTransaction): TransactionReceipt {
        val balancer = getBalancer()
        val start = System.currentTimeMillis()

        val balancedTxHex = balancer.balanceTransaction(prepared.provenTxHex)
        val balanceMs = System.currentTimeMillis() - start

        val submitStart = System.currentTimeMillis()
        balancer.submitTransaction(balancedTxHex)
        val submitMs = System.currentTimeMillis() - submitStart

        return TransactionReceipt(
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

        fun indexerUrl(url: String) = apply { this.indexerUrl = url }
        fun walletUrl(url: String) = apply { this.walletUrl = url }
        fun networkId(id: String) = apply { this.networkId = id }

        /** Provide an embedded [TransactionBalancer] instead of a remote wallet URL. */
        fun transactionBalancer(balancer: TransactionBalancer) = apply { this.balancer = balancer }

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
            )
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000

        /** Convenience factory for local development (Android emulator). */
        fun localDev(context: Context): MidnightConfig = Builder(context)
            .indexerUrl("http://10.0.2.2:8088/api/v3")
            .walletUrl("ws://10.0.2.2:9932")
            .networkId("undeployed")
            .build()
    }
}
