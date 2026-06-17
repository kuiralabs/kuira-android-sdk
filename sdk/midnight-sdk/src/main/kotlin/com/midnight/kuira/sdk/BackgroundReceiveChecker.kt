package com.midnight.kuira.sdk

import android.content.Context
import android.util.Log
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.model.TokenTypeMapper
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import com.midnight.kuira.core.indexer.sync.SyncState
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.math.BigInteger

/**
 * One-shot, SEED-FREE read of the current UNSHIELDED NIGHT balance for an address on a
 * network — what the background receive poll does while the full [MidnightSdk] may be
 * dropped (session locked / process backgrounded).
 *
 * It reuses the exact indexer pipeline the live SDK runs (UtxoManager ← SubscriptionManager
 * ← IndexerClient; balance via BalanceRepository — see [MidnightSdk]'s builder), but as a
 * bounded one-shot: start the unshielded-tx subscription for the PUBLIC [address] (no seed
 * — unshielded UTXOs are owned by the address, not a private key), wait until it reports
 * caught up to tip ([SyncState.Synced]) or a budget elapses, read the NIGHT total, then
 * tear the pipeline down.
 *
 * Shielded balance is deliberately NOT read here — it needs the seed-derived viewing key
 * (tracked as the watch-only carve-out, #280); this poll covers public NIGHT receipts only.
 *
 * Lives in midnight-sdk (which owns the indexer pipeline); wallet-runtime's worker calls it.
 * Lightweight — no DI, no live SDK instance.
 */
class BackgroundReceiveChecker(private val appContext: Context) {

    /**
     * Current unshielded NIGHT for [address] on [network], or null if the check couldn't
     * complete (network error). A non-null result is authoritative for "what the chain
     * shows now"; the caller diffs it against its checkpoint to detect a receipt.
     */
    suspend fun currentUnshieldedNight(network: MidnightNetwork, address: String): BigInteger? {
        val config = NetworkConfig.forNetwork(network)
        // Match how MidnightSdk builds it: localnet uses HTTP, which the default
        // (production) constructor rejects — pass the network's developmentMode flag.
        val indexerClient = IndexerClientImpl(
            baseUrl = config.indexerBaseUrl,
            developmentMode = config.developmentMode,
        )
        val utxoManager = UtxoManager(UtxoDatabase.getInstance(appContext).unshieldedUtxoDao())
        val subscriptionManager = SubscriptionManager(
            context = appContext,
            indexerClient = indexerClient,
            utxoManager = utxoManager,
            syncStateManager = SyncStateManager(appContext),
        )
        return try {
            // Catch up to tip (bounded). startSubscription feeds UtxoManager and emits
            // Synced once it's up to the highest available tx; first { Synced } takes that
            // and cancels the (otherwise long-lived) subscription — the one-shot.
            val caughtUp = withTimeoutOrNull(SYNC_BUDGET_MS) {
                subscriptionManager.startSubscription(address).first { it is SyncState.Synced }
                true
            } ?: false
            if (!caughtUp) {
                Log.w(TAG, "unshielded catch-up didn't finish in ${SYNC_BUDGET_MS}ms — reading current local state")
            }
            // Read NIGHT from the now-updated local UTXO set.
            BalanceRepository(utxoManager, indexerClient)
                .observeBalances(address)
                .first()
                .firstOrNull { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
                ?.balance
                ?: BigInteger.ZERO
        } catch (e: Exception) {
            Log.w(TAG, "background unshielded NIGHT check failed: ${e.message}")
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
         * before we read whatever's synced so far.
         */
        private const val SYNC_BUDGET_MS = 25_000L
    }
}
