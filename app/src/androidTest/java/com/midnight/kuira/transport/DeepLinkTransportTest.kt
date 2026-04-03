package com.midnight.kuira.transport

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.connector.ConnectedAPIHandler
import com.midnight.kuira.core.connector.InitialAPI
import com.midnight.kuira.core.connector.WalletAddresses
import com.midnight.kuira.core.connector.model.ConnectionStatus
import com.midnight.kuira.core.connector.model.ConnectorApiError
import com.midnight.kuira.core.connector.model.ErrorCodes
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Deep Link transport test.
 *
 * Simulates what happens when an external app sends:
 *   midnight://connect?networkId=undeployed
 *
 * Tests the InitialAPI.connect() handshake that deep links trigger,
 * including URI parsing and network validation.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkTransportTest {

    private lateinit var initialAPI: InitialAPI
    private val networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)

    @Before
    fun setup() {
        initialAPI = InitialAPI(
            networkConfig = networkConfig,
            handlerFactory = { _ ->
                ConnectedAPIHandler(
                    networkConfig = networkConfig,
                    walletAddresses = WalletAddresses(
                        unshieldedAddress = "mn_addr_deeplink_test",
                        shieldedAddress = "mn_shield_deeplink_test",
                        shieldedCoinPublicKey = "aa".repeat(32),
                        shieldedEncryptionPublicKey = "bb".repeat(32),
                        dustAddress = "mn_dust_deeplink_test",
                    ),
                )
            },
        )
    }

    // ── Deep link URI parsing ──

    @Test
    fun deepLink_parseConnectUri() {
        val uri = Uri.parse("midnight://connect?networkId=undeployed")
        assertEquals("midnight", uri.scheme)
        assertEquals("connect", uri.host)
        assertEquals("undeployed", uri.getQueryParameter("networkId"))
    }

    @Test
    fun deepLink_parseConnectUriWithCallback() {
        val uri = Uri.parse("midnight://connect?networkId=undeployed&callback=myapp://connected")
        assertEquals("undeployed", uri.getQueryParameter("networkId"))
        assertEquals("myapp://connected", uri.getQueryParameter("callback"))
    }

    // ── InitialAPI.connect() — the handshake triggered by deep link ──

    @Test
    fun deepLink_connectMatchingNetwork() {
        val networkId = Uri.parse("midnight://connect?networkId=undeployed")
            .getQueryParameter("networkId")!!
        val handler = initialAPI.connect(networkId)
        assertNotNull(handler)

        val status = handler.getConnectionStatus()
        assertTrue(status is ConnectionStatus.Connected)
        assertEquals("undeployed", (status as ConnectionStatus.Connected).networkId)
    }

    @Test
    fun deepLink_connectWrongNetworkRejects() {
        val networkId = Uri.parse("midnight://connect?networkId=mainnet")
            .getQueryParameter("networkId")!!
        try {
            initialAPI.connect(networkId)
            fail("Should reject wrong network")
        } catch (e: ConnectorApiError) {
            assertEquals(ErrorCodes.INVALID_REQUEST, e.code)
        }
    }

    @Test
    fun deepLink_connectedHandlerReturnsAddresses() {
        val handler = initialAPI.connect("undeployed")
        assertEquals("mn_addr_deeplink_test", handler.getUnshieldedAddress().unshieldedAddress)
        assertEquals("mn_shield_deeplink_test", handler.getShieldedAddresses().shieldedAddress)
    }

    // ── Intent creation (what Android would fire) ──

    @Test
    fun deepLink_intentCanBeCreated() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("midnight://connect?networkId=undeployed"))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("midnight", intent.data?.scheme)
        assertEquals("undeployed", intent.data?.getQueryParameter("networkId"))
    }
}
