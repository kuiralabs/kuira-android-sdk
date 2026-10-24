package com.midnight.kuira.sdk

import com.midnight.kuira.core.crypto.dust.DustLocalState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.IOException

/**
 * What [MidnightWallet.refresh] must guarantee when a collaborator FAILS.
 *
 * Every test here makes exactly one dependency throw and asserts the invariant that has to hold
 * regardless. None of this needs a chain, a device or a network — which is the point: the three
 * failure-path defects found on PreProd (a swallowed sync reported as "Synced", a wiped checkpoint,
 * a leaked spill file) were all reproducible offline and none of them had a test.
 *
 * The distinction this pins down: a SYNC leg failing means the balance may be stale, so the status
 * must say so. A BACKUP leg failing does not — the balance is as fresh as the sync that just ran.
 */
class RefreshFailureInvariantsTest {

    @Test
    fun `a failing shielded sync marks the balance stale`() = runTest {
        val wallet = createWallet(shieldedTracker = failingShielded())

        wallet.refresh()

        val status = wallet.syncStatus.value
        assertTrue("expected Failed, got $status", status is SyncStatus.Failed)
        assertTrue(
            "the message must name the leg so a report says which half is stale: $status",
            (status as SyncStatus.Failed).message.contains("shielded"),
        )
    }

    @Test
    fun `a failing dust sync marks the balance stale`() = runTest {
        val syncManager = mock<DustSyncManager> {
            onBlocking { refreshIncremental(anyOrNull()) } doAnswer { throw BOOM }
        }

        val wallet = createWallet(dustSyncManager = syncManager)
        wallet.refresh()

        val status = wallet.syncStatus.value
        assertTrue("expected Failed, got $status", status is SyncStatus.Failed)
        assertTrue(
            "the message must name the leg: $status",
            (status as SyncStatus.Failed).message.contains("dust"),
        )
    }

    /**
     * The asymmetry worth protecting. A cloud backup is the tail of a refresh, not part of reading
     * the chain — reporting "Not synced" because Drive was unreachable would train people to
     * ignore the one signal that means their balance is wrong.
     */
    @Test
    fun `a failing dust backup does NOT mark the balance stale`() = runTest {
        val backup = mock<DustCloudBackup> {
            onBlocking { upload(any(), any(), any()) } doAnswer { throw BOOM }
        }

        val wallet = createWallet(dustCloudBackup = backup)
        wallet.refresh()

        assertEquals(SyncStatus.UpToDate, wallet.syncStatus.value)
    }

    @Test
    fun `a failing app-state backup does NOT mark the balance stale`() = runTest {
        val backup = mock<AppStateCloudBackup> {
            onBlocking { uploadAppState(any()) } doAnswer { throw BOOM }
        }

        val wallet = createWallet(appStateCloudBackup = backup)
        wallet.appStateProvider = { ByteArray(8) }
        wallet.refresh()

        assertEquals(SyncStatus.UpToDate, wallet.syncStatus.value)
    }

    /** One failing leg must not abort the other — partial freshness beats none. */
    @Test
    fun `both legs are attempted even when the first one fails`() = runTest {
        val syncManager = mock<DustSyncManager> {
            onBlocking { refreshIncremental(anyOrNull()) } doAnswer { throw BOOM }
        }

        val wallet = createWallet(
            shieldedTracker = failingShielded(),
            dustSyncManager = syncManager,
        )
        wallet.refresh()

        val status = wallet.syncStatus.value as SyncStatus.Failed
        assertTrue("shielded must be reported: ${status.message}", status.message.contains("shielded"))
        assertTrue("dust must be reported too: ${status.message}", status.message.contains("dust"))
    }

    private fun failingShielded() =
        mock<ShieldedBalanceTracker> { onBlocking { resync() } doAnswer { throw BOOM } }

    private fun createWallet(
        dustSyncManager: DustSyncManager = mock(),
        shieldedTracker: ShieldedBalanceTracker = mock(),
        dustCloudBackup: DustCloudBackup? = null,
        appStateCloudBackup: AppStateCloudBackup? = null,
    ) = MidnightWallet(
        dustSyncManager = dustSyncManager,
        dustRepository = mock(),
        indexerClient = mock(),
        nodeRpcClient = mock(),
        balanceRepository = mock(),
        shieldedTracker = shieldedTracker,
        walletAddress = "mn_addr_undeployed1test",
        dustSeed = ByteArray(32),
        provingKeysDir = "/tmp/keys",
        networkId = "undeployed",
        spentDustNullifierStore = mock { onBlocking { spentNullifiers(any()) } doReturn emptySet() },
        nativeBalance = NativeBalanceInvoker { _, _, _, _, _, _, _, _, _ ->
            throw AssertionError("native balance reached but not stubbed in this test")
        },
        dustCloudBackup = dustCloudBackup,
        appStateCloudBackup = appStateCloudBackup,
    )

    private companion object {
        /** Shaped like the real thing: the PreProd outage arrived as an IO failure mid-stream. */
        private val BOOM = IOException("indexer unreachable")
    }
}
