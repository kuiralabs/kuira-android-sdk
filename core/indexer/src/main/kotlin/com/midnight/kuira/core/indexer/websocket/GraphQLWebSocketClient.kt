package com.midnight.kuira.core.indexer.websocket

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * GraphQL WebSocket client implementing graphql-transport-ws protocol.
 *
 * **Protocol:** graphql-transport-ws
 * **Spec:** https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md
 *
 * **Connection Lifecycle:**
 * 1. connect() - Establish WebSocket + send connection_init
 * 2. Wait for connection_ack
 * 3. subscribe() - Send GraphQL subscriptions
 * 4. Receive next/error/complete messages
 * 5. close() - Clean shutdown
 *
 * **Thread Safety:** All methods are thread-safe and can be called from any coroutine.
 *
 * @param httpClient Ktor HTTP client with WebSockets installed
 * @param url WebSocket URL (wss://...)
 * @param connectionTimeout Timeout for connection_ack (milliseconds)
 */
class GraphQLWebSocketClient(
    private val httpClient: HttpClient,
    private val url: String,
    private val connectionTimeout: Long = 10_000
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true  // CRITICAL: Always include fields with default values
    }

    private var session: DefaultClientWebSocketSession? = null
    private val connected = AtomicBoolean(false)
    private val operationIdCounter = AtomicInteger(0)
    private val activeSubscriptions = mutableMapOf<String, Channel<JsonElement>>()

    companion object {
        /**
         * Max subscription events buffered before backpressure suspends the producer.
         * Prevents OOM on high-volume subscriptions (PREPROD has 247k+ dust events).
         */
        private const val SUBSCRIPTION_CHANNEL_CAPACITY = 64
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Connect to WebSocket server.
     *
     * **Flow:**
     * 1. Open WebSocket connection
     * 2. Send connection_init
     * 3. Wait for connection_ack (with timeout)
     * 4. Start message processing loop
     *
     * @throws WebSocketException if connection fails
     * @throws TimeoutCancellationException if connection_ack not received in time
     */
    suspend fun connect() {
        if (connected.get()) {
            throw IllegalStateException("Already connected")
        }

        // Open WebSocket connection with graphql-transport-ws sub-protocol
        // The Sec-WebSocket-Protocol header is REQUIRED by the GraphQL-WS spec
        // See: https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md
        session = httpClient.webSocketSession(
            urlString = url,
            block = {
                header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
            }
        )

        // Send connection_init
        sendMessage(GraphQLWebSocketMessage.ConnectionInit())

        // Wait for connection_ack with timeout
        // IMPORTANT: Don't start a separate coroutine here - it would race with message processing!
        // Instead, read frames directly until we get connection_ack
        withTimeout(connectionTimeout) {
            for (frame in session!!.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        val message = parseMessage(text)
                        if (message is GraphQLWebSocketMessage.ConnectionAck) {
                            connected.set(true)
                            break  // Got ack, stop consuming and let message processing take over
                        }
                    }
                    else -> {
                        // Ignore other frame types during connection phase
                    }
                }
            }
        }

        // Start message processing loop - NOW it won't miss any messages
        startMessageProcessing()
    }

    /**
     * Subscribe to GraphQL operation.
     *
     * **Returns:** Flow of operation results (JsonElement)
     *
     * Each result is emitted as received from server.
     * Flow completes when server sends complete message.
     * Flow errors if server sends error message.
     *
     * **Example:**
     * ```kotlin
     * val query = """
     *   subscription {
     *     unshieldedTransactions(address: "mn_addr_testnet1...") {
     *       transaction { hash }
     *     }
     *   }
     * """
     * client.subscribe(query).collect { result ->
     *     println("Received: $result")
     * }
     * ```
     *
     * @param query GraphQL subscription query
     * @param variables Query variables (optional)
     * @param operationName Operation name (optional)
     * @return Flow of results
     * @throws IllegalStateException if not connected
     */
    fun subscribe(
        query: String,
        variables: Map<String, Any>? = null,
        operationName: String? = null
    ): Flow<JsonElement> = flow {
        if (!connected.get()) {
            throw IllegalStateException("Not connected. Call connect() first.")
        }

        val operationId = generateOperationId()
        val channel = Channel<JsonElement>(SUBSCRIPTION_CHANNEL_CAPACITY)
        activeSubscriptions[operationId] = channel
        android.util.Log.d("GraphQLWebSocket", "Starting subscription $operationId, active subs: ${activeSubscriptions.keys}")

        try {
            // Send subscribe message
            val payload = SubscribePayload(
                query = query,
                operationName = operationName,
                variables = variables?.let { varsMap ->
                    // Manually build JsonObject from variables map
                    // Variables are expected to be String values (or serializable primitives)
                    buildJsonObject {
                        varsMap.forEach { (key, value) ->
                            when (value) {
                                is String -> put(key, JsonPrimitive(value))
                                is Number -> put(key, JsonPrimitive(value))
                                is Boolean -> put(key, JsonPrimitive(value))
                                else -> put(key, JsonPrimitive(value.toString()))
                            }
                        }
                    }
                }
            )
            sendMessage(GraphQLWebSocketMessage.Subscribe(id = operationId, payload = payload))

            // Emit results from channel
            for (result in channel) {
                emit(result)
            }
        } finally {
            android.util.Log.d("GraphQLWebSocket", "Subscription $operationId Flow ended, cleaning up")
            activeSubscriptions.remove(operationId)
            sendMessage(GraphQLWebSocketMessage.Complete(id = operationId))
        }
    }

    /**
     * Close connection gracefully.
     *
     * Completes all active subscriptions and closes WebSocket.
     */
    suspend fun close() {
        if (!connected.get()) return

        // Complete all active subscriptions
        activeSubscriptions.values.forEach { it.close() }
        activeSubscriptions.clear()

        // Close WebSocket
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client closing"))
        session = null
        connected.set(false)
        scope.cancel()
    }

    /**
     * Send ping message (keep-alive).
     */
    suspend fun ping() {
        sendMessage(GraphQLWebSocketMessage.Ping())
    }

    // ==================== PRIVATE ====================

    private fun generateOperationId(): String {
        return "sub_${operationIdCounter.incrementAndGet()}"
    }

    private suspend fun sendMessage(message: GraphQLWebSocketMessage) {
        val json = when (message) {
            is GraphQLWebSocketMessage.ConnectionInit -> json.encodeToString(message)
            is GraphQLWebSocketMessage.Subscribe -> json.encodeToString(message)
            is GraphQLWebSocketMessage.Complete -> json.encodeToString(message)
            is GraphQLWebSocketMessage.Ping -> json.encodeToString(message)
            is GraphQLWebSocketMessage.Pong -> json.encodeToString(message)
            else -> throw IllegalArgumentException("Cannot send message type: ${message.type}")
        }
        // Verbose logging removed - enable for debugging: Log.d("GraphQLWebSocket", "⬆️ Sending: ${json.take(200)}")
        session?.send(Frame.Text(json))
    }

    private fun parseMessage(text: String): GraphQLWebSocketMessage {
        // Verbose logging removed - enable for debugging: Log.d("GraphQLWebSocket", "⬇️ Received: ${text.take(200)}")

        // Parse type field first
        val jsonElement = json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject
        val type = jsonElement["type"]?.toString()?.trim('"') ?: throw IllegalArgumentException("Missing type field")

        return when (type) {
            "connection_ack" -> json.decodeFromString<GraphQLWebSocketMessage.ConnectionAck>(text)
            "next" -> json.decodeFromString<GraphQLWebSocketMessage.Next>(text)
            "error" -> json.decodeFromString<GraphQLWebSocketMessage.Error>(text)
            "complete" -> json.decodeFromString<GraphQLWebSocketMessage.Complete>(text)
            "ping" -> json.decodeFromString<GraphQLWebSocketMessage.Ping>(text)
            "pong" -> json.decodeFromString<GraphQLWebSocketMessage.Pong>(text)
            else -> throw IllegalArgumentException("Unknown message type: $type")
        }
    }

    private fun startMessageProcessing() {
        scope.launch {
            try {
                session?.incoming?.consumeAsFlow()?.collect { frame ->
                    if (frame is Frame.Text) {
                        val message = parseMessage(frame.readText())
                        handleMessage(message)
                    }
                }
            } catch (e: Exception) {
                // Connection closed or error
                connected.set(false)
                activeSubscriptions.values.forEach { it.close(e) }
                activeSubscriptions.clear()
            }
        }
    }

    private suspend fun handleMessage(message: GraphQLWebSocketMessage) {
        when (message) {
            is GraphQLWebSocketMessage.Next -> {
                val channel = activeSubscriptions[message.id]
                if (channel != null) {
                    channel.send(message.payload)
                } else {
                    android.util.Log.w("GraphQLWebSocket", "No active subscription found for ${message.id}")
                }
            }
            is GraphQLWebSocketMessage.Error -> {
                val error = WebSocketSubscriptionException(
                    operationId = message.id,
                    errors = message.payload
                )
                activeSubscriptions[message.id]?.close(error)
                activeSubscriptions.remove(message.id)
            }
            is GraphQLWebSocketMessage.Complete -> {
                android.util.Log.d("GraphQLWebSocket", "Subscription ${message.id} completed by server, active subs: ${activeSubscriptions.keys}")
                activeSubscriptions[message.id]?.close()
                activeSubscriptions.remove(message.id)
            }
            is GraphQLWebSocketMessage.Ping -> {
                sendMessage(GraphQLWebSocketMessage.Pong())
            }
            else -> {
                // Ignore other message types (connection_ack, pong)
            }
        }
    }
}

/**
 * Exception thrown when subscription receives error from server.
 */
class WebSocketSubscriptionException(
    val operationId: String,
    val errors: List<GraphQLError>
) : Exception("GraphQL subscription error for operation $operationId: ${errors.joinToString { it.message }}")
