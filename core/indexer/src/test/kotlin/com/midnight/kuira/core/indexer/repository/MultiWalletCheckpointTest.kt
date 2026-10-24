package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.database.DustDao
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

/**
 * One device, several wallets.
 *
 * Every wallet keeps its own dust checkpoint, so switching sigils costs a first sync and nothing
 * after that. They used to share a single Preferences file, which made two wallets expensive in a
 * way neither of them caused: DataStore rewrites that file whole on every edit, so a three-event
 * delta for one wallet rewrote the other's ~870KB state too — stored as hex, so twice that again.
 */
class MultiWalletCheckpointTest {

    private val checkpointDir: File = Files.createTempDirectory("multi_wallet_test").toFile()

    @Test
    fun `each wallet keeps its own cursor`() = runTest {
        val repository = repositoryWith(preferencesOf())

        repository.saveLastAppliedEventId(WALLET_A, 1_400_000L)
        repository.saveLastAppliedEventId(WALLET_B, 9L)

        assertEquals(1_400_000L, repository.getLastAppliedEventId(WALLET_A))
        assertEquals(9L, repository.getLastAppliedEventId(WALLET_B))
    }

    /** The point of the split: one wallet's write must not touch another's bytes. */
    @Test
    fun `saving one wallet does not rewrite another`() = runTest {
        val repository = repositoryWith(preferencesOf())
        repository.saveLastAppliedEventId(WALLET_A, 1_400_000L)
        val untouched = checkpointFiles().single()
        val before = untouched.readBytes()

        repository.saveLastAppliedEventId(WALLET_B, 9L)

        assertEquals("wallet A's checkpoint was rewritten by wallet B's sync", 2, checkpointFiles().size)
        assertTrue("wallet A's bytes changed", before.contentEquals(untouched.readBytes()))
    }

    @Test
    fun `deleting one wallet leaves the others`() = runTest {
        val repository = repositoryWith(preferencesOf())
        repository.saveLastAppliedEventId(WALLET_A, 1_400_000L)
        repository.saveLastAppliedEventId(WALLET_B, 9L)

        repository.deleteState(WALLET_A)

        assertNull(repository.getLastAppliedEventId(WALLET_A))
        assertEquals(9L, repository.getLastAppliedEventId(WALLET_B))
    }

    /**
     * A device synced before the split keeps its state under the old keys. Re-syncing 1.7M events
     * to move house is not a migration anyone should pay for.
     */
    @Test
    fun `a checkpoint written before the split is adopted, not discarded`() = runTest {
        val legacy = preferencesOf(
            stringPreferencesKey("dust_state_$WALLET_A") to "00ff",
            longPreferencesKey("dust_last_event_$WALLET_A") to 1_731_623L,
        )
        val repository = repositoryWith(legacy)

        assertEquals(
            "the old cursor must survive the move",
            1_731_623L,
            repository.getLastAppliedEventId(WALLET_A),
        )
        assertTrue("it should now live in its own file", checkpointFiles().isNotEmpty())
    }

    // ── fixtures ──

    private fun checkpointFiles(): List<File> =
        checkpointDir.listFiles { file -> file.name.startsWith("dust_checkpoint_") }?.toList().orEmpty()

    private fun repositoryWith(preferences: Preferences): DustRepository {
        val dataStore = mock<DataStore<Preferences>>()
        whenever(dataStore.data).thenReturn(flowOf(preferences))
        return DustRepository(
            mock<DustDao>(),
            dataStore,
            mock<DustBalanceCalculator>(),
            mock<IndexerClient>(),
            checkpointDir,
        )
    }

    private companion object {
        private const val WALLET_A = "mn_addr_preprod1sigil_a"
        private const val WALLET_B = "mn_addr_preprod1sigil_b"
    }
}
