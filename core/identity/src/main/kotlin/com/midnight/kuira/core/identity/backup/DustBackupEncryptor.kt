package com.midnight.kuira.core.identity.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption for the dust cloud-backup bundle.
 *
 * Same construction as [AppStateBackupEncryptor] (`version || IV(12) ||
 * ciphertext+GCMtag`) but **size-agnostic**: the dust state is ~500 KB, far
 * past that encryptor's 2 KB metadata cap, and the bundle ([DustCloudBundleCodec])
 * carries its own internal lengths — so this encryptor just seals/opens an
 * arbitrary byte payload with no length prefix or cap. Kept separate so the
 * load-bearing passkey-backup encryptor is untouched.
 *
 * Throws [BackupDecryptionException] (shared with [AppStateBackupEncryptor]) on
 * a malformed blob or AEAD verification failure.
 */
object DustBackupEncryptor {

    private const val VERSION: Byte = 0x01
    private const val IV_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private const val AES_KEY_SIZE = 32
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val secureRandom = SecureRandom()

    /** Seals [plaintext] (any size) under [aesKey]. */
    fun encrypt(aesKey: ByteArray, plaintext: ByteArray): ByteArray {
        require(aesKey.size == AES_KEY_SIZE) { "AES key must be $AES_KEY_SIZE bytes" }

        val iv = ByteArray(IV_SIZE)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        val blob = ByteArray(1 + IV_SIZE + ciphertext.size)
        blob[0] = VERSION
        System.arraycopy(iv, 0, blob, 1, IV_SIZE)
        System.arraycopy(ciphertext, 0, blob, 1 + IV_SIZE, ciphertext.size)
        return blob
    }

    /** Opens a blob produced by [encrypt]. */
    fun decrypt(aesKey: ByteArray, blob: ByteArray): ByteArray {
        require(aesKey.size == AES_KEY_SIZE) { "AES key must be $AES_KEY_SIZE bytes" }
        if (blob.size < 1 + IV_SIZE + GCM_TAG_BITS / 8) {
            throw BackupDecryptionException("Dust blob too small: ${blob.size} bytes")
        }
        if (blob[0] != VERSION) {
            throw BackupDecryptionException("Unsupported dust backup version: ${blob[0]} (expected $VERSION)")
        }
        val iv = blob.copyOfRange(1, 1 + IV_SIZE)
        val ciphertext = blob.copyOfRange(1 + IV_SIZE, blob.size)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BackupDecryptionException("Dust decryption failed — wrong key or corrupted blob: ${e.message}", e)
        }
    }
}
