package com.midnight.kuira.sdk.walletruntime

import android.util.Log
import com.midnight.kuira.core.identity.backup.BackupStorage
import com.midnight.kuira.core.identity.backup.DustBackupEncryptor
import com.midnight.kuira.core.identity.backup.DustBackupEntry
import com.midnight.kuira.core.identity.backup.DustCloudBundleCodec
import com.midnight.kuira.sdk.DustCloudBackup
import com.midnight.kuira.sdk.RestoredDustCheckpoint
import java.security.MessageDigest

/**
 * Per-address last-uploaded digest, so [DustCloudBackupCoordinator] can skip a
 * redundant upload (and the network round-trip) when a checkpoint is unchanged.
 * Production backs this with SharedPreferences; tests use an in-memory map.
 */
interface DustBackupDigestStore {
    fun get(address: String): String?
    fun put(address: String, digest: String)

    /** Drop all recorded digests — used when the cloud blob is deleted so a later
     *  re-enable re-uploads instead of the hash-guard skipping an "already-uploaded" checkpoint. */
    fun clear()
}

/**
 * [DustCloudBackup] over an injected [BackupStorage] (Drive in production) +
 * a pre-derived AES key ([com.midnight.kuira.core.identity.backup.SeedDerivedKeyDeriver]).
 *
 * The Drive/auth specifics live in the Hilt wiring; this class is pure logic —
 * encrypt/decrypt, the versioned multi-network bundle merge, and the
 * content-hash upload guard — so it's fully JVM-unit-testable with a fake
 * storage + digest store.
 *
 * Every operation is failure-tolerant by contract: [fetch] returns null on any
 * problem (→ caller falls back to genesis), and [upload] is invoked best-effort
 * (callers wrap it in `runCatching`).
 */
class DustCloudBackupCoordinator(
    private val storage: BackupStorage,
    private val encryptionKey: ByteArray,
    private val digestStore: DustBackupDigestStore,
) : DustCloudBackup {

    override suspend fun fetch(address: String): RestoredDustCheckpoint? {
        val blob = storage.retrieve()
        if (blob == null) {
            Log.i(TAG, "fetch: nothing in cloud storage")
            return null
        }
        Log.i(TAG, "fetch: retrieved ${blob.size}-byte blob, decrypting…")
        val plaintext = DustBackupEncryptor.decrypt(encryptionKey, blob)
        val entry = DustCloudBundleCodec.entryFor(plaintext, address)
        if (entry == null) {
            Log.w(TAG, "fetch: bundle has no entry for $address")
            return null
        }
        Log.i(TAG, "fetch: found entry for $address, lastEventId=${entry.lastEventId}")
        return RestoredDustCheckpoint(stateBytes = entry.stateBytes, lastEventId = entry.lastEventId)
    }

    override suspend fun upload(address: String, stateBytes: ByteArray, lastEventId: Long) {
        // Local hash guard — skip entirely (no network) when this address's
        // checkpoint is unchanged since the last successful upload.
        val digest = digest(address, lastEventId, stateBytes)
        if (digestStore.get(address) == digest) return

        // Merge into the existing remote bundle so other networks' entries
        // survive: drop this address's prior entry, append the new one.
        val existing = storage.retrieve()
            ?.let { runCatching { DustCloudBundleCodec.decode(DustBackupEncryptor.decrypt(encryptionKey, it)) }.getOrNull() }
            ?: emptyList()
        val merged = existing.filterNot { it.address == address } +
            DustBackupEntry(address = address, lastEventId = lastEventId, stateBytes = stateBytes)

        val blob = DustBackupEncryptor.encrypt(encryptionKey, DustCloudBundleCodec.encode(merged))
        storage.store(blob)
        // Record only after a confirmed store, so a failed upload retries next time.
        digestStore.put(address, digest)
    }

    override suspend fun clear() {
        // Delete the remote blob first, then drop the local digests so a later re-enable
        // re-uploads (the hash-guard won't skip an "unchanged" checkpoint whose blob is gone).
        storage.delete()
        digestStore.clear()
        Log.i(TAG, "clear: deleted cloud dust blob + cleared upload digests")
    }

    private fun digest(address: String, lastEventId: Long, stateBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(address.toByteArray(Charsets.UTF_8))
        md.update(lastEventId.toByte())
        md.update((lastEventId ushr 32).toByte())
        md.update(stateBytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "DustCloudBackup"
    }
}
