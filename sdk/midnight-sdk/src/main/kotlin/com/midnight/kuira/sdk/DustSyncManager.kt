package com.midnight.kuira.sdk

import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dust state manager — keeps a single in-memory DustLocalState for the session.
 *
 * Serialize/deserialize of DustLocalState corrupts Merkle tree roots (SDK-001),
 * so the state is NEVER loaded from disk. On first call: full sync from genesis,
 * keep in memory. All subsequent calls reuse the same state. The node's historic
 * root set keeps old roots valid for 1+ hours.
 *
 * On error 170: [forceResync] does a fresh full sync from genesis.
 */
class DustSyncManager(
    private val dustRepository: DustRepository,
    private val nodeRpcClient: NodeRpcClient,
    private val walletAddress: String,
    private val dustSeed: ByteArray,
) {
    private val mutex = Mutex()
    private var state: DustLocalState? = null

    /**
     * Get a synced DustLocalState. First call does full sync (~60s on PREPROD).
     * All subsequent calls return the same in-memory state instantly.
     *
     * @param onSyncProgress Optional callback for streaming progress during full sync.
     */
    suspend fun ensureSynced(
        onSyncProgress: (suspend (eventsProcessed: Int, totalEvents: Int) -> Unit)? = null,
    ): DustLocalState = mutex.withLock {
        state?.let { return@withLock it }

        dustRepository.syncFromBlockchain(
            address = walletAddress,
            dustSeed = dustSeed,
            onProgress = onSyncProgress,
        )

        // Prefer the live state the sync just produced; otherwise rehydrate
        // from the persisted checkpoint. A cold process always has a null
        // in-memory state, so deleting + re-syncing from genesis here threw
        // away the checkpoint that syncFromBlockchain just delta-applied —
        // that was the "re-streams ~900k events on every launch / network
        // switch" bug. Load the checkpoint instead; only delete + full-resync
        // if there is genuinely none to recover.
        var freshState = dustRepository.getLastSyncedState()
            ?: dustRepository.loadState(walletAddress)

        if (freshState == null) {
            dustRepository.deleteState(walletAddress)
            dustRepository.syncFromBlockchain(
                address = walletAddress,
                dustSeed = dustSeed,
                onProgress = onSyncProgress,
            )
            freshState = dustRepository.getLastSyncedState()
                ?: throw IllegalStateException("No dust state after full sync. Is dust registered?")
        }

        state = freshState
        freshState
    }

    /** After submit: no-op. State stays in memory. */
    suspend fun invalidateMemo() {
        // Intentionally empty. The in-memory state is reused across transactions.
    }

    /**
     * On-demand incremental refresh (UI "refresh" affordance, between-tx
     * freshness): drop the in-memory memo and re-sync, which lands as a fast
     * DELTA on the persisted checkpoint. Unlike [forceResync] this does NOT
     * delete the checkpoint, so a routine refresh never triggers a full genesis
     * re-sync. [forceResync] stays reserved for error-170 recovery, where stale
     * roots require a clean rebuild from genesis.
     */
    suspend fun refreshIncremental(
        onSyncProgress: (suspend (eventsProcessed: Int, totalEvents: Int) -> Unit)? = null,
    ): DustLocalState {
        mutex.withLock {
            state?.close()
            state = null
        }
        return ensureSynced(onSyncProgress)
    }

    /** Force a completely fresh sync. Clears everything. For error 170 recovery. */
    suspend fun forceResync() = mutex.withLock {
        state?.close()
        state = null
        dustRepository.clearLastSyncedState()
        dustRepository.deleteState(walletAddress)
    }

    /** Release native resources. */
    fun close() {
        state?.close()
        state = null
    }
}
