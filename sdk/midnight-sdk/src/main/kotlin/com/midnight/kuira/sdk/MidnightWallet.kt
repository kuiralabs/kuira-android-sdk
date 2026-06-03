package com.midnight.kuira.sdk

import android.util.Log
import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.TokenTypeMapper
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.NodeRpcClient.SubmissionStage
import com.midnight.kuira.core.ledger.api.NodeRpcError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.math.BigInteger

/**
 * Embedded wallet that handles transaction balancing and submission on-device.
 *
 * Implements [TransactionBalancer] so it can be plugged into
 * [com.midnight.kuira.core.compact.MidnightConfig] as a drop-in replacement
 * for the remote DAppConnectorClient.
 *
 * Uses [DustSyncManager] for session-scoped caching. Handles error 170
 * (InvalidDustSpendProof) by auto-retrying with a fresh dust sync.
 */
class MidnightWallet internal constructor(
    private val dustSyncManager: DustSyncManager,
    private val dustRepository: DustRepository,
    private val indexerClient: IndexerClient,
    private val nodeRpcClient: NodeRpcClient,
    private val balanceRepository: BalanceRepository,
    private val shieldedTracker: ShieldedBalanceTracker,
    private val walletAddress: String,
    private val dustSeed: ByteArray,
    private val provingKeysDir: String,
    private val networkId: String,
    private val dustCloudBackup: DustCloudBackup? = null,
) : TransactionBalancer {

    private val balanceMutex = Mutex()

    /** Bech32m address this wallet receives NIGHT at. Exposed for dApps that want to display / share it. */
    val address: String get() = walletAddress

    /**
     * Current snapshot of every balance the SDK tracks for this wallet —
     * unshielded NIGHT, shielded NIGHT, DUST, and the registration flag.
     *
     * **Sources (all kept fresh by the SDK; no consumer action required):**
     *  - Unshielded NIGHT: [BalanceRepository] flow over the unshielded-tx
     *    subscription started at SDK-build time.
     *  - Shielded NIGHT: [ShieldedBalanceTracker]'s cache, refreshed by the
     *    zswap-event subscription started at SDK-build time. Returns 0 until
     *    the first sync lands (a few hundred ms on localnet, a few seconds
     *    on PREPROD on first run).
     *  - DUST: [DustRepository] local replay state.
     *
     * `dustRegistered` is a best-effort heuristic — true iff DUST > 0. A
     * fresh wallet that hasn't called [MidnightSdk.registerForDustGeneration]
     * reports false here even with NIGHT funded, because the chain doesn't
     * release spendable dust to an unregistered key.
     *
     * For a forced resync (e.g. before a sequential tx or when the UI wants
     * to skip the subscription's natural cadence), call [refresh] first.
     */
    suspend fun balance(): WalletBalance {
        val tokenBalances = balanceRepository.observeBalances(walletAddress).first()
        val unshielded = tokenBalances
            .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
            ?.balance
            ?: BigInteger.ZERO
        val shielded = shieldedTracker.currentNight()
        val dust = dustRepository.getCurrentBalance(walletAddress)
        return WalletBalance(
            unshieldedNight = unshielded,
            shieldedNight = shielded,
            dust = dust,
            dustRegistered = dust > BigInteger.ZERO,
        )
    }

    /**
     * Observable balance — emits a fresh [WalletBalance] whenever the wallet's
     * **unshielded OR shielded** NIGHT changes. Both are driven by background
     * indexer subscriptions, so **externally-received funds (an airdrop, an
     * incoming shielded or unshielded transfer) surface automatically** without
     * a manual refresh. Each emission also re-reads dust.
     *
     * Use this for a live UI (the wallet panel collects it); [balance] stays the
     * one-shot snapshot for callers that just need the current value.
     *
     * Note: a *dust-only* change (e.g. right after
     * [MidnightSdk.registerForDustGeneration]) won't re-emit on its own — that
     * path forces its own refresh.
     */
    fun balanceFlow(): Flow<WalletBalance> =
        combine(
            balanceRepository.observeBalances(walletAddress),
            shieldedTracker.nightFlow,
        ) { tokenBalances, shieldedNight ->
            val unshielded = tokenBalances
                .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
                ?.balance
                ?: BigInteger.ZERO
            val dust = dustRepository.getCurrentBalance(walletAddress)
            WalletBalance(
                unshieldedNight = unshielded,
                shieldedNight = shieldedNight,
                dust = dust,
                dustRegistered = dust > BigInteger.ZERO,
            )
        }

    /**
     * Suspend until the wallet's NIGHT balance reaches [minNight] or [timeoutMs] elapses.
     *
     * Used by onboarding flows after the user is told "fund this address" — the
     * UI can call this and show a spinner until funding lands, then proceed to
     * [MidnightSdk.registerForDustGeneration]. Throws
     * [kotlinx.coroutines.TimeoutCancellationException] on timeout; callers can
     * catch and re-prompt the user.
     *
     * On the funded edge, forces a fresh dust resync so the next operation
     * doesn't read stale UTXO state. (The unshielded subscription drives NIGHT
     * automatically — dust is on a separate sync path.)
     *
     * @param minNight Minimum NIGHT amount that counts as "funded" (in u128 base units).
     * @param pollIntervalMs Reserved — currently driven by the underlying Flow's emissions, not a poll.
     * @param timeoutMs Wall-clock cap on how long to wait.
     */
    suspend fun waitForFunding(
        minNight: BigInteger,
        @Suppress("UNUSED_PARAMETER") pollIntervalMs: Long = 3_000L,
        timeoutMs: Long = DEFAULT_FUNDING_TIMEOUT_MS,
    ): WalletBalance {
        val funded = withTimeout(timeoutMs) {
            balanceRepository.observeBalances(walletAddress)
                .first { balances ->
                    val night = balances
                        .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
                        ?.balance
                        ?: BigInteger.ZERO
                    night >= minNight
                }
        }
        // Funding arrived → force a dust resync so subsequent ops aren't stale.
        dustSyncManager.invalidateMemo()
        dustSyncManager.ensureSynced()
        val unshielded = funded
            .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
            ?.balance
            ?: BigInteger.ZERO
        val dust = dustRepository.getCurrentBalance(walletAddress)
        return WalletBalance(
            unshieldedNight = unshielded,
            // Shielded NIGHT is the SDK's cached value — external funding
            // lands on the unshielded address, so we don't trigger a shielded
            // resync here. Consumers that need a fresh shielded reading can
            // call [refresh] after waitForFunding returns.
            shieldedNight = shieldedTracker.currentNight(),
            dust = dust,
            dustRegistered = dust > BigInteger.ZERO,
        )
    }

    /**
     * Latest indexer-known chain block timestamp in milliseconds.
     *
     * Used by SDK-internal call sites that need a chain-anchored `ctime` rather
     * than wall-clock — see the Error 170 fix in [tryBalance] (commit 868e0d9)
     * for why this matters. The block timestamp is fetched fresh on every call;
     * it's already cached one layer down by [IndexerClient].
     */
    internal suspend fun indexerBlockTimestampMs(): Long =
        indexerClient.getCurrentBlockWithParams().timestamp

    /**
     * Sync dust state from the blockchain.
     *
     * @param onProgress Optional callback with (eventsProcessed, totalEvents) for progress UX.
     */
    suspend fun syncDust(
        onProgress: (suspend (eventsProcessed: Int, totalEvents: Int) -> Unit)? = null,
    ) {
        dustSyncManager.ensureSynced(onSyncProgress = onProgress)
    }

    override suspend fun balanceTransaction(provenTxHex: String): String = balanceMutex.withLock {
        doBalance(provenTxHex)
    }

    override suspend fun submitTransaction(balancedTxHex: String) {
        nodeRpcClient.submitAndWaitForFinalization(balancedTxHex)
        dustSyncManager.invalidateMemo()
    }

    override suspend fun balanceAndSubmit(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)?,
    ): Unit = balanceMutex.withLock {
        onProgress?.invoke(BalanceProgress.SyncingDust)

        // Error-170 recovery escalates: a fast delta re-sync first (the common case —
        // the dust state is just behind the tip), then a full genesis rebuild only if
        // that still fails (a genuinely corrupt checkpoint). The initial attempt has
        // no recovery prefix; each subsequent attempt runs the next, harder strategy.
        val recoveries: List<Pair<String, suspend () -> Unit>> = listOf(
            "delta re-sync" to { dustSyncManager.refreshIncremental(); Unit },
            "genesis rebuild" to { forceFullSync() },
        )
        var attempt = 0
        while (true) {
            try {
                val balanced = doBalance(provenTxHex, onProgress)
                onProgress?.invoke(BalanceProgress.Submitting)
                nodeRpcClient.submitAndWaitForFinalization(balanced) { stage ->
                    if (stage == SubmissionStage.IN_BLOCK) {
                        onProgress?.invoke(BalanceProgress.WaitingFinalization)
                    }
                }
                dustSyncManager.invalidateMemo()
                return@withLock
            } catch (e: NodeRpcError) {
                if (!isDustSpendProofError(e) || attempt >= recoveries.size) throw e
                val (label, recover) = recoveries[attempt++]
                Log.w(TAG, "Error 170 — $label and retry ($attempt/${recoveries.size})")
                onProgress?.invoke(BalanceProgress.RetryingDustSync)
                recover()
            }
        }
    }

    private suspend fun doBalance(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ): String {
        return tryBalance(provenTxHex, onProgress)
            ?: run {
                Log.w(TAG, "Balance failed, forcing full dust re-sync")
                onProgress?.invoke(BalanceProgress.RetryingDustSync)
                forceFullSync()
                tryBalance(provenTxHex, onProgress)
                    ?: throw IllegalStateException("Balance failed after full re-sync, check logcat")
            }
    }

    /**
     * Return a dust state fresh enough to balance against the chain tip.
     *
     * Error 170 (InvalidDustSpendProof) happens iff there are dust events between
     * our checkpoint and the block the node verifies the spend against (the tip):
     * the locally-replayed root then lags the tip's root. So probe whether the
     * indexer has dust events beyond our checkpoint; if it does, delta-refresh
     * first (fast — the events exist, so no first-event wait). If not, the memoized
     * state's dust root is already current (later blocks with no dust events don't
     * change it), so balance against the cached state.
     *
     * The probe uses a short timeout and degrades to the cached state on failure —
     * a wrong "not behind" only costs one 170 + the [balanceAndSubmit] retry.
     */
    private suspend fun ensureDustFresh(
        onProgress: (suspend (BalanceProgress) -> Unit)?,
    ): DustLocalState {
        val progress: suspend (Int, Int) -> Unit = { processed, total ->
            onProgress?.invoke(BalanceProgress.SyncingDustProgress(processed, total))
        }
        val checkpoint = dustRepository.getLastAppliedEventId(walletAddress)
        val behind = checkpoint != null && runCatching {
            indexerClient.queryDustEventsDelta(
                fromId = checkpoint + 1,
                timeoutMs = DUST_FRESHNESS_PROBE_MS,
            ).eventCount > 0
        }.getOrElse {
            Log.w(TAG, "dust freshness probe failed; using cached state: ${it.message}")
            false
        }
        return if (behind) {
            Log.i(TAG, "dust state behind tip — delta refresh before balancing")
            dustSyncManager.refreshIncremental(progress)
        } else {
            dustSyncManager.ensureSynced(progress)
        }
    }

    private suspend fun tryBalance(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ): String? {
        val dustState = ensureDustFresh(onProgress)

        val blockInfo = indexerClient.getCurrentBlockWithParams()
        val ledgerParamsHex = blockInfo.ledgerParameters
            ?: throw IllegalStateException("Indexer returned no ledger parameters")

        onProgress?.invoke(BalanceProgress.ProvingDust)

        // Use the indexer's view of the latest chain block timestamp, NOT wall-clock.
        // Reason: the node validates a dust spend by looking up
        // `dust.utxo.root_history.get(ctime)` (predecessor lookup keyed by block
        // timestamps). If we send wall-clock ahead of the latest indexed block, the
        // chain returns the tip root which won't match our locally-replayed root,
        // and rejects with `MalformedError::InvalidDustSpendProof` (Custom error 170).
        // The TS wallet does the same: see midnight-wallet/.../RunningV1Variant.ts
        // (`currentTime ?? blockData.timestamp`).
        return TransactionBalancerNative.nativeBalanceProvenTransaction(
            provenTxHex = provenTxHex,
            dustStatePtr = dustState.getStatePointer(),
            seed = dustSeed,
            ledgerParamsHex = ledgerParamsHex,
            currentTimeMs = blockInfo.timestamp,
            keysDir = provingKeysDir,
            networkId = networkId,
        )
    }

    private suspend fun forceFullSync() {
        dustSyncManager.forceResync()
        dustSyncManager.ensureSynced()
    }

    /**
     * Force a fresh resync of both shielded and dust state.
     *
     * Unshielded NIGHT is already kept live by the subscription started at
     * SDK build time, so it's not re-pulled here. Shielded NIGHT and DUST
     * are local-replay-driven and can lag if the SDK's subscriptions had a
     * transient hiccup; calling [refresh] catches them up immediately.
     *
     * Use cases:
     *  - Between sequential transactions (avoid stale UTXO state for fees).
     *  - When the UI shows a "refresh" affordance and the user wants
     *    on-demand freshness rather than waiting on the natural cadence.
     *  - After [waitForFunding] returns, if you also need shielded NIGHT
     *    fresh (waitForFunding only refreshes dust).
     *
     * Errors in shielded resync don't abort the dust resync (and vice versa) —
     * partial freshness is better than no freshness.
     */
    suspend fun refresh() {
        try {
            shieldedTracker.resync()
        } catch (e: Exception) {
            Log.w(TAG, "Shielded resync failed during refresh(): ${e.message}")
        }
        try {
            // Routine refresh = incremental delta on the persisted checkpoint,
            // NOT a genesis wipe. forceFullSync (forceResync) is only for
            // error-170 recovery where stale roots demand a clean rebuild.
            dustSyncManager.refreshIncremental()
        } catch (e: Exception) {
            Log.w(TAG, "Dust resync failed during refresh(): ${e.message}")
        }
        // Best-effort cloud backup of the freshly-synced checkpoint (no-op when
        // unconfigured; the coordinator's hash guard skips an unchanged blob).
        // Never let a backup failure surface to the caller.
        try {
            backupDustToCloud()
        } catch (e: Exception) {
            Log.w(TAG, "Dust cloud backup failed during refresh(): ${e.message}")
        }
    }

    /**
     * Snapshot the current dust checkpoint and hand it to the cloud backup
     * coordinator (e.g. Google Drive) for cross-device recovery. No-op when no
     * coordinator is wired or there's no checkpoint yet. The coordinator
     * hash-guards redundant uploads, so this is cheap to call after every sync.
     */
    suspend fun backupDustToCloud() {
        val backup = dustCloudBackup ?: return
        val state = dustRepository.loadState(walletAddress) ?: return
        val bytes = try {
            state.serialize()
        } finally {
            state.close()
        } ?: return
        val lastEventId = dustRepository.getLastAppliedEventId(walletAddress) ?: return
        backup.upload(walletAddress, bytes, lastEventId)
    }

    private fun isDustSpendProofError(e: NodeRpcError): Boolean {
        val data = e.data ?: return false
        return data.contains("Custom error: 170")
    }

    fun close() {
        dustSyncManager.close()
        nodeRpcClient.close()
    }

    companion object {
        private const val TAG = "MidnightWallet"

        /**
         * Timeout for the pre-balance "are there dust events beyond my checkpoint?"
         * probe. Short on purpose: a behind state returns the first event well within
         * this; a caught-up state just waits it out and balances against the cache.
         */
        private const val DUST_FRESHNESS_PROBE_MS = 1_000L

        /**
         * Default cap for [waitForFunding]. 5 minutes is the same envelope used
         * by [com.midnight.kicks.MatchManager.DEFAULT_OPPONENT_WAIT_MS] — long
         * enough for a faucet / manual transfer to clear, short enough that a
         * caller doesn't hang forever.
         */
        private const val DEFAULT_FUNDING_TIMEOUT_MS = 5L * 60L * 1_000L
    }
}

