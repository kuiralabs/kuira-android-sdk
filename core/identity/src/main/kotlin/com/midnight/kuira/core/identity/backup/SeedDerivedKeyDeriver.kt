package com.midnight.kuira.core.identity.backup

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the AES-256 key for the **dust cloud backup** from the wallet's dust
 * seed via HKDF-SHA256.
 *
 * Unlike [PrfKeyDeriver] (which keys the passkey/seed backup from a PRF output so
 * it works *without* the seed), the dust backup is only ever read on a device
 * that has **already** restored the seed — so we can derive the key straight
 * from the in-hand `dustSeed` with no extra biometric/PRF ceremony.
 *
 * Domain-separated from the wallet's dust *spending* use of the same seed (and
 * from SEED_SALT / SIGIL_SALT / BACKUP_SALT) by a dedicated HKDF salt.
 *
 * Reference: RFC 5869.
 */
object SeedDerivedKeyDeriver {

    private const val HASH_LEN = 32
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /** Domain-separation salt — distinct from every other key use in the app. */
    private val DUST_BACKUP_SALT: ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("kuira:dust-backup:v1".toByteArray(Charsets.UTF_8))

    private const val INFO = "kuira:dust-backup-encryption:v1"

    /**
     * Derives a 32-byte AES-256 key from dust-seed keying material.
     *
     * @param ikm input keying material (the wallet's `dustSeed`, 32 bytes).
     * @return 32-byte key for [DustBackupEncryptor].
     */
    fun deriveDustBackupKey(ikm: ByteArray): ByteArray {
        require(ikm.isNotEmpty()) { "IKM must be non-empty" }

        // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
        val prk = hmacSha256(key = DUST_BACKUP_SALT, data = ikm)

        // HKDF-Expand: 32 bytes = one block → info || 0x01
        val infoBytes = INFO.toByteArray(Charsets.UTF_8)
        val expandInput = ByteArray(infoBytes.size + 1)
        System.arraycopy(infoBytes, 0, expandInput, 0, infoBytes.size)
        expandInput[expandInput.size - 1] = 0x01

        return hmacSha256(key = prk, data = expandInput)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }
}
