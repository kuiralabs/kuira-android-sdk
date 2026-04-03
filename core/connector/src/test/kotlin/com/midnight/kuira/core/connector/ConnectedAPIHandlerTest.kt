package com.midnight.kuira.core.connector

import com.midnight.kuira.core.connector.model.Configuration
import com.midnight.kuira.core.connector.model.ConnectionStatus
import com.midnight.kuira.core.connector.model.ConnectorApiError
import com.midnight.kuira.core.connector.model.DustBalance
import com.midnight.kuira.core.connector.model.DesiredInput
import com.midnight.kuira.core.connector.model.DesiredOutput
import com.midnight.kuira.core.connector.model.ErrorCodes
import com.midnight.kuira.core.connector.model.HistoryEntry
import com.midnight.kuira.core.connector.model.IntentId
import com.midnight.kuira.core.connector.model.IntentOptions
import com.midnight.kuira.core.connector.model.SignDataOptions
import com.midnight.kuira.core.connector.model.SignEncoding
import com.midnight.kuira.core.connector.model.SignatureResult
import com.midnight.kuira.core.connector.model.TransferKind
import com.midnight.kuira.core.connector.model.TxStatus
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

    // ── signData ──

    @Test
    fun `signData with hex encoding returns signature`() = runTest {
        val handlerWithSigner = createHandlerWithSigner { data, _ ->
            SignatureResult(data = data, signature = "sig_hex", verifyingKey = "vk_hex")
        }
        val result = handlerWithSigner.signData("deadbeef", SignDataOptions(SignEncoding.HEX))
        assertEquals("deadbeef", result.data)
        assertEquals("sig_hex", result.signature)
    }

    // ── submitTransaction ──

    @Test
    fun `submitTransaction delegates to submitter`() = runTest {
        var submitted = false
        val handlerWithSubmitter = createHandlerWithSubmitter { submitted = true }
        handlerWithSubmitter.submitTransaction("sealed_tx_hex")
        assertTrue("Transaction should be submitted", submitted)
    }

    // ── getProvingProvider ──

    @Test
    fun `getProvingProvider returns local prover info`() {
        val result = handler.getProvingProvider()
        // Default: no prover server URI (local proving)
        assertNull(result.proverServerUri)
    }

    // ── hintUsage ──

    @Test
    fun `hintUsage does not throw`() {
        handler.hintUsage(listOf("getUnshieldedBalances", "makeTransfer"))
    }

    // ── makeTransfer ──

    @Test
    fun `makeTransfer throws when no fn configured`() = runTest {
        try {
            handler.makeTransfer(emptyList())
            fail("Expected ConnectorApiError")
        } catch (e: ConnectorApiError) {
            assertEquals(ErrorCodes.INTERNAL_ERROR, e.code)
        }
    }

    @Test
    fun `makeTransfer delegates to fn and returns tx hex`() = runTest {
        var receivedOutputs: List<DesiredOutput>? = null
        var receivedPayFees: Boolean? = null
        val handlerWithTransfer = createHandlerWithTransfer { outputs, payFees ->
            receivedOutputs = outputs
            receivedPayFees = payFees
            "sealed_tx_abc123"
        }
        val outputs = listOf(
            DesiredOutput(
                kind = TransferKind.SHIELDED,
                type = "0".repeat(64),
                value = BigInteger("1000000"),
                recipient = "mn_shield-addr_test1recipient",
            )
        )
        val result = handlerWithTransfer.makeTransfer(outputs, payFees = true)
        assertEquals("sealed_tx_abc123", result)
        assertEquals(1, receivedOutputs?.size)
        assertEquals(true, receivedPayFees)
    }

    // ── getTxHistory ──

    @Test
    fun `getTxHistory returns empty when no fn configured`() = runTest {
        assertTrue(handler.getTxHistory(pageNumber = 0, pageSize = 10).isEmpty())
    }

    @Test
    fun `getTxHistory delegates to fn and returns entries`() = runTest {
        val entries = listOf(
            HistoryEntry(txHash = "0xabc", txStatus = TxStatus.Pending),
            HistoryEntry(txHash = "0xdef", txStatus = TxStatus.Finalized(mapOf(0 to "Success"))),
        )
        val handlerWithHistory = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = handler.walletAddresses,
            getTxHistoryFn = { _, _ -> entries },
        )
        val result = handlerWithHistory.getTxHistory(pageNumber = 0, pageSize = 10)
        assertEquals(2, result.size)
        assertEquals("0xabc", result[0].txHash)
        assertTrue(result[1].txStatus is TxStatus.Finalized)
    }

    // ── balanceUnsealedTransaction ──

    @Test
    fun `balanceUnsealedTransaction throws when no fn configured`() = runTest {
        try {
            handler.balanceUnsealedTransaction("unbalanced_tx_hex")
            fail("Expected ConnectorApiError")
        } catch (e: ConnectorApiError) {
            assertEquals(ErrorCodes.INTERNAL_ERROR, e.code)
        }
    }

    @Test
    fun `balanceUnsealedTransaction delegates to fn`() = runTest {
        var receivedTx: String? = null
        var receivedPayFees: Boolean? = null
        val h = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = handler.walletAddresses,
            balanceUnsealedFn = { tx, payFees ->
                receivedTx = tx
                receivedPayFees = payFees
                "balanced_unsealed_tx"
            },
        )
        val result = h.balanceUnsealedTransaction("unbalanced_tx_hex", payFees = true)
        assertEquals("balanced_unsealed_tx", result)
        assertEquals("unbalanced_tx_hex", receivedTx)
        assertEquals(true, receivedPayFees)
    }

    // ── balanceSealedTransaction ──

    @Test
    fun `balanceSealedTransaction throws when no fn configured`() = runTest {
        try {
            handler.balanceSealedTransaction("unsealed_tx_hex")
            fail("Expected ConnectorApiError")
        } catch (e: ConnectorApiError) {
            assertEquals(ErrorCodes.INTERNAL_ERROR, e.code)
        }
    }

    @Test
    fun `balanceSealedTransaction delegates to fn`() = runTest {
        var receivedTx: String? = null
        val h = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = handler.walletAddresses,
            balanceSealedFn = { tx, _ ->
                receivedTx = tx
                "balanced_sealed_tx"
            },
        )
        val result = h.balanceSealedTransaction("unsealed_tx_hex")
        assertEquals("balanced_sealed_tx", result)
        assertEquals("unsealed_tx_hex", receivedTx)
    }

    // ── makeIntent ──

    @Test
    fun `makeIntent throws when no fn configured`() = runTest {
        try {
            handler.makeIntent(
                desiredInputs = emptyList(),
                desiredOutputs = emptyList(),
                options = IntentOptions(intentId = IntentId.Random, payFees = true),
            )
            fail("Expected ConnectorApiError")
        } catch (e: ConnectorApiError) {
            assertEquals(ErrorCodes.INTERNAL_ERROR, e.code)
        }
    }

    @Test
    fun `makeIntent delegates to fn with inputs outputs and options`() = runTest {
        var receivedInputs: List<DesiredInput>? = null
        var receivedOutputs: List<DesiredOutput>? = null
        var receivedOptions: IntentOptions? = null
        val h = ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = handler.walletAddresses,
            makeIntentFn = { inputs, outputs, options ->
                receivedInputs = inputs
                receivedOutputs = outputs
                receivedOptions = options
                "intent_tx_hex"
            },
        )
        val inputs = listOf(
            DesiredInput(
                kind = TransferKind.SHIELDED,
                type = "0".repeat(64),
                value = BigInteger("500"),
            )
        )
        val outputs = listOf(
            DesiredOutput(
                kind = TransferKind.SHIELDED,
                type = "0".repeat(64),
                value = BigInteger("500"),
                recipient = "mn_shield-addr_test1contract",
            )
        )
        val options = IntentOptions(intentId = IntentId.Fixed(42), payFees = true)
        val result = h.makeIntent(inputs, outputs, options)
        assertEquals("intent_tx_hex", result)
        assertEquals(1, receivedInputs?.size)
        assertEquals(1, receivedOutputs?.size)
        assertEquals(42, (receivedOptions?.intentId as IntentId.Fixed).id)
    }

    // ── Test Helpers (write methods) ──

    private fun createHandlerWithSigner(
        signer: suspend (String, SignDataOptions) -> SignatureResult,
    ): ConnectedAPIHandler {
        return ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = handler.walletAddresses,
            signDataFn = signer,
        )
    }

    private fun createHandlerWithSubmitter(
        submitter: suspend (String) -> Unit,
    ): ConnectedAPIHandler {
        return ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = handler.walletAddresses,
            submitTransactionFn = submitter,
        )
    }

    private fun createHandlerWithTransfer(
        transfer: suspend (List<DesiredOutput>, Boolean) -> String,
    ): ConnectedAPIHandler {
        return ConnectedAPIHandler(
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED),
            walletAddresses = handler.walletAddresses,
            makeTransferFn = transfer,
        )
    }
}
