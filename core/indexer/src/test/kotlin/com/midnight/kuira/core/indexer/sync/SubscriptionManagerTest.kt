package com.midnight.kuira.core.indexer.sync

import android.content.Context
import android.content.SharedPreferences
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.TransactionStatus
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import io.mockk.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for SubscriptionManager.
 *
 * **Coverage:**
 * - State emissions (Connecting, Syncing, Synced)
 * - Progress tracking
 * - Basic error handling
 *
 * **Note:** Complex retry logic and real subscription behavior tested in integration tests.
 */
class SubscriptionManagerTest {

    private lateinit var context: Context
    private lateinit var indexerClient: IndexerClient
    private lateinit var utxoManager: UtxoManager
    private lateinit var syncStateManager: SyncStateManager
    private lateinit var subscriptionManager: SubscriptionManager

    private val testAddress = "mn_addr_test1234"

    @Before
    fun setup() {
        context = mockk()
        indexerClient = mockk()
        utxoManager = mockk()
        syncStateManager = mockk()

        // Mock SharedPreferences for resync check
        val mockPrefs = mockk<SharedPreferences>()
        val mockEditor = mockk<SharedPreferences.Editor>()
        every { context.getSharedPreferences(any(), any()) } returns mockPrefs
        every { mockPrefs.getBoolean(any(), any()) } returns false
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.apply() } just Runs

        subscriptionManager = SubscriptionManager(context, indexerClient, utxoManager, syncStateManager)

        // Default mock behavior
        coEvery { syncStateManager.getLastProcessedTransactionId(any()) } returns null
        coEvery { syncStateManager.saveLastProcessedTransactionId(any(), any()) } just Runs
        coEvery { syncStateManager.clearSyncState(any()) } just Runs
        coEvery { utxoManager.clearUtxos(any()) } just Runs

