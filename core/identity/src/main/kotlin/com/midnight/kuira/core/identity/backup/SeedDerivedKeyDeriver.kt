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

    /** Domain-separation salt for the APP-STATE backup key — distinct from dust. */
    private val APP_STATE_BACKUP_SALT: ByteArray =
        MessageDigest.getInstance("SHA-256")
            .digest("kuira:appstate-backup:v1".toByteArray(Charsets.UTF_8))

    private const val APP_STATE_INFO = "kuira:appstate-backup-encryption:v1"

    /**
     * Derives a 32-byte AES-256 key from dust-seed keying material.
     *
     * @param ikm input keying material (the wallet's `dustSeed`, 32 bytes).
     * @return 32-byte key for [DustBackupEncryptor].
     */
    fun deriveDustBackupKey(ikm: ByteArray): ByteArray =
        hkdf32(salt = DUST_BACKUP_SALT, ikm = ikm, info = INFO)

    /**
     * Derives a 32-byte AES-256 key for the APP-STATE cloud backup from the
     * wallet seed via HKDF-SHA256.
     *
     * Like [deriveDustBackupKey], this needs only the in-hand seed — no
     * per-backup PRF/biometric — so app-state backups can run automatically in
     * the background. Domain-separated from the dust-backup key (different salt +
     * info) and from every other seed use.
     *
     * @param ikm input keying material (the wallet seed).
     * @return 32-byte key for the app-state backup encryptor.
     */
    fun deriveAppStateBackupKey(ikm: ByteArray): ByteArray =
        hkdf32(salt = APP_STATE_BACKUP_SALT, ikm = ikm, info = APP_STATE_INFO)

    /** HKDF-SHA256 (RFC 5869) producing exactly one [HASH_LEN]-byte block. */
    private fun hkdf32(salt: ByteArray, ikm: ByteArray, info: String): ByteArray {
        require(ikm.isNotEmpty()) { "IKM must be non-empty" }

        // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
        val prk = hmacSha256(key = salt, data = ikm)

        // HKDF-Expand: HASH_LEN bytes = one block → info || 0x01
        val infoBytes = info.toByteArray(Charsets.UTF_8)
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
