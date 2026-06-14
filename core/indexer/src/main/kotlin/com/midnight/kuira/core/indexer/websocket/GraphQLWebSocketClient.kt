package com.midnight.kuira.core.indexer.websocket

import android.util.Log
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.ConcurrentHashMap
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
    // Concurrent: mutated from the subscribe() flow's collector coroutine, the
    // message-processing loop (Dispatchers.IO), and close() — potentially on
    // different threads. A plain mutableMapOf would race (lost puts / CME / a
    // spurious "subscription not found" miss).
    private val activeSubscriptions = ConcurrentHashMap<String, Channel<JsonElement>>()

    /**
     * Guards [connect] and [close] against concurrent callers.
     *
     * Without this lock, two coroutines that both observe `connected.get() == false`
     * race past the early-return and each opens its own WebSocket session — the
     * second `subscribe()` then races with `connected.set(true)` and may see the
     * window where the session exists but the ack hasn't arrived, surfacing as
     * `IllegalStateException("Not connected. Call connect() first.")`.
     *
     * With this lock, the second connect() suspends on the mutex while the first
     * completes its connection_init → connection_ack handshake, then wakes up,
     * observes `connected == true`, and returns silently. One handshake, no race.
     */
    private val lifecycleMutex = Mutex()

    companion object {
        /**
         * Max subscription events buffered before backpressure suspends the producer.
         * Prevents OOM on high-volume subscriptions (PREPROD has 247k+ dust events).
         */
        private const val SUBSCRIPTION_CHANNEL_CAPACITY = 64

        /**
         * Application-level (graphql-transport-ws) ping cadence. Kept under the
         * server/proxy idle close (~60s observed) so the connection never looks
         * idle. Belt-and-suspenders with the engine's RFC6455 ping — covers a
         * server that times out on the graphql-ws layer rather than the socket.
         */
        private const val KEEPALIVE_INTERVAL_MS = 20_000L
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
    suspend fun connect() = lifecycleMutex.withLock {
        // Idempotent: if a concurrent caller already completed the handshake
        // while we were waiting on the mutex, just return. Callers should
        // treat connect() as "ensure the connection is up", not "open a new one".
        if (connected.get()) return@withLock

        // Open WebSocket connection with graphql-transport-ws sub-protocol.
        // The Sec-WebSocket-Protocol header is REQUIRED by the GraphQL-WS spec.
        // See: https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md
        val newSession = httpClient.webSocketSession(
            urlString = url,
            block = {
                header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
            }
        )
        session = newSession

        try {
            // Send connection_init
            sendMessage(GraphQLWebSocketMessage.ConnectionInit())

            // Wait for connection_ack with timeout.
            // IMPORTANT: Don't start a separate coroutine here — it would race
            // with message processing! Instead read frames directly until ack.
            withTimeout(connectionTimeout) {
                for (frame in newSession.incoming) {
                    if (frame is Frame.Text) {
                        val message = parseMessage(frame.readText())
                        if (message is GraphQLWebSocketMessage.ConnectionAck) {
                            connected.set(true)
                            break
                        }
                    }
                    // Ignore other frame types during connection phase
                }
            }
        } catch (t: Throwable) {
            // Handshake failed (timeout, parse error, socket closed early).
            // Tear the session back down so a retry through the same mutex
            // can open a fresh one — otherwise we'd leak a half-open session
            // that subscribe() would happily try to use.
            runCatching { newSession.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "Handshake failed")) }
            session = null
            connected.set(false)
            throw t
        }

        // Start message processing loop — NOW it won't miss any messages
        startMessageProcessing()
        // Send periodic app-level pings so the server/proxy never sees the
        // connection as idle (the server closes idle sockets ~60s, which showed
        // up as subscribe → error → reconnect flapping every minute).
        startKeepAlive()
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
        Log.d("GraphQLWebSocket", "Starting subscription $operationId, active subs: ${activeSubscriptions.keys}")

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
            activeSubscriptions.remove(operationId)
            // This finally is normally reached via cancellation — transformWhile
            // stops collection when id >= maxId, or the tracker is cancelled on a
            // network switch. A suspend send in a cancelled coroutine throws at the
            // first suspension point before doing any work, so without NonCancellable
            // the Complete never reaches the server and it keeps streaming `next` for
            // this now-dead id (the "No active subscription found" flood + wasted
            // bandwidth). runCatching: the session may already be gone, in which case
            // there's nothing left to tell the server.
            withContext(NonCancellable) {
                runCatching { sendMessage(GraphQLWebSocketMessage.Complete(id = operationId)) }
            }
        }
    }

    /**
     * Close connection gracefully.
     *
     * Completes all active subscriptions and closes WebSocket.
     */
    suspend fun close() = lifecycleMutex.withLock {
        if (!connected.get()) return@withLock

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
        session?.send(Frame.Text(json))
    }

    private fun parseMessage(text: String): GraphQLWebSocketMessage {
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

    /**
     * Periodically send a graphql-transport-ws `ping` while connected, so the
     * connection never idles past the server's close timeout. Runs on [scope],
     * so [close] (which cancels the scope) stops it; a send failure means the
     * session is already gone, so the loop exits and the message loop handles
     * teardown/reconnect.
     */
    private fun startKeepAlive() {
        scope.launch {
            while (isActive && connected.get()) {
                delay(KEEPALIVE_INTERVAL_MS)
                if (!connected.get()) break
                try {
                    ping()
                } catch (e: Exception) {
                    break
                }
            }
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
                    // Benign + high-frequency: after we send Complete the server can
                    // stream a backlog of in-flight `next` (one per buffered event)
                    // before it processes the Complete. Per graphql-transport-ws the
                    // client just ignores them. Off by default (no per-frame spam);
                    // enable with: adb shell setprop log.tag.GraphQLWebSocket VERBOSE
                    if (Log.isLoggable("GraphQLWebSocket", Log.VERBOSE)) {
                        Log.v("GraphQLWebSocket", "Ignoring next for inactive subscription ${message.id}")
                    }
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
                Log.d("GraphQLWebSocket", "Subscription ${message.id} completed by server, active subs: ${activeSubscriptions.keys}")
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
