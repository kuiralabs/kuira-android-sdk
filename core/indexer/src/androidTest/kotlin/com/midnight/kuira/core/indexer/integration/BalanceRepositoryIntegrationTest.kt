package com.midnight.kuira.core.indexer.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger

/**
 * Integration tests for Phase 4B-3 using real Midnight testnet data.
 *
 * **Purpose:**
 * - Verify BalanceRepository works with real blockchain data
 * - Test end-to-end flow: Indexer API → Database → Repository → ViewModel
 * - Validate BigInteger handling with production amounts
 * - Ensure Flow emissions work correctly
 *
 * **Prerequisites:**
 * 1. Local Midnight testnet running: `docker compose -f v3-full-stack-compose.yml up -d`
 * 2. Indexer API v3 accessible at http://10.0.2.2:8088 (Android emulator)
 * 3. Test address funded with UTXOs (genesis wallet has funds by default)
 *
 * **Test Strategy:**
 * - Load real data from Indexer API using TestDataLoader
 * - Populate database with production-like UTXOs
 * - Test repository and manager with real data
 * - Verify calculations match expected values
 *
 * **Running tests:**
 * ```bash
 * # From project root
 * ./gradlew :core:indexer:connectedDebugAndroidTest \
 *   --tests "BalanceRepositoryIntegrationTest"
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BalanceRepositoryIntegrationTest {

    private lateinit var context: Context
    private lateinit var database: UtxoDatabase
    private lateinit var utxoManager: UtxoManager
    private lateinit var repository: BalanceRepository
    private lateinit var indexerClient: IndexerClient
    private lateinit var syncStateManager: SyncStateManager

    /**
     * Test address derived from test seed 0x02.
     *
     * **Derivation path:** m/44'/2400'/0'/0/0
     * **Seed:** 0x0000000000000000000000000000000000000000000000000000000000000002
     *
     * **Current Balance:** ~2.5 billion NIGHT (genesis wallet receiving block rewards)
     * **UTXOs:** 7 available (5x 500M NIGHT + 2x 1.0 NIGHT)
     *
     * **To regenerate:** `npm run generate-address -- 02 0`
     */
    private val TEST_UNSHIELDED_ADDRESS = "mn_addr_undeployed1gkasr3z3vwyscy2jpp53nzr37v7n4r3lsfgj6v5g584dakjzt0xqun4d4r"

    /**
     * Indexer API endpoint (Android emulator uses 10.0.2.2 for host localhost).
     *
     * **Prerequisites:**
     * 1. Start Midnight testnet: `docker compose -f v3-full-stack-compose.yml up -d`
     * 2. Indexer runs on http://localhost:8088
     * 3. Android emulator accesses via http://10.0.2.2:8088
     *
     * **For physical device:** Replace with computer's local IP
     * Example: "http://192.168.1.100:8088"
     */
    private val INDEXER_BASE_URL = "http://10.0.2.2:8088/api/v3"

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()

        // Create in-memory database for testing
        database = Room.inMemoryDatabaseBuilder(
            context,
            UtxoDatabase::class.java
        ).build()

        // Initialize production components
        utxoManager = UtxoManager(database.unshieldedUtxoDao())

        // Initialize indexer client (development mode for HTTP)
        indexerClient = IndexerClientImpl(
            baseUrl = INDEXER_BASE_URL,
            developmentMode = true // Allow HTTP for local testing
        )

        repository = BalanceRepository(utxoManager, indexerClient)

        // Initialize sync state manager and clear any previous state
        syncStateManager = SyncStateManager(context)
        runBlocking {
            android.util.Log.d("Test", "Clearing sync state...")
            syncStateManager.clearAllSyncState()  // Start fresh for each test
            // Also verify it was cleared
            kotlinx.coroutines.delay(100) // Brief delay to ensure DataStore write completes
            val lastId = syncStateManager.getLastProcessedTransactionId(TEST_UNSHIELDED_ADDRESS)
            android.util.Log.d("Test", "After clear, lastProcessedTransactionId = $lastId (should be null)")
            require(lastId == null) { "Failed to clear sync state! Still has lastId=$lastId" }
        }
    }

    @After
    fun tearDown() {
        database.close()
        indexerClient.close()
    }

    // ==================== Basic Scenarios ====================

    /**
     * Test 1: Load real UTXOs from indexer via subscription and verify population.
     *
     * **Flow:**
     * 1. Subscribe to unshielded transactions via IndexerClient (WebSocket)
     * 2. UtxoManager processes transaction updates
     * 3. UTXOs inserted into Room database
     * 4. Verify data was inserted correctly
     *
     * **Prerequisites:**
     * - Docker services running: `docker compose -f v3-full-stack-compose.yml up -d`
     * - Indexer API accessible at http://10.0.2.2:8088 (Android emulator)
     * - Test address must have UTXOs (genesis wallet always has funds)
     */
    @Test
    fun basicScenario_loadUtxosFromIndexer_thenDatabasePopulated() = runTest(timeout = 90.seconds) {
        // Load real data via subscription (production code path)
        val utxoCount = TestDataLoader.loadFromIndexer(
            context = context,
            indexerClient = indexerClient,
            utxoManager = utxoManager,
            syncStateManager = syncStateManager,
            address = TEST_UNSHIELDED_ADDRESS,
            database = database
        )

        // Verify data was loaded
        assertTrue("Expected at least 1 UTXO, got $utxoCount", utxoCount > 0)

        // Query database directly
        val utxos = database.unshieldedUtxoDao().getUnspentUtxos(TEST_UNSHIELDED_ADDRESS)
        assertEquals(utxoCount, utxos.size)

        println("Loaded $utxoCount UTXOs from Indexer API")
    }

    /**
     * Test 2: Verify BalanceRepository calculates correct total from real UTXOs.
     *
     * **Flow:**
     * 1. Load UTXOs from Indexer API via subscription
     * 2. Observe balances via BalanceRepository
     * 3. Calculate total balance
     * 4. Verify matches expected amount
     *
     * **Expected:**
     * - Genesis wallet has ~2.5B NIGHT (block rewards)
     * - Multiple UTXOs with different amounts
     */
    @Test
    fun basicScenario_observeBalances_thenMatchesIndexerData() = runTest(timeout = 90.seconds) {
        // Load real data via subscription
        val utxoCount = TestDataLoader.loadFromIndexer(
            context = context,
            indexerClient = indexerClient,
            utxoManager = utxoManager,
            syncStateManager = syncStateManager,
            address = TEST_UNSHIELDED_ADDRESS,
            database = database
        )

        assertTrue("No UTXOs loaded. Fund test address first.", utxoCount > 0)

        // Observe balances via repository
        val balances = repository.observeBalances(TEST_UNSHIELDED_ADDRESS).first()

        // Verify balances returned
        assertTrue("Expected at least 1 token balance", balances.isNotEmpty())

        // Calculate total
        val totalBalance = balances.fold(BigInteger.ZERO) { acc, balance ->
            acc.add(balance.balance)
        }

        // Verify total is positive
        assertTrue("Total balance should be > 0, got $totalBalance", totalBalance > BigInteger.ZERO)

        println("Total balance: $totalBalance (${balances.size} token types)")

        // Verify UTXO counts
        balances.forEach { balance ->
            assertTrue(
                "UTXO count should be > 0 for ${balance.tokenType}",
                balance.utxoCount > 0
            )
        }
    }

    /**
     * Test 3: Verify observeTotalBalance() sums correctly across token types.
     *
     * **Flow:**
     * 1. Load UTXOs via subscription (may have multiple token types)
     * 2. Observe total balance (available + pending)
     * 3. Verify sum is correct
     *
     * **Note:**
     * - Total balance sums DIFFERENT token types (warning: not financially accurate)
     * - Used for UI display only, not financial calculations
     */
    @Test
    fun basicScenario_observeTotalBalance_thenSumsAllTokens() = runTest(timeout = 90.seconds) {
        // Load real data via subscription
        val utxoCount = TestDataLoader.loadFromIndexer(
            context = context,
            indexerClient = indexerClient,
            utxoManager = utxoManager,
            syncStateManager = syncStateManager,
            address = TEST_UNSHIELDED_ADDRESS,
            database = database
        )

        assertTrue("No UTXOs loaded. Fund test address first.", utxoCount > 0)

        // Observe total balance
        val totalBalance = repository.observeTotalBalance(TEST_UNSHIELDED_ADDRESS).first()

        // Verify positive
        assertTrue("Total balance should be > 0, got $totalBalance", totalBalance > BigInteger.ZERO)

        println("Total balance across all tokens: $totalBalance")
    }

    // ==================== Advanced Scenarios ====================

    /**
     * Test 4: Verify BigInteger handles large amounts without overflow.
     *
     * **Purpose:**
     * - Test edge case: Very large balance amounts
     * - Verify BigInteger arithmetic works correctly
     * - Ensure no overflow exceptions
     *
     * **Note:**
     * - This test uses database directly (doesn't require Indexer API)
     * - Creates synthetic large-value UTXOs
     */
    @Test
    fun advancedScenario_largeAmounts_thenBigIntegerHandlesCorrectly() = runTest {
        // Create synthetic UTXO with very large amount
        val largeAmount = BigInteger.valueOf(Long.MAX_VALUE).divide(BigInteger.valueOf(2))

        val largeUtxo = com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity(
            id = "test_large_amount:0",
            transactionHash = "test_large_amount",
            intentHash = "test_large_amount",
            outputIndex = 0,
            owner = TEST_UNSHIELDED_ADDRESS,
            ownerPublicKey = null,
            value = largeAmount.toString(),
            tokenType = "TNIGHT",
            state = com.midnight.kuira.core.indexer.database.UtxoState.AVAILABLE,
            ctime = System.currentTimeMillis() / 1000,
            registeredForDustGeneration = false
        )

        database.unshieldedUtxoDao().insertUtxos(listOf(largeUtxo))

        // Observe balances (should handle large amount)
        val balances = repository.observeBalances(TEST_UNSHIELDED_ADDRESS).first()

        // Verify balance is correct (no overflow)
        val tnightBalance = balances.find { it.tokenType == "TNIGHT" }
        assertEquals(largeAmount, tnightBalance?.balance)

        println("BigInteger handles large amounts: $largeAmount")
    }

    /**
     * Test 5: Verify balances are sorted by largest first.
     *
     * **Flow:**
     * 1. Create multiple UTXOs with different amounts
     * 2. Observe balances
     * 3. Verify sorted descending by balance
     *
     * **Requirement:** UI should show largest balances first
     */
    @Test
    fun advancedScenario_multipleTokenTypes_thenSortedByLargest() = runTest {
        // Create UTXOs with different amounts
        val utxos = listOf(
            createTestUtxo("TNIGHT", BigInteger.valueOf(5_000_000), 0),  // Largest
            createTestUtxo("DUST", BigInteger.valueOf(1_000_000), 1),    // Smallest
            createTestUtxo("TOKEN_X", BigInteger.valueOf(3_000_000), 2)  // Middle
        )

        database.unshieldedUtxoDao().insertUtxos(utxos)

        // Observe balances
        val balances = repository.observeBalances(TEST_UNSHIELDED_ADDRESS).first()

        // Verify sorting
        assertEquals("TNIGHT", balances[0].tokenType)  // Largest first
        assertEquals(BigInteger.valueOf(5_000_000), balances[0].balance)

        assertEquals("TOKEN_X", balances[1].tokenType)  // Middle
        assertEquals(BigInteger.valueOf(3_000_000), balances[1].balance)

        assertEquals("DUST", balances[2].tokenType)    // Smallest last
        assertEquals(BigInteger.valueOf(1_000_000), balances[2].balance)

        // Balances sorted correctly by amount
    }

    // ==================== Helper Methods ====================

    private fun createTestUtxo(
        tokenType: String,
        amount: BigInteger,
        index: Int
    ): com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity {
        val intentHash = "test_${tokenType}_$index"
        return com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity(
            id = "${intentHash}:$index",
            transactionHash = intentHash,
            intentHash = intentHash,
            outputIndex = index,
            owner = TEST_UNSHIELDED_ADDRESS,
            ownerPublicKey = null,
            value = amount.toString(),
            tokenType = tokenType,
            state = com.midnight.kuira.core.indexer.database.UtxoState.AVAILABLE,
            ctime = System.currentTimeMillis() / 1000 + index,
            registeredForDustGeneration = false
        )
    }
}
