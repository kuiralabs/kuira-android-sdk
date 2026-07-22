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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    //  recovery loop. Regression: a submit-time 170 arrives as TransactionRejected,
    //  not NodeRpcError — the loop used to miss it and fail without recovering. ──

    @Test
    fun `isDustSpendProofError detects 170 from TransactionRejected and NodeRpcError, not others`() {
        val wallet = createWallet()
        assertTrue(wallet.isDustSpendProofError(TransactionRejected("Invalid Transaction", customErrorCode = 170)))
        assertTrue(wallet.isDustSpendProofError(NodeRpcError(1010, "Invalid Transaction", "Custom error: 170")))
        assertFalse(wallet.isDustSpendProofError(TransactionRejected("Invalid Transaction", customErrorCode = 115)))
        assertFalse(wallet.isDustSpendProofError(NodeNetworkException("offline")))
        // 171 is a different recovery gate — must NOT be caught by the 170 detector.
        assertFalse(wallet.isDustSpendProofError(TransactionRejected("Invalid Transaction", customErrorCode = 171)))
    }

    // ── Error-171 (OutOfDustValidityWindow) detection drives the same balanceAndSubmit
    //  re-sync recovery loop as 170 — an idle dust state whose ctime drifted out of the
    //  node's [tblock-grace, tblock] window. Must be detected as TransactionRejected
    //  (customErrorCode) AND raw NodeRpcError, and kept distinct from 170/115. ──

    @Test
    fun `isOutOfDustValidityWindowError detects 171 from TransactionRejected and NodeRpcError, not others`() {
        val wallet = createWallet()
        assertTrue(wallet.isOutOfDustValidityWindowError(TransactionRejected("Invalid Transaction", customErrorCode = 171)))
        assertTrue(wallet.isOutOfDustValidityWindowError(NodeRpcError(1010, "Invalid Transaction", "Custom error: 171")))
        assertFalse(wallet.isOutOfDustValidityWindowError(TransactionRejected("Invalid Transaction", customErrorCode = 170)))
        assertFalse(wallet.isOutOfDustValidityWindowError(TransactionRejected("Invalid Transaction", customErrorCode = 115)))
        assertFalse(wallet.isOutOfDustValidityWindowError(NodeNetworkException("offline")))
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

    // ── : heavy SDK work runs off the caller's thread (withContext IO),
    //  so a main-thread caller (wallet panel, match flow) doesn't jank. ──

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

        // syncDust now always supplies a progress callback — it publishes to
        // syncStatus in addition to forwarding the caller's onProgress.
        verify(syncManager).ensureSynced(any())
    }

    @Test
    fun `syncDust publishes Syncing then UpToDate to syncStatus`() = runTest {
        val syncManager = mock<DustSyncManager> {
            onBlocking { ensureSynced(any()) } doReturn mock()
        }
        val wallet = createWallet(dustSyncManager = syncManager)

        assertEquals(SyncStatus.Idle, wallet.syncStatus.value)
        wallet.syncDust()
        assertEquals(SyncStatus.UpToDate, wallet.syncStatus.value)
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

    // ── walletBackupPrefsProvider (the blob-borne prefs supplier, ) ──

    @Test
    fun `app-state upload stamps the SUPPLIER's prefs at upload time`() = runTest {
        // The supplier (not a pushed snapshot) is the fix for the stale-prefs clobber: a
        // pref change after bootstrap must be reflected in the very next upload.
        var uploaded: ByteArray? = null
        val backup = mock<AppStateCloudBackup> {
            onBlocking { uploadAppState(any()) } doAnswer { uploaded = it.arguments[0] as ByteArray; Unit }
        }
        var prefs = WalletBackupPrefs(dustBackupEnabled = false, dustBackupOptedOut = false)
        val wallet = createWallet(appStateCloudBackup = backup)
        wallet.walletBackupPrefsProvider = { prefs }

        prefs = WalletBackupPrefs(dustBackupEnabled = true, dustBackupOptedOut = false) // post-bootstrap change
        wallet.backupAppStateToCloud(byteArrayOf(7))

        val decoded = AppStateEnvelope.decode(uploaded!!)
        assertEquals("the upload must carry the prefs CURRENT at upload time", prefs, decoded.prefs)
        assertArrayEquals(byteArrayOf(7), decoded.hostPayload)
    }

    @Test
    fun `opted-out supplier blocks both uploads even when the flags say enabled`() = runTest {
        // Belt over the flags: a restore-gate-mirrored opt-out must stop uploads BEFORE the
        // next bootstrap pass re-applies the flags — a -disabled wallet must never
        // re-create the blobs it deleted.
        val dustBackup = mock<DustCloudBackup>()
        val appBackup = mock<AppStateCloudBackup>()
        val wallet = createWallet(dustCloudBackup = dustBackup, appStateCloudBackup = appBackup)
        wallet.dustBackupEnabled = true // flags still say enabled (pre-gate bootstrap)
        wallet.appStateBackupEnabled = true
        wallet.walletBackupPrefsProvider =
            { WalletBackupPrefs(dustBackupEnabled = false, dustBackupOptedOut = true) }

        wallet.backupDustToCloud()
        wallet.backupAppStateToCloud(byteArrayOf(1))

        verify(dustBackup, never()).upload(any(), any(), any())
        verify(appBackup, never()).uploadAppState(any())
    }

    // ── close ──

    @Test
    fun `close delegates to NodeRpcClient and DustSyncManager`() = runTest {
        val nodeRpc = mock<NodeRpcClient>()
        val syncManager = mock<DustSyncManager>()
        val wallet = createWallet(nodeRpcClient = nodeRpc, dustSyncManager = syncManager)
        wallet.close()
        verify(nodeRpc).close()
        verify(syncManager).close()
    }

    // Regression (PreProd "DustLocalState has been closed"): a teardown (network switch / logout)
    // froze the shared dust state while a balance was mid-flight reading it. refresh() already
    // guarded the dust-state swap under balanceMutex; close() did NOT — it freed the state without
    // the mutex, so a balance holding it resumed onto a closed state. localnet's instant dust sync
    // never opened the window; PreProd's seconds-long sync did.
    //
    // This reproduces the race deterministically: a refresh() holds balanceMutex while its dust work
    // is in progress (blocked on a latch), and a concurrent close() must WAIT for that mutex before
    // freeing the dust state. Before the fix close() completed immediately (assertNull fails +
    // close() is observed on the sync manager); after, it blocks until refresh releases.
    @Test
    fun `close waits for an in-flight balance before freeing the dust state`() = runBlocking {
        val balanceHoldsMutex = CompletableDeferred<Unit>()
        val releaseBalance = CompletableDeferred<Unit>()
        val syncManager = mock<DustSyncManager> {
            // refreshIncremental runs under balanceMutex (see MidnightWallet.refresh). Signal that
            // the balance now holds the mutex, then block — simulating a slow PreProd dust sync.
            onBlocking { refreshIncremental(anyOrNull()) } doSuspendableAnswer {
                balanceHoldsMutex.complete(Unit)
                releaseBalance.await()
                mock<DustLocalState>()
            }
        }
        val nodeRpc = mock<NodeRpcClient>()
        val wallet = createWallet(dustSyncManager = syncManager, nodeRpcClient = nodeRpc)

        val balancing = launch(Dispatchers.IO) { wallet.refresh() }
        balanceHoldsMutex.await() // deterministic: refresh now holds balanceMutex

        val tearing = launch(Dispatchers.IO) { wallet.close() }

        // close() must block on balanceMutex — it cannot free the dust state while the balance holds
        // it. A non-null result would mean close() completed early (the pre-fix bug).
        val closedEarly = withTimeoutOrNull(1_000) { tearing.join(); true }
        assertNull("close() must not complete while a balance holds balanceMutex", closedEarly)
        verify(syncManager, never()).close()

        releaseBalance.complete(Unit) // balance finishes, releases the mutex
        balancing.join()
        withTimeout(5_000) { tearing.join() } // now close() proceeds
        verify(syncManager).close()
        verify(nodeRpc).close()
    }

    // Regression (PreProd genesis replay on back-to-back txs): a balance-time FFI failure went
    // STRAIGHT to a genesis rebuild — deleting the checkpoint and replaying the full event stream
    // (1.27M events, ~10 min of dead wallet on PreProd). The common cause is benign staleness:
    // the previous tx just spent the wallet's dust and its events haven't reached the indexer yet
    // (the freshness probe sees "nothing new" during that lag), so the cached state's only UTXO is
    // pending → FFI returns null. The recovery must mirror balanceAndSubmit's 170/171 ladder —
    // delta re-sync FIRST (picks up the spend + change UTXO in seconds); genesis only if the delta
    // didn't cure it. Before the fix this test fails on forceResync being invoked.
    @Test
    fun `balance-time failure delta re-syncs and retries — no genesis checkpoint wipe`() = runTest {
        var balanceCalls = 0
        // First balance fails (stale state: own spend not yet indexed); the retry after the
        // delta re-sync succeeds — the exact PreProd shape.
        val nativeBalance = NativeBalanceInvoker { _, _, _, _, _, _, _, _, _ ->
            if (++balanceCalls == 1) null else "cafe;"
        }
        val dustState = mock<DustLocalState> {
            on { getStatePointer() } doReturn 1L
            on { currentNullifiers(any()) } doReturn null
            on { syncTimeMs() } doReturn 1_000L
        }
        val syncManager = mock<DustSyncManager> {
            onBlocking { ensureSynced(anyOrNull()) } doReturn dustState
            onBlocking { refreshIncremental(anyOrNull()) } doReturn dustState
        }
        val indexer = mock<IndexerClient> {
            onBlocking { getCurrentBlockWithParams() } doReturn BlockInfo(
                height = 100L,
                hash = "a".repeat(64),
                timestamp = 1704067200000L,
                ledgerParameters = "params_hex",
            )
        }
        val wallet = createWallet(
            dustSyncManager = syncManager,
            indexerClient = indexer,
            nativeBalance = nativeBalance,
        )

        val balanced = wallet.balanceTransaction("proven_hex")

        assertEquals("cafe", balanced)
        assertEquals("the retry after the delta re-sync must re-balance", 2, balanceCalls)
        verify(syncManager).refreshIncremental(anyOrNull())
        // The alpha04 lesson: recoverable staleness must NOT nuke the checkpoint.
        verify(syncManager, never()).forceResync()
    }

    // ── balanceAndSubmit 170/171 submit-time recovery ladder ──
    //
    // Pins the alpha04 escalation ladder itself: a submit rejected with 170 (stale dust root)
    // or 171 (dust ctime out of the validity window) recovers with a delta re-sync FIRST, a
    // genesis rebuild only if the delta didn't cure it, and gives up after both. The ladder
    // shipped verified on-device only (the FFI boundary was unmockable before the
    // NativeBalanceInvoker seam), so until these tests nothing guarded its escalation order —
    // the exact blind spot that let doBalance keep its genesis-first recovery for months.

    private fun rejected(code: Int) = TransactionRejected("Invalid Transaction", customErrorCode = code)

    /** Wallet whose balance always succeeds, so the tests drive recovery purely from submit. */
    private fun submitLadderWallet(nodeRpc: NodeRpcClient): Pair<MidnightWallet, DustSyncManager> {
        val dustState = mock<DustLocalState> {
            on { getStatePointer() } doReturn 1L
            on { currentNullifiers(any()) } doReturn null
            on { syncTimeMs() } doReturn 1_000L
        }
        val syncManager = mock<DustSyncManager> {
            onBlocking { ensureSynced(anyOrNull()) } doReturn dustState
            onBlocking { refreshIncremental(anyOrNull()) } doReturn dustState
        }
        val indexer = mock<IndexerClient> {
            onBlocking { getCurrentBlockWithParams() } doReturn BlockInfo(
                height = 100L,
                hash = "a".repeat(64),
                timestamp = 1704067200000L,
                ledgerParameters = "params_hex",
            )
        }
        val wallet = createWallet(
            dustSyncManager = syncManager,
            indexerClient = indexer,
            nodeRpcClient = nodeRpc,
            nativeBalance = NativeBalanceInvoker { _, _, _, _, _, _, _, _, _ -> "cafe;" },
        )
        return wallet to syncManager
    }

    @Test
    fun `submit-time 170 recovers with a delta re-sync first — no genesis rebuild`() = runTest {
        val finalized = TransactionFinalizationResult.Finalized("tx_hash", "block_hash", 42L)
        var submits = 0
        // doSuspendableAnswer, not doThrow: Mockito validates doThrow's exception against the
        // compiled signature, and a suspend fun declares no `throws` — so any checked exception
        // (TransactionRejected extends Exception) is rejected at stubbing time.
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { submitAndWaitForFinalization(any(), any(), anyOrNull()) } doSuspendableAnswer {
                if (++submits == 1) throw rejected(170) else finalized
            }
        }
        val (wallet, syncManager) = submitLadderWallet(nodeRpc)

        wallet.balanceAndSubmit("proven_hex", null)

        verify(syncManager).refreshIncremental(anyOrNull())
        verify(syncManager, never()).forceResync()
        assertEquals("one rejected submit + one successful retry", 2, submits)
    }

    @Test
    fun `submit-time 171 takes the same delta-first ladder`() = runTest {
        val finalized = TransactionFinalizationResult.Finalized("tx_hash", "block_hash", 42L)
        var submits = 0
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { submitAndWaitForFinalization(any(), any(), anyOrNull()) } doSuspendableAnswer {
                if (++submits == 1) throw rejected(171) else finalized
            }
        }
        val (wallet, syncManager) = submitLadderWallet(nodeRpc)

        wallet.balanceAndSubmit("proven_hex", null)

        verify(syncManager).refreshIncremental(anyOrNull())
        verify(syncManager, never()).forceResync()
    }

    @Test
    fun `submit-time 170 persisting after the delta escalates to a genesis rebuild — in that order`() = runTest {
        val finalized = TransactionFinalizationResult.Finalized("tx_hash", "block_hash", 42L)
        var submits = 0
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { submitAndWaitForFinalization(any(), any(), anyOrNull()) } doSuspendableAnswer {
                if (++submits <= 2) throw rejected(170) else finalized
            }
        }
        val (wallet, syncManager) = submitLadderWallet(nodeRpc)

        wallet.balanceAndSubmit("proven_hex", null)

        // Escalation ORDER is the contract: the cheap delta must be tried before the wipe.
        val order = inOrder(syncManager)
        order.verify(syncManager).refreshIncremental(anyOrNull())
        order.verify(syncManager).forceResync()
        assertEquals("initial + one retry per rung", 3, submits)
    }

    @Test
    fun `submit-time 170 surviving both recoveries propagates — bounded, no infinite loop`() = runTest {
        var submits = 0
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { submitAndWaitForFinalization(any(), any(), anyOrNull()) } doSuspendableAnswer {
                submits++; throw rejected(170)
            }
        }
        val (wallet, _) = submitLadderWallet(nodeRpc)

        val thrown = runCatching { wallet.balanceAndSubmit("proven_hex", null) }.exceptionOrNull()

        assertTrue("the exhausted ladder must rethrow the rejection", thrown is TransactionRejected)
        // Initial attempt + one retry per rung — then stop.
        assertEquals(3, submits)
    }

    @Test
    fun `non-recoverable submit rejection propagates immediately — no dust recovery`() = runTest {
        // 115 (stale UTXO) is NOT in the ladder's recoverable set — re-syncing dust can't cure it.
        var submits = 0
        val nodeRpc = mock<NodeRpcClient> {
            onBlocking { submitAndWaitForFinalization(any(), any(), anyOrNull()) } doSuspendableAnswer {
                submits++; throw rejected(115)
            }
        }
        val (wallet, syncManager) = submitLadderWallet(nodeRpc)

        val thrown = runCatching { wallet.balanceAndSubmit("proven_hex", null) }.exceptionOrNull()

        assertTrue(thrown is TransactionRejected)
        verify(syncManager, never()).refreshIncremental(anyOrNull())
        verify(syncManager, never()).forceResync()
        assertEquals(1, submits)
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
        // Fails loudly if a test unexpectedly reaches the FFI boundary — every path that should
        // is stubbed explicitly, and the real JNI object can't load in a JVM test.
        nativeBalance: NativeBalanceInvoker = NativeBalanceInvoker { _, _, _, _, _, _, _, _, _ ->
            throw AssertionError("native balance reached but not stubbed in this test")
        },
        dustCloudBackup: DustCloudBackup? = null,
        appStateCloudBackup: AppStateCloudBackup? = null,
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
        nativeBalance = nativeBalance,
        dustCloudBackup = dustCloudBackup,
        appStateCloudBackup = appStateCloudBackup,
    )
}