        // Mock for checkAndHandleResyncNeeded
        coEvery { utxoManager.hasAnyUtxos(any()) } returns true
        coEvery { utxoManager.hasAvailableUtxos(any()) } returns true
        coEvery { utxoManager.debugDumpAllUtxos(any(), any()) } just Runs
    }

    @After
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `startSubscription emits Connecting state first`() = runTest {
        // Given: Empty subscription
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns emptyFlow()

        // When: Start subscription
        val states = subscriptionManager.startSubscription(testAddress).take(1).toList()

        // Then: First state is Connecting
        assertEquals(1, states.size)
        assertTrue("First state should be Connecting", states[0] is SyncState.Connecting)
    }

    @Test
    fun `startSubscription emits Syncing when processing transactions`() = runTest {
        // Given: Flow with one transaction
        val mockTransaction = mockk<UnshieldedTransactionUpdate.Transaction>(relaxed = true)
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns flowOf(mockTransaction)
        coEvery { utxoManager.processUpdate(mockTransaction) } returns UtxoManager.ProcessingResult.TransactionProcessed(
            transactionId = 10,
            transactionHash = "tx_123",
            createdCount = 1,
            spentCount = 0,
            status = TransactionStatus.SUCCESS
        )

        // When: Start subscription
        val states = subscriptionManager.startSubscription(testAddress).take(2).toList()

        // Then: Second state is Syncing
        assertEquals(2, states.size)
        assertTrue("Second state should be Syncing", states[1] is SyncState.Syncing)
        assertEquals(1, (states[1] as SyncState.Syncing).processedCount)
    }

    @Test
    fun `startSubscription emits Synced on Progress update after transaction`() = runTest {
        // Given: Flow with a transaction followed by Progress
        // Note: Synced is only emitted immediately if at least one transaction was processed.
        // This prevents the UI from showing "Synced" before any actual data is received.
        val mockTransaction = mockk<UnshieldedTransactionUpdate.Transaction>(relaxed = true)
        val mockProgress = UnshieldedTransactionUpdate.Progress(
            type = "UnshieldedTransactionsProgress",
            highestTransactionId = 27
        )
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns flowOf(mockTransaction, mockProgress)
        coEvery { utxoManager.processUpdate(mockTransaction) } returns UtxoManager.ProcessingResult.TransactionProcessed(
            transactionId = 10,
            transactionHash = "tx_123",
            createdCount = 1,
            spentCount = 0,
            status = TransactionStatus.SUCCESS
        )
        coEvery { utxoManager.processUpdate(mockProgress) } returns UtxoManager.ProcessingResult.ProgressUpdate(27)

        // When: Start subscription
        val states = subscriptionManager.startSubscription(testAddress).take(3).toList()

        // Then: States are Connecting, Syncing, then Synced
        assertEquals(3, states.size)
        assertTrue("First state should be Connecting", states[0] is SyncState.Connecting)
        assertTrue("Second state should be Syncing", states[1] is SyncState.Syncing)
        assertTrue("Third state should be Synced", states[2] is SyncState.Synced)
        assertEquals(27, (states[2] as SyncState.Synced).highestTransactionId)
    }

    @Test
    fun `startSubscription saves progress to SyncStateManager`() = runTest {
        // Given: Progress update
        val mockProgress = UnshieldedTransactionUpdate.Progress(
            type = "UnshieldedTransactionsProgress",
            highestTransactionId = 42
        )
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns flowOf(mockProgress)
        coEvery { utxoManager.processUpdate(mockProgress) } returns UtxoManager.ProcessingResult.ProgressUpdate(42)

        // When: Collect all states
        subscriptionManager.startSubscription(testAddress).toList()

        // Then: Progress was saved
        coVerify { syncStateManager.saveLastProcessedTransactionId(testAddress, 42) }
    }

    @Test
    fun `startSubscription uses last processed ID for resumption`() = runTest {
        // Given: Last ID exists (override default mock)
        coEvery { syncStateManager.getLastProcessedTransactionId(testAddress) } returns 100

        // IMPORTANT: Must have UTXOs in database, otherwise SubscriptionManager clears sync state
        // when lastProcessedId exists but database is empty (assumes corrupted state)
        coEvery { utxoManager.getUnspentUtxos(testAddress) } returns listOf(mockk(relaxed = true))

        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns emptyFlow()

        // When: Start subscription (collect all states - emptyFlow completes immediately after Connecting)
        subscriptionManager.startSubscription(testAddress).toList()

        // Then: Subscription was called with lastId=100 (not null)
        verify { indexerClient.subscribeToUnshieldedTransactions(testAddress, 100) }
    }

    @Test
    fun `startSubscription saves final progress on cancellation`() = runTest {
        // Given: Subscription that emits progress
        val progress = UnshieldedTransactionUpdate.Progress(
            type = "UnshieldedTransactionsProgress",
            highestTransactionId = 42
        )

        // Flow emits progress then stays open
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns flow {
            emit(progress)
            // Simulate long-running subscription (will be cancelled by take())
            awaitCancellation()
        }
        coEvery { utxoManager.processUpdate(progress) } returns UtxoManager.ProcessingResult.ProgressUpdate(42)

        // When: Start subscription and cancel after first progress (take cancels the flow)
        subscriptionManager.startSubscription(testAddress)
            .take(2) // Connecting + Synced, then cancel
            .collect { /* Collect states */ }

        // Then: Final progress should be saved (in finally block) despite cancellation
        coVerify { syncStateManager.saveLastProcessedTransactionId(testAddress, 42) }
    }

    @Test
    fun `startSubscription throttles progress saves to reduce disk IO`() = runTest {
        // Given: Multiple rapid Progress updates (within throttle window)
        val progress1 = UnshieldedTransactionUpdate.Progress(
            type = "UnshieldedTransactionsProgress",
            highestTransactionId = 10
        )
        val progress2 = UnshieldedTransactionUpdate.Progress(
            type = "UnshieldedTransactionsProgress",
            highestTransactionId = 20
        )
        val progress3 = UnshieldedTransactionUpdate.Progress(
            type = "UnshieldedTransactionsProgress",
            highestTransactionId = 30
        )

        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns flowOf(
            progress1,
            progress2,
            progress3
        )
        coEvery { utxoManager.processUpdate(any()) } returns UtxoManager.ProcessingResult.ProgressUpdate(10) andThen
                UtxoManager.ProcessingResult.ProgressUpdate(20) andThen
                UtxoManager.ProcessingResult.ProgressUpdate(30)

        // When: Collect all states
        subscriptionManager.startSubscription(testAddress).toList()

        // Then: Only first save should happen (others throttled), plus final save on completion
        // Expected calls: First progress (10) + Final save (30) = 2 calls
        coVerify(exactly = 2) { syncStateManager.saveLastProcessedTransactionId(any(), any()) }

        // Verify final save has latest transaction ID
        coVerify { syncStateManager.saveLastProcessedTransactionId(testAddress, 30) }
    }

    /**
     * Phase 8B.3 T1-21 Round 1: the default launch path must NOT wipe local
     * state. Previously `checkAndHandleResyncNeeded` cleared sync state and
     * UTXOs on every subscription start, causing the zero-balance flicker on
     * every app launch.
     */
    @Test
    fun `default startSubscription does not wipe sync state or UTXOs`() = runTest {
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns emptyFlow()

        // When: default incremental path
        subscriptionManager.startSubscription(testAddress).toList()

        // Then: neither wipe primitive ran
        coVerify(exactly = 0) { syncStateManager.clearSyncState(any()) }
        coVerify(exactly = 0) { utxoManager.clearUtxos(any()) }
    }

    @Test
    fun `forceFullResync=true wipes sync state and UTXOs before subscribing`() = runTest {
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } returns emptyFlow()

        subscriptionManager.startSubscription(testAddress, forceFullResync = true).toList()

        coVerify(exactly = 1) { syncStateManager.clearSyncState(testAddress) }
        coVerify(exactly = 1) { utxoManager.clearUtxos(testAddress) }
    }

    @Test
    fun `forceFullResync=true wipes once even with transient retries`() = runTest {
        // Fail the first collect attempt with a retryable IOException; the retry
        // must NOT re-run the wipe (that would discard progress already saved
        // during the first pass).
        var attempts = 0
        every { indexerClient.subscribeToUnshieldedTransactions(any(), any()) } answers {
            attempts++
            if (attempts == 1) {
                flow<UnshieldedTransactionUpdate> { throw java.io.IOException("flaky socket") }
            } else {
                emptyFlow()
            }
        }

        subscriptionManager.startSubscription(testAddress, forceFullResync = true).toList()

        // Wipe ran once; retry reused the already-clean state.
        coVerify(exactly = 1) { syncStateManager.clearSyncState(testAddress) }
        coVerify(exactly = 1) { utxoManager.clearUtxos(testAddress) }
    }

    /**
     * Additional tests covered by integration tests:
     * - Retry logic with exponential backoff (BalanceRepositoryIntegrationTest)
     * - Real WebSocket subscription behavior (BalanceRepositoryIntegrationTest)
     * - Error handling with real indexer (BalanceRepositoryIntegrationTest)
     * - Multiple concurrent subscriptions
     */
}
