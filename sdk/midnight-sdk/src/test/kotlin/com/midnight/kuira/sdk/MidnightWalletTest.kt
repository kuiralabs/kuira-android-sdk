package com.midnight.kuira.sdk

import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.BlockInfo
import com.midnight.kuira.core.indexer.model.TokenBalance
import com.midnight.kuira.core.indexer.model.TokenTypeMapper
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.repository.SpentDustNullifierStore
import com.midnight.kuira.core.ledger.api.NodeNetworkException
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.NodeRpcError
import com.midnight.kuira.core.ledger.api.TransactionFinalizationResult
import com.midnight.kuira.core.ledger.api.TransactionRejected
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.math.BigInteger

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

    // ── Error-170 (InvalidDustSpendProof) detection drives the balanceAndSubmit
    //    recovery loop. Regression: a submit-time 170 arrives as TransactionRejected,
    //    not NodeRpcError — the loop used to miss it and fail without recovering. ──

    @Test
    fun `isDustSpendProofError detects 170 from TransactionRejected and NodeRpcError, not others`() {
        val wallet = createWallet()
        assertTrue(wallet.isDustSpendProofError(TransactionRejected("Invalid Transaction", customErrorCode = 170)))
        assertTrue(wallet.isDustSpendProofError(NodeRpcError(1010, "Invalid Transaction", "Custom error: 170")))
        assertFalse(wallet.isDustSpendProofError(TransactionRejected("Invalid Transaction", customErrorCode = 115)))
        assertFalse(wallet.isDustSpendProofError(NodeNetworkException("offline")))
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

    // ── #226: heavy SDK work runs off the caller's thread (withContext IO),
    //    so a main-thread caller (wallet panel, match flow) doesn't jank. ──

    @Test
    fun `balance offloads its work off the caller thread`() = runTest {
        val callerThread = Thread.currentThread()
        var workThread: Thread? = null
        val balanceRepo = mock<BalanceRepository> {
            on { observeBalances(any()) } doAnswer {
                workThread = Thread.currentThread()
                flowOf(emptyList<TokenBalance>())
            }
        }
        val dustRepo = mock<DustRepository> {
            onBlocking { getCurrentBalance(any()) } doReturn BigInteger.ZERO
        }
        val tracker = mock<ShieldedBalanceTracker> {
            on { currentNight() } doReturn BigInteger.ZERO
        }
        val wallet = createWallet(
            balanceRepository = balanceRepo,
            dustRepository = dustRepo,
            shieldedTracker = tracker,
        )

        wallet.balance()

        assertNotNull("balance() should query the balance repository", workThread)
        assertNotEquals(
            "balance() must run its work off the caller thread (withContext(Dispatchers.IO))",
            callerThread,
            workThread,
        )
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
            // null = "couldn't determine current nullifiers" → wallet skips prune /
            // fast-fail and proceeds to the ledger-params check under test.
            on { currentNullifiers(any()) } doReturn null
        }
        val syncManager = mock<DustSyncManager> {
            onBlocking { ensureSynced(anyOrNull()) } doReturn dustState
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

    @Test(expected = InsufficientDustException::class)
    fun `balanceTransaction fast-fails when every dust UTXO is already excluded`() = runTest {
        // Synced state holds two UTXOs, both already in the spent-dust skip-set →
        // no spendable dust → fail fast (no wasteful re-sync, no balance attempt).
        val dustState = mock<DustLocalState> {
            on { getStatePointer() } doReturn 12345L
            on { currentNullifiers(any()) } doReturn listOf("aa", "bb")
        }
        val syncManager = mock<DustSyncManager> {
            onBlocking { ensureSynced(anyOrNull()) } doReturn dustState
        }
        val store = mock<SpentDustNullifierStore> {
            onBlocking { spentNullifiers(any()) } doReturn setOf("aa", "bb")
        }

        val wallet = createWallet(dustSyncManager = syncManager, spentDustNullifierStore = store)
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

    // ── balanceFlow reactivity (the auto-update-on-credit fix) ──

    @Test
    fun `balanceFlow re-emits when unshielded OR shielded NIGHT changes`() = runTest {
        val addr = "test_address"
        // Both balance sources are observable; the wallet combines them.
        val unshielded = MutableStateFlow(
            listOf(TokenBalance(TokenTypeMapper.NIGHT_SYMBOL, BigInteger.valueOf(100), utxoCount = 1)),
        )
        val shielded = MutableStateFlow(BigInteger.ZERO)
        val balanceRepo = mock<BalanceRepository> {
            on { observeBalances(addr) } doReturn unshielded
        }
        val tracker = mock<ShieldedBalanceTracker> {
            on { nightFlow } doReturn shielded
        }
        val dustRepo = mock<DustRepository> {
            onBlocking { getCurrentBalance(addr) } doReturn BigInteger.ZERO
        }
        val wallet = createWallet(
            balanceRepository = balanceRepo,
            shieldedTracker = tracker,
            dustRepository = dustRepo,
            walletAddress = addr,
        )

        val seen = mutableListOf<WalletBalance>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            wallet.balanceFlow().collect { seen += it }
        }

        // Initial combine of both sources.
        assertEquals(BigInteger.valueOf(100), seen.last().unshieldedNight)
        assertEquals(BigInteger.ZERO, seen.last().shieldedNight)

        // A shielded-only credit MUST re-emit — this was the bug: a shielded
        // change was synced but never reached the panel.
        shielded.value = BigInteger.valueOf(50)
        assertEquals(BigInteger.valueOf(50), seen.last().shieldedNight)
        assertEquals(BigInteger.valueOf(100), seen.last().unshieldedNight)

        // An unshielded credit (airdrop) re-emits too.
        unshielded.value =
            listOf(TokenBalance(TokenTypeMapper.NIGHT_SYMBOL, BigInteger.valueOf(200), utxoCount = 1))
        assertEquals(BigInteger.valueOf(200), seen.last().unshieldedNight)
        assertEquals(BigInteger.valueOf(50), seen.last().shieldedNight)

        job.cancel()
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
        spentDustNullifierStore: SpentDustNullifierStore = mock {
            onBlocking { spentNullifiers(any()) } doReturn emptySet()
        },
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
        spentDustNullifierStore = spentDustNullifierStore,
    )
}
