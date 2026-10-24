// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NetworkConfig].
 *
 * Note: [DeviceType.isEmulator] reads `android.os.Build` fields. In JVM unit
 * tests these are mocked to default values (empty strings), which means
 * `isEmulator` evaluates based on a few `startsWith("")` checks. We assert
 * the structural properties of the URLs (correct ports, correct schemes)
 * rather than which specific host string ends up in them.
 */
class NetworkConfigTest {

    /** Shaped like a real PreProd id (correct prefix); not a working credential. */
    private val PREPROD_ID = "nightpreprodTESTONLYTESTONLYTEST"

    @Test
    fun `port constants match the docker-compose values`() {
        // These must match the Gradle adbReverseLocalnet task and the
        // upstream localnet docker-compose. Catch accidental drift.
        assertEquals(8088, NetworkConfig.LOCALNET_INDEXER_PORT)
        assertEquals(9944, NetworkConfig.LOCALNET_NODE_RPC_PORT)
        assertEquals(6300, NetworkConfig.LOCALNET_PROOF_SERVER_PORT)
    }

    @Test
    fun `PREPROD points at Blockfrost`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID)

        // The HOST moved and so did the PATH. Changing only the host yields a 404, which reads
        // like a different fault — so both are asserted.
        assertTrue(
            "indexer must be the Blockfrost host, was: ${config.indexerGraphqlUrl}",
            config.indexerGraphqlUrl.startsWith("https://${NetworkConfig.BLOCKFROST_PREPROD_HOST}"),
        )
        assertTrue(
            "indexer must use Blockfrost's path, was: ${config.indexerGraphqlUrl}",
            config.indexerGraphqlUrl.contains(NetworkConfig.BLOCKFROST_API_PATH),
        )
        assertEquals(
            "the credential travels as a header",
            IndexerCredential(NetworkConfig.BLOCKFROST_PROJECT_ID_PARAM, PREPROD_ID),
            config.indexerCredential,
        )
        // Only the INDEXER moved. The node is unaffected — which is exactly why a node-level
        // health probe passes while every chain read fails.
        assertEquals("wss://rpc.preprod.midnight.network", config.nodeRpcUrl)
        assertTrue(config.proofServerUrl.startsWith("http://"))
        assertTrue(config.proofServerUrl.endsWith(":${NetworkConfig.LOCALNET_PROOF_SERVER_PORT}"))
    }

    /**
     * The decommission, encoded. `indexer.preprod.midnight.network` answered until 2026-10-09 and
     * now returns HTTP 410; building a PreProd config without a project id used to succeed and
     * produce exactly that dead URL. Failing at construction is the difference between one clear
     * sentence and a 410 surfacing from inside a wallet sync.
     */
    @Test
    fun `PREPROD without a project id fails loudly instead of building a dead config`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig.forNetwork(MidnightNetwork.PREPROD)
        }
        val message = error.message.orEmpty()
        assertTrue("must say PreProd will not work: $message", message.contains("not be functional"))
        assertTrue("must name the decommission: $message", message.contains("2026-10-09"))
        assertTrue("must say where to get one: $message", message.contains("blockfrost.io"))
    }

    /** Blockfrost scopes an id to one network, and rejects a foreign one as an opaque 403. */
    @Test
    fun `a project id for another network is rejected by name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            NetworkConfig.forNetwork(MidnightNetwork.PREPROD, "nightmainnetAAAAAAAAAAAAAAAAAAAA")
        }
        assertTrue(
            "must name the required prefix: ${error.message}",
            error.message.orEmpty().contains(NetworkConfig.BLOCKFROST_PREPROD_ID_PREFIX),
        )
    }

    /**
     * The invariant the whole design rests on: NO config URL may contain the credential.
     *
     * While the project id rode in the query string, every URL was a working key — it reached a
     * user's Send screen through an HTTP-client exception that quoted the request. Carrying it as
     * a header means nothing that logs, wraps or displays a URL can leak it.
     */
    @Test
    fun `no URL carries the credential`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID)

        listOf(
            "indexerGraphqlUrl" to config.indexerGraphqlUrl,
            "indexerWsUrl" to config.indexerWsUrl,
            "nodeRpcUrl" to config.nodeRpcUrl,
            "proofServerUrl" to config.proofServerUrl,
        ).forEach { (name, url) ->
            assertTrue("$name leaked the credential: $url", !url.contains(PREPROD_ID))
            assertTrue(
                "$name must not carry a credential parameter either: $url",
                !url.contains(NetworkConfig.BLOCKFROST_PROJECT_ID_PARAM),
            )
        }
    }

    /** The credential type exists to stay out of strings — including the ones it builds itself. */
    @Test
    fun `printing a credential does not print its value`() {
        val credential = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID).indexerCredential

        assertTrue("toString leaked it: $credential", !credential.toString().contains(PREPROD_ID))
        assertTrue(
            "the header name should survive so a log still says what is missing: $credential",
            credential.toString().contains(NetworkConfig.BLOCKFROST_PROJECT_ID_PARAM),
        )
    }

    /**
     * The bug that reached a user. Blockfrost serves GraphQL at the API ROOT; appending
     * `/graphql` 404s every HTTP query. The WebSocket uses a different path and kept working, so
     * balances arrived normally and only SEND failed — nothing in the gate issued an HTTP query,
     * so nothing noticed.
     */
    @Test
    fun `Blockfrost serves GraphQL at the API root, not under slash-graphql`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID)

        assertEquals(
            "https://${NetworkConfig.BLOCKFROST_PREPROD_HOST}${NetworkConfig.BLOCKFROST_API_PATH}",
            config.indexerGraphqlUrl,
        )
        assertTrue(
            "appending /graphql here 404s: ${config.indexerGraphqlUrl}",
            !config.indexerGraphqlUrl.contains(NetworkConfig.INDEXER_GRAPHQL_PATH),
        )
    }

    /** The self-hosted indexer keeps its own shape — the fix above must not move everyone. */
    @Test
    fun `the self-hosted indexer still answers GraphQL under slash-graphql`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREVIEW)

        assertTrue(
            "was: ${config.indexerGraphqlUrl}",
            config.indexerGraphqlUrl.endsWith(NetworkConfig.INDEXER_GRAPHQL_PATH),
        )
        assertEquals("a self-hosted indexer needs no credential", null, config.indexerCredential)
    }

    /**
     * HTTP and WS are resolved together because getting ONE of them wrong is the failure that
     * hides: a broken WS with working HTTP reads as a stale balance, a broken HTTP with working WS
     * reads as a healthy wallet that cannot send. Both shipped, three weeks apart.
     */
    @Test
    fun `each provider pairs its own HTTP and WebSocket endpoints`() {
        val blockfrost = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID)
        assertEquals(
            "wss://${NetworkConfig.BLOCKFROST_PREPROD_HOST}" +
                "${NetworkConfig.BLOCKFROST_API_PATH}${NetworkConfig.BLOCKFROST_WS_PATH}",
            blockfrost.indexerWsUrl,
        )

        val selfHosted = NetworkConfig.forNetwork(MidnightNetwork.PREVIEW)
        assertTrue(
            "the Midnight indexer subscribes at ${NetworkConfig.INDEXER_WS_PATH}, was: ${selfHosted.indexerWsUrl}",
            selfHosted.indexerWsUrl.endsWith(NetworkConfig.INDEXER_WS_PATH),
        )
        assertTrue("subscriptions are wss/ws", selfHosted.indexerWsUrl.startsWith("wss://"))
    }

    /** Redaction must be safe to call on anything, so no caller has to decide whether it applies. */
    @Test
    fun `redacting a URL with no project id leaves it unchanged`() {
        val plain = "https://indexer.preview.midnight.network/api/v4"
        assertEquals(plain, NetworkConfig.redactProjectId(plain))
    }

    /** Scoped to PreProd on purpose: Preview's indexer is alive and needs no credential. */
    @Test
    fun `only PREPROD requires a Blockfrost project id`() {
        assertTrue(NetworkConfig.requiresBlockfrostProjectId(MidnightNetwork.PREPROD))
        assertTrue(!NetworkConfig.requiresBlockfrostProjectId(MidnightNetwork.PREVIEW))
        assertTrue(!NetworkConfig.requiresBlockfrostProjectId(MidnightNetwork.UNDEPLOYED))
    }

    @Test
    fun `PREVIEW uses HTTPS for indexer and WSS for node`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREVIEW)
        assertTrue(config.indexerGraphqlUrl.startsWith("https://"))
        assertTrue(config.nodeRpcUrl.startsWith("wss://"))
        assertTrue(config.proofServerUrl.endsWith(":${NetworkConfig.LOCALNET_PROOF_SERVER_PORT}"))
    }

    @Test
    fun `UNDEPLOYED uses HTTP for everything and the right ports`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)

        assertTrue(config.indexerGraphqlUrl.startsWith("http://"))
        assertTrue(
            "indexer should target port 8088, was: ${config.indexerGraphqlUrl}",
            config.indexerGraphqlUrl.contains(":${NetworkConfig.LOCALNET_INDEXER_PORT}/")
        )

        assertTrue(config.nodeRpcUrl.startsWith("ws://"))
        assertTrue(
            "node RPC should target port 9944, was: ${config.nodeRpcUrl}",
            config.nodeRpcUrl.endsWith(":${NetworkConfig.LOCALNET_NODE_RPC_PORT}")
        )

        assertTrue(config.proofServerUrl.startsWith("http://"))
        assertTrue(config.proofServerUrl.endsWith(":${NetworkConfig.LOCALNET_PROOF_SERVER_PORT}"))
    }

    @Test
    fun `all networks have developmentMode true`() {
        // All three currently allow HTTP for the local proof server.
        // If we tighten this, the test will catch the change.
        MidnightNetwork.entries.forEach { network ->
            // PreProd refuses to build without a Blockfrost project id, so supply one here. The
            // id is only required where requiresBlockfrostProjectId says so, which keeps this
            // loop honest if another network is added that also needs one.
            val id = if (NetworkConfig.requiresBlockfrostProjectId(network)) PREPROD_ID else null
            assertTrue(NetworkConfig.forNetwork(network, id).developmentMode)
        }
    }
}
