package com.midnight.kuira.core.indexer.sync

import android.util.Log
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
 * and keeps the OLD balance (the 30,001-NIGHT ghost). Block height and IDs both restart at 0, so
 * they can't be the discriminator; the one value stable within a chain but DIFFERENT across one is
 * the **genesis block hash**. This guard pins it and, on a mismatch, wipes all three caches.
 *
 * **Reliability contract** (this path is credibility-critical: a false positive nukes a healthy
 * wallet on PreProd; a false negative leaves the dev staring at a ghost balance):
 *  - genesis-hash query fails / null  → [ChainStatus.UNKNOWN] → SKIP (never treated as a reset).
 *  - first sync for this address      → [ChainStatus.FIRST_SYNC] → pin it, no wipe.
 *  - pinned == current                → [ChainStatus.SAME] → untouched fast path.
 *  - pinned != current AND [wipeOnResetEnabled] → [ChainStatus.RESET] → wipe shielded + dust +
 *                                       unshielded (state, cursors, UTXO rows) + re-pin.
 *  - pinned != current AND NOT [wipeOnResetEnabled] (remote chain) → LOG only, no wipe, no re-pin.
 *                                       Real chains don't reset, and a remote indexer could return
 *                                       an anomalous height-0 hash — we wipe ONLY for localnet.
 *
 * **Concurrency + crash safety:** a [Mutex] serializes the decision so concurrent syncs can't
 * double-wipe (and the genesis query runs OUTSIDE the lock so it doesn't serialize every sync on
 * one round-trip). The re-pin happens LAST, after the wipes — so a crash mid-wipe leaves the OLD
 * pin in place and the next sync re-detects the reset and re-wipes (the wipes are idempotent),
 * never a half-wiped wallet pinned to the new chain.
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
     * Whether a detected reset should actually WIPE. True only for local dev chains (localnet),
     * where the indexer is local and devs reset frequently. False for remote chains (PreProd /
     * Preview): real chains don't reset, and a load-balanced or pruned remote indexer could return
     * an anomalous height-0 hash — wiping on that would nuke a HEALTHY wallet. When false a mismatch
     * is logged but never acted on (and the pin is left intact so a transient anomaly self-corrects).
     */
    private val wipeOnResetEnabled: Boolean,
) {
    private val mutex = Mutex()

    /**
     * Ensure the caches for [address] belong to the CURRENT chain.
     *
     * @return true iff a reset was detected and the caches were wiped (the caller's subsequent sync
     *   then runs from genesis). false on the fast path, first sync, or an unqueryable genesis.
     */
    suspend fun ensureFreshChain(address: String): Boolean {
        // Query OUTSIDE the lock: it's a network round-trip, and holding the mutex across it would
        // serialize every concurrent sync on it. A failure returns null → UNKNOWN → skip.
        val current = indexerClient.getGenesisBlockHash()
        return mutex.withLock {
            val pinned = syncStateManager.getPinnedGenesisHash(address)
            val status = chainStatus(current = current, pinned = pinned)
            when (resetAction(status, wipeOnResetEnabled)) {
                ResetAction.SKIP -> {
                    // A RESET that's gated off (remote chain) lands here too — log it so a genuine
                    // remote re-genesis is visible, but take NO action: no wipe, no re-pin, so a
                    // transient indexer anomaly self-corrects on the next correct read. (UNKNOWN/SAME
                    // are the silent fast paths.) Real chains don't reset, and a remote, possibly
                    // load-balanced or pruned indexer could return an anomalous height-0 hash — a
                    // wipe there would nuke a HEALTHY wallet, so we only wipe on the dev chain.
                    if (status == ChainStatus.RESET) {
                        Log.w(
                            TAG,
                            "Genesis mismatch on a remote chain for ${address.take(16)}… (pinned " +
                                "${pinned?.take(12)}… vs ${current?.take(12)}…) — NOT wiping " +
                                "(real chains don't reset; treating as an indexer anomaly)",
                        )
                    }
                    false
                }
                ResetAction.PIN -> {
                    // FIRST_SYNC ⇒ current != null (chainStatus returns UNKNOWN for a null current).
                    current?.let { syncStateManager.setPinnedGenesisHash(address, it) }
                    false
                }
                ResetAction.WIPE -> {
                    Log.w(
                        TAG,
                        "Chain reset for ${address.take(16)}… (genesis ${pinned?.take(12)}… → " +
                            "${current?.take(12)}…); wiping shielded + dust + unshielded caches",
                    )
                    shieldedRepository.deleteState(address)
                    dustRepository.deleteState(address)
                    syncStateManager.clearSyncState(address)
                    utxoManager.clearUtxos(address)
                    // Re-pin LAST (crash safety — see the class doc).
                    current?.let { syncStateManager.setPinnedGenesisHash(address, it) }
                    true
                }
            }
        }
    }

    private companion object {
        const val TAG = "ChainResetGuard"
    }
}

/** Chain-identity status — pure + exhaustive so the decision is unit-testable without side effects. */
internal enum class ChainStatus { UNKNOWN, FIRST_SYNC, SAME, RESET }

/**
 * Pure decision: given the live genesis hash [current] (null = unqueryable) and the [pinned] hash
 * the caches were built against (null = never pinned), what should happen?
 *
 * A null [current] is ALWAYS [ChainStatus.UNKNOWN] (skip) — a transient indexer failure must never
 * be read as a reset. This ordering is the heart of the no-false-positive guarantee.
 */
internal fun chainStatus(current: String?, pinned: String?): ChainStatus = when {
    current == null -> ChainStatus.UNKNOWN
    pinned == null -> ChainStatus.FIRST_SYNC
    pinned == current -> ChainStatus.SAME
    else -> ChainStatus.RESET
}

/** What [ChainResetGuard] does for a sync — pure + exhaustive so the full decision (incl. gating) is testable. */
internal enum class ResetAction { SKIP, PIN, WIPE }

/**
 * The action to take given the chain-identity [status] and whether wiping is allowed on this chain
 * ([wipeOnResetEnabled] — true only for local dev chains). A RESET only WIPEs when wiping is enabled;
 * on a remote chain it degrades to SKIP (log-only) so an anomalous indexer hash can't nuke a healthy
 * wallet. UNKNOWN/SAME → SKIP; FIRST_SYNC → PIN.
 */
internal fun resetAction(status: ChainStatus, wipeOnResetEnabled: Boolean): ResetAction = when (status) {
    ChainStatus.UNKNOWN, ChainStatus.SAME -> ResetAction.SKIP
    ChainStatus.FIRST_SYNC -> ResetAction.PIN
    ChainStatus.RESET -> if (wipeOnResetEnabled) ResetAction.WIPE else ResetAction.SKIP
}
