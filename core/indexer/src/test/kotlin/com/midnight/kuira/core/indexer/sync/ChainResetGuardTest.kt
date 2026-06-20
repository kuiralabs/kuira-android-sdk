package com.midnight.kuira.core.indexer.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the chain-reset DECISION ([chainStatus]) — the heart of [ChainResetGuard]'s contract.
 *
 * The credibility-critical invariant is the FIRST case: a null `current` (genesis hash unqueryable —
 * a transient indexer failure) is ALWAYS [ChainStatus.UNKNOWN], never a reset, even when a pin
 * exists. That is what guarantees a flaky indexer can't wipe a healthy wallet.
 */
class ChainResetGuardTest {

    @Test
    fun `unqueryable genesis is UNKNOWN, never a reset — even with a pin`() {
        assertEquals(ChainStatus.UNKNOWN, chainStatus(current = null, pinned = null))
        assertEquals(ChainStatus.UNKNOWN, chainStatus(current = null, pinned = "genesisA"))
    }

    @Test
    fun `first sync (no pin) pins, does not wipe`() {
        assertEquals(ChainStatus.FIRST_SYNC, chainStatus(current = "genesisA", pinned = null))
    }

    @Test
    fun `same genesis is the untouched fast path`() {
        assertEquals(ChainStatus.SAME, chainStatus(current = "genesisA", pinned = "genesisA"))
    }

    @Test
    fun `different genesis is a reset`() {
        assertEquals(ChainStatus.RESET, chainStatus(current = "genesisB", pinned = "genesisA"))
    }

    @Test
    fun `empty-string genesis values are compared as-is (no special casing)`() {
        // Defensive: the guard never invents semantics for "" — equality is the only rule, so an
        // indexer that returned "" once and a real hash later is correctly seen as a RESET, and ""
        // vs "" is SAME. (getGenesisBlockHash returns null, not "", on failure — UNKNOWN covers that.)
        assertEquals(ChainStatus.SAME, chainStatus(current = "", pinned = ""))
        assertEquals(ChainStatus.RESET, chainStatus(current = "genesisA", pinned = ""))
    }

    @Test
    fun `resetAction wipes a reset only on a wipe-enabled (dev) chain`() {
        // The gating that keeps PreProd safe: a genuine reset (different genesis) WIPEs on the dev
        // chain but degrades to a log-only SKIP on a remote chain — a remote indexer's anomalous
        // height-0 hash can never nuke a healthy wallet.
        assertEquals(ResetAction.WIPE, resetAction(ChainStatus.RESET, wipeOnResetEnabled = true))
        assertEquals(ResetAction.SKIP, resetAction(ChainStatus.RESET, wipeOnResetEnabled = false))
    }

    @Test
    fun `resetAction never wipes a non-reset status, regardless of the gate`() {
        for (enabled in listOf(true, false)) {
            assertEquals(ResetAction.SKIP, resetAction(ChainStatus.UNKNOWN, enabled))
            assertEquals(ResetAction.SKIP, resetAction(ChainStatus.SAME, enabled))
            assertEquals(ResetAction.PIN, resetAction(ChainStatus.FIRST_SYNC, enabled))
        }
    }

    @Test
    fun `end-to-end - a localnet reset wipes, the same mismatch on a remote chain does not`() {
        // chainStatus + resetAction composed: the exact decision ensureFreshChain makes.
        assertEquals(ResetAction.WIPE, resetAction(chainStatus(current = "newGenesis", pinned = "oldGenesis"), wipeOnResetEnabled = true))
        assertEquals(ResetAction.SKIP, resetAction(chainStatus(current = "newGenesis", pinned = "oldGenesis"), wipeOnResetEnabled = false))
        // An unqueryable genesis is SKIP on either chain — never a wipe.
        assertEquals(ResetAction.SKIP, resetAction(chainStatus(current = null, pinned = "oldGenesis"), wipeOnResetEnabled = true))
    }
}
