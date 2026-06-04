package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real (temp-file) DataStore test for the durable spent-dust nullifier tracking —
 * the persistence IS the behavior, so it's exercised against an actual DataStore
 * rather than a mock.
 */
@RunWith(RobolectricTestRunner::class)
class SpentDustNullifierStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: SpentDustNullifierStore

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            tmp.newFile("spent_dust.preferences_pb")
        }
        store = SpentDustNullifierStore(dataStore)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun recordsAndReadsBackLowercased() = runBlocking {
        assertTrue(store.spentNullifiers("addrA").isEmpty())
        store.recordSpent("addrA", "73AB12CD")
        assertEquals(setOf("73ab12cd"), store.spentNullifiers("addrA"))
    }

    @Test
    fun recordIsAdditiveAndPerAddress() = runBlocking {
        store.recordSpent("addrA", "aa")
        store.recordSpent("addrA", "bb")
        store.recordSpent("addrB", "cc")
        assertEquals(setOf("aa", "bb"), store.spentNullifiers("addrA"))
        assertEquals(setOf("cc"), store.spentNullifiers("addrB"))
    }

    @Test
    fun retainPresentPrunesConfirmedGoneNullifiers() = runBlocking {
        store.recordSpent("addrA", "n1")
        store.recordSpent("addrA", "n2")
        // The freshly-synced state still holds n1 (stream behind) but n2 is gone
        // (stream caught up) → n2 should be pruned, n1 kept.
        store.retainPresent("addrA", setOf("n1"))
        assertEquals(setOf("n1"), store.spentNullifiers("addrA"))
    }

    @Test
    fun retainPresentEmptyClearsTheEntry() = runBlocking {
        store.recordSpent("addrA", "n1")
        store.retainPresent("addrA", emptySet())
        assertTrue(store.spentNullifiers("addrA").isEmpty())
    }

    @Test
    fun clearRemovesAllForAddress() = runBlocking {
        store.recordSpent("addrA", "n1")
        store.clear("addrA")
        assertTrue(store.spentNullifiers("addrA").isEmpty())
    }
}
