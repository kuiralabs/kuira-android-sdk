package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.model.DustSyncResult
import com.midnight.kuira.core.indexer.model.NetworkState
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * On-device proof of : an at-tip dust refresh must NOT open the delta
 * subscription — which would otherwise block the full `DELTA_FIRST_EVENT_TIMEOUT_MS`
 * (30s) on a live subscription the indexer never feeds (the chain hasn't advanced
 * past our cursor). Needs the real native [DustLocalState] (loadCheckpoint
 * deserialize), so it runs instrumented, not as a JVM unit test.
 *
 * The decision logic itself is unit-tested exhaustively in `DustSyncPlanTest`; this
 * test pins the *wiring* in [DustRepository.syncFromBlockchain] against real state.
 *
 * The fake indexer's delta subscription (fromId != null) returns a flow that NEVER
 * emits or completes — exactly how the real indexer behaves at the tip. So if the
 * short-circuit regressed and the delta were opened, the `withTimeout(GUARD_MS)`
 * around the call would trip well before the real 30s wait, failing the test.
 */
@RunWith(AndroidJUnit4::class)
class DustRepositorySyncPlanInstrumentedTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var database: UtxoDatabase

    private val address = "mn_addr_sync_plan_test"
    private val dustSeed = ByteArray(32) { 1 } // content irrelevant: no events are replayed here

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dir = Files.createTempDirectory("dust_sync_plan_test").toFile()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
        database = UtxoDatabase.createInMemoryDatabase(context)
    }

    @After
    fun teardown() {
        database.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Persist an empty native dust checkpoint at [lastEventId] via the production save path. */
    private fun seedEmptyCheckpoint(repository: DustRepository, lastEventId: Long) = runBlocking {
        val state = DustLocalState.create()
        assertNotNull("native DustLocalState.create() (is the .so present?)", state)
        try {
            repository.saveCheckpoint(address, state!!, lastEventId)
        } finally {
            state!!.close()
        }
    }

    private fun newRepository(indexer: IndexerClient): DustRepository =
        DustRepository(database.dustDao(), dataStore, DustBalanceCalculator(), indexer)

    @Test
    fun atTipDoesNotOpenTheDeltaSubscriptionAndReturnsFast() = runBlocking<Unit> {
        // Chain tip == our cursor (event 5): caught up, nothing new to fetch.
        val indexer = FakeIndexerClient(tipMaxId = 5L, deltaFlow = flow { awaitCancellation() })
        val repository = newRepository(indexer)
        seedEmptyCheckpoint(repository, lastEventId = 5L)

        val synced = withTimeout(GUARD_MS) { repository.syncFromBlockchain(address, dustSeed) }

        assertTrue("at-tip sync reports success (checkpoint already current)", synced)
        // Only the reorg-guard probe (fromId = null) was opened — never the delta
        // (fromId = lastEventId + 1 = 6). That delta would have blocked 30s.
        assertEquals(
            "at the tip only the tip probe is opened, never the delta subscription",
            listOf<Long?>(null),
            indexer.dustSubscriptionFromIds,
        )
        repository.getLastSyncedState()?.close()
    }

    @Test
    fun deltaPathOpensTheDeltaSubscriptionWhenChainIsAhead() = runBlocking<Unit> {
        // Chain tip (8) is ahead of our cursor (5): there ARE events to fetch, so the
        // delta subscription MUST open. The fake delivers nothing new (empty → returns
        // immediately), so the checkpoint is retained — this guards against the
        // short-circuit firing too eagerly and skipping a real delta.
        val indexer = FakeIndexerClient(tipMaxId = 8L, deltaFlow = emptyFlow())
        val repository = newRepository(indexer)
        seedEmptyCheckpoint(repository, lastEventId = 5L)

        val synced = withTimeout(GUARD_MS) { repository.syncFromBlockchain(address, dustSeed) }

        assertTrue("delta sync with nothing new still reports success", synced)
        assertEquals(
            "ahead-of-tip opens the tip probe then the delta from cursor+1",
            listOf<Long?>(null, 6L),
            indexer.dustSubscriptionFromIds,
        )
        repository.getLastSyncedState()?.close()
    }

    /**
     * Minimal [IndexerClient] test double: only [subscribeToDustEvents] has behavior
     * (records its cursor + returns the configured flows); everything else is unused
     * by [DustRepository.syncFromBlockchain]'s checkpoint path and throws if touched.
     */
    private class FakeIndexerClient(
        private val tipMaxId: Long,
        private val deltaFlow: Flow<RawLedgerEvent>,
    ) : IndexerClient {
        val dustSubscriptionFromIds = mutableListOf<Long?>()

        override fun subscribeToDustEvents(fromId: Long?): Flow<RawLedgerEvent> {
            dustSubscriptionFromIds.add(fromId)
            // fromId == null is the reorg-guard probe: the first event carries the
            // chain's current tip as its maxId. fromId != null is a delta resume.
            return if (fromId == null) flowOf(RawLedgerEvent(id = 0, rawHex = "00", maxId = tipMaxId)) else deltaFlow
        }

        override fun subscribeToUnshieldedTransactions(address: String, transactionId: Int?): Flow<UnshieldedTransactionUpdate> =
            error("not used")
        override fun subscribeToZswapEvents(fromId: Long?): Flow<RawLedgerEvent> = error("not used")
        override fun subscribeToBlocks(): Flow<BlockInfo> = error("not used")
        override suspend fun getNetworkState(): NetworkState = error("not used")
        override suspend fun getCurrentBlockWithParams(): BlockInfo = error("not used")
        override suspend fun getEventsInRange(fromId: Long, toId: Long): List<RawLedgerEvent> = error("not used")
        override suspend fun queryDustEvents(maxBlocks: Int): String = error("not used")
        override suspend fun queryDustEventsDelta(fromId: Long, timeoutMs: Long): DustSyncResult = error("not used")
        override suspend fun queryZswapEvents(): String = error("not used")
        override suspend fun queryContractState(address: String): String = error("not used")
        override suspend fun isHealthy(): Boolean = error("not used")
        override fun close() = error("not used")
        override suspend fun resetConnection() = error("not used")
    }

    companion object {
        /**
         * Caps the at-tip call far below the real 30s `DELTA_FIRST_EVENT_TIMEOUT_MS`: a
         * regressed short-circuit would open the (never-emitting) delta and blow past this.
         */
        private const val GUARD_MS = 10_000L
    }
}
