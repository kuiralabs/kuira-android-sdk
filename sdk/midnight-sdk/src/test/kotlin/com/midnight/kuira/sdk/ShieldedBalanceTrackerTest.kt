package com.midnight.kuira.sdk

import com.midnight.kuira.core.indexer.repository.ShieldedRepository
import com.midnight.kuira.core.ledger.model.UtxoSpend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import java.math.BigInteger

/**
 * Unit tests for [ShieldedBalanceTracker]'s observable [ShieldedBalanceTracker.nightFlow].
 *
 * The flow drives the wallet panel's live shielded balance (combined into
 * `MidnightWallet.balanceFlow`); these tests pin that `resync()` actually
 * pushes the new amount into it on every call — without this, a shielded
 * credit would sync silently and the panel would never update.
 */
class ShieldedBalanceTrackerTest {

    private val addr = "shielded_test_address"

    @Test
    fun `nightFlow starts at zero`() {
        val tracker = ShieldedBalanceTracker(
            shieldedRepository = mock(),
            walletAddress = addr,
            zswapSeed = ByteArray(32),
        )
        assertEquals(BigInteger.ZERO, tracker.nightFlow.value)
    }

    @Test
    fun `nightFlow emits the new amount when resync finds shielded coins`() = runTest {
        val expected = BigInteger.valueOf(12_345L)
        val repo = mock<ShieldedRepository> {
            onBlocking { syncFromBlockchain(eq(addr), any()) } doReturn true
            onBlocking { getBalances(addr) } doReturn mapOf(UtxoSpend.NATIVE_TOKEN_TYPE to expected)
        }
        val tracker = ShieldedBalanceTracker(
            shieldedRepository = repo,
            walletAddress = addr,
            zswapSeed = ByteArray(32),
        )

        tracker.resync()

        assertEquals(expected, tracker.nightFlow.value)
        assertEquals(expected, tracker.currentNight()) // snapshot stays consistent
    }

    @Test
    fun `nightFlow stays at zero when resync finds no coins`() = runTest {
        val repo = mock<ShieldedRepository> {
            onBlocking { syncFromBlockchain(any(), any()) } doReturn false
        }
        val tracker = ShieldedBalanceTracker(
            shieldedRepository = repo,
            walletAddress = addr,
            zswapSeed = ByteArray(32),
        )

        tracker.resync()

        assertEquals(BigInteger.ZERO, tracker.nightFlow.value)
    }

    @Test
    fun `nightFlow falls back to zero when the balances map has no NIGHT entry`() = runTest {
        val repo = mock<ShieldedRepository> {
            onBlocking { syncFromBlockchain(any(), any()) } doReturn true
            // Has coins, but not the native NIGHT token (some other token type).
            onBlocking { getBalances(addr) } doReturn mapOf("OTHER_TOKEN" to BigInteger.TEN)
        }
        val tracker = ShieldedBalanceTracker(
            shieldedRepository = repo,
            walletAddress = addr,
            zswapSeed = ByteArray(32),
        )

        tracker.resync()

        assertEquals(BigInteger.ZERO, tracker.nightFlow.value)
    }
}
