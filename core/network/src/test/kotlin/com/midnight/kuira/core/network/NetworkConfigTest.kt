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
    fun `PREPROD points at Blockfrost and carries the project id`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID)

        // The HOST moved and so did the PATH. Changing only the host yields a 404, which reads
        // like a different fault — so both are asserted.
        assertTrue(
            "indexer must be the Blockfrost host, was: ${config.redacted().indexerBaseUrl}",
            config.indexerBaseUrl.startsWith("https://${NetworkConfig.BLOCKFROST_PREPROD_HOST}"),
        )
        assertTrue(
            "indexer must use Blockfrost's path, was: ${config.redacted().indexerBaseUrl}",
            config.indexerBaseUrl.contains(NetworkConfig.BLOCKFROST_API_PATH),
        )
        assertTrue(
            "the project id travels as a query parameter, not a header",
            config.indexerBaseUrl.contains("${NetworkConfig.BLOCKFROST_PROJECT_ID_PARAM}=$PREPROD_ID"),
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
     * The credential is IN the URL, so anything that logs a URL logs a working key. This is the
     * one form that is safe to print, and it has to hide the id while leaving the rest readable —
     * a redaction that blanked the whole URL would make logs useless and get bypassed.
     */
    @Test
    fun `redacted hides the project id and keeps the URL legible`() {
        val redacted = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID).redacted()

        assertTrue(
            "the id must not survive redaction: ${redacted.indexerBaseUrl}",
            !redacted.indexerBaseUrl.contains(PREPROD_ID),
        )
        assertTrue(
            "the host must still be readable: ${redacted.indexerBaseUrl}",
            redacted.indexerBaseUrl.contains(NetworkConfig.BLOCKFROST_PREPROD_HOST),
        )
    }

    /**
     * The bug this method exists for. The indexer composes its endpoints from the base URL, and
     * `"$baseUrl/graphql"` was correct until PreProd's base started carrying `?project_id=<id>`.
     * Concatenating then puts the path INSIDE the credential — `...?project_id=<id>/graphql` —
     * and the request fails as a bad project id, pointing the blame at the id instead of the URL.
     */
    @Test
    fun `appendPath inserts before a query string instead of after it`() {
        val base = NetworkConfig.forNetwork(MidnightNetwork.PREPROD, PREPROD_ID).indexerBaseUrl
        val endpoint = NetworkConfig.appendPath(base, "/graphql")

        assertTrue(
            "the path must not land inside the project id: ${NetworkConfig.redactProjectId(endpoint)}",
            !endpoint.contains("$PREPROD_ID/graphql"),
        )
        assertTrue(
            "the path must precede the query: ${NetworkConfig.redactProjectId(endpoint)}",
            endpoint.contains("/api/v0/graphql?"),
        )
        assertTrue(
            "the project id must survive intact",
            endpoint.endsWith("${NetworkConfig.BLOCKFROST_PROJECT_ID_PARAM}=$PREPROD_ID"),
        )
    }

    /** A URL with no query string must be untouched, so the helper is safe everywhere. */
    @Test
    fun `appendPath on a plain URL just appends`() {
        assertEquals(
            "https://indexer.preview.midnight.network/api/v4/graphql",
            NetworkConfig.appendPath("https://indexer.preview.midnight.network/api/v4", "/graphql"),
        )
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
        assertTrue(config.indexerBaseUrl.startsWith("https://"))
        assertTrue(config.nodeRpcUrl.startsWith("wss://"))
        assertTrue(config.proofServerUrl.endsWith(":${NetworkConfig.LOCALNET_PROOF_SERVER_PORT}"))
    }

    @Test
    fun `UNDEPLOYED uses HTTP for everything and the right ports`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)

        assertTrue(config.indexerBaseUrl.startsWith("http://"))
        assertTrue(
            "indexer should target port 8088, was: ${config.indexerBaseUrl}",
            config.indexerBaseUrl.contains(":${NetworkConfig.LOCALNET_INDEXER_PORT}/")
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
