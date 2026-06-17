package com.midnight.kuira.sdk.walletruntime

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * Regression for the received-alert collapse: the background poll news up a FRESH
 * [AlertNotifier] on every run, so an in-memory slot counter reset to 0 each time and every
 * receipt landed on the same id, silently replacing the last. The counter is persisted, so
 * even across fresh instances each receipt must get its own notification id.
 */
@RunWith(RobolectricTestRunner::class)
class AlertNotifierReceivedIdTest {

    @Test
    fun `receipts from fresh notifier instances don't collapse onto one notification`() {
        val context = RuntimeEnvironment.getApplication()
        val mgr = context.getSystemService(NotificationManager::class.java)

        AlertNotifier(context).postReceived("Received 1 NIGHT", null)
        AlertNotifier(context).postReceived("Received 2 NIGHT", null) // the worker case: new instance
        AlertNotifier(context).postReceived("Received 3 NIGHT", null)

        assertEquals(3, shadowOf(mgr).allNotifications.size)
    }
}
