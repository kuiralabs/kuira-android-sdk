package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import com.midnight.kuira.core.network.MidnightNetwork
import java.math.BigInteger

/**
 * Persists the highest unshielded NIGHT balance seen per (network, address), so the
 * background receive poll ([ReceivePollWorker]) — which is short-lived and re-runs
 * across process deaths — can tell a genuine NEW receipt from a balance it has already
 * accounted for.
 *
 * "Highest seen", not "last seen": the balance dips on a spend (and on a flaky resync
 * transient), and a dip-then-recover must NOT re-fire as a receipt — only a balance that
 * EXCEEDS the recorded high is a new receipt. Both the in-app observer and the background
 * worker diff against this same persisted high, so a receipt is announced once: whichever
 * path sees it first records the high, and the other then finds no delta.
 *
 * Plain prefs: the NIGHT total is public chain data (an unshielded balance), not a secret.
 */
class ReceiveCheckpointStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Highest NIGHT recorded for [network] + [address], or null if never recorded. */
    fun lastHigh(network: MidnightNetwork, address: String): BigInteger? =
        prefs.getString(key(network, address), null)?.let { runCatching { BigInteger(it) }.getOrNull() }

    /** Record [value] as the new high for [network] + [address]. */
    fun recordHigh(network: MidnightNetwork, address: String, value: BigInteger) {
        prefs.edit().putString(key(network, address), value.toString()).apply()
    }

    private fun key(network: MidnightNetwork, address: String) = "${network.name}:$address"

    companion object {
        private const val PREFS = "kuira_receive_checkpoint"

        /**
         * The positive amount to notify as "received", or null for no new receipt.
         *
         * - `storedHigh == null` (first observation — a freshly set-up wallet, or the
         *   cold-start `0 → balance` ramp): the BASELINE. Notify nothing (don't announce
         *   the pre-existing balance as a receipt); the caller records it as the high.
         * - `current > storedHigh`: a genuine receipt — notify the difference.
         * - `current <= storedHigh`: a spend or a flaky-resync dip — no receipt; the
         *   recorded high is retained so the recovery back up isn't re-counted.
         */
        fun receivedDelta(storedHigh: BigInteger?, current: BigInteger): BigInteger? = when {
            storedHigh == null -> null
            current > storedHigh -> current - storedHigh
            else -> null
        }

        /** Whether [current] should be written as the new high (baseline or a fresh increase). */
        fun shouldRecord(storedHigh: BigInteger?, current: BigInteger): Boolean =
            storedHigh == null || current > storedHigh
    }
}
