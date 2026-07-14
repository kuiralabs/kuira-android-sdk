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

    // ── Cloud checkpoint seeding (cross-device restore) ──

    @Test
    fun `cloud source not consulted when a local checkpoint exists`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn true
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }
        var fetched = false
        val source = DustCloudBackupSource { fetched = true; null }

        createManager(dustRepo = dustRepo, cloudSource = source).ensureSynced()

        assertFalse("cloud must not be consulted when a local checkpoint exists", fetched)
        verify(dustRepo, never()).saveCheckpoint(any(), any(), any())
    }

    // Note: the positive "fetch-hit → saveCheckpoint" path runs
    // DustLocalState.deserialize (native), so it's covered by the instrumented
    // DustCheckpointResumeTest, not here. These JVM cases pin the robustness
    // branches that don't depend on deserialize succeeding.

    @Test
    fun `cloud fetch returning null falls through to genesis without seeding`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }
        val source = DustCloudBackupSource { null }

        val result = createManager(dustRepo = dustRepo, cloudSource = source).ensureSynced()

        assertEquals(dustState, result)
        verify(dustRepo, never()).saveCheckpoint(any(), any(), any())
    }

    @Test
    fun `cloud fetch throwing degrades to genesis, never crashes`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }
        val source = DustCloudBackupSource { error("drive unreachable") }

        val result = createManager(dustRepo = dustRepo, cloudSource = source).ensureSynced()

        assertEquals(dustState, result)
        verify(dustRepo, never()).saveCheckpoint(any(), any(), any())
    }

    // ── Restore gate (dust-backup restore continuity) ──
    //
    // The gate is the host's one chance to obtain Drive consent BEFORE the first
    // no-checkpoint sync, so the cloud-checkpoint fetch can succeed instead of the sync
    // silently replaying from genesis on a fresh install. Ordering is the contract:
    // gate → cloud seed → sync.

    @Test
    fun `restore gate awaited before the cloud fetch when no checkpoint exists`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }
        val order = mutableListOf<String>()
        val source = DustCloudBackupSource { order.add("fetch"); null }

        createManager(dustRepo = dustRepo, cloudSource = source, restoreGate = { order.add("gate") })
            .ensureSynced()

        assertEquals("the gate must resolve consent BEFORE the silent fetch", listOf("gate", "fetch"), order)
    }

    @Test
    fun `restore gate not run when a local checkpoint exists`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn true
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }
        var gateRan = false

        createManager(dustRepo = dustRepo, restoreGate = { gateRan = true }).ensureSynced()

        assertFalse("a warm install must never pay the gate (no prompt risk)", gateRan)
    }

    @Test
    fun `restore gate runs at most once per manager`() = runTest {
        var syncCount = 0
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            // refreshIncremental path: state closed → ensureSynced again with no cached state
            on { getLastSyncedState() } doAnswer { syncCount++; dustState }
        }
        var gateRuns = 0
        val manager = createManager(dustRepo = dustRepo, restoreGate = { gateRuns++ })

        manager.ensureSynced()
        manager.refreshIncremental() // closes the state, re-enters ensureSynced cold

        assertEquals("the gate must not re-prompt within one session", 1, gateRuns)
    }

    @Test
    fun `restore gate throwing degrades to a normal sync, never blocks`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }

        val result = createManager(dustRepo = dustRepo, restoreGate = { error("host gate broke") })
            .ensureSynced()

        assertEquals(dustState, result)
    }

    @Test
    fun `restore gate cancellation propagates — the sync must not continue torn down`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn false
        }
        val manager = createManager(
            dustRepo = dustRepo,
            restoreGate = { throw kotlinx.coroutines.CancellationException("SDK torn down") },
        )

        val thrown = runCatching { manager.ensureSynced() }.exceptionOrNull()

        assertTrue(
            "cancellation must propagate, not be logged-and-continued",
            thrown is kotlinx.coroutines.CancellationException,
        )
        verify(dustRepo, never()).syncFromBlockchain(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `no gate wired keeps today's behavior exactly`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint("test_addr") } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNull()) } doReturn true
            on { getLastSyncedState() } doReturn dustState
        }

        val result = createManager(dustRepo = dustRepo).ensureSynced()

        assertEquals(dustState, result)
        verify(dustRepo, times(1)).syncFromBlockchain(any(), any(), any(), anyOrNull())
    }

    // ── Helpers ──

    private fun createManager(
        dustRepo: DustRepository = mock(),
        nodeRpc: NodeRpcClient = mock(),
        cloudSource: DustCloudBackupSource? = null,
        restoreGate: (suspend () -> Unit)? = null,
    ) = DustSyncManager(
        dustRepository = dustRepo,
        nodeRpcClient = nodeRpc,
        walletAddress = "test_addr",
        dustSeed = ByteArray(32),
        cloudBackupSource = cloudSource,
        restoreGate = restoreGate,
    )
}
