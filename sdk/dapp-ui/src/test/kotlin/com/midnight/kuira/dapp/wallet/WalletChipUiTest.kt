package com.midnight.kuira.dapp.wallet

import com.midnight.kuira.core.ledger.ui.BalanceFormatter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.WalletBalance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * Unit tests for [walletChipUi] — the floating chip's UI model.
 *
 * The centerpiece is the syncProgress → chip-fraction plumbing that the enlarged (Panel) tier's
 * progress bar reads. That bar was previously a hardcoded 0.62 mock wired to nothing (always
 * visible, never reflecting real state); these lock in that a real determinate sync fraction
 * actually reaches [WalletChipUi.syncProgress] and that an idle/indeterminate state hides it (null),
 * so a regression back to the mock (or a dropped fraction) fails here rather than only on-device.
 */
class WalletChipUiTest {

    private val formatter = BalanceFormatter()
    private val net = MidnightNetwork.UNDEPLOYED

    @Test
    fun `determinate sync fraction reaches the chip`() {
        val ui = walletChipUi(ready(nightOf(10)), WalletSyncProgress(0.42f, "Syncing"), formatter, net)
        assertEquals(0.42f, ui.syncProgress!!, 1e-6f)
        assertFalse("syncing → not synced", ui.synced)
    }

    @Test
    fun `idle (no sync) hides the bar and marks synced`() {
        val ui = walletChipUi(ready(nightOf(10)), null, formatter, net)
        assertNull("no sync in flight → no determinate bar", ui.syncProgress)
        assertTrue("idle Ready → synced", ui.synced)
    }

    @Test
    fun `indeterminate sync (null fraction) hides the bar but is not synced`() {
        val ui = walletChipUi(ready(nightOf(10)), WalletSyncProgress(null, "Working"), formatter, net)
        assertNull("indeterminate → no determinate bar", ui.syncProgress)
        assertFalse("working → not synced", ui.synced)
    }

    @Test
    fun `non-Ready status yields the placeholder chip`() {
        val ui = walletChipUi(WalletStatus.Loading("boot"), null, formatter, net)
        assertEquals("—", ui.night)
        assertFalse(ui.synced)
        assertNull(ui.syncProgress)
    }

    private fun nightOf(whole: Long): BigInteger = BigInteger.valueOf(whole) * BigInteger.TEN.pow(6)

    private fun ready(unshieldedNight: BigInteger) = WalletStatus.Ready(
        address = "mn_addr_undeployed1test",
        shieldedAddress = "mn_shield-addr_undeployed1test",
        balance = WalletBalance(
            unshieldedNight = unshieldedNight,
            shieldedNight = BigInteger.ZERO,
            dust = BigInteger.ZERO,
            dustRegistered = false,
        ),
    )
}
