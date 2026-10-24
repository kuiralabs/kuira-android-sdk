package com.midnight.kuira.core.network

/**
 * A secret an indexer requires on every request, carried as an HTTP header.
 *
 * Never a query parameter: a credential inside a URL leaks into every log line, exception message
 * and error screen that quotes the request.
 */
data class IndexerCredential(val header: String, val value: String) {

    override fun toString(): String = "$header=…"
}
