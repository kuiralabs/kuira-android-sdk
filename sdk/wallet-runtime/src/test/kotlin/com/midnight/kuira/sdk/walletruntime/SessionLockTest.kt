package com.midnight.kuira.sdk.walletruntime

import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SessionLock]'s lock policy. The trigger methods use only the
 * injected provider + a coroutine scope (no Android framework), so we substitute
 * the runTest [kotlinx.coroutines.test.TestScope] and drive virtual time.
 * Platform wiring ([SessionLock.installPlatformHooks]) is exercised on-device,
 * not here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionLockTest {

    private val provider = mockk<MidnightSdkProvider>(relaxed = true)
    private val walletSeedSource = mockk<com.midnight.kuira.sdk.walletseed.WalletSeedSource>(relaxed = true)

    private fun newLock() = SessionLock(provider, walletSeedSource).apply {
        idleTimeoutMs = 1_000
        backgroundGraceMs = 500
    }

    @Test
    fun `lockNow closes the SDK AND forces fresh auth on next seed load`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.lockNow()
        verify(exactly = 1) { provider.close() }
        // The security guarantee: lock must force the next unlock to re-auth,
        // not just drop the SDK (which the 30s Keystore window would undo).
        verify(exactly = 1) { walletSeedSource.requireFreshAuthNext() }
    }

    @Test
    fun `screen off locks immediately`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.onScreenOff()
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `background locks only after the grace period`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.onBackground()

        advanceTimeBy(499); runCurrent()
        verify(exactly = 0) { provider.close() }

        advanceTimeBy(2); runCurrent()
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `returning to foreground cancels the pending background lock`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.onBackground()
        advanceTimeBy(300); runCurrent()

        lock.onForeground() // cancels the bg lock, re-arms the (longer) idle timer
        advanceTimeBy(300); runCurrent() // past the old 500 grace, before the 1000 idle

        verify(exactly = 0) { provider.close() }
    }

    @Test
    fun `idle timeout locks after inactivity`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.onUserActivity()
        advanceTimeBy(1_001); runCurrent()
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `user activity resets the idle timer`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.onUserActivity()
        advanceTimeBy(900); runCurrent()

        lock.onUserActivity() // reset — next lock is 1000ms from here
        advanceTimeBy(900); runCurrent()
        verify(exactly = 0) { provider.close() }

        advanceTimeBy(200); runCurrent()
        verify(exactly = 1) { provider.close() }
    }
}
