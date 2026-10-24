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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * A delta must be abandoned on the first event the checkpoint cannot apply, not after streaming
 * the whole gap.
 *
 * Two PreProd devices showed what the old order costs: a checkpoint written before the save
 * became atomic sat a handful of commitments behind its own cursor, and the sync discovered that
 * only after streaming 266,070 and 368,240 events — then threw all of it away and rebuilt from
 * genesis regardless. The first event was always enough to know.
 *
 * Both cases run the real native replay against real captured dust events, because what is being
 * pinned is whether the state ACCEPTS an event — a question only the ledger can answer.
 */
@RunWith(AndroidJUnit4::class)
class DustDeltaProbeInstrumentedTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var database: UtxoDatabase

    private val address = "mn_addr_delta_probe_test"

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dir = Files.createTempDirectory("dust_delta_probe_test").toFile()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "test.preferences_pb") }
        database = UtxoDatabase.createInMemoryDatabase(context)
    }

    @After
    fun teardown() {
        database.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    /**
     * An empty checkpoint standing at commitment zero cannot take an event from the middle of the
     * chain — the same `NonLinearInsertion` both devices hit, reached the same way: through the
     * state the checkpoint restores to.
     */
    @Test
    fun aCheckpointThatCannotTakeTheFirstEventStopsBeforeStreamingTheRest() = runBlocking<Unit> {
        val indexer = ProbeIndexer(tipMaxId = FAR_AHEAD, firstDeltaEvent = asset("later_event.hex"))
        val repository = newRepository(indexer)
        seedEmptyCheckpoint(repository, lastEventId = CURSOR)

        repository.syncFromBlockchain(address, fixtureSeed())

        assertFalse(
            "it kept streaming a delta whose first event the checkpoint had already rejected: " +
                "${indexer.dustSubscriptionFromIds}",
            indexer.dustSubscriptionFromIds.contains(CURSOR + 2),
        )
        assertEquals(
            "a rejected delta must fall back to genesis (a third subscription, from null)",
            listOf<Long?>(null, CURSOR + 1, null),
            indexer.dustSubscriptionFromIds,
        )
        repository.getLastSyncedState()?.close()
    }

    /**
     * The other half: the probe has to discriminate, not just abort. An empty checkpoint DOES take
     * the chain's first event, so that delta must proceed to its continuation.
     */
    @Test
    fun aCheckpointThatCanTakeTheFirstEventKeepsStreaming() = runBlocking<Unit> {
        val indexer = ProbeIndexer(tipMaxId = FAR_AHEAD, firstDeltaEvent = asset("genesis_event.hex"))
        val repository = newRepository(indexer)
        seedEmptyCheckpoint(repository, lastEventId = CURSOR)

        val synced = repository.syncFromBlockchain(address, fixtureSeed())

        assertTrue("an applicable delta must sync, not fall back to genesis", synced)
        assertEquals(
            "the delta must run to its continuation instead of being abandoned",
            listOf<Long?>(null, CURSOR + 1, CURSOR + 2),
            indexer.dustSubscriptionFromIds,
        )
        repository.getLastSyncedState()?.close()
    }

    // ── fixtures ──

    private fun newRepository(indexer: IndexerClient): DustRepository =
        DustRepository(database.dustDao(), dataStore, DustBalanceCalculator(), indexer)

    private fun seedEmptyCheckpoint(repository: DustRepository, lastEventId: Long) = runBlocking {
        val state = DustLocalState.create()
        assertNotNull("native DustLocalState.create() (is the .so present?)", state)
        try {
            repository.saveCheckpoint(address, state!!, lastEventId)
        } finally {
            state!!.close()
        }
    }

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("$FIXTURE_DIR/$name")
            .bufferedReader()
            .use { it.readText().trim() }

    private fun fixtureSeed(): ByteArray =
        asset("seed.hex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Serves the tip probe, then one configured event as the delta's first, then nothing. The
     * continuation subscription is what the probe decides the fate of, so whether it is ever
     * opened is the whole assertion.
     */
    private class ProbeIndexer(
        private val tipMaxId: Long,
        private val firstDeltaEvent: String,
    ) : IndexerClient {
        val dustSubscriptionFromIds = mutableListOf<Long?>()

        override fun subscribeToDustEvents(fromId: Long?): Flow<RawLedgerEvent> {
            dustSubscriptionFromIds.add(fromId)
            return when (fromId) {
                null -> flowOf(RawLedgerEvent(id = 0, rawHex = "00", maxId = tipMaxId))
                CURSOR + 1 -> flowOf(RawLedgerEvent(id = fromId, rawHex = firstDeltaEvent, maxId = tipMaxId))
                else -> emptyFlow()
            }
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

    private companion object {
        private const val FIXTURE_DIR = "dust_delta_probe"

        /** The checkpoint's cursor; the delta therefore opens at CURSOR + 1. */
        private const val CURSOR = 5L

        /** Far enough above the cursor that the sync plan is always DELTA, never AT_TIP. */
        private const val FAR_AHEAD = 5_000L
    }
}
