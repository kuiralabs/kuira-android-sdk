package com.midnight.kuira.sdk

import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tip-aware dust sync manager.
 *
 * Implements the cache pattern from wallet-cli WalletDataRepository:
 * - Memoizes chain tip hash (5s TTL)
 * - Serves cached DustLocalState instantly if tip hasn't changed
 * - Delta syncs only new events when tip changes
 * - Keeps disk checkpoint across submit (no destructive delete)
 *
 * This is the key optimization that makes the SDK scale to PREPROD/PREVIEW.
 * Without it, every transaction after the first would take 3-10s to re-sync
 * 250k+ dust events from genesis. With it, same-block transactions are
 * instant and cross-block transactions take ~200ms (tip check + delta).
 *
 * @param clock Injectable time source for testing
 */
class DustSyncManager(
    private val dustRepository: DustRepository,
    private val nodeRpcClient: NodeRpcClient,
    private val walletAddress: String,
    private val dustSeed: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    // In-memory memo layer
    private var cachedState: DustLocalState? = null
    private var tipAtSync: String? = null
    private var memoizedTip: String? = null
    private var tipFetchedAt: Long = 0L

    /**
     * Ensure dust state is synced and return a usable DustLocalState.
     *
     * Fast path (same block, within TTL): returns cached state, zero network calls.
     * Medium path (TTL expired, same tip): one RPC call (~50ms), returns cached state.
     * Slow path (tip changed): one RPC call + delta sync (~200ms for a few new events).
     * Cold path (first call): full sync from genesis (3-10s, same as before).
     */
    suspend fun ensureSynced(): DustLocalState = mutex.withLock {
        val currentTip = fetchTip()

        // Fast path: cached state exists and tip hasn't changed
        val cached = cachedState
        if (cached != null && currentTip == tipAtSync) {
            return@withLock cached
        }

        // Tip changed or no cache: delta sync (or full sync on cold start)
        dustRepository.syncFromBlockchain(walletAddress, dustSeed)

        val freshState = dustRepository.loadState(walletAddress)
            ?: throw IllegalStateException("No dust state after sync. Is dust registered?")

        // Close previous cached state to free native memory
        cachedState?.close()

        cachedState = freshState
        tipAtSync = currentTip
        freshState
    }

    /**
     * Invalidate the in-memory memo after a successful transaction submit.
     *
     * Does NOT delete the disk checkpoint. The next [ensureSynced] call will
     * re-check the tip (which will have changed due to our new block) and
     * delta-sync only the new events since the checkpoint.
     */
    suspend fun invalidateMemo() = mutex.withLock {
        // Don't close the cached state here: MidnightWallet may still hold a
        // reference from the balanceTransaction call. It will be replaced on
        // the next ensureSynced() call.
        cachedState = null
        tipAtSync = null
        memoizedTip = null
        tipFetchedAt = 0L
    }

    /** Release native resources. */
    fun close() {
        cachedState?.close()
        cachedState = null
    }

    /**
     * Fetch the current finalized tip hash, memoized for [TIP_MEMO_TTL_MS].
     *
     * On network error: returns the last known tip (availability over consistency).
     * This means a brief network glitch won't block a transaction that has valid
     * cached state.
     */
    private suspend fun fetchTip(): String {
        val now = clock()
        val memo = memoizedTip
        if (memo != null && (now - tipFetchedAt) < TIP_MEMO_TTL_MS) {
            return memo
        }

        return try {
            val tip = nodeRpcClient.getFinalizedHead()
            memoizedTip = tip
            tipFetchedAt = now
            tip
        } catch (e: Exception) {
            // Network error: serve stale tip if available (availability over consistency).
            // If no stale tip, rethrow so the caller knows something is wrong.
            memo ?: throw e
        }
    }

    companion object {
        /** TTL for memoized tip hash (5 seconds, matching wallet-cli). */
        const val TIP_MEMO_TTL_MS = 5_000L
    }
}
