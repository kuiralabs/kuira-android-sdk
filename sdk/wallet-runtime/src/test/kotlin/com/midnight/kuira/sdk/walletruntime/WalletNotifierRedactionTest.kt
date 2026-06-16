package com.midnight.kuira.sdk.walletruntime

import android.app.Notification
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.sdk.OperationOutcome
import com.midnight.kuira.sdk.OperationTerminalStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Lock-screen redaction (feat/261 review). A privacy wallet must not leak amounts or operation
 * labels to a locked screen, so EVERY wallet notification is built [Notification.VISIBILITY_PRIVATE]
 * with a neutral [redactedPublicNotification] as its public version. These pin that contract on the
 * three notifier types + the shared helper.
 *
 * `sdk = 33` forces the NotificationCompat path (the API-36 ProgressStyle path differs only in the
 * progress widget; visibility + public version are set identically in both).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WalletNotifierRedactionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val redacted: String get() = context.getString(R.string.kuira_notification_redacted)

    private fun Notification.title(): String? = extras.getString(Notification.EXTRA_TITLE)

    @Test
    fun `received-funds alert hides the amount behind a redacted public version`() {
        val n = AlertNotifier(context).build("Received 5 NIGHT", "Tap to view your wallet.", null)

        assertEquals("the alert must be PRIVATE on the lock screen", Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("the amount is visible once unlocked", "Received 5 NIGHT", n.title())
        assertNotNull("a public (lock-screen) version must exist", n.publicVersion)
        assertEquals("the public version is the neutral redaction", redacted, n.publicVersion!!.title())
        assertNotEquals("the amount must NOT be on the public version", "Received 5 NIGHT", n.publicVersion!!.title())
    }

    @Test
    fun `finalization push hides the op label behind a redacted public version`() {
        val outcome = mockk<OperationOutcome>(relaxed = true) {
            every { completionLabel } returns null
            every { label } returns "Sending NIGHT…"
            every { status } returns OperationTerminalStatus.Success
            every { contentIntent } returns null
        }

        val n = FinalizationNotifier(context).build(outcome)

        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("the op label is visible once unlocked", "Sending NIGHT…", n.title())
        assertNotNull(n.publicVersion)
        assertEquals(redacted, n.publicVersion!!.title())
    }

    @Test
    fun `ongoing operation notification hides the op label behind a redacted public version`() {
        val n = SyncNotifier(context).buildOperation("Sending NIGHT…")

        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotNull(n.publicVersion)
        assertEquals(redacted, n.publicVersion!!.title())
    }

    @Test
    fun `the shared redacted stand-in is neutral and itself public-visibility`() {
        val accent = ContextCompat.getColor(context, R.color.kuira_sync_accent)
        val n = redactedPublicNotification(context, SyncNotifier.CHANNEL_ID, accent)

        assertEquals("the stand-in carries no amount or label", redacted, n.title())
        // It IS the public/lock-screen form, so it itself renders publicly.
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
    }
}
