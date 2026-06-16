package com.midnight.kuira.sdk.walletruntime

import androidx.fragment.app.FragmentActivity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `unlock re-authenticates via the seed source and lifts the lock`() = runTest {
        val lock = newLock().also { it.scope = this }
        val activity = mockk<FragmentActivity>(relaxed = true)
        // ensureSeedReady prompting biometric is the unlock's teeth; relaxed mock
        // returns a seed without throwing → unlock succeeds.
        coEvery { walletSeedSource.ensureSeedReady(activity) } returns ByteArray(64)

        lock.lockNow()
        assertTrue("precondition: locked after lockNow", lock.locked.value)

        val ok = lock.unlock(activity)

        assertTrue("unlock should report success", ok)
        assertFalse("lock must be lifted after a successful re-auth", lock.locked.value)
        coVerify(exactly = 1) { walletSeedSource.ensureSeedReady(activity) }
    }

    @Test
    fun `unlock failure keeps the session locked`() = runTest {
        val lock = newLock().also { it.scope = this }
        val activity = mockk<FragmentActivity>(relaxed = true)
        // Cancelled / failed biometric → ensureSeedReady throws.
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws RuntimeException("auth cancelled")

        lock.lockNow()
        val ok = lock.unlock(activity)

        assertFalse("unlock should report failure", ok)
        assertTrue("a failed re-auth must NOT lift the lock", lock.locked.value)
    }

    @Test
    fun `screen off locks immediately`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.onScreenOff()
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `background SOFT-locks after the grace — gates UI + re-auth, but KEEPS the SDK alive`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.onBackground()

        advanceTimeBy(499); runCurrent()
        assertFalse("within the grace — not locked yet", lock.locked.value)

        advanceTimeBy(2); runCurrent()
        // App-switching soft-locks: the UI gates (re-auth on return is armed) but the SDK is
        // NOT closed, so background monitoring (received-funds / sync) keeps running.
        assertTrue("soft-locked after the grace", lock.locked.value)
        verify(exactly = 1) { walletSeedSource.requireFreshAuthNext() }
        verify(exactly = 0) { provider.close() }
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

    // ── Operation holds (#235 pivot: don't drop the SDK mid-transaction) ──

    @Test
    fun `a background soft-lock keeps the SDK alive through the grace, then hard-wipes at the idle ceiling`() = runTest {
        val lock = newLock().also { it.scope = this }

        // Background grace expires: SOFT-lock — gate the UI but KEEP the SDK alive so monitoring
        // continues (no provider.close yet).
        lock.onBackground()
        advanceTimeBy(501); runCurrent()
        assertTrue("soft-locked after the grace", lock.locked.value)
        verify(exactly = 0) { provider.close() }

        // Still backgrounded past the idle ceiling (idleTimeoutMs, re-armed by the soft-lock):
        // the seed must not live forever, so it escalates to a full wipe.
        advanceTimeBy(1_001); runCurrent()
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `the soft-lock idle-ceiling wipe still defers while an operation is held`() = runTest {
        val lock = newLock().also { it.scope = this }
        val hold = lock.acquireHold()

        lock.onBackground()
        advanceTimeBy(501); runCurrent() // soft-lock; SDK alive
        verify(exactly = 0) { provider.close() }

        // Idle ceiling fires, but a value-bearing op is in flight → the wipe is DEFERRED, not skipped.
        advanceTimeBy(1_001); runCurrent()
        verify(exactly = 0) { provider.close() }

        // Op finishes → the deferred wipe runs, so the seed isn't pinned in memory after the op.
        hold.close()
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `screen-off lock is deferred while held`() = runTest {
        val lock = newLock().also { it.scope = this }
        val hold = lock.acquireHold()

        lock.onScreenOff()
        verify(exactly = 0) { provider.close() }

        hold.close()
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `manual lockNow ignores holds and locks immediately`() = runTest {
        val lock = newLock().also { it.scope = this }
        lock.acquireHold() // never released

        lock.lockNow()

        // Explicit user intent overrides the hold.
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `releasing a hold with no pending lock does not lock`() = runTest {
        val lock = newLock().also { it.scope = this }
        val hold = lock.acquireHold()

        hold.close() // no auto-lock fired while held → nothing to run

        verify(exactly = 0) { provider.close() }
    }

    @Test
    fun `nested holds defer until the LAST release`() = runTest {
        val lock = newLock().also { it.scope = this }
        val outer = lock.acquireHold()
        val inner = lock.acquireHold()

        lock.onScreenOff() // deferred
        inner.close()
        verify(exactly = 0) { provider.close() } // outer still holds

        outer.close()
        verify(exactly = 1) { provider.close() } // last release runs it
    }
}
