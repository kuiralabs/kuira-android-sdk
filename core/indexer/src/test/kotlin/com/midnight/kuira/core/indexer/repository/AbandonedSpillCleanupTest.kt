package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Spill files that no sync can read again must not sit on the device forever.
 *
 * Before the spill was named per address it was named per attempt, so every failed genesis sync
 * abandoned its stream — nine files and 1.0GB on one PreProd device, which is the headroom the
 * next genesis sync needs. Naming the spill deterministically stopped new ones accumulating but
 * left every existing one unreachable: the code only knows the name it would write itself.
 */
class AbandonedSpillCleanupTest {

    @Before
    fun clearSpillDirectory() {
        spillDirectory.listFiles { file -> file.name.startsWith(PREFIX) }?.forEach { it.delete() }
    }

    @Test
    fun `a spill no sync can name again is deleted`() = runTest {
        val abandoned = spill("${PREFIX}7540482734433097156.hex", ageMillis = ONE_DAY)

        syncOnce()

        assertFalse(
            "an unreachable ${abandoned.name} survives every sync and the OS reclaims it instead",
            abandoned.exists(),
        )
    }

    /**
     * The retained genesis spill is the whole point of the resume: deleting another address's
     * would cost it a full re-stream.
     */
    @Test
    fun `the spill of an address that can still resume is kept`() = runTest {
        val other = "mn_addr_preprod1other"
        val retained = spill(spillNameFor(other), ageMillis = ONE_DAY)

        syncOnce(preferences = preferencesOf(longPreferencesKey("dust_spill_cursor_$other") to 4_000L))

        assertTrue("a resumable spill was deleted — its address now re-streams from zero", retained.exists())
    }

    /** A second address mid-stream has no cursor yet, so only its file says it is alive. */
    @Test
    fun `a spill written moments ago is left alone`() = runTest {
        val inFlight = spill("${PREFIX}0123456789abcdef.hex", ageMillis = 0)

        syncOnce()

        assertTrue("a live sync's spill was deleted out from under it", inFlight.exists())
    }

    @Test
    fun `this address keeps the spill it is about to resume`() = runTest {
        val mine = spill(spillNameFor(ADDRESS), ageMillis = ONE_DAY)

        syncOnce(preferences = preferencesOf(longPreferencesKey("dust_spill_cursor_$ADDRESS") to 9L))

        assertTrue("the sync deleted the very spill it was going to resume", mine.exists())
    }

    // ── fixtures ──

    /** Its own directory: the repository is told where to spill, so a test never shares one. */
    private val spillDirectory: File =
        java.nio.file.Files.createTempDirectory("abandoned_spill_test").toFile()

    private fun spill(name: String, ageMillis: Long): File =
        File(spillDirectory, name).apply {
            writeText("00\n")
            setLastModified(System.currentTimeMillis() - ageMillis)
        }

    private fun spillNameFor(address: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(address.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "$PREFIX$digest.hex"
    }

    /**
     * Run one sync far enough to reach the cleanup. The indexer fails immediately, so nothing
     * downstream of it runs — the cleanup happens before the first subscription either way.
     */
    private suspend fun syncOnce(preferences: Preferences = preferencesOf()) {
        val dataStore = mock<DataStore<Preferences>>()
        whenever(dataStore.data).thenReturn(flowOf(preferences))
        val repository = DustRepository(mock<DustDao>(), dataStore, mock<DustBalanceCalculator>(), DeadIndexer(), spillDirectory)
        runCatching { repository.syncFromBlockchain(ADDRESS, ByteArray(32)) }
    }

    private class DeadIndexer : IndexerClient {
        override fun subscribeToDustEvents(fromId: Long?): Flow<RawLedgerEvent> =
            flow { throw IOException("indexer unreachable") }

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
        private const val ADDRESS = "mn_addr_preprod1cleanup"
        private const val PREFIX = "dust_events_"
        private const val ONE_DAY = 24 * 60 * 60 * 1000L
    }
}
