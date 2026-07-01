package com.midnight.kuira.sdk.walletruntime

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented end-to-end coverage for the wallet's received-funds signal notifications
 * (feat/261 review — #261-264 / #274). It posts through the REAL [NotificationManager] and
 * reads them back via [NotificationManager.getActiveNotifications], verifying the two behaviors
 * a JVM/Robolectric test can't prove on a real device:
 *  - concurrent receipts land as DISTINCT notifications (the rolling-id guard — a later receipt
 *    must never silently overwrite an earlier one), and
 *  - each is lock-screen-redacted (VISIBILITY_PRIVATE + a neutral public version), so the amount
 *    never renders on a locked screen.
 */
@RunWith(AndroidJUnit4::class)
class WalletNotificationSignalsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun grantPermissionAndClear() {
        // POST_NOTIFICATIONS is runtime-gated on API 33+; without it notify() is a no-op.
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        notificationManager.cancelAll()
    }

    @After
    fun clear() = notificationManager.cancelAll()

    @Test
    fun concurrentReceipts_postAsDistinct_lockScreenRedacted_notifications() {
        val alerter = AlertNotifier(context)
        alerter.postReceived("Received 5 NIGHT", "Tap to view your wallet.", null)
        alerter.postReceived("Received 12 NIGHT", "Tap to view your wallet.", null)

        // notify() enqueues ASYNCHRONOUSLY (binder → NotificationManagerService), so a synchronous
        // read of activeNotifications can miss a just-posted one. Wait for both to land before
        // asserting — the rolling ids themselves are already distinct; this only rides out the post.
        val active = awaitActiveNotifications(minDistinctIds = 2)

        // Both receipts must survive as SEPARATE notifications — the rolling id prevents a later
        // receipt from silently replacing an earlier one.
        val ids = active.map { it.id }.toSet()
        assertTrue("expected >= 2 distinct active notifications, got ids=$ids", ids.size >= 2)

        val redacted = context.getString(R.string.kuira_notification_redacted)
        active.forEach { sbn ->
            val n = sbn.notification
            assertEquals(
                "received-funds notification must be PRIVATE on the lock screen",
                Notification.VISIBILITY_PRIVATE,
                n.visibility,
            )
            assertNotNull("must carry a redacted public (lock-screen) version", n.publicVersion)
            assertEquals(
                "the NIGHT amount must NOT appear on the lock-screen version",
                redacted,
                n.publicVersion!!.extras.getString(Notification.EXTRA_TITLE),
            )
        }
    }

    /**
     * Poll [NotificationManager.getActiveNotifications] until at least [minDistinctIds] distinct ids
     * are present, or [timeoutMs] elapses. notify() enqueues asynchronously, so a synchronous read
     * can race a just-posted notification. Returns the last snapshot — the caller still asserts, so
     * a timeout surfaces as a clear assertion failure rather than a hang.
     */
    private fun awaitActiveNotifications(
        minDistinctIds: Int,
        timeoutMs: Long = 5_000L,
    ): Array<StatusBarNotification> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var active = notificationManager.activeNotifications
        while (active.map { it.id }.toSet().size < minDistinctIds &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
            active = notificationManager.activeNotifications
        }
        return active
    }
}
