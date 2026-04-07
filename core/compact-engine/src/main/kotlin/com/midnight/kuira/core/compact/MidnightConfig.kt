package com.midnight.kuira.core.compact

import android.content.Context
import com.midnight.kuira.core.crypto.proving.ProvingKeyManager
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
 * Manages indexer connectivity, DApp Connector connection, and proving.
 *
 * ```kotlin
 * val config = MidnightConfig.Builder(context)
 *     .indexerUrl("https://indexer.preview.midnight.network/api/v3")
 *     .walletUrl("ws://10.0.2.2:9932")
 *     .networkId("preview")
 *     .build()
 * ```
 */
class MidnightConfig private constructor(
    val context: Context,
    val indexerUrl: String,
    val walletUrl: String,
    val networkId: String,
    val provingKeyManager: ProvingKeyManager,
) {
    internal val executor = CircuitExecutor(context)
    internal val proofProvider: ProofProvider = LocalProofProvider(provingKeyManager)

    private var connector: DAppConnectorClient? = null
    private val connectorMutex = Mutex()

    /** Get or create a DApp Connector connection (reused across calls). */
    internal suspend fun getConnector(): DAppConnectorClient = connectorMutex.withLock {
        connector?.let { return@withLock it }

        val client = DAppConnectorClient(walletUrl)
        client.connect()
        connector = client
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

    /** Submit a previously prepared transaction. */
    suspend fun submit(prepared: PreparedTransaction): TransactionReceipt {
        val connector = getConnector()
        val start = System.currentTimeMillis()

        val balancedTxHex = connector.balanceTransaction(prepared.provenTxHex)
        val balanceMs = System.currentTimeMillis() - start

        val submitStart = System.currentTimeMillis()
        connector.submitTransaction(balancedTxHex)
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
        connector?.disconnect()
        connector = null
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

        fun indexerUrl(url: String) = apply { this.indexerUrl = url }
        fun walletUrl(url: String) = apply { this.walletUrl = url }
        fun networkId(id: String) = apply { this.networkId = id }

        fun build(): MidnightConfig {
            val indexer = requireNotNull(indexerUrl) { "indexerUrl is required" }
            val wallet = requireNotNull(walletUrl) { "walletUrl is required" }
            val network = requireNotNull(networkId) { "networkId is required" }

            require(indexer.startsWith("http://") || indexer.startsWith("https://")) {
                "indexerUrl must start with http:// or https://"
            }
            require(wallet.startsWith("ws://") || wallet.startsWith("wss://")) {
                "walletUrl must start with ws:// or wss://"
            }

            return MidnightConfig(
                context = context.applicationContext,
                indexerUrl = indexer,
                walletUrl = wallet,
                networkId = network,
                provingKeyManager = ProvingKeyManager(context.applicationContext),
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
