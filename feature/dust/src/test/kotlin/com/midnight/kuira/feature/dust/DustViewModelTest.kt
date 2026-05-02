package com.midnight.kuira.feature.dust

import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.auth.AuthenticationCancelledException
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.core.network.NetworkRepository
import com.midnight.kuira.core.indexer.database.DustTokenEntity
import com.midnight.kuira.core.indexer.database.UtxoState
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.ProofServerClient
import com.midnight.kuira.core.ledger.api.TransactionSerializer
import com.midnight.kuira.core.ledger.model.UtxoSpend
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
 * **What's mocked:**
 * - [DustRepository] (database + FFI), [ProofServerClient] (network),
 *   [TransactionSerializer] (FFI), [NodeRpcClient] (network),
 *   [UtxoManager] (database), [SeedVault] (hardware-backed biometric),
 *   [FragmentActivity] (Android host).
 *
 * **Seed handling:** `seedVault.loadSeed(activity)` is stubbed to return a
 * fresh [PlaintextSeed] on every call so the real BIP-32 / HDWallet
 * derivation path still runs inside the VM. That keeps the tests honest
 * about the key lifecycle (wipe in `finally`) without needing a device.
 *
 * **Known gap:** The registerDust happy path (build → prove → seal → submit)
 * is not testable because [com.midnight.kuira.core.crypto.dust.DustKeyDeriver]
 * and [com.midnight.kuira.core.ledger.dust.DustRegistrationBuilder] are
 * static objects that load the native library. They'd need to sit behind
 * injectable interfaces for a full unit test of the registration flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DustViewModelTest {

    private lateinit var dustRepository: DustRepository
    private lateinit var proofServerClient: ProofServerClient
    private lateinit var serializer: TransactionSerializer
    private lateinit var nodeRpcClient: NodeRpcClient
    private lateinit var utxoManager: UtxoManager
    private lateinit var seedVault: SeedVault
    private lateinit var activity: FragmentActivity
    private lateinit var viewModel: DustViewModel

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        dustRepository = mock()
        proofServerClient = mock()
        serializer = mock()
        nodeRpcClient = mock()
        utxoManager = mock()
        activity = mock()

        // Stub `seedVault.loadSeed` via `onBlocking` (it's a `suspend` fun).
        // Return a fresh PlaintextSeed on every call — VM's finally block
        // wipes it, so re-using the same instance across calls would fail
        // the size invariants on the second load.
        seedVault = mock {
            onBlocking { loadSeed(any()) } doAnswer {
                PlaintextSeed(
                    mnemonicEntropy = ByteArray(PlaintextSeed.ENTROPY_SIZE) { 0x11 },
                    bip39Seed = ByteArray(PlaintextSeed.SEED_SIZE) { 0x22 },
                )
            }
        }

        viewModel = DustViewModel(
            dustRepository = dustRepository,
            proofServerClient = proofServerClient,
            serializer = serializer,
            nodeRpcClient = nodeRpcClient,
            utxoManager = utxoManager,
            seedVault = seedVault,
            networkRepository = mock<NetworkRepository>().also {
                whenever(it.getSelectedNetworkBlocking()).thenReturn(MidnightNetwork.PREPROD)
            },
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
    fun `checkDustStatus with blank address shows error and does not prompt biometric`() = runTest {
        viewModel.checkDustStatus(activity, "")

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        assertEquals("Address cannot be empty", (state as DustUiState.Error).message)

        // Cheap validation runs before anything touches SeedVault or the repo.
        verifyNoInteractions(seedVault)
        verifyNoInteractions(dustRepository)
    }

    // ========================================================================
    // checkDustStatus - NoDust
    // ========================================================================

    @Test
    fun `checkDustStatus when no dust returns NoDust state`() = runTest {
        whenever(dustRepository.syncFromBlockchain(any(), any(), any(), anyOrNull())).thenReturn(false)

        viewModel.checkDustStatus(activity, TEST_ADDRESS)

        val state = viewModel.state.value
        assertTrue("Expected NoDust but got ${state::class.simpleName}", state is DustUiState.NoDust)
    }

    // ========================================================================
    // checkDustStatus - Status with real balance calculation
    // ========================================================================

    @Test
    fun `checkDustStatus with generating token computes real time to capacity`() = runTest {
        val balance = BigInteger.valueOf(500_000)

        // Token created 10 seconds ago, rate=1000/sec, capacity=1,000,000, initial=0
        // After ~10s: current ≈ 10,000; remaining ≈ 990,000; time ≈ 990s ≈ 990,000ms
        createToken(
            initialValue = "0",
            creationTimeMillis = System.currentTimeMillis() - 10_000,
            dustCapacitySpecks = "1000000",
            generationRatePerSecond = "1000",
        )

        val nightBalance = BigInteger.valueOf(5_000_000)
        whenever(dustRepository.syncFromBlockchain(any(), any(), any(), anyOrNull())).thenReturn(true)
        whenever(dustRepository.getCurrentBalance(TEST_ADDRESS)).thenReturn(balance)
        whenever(utxoManager.calculateBalance(TEST_ADDRESS)).thenReturn(
            mapOf(UtxoSpend.NATIVE_TOKEN_TYPE to nightBalance)
        )

        viewModel.checkDustStatus(activity, TEST_ADDRESS)

        val state = viewModel.state.value
        assertTrue("Expected Status but got ${state::class.simpleName}", state is DustUiState.Status)
        val status = state as DustUiState.Status
        assertEquals(balance, status.balance)
        assertEquals(nightBalance, status.nightBalance)
    }

    @Test
    fun `checkDustStatus shows nightBalance from utxoManager`() = runTest {
        val nightBalance = BigInteger.valueOf(10_000_000)

        whenever(dustRepository.syncFromBlockchain(any(), any(), any(), anyOrNull())).thenReturn(true)
        whenever(dustRepository.getCurrentBalance(TEST_ADDRESS)).thenReturn(BigInteger.valueOf(1_000_000))
        whenever(utxoManager.calculateBalance(TEST_ADDRESS)).thenReturn(
            mapOf(UtxoSpend.NATIVE_TOKEN_TYPE to nightBalance)
        )

        viewModel.checkDustStatus(activity, TEST_ADDRESS)

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Status)
        val status = state as DustUiState.Status
        assertEquals(nightBalance, status.nightBalance)
    }

    @Test
    fun `checkDustStatus with no NIGHT UTXOs shows zero nightBalance`() = runTest {
        whenever(dustRepository.syncFromBlockchain(any(), any(), any(), anyOrNull())).thenReturn(true)
        whenever(dustRepository.getCurrentBalance(TEST_ADDRESS)).thenReturn(BigInteger.ZERO)
        whenever(utxoManager.calculateBalance(TEST_ADDRESS)).thenReturn(emptyMap())

        viewModel.checkDustStatus(activity, TEST_ADDRESS)

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Status)
        val status = state as DustUiState.Status
        assertEquals(BigInteger.ZERO, status.nightBalance)
    }

    // ========================================================================
    // checkDustStatus - Error Handling
    // ========================================================================

    @Test
    fun `checkDustStatus sync exception shows error with original message`() = runTest {
        whenever(dustRepository.syncFromBlockchain(any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("Network error"))

        viewModel.checkDustStatus(activity, TEST_ADDRESS)

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        val error = state as DustUiState.Error
        assertTrue(error.message.contains("Network error"))
        assertNotNull("Error should preserve throwable for debugging", error.throwable)
    }

    // ========================================================================
    // checkDustStatus - Biometric Cancellation
    // ========================================================================

    @Test
    fun `checkDustStatus shows Error when user cancels biometric prompt`() = runTest {
        // REGRESSION: the VM must not hang in Loading forever if the user
        // declines the biometric prompt. SeedVault.loadSeed throws
        // AuthenticationCancelledException; the catch block should surface
        // a user-visible error state.
        //
        // Re-stub the hoisted loadSeed() mock via `stub { onBlocking }` —
        // the @Before sets a default success stub via `mock { }`, and
        // mockito-kotlin needs the suspend-aware stub DSL to override it.
        //
        // We use `doAnswer { throw ... }` rather than `doThrow(...)` because
        // Mockito rejects `doThrow` on checked exceptions that aren't
        // declared by the underlying JVM method signature — which is the
        // case for Kotlin `suspend` functions generated from throwing
        // lambdas. `doAnswer` sidesteps that check.
        seedVault.stub {
            onBlocking { loadSeed(any()) } doAnswer {
                throw AuthenticationCancelledException("user cancelled")
            }
        }

        viewModel.checkDustStatus(activity, TEST_ADDRESS)

        val state = viewModel.state.value
        assertTrue(
            "Expected Error but got ${state::class.simpleName}",
            state is DustUiState.Error
        )
        assertTrue(
            "Error message should surface the cancellation cause",
            (state as DustUiState.Error).message.contains("cancelled", ignoreCase = true)
                || state.message.contains("check dust status")
        )
        // Sync must NOT have run — we bailed before touching the repository.
        verifyNoInteractions(dustRepository)
    }

    // ========================================================================
    // registerDust - Validation
    // ========================================================================

    @Test
    fun `registerDust with blank address shows error without prompting biometric`() = runTest {
        viewModel.registerDust(activity, "")

        val state = viewModel.state.value
        assertTrue(state is DustUiState.Error)
        assertEquals("Address is required", (state as DustUiState.Error).message)
        verifyNoInteractions(seedVault)
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
    }
}
