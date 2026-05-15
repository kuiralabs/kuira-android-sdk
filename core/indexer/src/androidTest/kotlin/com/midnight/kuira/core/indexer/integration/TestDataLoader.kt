package com.midnight.kuira.core.indexer.integration

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import com.midnight.kuira.core.indexer.sync.SyncState
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Loads real UTXO data from the Midnight indexer GraphQL API into the test
 * database using the production code path.
 *
 * See [com.midnight.kuira.core.network.NetworkConfig] for the URL / API path
 * that's actually used at runtime — this loader doesn't pin a specific
 * version, it just rides whatever `NetworkConfig` exposes.
 *
 * **Purpose:**
 * - Integration testing with real blockchain data
 * - Test actual subscription flow (not synthetic data)
 * - Verify end-to-end: IndexerClient → WebSocket → UtxoManager → Database
 *
 * **Architecture (CORRECT - Production Code Path):**
 * ```
 * Indexer GraphQL (WebSocket)
 *   └─> IndexerClient.subscribeToUnshieldedTransactions()
 *       └─> GraphQLWebSocketClient (graphql-transport-ws protocol)
 *           └─> SubscriptionManager orchestrates flow
 *               └─> UtxoManager processes updates
 *                   └─> Room database (UnshieldedUtxoEntity)
 *                       └─> Tests query database
 * ```
 *
 * **Previous WRONG Approach:**
 * - HTTP helper server querying postgres directly
 * - Bypassed production code path
 * - Required Node.js dependency
 * - Not testing what actually runs in app
 *
 * **New CORRECT Approach:**
 * - Uses real IndexerClient subscription
 * - Tests production WebSocket flow
 * - No external dependencies
 * - Same code that runs in app
 *
 * **Usage in tests:**
 * ```kotlin
 * @Test
 * fun integrationTest() = runTest {
 *     // Load real data via subscription (production code path)
 *     // Note: context parameter is required for resync detection
 *     val utxoCount = TestDataLoader.loadFromIndexer(
 *         indexerClient = indexerClient,
 *         utxoManager = utxoManager,
 *         syncStateManager = syncStateManager,
 *         address = TEST_ADDRESS,
 *         database = database
 *     )
 *
 *     // Test with real data
 *     val balances = repository.observeBalances(address).first()
 *     assertTrue("Expected UTXOs", utxoCount > 0)
 * }
 * ```
 */
object TestDataLoader {

    private val TAG = "TestDataLoader"

    /**
     * Load UTXOs from indexer via real subscription (production code path).
     *
     * **Network Requirements:**
     * - Indexer must be accessible at indexerClient's configured URL
     * - For Android emulator: Use http://10.0.2.2:8088 (not localhost)
     * - For physical device: Use computer's local IP (e.g., http://192.168.1.100:8088)
     *
     * **Flow:**
     * 1. Create SubscriptionManager with production components
     * 2. Start subscription for address
     * 3. Wait for initial sync to complete (Synced state)
     * 4. Return UTXO count from database
     *
     * **Sync Progress:**
     * - Connecting: WebSocket connecting
     * - Syncing: Processing transactions
     * - Synced: Reached latest transaction (subscription stays open for new updates)
     *
     * **Timeout:**
     * - 60 seconds for initial sync
     * - If timeout: Test fails (indexer may be down or address has too many txs)
     *
     * @param indexerClient IndexerClient configured for test network
     * @param utxoManager UtxoManager with test database
     * @param syncStateManager SyncStateManager for test
     * @param address Unshielded address to sync
     * @param database Database instance (used to query final count)
     * @param timeoutMs Timeout for initial sync (default 60s)
     * @return Number of UTXOs loaded
     * @throws Exception if subscription fails or times out
     */
    suspend fun loadFromIndexer(
        context: Context,
        indexerClient: IndexerClient,
        utxoManager: UtxoManager,
        syncStateManager: SyncStateManager,
        address: String,
        database: UtxoDatabase,
        timeoutMs: Long = 90_000  // 90s to account for indexer Progress heartbeat (every ~30s)
    ): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading UTXOs from indexer for address: $address")

        // Create subscription manager with production components
        val subscriptionManager = SubscriptionManager(
            context = context,
            indexerClient = indexerClient,
            utxoManager = utxoManager,
            syncStateManager = syncStateManager
        )

        try {
            // Start subscription and wait for initial sync to complete
            withTimeout(timeoutMs) {
                subscriptionManager.startSubscription(address)
                    .takeWhile { state ->
                        when (state) {
                            is SyncState.Connecting -> {
                                Log.d(TAG, "Connecting to indexer...")
                                true // Keep collecting
                            }
                            is SyncState.Syncing -> {
                                Log.d(TAG, "Syncing: processed ${state.processedCount} transactions " +
                                        "(current tx: ${state.currentTransactionId})")
                                true // Keep collecting
                            }
                            is SyncState.Synced -> {
                                Log.d(TAG, "✅ Synced up to transaction ${state.highestTransactionId}")
                                false // Stop collecting - sync complete
                            }
                            is SyncState.Error -> {
                                throw Exception("Subscription error: ${state.message}")
                            }
                        }
                    }
                    .collect { /* Collect until Synced state */ }
            }

            // Brief delay to allow concurrent database writes to complete
            // (transactions are processed on multiple threads, some inserts may still be in-flight)
            kotlinx.coroutines.delay(500)

            // Query database to get final UTXO count
            val utxos = database.unshieldedUtxoDao().getUnspentUtxos(address)
            Log.d(TAG, "✅ Loaded ${utxos.size} UTXOs from indexer")

            utxos.forEach { utxo ->
                Log.d(TAG, "  - ${utxo.tokenType}: ${utxo.value} " +
                        "(state: ${utxo.state}, tx: ${utxo.intentHash.take(10)}...)")
            }

            utxos.size

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to load UTXOs from indexer", e)
            throw e
        }
        // Note: Subscription cleanup is automatic when Flow collection completes
    }

    /**
     * Load UTXOs with custom sync progress callback.
     *
     * Useful for tests that want to monitor sync progress in detail.
     *
     * @param onProgress Callback for each sync state change
     */
    suspend fun loadFromIndexerWithProgress(
        context: Context,
        indexerClient: IndexerClient,
        utxoManager: UtxoManager,
        syncStateManager: SyncStateManager,
        address: String,
        database: UtxoDatabase,
        timeoutMs: Long = 60_000,
        onProgress: (SyncState) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "Loading UTXOs from indexer with progress monitoring for: $address")

        val subscriptionManager = SubscriptionManager(
            context = context,
            indexerClient = indexerClient,
            utxoManager = utxoManager,
            syncStateManager = syncStateManager
        )

        withTimeout(timeoutMs) {
            subscriptionManager.startSubscription(address)
                .takeWhile { state ->
                    onProgress(state) // Notify test

                    when (state) {
                        is SyncState.Synced -> false // Stop collecting
                        is SyncState.Error -> throw Exception("Subscription error: ${state.message}")
                        else -> true // Keep collecting
                    }
                }
                .collect { /* Collect until Synced */ }
        }

        // Note: Subscription cleanup is automatic when Flow collection completes
        database.unshieldedUtxoDao().getUnspentUtxos(address).size
    }
}
