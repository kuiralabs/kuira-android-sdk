package com.midnight.kuira.sdk

import android.util.Log
import com.midnight.kuira.core.indexer.api.IndexerClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Proactive dust bookkeeper (#235) — the dust analog of [ShieldedBalanceTracker].
 *
 * Keeps dust state current **ahead of need** so a contract transaction never
 * waits on a cold dust sync. On [start]: one initial sync, then subscribe to
 * dust events and re-sync (delta only) whenever the chain tip advances — the
 * same tip-aware strategy the shielded tracker uses, kept inside the SDK so
 * dApps don't wire it.
 *
 * **Delegates the actual sync to the wallet** ([onTipAdvance] =
 * `MidnightWallet::proactiveDustResync`) so it runs under the wallet's
 * `balanceMutex` (never closing the shared `DustLocalState` mid-balance) and
 * publishes to `MidnightWallet.syncStatus`. This tracker only owns the
 * *when* (the live subscription + tip detection + backoff), not the *how*.
 *
 * **Lifecycle:** started by `MidnightSdk.Builder.build()` when
 * `proactiveDustSync(true)` is set; cancelled by `MidnightSdk.close()` (which
 * cancels the shared `subscriptionScope`). Opt-in, so existing hosts are
 * unchanged.
 */
internal class DustBalanceTracker(
    private val indexerClient: IndexerClient,
    private val onTipAdvance: suspend () -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job = scope.launch(dispatcher) {
            // Initial proactive sync — best-effort; the subscription loop below
            // recovers if this transient-fails.
            try {
                onTipAdvance()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Initial proactive dust sync failed (will retry via subscription): ${e.message}")
            }

            // Tip-aware subscription. `event.id >= event.maxId` means we've caught
            // up to the indexer's visible tip and the next event is genuinely new.
            // Log only the first error of an outage streak (the retry is correct;
            // the per-reconnect noise isn't), with 3s→30s exponential backoff.
            var lastMaxId = 0L
            var consecutiveErrors = 0
            var backoffMs = INITIAL_RECONNECT_DELAY_MS
            while (isActive) {
                try {
                    indexerClient.subscribeToDustEvents(fromId = lastMaxId)
                        .collect { event ->
                            if (consecutiveErrors > 0) {
                                Log.i(TAG, "Dust subscription recovered after $consecutiveErrors failure(s)")
                                consecutiveErrors = 0
                                backoffMs = INITIAL_RECONNECT_DELAY_MS
                            }
                            val previousMax = lastMaxId
                            lastMaxId = maxOf(lastMaxId, event.id)
                            // Resync ONLY on a STRICT tip advance. A `>= previousMax`
                            // test (the old `> previousMax - 1`) re-fired on every
                            // caught-up event at the SAME tip — and on each 30s
                            // subscription reconnect re-delivering that tip — so a
                            // quiet/undeployed chain looped proactiveDustResync
                            // forever, pinning the sync notification on. The tip must
                            // genuinely move past what we last synced.
                            if (event.id >= event.maxId && event.maxId > previousMax) {
                                onTipAdvance()
                            }
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveErrors++
                    if (consecutiveErrors == 1) {
                        Log.w(TAG, "Dust subscription error, retrying with backoff: ${e.message}")
                    }
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                }
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "DustTracker"
        const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
