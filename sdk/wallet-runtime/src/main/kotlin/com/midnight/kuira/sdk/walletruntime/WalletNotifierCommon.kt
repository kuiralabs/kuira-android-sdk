package com.midnight.kuira.sdk.walletruntime

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Neutral lock-screen stand-in shared by all wallet notifiers (sync / finalization / alert).
 *
 * A privacy wallet must not leak amounts, op labels or sync phases to a locked screen, so every
 * wallet notification is posted [NotificationCompat.VISIBILITY_PRIVATE] with THIS as its
 * `setPublicVersion`: the lock screen shows only the generic [R.string.kuira_notification_redacted]
 * line; the full content appears once the device is unlocked (when the user is present anyway).
 *
 * Built on the parent's [channelId] so it inherits the same channel treatment. [ongoing] mirrors
 * the parent (true for the ongoing progress notification) so the redacted form renders the same way.
 */
internal fun redactedPublicNotification(
    context: Context,
    channelId: String,
    accent: Int,
    ongoing: Boolean = false,
): Notification =
    NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.kuira_sync_icon)
        .setContentTitle(context.getString(R.string.kuira_notification_redacted))
        .setColor(accent)
        .setOngoing(ongoing)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .build()
