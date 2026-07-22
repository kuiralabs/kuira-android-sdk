package com.midnight.kuira.sdk

import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Restartable handle for the long-lived unshielded-tx subscription.
 *
 * The subscription is launched fire-and-forget at SDK build time and normally
 * never needs touching — it resumes incrementally from the saved cursor and the
 * indexer drives the NIGHT balance automatically. But there's no way to *restart*
 * a bare `scope.launch { … }`, and one recovery path needs exactly that: a
 * user-triggered force re-sync that rebuilds the unshielded UTXO cache from
 * genesis ([SubscriptionManager.startSubscription] with `forceFullResync = true`).
 *
 * This controller owns that single Job so it can be cancelled and re-launched on
 * demand. [start] is the one entry point — the initial fire-and-forget launch and
 * the force re-sync both go through it, so there's never a second collector racing
 * the first on the same address.
 *
 * **Why force re-sync is needed (roadmap ).** The unshielded UTXO cache is
 * event-driven (created / spent deltas), with no reconcile against the indexer's
 * current set. A *missed* spent-event — a sibling app on the same shared wallet
 * spent a coin while this app was backgrounded, or a subscription gap — leaves a
 * ghost AVAILABLE UTXO forever, so the balance over-counts (e.g. shows 20k NIGHT
 * when the real spendable is 10k). Replaying from genesis rebuilds the set from
 * the indexer's truth, dropping the ghosts.
 *
 * **Scope:** UTXO-cache only. [SubscriptionManager.performFullResync] clears the
 * sync cursor + the unshielded UTXOs and nothing else — the dust checkpoint and
 * the shielded state are separate replay models and are deliberately left intact
 * (a dust wipe would force a heavy genesis dust replay, fatal on PreProd).
 */
internal class UnshieldedSubscription(
    private val subscriptionManager: SubscriptionManager,
    private val scope: CoroutineScope,
    private val address: String,
) {
    private var job: Job? = null

    /**
     * (Re)launch the unshielded subscription on the controller's [scope].
     *
     * Cancels the prior collecting Job first so only one runs per address, then
     * starts a fresh collect. The state is observed inside [SubscriptionManager]
     * (it feeds the UtxoManager); the emitted [com.midnight.kuira.core.indexer.sync.SyncState]
     * is intentionally not consumed here.
     *
     * @param forceFullResync true → wipe the sync cursor + unshielded UTXOs for
     *  [address] ONCE before subscribing, forcing a genesis replay. The cache
     *  rebuilds from the indexer, so the balance momentarily drops toward 0 then
     *  climbs back as events replay. UTXO-cache only — dust and shielded state are
     *  untouched. Default false (the normal incremental resume).
     */
    fun start(forceFullResync: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            subscriptionManager.startSubscription(address, forceFullResync = forceFullResync)
                .collect { /* state observed inside SubscriptionManager */ }
        }
    }
}
