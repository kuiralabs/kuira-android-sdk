package com.midnight.kuira.sdk.walletruntime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.midnight.kuira.sdk.DustSyncStatus

/**
 * Builds the dust-sync "Live Update" notification (#235) — the background
 * counterpart to the in-app `WalletSyncIndicator`. An ongoing, silent progress
 * notification with a determinate bar + percentage (or indeterminate for the
 * replay tail), visible in the shade and on the lock screen.
 *
 * Pure builder (no service/lifecycle deps) so the text/percentage seam is
 * unit-testable. Note: Android notifications are templated — the branded
 * `RunnerDustProgress` runner stays in-app; here it's icon + text + bar + %.
 */
class SyncNotifier(private val context: Context) {

    /** Create the low-importance channel once (idempotent). */
    fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Wallet dust balance sync progress"
                    setShowBadge(false)
                },
            )
        }
    }

    /** Build the ongoing progress notification for a [DustSyncStatus.Syncing] tick. */
    fun build(status: DustSyncStatus.Syncing): Notification {
        ensureChannel()
        val (text, percent) = status.toNotificationText()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(TITLE)
            .setContentText(text)
            .setOngoing(true)          // can't be swiped away while syncing
            .setSilent(true)           // no sound/vibration
            .setOnlyAlertOnce(true)    // updates don't re-alert
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // show on lock screen
        launchIntent()?.let(builder::setContentIntent)
        if (percent != null) {
            builder.setProgress(PROGRESS_MAX, percent, false)
        } else {
            builder.setProgress(0, 0, true) // indeterminate (replay tail)
        }
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
        const val CHANNEL_ID = "kuira_dust_sync"
        const val NOTIFICATION_ID = 0xD057 // arbitrary stable id for the sync notification
        private const val CHANNEL_NAME = "Wallet sync"
        private const val TITLE = "Syncing wallet"
        private const val PROGRESS_MAX = 100
    }
}

/**
 * Pure seam (unit-tested): a [DustSyncStatus.Syncing] → (content text, percent-or-null).
 * Determinate fraction → "<label> · NN%" + the int percent; indeterminate → just the label.
 */
internal fun DustSyncStatus.Syncing.toNotificationText(): Pair<String, Int?> {
    val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
    val text = if (percent != null) "$label · $percent%" else label
    return text to percent
}
