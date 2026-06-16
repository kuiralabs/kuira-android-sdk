package com.midnight.kuira.sdk.walletruntime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.midnight.kuira.sdk.OperationAttention

/**
 * Builds + posts the HEADS-UP alert (#264 inbound): a high-importance, dismissible
 * notification that pulls the user BACK to the app when something needs them — a tracked
 * operation awaiting their input ("your turn"), or funds arriving ("received N NIGHT").
 *
 * It's deliberately the loudest of the three notifier types, because its whole job is to
 * SUMMON:
 *  - [SyncNotifier] — ongoing progress, silent + persistent (you're watching it work).
 *  - [FinalizationNotifier] — completion push, default importance (it's done, no rush).
 *  - this — alerting, heads-up + sound once (come back, something needs you NOW).
 *
 * Its own HIGH-importance channel; auto-cancels on tap; alerts once. The caller (the FGS
 * attach observer / the incoming-tx watcher) is responsible for SUPPRESSING it when the user
 * is already looking — there's no point summoning someone who's here.
 */
class AlertNotifier(private val context: Context) {

    /** Create the alert channel once (idempotent). HIGH importance → heads-up + sound. */
    fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.kuira_alert_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setShowBadge(true)
                    enableVibration(true)
                },
            )
        }
    }

    /** Post a "your turn" alert for an operation that needs the user (#264 inbound). */
    fun postAttention(attention: OperationAttention) =
        post(
            // A distinct id per op so two concurrent "your turn"s don't overwrite each other.
            id = ATTENTION_BASE_ID + (attention.id % MAX_CONCURRENT).toInt(),
            title = attention.title,
            body = attention.body,
            contentIntent = attention.contentIntent,
        )

    /**
     * Post a received-funds alert (#264 inbound). [contentIntent] null → opens the app. Uses a
     * DISTINCT id per receipt (rolling) so a later receipt heads-up on its own instead of
     * silently updating an earlier one (the `setOnlyAlertOnce` would otherwise mute it).
     */
    fun postReceived(title: String, body: String?, contentIntent: PendingIntent? = null) =
        post(RECEIVED_BASE_ID + (receivedSeq.getAndIncrement() % MAX_CONCURRENT), title, body, contentIntent)

    private fun post(id: Int, title: String, body: String?, contentIntent: PendingIntent?) {
        ensureChannel()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        NotificationManagerCompat.from(context).notify(id, build(title, body, contentIntent))
    }

    internal fun build(title: String, body: String?, contentIntent: PendingIntent?): Notification {
        val accent = ContextCompat.getColor(context, R.color.kuira_sync_accent)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.kuira_sync_icon)
            .setContentTitle(title)
            .setColor(accent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // heads-up on API < 26
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        body?.let { builder.setContentText(it).setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        (contentIntent ?: launchIntent())?.let(builder::setContentIntent)
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

    // Rolling sequence so each received-funds alert gets its own notification id.
    private val receivedSeq = java.util.concurrent.atomic.AtomicInteger(0)

    companion object {
        const val CHANNEL_ID = "kuira_alerts"
        // Non-overlapping id ranges (16 wide each). SyncNotifier=0xD057,
        // FinalizationNotifier=0xD058..0xD067 — so these start at 0xD068 to avoid clobbering a
        // completion push with an alert.
        private const val ATTENTION_BASE_ID = 0xD068
        private const val RECEIVED_BASE_ID = 0xD078
        /** Spread of distinct ids so near-simultaneous alerts don't collide. */
        private const val MAX_CONCURRENT = 16
    }
}
