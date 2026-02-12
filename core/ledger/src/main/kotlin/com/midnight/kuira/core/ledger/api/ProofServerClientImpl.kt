package com.midnight.kuira.core.ledger.api

import android.util.Log
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.delay

/**
 * Ktor-based implementation of ProofServerClient for Midnight proof server.
 *
 * **Connection:**
 * - HTTP endpoint: POST to `{proofServerUrl}/prove-tx`
 * - Binary protocol (SCALE codec)
 * - Timeout: 300 seconds (5 minutes) - proof generation is slow
 *
 * **Retry Logic:**
 * - Retries on 502, 503, 504 (server overload/unavailable)
 * - Exponential backoff: 2s, 4s, 8s
 * - Maximum 3 attempts
 * - Does NOT retry on 400-499 (client errors)
 *
 * **Security:**
 * - Development mode: Allows HTTP to localhost (testing only)
 * - Production mode: Requires HTTPS
 *
 * @param httpClient HTTP client for making requests (injectable for testing)
 * @param proofServerUrl Proof server endpoint URL (e.g., "http://localhost:6300")
 * @param developmentMode If true, allows HTTP to localhost (INSECURE - testing only)
 */
class ProofServerClientImpl(
    private val httpClient: HttpClient,
    private val proofServerUrl: String = "http://localhost:6300",
    private val developmentMode: Boolean = true
) : ProofServerClient {

    /**
     * Convenience constructor for production use (creates default HTTP client).
     */
    constructor(
        proofServerUrl: String = "http://localhost:6300",
        developmentMode: Boolean = true
    ) : this(
        httpClient = createDefaultHttpClient(),
        proofServerUrl = proofServerUrl,
        developmentMode = developmentMode
    )

    companion object {
        private const val TAG = "ProofServerClient"
        private const val RETRY_BASE_DELAY_MS = 2000L  // 2 seconds
        private const val MAX_RETRY_ATTEMPTS = 3

        private fun createDefaultHttpClient() = HttpClient(OkHttp) {
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.INFO
            }

            // Timeout configuration - proof generation is SLOW
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000 // 5 minutes
                connectTimeoutMillis = 30_000  // 30 seconds
                socketTimeoutMillis = 300_000  // 5 minutes
            }

            // Don't automatically throw on non-2xx (we handle retries manually)
            expectSuccess = false
        }
    }

    init {
        // Validate configuration
        if (!developmentMode && !proofServerUrl.startsWith("https://")) {
            throw IllegalArgumentException(
                "Production mode requires HTTPS. Got: $proofServerUrl\n" +
                "Use developmentMode=true for HTTP testing (INSECURE)"
            )
        }

        if (developmentMode &&
            !proofServerUrl.startsWith("http://localhost") &&
            !proofServerUrl.startsWith("http://127.0.0.1") &&
            !proofServerUrl.startsWith("http://10.0.2.2")) {  // Android emulator special IP
            throw IllegalArgumentException(
                "Development mode only allows localhost. Got: $proofServerUrl\n" +
                "Use HTTPS for remote proof servers"
            )
        }
    }

    override suspend fun proveTransaction(unprovenTxHex: String): String {
        // Validate input
        val cleanHex = unprovenTxHex.removePrefix("0x").lowercase()
        require(cleanHex.matches(Regex("^[0-9a-f]+$"))) {
            "unprovenTxHex must be valid hexadecimal (got: $unprovenTxHex)"
        }
        require(cleanHex.isNotEmpty()) {
            "unprovenTxHex cannot be empty"
        }

        // Convert hex to binary
        val unprovenTxBytes = cleanHex.decodeHex()

        Log.d(TAG, "Proving transaction: ${unprovenTxBytes.size} bytes")
        Log.d(TAG, "Proof server URL: $proofServerUrl/prove-tx")

        // Retry logic with exponential backoff
        var lastException: ProofServerException? = null

        repeat(MAX_RETRY_ATTEMPTS) { attempt ->
            try {
                val provenTxBytes = proveTransactionWithRetry(unprovenTxBytes, attempt)
                val provenTxHex = provenTxBytes.encodeHex()

                Log.d(TAG, "✅ Transaction proven successfully")
                Log.d(TAG, "   Proven transaction: ${provenTxBytes.size} bytes")

                return provenTxHex
            } catch (e: ProofServerHttpException) {
                // Retry on 502, 503, 504 (server overload)
                if (e.statusCode in listOf(502, 503, 504) && attempt < MAX_RETRY_ATTEMPTS - 1) {
                    val delayMs = RETRY_BASE_DELAY_MS * (1 shl attempt) // Exponential: 2s, 4s, 8s
                    Log.w(TAG, "⚠️  Proof server error ${e.statusCode}, retrying in ${delayMs}ms (attempt ${attempt + 1}/$MAX_RETRY_ATTEMPTS)")
                    lastException = e
                    delay(delayMs)
                } else {
                    // Don't retry on other status codes or if max attempts reached
                    throw e
                }
            } catch (e: ProofServerException) {
                // Don't retry on other exceptions
                throw e
            }
        }

        // If we exhausted all retries, throw last exception
        throw lastException ?: ProofServerError(
            statusCode = 503,
            message = "Proof server retry limit exceeded after $MAX_RETRY_ATTEMPTS attempts"
        )
    }

    private suspend fun proveTransactionWithRetry(
        unprovenTxBytes: ByteArray,
        attempt: Int
    ): ByteArray {
        try {
            val response: HttpResponse = httpClient.post("$proofServerUrl/prove-tx") {
                contentType(ContentType.Application.OctetStream)
                setBody(unprovenTxBytes)
            }

            Log.d(TAG, "   HTTP ${response.status.value}: ${response.status.description}")

            // Handle response
            return when (response.status.value) {
                in 200..299 -> {
                    // Success - parse binary response
                    val provenTxBytes = response.readBytes()
                    if (provenTxBytes.isEmpty()) {
                        throw ProofServerInvalidResponseException("Empty response from proof server")
                    }
                    provenTxBytes
                }
                in 400..499 -> {
                    // Client error - don't retry
                    val errorBody = response.bodyAsText()
                    Log.e(TAG, "❌ Proof server error response (400): $errorBody")
                    throw ProofServerHttpException(
                        statusCode = response.status.value,
                        message = "Client error: ${response.status.description}\nBody: $errorBody",
                        cause = null
                    )
                }
                in 500..599 -> {
                    // Server error - may retry (handled by caller)
                    val errorBody = response.bodyAsText()
                    throw ProofServerHttpException(
                        statusCode = response.status.value,
                        message = "Server error: ${response.status.description}\n$errorBody"
                    )
                }
                else -> {
                    throw ProofServerInvalidResponseException(
                        "Unexpected status code: ${response.status.value}"
                    )
                }
            }
        } catch (e: HttpRequestTimeoutException) {
            Log.e(TAG, "❌ Proof server timeout (> 5 minutes)")
            throw ProofServerTimeoutException(
                "Proof generation timed out after 5 minutes",
                e
            )
        } catch (e: ProofServerException) {
            // Re-throw proof server exceptions
            throw e
        } catch (e: Exception) {
            // Network or other error
            Log.e(TAG, "❌ Proof server network error", e)
            throw ProofServerNetworkException(
                "Failed to connect to proof server: ${e.message}",
                e
            )
        }
    }

    override suspend fun isHealthy(): Boolean {
        return try {
            val response: HttpResponse = httpClient.get("$proofServerUrl/health") {
                timeout {
                    requestTimeoutMillis = 10_000 // 10 seconds for health check
                }
            }
            response.status.isSuccess()
        } catch (e: Exception) {
            Log.w(TAG, "Proof server health check failed", e)
            false
        }
    }

    override fun close() {
        httpClient.close()
    }
}

/**
 * Extension to convert hex string to ByteArray.
 */
@OptIn(InternalAPI::class)
private fun String.decodeHex(): ByteArray = hex(this)

/**
 * Extension to convert ByteArray to hex string.
 */
@OptIn(InternalAPI::class)
private fun ByteArray.encodeHex(): String = hex(this)
