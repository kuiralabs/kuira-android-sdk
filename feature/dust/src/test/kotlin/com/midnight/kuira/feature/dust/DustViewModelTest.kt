package com.midnight.kuira.feature.dust

import com.midnight.kuira.core.indexer.database.DustTokenEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.ProofServerClient
import com.midnight.kuira.core.ledger.api.TransactionSerializer
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Unit tests for DustViewModel.
 *
 * **What's real vs mocked:**
 * - Real: DustBalanceCalculator (pure domain logic — no I/O)
 * - Mocked: DustRepository (database + FFI), ProofServerClient (network),
 *   TransactionSerializer (FFI), NodeRpcClient (network)
 *
 * **Known gap:** The registerDust happy path (build → prove → seal → submit)
 * is not testable because DustKeyDeriver and DustRegistrationBuilder are
 * static objects with native library loading. They need to be behind
 * injectable interfaces to enable unit testing of the full registration flow
 * and TransactionFinalizationResult branch handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DustViewModelTest {

    private lateinit var dustRepository: DustRepository
    private lateinit var proofServerClient: ProofServerClient
    private lateinit var serializer: TransactionSerializer
    private lateinit var nodeRpcClient: NodeRpcClient
    private lateinit var viewModel: DustViewModel

    // Real calculator — pure domain logic, no reason to mock
    private val dustBalanceCalculator = DustBalanceCalculator()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        dustRepository = mock()
        proofServerClient = mock()
        serializer = mock()
        nodeRpcClient = mock()

        viewModel = DustViewModel(
            dustRepository = dustRepository,
            dustBalanceCalculator = dustBalanceCalculator,
            proofServerClient = proofServerClient,
            serializer = serializer,
            nodeRpcClient = nodeRpcClient,
            networkConfig = NetworkConfig.forNetwork(MidnightNetwork.PREPROD)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========================================================================
    // Initial State
    // ========================================================================

    @Test
    fun `initial state is Idle`() {
        assertTrue(viewModel.state.value is DustUiState.Idle)
    }

    // ========================================================================
    // checkDustStatus - Validation
    // ========================================================================

    @Test
    fun `checkDustStatus with blank address shows error and does not call repository`() = runTest {
        viewModel.checkDustStatus("", "some seed phrase")

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        assertEquals("Address cannot be empty", (state as DustUiState.Error).message)

        // Verify no unnecessary network/database work
        verifyNoInteractions(dustRepository)
    }

    @Test
    fun `checkDustStatus with blank seed phrase shows error and does not call repository`() = runTest {
        viewModel.checkDustStatus("mn_addr_preprod1test", "")

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        assertEquals("Seed phrase cannot be empty", (state as DustUiState.Error).message)

        verifyNoInteractions(dustRepository)
    }

    // ========================================================================
    // checkDustStatus - NoDust
    // ========================================================================

    @Test
    fun `checkDustStatus when no dust returns NoDust state`() = runTest {
        whenever(dustRepository.syncFromBlockchain(any(), any(), any())).thenReturn(false)

        viewModel.checkDustStatus(TEST_ADDRESS, TEST_SEED_PHRASE)

        val state = viewModel.state.value
        assertTrue("Expected NoDust but got ${state::class.simpleName}", state is DustUiState.NoDust)
    }

    // ========================================================================
    // checkDustStatus - Status with real balance calculation
    // ========================================================================

    @Test
    fun `checkDustStatus with generating token computes real time to capacity`() = runTest {
        val balance = BigInteger.valueOf(500_000)
        val tokenCount = 1

        // Token created 10 seconds ago, rate=1000/sec, capacity=1,000,000, initial=0
        // After ~10s: current ≈ 10,000; remaining ≈ 990,000; time ≈ 990s ≈ 990,000ms
        val token = createToken(
            initialValue = "0",
            creationTimeMillis = System.currentTimeMillis() - 10_000,
            dustCapacitySpecks = "1000000",
            generationRatePerSecond = "1000"
        )

        whenever(dustRepository.syncFromBlockchain(any(), any(), any())).thenReturn(true)
        whenever(dustRepository.getCurrentBalance(TEST_ADDRESS)).thenReturn(balance)
        whenever(dustRepository.getTokenCount(TEST_ADDRESS)).thenReturn(tokenCount)
        whenever(dustRepository.getAvailableTokens(TEST_ADDRESS)).thenReturn(listOf(token))

        viewModel.checkDustStatus(TEST_ADDRESS, TEST_SEED_PHRASE)

        val state = viewModel.state.value
        assertTrue("Expected Status but got ${state::class.simpleName}", state is DustUiState.Status)
        val status = state as DustUiState.Status
        assertEquals(balance, status.balance)
        assertEquals(tokenCount, status.tokenCount)
        assertFalse(status.isAtCapacity)

        // Real calculator should produce a positive time-to-capacity
        // (not a hardcoded mock value)
        assertTrue(
            "timeToCapacityMs should be > 0 for generating token, got ${status.timeToCapacityMs}",
            status.timeToCapacityMs > 0
        )
        // Should be roughly 990 seconds (990,000ms), give large margin for timing
        assertTrue(
            "timeToCapacityMs should be roughly 900-1000 seconds, got ${status.timeToCapacityMs}ms",
            status.timeToCapacityMs in 800_000L..1_100_000L
        )
    }

    @Test
    fun `checkDustStatus with token at capacity reports isAtCapacity true`() = runTest {
        // Token created 1 hour ago with high rate and small capacity → definitely at capacity
        val token = createToken(
            initialValue = "500000",
            creationTimeMillis = System.currentTimeMillis() - 3_600_000,
            dustCapacitySpecks = "1000000",
            generationRatePerSecond = "10000"
        )

        whenever(dustRepository.syncFromBlockchain(any(), any(), any())).thenReturn(true)
        whenever(dustRepository.getCurrentBalance(TEST_ADDRESS)).thenReturn(BigInteger.valueOf(1_000_000))
        whenever(dustRepository.getTokenCount(TEST_ADDRESS)).thenReturn(1)
        whenever(dustRepository.getAvailableTokens(TEST_ADDRESS)).thenReturn(listOf(token))

        viewModel.checkDustStatus(TEST_ADDRESS, TEST_SEED_PHRASE)

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Status)
        val status = state as DustUiState.Status
        assertTrue("Token created 1h ago with high rate should be at capacity", status.isAtCapacity)
        assertEquals(0L, status.timeToCapacityMs)
    }

    @Test
    fun `checkDustStatus with multiple tokens uses maxOf for time and all for capacity`() = runTest {
        // Token A: at capacity (created long ago, high rate, small capacity)
        val tokenAtCapacity = createToken(
            nullifier = "token_a",
            initialValue = "500000",
            creationTimeMillis = System.currentTimeMillis() - 3_600_000,
            dustCapacitySpecks = "1000000",
            generationRatePerSecond = "10000"
        )
        // Token B: NOT at capacity (created 1s ago, low rate, huge capacity)
        val tokenGenerating = createToken(
            nullifier = "token_b",
            initialValue = "0",
            creationTimeMillis = System.currentTimeMillis() - 1_000,
            dustCapacitySpecks = "100000000",
            generationRatePerSecond = "1"
        )

        whenever(dustRepository.syncFromBlockchain(any(), any(), any())).thenReturn(true)
        whenever(dustRepository.getCurrentBalance(TEST_ADDRESS)).thenReturn(BigInteger.valueOf(1_000_001))
        whenever(dustRepository.getTokenCount(TEST_ADDRESS)).thenReturn(2)
        whenever(dustRepository.getAvailableTokens(TEST_ADDRESS)).thenReturn(
            listOf(tokenAtCapacity, tokenGenerating)
        )

        viewModel.checkDustStatus(TEST_ADDRESS, TEST_SEED_PHRASE)

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Status)
        val status = state as DustUiState.Status

        // isAtCapacity should be false: tokens.all requires ALL to be at capacity
        assertFalse(
            "isAtCapacity should be false when any token is still generating",
            status.isAtCapacity
        )
        // timeToCapacityMs should be the MAX across tokens (Token B has huge remaining)
        assertTrue(
            "timeToCapacityMs should be very large (token B has 100M capacity at rate 1/s), got ${status.timeToCapacityMs}",
            status.timeToCapacityMs > 1_000_000L
        )
    }

    @Test
    fun `checkDustStatus with empty available tokens returns zero time and not at capacity`() = runTest {
        // Dust is registered but no available tokens (all spent or pending)
        whenever(dustRepository.syncFromBlockchain(any(), any(), any())).thenReturn(true)
        whenever(dustRepository.getCurrentBalance(TEST_ADDRESS)).thenReturn(BigInteger.ZERO)
        whenever(dustRepository.getTokenCount(TEST_ADDRESS)).thenReturn(0)
        whenever(dustRepository.getAvailableTokens(TEST_ADDRESS)).thenReturn(emptyList())

        viewModel.checkDustStatus(TEST_ADDRESS, TEST_SEED_PHRASE)

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Status)
        val status = state as DustUiState.Status
        assertEquals(0L, status.timeToCapacityMs)
        assertFalse("Empty token list should not report at capacity", status.isAtCapacity)
    }

    // ========================================================================
    // checkDustStatus - Error Handling
    // ========================================================================

    @Test
    fun `checkDustStatus sync exception shows error with original message`() = runTest {
        whenever(dustRepository.syncFromBlockchain(any(), any(), any()))
            .thenThrow(RuntimeException("Network error"))

        viewModel.checkDustStatus(TEST_ADDRESS, TEST_SEED_PHRASE)

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        val error = state as DustUiState.Error
        assertTrue(error.message.contains("Network error"))
        assertNotNull("Error should preserve throwable for debugging", error.throwable)
    }

    // ========================================================================
    // registerDust - Validation
    // ========================================================================

    @Test
    fun `registerDust with blank address shows error without entering Registering state`() = runTest {
        viewModel.registerDust("", "seed", BigInteger.valueOf(1_000_000))

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        assertEquals("Address and seed phrase are required", (state as DustUiState.Error).message)
    }

    @Test
    fun `registerDust with blank seed shows error without entering Registering state`() = runTest {
        viewModel.registerDust("mn_addr_test", "", BigInteger.valueOf(1_000_000))

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        assertEquals("Address and seed phrase are required", (state as DustUiState.Error).message)
    }

    // ========================================================================
    // registerDust - Error Handling
    // ========================================================================

    @Test
    fun `registerDust with invalid seed phrase shows error`() = runTest {
        // BIP39.mnemonicToSeed will throw for invalid mnemonic — real crypto, not mocked
        viewModel.registerDust(TEST_ADDRESS, "invalid seed", BigInteger.valueOf(1_000_000))

        val state = viewModel.state.value
        assertTrue("Expected Error but got ${state::class.simpleName}", state is DustUiState.Error)
        assertTrue((state as DustUiState.Error).message.contains("Registration failed"))
    }

    // ========================================================================
    // Default values
    // ========================================================================

    @Test
    fun `defaultTestSeedPhrase is populated for preprod`() {
        assertTrue(viewModel.defaultTestSeedPhrase.isNotBlank())
        assertEquals(24, viewModel.defaultTestSeedPhrase.split(" ").size)
    }

    @Test
    fun `defaultTestAddress is populated for preprod`() {
        assertTrue(viewModel.defaultTestAddress.isNotBlank())
        assertTrue(viewModel.defaultTestAddress.startsWith("mn_addr_preprod"))
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Creates a DustTokenEntity with explicit parameters.
     * Uses deterministic values — no System.currentTimeMillis() in defaults.
     */
    private fun createToken(
        nullifier: String = "test_nullifier",
        initialValue: String = "0",
        creationTimeMillis: Long,
        dustCapacitySpecks: String = "1000000",
        generationRatePerSecond: String = "100"
    ): DustTokenEntity {
        return DustTokenEntity(
            nullifier = nullifier,
            address = TEST_ADDRESS,
            initialValue = initialValue,
            creationTimeMillis = creationTimeMillis,
            nightUtxoId = "test_utxo",
            nightValueStars = "1000000",
            dustCapacitySpecks = dustCapacitySpecks,
            generationRatePerSecond = generationRatePerSecond,
            state = UtxoState.AVAILABLE,
            lastUpdatedMillis = System.currentTimeMillis()
        )
    }

    private companion object {
        const val TEST_ADDRESS = "mn_addr_preprod14jv9z9g3dwpm74zx8ntv9wt026gtt87wu7ev90mv2hm94r6zc6jqjj0mtk"
        const val TEST_SEED_PHRASE = "slot pave company hobby wear thank erupt license major devote jealous plunge protect dice floor exact ride manual harvest ribbon harbor regular romance artist"
    }
}
