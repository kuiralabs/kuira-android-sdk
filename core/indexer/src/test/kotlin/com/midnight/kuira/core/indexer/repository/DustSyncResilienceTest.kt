package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.database.DustDao
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.model.DustSyncResult
import com.midnight.kuira.core.indexer.model.NetworkState
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.IOException
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.yield

/**
 * What a dust sync must survive.
 *
 * A PreProd genesis replay streams ~1.7M events over many minutes while the indexer WebSocket's
 * pong timeout fires every 20-60 seconds. Each test here describes a way that combination used to
 * discard all progress and start again at event zero — which is why the sync never converged.
 *
 * The streaming phase is plain Kotlin, so it runs here; the replay that needs the native ledger
 * happens afterwards and is covered by the instrumented tier.
 */
class DustSyncResilienceTest {

    /**
     * A dropped connection must resume from the last event received.
     *
     * This is the defect that made PreProd unusable: the transport cannot stay up for the length
     * of the replay, so a sync that cannot survive a drop can never finish.
     */
    @Test
    fun `a dropped stream resumes from the event after the last one received`() = runTest {
        val indexer = FakeIndexer(
            genesis = emitsThenDrops(upToId = 2),
            resume = { completesAtTip() },
        )

        runCatching { repositoryFor(indexer).syncFromBlockchain(ADDRESS, DUST_SEED) }

        assertEquals(
            "the resume must pick up after event 2, and must not start over",
            listOf(null, 3L),
            indexer.dustSubscriptionFromIds,
        )
    }

    /** Several drops in one sync is the normal case on PreProd, not an edge case. */
    @Test
    fun `repeated drops keep moving forward instead of restarting`() = runTest {
        val indexer = FakeIndexer(
            genesis = emitsThenDrops(upToId = 2),
            resume = { from -> if (from < 9) emitsThenDrops(upToId = from + 2) else completesAtTip() },
        )

        runCatching { repositoryFor(indexer).syncFromBlockchain(ADDRESS, DUST_SEED) }

        assertEquals(
            "each resume must start after the previous one's last event",
            listOf(null, 3L, 6L, 9L),
            indexer.dustSubscriptionFromIds,
        )
    }

    /**
     * A reconnect that yields nothing new must give up, or a genuinely unreachable indexer is
     * retried forever. Forward progress is the licence to keep going.
     */
    @Test
    fun `a stream that drops without progress is not retried forever`() = runTest {
        val indexer = FakeIndexer(genesis = dropsImmediately(), resume = { dropsImmediately() })

        val result = runCatching { repositoryFor(indexer).syncFromBlockchain(ADDRESS, DUST_SEED) }

        assertTrue("an unreachable indexer must surface, not spin: $result", result.isFailure)
        assertTrue(
            "it must not have resubscribed without progress: ${indexer.dustSubscriptionFromIds}",
            indexer.dustSubscriptionFromIds.size == 1,
        )
    }

    /**
     * The other half of the problem. Resuming inside one attempt is not enough: the sync is also
     * restarted from above — a new refresh, a new process — and each of those used to repeat all
     * ~1.7M events. The streamed spill is the expensive part, so it survives a failure and the
     * next attempt picks it up.
     */
    @Test
    fun `a genesis sync that fails keeps its streamed events for the next attempt`() = runTest {
        val indexer = FakeIndexer(genesis = emitsThenDrops(upToId = 2), resume = { dropsImmediately() })
        val repository = repositoryFor(indexer)

        runCatching { repository.syncFromBlockchain(ADDRESS, DUST_SEED) }

        assertTrue(
            "the streamed events must survive, or the next attempt starts at zero again",
            spillFor(ADDRESS).exists(),
        )
    }

    /**
     * DustSyncManager holds a mutex, but MidnightSdk's shielded-send path and
     * TransactionSubmitter's error-196 recovery call the repository directly and bypass it. Two
     * syncs on one address share a spill file, a checkpoint and a live state.
     */
    @Test
    fun `two syncs for one address do not run at the same time`() = runTest {
        val indexer = FakeIndexer(genesis = emitsThenDrops(upToId = 2), resume = { dropsImmediately() })
        val repository = repositoryFor(indexer)

        val both = listOf(
            async { runCatching { repository.syncFromBlockchain(ADDRESS, DUST_SEED) } },
            async { runCatching { repository.syncFromBlockchain(ADDRESS, DUST_SEED) } },
        )
        both.awaitAll()

        assertEquals(
            "two syncs for one address overlapped — they share a spill file, a checkpoint and a " +
                "live state",
            1,
            indexer.peakConcurrentStreams,
        )
    }

    // ── fixtures ──

    /** Mirrors DustRepository.spillFileFor so the test names the same file the code does. */
    private fun spillFor(address: String): java.io.File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return java.io.File(System.getProperty("java.io.tmpdir"), "dust_events_$digest.hex")
    }


    private fun repositoryFor(indexer: IndexerClient): DustRepository {
        val dataStore = mock<DataStore<Preferences>>()
        whenever(dataStore.data).thenReturn(flowOf(mock()))
        return DustRepository(mock<DustDao>(), dataStore, mock<DustBalanceCalculator>(), indexer)
    }

    private fun event(id: Long, maxId: Long = TIP) = RawLedgerEvent(id = id, rawHex = "00", maxId = maxId)

    /** Emits 0..[upToId] then dies the way a pong timeout does. */
    private fun emitsThenDrops(upToId: Long): Flow<RawLedgerEvent> = flow {
        enterStream()
        try {
            var id = currentCoroutineStart
            while (id <= upToId) {
                emit(event(id))
                yield()
                id++
            }
            throw IOException("sent ping but didn't receive pong within 20000ms")
        } finally {
            leaveStream()
        }
    }

    private var liveStreams = 0
    private var peak = 0

    private fun enterStream() {
        liveStreams++
        peak = maxOf(peak, liveStreams)
    }

    private fun leaveStream() {
        liveStreams--
    }

    private fun dropsImmediately(): Flow<RawLedgerEvent> = flow {
        throw IOException("sent ping but didn't receive pong within 20000ms")
    }

    /** Reaching the tip is how the real stream signals it is done. */
    private fun completesAtTip(): Flow<RawLedgerEvent> = flowOf(event(id = TIP, maxId = TIP))

    private var currentCoroutineStart = 0L

    private inner class FakeIndexer(
        private val genesis: Flow<RawLedgerEvent>,
        private val resume: (Long) -> Flow<RawLedgerEvent>,
    ) : IndexerClient {
        val dustSubscriptionFromIds = mutableListOf<Long?>()
        val peakConcurrentStreams: Int get() = peak

        override fun subscribeToDustEvents(fromId: Long?): Flow<RawLedgerEvent> {
            dustSubscriptionFromIds.add(fromId)
            if (fromId == null) return genesis
            currentCoroutineStart = fromId
            return resume(fromId)
        }

        override fun subscribeToUnshieldedTransactions(address: String, transactionId: Int?): Flow<UnshieldedTransactionUpdate> = error("not used")
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
        private const val ADDRESS = "mn_addr_preprod1test"
        private val DUST_SEED = ByteArray(32)

        /** Far enough away that no fixture reaches it by accident. */
        private const val TIP = 1_728_618L
    }
}
