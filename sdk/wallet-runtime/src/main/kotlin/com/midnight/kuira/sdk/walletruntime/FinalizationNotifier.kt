package com.midnight.kuira.sdk.walletruntime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
        // A distinct id per outcome so two finalizing ops don't overwrite each other.
        NotificationManagerCompat.from(context).notify(
            BASE_NOTIFICATION_ID + (outcome.id % MAX_CONCURRENT).toInt(),
            build(outcome),
        )
    }

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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        launchIntent()?.let(builder::setContentIntent)
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
        private const val BASE_NOTIFICATION_ID = 0xD058
        /** Spread of distinct ids so near-simultaneous finalizations don't collide. */
        private const val MAX_CONCURRENT = 16
    }
}
