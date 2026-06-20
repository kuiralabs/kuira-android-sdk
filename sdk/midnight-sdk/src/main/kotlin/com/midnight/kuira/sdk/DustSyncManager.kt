package com.midnight.kuira.sdk

import android.util.Log
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.sync.ChainResetGuard
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Dust state manager — keeps a single in-memory DustLocalState for the session.
 *
 * On the first sync the persisted checkpoint (if any) is reused for a fast
 * delta; only a genuinely-empty start falls back to a full genesis sync.
 * Serialize/deserialize is root-lossless (proven by the compact-engine
 * serialize_deserialize_reproduces_root test), so the checkpoint is
 * authoritative across process restarts. On error 170: [forceResync] does a
 * fresh full sync from genesis.
 *
 * On a cold start with no local checkpoint, an optional [cloudBackupSource]
 * (e.g. Google Drive, cross-device) is consulted to seed one before genesis —
 * see [maybeSeedCheckpointFromCloud]. Null (the default) preserves today's
 * behavior exactly.
 */
class DustSyncManager(
    private val dustRepository: DustRepository,
    private val nodeRpcClient: NodeRpcClient,
    private val walletAddress: String,
    private val dustSeed: ByteArray,
    private val cloudBackupSource: DustCloudBackupSource? = null,
    private val chainResetGuard: ChainResetGuard? = null,
) {
    private val mutex = Mutex()
    private var state: DustLocalState? = null

    /**
     * Set by [forceResync] so the next [ensureSynced] rebuilds from genesis instead
     * of restoring the cloud checkpoint. Error-170 recovery means the local root is
     * stale; re-seeding the same (often older) cloud blob can't fix that — only a
     * genuine genesis sync can.
     */
    private var skipCloudSeedOnce = false

    /**
     * Get a synced DustLocalState. First call does full sync (~60s on PREPROD).
     * All subsequent calls return the same in-memory state instantly.
     *
     * @param onSyncProgress Optional callback for streaming progress during full sync.
     */
    suspend fun ensureSynced(
        onSyncProgress: (suspend (eventsProcessed: Int, totalEvents: Int) -> Unit)? = null,
    ): DustLocalState = mutex.withLock {
        // If the chain was reset under us (localnet docker restart), the guard wiped the persisted
        // dust caches; also drop the in-memory state so we don't hand back the stale (old-chain)
        // DustLocalState via the cache short-circuit below — instead fall through to a fresh sync.
        if (chainResetGuard?.ensureFreshChain(walletAddress) == true) {
            state = null
        }
        state?.let { return@withLock it }

        // Cold start with no local checkpoint → try to seed one from the cloud
        // (cross-device restore) so the sync below is a fast delta, not genesis.
        // Skipped exactly once after forceResync (error-170 recovery), so recovery
        // rebuilds from genesis rather than restoring the stale cloud checkpoint.
        if (skipCloudSeedOnce) {
            skipCloudSeedOnce = false
            Log.i(TAG, "cloud-restore: skipped — error-170 recovery rebuilds from genesis")
        } else {
            maybeSeedCheckpointFromCloud()
        }

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

    /**
     * On a cold start with no local checkpoint, fetch one from [cloudBackupSource]
     * and write it to [DustRepository] so the subsequent [DustRepository.syncFromBlockchain]
     * takes the delta branch. No-op when: no cloud source is wired, a local
     * checkpoint already exists (local wins), the cloud has nothing for this
     * address, or any step fails (→ clean genesis fallback, never a crash).
     */
    private suspend fun maybeSeedCheckpointFromCloud() {
        val source = cloudBackupSource
        if (source == null) {
            Log.i(TAG, "cloud-restore: no cloud source wired — skipping")
            return
        }
        // Local checkpoint present (BOTH halves) → don't overwrite it with the
        // cloud copy. hasCheckpoint reads both keys from one snapshot, so a
        // half-written local checkpoint counts as absent and we re-seed cleanly.
        if (dustRepository.hasCheckpoint(walletAddress)) {
            Log.i(TAG, "cloud-restore: local checkpoint present — using it (no cloud fetch)")
            return
        }
        Log.i(TAG, "cloud-restore: no local checkpoint — trying cloud for $walletAddress")
        val restored = runCatching { source.fetch(walletAddress) }
            .onFailure { Log.w(TAG, "cloud-restore: fetch failed: ${it.message}", it) }
            .getOrNull()
        if (restored == null) {
            Log.w(TAG, "cloud-restore: no checkpoint in cloud for $walletAddress — genesis fallback")
            return
        }
        val state = DustLocalState.deserialize(restored.stateBytes)
        if (state == null) {
            Log.w(TAG, "cloud-restore: deserialize of cloud checkpoint failed — genesis fallback")
            return
        }
        try {
            // One atomic write — the seeded state and its cursor can never land
            // torn (which would make the very first delta fail NonLinearInsertion).
            dustRepository.saveCheckpoint(walletAddress, state, restored.lastEventId)
            Log.i(TAG, "cloud-restore: SEEDED from cloud, lastEventId=${restored.lastEventId} — next sync is a delta")
        } finally {
            state.close()
        }
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
        // Recovery must rebuild from genesis — the next ensureSynced must NOT
        // restore the (stale) cloud checkpoint that put us in this state.
        skipCloudSeedOnce = true
    }

    /** Release native resources. */
    fun close() {
        state?.close()
        state = null
    }

    private companion object {
        const val TAG = "DustSyncManager"
    }
}
