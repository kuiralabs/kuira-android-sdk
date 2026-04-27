package com.midnight.kuira.sdk

import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.BlockInfo
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
    fun `submitTransaction calls submitAndWaitForFinalization then deletes dust cache`() = runTest {
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { submitAndWaitForFinalization(any(), any()) } doReturn
                TransactionFinalizationResult.Finalized("tx_hash", "block_hash", 42L)
        }
        val dustRepo = mock<DustRepository> {
            onBlocking { deleteState(any()) } doReturn Unit
        }

        val wallet = createWallet(nodeRpcClient = nodeRpc, dustRepository = dustRepo)
        wallet.submitTransaction("balanced_hex")

        verify(nodeRpc).submitAndWaitForFinalization(eq("balanced_hex"), any())
        verify(dustRepo).deleteState("test_address")
    }

    // ── syncDust delegates to DustRepository ──

    @Test
    fun `syncDust calls dustRepository syncFromBlockchain`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { syncFromBlockchain(any(), any(), any()) } doReturn true
        }

        val wallet = createWallet(dustRepository = dustRepo)
        wallet.syncDust()

        verify(dustRepo).syncFromBlockchain(eq("test_address"), any(), any())
    }

    // ── balanceTransaction error cases ──

    @Test(expected = IllegalStateException::class)
    fun `balanceTransaction throws if no dust state found`() = runTest {
        val dustRepo = mock<DustRepository> {
            onBlocking { loadState(any()) } doReturn null
        }

        val wallet = createWallet(dustRepository = dustRepo)
        wallet.balanceTransaction("proven_hex")
    }

    @Test(expected = IllegalStateException::class)
    fun `balanceTransaction throws if indexer returns no ledger params`() = runTest {
        val dustState = mock<DustLocalState> {
            on { getStatePointer() } doReturn 12345L
        }
        val dustRepo = mock<DustRepository> {
            onBlocking { loadState(any()) } doReturn dustState
        }
        val indexer = mock<IndexerClient> {
            onBlocking { getCurrentBlockWithParams() } doReturn BlockInfo(
                height = 100L,
                hash = "a".repeat(64),
                timestamp = 1704067200000L,
                ledgerParameters = null, // No params
            )
        }

        val wallet = createWallet(dustRepository = dustRepo, indexerClient = indexer)
        wallet.balanceTransaction("proven_hex")
    }

    // ── close ──

    @Test
    fun `close delegates to NodeRpcClient`() {
        val nodeRpc = mock<NodeRpcClient>()
        val wallet = createWallet(nodeRpcClient = nodeRpc)
        wallet.close()
        verify(nodeRpc).close()
    }

    // ── Helpers ──

    private fun createWallet(
        dustRepository: DustRepository = mock(),
        indexerClient: IndexerClient = mock(),
        nodeRpcClient: NodeRpcClient = mock(),
        walletAddress: String = "test_address",
        dustSeed: ByteArray = ByteArray(32),
        provingKeysDir: String = "/tmp/keys",
        networkId: String = "undeployed",
    ) = MidnightWallet(
        dustRepository = dustRepository,
        indexerClient = indexerClient,
        nodeRpcClient = nodeRpcClient,
        walletAddress = walletAddress,
        dustSeed = dustSeed,
        provingKeysDir = provingKeysDir,
        networkId = networkId,
    )
}
