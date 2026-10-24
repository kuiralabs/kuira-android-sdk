package com.midnight.kuira.core.indexer.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No indexer exception may carry a live Blockfrost project id.
 *
 * One did, all the way to the Send screen: the HTTP client quotes the request URL in its own
 * message, PreProd's URL carries `?project_id=<id>`, and the throw site interpolated that message
 * verbatim. A user photographed their own credential.
 */
class IndexerExceptionRedactionTest {

    @Test
    fun `an HTTP error cannot leak the project id`() {
        val e = HttpException(HTTP_NOT_FOUND, "HTTP error: Client request(POST $URL) invalid: 404")

        assertFalse("the id must not survive: ${e.message}", e.message.orEmpty().contains(PROJECT_ID))
        assertTrue("the status must survive", e.message.orEmpty().contains(HTTP_NOT_FOUND.toString()))
        assertTrue("the host must survive, or the error says nothing", e.message.orEmpty().contains(HOST))
    }

    /** Every subclass inherits the redaction — a new one cannot opt out by forgetting. */
    @Test
    fun `every indexer exception type redacts`() {
        val leaks: List<IndexerException> = listOf(
            NetworkException("Network I/O error: connect to $URL failed"),
            InvalidResponseException("Unexpected error: $URL"),
            TimeoutException("timed out calling $URL"),
            RetryExhaustedException("gave up on $URL", attempts = 3),
            GraphQLException(errors = listOf("boom"), message = "query failed against $URL"),
            ApiVersionException("unsupported API at $URL"),
        )

        leaks.forEach { e ->
            assertFalse("${e::class.simpleName} leaked the id: ${e.message}", e.message.orEmpty().contains(PROJECT_ID))
        }
    }

    private companion object {
        private const val HTTP_NOT_FOUND = 404
        private const val HOST = "midnight-preprod.blockfrost.io"
        private const val PROJECT_ID = "nightpreprodTESTONLYTESTONLYTEST"
        private const val URL = "https://$HOST/api/v0?project_id=$PROJECT_ID"
    }
}
