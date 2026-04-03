package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.ConnectorApiError
import com.midnight.kuira.core.connector.model.ErrorCodes
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import org.junit.Assert.*
import org.junit.Test

/**
 * Simulates Deep Link transport.
 *
 * Deep link = external app sends `midnight://connect?networkId=undeployed`
 * which triggers InitialAPI.connect(). After connection, the dApp
 * switches to WebSocket or Bound Service for ongoing operations.
 *
 * These tests prove the connect handshake works correctly —
 * the entry point that all transports share.
 */
class DeepLinkTransportTest {

    private val networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)

    private val initialAPI = InitialAPI(
        networkConfig = networkConfig,
        handlerFactory = { _ ->
            ConnectedAPIHandler(
                networkConfig = networkConfig,
                walletAddresses = WalletAddresses(
                    unshieldedAddress = "mn_addr_test",
                    shieldedAddress = "mn_shield_test",
                    shieldedCoinPublicKey = "aa".repeat(32),
                    shieldedEncryptionPublicKey = "bb".repeat(32),
                    dustAddress = "mn_dust_test",
                ),
            )
        },
    )

    @Test
    fun `deep link - connect with matching network returns handler`() {
        val handler = initialAPI.connect("undeployed")
        assertNotNull(handler)
        assertEquals("mn_addr_test", handler.walletAddresses.unshieldedAddress)
    }

    @Test
    fun `deep link - connect with wrong network rejects`() {
        try {
            initialAPI.connect("mainnet")
            fail("Expected ConnectorApiError")
        } catch (e: ConnectorApiError) {
            assertEquals(ErrorCodes.INVALID_REQUEST, e.code)
            assertTrue(e.message.contains("mainnet"))
            assertTrue(e.message.contains("undeployed"))
        }
    }

    @Test
    fun `deep link - rdns identifies wallet`() {
        assertEquals("com.kuira.wallet", initialAPI.rdns)
    }

    @Test
    fun `deep link - apiVersion for client compatibility check`() {
        assertEquals("4.0.1", initialAPI.apiVersion)
    }

    @Test
    fun `deep link - connect then use handler for read`() {
        val handler = initialAPI.connect("undeployed")
        val status = handler.getConnectionStatus()
        assertTrue(status is com.midnight.kuira.core.connector.model.ConnectionStatus.Connected)
    }

    @Test
    fun `deep link - connect then use handler for address`() {
        val handler = initialAPI.connect("undeployed")
        assertEquals("mn_addr_test", handler.getUnshieldedAddress().unshieldedAddress)
        assertEquals("mn_shield_test", handler.getShieldedAddresses().shieldedAddress)
        assertEquals("mn_dust_test", handler.getDustAddress().dustAddress)
    }
}
