package com.midnight.kuira.dapp.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.midnight.kuira.core.identity.backup.AuthorizeOutcome
import com.midnight.kuira.core.identity.backup.DriveAuthManager
import com.midnight.kuira.dapp.sigil.IdentityProvenanceStore
import com.midnight.kuira.sdk.RestoredAppState
import com.midnight.kuira.sdk.WalletBackupPrefs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The restore gate's decision ladder, tested against the REAL stores (Robolectric
 * prefs) — only the Drive boundary is mocked. These are the branches that decide whether a
 * fresh install restores in seconds or silently replays the whole event stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DustRestoreGateImplTest {

    private val driveAuth: DriveAuthManager = mockk(relaxed = true)
    private lateinit var context: Context
    private lateinit var backupState: DustBackupStateStore
    private lateinit var provenance: IdentityProvenanceStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(DustBackupStateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(IdentityProvenanceStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        backupState = DustBackupStateStore(context)
        provenance = IdentityProvenanceStore(context)
    }

    private fun gate() = DustRestoreGateImpl(driveAuth, backupState, provenance)

    private fun restored(enabled: Boolean, optedOut: Boolean) =
        RestoredAppState(WalletBackupPrefs(enabled, optedOut), ByteArray(0))

    @Test
    fun `restored opt-out is mirrored locally and Drive is never probed`() = runTest {
        gate().onColdRestore(restored(enabled = false, optedOut = true))

        assertTrue(backupState.optedOut.value)
        assertFalse(backupState.enabled.value)
        coVerify(exactly = 0) { driveAuth.authorize() }
    }

    @Test
    fun `brand-new wallet pays neither probe nor prompt`() = runTest {
        // No blob, identity freshly forged: the most common install path must not even pay
        // the Play Services authorize() IPC inside the dust-sync critical section.
        gate().onColdRestore(null)

        coVerify(exactly = 0) { driveAuth.authorize() }
        assertFalse(backupState.enabled.value)
    }

    @Test
    fun `a skipped offer is NOT durable — the next cold build re-offers`() = runTest {
        // Skipping (or dismissing) must never permanently disable restore — a fresh gate
        // instance (= fresh SDK build) offers again.
        coEvery { driveAuth.authorize() } returns AuthorizeOutcome.NeedsConsent(mockk())
        provenance.identityRestored = true
        val first = gate()
        val c1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            first.restoreOffer.collect { if (it) first.skipRestore() }
        }
        first.onColdRestore(null) // skipped

        var secondOffered = false
        val second = gate()
        val c2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            second.restoreOffer.collect { if (it) { secondOffered = true; second.skipRestore() } }
        }
        second.onColdRestore(null)

        assertTrue("a skip must not burn future offers", secondOffered)
        c1.cancel(); c2.cancel()
    }

    @Test
    fun `grant already present commits the toggle ON silently`() = runTest {
        // Same-app reinstall / new device: account-level grant survives — zero UX.
        coEvery { driveAuth.authorize() } returns AuthorizeOutcome.Authorized(mockk())

        gate().onColdRestore(restored(enabled = true, optedOut = false))

        assertTrue(backupState.enabled.value)
        assertFalse(backupState.optedOut.value)
    }

    @Test
    fun `needs-consent with no offer UI attached skips immediately without burning the prompt`() = runTest {
        // Headless host / backgrounded app: nobody can render the offer step — the gate must
        // NOT stall the dust-sync mutex for the timeout, and nothing durable is recorded.
        coEvery { driveAuth.authorize() } returns AuthorizeOutcome.NeedsConsent(mockk())
        provenance.identityRestored = true

        gate().onColdRestore(null) // no collector on restoreOffer

        assertFalse(backupState.enabled.value)
        assertFalse(backupState.optedOut.value)
    }

    @Test
    fun `dismissing the SYSTEM dialog returns to the offer — only an explicit skip proceeds`() = runTest {
        // The exact on-device failure: Google's dialog is easy to dismiss by accident. A
        // dismissal must land the user BACK on the offer step (retry available), never
        // silently fall through to genesis.
        coEvery { driveAuth.authorize() } returns AuthorizeOutcome.NeedsConsent(mockk())
        every { driveAuth.tokenFromConsent(any()) } throws IllegalStateException("consent dismissed")
        provenance.identityRestored = true
        val g = gate()
        var offerSurvivedDismissal = false
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            g.restoreOffer.collect { showing ->
                if (showing) {
                    g.connectRestore() // user taps Connect & Restore…
                    g.completeConsent(null) // …but dismisses the system dialog
                    offerSurvivedDismissal = g.restoreOffer.value
                    g.skipRestore() // only this explicit choice proceeds
                }
            }
        }

        g.onColdRestore(null)

        assertTrue("the offer must still be showing after a system-dialog dismissal", offerSurvivedDismissal)
        assertFalse(backupState.enabled.value)
        collector.cancel()
    }

    @Test
    fun `connect then grant commits the toggle ON`() = runTest {
        coEvery { driveAuth.authorize() } returns AuthorizeOutcome.NeedsConsent(mockk())
        every { driveAuth.tokenFromConsent(any()) } returns mockk()
        provenance.identityRestored = true
        val g = gate()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            g.restoreOffer.collect { if (it) { g.connectRestore(); g.completeConsent(null) } }
        }

        g.onColdRestore(null)

        assertTrue(backupState.enabled.value)
        collector.cancel()
    }

    @Test
    fun `enable flow JOINS a pending prompt and both see the same grant`() = runTest {
        // The toggle tap racing the fresh-install gate (the exact on-device failure): the
        // enable flow must share the gate dialog's verdict — never cancel it (the old
        // takeover abandoned the restore mid-dialog → genesis) and never stack a second
        // dialog. On grant BOTH proceed: the gate commits + restores; the joiner sees GRANTED.
        coEvery { driveAuth.authorize() } returns AuthorizeOutcome.NeedsConsent(mockk())
        every { driveAuth.tokenFromConsent(any()) } returns mockk()
        provenance.identityRestored = true
        val g = gate()
        var joinerVerdict: ConsentVerdict? = null
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            g.restoreOffer.collect { showing ->
                if (!showing) return@collect
                // The toggle tap joins while the offer is up…
                val join = launch { joinerVerdict = g.awaitPendingVerdict() }
                // …then the user connects and grants the ONE dialog.
                g.connectRestore()
                g.completeConsent(null)
                join.join()
            }
        }

        g.onColdRestore(null)

        assertTrue("the gate's grant path must commit the toggle", backupState.enabled.value)
        org.junit.Assert.assertEquals(ConsentVerdict.GRANTED, joinerVerdict)
        collector.cancel()
    }

    @Test
    fun `cancelling the gate mid-offer releases a joined enable flow immediately`() = runTest {
        // The enable toggle joined the offer's deferred (awaitPendingVerdict). The gate is then
        // cancelled (SDK teardown / network switch). awaitConsent's finally must COMPLETE the
        // shared deferred so the joiner resumes now — not block its own 10-min timeout with the
        // toggle stuck spinning. Asserted by latency: the joiner is done before any time advance.
        coEvery { driveAuth.authorize() } returns AuthorizeOutcome.NeedsConsent(mockk())
        provenance.identityRestored = true
        val g = gate()
        val offerCollector = launch(UnconfinedTestDispatcher(testScheduler)) { g.restoreOffer.collect {} }
        val gateJob = launch(UnconfinedTestDispatcher(testScheduler)) { g.onColdRestore(null) }
        var joinerVerdict: ConsentVerdict? = null
        var joinerDone = false
        val joiner = launch(UnconfinedTestDispatcher(testScheduler)) {
            joinerVerdict = g.awaitPendingVerdict(); joinerDone = true
        }

        gateJob.cancel() // SDK torn down mid-offer

        assertTrue("the joiner must resume immediately, not after the timeout", joinerDone)
        org.junit.Assert.assertEquals(ConsentVerdict.UNANSWERED, joinerVerdict)
        joiner.cancel(); offerCollector.cancel()
    }

    @Test
    fun `awaitPendingVerdict returns null when nothing is pending`() = runTest {
        // The enable flow probes before running its own consent — no gate prompt in flight
        // must mean "run your own flow", never a hang.
        org.junit.Assert.assertNull(gate().awaitPendingVerdict())
    }

    @Test
    fun `completeConsent with no pending prompt reports not-consumed`() = runTest {
        // onConsentResult offers every result to the gate FIRST — with nothing pending it
        // must decline to consume, so the enable flow's result reaches cloudSyncNow.
        assertFalse(gate().completeConsent(null))
    }
}
