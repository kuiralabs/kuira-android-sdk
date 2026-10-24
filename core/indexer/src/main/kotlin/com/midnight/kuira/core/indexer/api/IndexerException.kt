package com.midnight.kuira.core.indexer.api

import com.midnight.kuira.core.network.NetworkConfig

/**
 * Base exception for all indexer-related errors.
 *
 * Messages are redacted here, not at each throw site: HTTP-client exceptions quote the request URL
 * in their own message, and a throw site that interpolates one cannot be relied on to remember.
 */
sealed class IndexerException(message: String, cause: Throwable? = null) :
    Exception(NetworkConfig.redactProjectId(message), cause)

/**
 * Network connectivity error (no internet, DNS failure, etc).
 *
 * **Recovery:** Retry with exponential backoff
 */
class NetworkException(message: String, cause: Throwable? = null) : IndexerException(message, cause)

/**
 * HTTP error from indexer API (4xx or 5xx status).
 *
 * **Recovery:**
 * - 4xx: Don't retry (client error)
 * - 5xx: Retry with exponential backoff (server error)
 */
class HttpException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : IndexerException("HTTP $statusCode: $message", cause) {
    val isClientError: Boolean get() = statusCode in 400..499
    val isServerError: Boolean get() = statusCode in 500..599
}

/**
 * Request timeout error.
 *
 * **Recovery:** Retry with longer timeout
 */
class TimeoutException(message: String, cause: Throwable? = null) : IndexerException(message, cause)

/**
 * Invalid or malformed response from indexer.
 *
 * **Recovery:**
 * - Retry once (might be transient)
 * - If persists, report to indexer operator
 */
class InvalidResponseException(message: String, cause: Throwable? = null) : IndexerException(message, cause)

/**
 * GraphQL error in response.
 *
 * **Recovery:** Don't retry (query error)
 */
class GraphQLException(
    val errors: List<String>,
    message: String
) : IndexerException(message)

/**
 * Indexer API version mismatch or unsupported feature.
 *
 * **Recovery:** Don't retry (incompatible API version)
 */
class ApiVersionException(message: String) : IndexerException(message)

/**
 * Maximum retry attempts exceeded.
 *
 * **Recovery:** Alert user, fall back to cached data
 */
class RetryExhaustedException(
    message: String,
    val attempts: Int,
    cause: Throwable? = null
) : IndexerException(message, cause)
