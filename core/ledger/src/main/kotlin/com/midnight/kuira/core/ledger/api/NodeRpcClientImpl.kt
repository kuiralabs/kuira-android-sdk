package com.midnight.kuira.core.ledger.api

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        private fun createDefaultHttpClient() = HttpClient(CIO) {
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
        // Validate configuration
        if (!developmentMode && !nodeUrl.startsWith("https://")) {
            throw IllegalArgumentException(
                "Production mode requires HTTPS. Got: $nodeUrl\n" +
                "Use developmentMode=true for HTTP testing (INSECURE)"
            )
        }

        if (developmentMode &&
            !nodeUrl.startsWith("http://localhost") &&
            !nodeUrl.startsWith("http://127.0.0.1") &&
            !nodeUrl.startsWith("http://10.0.2.2")) {  // Android emulator special IP
            throw IllegalArgumentException(
                "Development mode only allows localhost. Got: $nodeUrl\n" +
                "Use HTTPS for remote nodes"
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
                    if (customErrorCode == 115) {
                        println("[NodeRpc]   ERROR 115 = UTXO not found or already spent on blockchain")
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
