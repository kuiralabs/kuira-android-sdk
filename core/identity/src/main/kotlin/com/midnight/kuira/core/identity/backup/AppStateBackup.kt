package com.midnight.kuira.core.identity.backup

import android.app.Activity
import com.midnight.kuira.core.identity.passkey.PasskeyException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Coordinates PRF-encrypted backup and restore of **app-specific
 * state** (e.g. Kicks's MatchStore witnesses + commitments, BBoard
 * drafts, future game/agent state).
 *
 * **Why "AppState" and not "Sigil":** the wallet seed and the sigil
 * DID both derive from the user's passkey via PRF (see
 * `WalletSeedSource` and `SigilIdentityProvider`). Neither needs to
 * travel through a Block Store blob to survive a new device — the
 * passkey carries them. What *can't* be re-derived from the passkey
 * is per-app state that's accumulated mid-session: witness keys
 * paired to a deployed contract, draft data, in-flight game moves.
 * That's what this class persists.
 *
 * **Flow:**
 *  1. Authenticate passkey with `BACKUP_SALT` → 32-byte PRF output.
 *  2. HKDF(PRF output) → AES-256-GCM key.
 *  3. Encrypt `appMetadata` → versioned blob.
 *  4. Store blob in [BackupStorage] (Block Store by default).
 *
 * **Restore (new device or sign-in flow):**
 *  1. Passkey syncs via Google Password Manager.
 *  2. Authenticate with the same salt → same PRF output → same AES key.
 *  3. Decrypt blob → [RestoredAppState.appMetadata] handed to the host.
 *
 * **Security:** intermediate key material (PRF output, AES key,
 * plaintext) is wiped in finally blocks immediately after use.
 * Google sees only an opaque encrypted blob.
 *
 * **Versioning:** the blob's leading byte is the schema version. v2
 * is the post-PRF format (this class); v1 blobs from before the
 * AppStateBackup extraction are silently rejected as "no backup" —
 * the seed and sigil fields they carried are redundant now, and the
 * appMetadata they carried isn't decryptable under the v2 schema.
 */
class AppStateBackup(
    private val passkeyManager: PasskeyManager,
    private val storage: BackupStorage,
) {

    /**
     * Encrypts [appMetadata] and writes the blob to storage.
     *
     * Passing `null` (or an empty array) is allowed — the blob then
     * contains a zero-length payload. That's a useful "user has a
     * sigil but no app state to back up" signal, e.g. for a wallet-
     * only app that wants the backup-exists indicator without
     * shipping any per-app state.
     *
     * @throws PasskeyException if passkey auth fails
     * @throws BackupException if PRF is not available or storage fails
     */
    suspend fun backup(
        activity: Activity,
        appMetadata: ByteArray? = null,
    ) {
        val challenge = generateChallenge()
        val prfResult = passkeyManager.authenticateWithPrf(
            activity = activity,
            challenge = challenge,
            prfSalt = BACKUP_SALT,
        )
        val prfOutput = prfResult.prfOutput
            ?: throw BackupException("PRF not available — authenticator does not support PRF extension")

        var aesKey: ByteArray? = null
        try {
            aesKey = PrfKeyDeriver.deriveKey(prfOutput)
            val blob = AppStateBackupEncryptor.encrypt(
                aesKey = aesKey,
                appMetadata = appMetadata ?: ByteArray(0),
            )
            storage.store(blob)
        } finally {
            aesKey?.fill(0)
            prfOutput.fill(0)
        }
    }

    /**
     * Reads + decrypts the app-state blob from storage.
     *
     * @throws PasskeyException if passkey auth fails
     * @throws BackupException if no backup exists, PRF unavailable, or
     *   decryption fails (the latter happens when the blob is from a
     *   different passkey or is corrupted; v1 legacy blobs are
     *   surfaced as "no backup found").
     */
    suspend fun restore(activity: Activity): RestoredAppState {
        val blob = storage.retrieve()
            ?: throw BackupException("No backup found in storage")

        // v1 blobs (pre-AppStateBackup) carry a different schema we
        // can't decode and don't want to. Treat as "no backup" so the
        // UI flows into the fresh-sigil path instead of erroring.
        if (blob.isNotEmpty() && blob[0] == LEGACY_V1_VERSION) {
            throw BackupException("Legacy v1 backup detected — no recoverable app state in this schema")
        }

        val challenge = generateChallenge()
        val prfResult = passkeyManager.authenticateWithPrf(
            activity = activity,
            challenge = challenge,
            prfSalt = BACKUP_SALT,
        )
        val prfOutput = prfResult.prfOutput
            ?: throw BackupException("PRF not available — authenticator does not support PRF extension")

        var aesKey: ByteArray? = null
        try {
            aesKey = PrfKeyDeriver.deriveKey(prfOutput)
            val appMetadata = AppStateBackupEncryptor.decrypt(blob = blob, aesKey = aesKey)
            return RestoredAppState(appMetadata = appMetadata)
        } catch (e: BackupDecryptionException) {
            throw BackupException("Restore failed: ${e.message}", e)
        } finally {
            aesKey?.fill(0)
            prfOutput.fill(0)
        }
    }

    /** Whether a backup exists in storage. */
    suspend fun hasBackup(): Boolean = storage.retrieve() != null

    /** Whether the storage backend is available. */
    suspend fun isStorageAvailable(): Boolean = storage.isAvailable()

    /** Delete the backup. */
    suspend fun deleteBackup() = storage.delete()

    private fun generateChallenge(): ByteArray {
        val challenge = ByteArray(CHALLENGE_SIZE)
        SecureRandom().nextBytes(challenge)
        return challenge
    }

    companion object {
        private const val CHALLENGE_SIZE = 32

        /**
         * Schema version of the pre-PRF SigilBackup blob format
         * (`[version=0x01][IV][ENC(entropy||seed||metadataLen||metadata||padding)]`).
         * v1 blobs are detected on restore and rejected — they carried
         * a fixed seed-plus-sigil schema that's redundant post-PRF,
         * and any appMetadata they held isn't decryptable under v2.
         */
        private const val LEGACY_V1_VERSION: Byte = 0x01

        /**
         * Purpose-bound salt: SHA-256("kuira:backup:v1"). Deterministic,
         * public. Domain-separated from `SeedDeriver.SEED_SALT` and
         * `SeedDeriver.SIGIL_SALT` — three independent secrets from
         * the same passkey credential.
         */
        val BACKUP_SALT: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest("kuira:backup:v1".toByteArray(Charsets.UTF_8))
    }
}

/**
 * Restored app state — just the metadata bytes the host wrote on a
 * previous backup. Caller MUST call [wipe] after handing the bytes
 * to the host's `AppDataBackupProvider`.
 */
class RestoredAppState(
    /** App-specific state restored from backup. Empty array = no metadata was stored. */
    val appMetadata: ByteArray,
) {
    fun wipe() {
        appMetadata.fill(0)
    }
}

class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
