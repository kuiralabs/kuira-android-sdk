package com.midnight.kuira.core.identity.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for the `AppStateBackup` blob.
 *
 * **Schema (v2 — post-PRF):**
 * ```
 * Blob:      [version=0x02 : 1 byte] [IV : 12 bytes] [ciphertext+GCMtag : variable]
 * Plaintext: [metadataLen : 2 bytes BE] [metadata : 0..N bytes]
 * ```
 *
 * Variable length — appMetadata is what differs between dApps (Kicks's
 * witness state ≠ BBoard's drafts ≠ future agent state). The pre-PRF v1
 * schema padded plaintext to a fixed 496 bytes to hide whether a seed
 * was present. Post-PRF the blob doesn't carry a seed (PRF derives it
 * deterministically from the passkey), so the only thing the size
 * could leak is "this user has app state of approximately N bytes,"
 * which the package name already discloses anyway.
 *
 * All intermediate plaintext is wiped in a `finally`.
 */
object AppStateBackupEncryptor {

    /** Schema version. Bumped to 0x02 to distinguish from the legacy seed-bearing v1 blob. */
    private const val VERSION: Byte = 0x02

    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_SIZE = 32
    private const val METADATA_LEN_SIZE = 2
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /**
     * Upper bound on `appMetadata` — large enough to hold Kicks's
     * full match-state witness bundle (~1 KB worst case) with room
     * to grow, small enough that Block Store's 4 KB entry limit
     * isn't a concern.
     */
    const val MAX_METADATA_SIZE = 2048

    private val secureRandom = SecureRandom()

    /**
     * Encrypts [appMetadata] into the v2 blob.
     *
     * @param aesKey 32-byte AES-256 key (from `PrfKeyDeriver`).
     * @param appMetadata The bytes to encrypt. Zero-length array is
     *   allowed — the blob then encodes `metadataLen=0`.
     */
    fun encrypt(
        aesKey: ByteArray,
        appMetadata: ByteArray,
    ): ByteArray {
        require(aesKey.size == AES_KEY_SIZE) { "AES key must be $AES_KEY_SIZE bytes" }
        require(appMetadata.size <= MAX_METADATA_SIZE) {
            "appMetadata must be at most $MAX_METADATA_SIZE bytes, got ${appMetadata.size}"
        }

        val plaintext = ByteArray(METADATA_LEN_SIZE + appMetadata.size)
        try {
            // Length prefix, big-endian.
            plaintext[0] = (appMetadata.size shr 8).toByte()
            plaintext[1] = (appMetadata.size and 0xFF).toByte()
            if (appMetadata.isNotEmpty()) {
                System.arraycopy(appMetadata, 0, plaintext, METADATA_LEN_SIZE, appMetadata.size)
            }

            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val keySpec = SecretKeySpec(aesKey, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)

            // Assemble blob: version || IV || ciphertext(includes GCM tag).
            val blob = ByteArray(1 + IV_SIZE + ciphertext.size)
            blob[0] = VERSION
            System.arraycopy(iv, 0, blob, 1, IV_SIZE)
            System.arraycopy(ciphertext, 0, blob, 1 + IV_SIZE, ciphertext.size)
            return blob
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Decrypts a v2 blob and returns the embedded `appMetadata`
     * bytes (zero-length array when the blob stored no metadata).
     *
     * @throws BackupDecryptionException if the blob is malformed,
     *   not v2, or fails AEAD verification.
     */
    fun decrypt(
        blob: ByteArray,
        aesKey: ByteArray,
    ): ByteArray {
        require(aesKey.size == AES_KEY_SIZE) { "AES key must be $AES_KEY_SIZE bytes" }

        if (blob.size < 1 + IV_SIZE + METADATA_LEN_SIZE + GCM_TAG_BITS / 8) {
            throw BackupDecryptionException("Blob too small: ${blob.size} bytes")
        }

        val version = blob[0]
        if (version != VERSION) {
            throw BackupDecryptionException("Unsupported backup version: $version (expected $VERSION)")
        }

        val iv = blob.copyOfRange(1, 1 + IV_SIZE)
        val ciphertext = blob.copyOfRange(1 + IV_SIZE, blob.size)

        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val keySpec = SecretKeySpec(aesKey, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BackupDecryptionException(
                "Decryption failed — wrong key or corrupted blob: ${e.message}", e
            )
        }

        try {
            if (plaintext.size < METADATA_LEN_SIZE) {
                throw BackupDecryptionException("Decrypted plaintext too small: ${plaintext.size}")
            }
            val metaLen = ((plaintext[0].toInt() and 0xFF) shl 8) or
                (plaintext[1].toInt() and 0xFF)
            if (metaLen > MAX_METADATA_SIZE || METADATA_LEN_SIZE + metaLen > plaintext.size) {
                throw BackupDecryptionException("Metadata length out of range: $metaLen")
            }
            return plaintext.copyOfRange(METADATA_LEN_SIZE, METADATA_LEN_SIZE + metaLen)
        } finally {
            plaintext.fill(0)
        }
    }
}

class BackupDecryptionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
