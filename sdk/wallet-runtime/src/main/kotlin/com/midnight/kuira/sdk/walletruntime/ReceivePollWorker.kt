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
 * the device is locked, when the live balance observer isn't running.
 *
 * Flow: read the poll target ([ReceiveTargetStore]) → seed-free one-shot balance check
 * ([BackgroundReceiveChecker]) → diff against the checkpoint ([ReceiveCheckpointStore]) → if
 * the balance rose while the user was away, post the "received" alert. Suppressed when the
 * wallet UI is foreground (the live observer covers that, and there's no point summoning
 * someone who's already here).
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

        val current = BackgroundReceiveChecker(ctx)
            .currentUnshieldedNight(target.network, target.address)
            ?: return Result.retry() // network error — WorkManager re-runs with backoff

        val checkpoint = ReceiveCheckpointStore(ctx)
        val stored = checkpoint.lastHigh(target.network, target.address)
        ReceiveCheckpointStore.receivedDelta(stored, current)?.let { delta ->
            // Re-check foreground right before alerting — the user may have opened the app
            // during the (seconds-long) catch-up sync.
            if (!sessionLock.inForeground.value) {
                runCatching {
                    AlertNotifier(ctx).postReceived(
                        title = ctx.getString(R.string.kuira_alert_received_fmt, formatNight(delta)),
                        body = ctx.getString(R.string.kuira_alert_received_body),
                    )
                }.onFailure { Log.w(TAG, "received alert post failed: ${it.message}") }
            }
        }
        if (ReceiveCheckpointStore.shouldRecord(stored, current)) {
            checkpoint.recordHigh(target.network, target.address, current)
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