/**
 * Snapshot of a wallet's full balance state across both pools.
 *
 * **Pool semantics:**
 *  - Unshielded NIGHT lives in UTXOs on the public ledger; this is what
 *    external transfers ("fund this address") land in.
 *  - Shielded NIGHT lives in Zswap coins on the private ledger; the wallet
 *    moves NIGHT here for transactions whose amounts/recipients shouldn't
 *    be public.
 *  - DUST is a separate fee token, generated by the chain at a rate tied
 *    to the wallet's registered NIGHT. Not held in either pool's
 *    coin/UTXO graph; tracked in the ledger's dust state.
 *
 * **Why DUST has no "shielded" half:** Midnight's protocol generates DUST
 * against a registered NIGHT key directly. It's never wrapped in a Zswap
 * coin and never appears as a shielded balance — by design, since DUST
 * is the *fee* substrate, not a transferrable shielded asset.
 *
 * @property unshieldedNight Sum of unspent NIGHT UTXOs at this wallet's
 *   Bech32m address. u128 base units.
 * @property shieldedNight Sum of shielded NIGHT coins decryptable by this
 *   wallet's zswap key. u128 base units. Zero until the SDK's shielded
 *   subscription has completed its first replay (a few hundred ms on
 *   localnet, a few seconds on PREPROD).
 * @property dust Wallet's locally-replayed DUST state — what the SDK would
 *   spend on the next fee. u128 base units.
 * @property dustRegistered Best-effort signal: true iff the wallet currently
 *   holds spendable dust. A fresh wallet that hasn't run
 *   [MidnightSdk.registerForDustGeneration] reports `false` even with NIGHT
 *   funded, because the chain doesn't release spendable dust to an
 *   unregistered key.
 */
data class WalletBalance(
    val unshieldedNight: BigInteger,
    val shieldedNight: BigInteger,
    val dust: BigInteger,
    val dustRegistered: Boolean,
) {
    /** Sum of both NIGHT pools — what the wallet "has" in NIGHT terms. */
    val totalNight: BigInteger get() = unshieldedNight + shieldedNight

    /** True iff any shielded NIGHT is present. UIs use this to gate the privacy badge. */
    val hasShielded: Boolean get() = shieldedNight > BigInteger.ZERO
}
