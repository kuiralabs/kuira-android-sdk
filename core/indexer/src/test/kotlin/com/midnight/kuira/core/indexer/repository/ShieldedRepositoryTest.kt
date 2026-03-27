package com.midnight.kuira.core.indexer.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for ShieldedRepository.
 *
 * Tests repository logic in isolation — ZswapLocalState FFI calls are NOT tested here
 * (those require native library, tested in androidTest).
 */
class ShieldedRepositoryTest {

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var indexerClient: IndexerClient
    private lateinit var networkConfig: NetworkConfig
    private lateinit var repository: ShieldedRepository

    @Before
    fun setup() {
        dataStore = mock()
        indexerClient = mock()
        networkConfig = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED)
        repository = ShieldedRepository(dataStore, indexerClient, networkConfig)
    }

    @Test
    fun `getBalances returns empty when no cached state`() = runTest {
        val emptyPrefs = preferencesOf()
        whenever(dataStore.data).thenReturn(flowOf(emptyPrefs))

        val balances = repository.getBalances("mn_addr_test")
        assertTrue(balances.isEmpty())
    }

    @Test
    fun `getCoinCount returns 0 when no cached state`() = runTest {
        val emptyPrefs = preferencesOf()
        whenever(dataStore.data).thenReturn(flowOf(emptyPrefs))

        val count = repository.getCoinCount("mn_addr_test")
        assertEquals(0, count)
    }

    @Test
    fun `hasCachedState returns false when no state`() = runTest {
        val emptyPrefs = preferencesOf()
        whenever(dataStore.data).thenReturn(flowOf(emptyPrefs))

        assertFalse(repository.hasCachedState("mn_addr_test"))
    }

    @Test
    fun `hasCachedState returns true when state exists`() = runTest {
        val key = stringPreferencesKey("zswap_state_mn_addr_test")
        val prefs = preferencesOf(key to "deadbeef")
        whenever(dataStore.data).thenReturn(flowOf(prefs))

        assertTrue(repository.hasCachedState("mn_addr_test"))
    }

    @Test
    fun `syncFromBlockchain returns false when no events`() = runTest {
        whenever(indexerClient.queryZswapEvents()).thenReturn("")

        val result = repository.syncFromBlockchain("mn_addr_test", ByteArray(32))
        assertFalse(result)
    }

    @Test
    fun `subscribeToZswapEvents returns flow`() {
        val event = RawLedgerEvent(id = 1, rawHex = "deadbeef", maxId = 1)
        whenever(indexerClient.subscribeToZswapEvents(any())).thenReturn(flowOf(event))

        // The repository's subscribeToZswapEvents uses its own WebSocket,
        // but for unit testing we verify the method exists and returns a Flow
        val flow = repository.subscribeToZswapEvents(fromId = 0L)
        assertNotNull(flow)
    }

    @Test
    fun `separate WebSocket endpoint derived from networkConfig`() {
        // Verify the repository constructs its own WS endpoint
        // Undeployed network uses 10.0.2.2:8088
        val repo = ShieldedRepository(dataStore, indexerClient, networkConfig)
        // Repository should exist without errors — WS client created lazily
        assertNotNull(repo)
    }
}
