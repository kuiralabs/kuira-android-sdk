package com.midnight.kuira.core.indexer.sync

import android.util.Log
import com.midnight.kuira.core.indexer.api.BlockHashLookup
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.repository.ShieldedRepository
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Detects a chain RESET — a localnet `docker` down/up replaces the chain with a fresh genesis —
 * and wipes the stale wallet caches so they re-sync from the new chain.
 *
 * **Why this exists:** all three sync layers (shielded, dust, unshielded) decide "already synced"
 * by comparing event/transaction-ID COUNTS. After a reset the fresh chain restarts IDs at 0 and,
 * once the wallet is re-funded, re-climbs to the SAME counts — so every layer concludes "at tip"
 * and keeps the OLD balance (the 30,001-NIGHT ghost). Left unfixed, the stale DUST also fails the
 * next spend with node error 171 (OutOfDustValidityWindow).
 *
 * **Why a CHECKPOINT block, not the genesis hash (the #36 fix):** the obvious discriminator is the
 * genesis (height-0) hash — but a localnet booting from a FIXED chain spec reproduces the same
 * genesis hash byte-for-byte across a reset (verified against a live localnet: a full volume-wiping
 * down/up returned the identical height-0 hash). So genesis can't tell a reset from a healthy chain.
 * Instead the guard pins a CHECKPOINT block ABOVE genesis — `(height, hash)` — and re-looks-up that
 * height each sync. Two independent reset signals fall out of one lookup:
 *  - the pinned height is [BlockHashLookup.NotOnChain] — the fresh chain is shorter (proven live: an
 *    old tip height returned `block: null` on the fresh chain), or
 *  - the block at that height carries a DIFFERENT hash — the fresh chain re-climbed past the pin
 *    (blocks above genesis carry a per-instance timestamp, so their hashes are instance-specific).
 *
 * **Reliability contract** (this path is credibility-critical: a false positive nukes a healthy
 * wallet on PreProd; a false negative leaves the dev staring at a ghost balance / error 171):
 *  - checkpoint un-queryable ([BlockHashLookup.Unavailable]) → [ChainStatus.UNKNOWN] → SKIP.
 *  - first sync for this address                            → [ChainStatus.FIRST_SYNC] → pin, no wipe.
 *  - checkpoint present, same hash                          → [ChainStatus.SAME] → untouched fast path.
 *  - checkpoint missing OR different hash, AND [wipeOnResetEnabled] → [ChainStatus.RESET] → wipe
 *                                       shielded + dust + unshielded (state, cursors, UTXO rows) + re-pin.
 *  - reset detected but NOT [wipeOnResetEnabled] (remote chain) → LOG only, no wipe, no re-pin. Real
 *                                       chains don't reset; a remote indexer that pruned or briefly
 *                                       rolled back the checkpoint must never nuke a healthy wallet.
 *
 * **Concurrency + crash safety:** a [Mutex] serializes the decision so concurrent syncs can't
 * double-wipe (and the checkpoint lookup runs OUTSIDE the lock so it doesn't serialize every sync on
 * one round-trip). Because the lookup is un-locked, the pin is re-read under the lock and the
 * decision is dropped if another sync already re-pinned in the meantime. The re-pin happens LAST,
 * after the wipes — so a crash mid-wipe leaves the OLD pin in place and the next sync re-detects the
 * reset and re-wipes (the wipes are idempotent), never a half-wiped wallet pinned to the new chain.
 *
 * Call [ensureFreshChain] at the START of every wallet sync entry point (shielded, dust,
 * unshielded). Whichever runs first triggers the wipe; the others then find their state gone and
 * run a full re-sync.
 */
class ChainResetGuard(
    private val indexerClient: IndexerClient,
    private val shieldedRepository: ShieldedRepository,
    private val dustRepository: DustRepository,
    private val syncStateManager: SyncStateManager,
    private val utxoManager: UtxoManager,
    /**
     * Whether a detected reset should actually WIPE. True only for the LOCAL dev chain (localnet),
     * where the indexer is local and devs reset frequently. False for remote chains (PreProd /
     * Preview): real chains don't reset, and a load-balanced, pruned, or briefly-rolled-back remote
     * indexer could momentarily fail the checkpoint lookup — wiping on that would nuke a HEALTHY
     * wallet. When false a mismatch is logged but never acted on (and the pin is left intact so a
     * transient anomaly self-corrects). NOTE: this MUST be gated on a true localnet signal, not on
     * `NetworkConfig.developmentMode` (which is also true on PreProd/Preview to allow the local
     * proof-server over HTTP) — see [com.midnight.kuira.core.network.NetworkConfig.isLocalDevChain].
     */
    private val wipeOnResetEnabled: Boolean,
) {
    private val mutex = Mutex()

    /**
     * Ensure the caches for [address] belong to the CURRENT chain.
     *
     * @return true iff a reset was detected and the caches were wiped (the caller's subsequent sync
     *   then runs from a fresh checkpoint). false on the fast path, first sync, or an unqueryable
     *   checkpoint.
     */
    suspend fun ensureFreshChain(address: String): Boolean {
        val pinned = syncStateManager.getPinnedChainCheckpoint(address)

        if (pinned == null) {
            // First sync for this address: pin the current tip so future syncs have a checkpoint.
            // A tip we can't read → skip pinning now; the next sync tries again (never a wipe).
            val tip = currentCheckpointOrNull() ?: return false
            mutex.withLock {
                // Re-check under the lock so two first-syncs don't both pin (harmless, but tidy).
                if (syncStateManager.getPinnedChainCheckpoint(address) == null) {
                    syncStateManager.setPinnedChainCheckpoint(address, tip)
                }
            }
            return false
        }

        // Look up the pinned checkpoint's height OUTSIDE the lock (a network round-trip — holding the
        // mutex across it would serialize every concurrent sync). Unavailable → UNKNOWN → skip.
        val lookup = indexerClient.getBlockHashAtHeight(pinned.height)

        return mutex.withLock {
            // Re-read the pin under the lock: a concurrent sync may have detected the reset and
            // re-pinned while our lookup was in flight. If the pin moved, that sync already handled
            // it — our now-stale lookup must not act.
            val current = syncStateManager.getPinnedChainCheckpoint(address)
            if (current == null || current != pinned) return@withLock false

            val status = checkpointStatus(pinnedHash = pinned.hash, lookup = lookup)
            when (resetAction(status, wipeOnResetEnabled)) {
                ResetAction.SKIP -> {
                    // A RESET that's gated off (remote chain) lands here too — log it so a genuine
                    // remote re-genesis is visible, but take NO action: no wipe, no re-pin, so a
                    // transient indexer anomaly self-corrects on the next correct read. (UNKNOWN/SAME
                    // are the silent fast paths.)
                    if (status == ChainStatus.RESET) {
                        Log.w(
                            TAG,
                            "Checkpoint mismatch on a remote chain for ${address.take(16)}… " +
                                "(pinned height ${pinned.height}) — NOT wiping (real chains don't " +
                                "reset; treating as an indexer anomaly)",
                        )
                    }
                    false
                }
                ResetAction.PIN -> {
                    // Unreachable here: FIRST_SYNC is handled above with no pin present. Kept for
                    // exhaustiveness — a stray PIN status is a no-op, never a wipe.
                    false
                }
                ResetAction.WIPE -> {
                    Log.w(
                        TAG,
                        "Chain reset for ${address.take(16)}… (checkpoint at height ${pinned.height} " +
                            "is gone or changed); wiping shielded + dust + unshielded caches",
                    )
                    shieldedRepository.deleteState(address)
                    dustRepository.deleteState(address)
                    syncStateManager.clearSyncState(address)
                    utxoManager.clearUtxos(address)
                    // Re-pin LAST (crash safety — see the class doc). Re-pin to the NEW chain's tip;
                    // if the tip is momentarily unreadable, leave the OLD pin so the next sync
                    // re-detects the reset and re-wipes (idempotent) rather than pinning nothing.
                    currentCheckpointOrNull()?.let {
                        syncStateManager.setPinnedChainCheckpoint(address, it)
                    }
                    true
                }
            }
        }
    }

    /** The current tip as a [ChainCheckpoint], or null if the tip can't be read (never throws). */
    private suspend fun currentCheckpointOrNull(): ChainCheckpoint? {
        return try {
            val tip = indexerClient.getCurrentBlockWithParams()
            ChainCheckpoint(height = tip.height, hash = tip.hash)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "ChainResetGuard"
    }
}

/** Chain-identity status — pure + exhaustive so the decision is unit-testable without side effects. */
internal enum class ChainStatus { UNKNOWN, FIRST_SYNC, SAME, RESET }

/**
 * Pure decision: given the [pinnedHash] the caches were built against and the [lookup] of that
 * pinned block's height on the chain now, what is the chain-identity status?
 *
 * A [BlockHashLookup.Unavailable] is ALWAYS [ChainStatus.UNKNOWN] (skip) — a transient indexer
 * failure must never be read as a reset. This is the heart of the no-false-positive guarantee.
 * (FIRST_SYNC is decided by the guard from the ABSENCE of a pin, not here.)
 */
internal fun checkpointStatus(pinnedHash: String, lookup: BlockHashLookup): ChainStatus = when (lookup) {
    is BlockHashLookup.Unavailable -> ChainStatus.UNKNOWN
    is BlockHashLookup.NotOnChain -> ChainStatus.RESET
    is BlockHashLookup.Found -> if (lookup.hash == pinnedHash) ChainStatus.SAME else ChainStatus.RESET
}

/** What [ChainResetGuard] does for a sync — pure + exhaustive so the full decision (incl. gating) is testable. */
internal enum class ResetAction { SKIP, PIN, WIPE }

/**
 * The action to take given the chain-identity [status] and whether wiping is allowed on this chain
 * ([wipeOnResetEnabled] — true only for local dev chains). A RESET only WIPEs when wiping is enabled;
 * on a remote chain it degrades to SKIP (log-only) so an anomalous indexer lookup can't nuke a
 * healthy wallet. UNKNOWN/SAME → SKIP; FIRST_SYNC → PIN.
 */
internal fun resetAction(status: ChainStatus, wipeOnResetEnabled: Boolean): ResetAction = when (status) {
    ChainStatus.UNKNOWN, ChainStatus.SAME -> ResetAction.SKIP
    ChainStatus.FIRST_SYNC -> ResetAction.PIN
    ChainStatus.RESET -> if (wipeOnResetEnabled) ResetAction.WIPE else ResetAction.SKIP
}
