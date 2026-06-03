package com.midnight.kuira.sdk

/**
 * A restored dust checkpoint: enough to seed [DustSyncManager] so the next sync
 * is a fast delta instead of a genesis replay.
 *
 * @param stateBytes serialized [com.midnight.kuira.core.crypto.dust.DustLocalState]
 *   (the lossless `serialize()` output)
 * @param lastEventId the last dust event id applied to that state
 */
data class RestoredDustCheckpoint(
    val stateBytes: ByteArray,
    val lastEventId: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestoredDustCheckpoint) return false
        return lastEventId == other.lastEventId && stateBytes.contentEquals(other.stateBytes)
    }

    override fun hashCode(): Int = 31 * lastEventId.hashCode() + stateBytes.contentHashCode()
}

/**
 * Optional cross-device source of a dust checkpoint (e.g. Google Drive). The
 * concrete implementation lives in `sdk:wallet-runtime` (it needs Drive + the
 * backup crypto); `core:indexer` and this module stay transport-agnostic.
 *
 * [DustSyncManager] consults this once, on a cold start with no local checkpoint,
 * before falling back to a genesis sync. Any failure must surface as null/throw
 * and degrade cleanly to genesis — never block or crash a working wallet.
 */
fun interface DustCloudBackupSource {
    /** The cloud checkpoint for [address], or null if none / unavailable. */
    suspend fun fetch(address: String): RestoredDustCheckpoint?
}

/**
 * Full cross-device dust backup — adds the upload side to [DustCloudBackupSource].
 * One implementation serves both views: [DustSyncManager] consumes only `fetch`,
 * [MidnightWallet] also drives `upload`.
 */
interface DustCloudBackup : DustCloudBackupSource {
    /**
     * Snapshot this address's checkpoint into the (multi-network) cloud bundle,
     * preserving other networks' entries. Best-effort + idempotent: a content
     * hash skips re-upload when nothing changed. Callers wrap in `runCatching`
     * so a backup failure never affects wallet operations.
     */
    suspend fun upload(address: String, stateBytes: ByteArray, lastEventId: Long)
}
