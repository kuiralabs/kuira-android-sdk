package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import java.io.File
import java.nio.file.Files

/**
 * Pins the checkpoint-consistency invariant behind #236: a dust checkpoint is a
 * (serialized state, resume cursor) pair that must be all-or-nothing. A half
 * that is present without its partner — a torn pair — must read as ABSENT so the
 * caller falls back to a clean genesis sync, never resumes a delta on a state
 * whose tree frontier disagrees with the cursor (which the ledger rejects as
 * `NonLinearInsertion`, triggering the genesis-resync loop this fix removes).
 *
 * Runs against a REAL Preferences DataStore (not a mock) so it exercises the
 * actual key schema and the single-emission read in [DustRepository.loadCheckpoint].
 * The negative (half-missing) paths return before the native `DustLocalState`
 * deserialize, so they run on the JVM. The positive both-present round-trip needs
 * real native state and is covered on-device by `DustCheckpointResumeTest`.
 */
class DustCheckpointConsistencyTest {

    private lateinit var scope: CoroutineScope
    private lateinit var dir: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DustRepository

    private val address = "mn_addr_test_checkpoint"
    private val stateKey = stringPreferencesKey("dust_state_$address")
    private val cursorKey = longPreferencesKey("dust_last_event_$address")

    @Before
    fun setup() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dir = Files.createTempDirectory("dust_ckpt_test").toFile()
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(dir, "test.preferences_pb")
        }
        repository = DustRepository(mock(), dataStore, mock(), mock())
    }

    @After
    fun teardown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `fresh store has no checkpoint`() = runBlocking {
        assertFalse("no checkpoint before any sync", repository.hasCheckpoint(address))
        assertNull("loadCheckpoint is null on a fresh store", repository.loadCheckpoint(address))
    }

    @Test
    fun `cursor without state reads as absent`() = runBlocking {
        // Only the resume cursor was persisted (state write lost / not yet written).
        repository.saveLastAppliedEventId(address, 42L)

        assertFalse("a lone cursor is not a checkpoint", repository.hasCheckpoint(address))
        assertNull("loadCheckpoint must reject the half-pair", repository.loadCheckpoint(address))
    }

    @Test
    fun `state without cursor reads as absent`() = runBlocking {
        // Only the serialized state was persisted (e.g. initialize-without-sync, or
        // a torn write that dropped the cursor). loadCheckpoint returns before it
        // would deserialize, so no native state is needed to prove the rejection.
        dataStore.edit { it[stateKey] = "deadbeef" }

        assertFalse("a lone state is not a checkpoint", repository.hasCheckpoint(address))
        assertNull("loadCheckpoint must reject the half-pair", repository.loadCheckpoint(address))
    }

    @Test
    fun `both halves present reads as a checkpoint`() = runBlocking {
        // Presence test only — deserializing the (fake) state needs native code,
        // proven separately on-device. This pins that BOTH keys are required.
        dataStore.edit {
            it[stateKey] = "deadbeef"
            it[cursorKey] = 99L
        }

        assertTrue("both halves present is a checkpoint", repository.hasCheckpoint(address))
    }

    @Test
    fun `deleteState clears both halves`() = runBlocking {
        dataStore.edit {
            it[stateKey] = "deadbeef"
            it[cursorKey] = 99L
        }
        assertTrue(repository.hasCheckpoint(address))

        repository.deleteState(address)

        assertFalse("checkpoint gone after deleteState", repository.hasCheckpoint(address))
        assertNull("no resume cursor after deleteState", repository.getLastAppliedEventId(address))
    }
}
