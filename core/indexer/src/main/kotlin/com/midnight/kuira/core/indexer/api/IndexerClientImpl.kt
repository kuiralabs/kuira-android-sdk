package com.midnight.kuira.core.indexer.api

import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.model.DustSyncResult
import com.midnight.kuira.core.indexer.model.NetworkState
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import com.midnight.kuira.core.indexer.websocket.GraphQLWebSocketClient
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.util.concurrent.TimeUnit
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Ktor-based implementation of IndexerClient for the Midnight indexer GraphQL API.
 *
 * **Connection:**
 * - HTTPS endpoint: `{baseUrl}/graphql` (with TLS certificate pinning)
 * - WebSocket endpoint: `{baseUrl}/graphql/ws`
 *
 * **GraphQL Protocol:**
 * - Uses HTTPS POST for queries/mutations
 * - Uses WebSocket for subscriptions (graphql-ws protocol)
 *
 * **Security:**
 * - TLS certificate pinning enabled for production
 * - Development mode allows localhost HTTP (for local testing)
 *
 * @param httpClient HTTP client for making requests (injectable for testing)
 * @param baseUrl Indexer API base URL. Typical values come from
 *   [NetworkConfig.forNetwork(...).indexerBaseUrl][NetworkConfig.forNetwork].
 * @param pinnedCertificates List of SHA-256 certificate fingerprints for pinning (production only)
 * @param developmentMode If true, allows HTTP to localhost (INSECURE - testing only)
 */
