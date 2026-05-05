package com.midnight.kuira.core.identity.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts the wallet seed into a fixed-size blob.
 *
 * Privacy design:
 * - Fixed 496-byte plaintext (padded regardless of content) — no size oracle
 * - AES-256-GCM with random IV per backup — semantic security
 * - Version byte for forward compatibility
 * - All intermediate plaintext wiped in finally blocks
 *
 * Blob format (after encryption):
 * ```
 * [version: 1 byte] [IV: 12 bytes] [ciphertext + GCM tag: 512 bytes]
 * Total: 525 bytes (fixed)
 * ```
 *
 * Plaintext format (before encryption):
 * ```
 * [version: 1 byte] [entropy: 32 bytes] [bip39Seed: 64 bytes]
 * [metadataLen: 2 bytes BE] [metadata: 0..MAX_METADATA bytes] [padding to 496]
 * Total: 496 bytes (fixed)
 * ```
 *
 * Metadata is arbitrary app state (game choices, session data, contract
 * private state) that's expensive to store on-chain. Up to 395 bytes.
 */
object BackupEncryptor {

    private const val VERSION: Byte = 0x01
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_SIZE = 32

    /** Fixed plaintext size — padded to prevent size-based inference. */
    private const val PLAINTEXT_SIZE = 496

    /** Fixed blob size: version(1) + IV(12) + ciphertext(496) + GCM tag(16) = 525. */
    const val BLOB_SIZE = 1 + IV_SIZE + PLAINTEXT_SIZE + GCM_TAG_BITS / 8

    private const val ENTROPY_SIZE = 32
    private const val SEED_SIZE = 64
    private const val METADATA_LEN_SIZE = 2
    private const val HEADER_SIZE = 1 + ENTROPY_SIZE + SEED_SIZE + METADATA_LEN_SIZE // 99
    /** Max metadata: 496 - 99 = 397 bytes. */
    const val MAX_METADATA_SIZE = PLAINTEXT_SIZE - HEADER_SIZE
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    /**
     * Encrypts seed material and optional metadata into a fixed-size blob.
     *
     * @param entropy 32-byte BIP-39 mnemonic entropy
     * @param bip39Seed 64-byte BIP-39 seed
     * @param aesKey 32-byte AES-256 key (from PrfKeyDeriver)
     * @param metadata Optional app state (game data, contract private state, etc.).
     *                 Max [MAX_METADATA_SIZE] bytes (397). Null or empty = no metadata.
     * @return 525-byte encrypted blob (always fixed size regardless of metadata)
     */
    fun encrypt(
        entropy: ByteArray,
        bip39Seed: ByteArray,
        aesKey: ByteArray,
        metadata: ByteArray? = null,
    ): ByteArray {
        require(entropy.size == ENTROPY_SIZE) { "Entropy must be $ENTROPY_SIZE bytes" }
        require(bip39Seed.size == SEED_SIZE) { "BIP-39 seed must be $SEED_SIZE bytes" }
        require(aesKey.size == AES_KEY_SIZE) { "AES key must be $AES_KEY_SIZE bytes" }

        val metaBytes = metadata ?: ByteArray(0)
        require(metaBytes.size <= MAX_METADATA_SIZE) {
            "Metadata must be at most $MAX_METADATA_SIZE bytes, got ${metaBytes.size}"
        }

        val plaintext = ByteArray(PLAINTEXT_SIZE)
        try {
            var offset = 0

            // Version
            plaintext[offset] = VERSION
            offset += 1

            // Seed material
            System.arraycopy(entropy, 0, plaintext, offset, ENTROPY_SIZE)
            offset += ENTROPY_SIZE
            System.arraycopy(bip39Seed, 0, plaintext, offset, SEED_SIZE)
            offset += SEED_SIZE

            // Metadata length (2 bytes, big-endian)
            val metaLen = metaBytes.size
            plaintext[offset] = (metaLen shr 8).toByte()
            plaintext[offset + 1] = (metaLen and 0xFF).toByte()
            offset += METADATA_LEN_SIZE

            // Metadata content
            if (metaLen > 0) {
                System.arraycopy(metaBytes, 0, plaintext, offset, metaLen)
            }
            // Remaining bytes stay zero (padding to fixed size)

            // Encrypt with random IV
            val iv = ByteArray(IV_SIZE)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val keySpec = SecretKeySpec(aesKey, "AES")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            val ciphertext = cipher.doFinal(plaintext)

            // Assemble blob: version + IV + ciphertext (includes GCM tag)
            val blob = ByteArray(BLOB_SIZE)
            blob[0] = VERSION
            System.arraycopy(iv, 0, blob, 1, IV_SIZE)
            System.arraycopy(ciphertext, 0, blob, 1 + IV_SIZE, ciphertext.size)

            return blob
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Decrypts a backup blob and returns the seed material.
     *
     * @param blob 525-byte encrypted blob
     * @param aesKey 32-byte AES-256 key (from PrfKeyDeriver)
     * @return [DecryptedBackup] with entropy and BIP-39 seed
     * @throws BackupDecryptionException if the blob is malformed or decryption fails
     */
    fun decrypt(
        blob: ByteArray,
        aesKey: ByteArray,
    ): DecryptedBackup {
        require(aesKey.size == AES_KEY_SIZE) { "AES key must be $AES_KEY_SIZE bytes" }

        if (blob.size != BLOB_SIZE) {
            throw BackupDecryptionException(
                "Invalid blob size: expected $BLOB_SIZE bytes, got ${blob.size}"
            )
        }

        val version = blob[0]
        if (version != VERSION) {
            throw BackupDecryptionException(
                "Unsupported backup version: $version (expected $VERSION)"
            )
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
            if (plaintext.size != PLAINTEXT_SIZE) {
                throw BackupDecryptionException(
                    "Decrypted plaintext has wrong size: ${plaintext.size}"
                )
            }

            val ptVersion = plaintext[0]
            if (ptVersion != VERSION) {
                throw BackupDecryptionException(
                    "Plaintext version mismatch: $ptVersion (expected $VERSION)"
                )
            }

            var offset = 1 // skip version
            val entropy = plaintext.copyOfRange(offset, offset + ENTROPY_SIZE)
            offset += ENTROPY_SIZE
            val seed = plaintext.copyOfRange(offset, offset + SEED_SIZE)
            offset += SEED_SIZE

            // Read metadata length (2 bytes, big-endian)
            val metaLen = ((plaintext[offset].toInt() and 0xFF) shl 8) or
                (plaintext[offset + 1].toInt() and 0xFF)
            offset += METADATA_LEN_SIZE

            val metadata = if (metaLen > 0 && metaLen <= MAX_METADATA_SIZE) {
                plaintext.copyOfRange(offset, offset + metaLen)
            } else {
                null
            }

            return DecryptedBackup(entropy = entropy, bip39Seed = seed, metadata = metadata)
        } finally {
            plaintext.fill(0)
        }
    }
}

/**
 * Decrypted backup contents — seed material + optional app metadata.
 * Caller MUST call [wipe] after use.
 */
class DecryptedBackup(
    val entropy: ByteArray,
    val bip39Seed: ByteArray,
    /** App-specific state restored from backup. Null if no metadata was stored. */
    val metadata: ByteArray?,
) {
    fun wipe() {
        entropy.fill(0)
        bip39Seed.fill(0)
        metadata?.fill(0)
    }
}

class BackupDecryptionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
