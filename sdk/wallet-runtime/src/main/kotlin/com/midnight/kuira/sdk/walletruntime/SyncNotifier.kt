package com.midnight.kuira.sdk.walletruntime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.midnight.kuira.sdk.SyncPhase
import com.midnight.kuira.sdk.SyncStatus

/**
 * Builds the wallet-sync "Live Update" notification (#235) — the background
 * counterpart to the in-app `WalletSyncIndicator`. An ongoing, silent, themed
 * progress notification visible in the shade, on the lock screen, and — on
 * Android 16 — as a promoted **status-bar chip** ("Kuira Syncing" / "Kuira NN%").
 * The phase label (dust / shielded refresh / genesis) is resolved from
 * [SyncStatus.Syncing.phase].
 *
 * On API 36+ it uses the platform [Notification.ProgressStyle] — the rich
 * Live-Update tracker (a themed segment + a moving tracker dot). Below 36 it
 * falls back to a `NotificationCompat` `BigTextStyle` + classic progress bar.
 *
 * Promotion (the chip) needs, per the platform contract:
 *  - the `POST_PROMOTED_NOTIFICATIONS` manifest permission (declared here),
 *  - a promotion REQUEST — the `android.requestPromotedOngoing` extra (what
 *    `NotificationCompat.setRequestPromotedOngoing` writes); NOT a raw
 *    `FLAG_PROMOTED_ONGOING`, which is the system's output signal,
 *  - an ongoing notification with a promotable style (ProgressStyle / BigText),
 *  - a content title, a channel importance above `IMPORTANCE_MIN`,
 *  - and `setColorized(true)` must NOT be used (it disqualifies promotion).
 * The chip rendering itself is OEM-dependent (Samsung routes it to its Now Bar).
 *
 * Pure text/percentage seam ([syncNotificationText]) stays unit-testable.
 */
class SyncNotifier(private val context: Context) {

    /**
     * Create the sync channel once (idempotent). Importance is DEFAULT (silent —
     * no sound/vibration) so it clears the `IMPORTANCE_MIN` promotion floor while
     * staying quiet. Importance can't be raised after creation, so the old LOW
     * channel is deleted and a new id used to take effect on upgraded installs.
     */
    fun ensureChannel() {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Wallet balance sync progress"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    /** Build the ongoing progress notification for a [SyncStatus.Syncing] tick. */
    fun build(status: SyncStatus.Syncing): Notification =
        build(phase = status.phase, fraction = status.fraction)

    /**
     * Indeterminate placeholder for the brief FGS-start window before the first
     * real status tick arrives (generic label + the dust state icon).
     */
    fun buildIndeterminate(): Notification = build(phase = null, fraction = null)

    private fun build(phase: SyncPhase?, fraction: Float?): Notification {
        ensureChannel()
        val label = phase?.let { context.getString(it.labelRes()) }
            ?: context.getString(R.string.kuira_sync_generic)
        val (text, percent) = syncNotificationText(label, fraction)
        val accent = ContextCompat.getColor(context, R.color.kuira_sync_accent)
        val largeIconRes = (phase ?: SyncPhase.DustFull).largeIconRes()
        // Phase-aware chip: a short phase word + percent ("Dust 45%"); the brand
        // generic ("Kuira Syncing") only when no phase is known yet.
        val phaseChip = phase?.let { context.getString(it.chipLabelRes()) }
            ?: context.getString(R.string.kuira_sync_chip)
        val chip = percent
            ?.let { context.getString(R.string.kuira_sync_chip_fmt, phaseChip, it) }
            ?: phaseChip
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildRich(text, percent, accent, chip, largeIconRes)
        } else {
            buildCompat(text, percent, accent, chip, largeIconRes)
        }
    }

    /**
     * API 36+: the platform [Notification.ProgressStyle] Live-Update tracker —
     * a themed segment with a moving tracker dot. Promotion is requested via the
     * `android.requestPromotedOngoing` extra (no public platform constant exists).
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildRich(text: String, percent: Int?, accent: Int, chip: String, largeIconRes: Int): Notification {
        val style = Notification.ProgressStyle().setStyledByProgress(false)
        if (percent != null) {
            val pointColor = ContextCompat.getColor(context, R.color.kuira_sync_point)
            style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(PROGRESS_MAX).setColor(accent)))
                // Quarter-mark milestone dots — the runner passes each as it advances.
                .setProgressPoints(
                    PROGRESS_POINTS.map { Notification.ProgressStyle.Point(it).setColor(pointColor) },
                )
                .setProgress(percent)
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.kuira_sync_tracker))
        } else {
            style.setProgressIndeterminate(true)
        }
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.kuira_sync_icon)
            .setLargeIcon(Icon.createWithResource(context, largeIconRes))
            .setContentTitle(context.getString(R.string.kuira_sync_title))
            .setContentText(text)
            .setStyle(style)
            .setShortCriticalText(chip)
            .setColor(accent)
            .setColorized(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        launchIntent()?.let(builder::setContentIntent)
        return builder.build()
    }

    /** API 30–35: NotificationCompat fallback (BigText + classic progress bar). */
    private fun buildCompat(text: String, percent: Int?, accent: Int, chip: String, largeIconRes: Int): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.kuira_sync_icon)
            .setLargeIcon(Icon.createWithResource(context, largeIconRes))
            .setContentTitle(context.getString(R.string.kuira_sync_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(chip)
            .setColor(accent)
            .setColorized(false)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (percent != null) {
            builder.setProgress(PROGRESS_MAX, percent, false)
        } else {
            builder.setProgress(0, 0, true) // indeterminate (replay tail)
        }
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
        const val CHANNEL_ID = "kuira_wallet_sync" // DEFAULT-importance channel (Live-Update eligible)
        private const val LEGACY_CHANNEL_ID = "kuira_dust_sync" // old LOW channel — deleted on upgrade
        const val NOTIFICATION_ID = 0xD057 // arbitrary stable id for the sync notification
        private const val CHANNEL_NAME = "Wallet sync"
        private const val PROGRESS_MAX = 100

        /** Quarter-mark milestone positions (0..[PROGRESS_MAX]) drawn on the bar. */
        private val PROGRESS_POINTS = listOf(25, 50, 75)

        /**
         * The extra key `NotificationCompat.setRequestPromotedOngoing` writes and the
         * platform NMS reads to grant the Live-Update chip. No public platform
         * constant exists in API 36, so the key is mirrored here for the rich path.
         */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}

/**
 * Pure seam (unit-tested): a resolved label + fraction → (content text, percent-or-null).
 * Determinate fraction → "<label> · NN%" + the int percent; indeterminate → just the label.
 */
internal fun syncNotificationText(label: String, fraction: Float?): Pair<String, Int?> {
    val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() }
    val text = if (percent != null) "$label · $percent%" else label
    return text to percent
}
