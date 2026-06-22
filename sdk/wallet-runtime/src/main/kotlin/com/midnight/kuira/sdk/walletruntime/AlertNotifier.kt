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
        post(RECEIVED_BASE_ID + nextReceivedSlot(), title, body, contentIntent)

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
            // PRIVATE, not PUBLIC: the title can carry a received NIGHT amount — never leak it to a
            // locked screen. The lock screen shows the neutral [redactedPublic]; the full amount
            // appears only once unlocked (when the user is present anyway).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(redactedPublicNotification(context, CHANNEL_ID, accent))
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

    /**
     * Next received-alert slot, rolling over [MAX_CONCURRENT] so distinct receipts each get their
     * own notification id (a fixed id lets a later receipt silently replace an earlier one).
     *
     * Backed by a PROCESS-WIDE atomic, not a plain prefs read-modify-write: the old version read
     * `seq`, then wrote `seq+1` via async `apply()`, so two rapid posts (the live observer + the
     * [ReceivePollWorker], or two within one tick) both read the same `seq` and collided on one id.
     * The atomic hands out distinct slots even under concurrency; prefs only seeds it once per
     * process (the worker news up a fresh [AlertNotifier] each run, so an un-seeded in-memory
     * counter would reset to 0 — the seed preserves the rolling sequence across restarts).
     */
    private fun nextReceivedSlot(): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        receivedSeq.compareAndSet(SEQ_UNSEEDED, prefs.getInt(KEY_RECEIVED_SEQ, 0) % MAX_CONCURRENT)
        val slot = receivedSeq.getAndUpdate { (it + 1) % MAX_CONCURRENT }
        prefs.edit().putInt(KEY_RECEIVED_SEQ, (slot + 1) % MAX_CONCURRENT).apply()
        return slot
    }

    companion object {
        const val CHANNEL_ID = "kuira_alerts"
        // Non-overlapping id ranges (16 wide each). SyncNotifier=0xD057,
        // FinalizationNotifier=0xD058..0xD067 — so these start at 0xD068 to avoid clobbering a
        // completion push with an alert.
        private const val ATTENTION_BASE_ID = 0xD068
        private const val RECEIVED_BASE_ID = 0xD078
        /** Spread of distinct ids so near-simultaneous alerts don't collide. */
        private const val MAX_CONCURRENT = 16
        private const val SEQ_UNSEEDED = -1
        /** Process-wide rolling slot — atomic so rapid in-process posts never collide; prefs seeds it. */
        private val receivedSeq = java.util.concurrent.atomic.AtomicInteger(SEQ_UNSEEDED)
        // The rolling received-slot counter outlives any single notifier instance.
        private const val PREFS = "kuira_alert_state"
        private const val KEY_RECEIVED_SEQ = "received_seq"
    }
}
