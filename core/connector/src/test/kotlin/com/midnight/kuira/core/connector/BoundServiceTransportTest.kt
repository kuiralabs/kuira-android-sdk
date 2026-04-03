package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.DustBalance
import com.midnight.kuira.core.connector.model.SignDataOptions
import com.midnight.kuira.core.connector.model.SignEncoding
import com.midnight.kuira.core.connector.model.SignatureResult
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Simulates Android Bound Service transport.
 *
 * Bound Service = direct in-process Kotlin API calls on ConnectedAPIHandler.
 * No JSON-RPC, no WebSocket. The fastest transport — type-safe, zero serialization.
 *
 * These tests prove that a native Android dApp binding to Kuira's Service
 * can call all ConnectedAPI methods directly.
 */
class BoundServiceTransportTest {

    private lateinit var handler: ConnectedAPIHandler
    private val nightToken = "0".repeat(64)

    @Before
    fun setup() {
        handler = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_undeployed1abc",
                shieldedAddress = "mn_shield-addr_undeployed1def",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_undeployed1ghi",
            ),
            balanceProvider = object : BalanceProvider {
                override suspend fun getUnshieldedBalances() =
                    mapOf(nightToken to BigInteger("5000000"))
                override suspend fun getShieldedBalances() =
                    mapOf(nightToken to BigInteger("10000000"))
                override suspend fun getDustBalance() =
                    DustBalance(BigInteger("1000"), BigInteger("500"))
            },
            signDataFn = { data, _ ->
                SignatureResult(data, "sig_$data", "vk_1")
            },
            submitTransactionFn = { },
            makeTransferFn = { _, _ -> "tx_hex" },
            balanceUnsealedFn = { tx, _ -> "balanced_$tx" },
            balanceSealedFn = { tx, _ -> "sealed_$tx" },
            makeIntentFn = { _, _, _ -> "intent_hex" },
        )
    }

    // ── Read operations (direct Kotlin calls) ──

    @Test
    fun `bound service - getConnectionStatus`() {
        val status = handler.getConnectionStatus()
        assertTrue(status is com.midnight.kuira.core.connector.model.ConnectionStatus.Connected)
    }

    @Test
    fun `bound service - getConfiguration`() {
        val config = handler.getConfiguration()
        assertEquals("undeployed", config.networkId)
        assertTrue(config.indexerUri.contains("/graphql"))
    }

    @Test
    fun `bound service - getUnshieldedBalances`() = runTest {
        val balances = handler.getUnshieldedBalances()
        assertEquals(BigInteger("5000000"), balances[nightToken])
    }

    @Test
    fun `bound service - getShieldedBalances`() = runTest {
        val balances = handler.getShieldedBalances()
        assertEquals(BigInteger("10000000"), balances[nightToken])
    }

    @Test
    fun `bound service - getDustBalance`() = runTest {
        val dust = handler.getDustBalance()
        assertEquals(BigInteger("500"), dust.balance)
        assertEquals(BigInteger("1000"), dust.cap)
    }

    @Test
    fun `bound service - addresses`() {
        assertEquals("mn_addr_undeployed1abc", handler.getUnshieldedAddress().unshieldedAddress)
        assertEquals("mn_shield-addr_undeployed1def", handler.getShieldedAddresses().shieldedAddress)
        assertEquals("mn_dust_undeployed1ghi", handler.getDustAddress().dustAddress)
    }

    // ── Write operations (direct Kotlin calls) ──

    @Test
    fun `bound service - signData`() = runTest {
        val result = handler.signData("cafe", SignDataOptions(SignEncoding.HEX))
        assertEquals("cafe", result.data)
        assertEquals("sig_cafe", result.signature)
    }

    @Test
    fun `bound service - makeTransfer`() = runTest {
        val tx = handler.makeTransfer(emptyList())
        assertEquals("tx_hex", tx)
    }

    @Test
    fun `bound service - submitTransaction`() = runTest {
        handler.submitTransaction("sealed_tx")
        // no exception = success
    }

    @Test
    fun `bound service - balanceUnsealedTransaction`() = runTest {
        val tx = handler.balanceUnsealedTransaction("raw_tx")
        assertEquals("balanced_raw_tx", tx)
    }

    @Test
    fun `bound service - balanceSealedTransaction`() = runTest {
        val tx = handler.balanceSealedTransaction("raw_tx")
        assertEquals("sealed_raw_tx", tx)
    }

    @Test
    fun `bound service - makeIntent`() = runTest {
        val tx = handler.makeIntent(
            emptyList(), emptyList(),
            com.midnight.kuira.core.connector.model.IntentOptions(
                com.midnight.kuira.core.connector.model.IntentId.Random, true
            ),
        )
        assertEquals("intent_hex", tx)
    }
}
