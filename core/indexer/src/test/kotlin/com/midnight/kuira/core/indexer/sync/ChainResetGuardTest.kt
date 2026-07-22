package com.midnight.kuira.core.indexer.sync

import com.midnight.kuira.core.indexer.api.BlockHashLookup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the chain-reset DECISION ([checkpointStatus]) — the heart of [ChainResetGuard]'s contract.
 *
 * **Why this looks the way it does (the regression it guards):** the guard's ORIGINAL
 * discriminator was the GENESIS (height-0) block hash, on the premise "genesis differs across a
 * reset." That premise is FALSE for a localnet booting from a fixed chain spec — a full
 * volume-wiping `docker` down/up reproduces the SAME genesis hash byte-for-byte (verified against a
 * live localnet). So a reset read as SAME, the stale dust/UTXO caches were never wiped, and the next
 * spend failed with node error 171 (OutOfDustValidityWindow). The fix pins a CHECKPOINT block above
 * genesis and re-looks-it-up: after a reset the fresh chain is shorter (the checkpoint height is
 * [BlockHashLookup.NotOnChain]) or, once it re-climbs, the block at that height has a different hash.
 * These two cases below — NotOnChain and Found(different) — are that regression, and both must be
 * RESET where the genesis compare returned SAME.
 *
 * The credibility-critical invariant is the LAST case: an un-queryable checkpoint
 * ([BlockHashLookup.Unavailable] — a transient indexer failure) is ALWAYS [ChainStatus.UNKNOWN],
 * never a reset. That is what guarantees a flaky indexer can't wipe a healthy wallet.
 */
class ChainResetGuardTest {

    @Test
    fun `checkpoint height missing on the chain is a RESET (fresh chain is shorter)`() {
        // THE REGRESSION: identical genesis, but the pinned checkpoint height no longer exists →
        // the chain was replaced by a shorter fresh one. The genesis compare called this SAME.
        assertEquals(ChainStatus.RESET, checkpointStatus(pinnedHash = "abc", lookup = BlockHashLookup.NotOnChain))
    }

    @Test
    fun `checkpoint present with a different hash is a RESET (chain re-climbed past the pin)`() {
        assertEquals(ChainStatus.RESET, checkpointStatus(pinnedHash = "abc", lookup = BlockHashLookup.Found("def")))
    }

    @Test
    fun `checkpoint present with the same hash is the untouched fast path`() {
        assertEquals(ChainStatus.SAME, checkpointStatus(pinnedHash = "abc", lookup = BlockHashLookup.Found("abc")))
    }

    @Test
    fun `un-queryable checkpoint is UNKNOWN, never a reset — a flaky indexer can't wipe a wallet`() {
        assertEquals(ChainStatus.UNKNOWN, checkpointStatus(pinnedHash = "abc", lookup = BlockHashLookup.Unavailable))
    }

    @Test
    fun `resetAction wipes a reset only on a wipe-enabled (dev) chain`() {
        // The gating that keeps PreProd safe: a genuine reset WIPEs on the local dev chain but
        // degrades to a log-only SKIP on a remote chain — a remote indexer's pruned or rolled-back
        // checkpoint can never nuke a healthy wallet.
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
}
