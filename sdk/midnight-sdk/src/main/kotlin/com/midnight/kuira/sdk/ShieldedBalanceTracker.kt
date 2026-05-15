package com.midnight.kuira.sdk

import android.util.Log
import com.midnight.kuira.core.indexer.repository.ShieldedRepository
import com.midnight.kuira.core.ledger.model.UtxoSpend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigInteger
import java.util.Arrays

/**
 * Internal shielded-NIGHT bookkeeper for [MidnightSdk].
 *
 * The SDK owns the full lifecycle here so consumer dApps never see raw
 * `tokenHex → BigInteger` maps or have to think about "did the user tap
 * sync?" — see `feedback_sdk_devx_principle` for the rationale.
 *
 * **What this does:**
 *  1. On [start], does an initial full replay of zswap events against the
 *     wallet's zswap key. Updates the cached NIGHT balance.
 *  2. Then subscribes to `zswapLedgerEvents` and re-runs the replay each
 *     time the chain tip advances — the same tip-aware caching strategy
 *     `BalanceViewModel` uses, kept local to the SDK so the consumer
 *     doesn't have to wire it.
 *
 * **Why we replay instead of accumulating events:** the zswap state is an
 * opaque FFI object (`ZswapLocalState`); we can't peek inside it to apply
 * deltas, and the FFI's `replayEvents` rebuilds the state from scratch
 * each call. Cheap enough on localnet, slower on PREPROD — that's exactly
 * why we only resync on chain-tip advance, not every event.
 *
 * **What the consumer sees:** [currentNight] returns the latest cached
 * value (`BigInteger.ZERO` until the first sync lands). [resync]
 * force-refreshes; the SDK exposes this through `MidnightWallet.refresh()`.
 *
 * **Lifecycle:** Started by `MidnightSdk.Builder.build()`, closed by
 * `MidnightSdk.close()`. The held [zswapSeed] is wiped on close.
 *
 * Only tracks NIGHT (token hex `0000…0000`). Custom-token support can be
 * added by exposing the full `Map<symbol, BigInteger>` later — the
 * underlying replay already produces it.
 */
internal class ShieldedBalanceTracker(
    private val shieldedRepository: ShieldedRepository,
    private val walletAddress: String,
    private val zswapSeed: ByteArray,
) {
    private val mutex = Mutex()

    @Volatile
    private var cachedNight: BigInteger = BigInteger.ZERO

    private var subscriptionJob: Job? = null

    /** Latest cached shielded NIGHT. Zero until the first [resync] completes. */
    fun currentNight(): BigInteger = cachedNight

    /**
     * Full re-sync from chain. Replays all zswap events, recomputes NIGHT.
     *
     * Serialized via [mutex] so concurrent callers (e.g. SDK's subscription
     * loop racing with `wallet.refresh()`) don't double-replay or corrupt
     * the persisted state.
     */
    suspend fun resync() = mutex.withLock {
        val hasCoins = shieldedRepository.syncFromBlockchain(walletAddress, zswapSeed)
        cachedNight = if (hasCoins) {
            val balances = shieldedRepository.getBalances(walletAddress)
            balances[UtxoSpend.NATIVE_TOKEN_TYPE] ?: BigInteger.ZERO
        } else {
            BigInteger.ZERO
        }
        Log.d(TAG, "Shielded NIGHT resync: $cachedNight")
    }

    /**
     * Launch the background sync loop. Returns immediately; the initial
     * replay happens off the caller's thread.
     */
    fun start(scope: CoroutineScope) {
        subscriptionJob = scope.launch(Dispatchers.IO) {
            // Initial sync — best-effort. A transient indexer error here means
            // currentNight stays at 0 until the subscription loop picks it up.
            try {
                resync()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Initial shielded sync failed (will retry via subscription): ${e.message}")
            }

            // Tip-aware subscription. Re-sync when the chain advances past our
            // last observed event id; the indexer's `maxId` signals how far
            // back the visible event log goes, so `event.id >= maxId` means
            // we're caught up to the tip and the next event is genuinely new.
            //
            // **Log noise control:**
            // We log a warning only on the *first* error of an outage streak,
            // and the next info-level "recovered" when the next collect()
            // succeeds. Without this the loop hammers logcat every few seconds
            // during a docker outage / adb-reverse drop — the underlying retry
            // is correct behavior, the noise isn't.
            //
            // **Backoff:**
            // 3s → 6s → 12s → 30s cap. Aggressive enough to recover quickly
            // once connectivity returns, sparse enough not to flood the
            // network stack during a long outage.
            var lastMaxId = 0L
            var consecutiveErrors = 0
            var backoffMs = INITIAL_RECONNECT_DELAY_MS
            while (isActive) {
                try {
                    shieldedRepository.subscribeToZswapEvents(fromId = lastMaxId)
                        .collect { event ->
                            if (consecutiveErrors > 0) {
                                Log.i(TAG, "Shielded subscription recovered after $consecutiveErrors failure(s)")
                                consecutiveErrors = 0
                                backoffMs = INITIAL_RECONNECT_DELAY_MS
                            }
                            val previousMax = lastMaxId
                            lastMaxId = maxOf(lastMaxId, event.id)
                            if (event.id >= event.maxId && event.maxId > previousMax - 1) {
                                resync()
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors == 1) {
                        Log.w(TAG, "Shielded subscription error, retrying with backoff: ${e.message}")
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    /** Cancel the subscription loop and wipe the held zswap seed. */
    fun close() {
        subscriptionJob?.cancel()
        subscriptionJob = null
        Arrays.fill(zswapSeed, 0.toByte())
    }

    private companion object {
        const val TAG = "ShieldedTracker"

        /** First backoff after a failed subscription — short so transient blips recover fast. */
        const val INITIAL_RECONNECT_DELAY_MS = 3_000L

        /** Cap on the exponential backoff. Stops the loop from hammering during a long outage. */
        const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
