package com.midnight.kuira.sdk

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.model.ReceiptEvent
import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import com.midnight.kuira.core.indexer.sync.SyncState
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-shot, SEED-FREE detection of inbound NIGHT receipts for an address on a network — what
 * the background receive poll does while the full [MidnightSdk] may be dropped (session locked
 * / process backgrounded).
 *
 * It reuses the exact indexer pipeline the live SDK runs (UtxoManager ← SubscriptionManager ←
 * IndexerClient — see [MidnightSdk]'s builder), as a bounded one-shot: start the unshielded-tx
 * subscription for the PUBLIC [address] (no seed — unshielded UTXOs are owned by the address,
 * not a private key), collect the per-transaction [ReceiptEvent]s the subscription classifies
 * (#284) while it catches up to tip ([SyncState.Synced]) or a budget elapses, then tear the
 * pipeline down and return what it saw.
 *
 * Receipts are classified by UTXO-set provenance in [SubscriptionManager], so this NEVER
 * reports a user's own change as a receipt — the same guarantee the live observer has. The
 * shared persisted sync cursor means a receipt already accounted for by the live SDK isn't
 * re-delivered here. Shielded receipts are out of scope (they need the seed-derived viewing
 * key — the #280 carve-out); this covers public NIGHT only.
 *
 * Lives in midnight-sdk (which owns the indexer pipeline); wallet-runtime's worker calls it.
 * Lightweight — no DI, no live SDK instance.
 */
class BackgroundReceiveChecker(private val appContext: Context) {

    /**
     * Inbound NIGHT receipts seen for [address] on [network] during a bounded catch-up to tip,
     * or null if the check couldn't run (network error). An empty list means "caught up, nothing
     * new." The caller dedups across poll runs (a receipt already announced carries the same
     * transaction id) before notifying.
     */
    suspend fun newReceipts(network: MidnightNetwork, address: String): List<ReceiptEvent>? {
        val config = NetworkConfig.forNetwork(network)
        // Match how MidnightSdk builds it: localnet uses HTTP, which the default
        // (production) constructor rejects — pass the network's developmentMode flag.
        val indexerClient = IndexerClientImpl(
            baseUrl = config.indexerBaseUrl,
            developmentMode = config.developmentMode,
        )
        val database = UtxoDatabase.getInstance(appContext)
        val utxoManager = UtxoManager(
            utxoDao = database.unshieldedUtxoDao(),
            inTransaction = { block -> database.withTransaction { block() } },
        )
        val subscriptionManager = SubscriptionManager(
            context = appContext,
            indexerClient = indexerClient,
            utxoManager = utxoManager,
            syncStateManager = SyncStateManager(appContext),
        )
        return try {
            val received = mutableListOf<ReceiptEvent>()
            coroutineScope {
                // Subscribe to the receipts flow BEFORE starting the subscription. receipts is a
                // replay-0 SharedFlow, so an emission with no active collector is dropped — we
                // gate on subscriptionCount to guarantee we're attached before any tx is processed.
                val collector = launch { subscriptionManager.receipts.collect { received.add(it) } }
                subscriptionManager.receiptSubscriberCount.first { it > 0 }

                // Catch up to tip (bounded). startSubscription feeds UtxoManager and emits Synced
                // once it's up to the highest available tx; first { Synced } takes that and cancels
                // the (otherwise long-lived) subscription — the one-shot.
                val caughtUp = withTimeoutOrNull(SYNC_BUDGET_MS) {
                    subscriptionManager.startSubscription(address).first { it is SyncState.Synced }
                    true
                } ?: false
                if (!caughtUp) {
                    Log.w(TAG, "unshielded catch-up didn't finish in ${SYNC_BUDGET_MS}ms — returning receipts seen so far")
                }
                collector.cancel()
            }
            received.toList()
        } catch (e: Exception) {
            Log.w(TAG, "background receipt check failed: ${e.message}")
            null
        } finally {
            runCatching { indexerClient.close() }
        }
    }

    companion object {
        private const val TAG = "ReceiveCheck"

        /**
         * Catch-up budget. The subscription self-declares Synced after ~5s of quiet
         * (SubscriptionManager's auto-sync timeout); this gives headroom for a cold start
         * before we return whatever's synced so far.
         */
        private const val SYNC_BUDGET_MS = 25_000L
    }
}
