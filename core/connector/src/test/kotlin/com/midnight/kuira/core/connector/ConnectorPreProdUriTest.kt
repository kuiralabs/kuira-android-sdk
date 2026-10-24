package com.midnight.kuira.core.connector

import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connector protocol passes URIs and nothing else, so a credentialed indexer must carry its
 * credential in the URI — otherwise a connected dApp gets a 403 it cannot fix.
 */
class ConnectorPreProdUriTest {

    @Test
    fun `a credentialed indexer URI carries the credential`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID)

        listOf(config.indexerGraphqlUrl, config.indexerWsUrl).forEach { url ->
            val uri = NetworkConfig.withCredentialInUrl(url, config.indexerCredential)
            assertTrue("a dApp cannot authenticate with: $uri", uri.contains(PREPROD_ID))
            assertTrue("the path must survive the credential: $uri", uri.startsWith(url))
        }
    }

    /** A self-hosted indexer needs none, and must not grow an empty query string. */
    @Test
    fun `an uncredentialed indexer URI is untouched`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREVIEW)

        assertEquals(
            config.indexerGraphqlUrl,
            NetworkConfig.withCredentialInUrl(config.indexerGraphqlUrl, config.indexerCredential),
        )
    }

    private companion object {
        private const val PREPROD_ID = "nightpreprodTESTONLYTESTONLYTEST"
    }
}
