// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.network

import org.junit.Assert.assertEquals
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

    @Test
    fun `port constants match the docker-compose values`() {
        // These must match the Gradle adbReverseLocalnet task and the
        // upstream localnet docker-compose. Catch accidental drift.
        assertEquals(8088, NetworkConfig.LOCALNET_INDEXER_PORT)
        assertEquals(9944, NetworkConfig.LOCALNET_NODE_RPC_PORT)
        assertEquals(6300, NetworkConfig.LOCALNET_PROOF_SERVER_PORT)
    }

    @Test
    fun `PREPROD uses HTTPS for indexer and WSS for node`() {
        val config = NetworkConfig.forNetwork(MidnightNetwork.PREPROD)
        assertTrue(config.indexerBaseUrl.startsWith("https://"))
        assertTrue(config.nodeRpcUrl.startsWith("wss://"))
        // Proof server is local
        assertTrue(config.proofServerUrl.startsWith("http://"))
        assertTrue(config.proofServerUrl.endsWith(":${NetworkConfig.LOCALNET_PROOF_SERVER_PORT}"))
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
            assertTrue(NetworkConfig.forNetwork(network).developmentMode)
        }
    }
}
