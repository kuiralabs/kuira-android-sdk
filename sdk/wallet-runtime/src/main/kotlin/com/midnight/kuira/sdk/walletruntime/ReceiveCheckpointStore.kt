package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * Idempotency cursor for inbound-receipt notifications: the highest receipt transaction
 * id already announced per (network, address). Two paths can see the same receipt — the live
 * in-app observer ([WalletForegroundService]) and the background poll ([ReceivePollWorker]) —
 * and both run across process deaths; this persisted id makes a receipt announce at most once.
 * Whichever path announces first records the id; the other then sees it as already handled.
 *
 * Provenance (is this a genuine receipt, and how much?) is decided upstream in the indexer's
 * SubscriptionManager by diffing the transaction's UTXO set — so a user's own change is never a
 * receipt and never reaches here. This store is therefore a pure dedup cursor; it no longer
 * reasons about balances. (That balance high-water was what the old delta detector used, and
 * what occasionally announced a spend's change as a "receipt".)
 *
 * Plain prefs: a transaction id is public chain metadata, not a secret. A dedicated prefs file
 * ([PREFS]) — the old balance-watermark store wrote String values under a different file, so
 * there's no type collision on upgrade.
 */
class ReceiveCheckpointStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Highest receipt tx id announced for [network] + [address], or null if none yet. */
    fun lastAnnouncedTxId(network: MidnightNetwork, address: String): Int? {
        val key = key(network, address)
        return if (prefs.contains(key)) prefs.getInt(key, 0) else null
    }

    /** Record [txId] as the highest receipt announced for [network] + [address]. */
    fun recordAnnouncedTxId(network: MidnightNetwork, address: String, txId: Int) {
        prefs.edit().putInt(key(network, address), txId).apply()
    }

    private fun key(network: MidnightNetwork, address: String) = "${network.name}:$address"

    companion object {
        private const val PREFS = "kuira_receive_cursor"

        /**
         * Whether a receipt at [txId] is not-yet-announced for this (network, address).
         *
         * - `lastAnnouncedTxId == null` (nothing announced yet) → announce. The upstream
         *  baseline already suppressed pre-existing history, so the first event that reaches
         *  here is a genuine new receipt.
         * - `txId > lastAnnouncedTxId` → a newer receipt → announce.
         * - otherwise → already announced (a replay or the other path beat us) → skip.
         */
        fun shouldAnnounce(lastAnnouncedTxId: Int?, txId: Int): Boolean =
            lastAnnouncedTxId == null || txId > lastAnnouncedTxId
    }
}
