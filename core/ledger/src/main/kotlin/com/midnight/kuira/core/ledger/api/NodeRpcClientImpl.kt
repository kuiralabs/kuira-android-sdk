package com.midnight.kuira.core.ledger.api

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicInteger

/**
 * Ktor-based implementation of NodeRpcClient for Midnight node JSON-RPC API.
 *
 * **Connection:**
 * - HTTP endpoint: POST to `{nodeUrl}` (e.g., http://localhost:9944)
 * - JSON-RPC 2.0 protocol
 * - Timeout: 30 seconds (configurable)
 *
 * **Security:**
 * - Development mode: Allows HTTP to localhost (testing only)
 * - Production mode: Requires HTTPS (not yet implemented)
 *
 * @param httpClient HTTP client for making requests (injectable for testing)
 * @param nodeUrl Node RPC endpoint URL (e.g., "http://localhost:9944")
 * @param developmentMode If true, allows HTTP to localhost (INSECURE - testing only)
 */
class NodeRpcClientImpl(
    private val httpClient: HttpClient,
    private val nodeUrl: String = "http://localhost:9944",
    private val developmentMode: Boolean = true
) : NodeRpcClient {

    /**
     * Convenience constructor for production use (creates default HTTP client).
     */
    constructor(
        nodeUrl: String = "http://localhost:9944",
        developmentMode: Boolean = true
    ) : this(
        httpClient = createDefaultHttpClient(),
        nodeUrl = nodeUrl,
        developmentMode = developmentMode
    )

    companion object {
        private const val TAG = "NodeRpcClient"
        private const val TIP_FETCH_TIMEOUT_MS = 3_000L

        private fun createDefaultHttpClient() = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true  // CRITICAL: Encode default values (needed for jsonrpc field)
                })
            }
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }

            // WebSocket support for transaction status subscription
            install(WebSockets)

            // Timeout configuration
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000 // 30 seconds
                connectTimeoutMillis = 10_000 // 10 seconds
                socketTimeoutMillis = 30_000 // 30 seconds
            }

            // Response validation (catch non-2xx responses)
            expectSuccess = true
        }
    }

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true  // CRITICAL: Encode default values (needed for jsonrpc field)
    }

    /**
     * Request ID counter for JSON-RPC (increments per request).
     */
    private val requestIdCounter = AtomicInteger(0)

    init {
        val isSecure = nodeUrl.startsWith("https://") || nodeUrl.startsWith("wss://")
        val isLocalhost = nodeUrl.startsWith("http://localhost") ||
            nodeUrl.startsWith("http://127.0.0.1") ||
            nodeUrl.startsWith("http://10.0.2.2") ||  // Android emulator special IP
            nodeUrl.startsWith("ws://localhost") ||
            nodeUrl.startsWith("ws://127.0.0.1") ||
            nodeUrl.startsWith("ws://10.0.2.2")

        // Production mode requires secure connections
        if (!developmentMode && !isSecure) {
            throw IllegalArgumentException(
                "Production mode requires HTTPS/WSS. Got: $nodeUrl\n" +
                "Use developmentMode=true for HTTP testing (INSECURE)"
            )
        }

        // Development mode allows secure URLs (any host) or insecure URLs (localhost only)
        if (developmentMode && !isSecure && !isLocalhost) {
            throw IllegalArgumentException(
                "Development mode only allows localhost for insecure connections. Got: $nodeUrl\n" +
                "Use HTTPS/WSS for remote nodes"
            )
        }
    }

    /**
     * JSON-RPC 2.0 request payload.
     */
    @Serializable
    private data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Int,
        val method: String,
        val params: List<String>
    )

    /**
     * JSON-RPC 2.0 response payload.
     */
    @Serializable
    private data class JsonRpcResponse(
        val jsonrpc: String,
        val id: Int,
        val result: JsonElement? = null,
        val error: JsonRpcErrorObject? = null
    )

    /**
     * JSON-RPC 2.0 error object.
     */
    @Serializable
    private data class JsonRpcErrorObject(
        val code: Int,
        val message: String,
        val data: String? = null
    )

    override suspend fun submitTransaction(serializedTxHex: String): String {
        // Validate input
        val cleanHex = serializedTxHex.removePrefix("0x").lowercase()
        require(cleanHex.matches(Regex("^[0-9a-f]+$"))) {
            "serializedTxHex must be valid hexadecimal (got: $serializedTxHex)"
        }
        require(cleanHex.isNotEmpty()) {
            "serializedTxHex must not be empty"
        }

        // CRITICAL: Wrap the midnight transaction in a Substrate extrinsic
        // The midnight transaction is already tagged SCALE (includes "midnight:transaction[v6]:" prefix)
        // We need to wrap it in: [version][pallet_index][call_index][SCALE(Vec<u8>)]
        val wrappedExtrinsic = wrapInExtrinsic(cleanHex)

        try {
            // Build JSON-RPC request
            val requestId = requestIdCounter.incrementAndGet()
            val request = JsonRpcRequest(
                id = requestId,
                method = "author_submitExtrinsic",
                params = listOf("0x$wrappedExtrinsic") // Submit the wrapped extrinsic
            )

            // Send POST request (logged at DEBUG level only)

            val response = httpClient.post(nodeUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            val responseBody = response.bodyAsText()

            val jsonResponse = json.decodeFromString<JsonRpcResponse>(responseBody)

            // Validate response ID matches request
            if (jsonResponse.id != requestId) {
                throw NodeInvalidResponseException(
                    "Response ID mismatch: expected $requestId, got ${jsonResponse.id}"
                )
            }

            // Check for error
            if (jsonResponse.error != null) {
                val error = jsonResponse.error

                // Log full error details for debugging
                println("[NodeRpc] Transaction rejected by node:")
                println("[NodeRpc]   Error code: ${error.code}")
                println("[NodeRpc]   Message: ${error.message}")
                println("[NodeRpc]   Data: ${error.data}")

                // Special handling for invalid transaction (code 1010)
                if (error.code == 1010) {
                    // Parse custom error code from data field (e.g., "Custom error: 115")
                    val customErrorCode = error.data?.let { data ->
                        val match = Regex("Custom error: (\\d+)").find(data)
                        match?.groupValues?.get(1)?.toIntOrNull()
                    }

                    println("[NodeRpc]   Custom error code: $customErrorCode")
                    when (customErrorCode) {
                        115 -> println("[NodeRpc]   115 = InvalidProof (ZK proof failed verification — check proving-key/verifier-key versions & proof public inputs)")
                        195 -> println("[NodeRpc]   195 = InputNotInUtxos (an unshielded UTXO input is already spent or missing)")
                        186 -> println("[NodeRpc]   186 = EffectsCheckFailure")
                    }

                    throw TransactionRejected(
                        reason = error.message,
                        txHash = null,
                        customErrorCode = customErrorCode
                    )
                }

                throw NodeRpcError(
                    code = error.code,
                    message = error.message,
                    data = error.data
                )
            }

            // Extract result (transaction hash)
            val result = jsonResponse.result
                ?: throw NodeInvalidResponseException("Missing 'result' field in response")

            val txHash = result.jsonPrimitive.content
                .removePrefix("0x") // Remove "0x" prefix if present

            // Validate transaction hash format (32 bytes hex = 64 characters)
            require(txHash.matches(Regex("^[0-9a-f]{64}$"))) {
                "Invalid transaction hash format: $txHash (expected 64 hex characters)"
            }

            return txHash
        } catch (e: ResponseException) {
            throw NodeHttpException(
                statusCode = e.response.status.value,
                message = "HTTP error: ${e.message}",
                cause = e
            )
        } catch (e: HttpRequestTimeoutException) {
            throw NodeTimeoutException(
                message = "Request timeout while submitting transaction",
                cause = e
            )
        } catch (e: java.net.UnknownHostException) {
            throw NodeNetworkException(
                message = "DNS resolution failed for $nodeUrl",
                cause = e
            )
        } catch (e: java.net.ConnectException) {
            throw NodeNetworkException(
                message = "Connection refused to $nodeUrl (is node running?)",
                cause = e
            )
        } catch (e: java.io.IOException) {
            throw NodeNetworkException(
                message = "Network I/O error: ${e.message}",
                cause = e
            )
        } catch (e: NodeRpcException) {
            throw e // Re-throw our custom exceptions
        } catch (e: Exception) {
            throw NodeInvalidResponseException(
                message = "Unexpected error: ${e.message}",
                cause = e
            )
        }
    }

    /**
     * Submit transaction and wait for finalization using WebSocket subscription.
     *
     * **This is the correct way to submit transactions.**
     *
     * Uses `author_submitAndWatchExtrinsic` which:
     * 1. Submits the transaction
     * 2. Subscribes to status updates via WebSocket
     * 3. Streams status events: ready → broadcast → inBlock → finalized
     *
     * **Why WebSocket instead of HTTP + Indexer:**
     * - HTTP `author_submitExtrinsic` is fire-and-forget (no confirmation)
     * - Indexer lags behind the node (causes race conditions)
     * - WebSocket gives us real-time confirmation from the node itself
     *
     * @param serializedTxHex Hex-encoded serialized transaction
     * @param timeoutMs Maximum time to wait for finalization
     * @return Finalization result
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun submitAndWaitForFinalization(
        serializedTxHex: String,
        timeoutMs: Long,
        onStage: (suspend (NodeRpcClient.SubmissionStage) -> Unit)?,
    ): TransactionFinalizationResult {
        // Validate input
        val cleanHex = serializedTxHex.removePrefix("0x").lowercase()
        require(cleanHex.matches(Regex("^[0-9a-f]+$"))) {
            "serializedTxHex must be valid hexadecimal"
        }

        // Wrap in extrinsic
        val wrappedExtrinsic = wrapInExtrinsic(cleanHex)

        // Convert HTTP URL to WebSocket URL
        val wsUrl = nodeUrl
            .replace("http://", "ws://")
            .replace("https://", "wss://")

        Log.d(TAG, "Connecting to node via WebSocket: $wsUrl")

        val result = CompletableDeferred<TransactionFinalizationResult>()
        var txHash: String? = null
        var subscriptionId: String? = null

        try {
            withTimeout(timeoutMs) {
                httpClient.webSocket(wsUrl) {
                    // Step 1: Send subscribe request
                    val requestId = requestIdCounter.incrementAndGet()
                    val subscribeRequest = buildJsonRpcRequest(
                        id = requestId,
                        method = "author_submitAndWatchExtrinsic",
                        params = listOf("0x$wrappedExtrinsic")
                    )

                    Log.d(TAG, "Sending author_submitAndWatchExtrinsic request...")
                    send(Frame.Text(subscribeRequest))
                    onStage?.invoke(NodeRpcClient.SubmissionStage.SUBMITTED)

                    // Step 2: Process responses
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                Log.d(TAG, "WebSocket received: $text")

                                val response = json.decodeFromString<JsonObject>(text)

                                // Check for error
                                val error = response["error"]?.jsonObject
                                if (error != null) {
                                    val errorCode = error["code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                                    val errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                    val errorData = error["data"]?.jsonPrimitive?.contentOrNull

                                    Log.e(TAG, "WebSocket error: code=$errorCode, message=$errorMessage, data=$errorData")

                                    // code 1010 = "Invalid Transaction" with a node
                                    // custom error code in `data` (e.g. "Custom error: 115").
                                    // These are transaction REJECTIONS — surface them as
                                    // TransactionRejected carrying the real custom code so
                                    // callers can branch on it. Authoritative codes (from
                                    // midnight-node): 115 = InvalidProof, 195 = InputNotInUtxos
                                    // ("UTXO already spent"), 186 = EffectsCheckFailure.
                                    if (errorCode == 1010) {
                                        val customErrorCode = errorData?.let { data ->
                                            Regex("Custom error: (\\d+)").find(data)?.groupValues?.get(1)?.toIntOrNull()
                                        }
                                        Log.e(TAG, "Transaction rejected: custom error $customErrorCode ($errorData)")
                                        throw TransactionRejected(
                                            reason = errorMessage,
                                            txHash = txHash,
                                            customErrorCode = customErrorCode,
                                        )
                                    }

                                    throw NodeRpcError(
                                        code = errorCode ?: -1,
                                        message = errorMessage,
                                        data = errorData
                                    )
                                }

                                // Check if this is subscription result (gives us subscription ID)
                                val subscriptionResult = response["result"]?.jsonPrimitive?.contentOrNull
                                if (subscriptionResult != null && response["id"] != null) {
                                    subscriptionId = subscriptionResult
                                    Log.d(TAG, "Subscription created: $subscriptionId")
                                    continue
                                }

                                // Check if this is a subscription notification
                                val params = response["params"]?.jsonObject
                                if (params != null) {
                                    val statusResult = params["result"]
                                    if (statusResult != null) {
                                        val finalizationResult = parseTransactionStatus(statusResult, txHash ?: "")
                                        Log.d(TAG, "Transaction status: $finalizationResult")

                                        when (finalizationResult) {
                                            is TransactionFinalizationResult.Finalized -> {
                                                Log.i(TAG, "✅ Transaction FINALIZED in block ${finalizationResult.blockHash}")
                                                result.complete(finalizationResult)
                                                // Unsubscribe
                                                subscriptionId?.let { subId ->
                                                    val unsubRequest = buildJsonRpcRequest(
                                                        id = requestIdCounter.incrementAndGet(),
                                                        method = "author_unwatchExtrinsic",
                                                        params = listOf(subId)
                                                    )
                                                    send(Frame.Text(unsubRequest))
                                                }
                                                return@webSocket
                                            }
                                            is TransactionFinalizationResult.InBlock -> {
                                                txHash = finalizationResult.txHash
                                                Log.d(TAG, "Transaction in block, waiting for finalization...")
                                                onStage?.invoke(NodeRpcClient.SubmissionStage.IN_BLOCK)
                                            }
                                            is TransactionFinalizationResult.Dropped,
                                            is TransactionFinalizationResult.Invalid,
                                            is TransactionFinalizationResult.Usurped -> {
                                                Log.e(TAG, "Transaction failed: $finalizationResult")
                                                result.complete(finalizationResult)
                                                return@webSocket
                                            }
                                            else -> {
                                                // Continue waiting (ready, broadcast, etc.)
                                                Log.d(TAG, "Intermediate status, continuing...")
                                                onStage?.invoke(NodeRpcClient.SubmissionStage.BROADCAST)
                                            }
                                        }
                                    }
                                }
                            }
                            is Frame.Close -> {
                                Log.w(TAG, "WebSocket closed")
                                if (!result.isCompleted) {
                                    result.complete(TransactionFinalizationResult.Timeout(txHash ?: ""))
                                }
                                return@webSocket
                            }
                            else -> { /* Ignore other frame types */ }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Transaction finalization timed out after ${timeoutMs}ms")
            return TransactionFinalizationResult.Timeout(txHash ?: "")
        } catch (e: TransactionRejected) {
            throw e // Re-throw for caller to handle
        } catch (e: NodeRpcException) {
            throw e // Re-throw for caller to handle
        } catch (e: ClosedReceiveChannelException) {
            Log.w(TAG, "WebSocket channel closed unexpectedly")
            if (result.isCompleted) {
                return result.getCompleted()
            }
            throw NodeNetworkException("WebSocket connection closed unexpectedly", e)
        } catch (e: Exception) {
            Log.e(TAG, "WebSocket error", e)
            throw NodeNetworkException("WebSocket error: ${e.message}", e)
        }

        return if (result.isCompleted) {
            result.getCompleted()
        } else {
            TransactionFinalizationResult.Timeout(txHash ?: "")
        }
    }

    /**
     * Build a JSON-RPC 2.0 request string.
     */
    private fun buildJsonRpcRequest(id: Int, method: String, params: List<String>): String {
        val paramsJson = params.joinToString(",") { "\"$it\"" }
        return """{"jsonrpc":"2.0","id":$id,"method":"$method","params":[$paramsJson]}"""
    }

    /**
     * Parse transaction status from Substrate node.
     *
     * Substrate `TransactionStatus` lifecycle:
     *   future → ready → broadcast → inBlock → finalized
     *
     * Wire form varies: some statuses are bare strings, others arrive as single-key
     * objects. Both shapes may appear for the same logical status depending on node
     * version — `broadcast` in particular is emitted as `{"broadcast": [peer_ids]}`
     * by Substrate's JSON-RPC, not as the string `"broadcast"` (which is what the
     * Rust enum debug format uses). Treating the object form as unknown → Invalid
     * was aborting every submission mid-flight right after the proof server
     * succeeded.
     */
    private fun parseTransactionStatus(status: JsonElement, txHash: String): TransactionFinalizationResult {
        return when {
            // String statuses
            status is JsonPrimitive && status.isString -> {
                when (status.content) {
                    "future", "ready", "broadcast" -> {
                        // Intermediate status - not a final result
                        // Return InBlock with empty hash to signal "still waiting"
                        TransactionFinalizationResult.InBlock(txHash, "")
                    }
                    "dropped" -> TransactionFinalizationResult.Dropped(txHash, "Transaction dropped from pool")
                    "invalid" -> TransactionFinalizationResult.Invalid(txHash, "Transaction is invalid")
                    else -> TransactionFinalizationResult.Invalid(txHash, "Unknown status: ${status.content}")
                }
            }

            // Object statuses
            status is JsonObject -> {
                when {
                    status.containsKey("inBlock") -> {
                        val blockHash = status["inBlock"]?.jsonPrimitive?.contentOrNull?.removePrefix("0x") ?: ""
                        TransactionFinalizationResult.InBlock(txHash, blockHash)
                    }
                    status.containsKey("finalized") -> {
                        val blockHash = status["finalized"]?.jsonPrimitive?.contentOrNull?.removePrefix("0x") ?: ""
                        TransactionFinalizationResult.Finalized(txHash, blockHash)
                    }
                    status.containsKey("broadcast") -> {
                        // Payload is the peer id list; still an intermediate status.
                        TransactionFinalizationResult.InBlock(txHash, "")
                    }
                    status.containsKey("usurped") -> {
                        val replacedBy = status["usurped"]?.jsonPrimitive?.contentOrNull?.removePrefix("0x")
                        TransactionFinalizationResult.Usurped(txHash, replacedBy)
                    }
                    status.containsKey("retracted") -> {
                        // Block was retracted (reorg) - transaction went back to pool
                        // Treat as still waiting
                        TransactionFinalizationResult.InBlock(txHash, "")
                    }
                    status.containsKey("finalityTimeout") -> {
                        // Block finalization timed out; substrate no longer tracks it.
                        // Surface as Dropped so the caller can decide whether to retry.
                        val blockHash = status["finalityTimeout"]?.jsonPrimitive?.contentOrNull?.removePrefix("0x")
                        TransactionFinalizationResult.Dropped(txHash, "Finality timeout at block $blockHash")
                    }
                    else -> TransactionFinalizationResult.Invalid(txHash, "Unknown status object: $status")
                }
            }

            else -> TransactionFinalizationResult.Invalid(txHash, "Cannot parse status: $status")
        }
    }

    override suspend fun getFinalizedHead(): String {
        val requestId = requestIdCounter.incrementAndGet()
        val request = JsonRpcRequest(
            id = requestId,
            method = "chain_getFinalizedHead",
            params = emptyList(),
        )

        // Convert ws(s):// to http(s):// for the HTTP POST RPC call
        val httpUrl = nodeUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")

        try {
            val response = httpClient.post(httpUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
                timeout {
                    requestTimeoutMillis = TIP_FETCH_TIMEOUT_MS
                }
            }

            val jsonResponse = json.decodeFromString<JsonRpcResponse>(response.bodyAsText())

            if (jsonResponse.error != null) {
                throw NodeRpcError(
                    code = jsonResponse.error.code,
                    message = jsonResponse.error.message,
                    data = jsonResponse.error.data,
                )
            }

            val result = jsonResponse.result
                ?: throw NodeInvalidResponseException("Missing 'result' in chain_getFinalizedHead response")

            return result.jsonPrimitive.content.removePrefix("0x")
        } catch (e: NodeRpcException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            throw NodeTimeoutException("Timeout fetching finalized head", e)
        } catch (e: Exception) {
            throw NodeNetworkException("Failed to fetch finalized head: ${e.message}", e)
        }
    }

    override suspend fun isHealthy(): Boolean {
        return try {
            // Try to submit a health check (system_health is standard Substrate RPC)
            val requestId = requestIdCounter.incrementAndGet()
            val request = JsonRpcRequest(
                id = requestId,
                method = "system_health",
                params = emptyList()
            )

            val response = httpClient.post(nodeUrl) {
                contentType(ContentType.Application.Json)
                setBody(request)
                timeout {
                    requestTimeoutMillis = 5_000 // 5 second timeout for health check
                }
            }

            response.status.isSuccess()
        } catch (e: Exception) {
            // Health check failed - node is not reachable
            false
        }
    }

    override fun close() {
        httpClient.close()
    }

    /**
     * Wraps a midnight transaction (tagged SCALE) in a Substrate extrinsic.
     *
     * Substrate extrinsic format for unsigned extrinsics:
     * compact_length_of_rest + version + call_index + call_params
     *
     * Example from polkadot.js for 569-byte midnight TX:
     * - Full extrinsic: f908 04 05 00 e508 + midnight_tx_data
     *   - f908 = compact(574) - length of the rest (576 total - 2 for this compact)
     *   - 04 = version byte (unsigned, version 4)
     *   - 05 = call enum variant (Midnight::sendMnTransaction)
     *   - 00 = ??? (need to investigate)
     *   - e508 = compact(569) - length of midnight TX Vec u8
     *
     * @param midnightTxHex Hex-encoded midnight transaction (tagged SCALE)
     * @return Hex-encoded extrinsic
     */
    private fun wrapInExtrinsic(midnightTxHex: String): String {
        // Convert hex to bytes
        val midnightTxBytes = midnightTxHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        // FOR NOW: Just match the empirical pattern from polkadot.js dump
        // We'll refine this once we understand the full structure
        val VERSION_BYTE: Byte = 0x04 // Empirically observed
        val CALL_VARIANT: Byte = 0x05  // Empirically observed
        val MYSTERY_BYTE: Byte = 0x00  // Empirically observed

        // Compact length of midnight TX
        val txLengthCompact = encodeCompactLength(midnightTxBytes.size)

        // Build the call data
        val callData = byteArrayOf(VERSION_BYTE, CALL_VARIANT, MYSTERY_BYTE) +
                txLengthCompact +
                midnightTxBytes

        // Compact length of call data
        val callLengthCompact = encodeCompactLength(callData.size)

        // Full extrinsic: compact(call_length) + call_data
        val extrinsicBytes = callLengthCompact + callData

        val extrinsicHex = extrinsicBytes.joinToString("") { "%02x".format(it) }
        return extrinsicHex
    }

    /**
     * SCALE compact encoding for unsigned integers.
     *
     * Single-byte mode:  0b00XXXXXX               (0-63)
     * Two-byte mode:     0b01XXXXXX XXXXXXXX      (64-16383)
     * Four-byte mode:    0b10XXXXXX ...           (16384-1073741823)
     * Big-integer mode:  0b11XXXXXX ...           (1073741824+)
     *
     * @param value The integer to encode
     * @return SCALE compact-encoded bytes
     */
    private fun encodeCompactLength(value: Int): ByteArray {
        return when {
            value < 64 -> {
                // Single-byte mode: 0b00XXXXXX
                byteArrayOf((value shl 2).toByte())
            }
            value < 16384 -> {
                // Two-byte mode: 0b01XXXXXX XXXXXXXX
                val encoded = (value shl 2) or 0x01
                byteArrayOf(
                    (encoded and 0xFF).toByte(),
                    ((encoded shr 8) and 0xFF).toByte()
                )
            }
            value < 1073741824 -> {
                // Four-byte mode: 0b10XXXXXX ...
                val encoded = (value shl 2) or 0x02
                byteArrayOf(
                    (encoded and 0xFF).toByte(),
                    ((encoded shr 8) and 0xFF).toByte(),
                    ((encoded shr 16) and 0xFF).toByte(),
                    ((encoded shr 24) and 0xFF).toByte()
                )
            }
            else -> {
                throw IllegalArgumentException("Value too large for SCALE compact encoding: $value")
            }
        }
    }
}
