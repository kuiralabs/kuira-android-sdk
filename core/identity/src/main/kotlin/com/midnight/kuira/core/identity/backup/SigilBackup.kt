package com.midnight.kuira.core.identity.backup

import android.app.Activity
import com.midnight.kuira.core.identity.passkey.PasskeyException
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import org.json.JSONObject
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
     * Encrypts the seed, sigil identity, and optional app metadata, then stores it.
     *
     * The sigil identity (DID, credential ID, public key) is stored inside the
     * encrypted blob so it can be recovered on a new device. The `get` ceremony
     * (authenticate) doesn't return the public key — only `create` does — so
     * the DID must be persisted in the backup.
     *
     * @param activity Activity for the passkey biometric prompt
     * @param entropy 32-byte BIP-39 mnemonic entropy
     * @param bip39Seed 64-byte BIP-39 seed
     * @param did The user's DID (did:key:z...)
     * @param credentialId Credential ID from passkey registration
     * @param publicKeyHex Compressed P-256 public key hex
     * @param appMetadata Optional app state (game data, contract state). Included in the
     *                    backup alongside the sigil identity.
     * @throws PasskeyException if passkey auth fails
     * @throws BackupException if PRF is not available or storage fails
     */
    suspend fun backup(
        activity: Activity,
        entropy: ByteArray,
        bip39Seed: ByteArray,
        did: String,
        credentialId: String,
        publicKeyHex: String,
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

        // Build metadata: sigil identity + optional app state
        val metadata = buildBackupMetadata(did, credentialId, publicKeyHex, appMetadata)

        var aesKey: ByteArray? = null
        try {
            aesKey = PrfKeyDeriver.deriveKey(prfOutput)
            val blob = BackupEncryptor.encrypt(
                entropy = entropy,
                bip39Seed = bip39Seed,
                aesKey = aesKey,
                metadata = metadata,
            )
            storage.store(blob)
        } finally {
            aesKey?.fill(0)
            prfOutput.fill(0)
        }
    }

    /**
     * Retrieves and decrypts the backup from cloud storage.
     *
     * Returns the seed, sigil identity (DID, credential ID, public key),
     * and any app metadata. This is everything needed to fully restore
     * the sigil on a new device.
     *
     * @param activity Activity for the passkey biometric prompt
     * @return [RestoredSigil] with seed, identity, and app metadata
     * @throws PasskeyException if passkey auth fails
     * @throws BackupException if no backup exists, PRF unavailable, or decryption fails
     */
    suspend fun restore(activity: Activity): RestoredSigil {
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
            val decrypted = BackupEncryptor.decrypt(blob = blob, aesKey = aesKey)
            return parseRestoredSigil(decrypted)
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

    // ── Metadata serialization ──

    private fun buildBackupMetadata(
        did: String,
        credentialId: String,
        publicKeyHex: String,
        appMetadata: ByteArray?,
    ): ByteArray {
        val json = JSONObject().apply {
            put("did", did)
            put("credentialId", credentialId)
            put("publicKeyHex", publicKeyHex)
            if (appMetadata != null) {
                put("appState", android.util.Base64.encodeToString(
                    appMetadata,
                    android.util.Base64.NO_WRAP,
                ))
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun parseRestoredSigil(decrypted: DecryptedBackup): RestoredSigil {
        val metaBytes = decrypted.metadata
        if (metaBytes == null) {
            return RestoredSigil(
                entropy = decrypted.entropy,
                bip39Seed = decrypted.bip39Seed,
                did = null,
                credentialId = null,
                publicKeyHex = null,
                appMetadata = null,
            )
        }

        return try {
            val json = JSONObject(String(metaBytes, Charsets.UTF_8))
            val appStateB64 = json.optString("appState", "")
            val appMetadata = if (appStateB64.isNotEmpty()) {
                android.util.Base64.decode(appStateB64, android.util.Base64.NO_WRAP)
            } else null

            RestoredSigil(
                entropy = decrypted.entropy,
                bip39Seed = decrypted.bip39Seed,
                did = json.optString("did", null),
                credentialId = json.optString("credentialId", null),
                publicKeyHex = json.optString("publicKeyHex", null),
                appMetadata = appMetadata,
            )
        } catch (e: Exception) {
            // Metadata is unparseable — return seed without identity
            RestoredSigil(
                entropy = decrypted.entropy,
                bip39Seed = decrypted.bip39Seed,
                did = null,
                credentialId = null,
                publicKeyHex = null,
                appMetadata = null,
            )
        }
    }

    companion object {
        private const val CHALLENGE_SIZE = 32

        /** Purpose-bound salt: SHA-256("kuira:backup:v1"). Deterministic, public. */
        val BACKUP_SALT: ByteArray = MessageDigest.getInstance("SHA-256")
            .digest("kuira:backup:v1".toByteArray(Charsets.UTF_8))
    }
}

/**
 * Full restored sigil — seed + identity + app state.
 * Everything needed to fully reconstruct the sigil on a new device.
 * Caller MUST call [wipe] after use.
 */
class RestoredSigil(
    val entropy: ByteArray,
    val bip39Seed: ByteArray,
    /** User's DID (did:key:z...). Null if backup predates identity storage. */
    val did: String?,
    /** Passkey credential ID. Null if backup predates identity storage. */
    val credentialId: String?,
    /** Compressed P-256 public key hex. Null if backup predates identity storage. */
    val publicKeyHex: String?,
    /** App-specific state (game data, etc.). Null if none was backed up. */
    val appMetadata: ByteArray?,
) {
    fun wipe() {
        entropy.fill(0)
        bip39Seed.fill(0)
        appMetadata?.fill(0)
    }
}

class BackupException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Thrown by seed-derivation paths that require the user to have a
 * forged passkey. The wallet panel converts this to a UI gate
 * (`WalletStatus.SigilRequired`); other callers can surface a
 * "forge sigil first" affordance however they choose.
 */
class SigilRequiredException(
    message: String = "No sigil found — forge a passkey first",
) : Exception(message)
