package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.ConnectorApiError
import com.midnight.kuira.core.connector.model.ErrorCodes
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import org.junit.Assert.*
import org.junit.Test

class InitialAPITest {

    private val networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)

    private val api = InitialAPI(
        networkConfig = networkConfig,
        handlerFactory = { networkId ->
            ConnectedAPIHandler(
                networkConfig = networkConfig,
                walletAddresses = WalletAddresses(
                    unshieldedAddress = "mn_addr_test",
                    shieldedAddress = "mn_shield-addr_test",
                    shieldedCoinPublicKey = "aa".repeat(32),
                    shieldedEncryptionPublicKey = "bb".repeat(32),
                    dustAddress = "mn_dust_test",
                ),
            )
        },
    )

    @Test
    fun `rdns identifies Kuira wallet`() {
        assertEquals("com.kuira.wallet", api.rdns)
    }

    @Test
    fun `apiVersion matches dapp-connector-api`() {
        assertEquals("4.0.1", api.apiVersion)
    }

    @Test
    fun `connect returns handler for matching network`() {
        val handler = api.connect("undeployed")
        assertNotNull(handler)
        assertEquals("mn_addr_test", handler.walletAddresses.unshieldedAddress)
    }

    @Test
    fun `connect throws on network mismatch`() {
        try {
            api.connect("mainnet")
            fail("Expected ConnectorApiError")
        } catch (e: ConnectorApiError) {
            assertEquals(ErrorCodes.INVALID_REQUEST, e.code)
            assertTrue(e.message.contains("mainnet"))
            assertTrue(e.message.contains("undeployed"))
        }
    }
}
