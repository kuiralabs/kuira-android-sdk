package com.midnight.kuira.feature.send

import com.midnight.kuira.core.indexer.di.SubscriptionManagerFactory
import com.midnight.kuira.core.indexer.model.TokenBalance
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.api.TransactionSerializer
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.core.ledger.builder.UnshieldedTransactionBuilder
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.indexer.model.TokenTypeMapper
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*
import java.math.BigInteger

/**
 * Unit tests for SendViewModel.
 *
 * Tests transaction flow, validation, and state management.
 *
 * **Note:** These are unit tests with mocked dependencies.
 * Integration tests should test with real blockchain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendViewModelTest {

    private lateinit var balanceRepository: BalanceRepository
    private lateinit var utxoManager: UtxoManager
    private lateinit var transactionSubmitter: TransactionSubmitter
    private lateinit var serializer: TransactionSerializer
    private lateinit var indexerClient: com.midnight.kuira.core.indexer.api.IndexerClient
    private lateinit var dustRepository: DustRepository
    private lateinit var shieldedRepository: com.midnight.kuira.core.indexer.repository.ShieldedRepository
    private lateinit var subscriptionManagerFactory: SubscriptionManagerFactory
    private lateinit var syncStateManager: SyncStateManager
    private lateinit var viewModel: SendViewModel

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        balanceRepository = mock()
        utxoManager = mock()
        transactionSubmitter = mock()
        serializer = mock()
        indexerClient = mock()
        dustRepository = mock()
        shieldedRepository = mock()
        subscriptionManagerFactory = mock()
        syncStateManager = mock()

        viewModel = SendViewModel(
            balanceRepository = balanceRepository,
            utxoManager = utxoManager,
            transactionSubmitter = transactionSubmitter,
            serializer = serializer,
            indexerClient = indexerClient,
            dustRepository = dustRepository,
            shieldedRepository = shieldedRepository,
            subscriptionManagerFactory = subscriptionManagerFactory,
            syncStateManager = syncStateManager,
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.PREPROD)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========================================================================
    // loadBalance Tests
    // ========================================================================

    @Test
    fun `loadBalance with valid address shows balance`() = runTest {
        // Generate valid Bech32m address
        val publicKey = ByteArray(32) { it.toByte() }
        val address = com.midnight.kuira.core.crypto.address.Bech32m.encode("mn_addr_preview", publicKey)
        val balance = BigInteger.valueOf(1000000)

        whenever(balanceRepository.observeBalances(address)).thenReturn(
            flowOf(
                listOf(
                    TokenBalance(
                        tokenType = TokenTypeMapper.NIGHT_SYMBOL,
                        balance = balance,
                        utxoCount = 1
                    )
                )
            )
        )

        viewModel.loadBalance(address)

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Idle)
        assertEquals(balance, (state as SendUiState.Idle).availableBalance)
    }

    @Test
    fun `loadBalance with blank address shows error`() = runTest {
        viewModel.loadBalance("")

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Error)
        assertEquals("Address cannot be empty", (state as SendUiState.Error).message)
    }

    @Test
    fun `loadBalance with invalid address shows error`() = runTest {
        viewModel.loadBalance("invalid_address")

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Error)
        assertTrue((state as SendUiState.Error).message.contains("Invalid"))
    }

    @Test
    fun `loadBalance with no UTXOs returns zero balance`() = runTest {
        // Generate valid Bech32m address
        val publicKey = ByteArray(32) { (it * 2).toByte() }
        val address = com.midnight.kuira.core.crypto.address.Bech32m.encode("mn_addr_preview", publicKey)

        whenever(balanceRepository.observeBalances(address)).thenReturn(
            flowOf(emptyList())
        )

        viewModel.loadBalance(address)

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Idle)
        assertEquals(BigInteger.ZERO, (state as SendUiState.Idle).availableBalance)
    }

    // ========================================================================
    // sendTransaction Validation Tests
    // ========================================================================

    @Test
    fun `sendTransaction with zero amount shows error`() = runTest {
        // Generate valid addresses for this test
        val fromKey = ByteArray(32) { (it * 11).toByte() }
        val toKey = ByteArray(32) { (it * 13).toByte() }
        val fromAddress = com.midnight.kuira.core.crypto.address.Bech32m.encode("mn_addr_preview", fromKey)
        val toAddress = com.midnight.kuira.core.crypto.address.Bech32m.encode("mn_addr_preview", toKey)

        viewModel.sendTransaction(
            fromAddress = fromAddress,
            toAddress = toAddress,
            amount = BigInteger.ZERO,
            seedPhrase = "test seed phrase"
        )

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Error)
        assertEquals("Amount must be greater than zero", (state as SendUiState.Error).message)
    }

    @Test
    fun `sendTransaction with negative amount shows error`() = runTest {
        // Generate valid addresses for this test
        val fromKey = ByteArray(32) { (it * 17).toByte() }
        val toKey = ByteArray(32) { (it * 19).toByte() }
        val fromAddress = com.midnight.kuira.core.crypto.address.Bech32m.encode("mn_addr_preview", fromKey)
        val toAddress = com.midnight.kuira.core.crypto.address.Bech32m.encode("mn_addr_preview", toKey)

        viewModel.sendTransaction(
            fromAddress = fromAddress,
            toAddress = toAddress,
            amount = BigInteger.valueOf(-100),
            seedPhrase = "test seed phrase"
        )

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Error)
        assertEquals("Amount must be greater than zero", (state as SendUiState.Error).message)
    }

    @Test
    fun `sendTransaction with invalid recipient address shows error`() = runTest {
        viewModel.sendTransaction(
            fromAddress = "mn_addr_preview1test",
            toAddress = "invalid_address",
            amount = BigInteger.valueOf(100),
            seedPhrase = "test seed phrase"
        )

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Error)
        assertTrue((state as SendUiState.Error).message.contains("Invalid"))
    }

    // ========================================================================
    // reset Tests
    // ========================================================================

    @Test
    fun `reset returns to Idle state`() = runTest {
        // Mock balance repository for reset
        whenever(balanceRepository.observeBalances(TEST_ADDRESS)).thenReturn(
            flowOf(emptyList())
        )

        // Set to error state
        viewModel.loadBalance("")
        assertTrue(viewModel.state.value is SendUiState.Error)

        // Reset with valid address
        viewModel.reset(TEST_ADDRESS)

        val state = viewModel.state.value
        assertTrue(state is SendUiState.Idle)
        assertEquals(BigInteger.ZERO, (state as SendUiState.Idle).availableBalance)
    }

    // ========================================================================
    // State Transition Tests
    // ========================================================================

    @Test
    fun `sendTransaction transitions through states correctly`() = runTest {
        // Initial state
        assertTrue(viewModel.state.value is SendUiState.Idle)

        // Start transaction with invalid input triggers Building then Error
        viewModel.sendTransaction(
            fromAddress = "mn_addr_preview1test",
            toAddress = "invalid",
            amount = BigInteger.ONE,
            seedPhrase = "test"
        )

        // Should show error
        assertTrue(viewModel.state.value is SendUiState.Error)
    }

    private companion object {
        // Valid test address generated from known public key
        val TEST_ADDRESS = com.midnight.kuira.core.crypto.address.Bech32m.encode(
            "mn_addr_preview",
            ByteArray(32) { it.toByte() }
        )
    }
}
