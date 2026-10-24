package com.midnight.kuira.sdk.walletruntime

import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.core.identity.backup.SigilRequiredException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
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

    /**
     * A lock for a session that HAS a sigil — the precondition for locking to mean anything.
     * [SessionLock] declines to lock a sigil-less session (there is no seed to protect), so every
     * test about lock policy has to state that a sigil exists or it is testing the skip path
     * instead. A relaxed mock answers `hasSigil()` with `false`, which is precisely the sigil-less
     * case, so leaving it unstubbed would silently turn these tests green for the wrong reason.
     */
    private fun newLock() = SessionLock(provider, walletSeedSource).apply {
        every { walletSeedSource.hasSigil() } returns true
        idleTimeoutMs = 1_000
        backgroundGraceMs = 500
        backgroundLifetimeMs = 5_000 // background seed-lifetime ceiling
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
    fun `a background soft-lock keeps the SDK alive (even with a hold) and never escalates to a wipe`() = runTest {
        val lock = newLock().also { it.scope = this }
        val hold = lock.acquireHold()

        // Background grace expires: SOFT-lock — gate the UI but KEEP the SDK alive so monitoring
        // continues. It's non-destructive, so it neither defers on the hold nor ever wipes.
        lock.onBackground()
        advanceTimeBy(501); runCurrent()
        assertTrue("soft-locked after the grace", lock.locked.value)
        verify(exactly = 0) { provider.close() }

        // Releasing the hold must NOT trigger a wipe — the background trigger queued no hard lock.
        hold.close()
        verify(exactly = 0) { provider.close() }
    }

    // ── Background seed-lifetime ceiling ──

    @Test
    fun `a held backgrounded session (Kicks match) never wipes past the lifetime ceiling — the hold defers it`() = runTest {
        // REGRESSION GUARD: a dual-process consumer (Midnight Kicks) backgrounds the main process
        // for the ENTIRE match and holds the session (MatchManager.acquireHold). The background
        // lifetime ceiling MUST defer on that hold — never tear the SDK out from under an in-flight
        // game — and fire only once the match releases the hold.
        val lock = newLock().also { it.scope = this }
        val hold = lock.acquireHold()

        lock.onBackground()
        advanceTimeBy(501); runCurrent()
        assertTrue("soft-locked after the grace", lock.locked.value)

        advanceTimeBy(10 * 5_000); runCurrent() // far past the ceiling, still held (match running)
        verify(exactly = 0) { provider.close() } // deferred while held — match safe

        hold.close() // match ends → the deferred ceiling lock now fires
        verify(exactly = 1) { provider.close() }
    }

    @Test
    fun `an idle, unheld backgrounded session hard-wipes at the lifetime ceiling`() = runTest {
        // The seed must not sit decrypted in memory forever just because the screen never turned
        // off. With nothing held, the ceiling bounds the backgrounded lifetime with a real wipe.
        val lock = newLock().also { it.scope = this }
        lock.onBackground()

        advanceTimeBy(501); runCurrent()
        assertTrue("soft-locked after the grace", lock.locked.value)
        verify(exactly = 0) { provider.close() } // still alive — within the ceiling

        advanceTimeBy(5_000); runCurrent() // cross the 5_000 ceiling
        verify(exactly = 1) { provider.close() } // bounded — hard wipe
    }

    @Test
    fun `returning to foreground cancels the lifetime ceiling`() = runTest {
        // An actively-used app must never be wiped by the backgrounded-lifetime bound; foregrounding
        // cancels the pending ceiling. (Idle-while-foreground is the separate idleTimeout path.)
        val lock = newLock().also { it.scope = this }
        lock.onBackground()
        advanceTimeBy(1_000); runCurrent() // backgrounded a while (past grace, before ceiling)

        lock.onForeground() // cancels the ceiling + re-arms idle
        // Stay actively foregrounded well past where the ceiling (5_000 from background) would fire,
        // resetting idle each tick so the foreground idle path can't fire instead.
        repeat(8) { advanceTimeBy(900); runCurrent(); lock.onUserActivity() }

        verify(exactly = 0) { provider.close() }
    }

    @Test
    fun `hardLocked is false on a soft-lock and true on a hard lock`() = runTest {
        // The FGS keys its teardown on hardLocked, NOT locked: a soft-lock (SDK kept alive) must
        // read false so the foreground service survives an app-switch; a real wipe reads true.
        val lock = newLock().also { it.scope = this }

        lock.onBackground()
        advanceTimeBy(501); runCurrent()
        assertTrue("backgrounding soft-locks", lock.locked.value)
        assertFalse("a soft-lock keeps the SDK alive → not hard-locked", lock.hardLocked.value)

        lock.onScreenOff() // device-secured trigger → full wipe
        assertTrue("screen-off hard-locks (SDK torn down)", lock.hardLocked.value)
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

    /**
     * The trap, reproduced: a sigil-less session that locks can never be unlocked.
     *
     * Found on a real device. bboard on a fresh install showed LOCKED with "Authentication didn't
     * complete — tap to try again", and every tap failed identically because `ensureSeedReady`
     * throws [SigilRequiredException] when no sigil has been forged — there is no seed for any
     * biometric to produce. The only control on that screen is the unlock, so the only escape was
     * force-stopping the app. Logged as:
     *   `SessionLock: Unlock failed (SigilRequiredException) — staying locked`
     *
     * Before the fix the catch-all treated this as just another failed attempt and left `locked`
     * true, so this test fails on the last assertion. After, the lock lifts (nothing to protect)
     * and [SessionLock.sigilRequired] says why, so the UI can stop inviting a pointless retry.
     */
    @Test
    fun `unlock with no sigil lifts the lock instead of stranding the user on it`() = runTest {
        val lock = newLock().also { it.scope = this }
        val activity = mockk<FragmentActivity>(relaxed = true)
        lock.lockNow()
        assertTrue("precondition: a sigil-bearing session locks", lock.locked.value)

        // The sigil is gone by the time the unlock runs — a fresh install, or a restore that
        // dropped it. Retrying this cannot ever succeed.
        every { walletSeedSource.hasSigil() } returns false
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws SigilRequiredException()

        val ok = lock.unlock(activity)

        assertFalse("unlock cannot succeed without a sigil", ok)
        assertTrue("the gate must be able to say WHY, not just 'try again'", lock.sigilRequired.value)
        assertFalse(
            "a session with no sigil has no seed to protect — staying locked strands the user " +
                "on a screen whose only control cannot work",
            lock.locked.value,
        )
    }

    /** Locking a sigil-less session is the state the test above has to recover from — don't enter it. */
    @Test
    fun `a sigil-less session is never locked in the first place`() = runTest {
        val lock = newLock().also { it.scope = this }
        every { walletSeedSource.hasSigil() } returns false

        lock.lockNow()
        assertFalse("no sigil means no seed to protect, so no lock", lock.locked.value)
        verify(exactly = 0) { provider.close() }

        lock.onScreenOff()
        runCurrent()
        assertFalse("screen-off must not lock a sigil-less session either", lock.locked.value)
    }

    /**
     * The flag must not outlive the condition it describes.
     *
     * Caught reviewing the fix, not by the tests above: `sigilRequired` was only cleared on a
     * SUCCESSFUL unlock, so after forging a sigil it stayed true, and the next ordinary lock
     * rendered "you don't have a sigil on this device yet" over a session that plainly did —
     * while also suppressing the retry hint that an ordinary lock needs.
     */
    @Test
    fun `forging a sigil clears the stale no-sigil flag before the next lock`() = runTest {
        val lock = newLock().also { it.scope = this }
        val activity = mockk<FragmentActivity>(relaxed = true)

        // 1. Reach the sigil-less state so the flag is set.
        lock.lockNow()
        every { walletSeedSource.hasSigil() } returns false
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws SigilRequiredException()
        lock.unlock(activity)
        assertTrue("precondition: the no-sigil flag is set", lock.sigilRequired.value)

        // 2. The user forges a sigil, then the session locks normally later on.
        every { walletSeedSource.hasSigil() } returns true
        lock.lockNow()

        assertTrue("a sigil-bearing session still locks", lock.locked.value)
        assertFalse(
            "the lock screen must not claim there is no sigil once one has been forged",
            lock.sigilRequired.value,
        )
    }

    /** The retryable case must keep its old behaviour: a cancelled biometric stays locked. */
    @Test
    fun `a cancelled biometric still stays locked and is not reported as a missing sigil`() = runTest {
        val lock = newLock().also { it.scope = this }
        val activity = mockk<FragmentActivity>(relaxed = true)
        lock.lockNow()
        coEvery { walletSeedSource.ensureSeedReady(activity) } throws
            IllegalStateException("biometric cancelled")

        val ok = lock.unlock(activity)

        assertFalse("a cancelled biometric is still a failed unlock", ok)
        assertTrue("retryable failure must KEEP the lock", lock.locked.value)
        assertFalse(
            "a cancelled biometric is retryable — it must not be reported as a missing sigil",
            lock.sigilRequired.value,
        )
    }
}
