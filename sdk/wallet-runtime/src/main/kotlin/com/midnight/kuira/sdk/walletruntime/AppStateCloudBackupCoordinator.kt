package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.core.identity.backup.AppStateBackupEncryptor
import com.midnight.kuira.core.identity.backup.BackupStorage
import com.midnight.kuira.sdk.AppStateCloudBackup
import java.security.MessageDigest

/**
 * Single last-uploaded digest, so [AppStateCloudBackupCoordinator] can skip a
 * redundant upload (and the network round-trip) when the app state is unchanged.
 * One blob per sigil → one digest (no per-address keying, unlike dust).
 * Production backs this with SharedPreferences; tests use an in-memory holder.
 */
interface AppStateBackupDigestStore {
    fun get(): String?
    fun put(digest: String)
}

/**
 * [AppStateCloudBackup] over an injected [BackupStorage] + a pre-derived AES key
 * (`SeedDerivedKeyDeriver.deriveAppStateBackupKey`).
 *
 * Pure logic — encrypt/decrypt the single app-state blob + a content-hash upload
 * guard — so it's fully JVM-unit-testable with a fake storage + digest store.
 * Unlike the manual `AppStateBackup`, this needs NO passkey assertion per call
 * (the key is seed-derived), which is what makes automatic background backup
 * possible.
 *
 * Failure-tolerant by contract: [fetchAppState] returns null on any problem;
 * [uploadAppState] is best-effort (callers wrap it in `runCatching`).
 */
class AppStateCloudBackupCoordinator(
    private val storage: BackupStorage,
    private val encryptionKey: ByteArray,
    private val digestStore: AppStateBackupDigestStore,
) : AppStateCloudBackup {

    // SHA-256 of the encryption key, mixed into the upload digest so a SEED/key
    // change (e.g. a re-forge) forces a re-upload instead of a stale-skip — a
    // blob from the old key would be undecryptable under the new one. One-way,
    // so the persisted digest never exposes the key. (Dust gets this for free
    // via its per-address digest; app state has no address, so it's explicit.)
    private val keyFingerprint: ByteArray =
        MessageDigest.getInstance("SHA-256").digest(encryptionKey)

    override suspend fun fetchAppState(): ByteArray? {
        val blob = storage.retrieve() ?: return null
        return runCatching {
            AppStateBackupEncryptor.decrypt(blob = blob, aesKey = encryptionKey)
        }.getOrNull()
    }

    override suspend fun uploadAppState(appMetadata: ByteArray) {
        // Local hash guard — skip entirely (no network) when unchanged since the
        // last successful upload.
        val digest = digest(appMetadata)
        if (digestStore.get() == digest) return

        val blob = AppStateBackupEncryptor.encrypt(aesKey = encryptionKey, appMetadata = appMetadata)
        storage.store(blob)
        // Record only after a confirmed store, so a failed upload retries next time.
        digestStore.put(digest)
    }

    private fun digest(appMetadata: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(keyFingerprint) // key-aware: a seed change forces a re-upload
        md.update(appMetadata)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
