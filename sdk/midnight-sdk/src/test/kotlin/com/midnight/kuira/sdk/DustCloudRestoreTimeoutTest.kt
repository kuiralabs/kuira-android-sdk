package com.midnight.kuira.sdk

import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.*

/**
 * A cloud that never answers must not freeze the wallet.
 *
 * `DustCloudBackupSource` promises that any failure "degrades cleanly to genesis — never block".
 * An unreachable Google Play services does not fail, though: it hangs. On a device that showed up
 * as a contract call stuck on "Balancing dust fee…" for twelve minutes with no error, no sync and
 * no timeout, because the fetch was wrapped in `runCatching` — which catches a throw and waits
 * forever for a hang.
 */
class DustCloudRestoreTimeoutTest {

    @Test
    fun `a cloud fetch that never returns still reaches the chain`() = runTest {
        val neverAnswers = DustCloudBackupSource { CompletableDeferred<RestoredDustCheckpoint?>().await() }
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint(any()) } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNullProgress()) } doReturn true
        }

        // The manager throws afterwards because the fake repo reports a sync with no state
        // behind it; what matters here is that it GOT there rather than waiting on the cloud.
        runCatching { createManager(dustRepo, cloudSource = neverAnswers).refreshIncremental(null) }

        verify(dustRepo, atLeastOnce()).syncFromBlockchain(any(), any(), any(), anyOrNullProgress())
    }

    /**
     * The gate waits on a person, so it has its own, longer budget — but it is still a budget.
     * A host whose dialog never resolves must not strand every later sync behind it.
     */
    @Test
    fun `a restore gate that never settles still reaches the chain`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { hasCheckpoint(any()) } doReturn false
            onBlocking { syncFromBlockchain(any(), any(), any(), anyOrNullProgress()) } doReturn true
        }

        runCatching {
            createManager(dustRepo, restoreGate = { CompletableDeferred<Unit>().await() })
                .refreshIncremental(null)
        }

        verify(dustRepo, atLeastOnce()).syncFromBlockchain(any(), any(), any(), anyOrNullProgress())
    }

    /** Cancellation is teardown, not degradation — it must propagate rather than fall to genesis. */
    @Test
    fun `cancelling the sync does not look like a failed cloud fetch`() = runTest {
        val dustRepo = mock<DustRepository> { onBlocking { hasCheckpoint(any()) } doReturn false }
        val cancelled = DustCloudBackupSource { throw kotlinx.coroutines.CancellationException("torn down") }

        val threw = runCatching {
            createManager(dustRepo, cloudSource = cancelled).refreshIncremental(null)
        }.exceptionOrNull()

        assertTrue(
            "teardown must surface as cancellation, not as a quiet genesis fallback: $threw",
            threw is kotlinx.coroutines.CancellationException,
        )
    }

    private fun anyOrNullProgress(): (suspend (Int, Int) -> Unit)? = org.mockito.kotlin.anyOrNull()

    private fun createManager(
        dustRepo: DustRepository,
        cloudSource: DustCloudBackupSource? = null,
        restoreGate: (suspend () -> Unit)? = null,
    ) = DustSyncManager(
        dustRepository = dustRepo,
        nodeRpcClient = mock<NodeRpcClient>(),
        walletAddress = "mn_addr_preprod1timeout",
        dustSeed = ByteArray(32),
        cloudBackupSource = cloudSource,
        restoreGate = restoreGate,
    )
}