class IndexerClientImpl(
    private val httpClient: HttpClient,
    private val baseUrl: String = NetworkConfig.forNetwork(MidnightNetwork.PREVIEW).indexerBaseUrl,
    private val pinnedCertificates: List<String> = emptyList(),
    private val developmentMode: Boolean = false
) : IndexerClient {

    /**
     * Convenience constructor for production use (creates default HTTP client).
     */
    constructor(
        baseUrl: String = NetworkConfig.forNetwork(MidnightNetwork.PREVIEW).indexerBaseUrl,
        pinnedCertificates: List<String> = emptyList(),
        developmentMode: Boolean = false
    ) : this(
        httpClient = createDefaultHttpClient(),
        baseUrl = baseUrl,
        pinnedCertificates = pinnedCertificates,
        developmentMode = developmentMode
    )

    companion object {
        /** Timeout for delta dust sync. Events arrive within ~50ms local, ~200ms remote. */
        private const val DELTA_SYNC_TIMEOUT_MS = 500L
        private fun createDefaultHttpClient() = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }
            install(WebSockets)

            // Keep subscription WebSockets alive. Ktor's plugin-level pingInterval
            // is a no-op on the OkHttp engine (KTOR-4752), so set OkHttp's own
            // ping: RFC6455 ping frames every 20s stop the indexer's subscriptions
            // from being dropped at the ~60s idle timeout (which otherwise causes
            // the subscribe → error → reconnect flapping seen in logcat).
            engine { config { pingInterval(20, TimeUnit.SECONDS) } }

            // Timeout configuration
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000 // 30 seconds
                connectTimeoutMillis = 10_000 // 10 seconds
                socketTimeoutMillis = 30_000 // 30 seconds
            }

            // Response validation (catch non-2xx responses)
            expectSuccess = true

            // TLS/SSL: OkHttp uses Android's native TLS stack automatically.
            // Certificate pinning can be added via engine { config { certificatePinner(...) } }
        }
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        classDiscriminator = "__typename"  // Use GraphQL's __typename field for polymorphism
    }

    init {
        // Validate configuration
        if (!developmentMode && !baseUrl.startsWith("https://")) {
            throw IllegalArgumentException(
                "Production mode requires HTTPS. Got: $baseUrl\n" +
                "Use developmentMode=true for HTTP testing (INSECURE)"
            )
        }

        if (developmentMode && pinnedCertificates.isNotEmpty()) {
            throw IllegalArgumentException(
                "Certificate pinning not supported in development mode"
            )
        }
    }

    private val graphqlEndpoint = "$baseUrl/graphql"
    private val wsEndpoint = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/graphql/ws"

    /**
     * WebSocket client for subscriptions.
     * Lazily initialized when first subscription is created.
     */
    private var wsClient: GraphQLWebSocketClient? = null

    /**
     * Get or create WebSocket client.
     */
    private fun getOrCreateWsClient(): GraphQLWebSocketClient {
        if (wsClient == null) {
            wsClient = GraphQLWebSocketClient(
                url = wsEndpoint,
                httpClient = httpClient
            )
        }
        return wsClient!!
    }

    /**
     * GraphQL request payload.
     */
    @Serializable
    private data class GraphQLRequest(
        val query: String,
        val variables: Map<String, String>? = null
    )

    // ==================== UTXO TRACKING ====================

    override fun subscribeToUnshieldedTransactions(
        address: String,
        transactionId: Int?
    ): Flow<UnshieldedTransactionUpdate> {
        // Build variables
        val variables = buildMap {
            put("address", address)
            if (transactionId != null) {
                put("transactionId", transactionId)
            }
        }

        // Subscribe using centralized query with auto-connect
        return flow {
            val client = getOrCreateWsClient()

            // connect() is idempotent + mutex-serialized inside GraphQLWebSocketClient,
            // so concurrent subscribers can all call it safely — only the first
            // performs the handshake; the rest observe `connected == true` and return.
            client.connect()

            // Now subscribe and emit all updates
            // IMPORTANT: buffer() prevents message loss when rapid transactions arrive
            // Without buffering, if processing TX N is slow, TX N+1 might be dropped
            client.subscribe(GraphQLQueries.SUBSCRIBE_UNSHIELDED_TRANSACTIONS, variables)
                .buffer(Channel.UNLIMITED)
                .collect { jsonElement ->
                    // GraphQL response format: {data: {unshieldedTransactions: {...}}}
                    // The indexer may send messages with "data": null (e.g. keepalives,
                    // completion signals, or errors). Skip these gracefully.
                    val dataElement = jsonElement.jsonObject["data"]
                    if (dataElement == null || dataElement is kotlinx.serialization.json.JsonNull) {
                        return@collect // Skip null data messages
                    }

                    val unshieldedTransactionsJson = dataElement
                        .jsonObject["unshieldedTransactions"]
                        ?: throw InvalidResponseException("Missing unshieldedTransactions in response")

                    // Parse to UnshieldedTransactionUpdate
                    val update = json.decodeFromJsonElement(
                        UnshieldedTransactionUpdate.serializer(),
                        unshieldedTransactionsJson
                    )
                    emit(update)
                }
        }
    }

    // ==================== DUST EVENTS (Subscription) ====================

    override fun subscribeToDustEvents(fromId: Long?): Flow<RawLedgerEvent> {
        // Always pass id explicitly. The indexer's dustLedgerEvents subscription
        // treats null/missing id differently from id=0: null may start from the
        // latest event, while 0 starts from genesis. Use 0 for full sync.
        val variables = mapOf("id" to (fromId ?: 0L))

        return flow {
            val client = getOrCreateWsClient()
            client.connect()  // idempotent + mutex-serialized

            client.subscribe(GraphQLQueries.SUBSCRIBE_DUST_LEDGER_EVENTS, variables)
                .buffer(Channel.UNLIMITED)
                .collect { jsonElement ->
                    val dataElement = jsonElement.jsonObject["data"]
                    if (dataElement == null || dataElement is kotlinx.serialization.json.JsonNull) {
                        return@collect
                    }

                    val dustEventJson = dataElement
                        .jsonObject["dustLedgerEvents"]
                        ?: throw InvalidResponseException("Missing dustLedgerEvents in response")

                    val eventObj = dustEventJson.jsonObject
                    val id = eventObj["id"]?.jsonPrimitive?.long
                        ?: throw InvalidResponseException("Dust event missing 'id' field")
                    val rawHex = eventObj["raw"]?.jsonPrimitive?.content
                        ?: throw InvalidResponseException("Dust event missing 'raw' field")
                    val maxId = eventObj["maxId"]?.jsonPrimitive?.long
                        ?: throw InvalidResponseException("Dust event missing 'maxId' field")

                    emit(RawLedgerEvent(id = id, rawHex = rawHex, maxId = maxId))
                }
        }
    }

    // ==================== SYNC ENGINE ====================

    override fun subscribeToZswapEvents(fromId: Long?): Flow<RawLedgerEvent> {
        val variables = buildMap<String, Any> {
            if (fromId != null) {
                put("id", fromId)
            }
        }

        return flow {
            val client = getOrCreateWsClient()
            client.connect()  // idempotent + mutex-serialized

            client.subscribe(GraphQLQueries.SUBSCRIBE_ZSWAP_LEDGER_EVENTS, variables)
                .buffer(Channel.UNLIMITED)
                .collect { jsonElement ->
                    val dataElement = jsonElement.jsonObject["data"]
                    if (dataElement == null || dataElement is kotlinx.serialization.json.JsonNull) {
                        return@collect
                    }

                    val zswapEventJson = dataElement
                        .jsonObject["zswapLedgerEvents"]
                        ?: throw InvalidResponseException("Missing zswapLedgerEvents in response")

                    val eventObj = zswapEventJson.jsonObject
                    val id = eventObj["id"]?.jsonPrimitive?.long
                        ?: throw InvalidResponseException("Zswap event missing 'id' field")
                    val rawHex = eventObj["raw"]?.jsonPrimitive?.content
                        ?: throw InvalidResponseException("Zswap event missing 'raw' field")
                    val maxId = eventObj["maxId"]?.jsonPrimitive?.long
                        ?: throw InvalidResponseException("Zswap event missing 'maxId' field")

                    emit(RawLedgerEvent(id = id, rawHex = rawHex, maxId = maxId))
                }
        }
    }

    override fun subscribeToBlocks(): Flow<BlockInfo> = flow {
        // Mirrors subscribeToUnshieldedTransactions / subscribeToDustEvents: open (idempotent,
        // mutex-serialized) WS, subscribe via the centralized query, parse each payload to BlockInfo.
        val client = getOrCreateWsClient()
        client.connect()
        client.subscribe(GraphQLQueries.SUBSCRIBE_BLOCKS)
            .buffer(Channel.UNLIMITED)
            .collect { jsonElement ->
                // The indexer may send "data": null (keepalives / completion); skip gracefully.
                val data = jsonElement.jsonObject["data"]
                if (data == null || data is kotlinx.serialization.json.JsonNull) return@collect
                parseBlockInfo(data.jsonObject)?.let { emit(it) }
            }
    }

    override suspend fun getNetworkState(): NetworkState = retryWithPolicy() {
        try {
            val response = httpClient.post(graphqlEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(GraphQLQueries.QUERY_NETWORK_STATE))
            }

            val responseBody = response.bodyAsText()
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

            // Check for GraphQL errors
            val errors = jsonResponse["errors"]?.jsonArray
            if (errors != null && errors.isNotEmpty()) {
                val errorMessages = errors.map { it.jsonObject["message"]?.jsonPrimitive?.content ?: "Unknown error" }
                throw GraphQLException(errorMessages, "GraphQL errors: ${errorMessages.joinToString()}")
            }

            val data = jsonResponse["data"]?.jsonObject
                ?: throw InvalidResponseException("Missing 'data' field in response")

            val networkState = data["networkState"]?.jsonObject
                ?: throw InvalidResponseException("Missing 'networkState' field in response")

            val currentBlock = networkState["currentBlock"]?.jsonPrimitive?.long
                ?: throw InvalidResponseException("Missing 'currentBlock' field")
            val maxBlock = networkState["maxBlock"]?.jsonPrimitive?.long
                ?: throw InvalidResponseException("Missing 'maxBlock' field")

            // Validate values
            if (currentBlock < 0 || maxBlock < 0) {
                throw InvalidResponseException("Invalid block heights: current=$currentBlock, max=$maxBlock")
            }
            if (currentBlock > maxBlock) {
                throw InvalidResponseException("Current block ($currentBlock) > max block ($maxBlock)")
            }

            NetworkState.fromBlockHeights(currentBlock, maxBlock)
        } catch (e: ResponseException) {
            throw HttpException(e.response.status.value, "HTTP error: ${e.message}", e)
        } catch (e: HttpRequestTimeoutException) {
            throw TimeoutException("Request timeout while fetching network state", e)
        } catch (e: java.net.UnknownHostException) {
            throw NetworkException("DNS resolution failed for $graphqlEndpoint", e)
        } catch (e: java.net.ConnectException) {
            throw NetworkException("Connection failed to $graphqlEndpoint", e)
        } catch (e: java.io.IOException) {
            throw NetworkException("Network I/O error: ${e.message}", e)
        } catch (e: IndexerException) {
            throw e // Re-throw our custom exceptions
        } catch (e: Exception) {
            throw InvalidResponseException("Unexpected error: ${e.message}", e)
        }
    }

    override suspend fun getCurrentBlockWithParams(): BlockInfo = retryWithPolicy() {
        try {
            val response = httpClient.post(graphqlEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(GraphQLQueries.QUERY_CURRENT_BLOCK))
            }

            val responseBody = response.bodyAsText()
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

            // Check for GraphQL errors
            val errors = jsonResponse["errors"]?.jsonArray
            if (errors != null && errors.isNotEmpty()) {
                val errorMessages = errors.map { it.jsonObject["message"]?.jsonPrimitive?.content ?: "Unknown error" }
                throw GraphQLException(errorMessages, "GraphQL errors: ${errorMessages.joinToString()}")
            }

            val data = jsonResponse["data"]?.jsonObject
                ?: throw InvalidResponseException("Missing 'data' field in response")

            val block = data["block"]?.jsonObject
                ?: throw InvalidResponseException("Missing 'block' field in response")

            val height = block["height"]?.jsonPrimitive?.long
                ?: throw InvalidResponseException("Missing 'height' field")
            val hash = block["hash"]?.jsonPrimitive?.content
                ?: throw InvalidResponseException("Missing 'hash' field")
            val ledgerParameters = block["ledgerParameters"]?.jsonPrimitive?.content
                ?: throw InvalidResponseException("Missing 'ledgerParameters' field")
            val timestamp = block["timestamp"]?.jsonPrimitive?.long
                ?: throw InvalidResponseException("Missing 'timestamp' field")

            BlockInfo(
                height = height,
                hash = hash,
                timestamp = timestamp,
                eventCount = 0,
                ledgerParameters = ledgerParameters
            )
        } catch (e: ResponseException) {
            throw HttpException(e.response.status.value, "HTTP error: ${e.message}", e)
        } catch (e: HttpRequestTimeoutException) {
            throw TimeoutException("Request timeout while fetching current block", e)
        } catch (e: java.net.UnknownHostException) {
            throw NetworkException("DNS resolution failed for $graphqlEndpoint", e)
        } catch (e: java.net.ConnectException) {
            throw NetworkException("Connection failed to $graphqlEndpoint", e)
        } catch (e: java.io.IOException) {
            throw NetworkException("Network I/O error: ${e.message}", e)
        } catch (e: IndexerException) {
            throw e // Re-throw our custom exceptions
        } catch (e: Exception) {
            throw InvalidResponseException("Unexpected error: ${e.message}", e)
        }
    }

    /**
     * Block hash at [height] — the chain-reset checkpoint lookup. FAILURE-TOLERANT: any query error
     * (network, GraphQL, parse) is [BlockHashLookup.Unavailable] rather than a throw, so the reset
     * check SKIPS on a transient hiccup instead of risking a wipe of a healthy wallet. A VALID
     * response whose `block` field is null (the chain is shorter than the checkpoint) is
     * [BlockHashLookup.NotOnChain] — kept distinct from Unavailable so the guard can treat the two
     * opposite meanings differently. CancellationException is rethrown so cancellation isn't swallowed.
     */
    override suspend fun getBlockHashAtHeight(height: Long): BlockHashLookup {
        return try {
            val response = httpClient.post(graphqlEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(GraphQLQueries.queryBlockAtHeight(height)))
            }
            val jsonResponse = json.parseToJsonElement(response.bodyAsText()).jsonObject
            // GraphQL errors → Unavailable ("can't determine", never treated as a reset).
            val errors = jsonResponse["errors"]?.jsonArray
            if (errors != null && errors.isNotEmpty()) return BlockHashLookup.Unavailable
            val data = jsonResponse["data"]?.jsonObject
                ?: return BlockHashLookup.Unavailable
            // A present `data` with a null `block` is a VALID "no block at that height" → the chain
            // is shorter than the checkpoint → NotOnChain (the reset signal), NOT Unavailable.
            val block = data["block"]
            if (block == null || block is JsonNull) return BlockHashLookup.NotOnChain
            val hash = block.jsonObject["hash"]?.jsonPrimitive?.content
            // A blank hash can't be compared — treat as Unavailable, never a false reset.
            if (hash.isNullOrBlank()) BlockHashLookup.Unavailable else BlockHashLookup.Found(hash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BlockHashLookup.Unavailable
        }
    }

    override suspend fun getEventsInRange(fromId: Long, toId: Long): List<RawLedgerEvent> = retryWithPolicy() {
        // Validate input parameters
        if (fromId < 0) {
            throw IllegalArgumentException("fromId must be non-negative, got: $fromId")
        }
        if (toId < fromId) {
            throw IllegalArgumentException("toId ($toId) must be >= fromId ($fromId)")
        }

        try {
            val response = httpClient.post(graphqlEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(
                    query = GraphQLQueries.QUERY_ZSWAP_EVENTS,
                    variables = mapOf(
                        "fromId" to fromId.toString(),
                        "toId" to toId.toString()
                    )
                ))
            }

            val responseBody = response.bodyAsText()
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

            // Check for GraphQL errors
            val errors = jsonResponse["errors"]?.jsonArray
            if (errors != null && errors.isNotEmpty()) {
                val errorMessages = errors.map { it.jsonObject["message"]?.jsonPrimitive?.content ?: "Unknown error" }
                throw GraphQLException(errorMessages, "GraphQL errors: ${errorMessages.joinToString()}")
            }

            val data = jsonResponse["data"]?.jsonObject
                ?: throw InvalidResponseException("Missing 'data' field in response")

            val events = data["zswapLedgerEvents"]?.jsonArray
                ?: return@retryWithPolicy emptyList()

            events.map { eventJson ->
                val event = eventJson.jsonObject
                val id = event["id"]?.jsonPrimitive?.long
                    ?: throw InvalidResponseException("Event missing 'id' field")
                val rawHex = event["raw"]?.jsonPrimitive?.content
                    ?: throw InvalidResponseException("Event missing 'raw' field")
                val maxId = event["maxId"]?.jsonPrimitive?.long
                    ?: throw InvalidResponseException("Event missing 'maxId' field")

                // Validate event data
                if (id < 0) {
                    throw InvalidResponseException("Invalid event ID: $id")
                }
                if (rawHex.isBlank()) {
                    throw InvalidResponseException("Event $id has empty raw hex")
                }
                if (maxId < 0) {
                    throw InvalidResponseException("Invalid maxId: $maxId")
                }

                RawLedgerEvent(id = id, rawHex = rawHex, maxId = maxId)
            }
        } catch (e: ResponseException) {
            throw HttpException(e.response.status.value, "HTTP error: ${e.message}", e)
        } catch (e: HttpRequestTimeoutException) {
            throw TimeoutException("Request timeout while fetching events [$fromId-$toId]", e)
        } catch (e: java.net.UnknownHostException) {
            throw NetworkException("DNS resolution failed for $graphqlEndpoint", e)
        } catch (e: java.net.ConnectException) {
            throw NetworkException("Connection failed to $graphqlEndpoint", e)
        } catch (e: java.io.IOException) {
            throw NetworkException("Network I/O error: ${e.message}", e)
        } catch (e: IndexerException) {
            throw e // Re-throw our custom exceptions
        } catch (e: Exception) {
            throw InvalidResponseException("Unexpected error: ${e.message}", e)
        }
    }

    override suspend fun queryDustEvents(maxBlocks: Int): String = try {
        println("\n🔍 Querying dust events via WebSocket subscription...")

        var eventCount = 0
        val allEvents = subscribeToDustEvents(fromId = null)
            .onEach { event ->
                eventCount++
                if (eventCount % 500 == 0) {
                    println("   Progress: $eventCount events received (${event.id}/${event.maxId})")
                }
            }
            .transformWhile { event ->
                emit(event)
                event.id < event.maxId // stop after catching up to chain tip
            }
            .toList()

        if (allEvents.isEmpty()) {
            println("⚠️  No dust events found")
            ""
        } else {
            // Sort by ID (subscription should deliver in order, but be safe)
            val sorted = allEvents.sortedBy { it.id }
            val combinedHex = sorted.joinToString("") { it.rawHex }

            println("✅ Found ${sorted.size} dust events via subscription")
            println("   Event IDs: ${sorted.first().id}..${sorted.last().id}")
            println("   Total hex length: ${combinedHex.length} chars")

            combinedHex
        }
    } catch (e: Exception) {
        throw InvalidResponseException("Failed to query dust events: ${e.message}", e)
    }

    override suspend fun queryDustEventsDelta(fromId: Long, timeoutMs: Long): DustSyncResult = try {
        val events = withTimeoutOrNull(timeoutMs) {
            subscribeToDustEvents(fromId = fromId)
                .transformWhile { event ->
                    emit(event)
                    event.id < event.maxId
                }
                .toList()
        }

        if (events.isNullOrEmpty()) {
            DustSyncResult(
                eventsHex = "",
                lastEventId = fromId - 1,
                eventCount = 0,
            )
        } else {
            val sorted = events.sortedBy { it.id }
            DustSyncResult(
                eventsHex = sorted.joinToString("") { it.rawHex },
                lastEventId = sorted.last().id,
                eventCount = sorted.size,
            )
        }
    } catch (e: Exception) {
        throw InvalidResponseException("Failed to query dust events delta from $fromId: ${e.message}", e)
    }

    override suspend fun queryZswapEvents(): String = try {
        val allEvents = subscribeToZswapEvents(fromId = null)
            .transformWhile { event ->
                emit(event)
                event.id < event.maxId
            }
            .toList()

        if (allEvents.isEmpty()) {
            ""
        } else {
            allEvents.sortedBy { it.id }.joinToString("") { it.rawHex }
        }
    } catch (e: Exception) {
        throw InvalidResponseException("Failed to query zswap events: ${e.message}", e)
    }

    // ==================== CONTRACT STATE ====================

    override suspend fun queryContractState(address: String): String {
        require(address.length == 64 && address.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Contract address must be 64 hex characters, got: ${address.take(20)}..."
        }

        try {
            val response = httpClient.post(graphqlEndpoint) {
                contentType(ContentType.Application.Json)
                setBody(GraphQLRequest(
                    query = GraphQLQueries.QUERY_CONTRACT_STATE,
                    variables = mapOf("address" to address),
                ))
            }

            val responseBody = response.bodyAsText()
            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject

            val errors = jsonResponse["errors"]?.jsonArray
            if (errors != null && errors.isNotEmpty()) {
                val errorMessages = errors.map { it.jsonObject["message"]?.jsonPrimitive?.content ?: "Unknown error" }
                throw GraphQLException(errorMessages, "GraphQL errors: ${errorMessages.joinToString()}")
            }

            val data = jsonResponse["data"]?.jsonObject
                ?: throw InvalidResponseException("Missing 'data' field in response")

            val contractAction = data["contractAction"]?.jsonObject
                ?: throw InvalidResponseException("Contract not found at address: $address")

            return contractAction["state"]?.jsonPrimitive?.content
                ?: throw InvalidResponseException("Missing 'state' field in contractAction response")
        } catch (e: ResponseException) {
            throw HttpException(e.response.status.value, "HTTP error: ${e.message}", e)
        } catch (e: HttpRequestTimeoutException) {
            throw TimeoutException("Request timeout while fetching contract state", e)
        } catch (e: java.net.UnknownHostException) {
            throw NetworkException("DNS resolution failed for $graphqlEndpoint", e)
        } catch (e: java.net.ConnectException) {
            throw NetworkException("Connection failed to $graphqlEndpoint", e)
        } catch (e: java.io.IOException) {
            throw NetworkException("Network I/O error: ${e.message}", e)
        } catch (e: IndexerException) {
            throw e
        } catch (e: Exception) {
            throw InvalidResponseException("Unexpected error: ${e.message}", e)
        }
    }

    override suspend fun isHealthy(): Boolean {
        return try {
            // Try to get network state with a short timeout
            val response = httpClient.get(graphqlEndpoint) {
                timeout {
                    requestTimeoutMillis = 5_000 // 5 second timeout for health check
                }
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            // Health check failed - indexer is not reachable
            false
        }
    }

    override fun close() {
        // Close HTTP client (WebSocket connections will be closed automatically)
        httpClient.close()

        // Note: WebSocket client cleanup happens automatically when httpClient closes
        // since they share the same underlying client.
        // To explicitly close: launch { wsClient?.close() } in a coroutine scope
    }

    override suspend fun resetConnection() {
        // Close current WebSocket connection and all active subscriptions
        wsClient?.close()
        wsClient = null  // Force new connection on next subscription
    }
}

/**
 * Parse a `blocks` subscription payload's `data` object into [BlockInfo] (the WS round-trip itself
 * needs a live indexer to verify, so the parse is split out and unit-tested). Returns null on a
 * malformed/partial payload so [IndexerClientImpl.subscribeToBlocks] skips it rather than crashing.
 * `eventCount`/`ledgerParameters` aren't selected by [GraphQLQueries.SUBSCRIBE_BLOCKS], so they
 * default (0 / null).
 */
internal fun parseBlockInfo(data: JsonObject): BlockInfo? {
    val block = data["blocks"]?.jsonObject ?: return null
    val height = block["height"]?.jsonPrimitive?.long ?: return null
    val hash = block["hash"]?.jsonPrimitive?.content ?: return null
    val timestamp = block["timestamp"]?.jsonPrimitive?.long ?: return null
    return BlockInfo(height = height, hash = hash, timestamp = timestamp)
}
