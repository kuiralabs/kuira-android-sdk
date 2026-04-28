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

        var freshState = dustRepository.getLastSyncedState()

        if (freshState == null) {
            // Delta sync found no new events — lastSyncedState wasn't set.
            // Force a full sync from genesis to get an in-memory state.
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
