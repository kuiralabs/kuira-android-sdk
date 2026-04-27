package com.midnight.kuira.sdk

import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeNetworkException
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class DustSyncManagerTest {

    private val dustState = mock<DustLocalState> {
        on { getStatePointer() } doReturn 12345L
    }

    // ── Cold start ──

    @Test
    fun `cold start calls syncFromBlockchain and returns state`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
            onBlocking { loadState(any()) } doReturn dustState
        }
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { getFinalizedHead() } doReturn "aabb" + "cc".repeat(30)
        }

        val manager = createManager(dustRepo = dustRepo, nodeRpc = nodeRpc)
        val result = manager.ensureSynced()

        assertEquals(dustState, result)
        verify(dustRepo).syncFromBlockchain(eq("test_addr"), any(), any())
    }

    // ── Warm hit (same tip, within TTL) ──

    @Test
    fun `same tip within TTL returns cached state without network calls`() = runTest {
        var now = 1000L
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
            onBlocking { loadState(any()) } doReturn dustState
        }
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { getFinalizedHead() } doReturn "tip_hash_1".padEnd(64, '0')
        }

        val manager = createManager(dustRepo = dustRepo, nodeRpc = nodeRpc, clock = { now })

        // First call: syncs
        manager.ensureSynced()
        verify(nodeRpc, times(1)).getFinalizedHead()
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any())

        // Second call within TTL: no network
        now += 2000 // 2s later, within 5s TTL
        val result = manager.ensureSynced()
        assertEquals(dustState, result)
        verify(nodeRpc, times(1)).getFinalizedHead() // still only 1 call
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any()) // still only 1 sync
    }

    // ── TTL expired but same tip ──

    @Test
    fun `TTL expired but same tip re-fetches tip and serves cache`() = runTest {
        var now = 1000L
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
            onBlocking { loadState(any()) } doReturn dustState
        }
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { getFinalizedHead() } doReturn "stable_tip".padEnd(64, '0')
        }

        val manager = createManager(dustRepo = dustRepo, nodeRpc = nodeRpc, clock = { now })

        manager.ensureSynced()
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any())

        // 6s later: TTL expired, but tip unchanged
        now += 6000
        manager.ensureSynced()
        verify(nodeRpc, times(2)).getFinalizedHead() // re-fetched tip
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any()) // no re-sync
    }

    // ── Tip changed ──

    @Test
    fun `tip changed triggers delta sync`() = runTest {
        var now = 1000L
        var tipCounter = 0
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
            onBlocking { loadState(any()) } doReturn dustState
        }
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { getFinalizedHead() } doAnswer {
                "tip_${tipCounter++}".padEnd(64, '0')
            }
        }

        val manager = createManager(dustRepo = dustRepo, nodeRpc = nodeRpc, clock = { now })

        manager.ensureSynced()
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any())

        // Expire TTL so tip is re-fetched (and gets a new value)
        now += 6000
        manager.ensureSynced()
        verify(dustRepo, times(2)).syncFromBlockchain(any(), any(), any())
    }

    // ── Post-submit invalidation ──

    @Test
    fun `invalidateMemo causes next ensureSynced to re-check tip`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
            onBlocking { loadState(any()) } doReturn dustState
        }
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { getFinalizedHead() } doReturn "same_tip".padEnd(64, '0')
        }

        val manager = createManager(dustRepo = dustRepo, nodeRpc = nodeRpc)

        manager.ensureSynced()
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any())

        // Invalidate memo
        manager.invalidateMemo()

        // Next call must re-check tip and re-sync (even though tip is same)
        manager.ensureSynced()
        verify(dustRepo, times(2)).syncFromBlockchain(any(), any(), any())
    }

    // ── Network error serves stale ──

    @Test
    fun `network error on tip check serves stale cache`() = runTest {
        var callCount = 0
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
            onBlocking { loadState(any()) } doReturn dustState
        }
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { getFinalizedHead() } doAnswer {
                callCount++
                if (callCount == 1) "good_tip".padEnd(64, '0')
                else throw NodeNetworkException("Network down")
            }
        }

        val manager = createManager(dustRepo = dustRepo, nodeRpc = nodeRpc)

        // First call: succeeds
        manager.ensureSynced()

        // Second call: network error, but stale tip is available
        // Should return cached state without throwing
        val result = manager.ensureSynced()
        assertEquals(dustState, result)
    }

    // ── Close releases memory ──

    @Test
    fun `close calls close on cached DustLocalState`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
            onBlocking { loadState(any()) } doReturn dustState
        }
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { getFinalizedHead() } doReturn "tip".padEnd(64, '0')
        }

        val manager = createManager(dustRepo = dustRepo, nodeRpc = nodeRpc)
        manager.ensureSynced()

        manager.close()
        verify(dustState).close()
    }

    // ── Helpers ──

    private fun createManager(
        dustRepo: DustRepository = mock(),
        nodeRpc: NodeRpcClient = mock(),
        clock: () -> Long = System::currentTimeMillis,
    ) = DustSyncManager(
        dustRepository = dustRepo,
        nodeRpcClient = nodeRpc,
        walletAddress = "test_addr",
        dustSeed = ByteArray(32),
        clock = clock,
    )
}
