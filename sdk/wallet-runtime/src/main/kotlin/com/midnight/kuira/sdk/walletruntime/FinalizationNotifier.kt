package com.midnight.kuira.sdk.walletruntime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.midnight.kuira.sdk.OperationKind
import com.midnight.kuira.sdk.OperationOutcome

/**
 * Builds + posts the DISMISSIBLE finalization notification (#264): when a tracked
 * operation finishes (success / pending / failed), the user — who may have left the
 * app — gets a notification they clear THEMSELVES, so they know the transaction
 * landed. Distinct from [SyncNotifier]'s ONGOING progress notification: its own
 * channel, NOT ongoing, auto-cancels on tap, alerts once.
 *
 * Labels follow #259: the operation's caller-provided label (or the kind default via
 * [defaultLabelRes]) renders the title; the terminal status renders the body — the
 * SDK emits enums, the host strings.
 */
class FinalizationNotifier(private val context: Context) {

    /** Create the finalization channel once (idempotent). DEFAULT importance: alerts once, dismissible. */
    fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.kuira_op_finalize_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setShowBadge(true)
                    enableVibration(false)
                },
            )
        }
    }

    /** Post the finalization notification for [outcome] (no-op if notifications are denied). */
    fun post(outcome: OperationOutcome) {
        ensureChannel()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationManagerCompat.from(context)
            .notify(finalizationNotificationId(outcome.kind, outcome.id), build(outcome))
    }

    /**
     * Clear the only-latest completion slot — called when a new operation starts so a stale
     * non-value completion can't linger or be mis-tapped during the next op. Value-transfer
     * (Send) finalizations live in their OWN ids and are deliberately NOT cleared here: each
     * sent transaction is a record the user dismisses themselves.
     */
    fun cancel() = NotificationManagerCompat.from(context).cancel(ONLY_LATEST_ID)

    internal fun build(outcome: OperationOutcome): Notification {
        val title = outcome.completionLabel
            ?: outcome.label
            ?: context.getString(outcome.kind.defaultLabelRes())
        val body = context.getString(outcome.status.labelRes())
        val accent = ContextCompat.getColor(context, R.color.kuira_sync_accent)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.kuira_sync_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setColor(accent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // PRIVATE: the title is the op label ("Sending NIGHT…") — keep it off a locked screen,
            // showing the neutral [redactedPublic] there; the full label appears only once unlocked.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicNotification(context, CHANNEL_ID, accent))
        (outcome.contentIntent ?: launchIntent())?.let(builder::setContentIntent)
        return builder.build()
    }

    /** Tap → open the host app (resolved by package; the SDK can't reference a host Activity). */
    private fun launchIntent(): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val CHANNEL_ID = "kuira_tx_updates"
        /**
         * Id map within the reserved finalization block 0xD058..0xD067:
         *  - [ONLY_LATEST_ID] (0xD058): stale / non-value op completions (dust, contract,
         *    custom). A new one REPLACES the previous so only the latest shows (#282); [cancel]
         *    clears it on a new op.
         *  - [SENT_BASE_ID] + id % [SENT_SPAN] (0xD059..0xD067): value sends. Each distinct send
         *    keeps its own slot so transactions don't replace each other; keyed by the op id so
         *    a single send's terminal outcome can't duplicate.
         */
        internal const val ONLY_LATEST_ID = 0xD058
        internal const val SENT_BASE_ID = 0xD059
        internal const val SENT_SPAN = 15
    }
}

/**
 * Which notification id a finalization posts to (pure, unit-tested). A NIGHT
 * [OperationKind.Send] is a value transaction — it gets its OWN id, keyed by the op [id], so
 * distinct sends are separate, persistent records while a single send can't duplicate. Every
 * other kind shares the single only-latest slot, so stale operation completions keep collapsing
 * to the latest (#282).
 */
internal fun finalizationNotificationId(kind: OperationKind, id: Long): Int =
    if (kind == OperationKind.Send) {
        FinalizationNotifier.SENT_BASE_ID + (id % FinalizationNotifier.SENT_SPAN).toInt()
    } else {
        FinalizationNotifier.ONLY_LATEST_ID
    }
