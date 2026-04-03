package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.Configuration
import com.midnight.kuira.core.connector.model.ConnectionStatus
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for ConnectedAPIHandler — the core SDK logic.
 *
 * Tests verify behavior through the public API without mocking internals.
 * Each method delegates to existing Kuira services; we test the delegation
 * and response formatting, not the services themselves.
 */
class ConnectedAPIHandlerTest {

    private lateinit var handler: ConnectedAPIHandler

    @Before
    fun setup() {
        val networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)
        handler = ConnectedAPIHandler(
            networkConfig = networkConfig,
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_undeployed1abc123",
                shieldedAddress = "mn_shield-addr_undeployed1def456",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_undeployed1ghi789",
            ),
        )
    }

    // ── getConfiguration ──

    @Test
    fun `getConfiguration returns correct network config`() {
        val config = handler.getConfiguration()

        assertEquals("undeployed", config.networkId)
        assertTrue(config.indexerUri.contains("8088"))
        assertTrue(config.substrateNodeUri.contains("9944"))
    }

    // ── getConnectionStatus ──

    @Test
    fun `getConnectionStatus returns connected with network id`() {
        val status = handler.getConnectionStatus()

        assertTrue(status is ConnectionStatus.Connected)
        assertEquals("undeployed", (status as ConnectionStatus.Connected).networkId)
    }

    // ── getUnshieldedAddress ──

    @Test
    fun `getUnshieldedAddress returns wallet address`() {
        val result = handler.getUnshieldedAddress()
        assertEquals("mn_addr_undeployed1abc123", result.unshieldedAddress)
    }

    // ── getShieldedAddresses ──

    @Test
    fun `getShieldedAddresses returns address and keys`() {
        val result = handler.getShieldedAddresses()
        assertEquals("mn_shield-addr_undeployed1def456", result.shieldedAddress)
        assertEquals("aa".repeat(32), result.shieldedCoinPublicKey)
        assertEquals("bb".repeat(32), result.shieldedEncryptionPublicKey)
    }

    // ── getDustAddress ──

    @Test
    fun `getDustAddress returns dust address`() {
        val result = handler.getDustAddress()
        assertEquals("mn_dust_undeployed1ghi789", result.dustAddress)
    }

    // ── getUnshieldedBalances ──

    @Test
    fun `getUnshieldedBalances returns empty when no provider`() = runTest {
        assertTrue(handler.getUnshieldedBalances().isEmpty())
    }

    @Test
    fun `getUnshieldedBalances delegates to provider`() = runTest {
        val nightToken = "0".repeat(64)
        val handlerWithBalances = createHandlerWithBalances(
            unshielded = mapOf(nightToken to BigInteger("5000000")),
        )
        val balances = handlerWithBalances.getUnshieldedBalances()
        assertEquals(BigInteger("5000000"), balances[nightToken])
    }

    // ── getShieldedBalances ──

    @Test
    fun `getShieldedBalances delegates to provider`() = runTest {
        val nightToken = "0".repeat(64)
        val handlerWithBalances = createHandlerWithBalances(
            shielded = mapOf(nightToken to BigInteger("10000000")),
        )
        val balances = handlerWithBalances.getShieldedBalances()
        assertEquals(BigInteger("10000000"), balances[nightToken])
    }

    // ── getDustBalance ──

    @Test
    fun `getDustBalance returns zero when no provider`() = runTest {
        val dust = handler.getDustBalance()
        assertEquals(BigInteger.ZERO, dust.balance)
    }

    @Test
    fun `getDustBalance delegates to provider`() = runTest {
        val handlerWithBalances = createHandlerWithBalances(
            dustBalance = DustBalance(cap = BigInteger("1000"), balance = BigInteger("500")),
        )
        val dust = handlerWithBalances.getDustBalance()
        assertEquals(BigInteger("500"), dust.balance)
        assertEquals(BigInteger("1000"), dust.cap)
    }

    // ── Test Helpers ──

    private fun createHandlerWithBalances(
        unshielded: Map<String, BigInteger> = emptyMap(),
        shielded: Map<String, BigInteger> = emptyMap(),
        dustBalance: DustBalance = DustBalance(BigInteger.ZERO, BigInteger.ZERO),
    ): ConnectedAPIHandler {
        val provider = object : BalanceProvider {
            override suspend fun getUnshieldedBalances() = unshielded
            override suspend fun getShieldedBalances() = shielded
            override suspend fun getDustBalance() = dustBalance
        }
        return ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = WalletAddresses(
                unshieldedAddress = "mn_addr_undeployed1abc123",
                shieldedAddress = "mn_shield-addr_undeployed1def456",
                shieldedCoinPublicKey = "aa".repeat(32),
                shieldedEncryptionPublicKey = "bb".repeat(32),
                dustAddress = "mn_dust_undeployed1ghi789",
            ),
            balanceProvider = provider,
        )
    }

    // ── hintUsage ──

    @Test
    fun `hintUsage does not throw`() {
        handler.hintUsage(listOf("getUnshieldedBalances", "makeTransfer"))
    }
}
