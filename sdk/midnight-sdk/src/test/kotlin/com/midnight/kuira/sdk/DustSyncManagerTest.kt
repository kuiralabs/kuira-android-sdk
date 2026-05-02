package com.midnight.kuira.sdk

import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.repository.DustRepository
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
    fun `cold start syncs from blockchain and returns state`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }

        val manager = createManager(dustRepo = dustRepo)
        val result = manager.ensureSynced()

        assertEquals(dustState, result)
        verify(dustRepo).syncFromBlockchain(eq("test_addr"), any(), any(), anyOrNull())
    }

    // ── Cached after first sync ──

    @Test
    fun `second call returns cached state without re-syncing`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }

        val manager = createManager(dustRepo = dustRepo)

        // First call: syncs
        manager.ensureSynced()
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any(), anyOrNull())

        // Second call: returns cached, no re-sync
        val result = manager.ensureSynced()
        assertEquals(dustState, result)
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any(), anyOrNull())
    }

    // ── invalidateMemo is no-op ──

    @Test
    fun `invalidateMemo is no-op — state stays cached`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }

        val manager = createManager(dustRepo = dustRepo)
        manager.ensureSynced()

        manager.invalidateMemo()

        // After invalidate, state is still cached
        val result = manager.ensureSynced()
        assertEquals(dustState, result)
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any(), anyOrNull())
    }

    // ── forceResync clears and re-syncs ──

    @Test
    fun `forceResync clears state and requires fresh sync`() = runTest {
        val freshState = mock<DustLocalState> {
            on { getStatePointer() } doReturn 99999L
        }

        var syncCount = 0
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doAnswer {
                if (syncCount == 0) { syncCount++; dustState }
                else freshState
            }
            onBlocking { deleteState(any()) } doAnswer {}
        }

        val manager = createManager(dustRepo = dustRepo)

        // First sync
        val first = manager.ensureSynced()
        assertEquals(dustState, first)

        // Force resync clears everything
        manager.forceResync()
        verify(dustState).close()
        verify(dustRepo).clearLastSyncedState()
        verify(dustRepo).deleteState("test_addr")

        // Next call must sync again
        val second = manager.ensureSynced()
        assertEquals(freshState, second)
        verify(dustRepo, times(2)).syncFromBlockchain(any(), any(), any(), anyOrNull())
    }

    // ── Fallback full sync when getLastSyncedState returns null ──

    @Test
    fun `falls back to full sync when delta returns no state`() = runTest {
        var callCount = 0
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doAnswer {
                callCount++
                if (callCount == 1) null // delta sync produced no state
                else dustState // full sync produced state
            }
            onBlocking { deleteState(any()) } doAnswer {}
        }

        val manager = createManager(dustRepo = dustRepo)
        val result = manager.ensureSynced()

        assertEquals(dustState, result)
        // Called twice: first delta attempt, then full sync fallback
        verify(dustRepo, times(2)).syncFromBlockchain(any(), any(), any(), anyOrNull())
        verify(dustRepo).deleteState("test_addr")
    }

    // ── Close releases memory ──

    @Test
    fun `close calls close on cached DustLocalState`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }

        val manager = createManager(dustRepo = dustRepo)
        manager.ensureSynced()

        manager.close()
        verify(dustState).close()
    }

    @Test
    fun `close is safe when no state exists`() {
        val manager = createManager()
        // Should not throw
        manager.close()
    }

    // ── Progress callback ──

    @Test
    fun `ensureSynced passes progress callback to repository`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }

        val manager = createManager(dustRepo = dustRepo)
        val progress = mock<suspend (Int, Int) -> Unit>()

        manager.ensureSynced(onSyncProgress = progress)

        verify(dustRepo).syncFromBlockchain(
            eq("test_addr"),
            any(),
            any(),
            eq(progress),
        )
    }

    // ── Helpers ──

    private fun createManager(
        dustRepo: DustRepository = mock(),
        nodeRpc: NodeRpcClient = mock(),
    ) = DustSyncManager(
        dustRepository = dustRepo,
        nodeRpcClient = nodeRpc,
        walletAddress = "test_addr",
        dustSeed = ByteArray(32),
    )
}
