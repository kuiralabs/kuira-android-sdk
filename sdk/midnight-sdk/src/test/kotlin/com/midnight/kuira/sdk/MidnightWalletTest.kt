package com.midnight.kuira.sdk

import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.TransactionFinalizationResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

/**
 * Unit tests for [MidnightWallet].
 *
 * Tests the orchestration logic — the sequence of calls to DustRepository,
 * IndexerClient, FFI bridge, and NodeRpcClient. The FFI and network boundaries
 * are mocked since they can't run in JVM tests.
 */
class MidnightWalletTest {

    // ── Interface compliance ──

    @Test
    fun `MidnightWallet implements TransactionBalancer`() {
        val wallet = createWallet()
        assertTrue(wallet is TransactionBalancer)
    }

    // ── submitTransaction delegates to NodeRpcClient ──

    @Test
    fun `submitTransaction calls submitAndWaitForFinalization then invalidates dust memo`() = runTest {
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { submitAndWaitForFinalization(any(), any(), anyOrNull()) } doReturn
                TransactionFinalizationResult.Finalized("tx_hash", "block_hash", 42L)
        }
        val syncManager = mock<DustSyncManager> {
            onBlocking { invalidateMemo() } doReturn Unit
        }

        val wallet = createWallet(nodeRpcClient = nodeRpc, dustSyncManager = syncManager)
        wallet.submitTransaction("balanced_hex")

        verify(nodeRpc).submitAndWaitForFinalization(eq("balanced_hex"), any(), anyOrNull())
        verify(syncManager).invalidateMemo()
    }

    // ── syncDust delegates to DustRepository ──

    @Test
    fun `syncDust delegates to DustSyncManager ensureSynced`() = runTest {
        val syncManager = mock<DustSyncManager> {
            onBlocking { ensureSynced() } doReturn mock()
        }

        val wallet = createWallet(dustSyncManager = syncManager)
        wallet.syncDust()

        verify(syncManager).ensureSynced()
    }

    // ── balanceTransaction error cases ──

    @Test(expected = IllegalStateException::class)
    fun `balanceTransaction throws if indexer returns no ledger params`() = runTest {
        val dustState = mock<DustLocalState> {
            on { getStatePointer() } doReturn 12345L
        }
        val syncManager = mock<DustSyncManager> {
            onBlocking { ensureSynced() } doReturn dustState
        }
        val indexer = mock<IndexerClient> {
            onBlocking { getCurrentBlockWithParams() } doReturn BlockInfo(
                height = 100L,
                hash = "a".repeat(64),
                timestamp = 1704067200000L,
                ledgerParameters = null, // No params
            )
        }

        val wallet = createWallet(dustSyncManager = syncManager, indexerClient = indexer)
        wallet.balanceTransaction("proven_hex")
    }

    // ── close ──

    @Test
    fun `close delegates to NodeRpcClient and DustSyncManager`() {
        val nodeRpc = mock<NodeRpcClient>()
        val syncManager = mock<DustSyncManager>()
        val wallet = createWallet(nodeRpcClient = nodeRpc, dustSyncManager = syncManager)
        wallet.close()
        verify(nodeRpc).close()
        verify(syncManager).close()
    }

    // ── Helpers ──

    private fun createWallet(
        dustSyncManager: DustSyncManager = mock(),
        dustRepository: DustRepository = mock(),
        indexerClient: IndexerClient = mock(),
        nodeRpcClient: NodeRpcClient = mock(),
        balanceRepository: BalanceRepository = mock(),
        shieldedTracker: ShieldedBalanceTracker = mock(),
        walletAddress: String = "test_address",
        dustSeed: ByteArray = ByteArray(32),
        provingKeysDir: String = "/tmp/keys",
        networkId: String = "undeployed",
    ) = MidnightWallet(
        dustSyncManager = dustSyncManager,
        dustRepository = dustRepository,
        indexerClient = indexerClient,
        nodeRpcClient = nodeRpcClient,
        balanceRepository = balanceRepository,
        shieldedTracker = shieldedTracker,
        walletAddress = walletAddress,
        dustSeed = dustSeed,
        provingKeysDir = provingKeysDir,
        networkId = networkId,
    )
}
