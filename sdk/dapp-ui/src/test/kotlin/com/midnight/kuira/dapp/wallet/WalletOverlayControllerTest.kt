package com.midnight.kuira.dapp.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [WalletOverlayController]'s back-to-sheet reopen bookkeeping.
 *
 * The controller bumps a per-pill reopen counter on [WalletOverlayController.close] so that closing
 * a full-screen overlay (Send / Receive / Settings) returns the user to the sheet they launched it
 * from. That is correct ONLY when the overlay was launched from a sheet. An enlarged floating chip
 * has its OWN action buttons that open overlays directly, with no sheet behind them — reopening one
 * on close springs a surprise sheet over the persistent widget (#52). These lock down that the
 * `reopenSheetOnClose = false` launches do not bump the counter, while the default launches do.
 */
class WalletOverlayControllerTest {

    /**
     * A controller with a host attached — the realistic state when overlays actually render. Also
     * avoids the un-attached [WalletOverlayController.open] path's `android.util.Log.w`, which is
     * unmocked under plain JVM unit tests.
     */
    private fun controller() = WalletOverlayController().apply { hostAttached = true }

    @Test
    fun `sheet-launched Send reopens the wallet sheet on close`() {
        val c = controller()
        c.open(WalletOverlay.Send) // default reopenSheetOnClose = true
        c.close()
        assertEquals(1, c.walletSheetReopen)
        assertEquals(0, c.sigilSheetReopen)
    }

    @Test
    fun `chip-launched Send does NOT reopen the sheet on close`() {
        val c = controller()
        c.open(WalletOverlay.Send, reopenSheetOnClose = false)
        c.close()
        // The regression: before the fix this bumped to 1 and a modal sheet sprang open over the
        // enlarged chip the user actually launched from.
        assertEquals(0, c.walletSheetReopen)
    }

    @Test
    fun `sheet-launched sigil Settings reopens the sigil sheet on close`() {
        val c = controller()
        c.open(WalletOverlay.Settings(SettingsLaunchSource.Sigil))
        c.close()
        assertEquals(1, c.sigilSheetReopen)
        assertEquals(0, c.walletSheetReopen)
    }

    @Test
    fun `chip-launched sigil Settings does NOT reopen the sheet on close`() {
        val c = controller()
        c.open(WalletOverlay.Settings(SettingsLaunchSource.Sigil), reopenSheetOnClose = false)
        c.close()
        assertEquals(0, c.sigilSheetReopen)
    }

    @Test
    fun `the reopen flag is per-launch — a chip launch does not poison the next sheet launch`() {
        val c = controller()
        c.open(WalletOverlay.Receive, reopenSheetOnClose = false)
        c.close()
        assertEquals(0, c.walletSheetReopen)
        // A subsequent sheet-launched overlay must still reopen — the flag is reset by open().
        c.open(WalletOverlay.Receive)
        c.close()
        assertEquals(1, c.walletSheetReopen)
    }

    @Test
    fun `dismissForLock never reopens a sheet regardless of launch`() {
        val c = controller()
        c.open(WalletOverlay.Settings(SettingsLaunchSource.Wallet)) // would reopen on close()
        c.dismissForLock()
        assertEquals(0, c.walletSheetReopen)
        assertEquals(0, c.sigilSheetReopen)
    }
}
