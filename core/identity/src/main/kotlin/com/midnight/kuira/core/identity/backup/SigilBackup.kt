package com.midnight.kuira.core.identity.backup

import android.app.Activity
import com.midnight.kuira.core.identity.passkey.PasskeyException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Coordinates PRF-encrypted backup and restore of the wallet seed.
 *
 * Backup flow:
 * 1. Authenticate passkey with PRF salt → 32-byte deterministic secret
 * 2. HKDF(PRF output) → AES-256-GCM key
 * 3. Encrypt seed (fixed-size, padded) → 525-byte blob
 * 4. Store blob in BackupStorage (Block Store by default)
 *
 * Restore flow (new device):
 * 1. Passkey syncs via Google Password Manager (automatic)
 * 2. Authenticate passkey with same PRF salt → same 32-byte secret
 * 3. HKDF → same AES key → decrypt blob → seed recovered
 *
 * Security: all intermediate key material (PRF output, AES key, plaintext)
 * is wiped in finally blocks immediately after use.
 *
 * Privacy: Google sees only a 525-byte opaque blob. The PRF output never
 * leaves the device. Double-encrypted (our AES-GCM + Block Store E2E).
 */
class SigilBackup(
    private val passkeyManager: PasskeyManager,
    private val storage: BackupStorage,
) {
    /**
     * Encrypts the seed with PRF-derived key and stores it.
     *
     * @param activity Activity for the passkey biometric prompt
     * @param entropy 32-byte BIP-39 mnemonic entropy
     * @param bip39Seed 64-byte BIP-39 seed
     * @throws PasskeyException if passkey auth fails
     * @throws BackupException if PRF is not available or storage fails
     */
    suspend fun backup(
        activity: Activity,
        entropy: ByteArray,
        bip39Seed: ByteArray,
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
            val blob = BackupEncryptor.encrypt(
                entropy = entropy,
                bip39Seed = bip39Seed,
                aesKey = aesKey,
            )
            storage.store(blob)
        } finally {
            aesKey?.fill(0)
            prfOutput.fill(0)
        }
    }

    /**
     * Retrieves and decrypts the seed from cloud backup.
     *
     * @param activity Activity for the passkey biometric prompt
     * @return [DecryptedBackup] with entropy and BIP-39 seed. Caller MUST call [DecryptedBackup.wipe].
     * @throws PasskeyException if passkey auth fails
     * @throws BackupException if no backup exists, PRF unavailable, or decryption fails
     */
    suspend fun restore(activity: Activity): DecryptedBackup {
        val blob = storage.retrieve()
            ?: throw BackupException("No backup found in storage")

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
            return BackupEncryptor.decrypt(blob = blob, aesKey = aesKey)
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

    /** Delete the cloud backup. */
    suspend fun deleteBackup() = storage.delete()

    private fun generateChallenge(): ByteArray {
        val challenge = ByteArray(CHALLENGE_SIZE)
        SecureRandom().nextBytes(challenge)
        return challenge
    }

    companion object {
        private const val CHALLENGE_SIZE = 32

        /** Purpose-bound salt: SHA-256("kuira:backup:v1"). Deterministic, public. */
        val BACKUP_SALT: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest("kuira:backup:v1".toByteArray(Charsets.UTF_8))
    }
}

class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
