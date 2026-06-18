package com.midnight.kuira.sdk.walletruntime

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.midnight.kuira.sdk.BackgroundReceiveChecker
import com.midnight.kuira.sdk.formatNight
import dagger.hilt.android.EntryPointAccessors

/**
 * Doze-aware background poll for incoming UNSHIELDED NIGHT (#271). Scheduled by
 * [WalletForegroundService.attach] — periodically (~15 min) and as an expedited one-shot
 * when the app backgrounds — so a receipt is noticed even while the app is backgrounded /
 * the device is locked, when the live observer isn't running.
 *
 * Flow: read the poll target ([ReceiveTargetStore]) → seed-free one-shot catch-up
 * ([BackgroundReceiveChecker.newReceipts]) that returns per-transaction inbound NIGHT receipts
 * classified by UTXO-set provenance (#284 — our own change is never a receipt) → announce each
 * one not yet seen ([ReceiveCheckpointStore] tx-id cursor, shared with the live observer).
 * Skipped when the wallet UI is foreground (the live observer covers that, and there's no point
 * summoning someone who's already here).
 *
 * Shielded receipts are out of scope here — they need the seed-derived viewing key (#280).
 */
class ReceivePollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val target = ReceiveTargetStore(ctx).target() ?: return Result.success() // no wallet yet

        val sessionLock = EntryPointAccessors
            .fromApplication(ctx, SessionLock.SessionLockEntryPoint::class.java)
            .sessionLock()
        // Foreground → the live in-app observer already covers receipts; skip the poll.
        if (sessionLock.inForeground.value) return Result.success()

        val receipts = BackgroundReceiveChecker(ctx)
            .newReceipts(target.network, target.address)
            ?: return Result.retry() // network error — WorkManager re-runs with backoff

        val checkpoint = ReceiveCheckpointStore(ctx)
        val alerter = AlertNotifier(ctx)
        // Announce in id order so the persisted cursor only ever advances. Each receipt is a
        // genuine inbound NIGHT transaction (our own change is filtered out upstream by UTXO-set
        // provenance) and carries the EXACT amount received in that transaction — not a delta.
        for (receipt in receipts.sortedBy { it.transactionId }) {
            // The user may have opened the app during the (seconds-long) catch-up sync — once
            // they're here the live observer owns receipts, so stop and leave the rest to it
            // (don't advance the cursor, or those would be dropped silently).
            if (sessionLock.inForeground.value) break
            val stored = checkpoint.lastAnnouncedTxId(target.network, target.address)
            if (!ReceiveCheckpointStore.shouldAnnounce(stored, receipt.transactionId)) continue
            runCatching {
                alerter.postReceived(
                    title = ctx.getString(R.string.kuira_alert_received_fmt, formatNight(receipt.amount)),
                    body = ctx.getString(R.string.kuira_alert_received_body),
                )
            }.onFailure { Log.w(TAG, "received alert post failed: ${it.message}") }
            checkpoint.recordAnnouncedTxId(target.network, target.address, receipt.transactionId)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ReceivePoll"

        /** Unique name for the periodic poll (one scheduled instance, kept across launches). */
        const val PERIODIC_NAME = "kuira-receive-poll-periodic"

        /** Unique name for the expedited on-background one-shot. */
        const val ONESHOT_NAME = "kuira-receive-poll-oneshot"
    }
}
