package com.midnight.kuira.sdk.walletruntime

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The renamed POST_NOTIFICATIONS permission helper (#274 — was `DustSyncNotifications`). It now
 * gates ALL wallet foreground notifications (#261-264), not just dust-sync. Pins the public surface
 * + the always-granted pre-Tiramisu path (the API-33 runtime-gate is exercised by the instrumented
 * test, which controls the real grant state).
 */
@RunWith(RobolectricTestRunner::class)
class WalletNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `PERMISSION is POST_NOTIFICATIONS`() {
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, WalletNotifications.PERMISSION)
    }

    @Test
    @Config(sdk = [30])
    fun `below API 33 notifications are always granted and never need a request`() {
        assertTrue("pre-Tiramisu has no runtime gate", WalletNotifications.isGranted(context))
        assertFalse("nothing to request below API 33", WalletNotifications.shouldRequest(context))
    }
}
