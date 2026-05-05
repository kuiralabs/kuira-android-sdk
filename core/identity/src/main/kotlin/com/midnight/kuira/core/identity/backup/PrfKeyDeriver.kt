package com.midnight.kuira.core.identity.backup

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives an AES-256-GCM encryption key from PRF output using HKDF-SHA256.
 *
 * The PRF output is 32 bytes of high-entropy keying material from the
 * authenticator hardware. HKDF stretches it into a purpose-bound key.
 *
 * Privacy: the PRF output never leaves the device. It's computed inside
 * the authenticator and immediately fed into HKDF. The derived key is
 * used for encryption and then wiped from memory.
 *
 * Reference: RFC 5869 (HMAC-based Extract-and-Expand Key Derivation Function)
 */
object PrfKeyDeriver {

    private const val HASH_LEN = 32 // SHA-256 output length
    private const val HMAC_ALGORITHM = "HmacSHA256"

    /**
     * Derives a 32-byte AES-256 key from PRF output.
     *
     * @param prfOutput 32-byte PRF output from the authenticator
     * @param info Purpose string for domain separation (default: backup encryption)
     * @return 32-byte key suitable for AES-256-GCM
     */
    fun deriveKey(
        prfOutput: ByteArray,
        info: String = DEFAULT_INFO,
    ): ByteArray {
        require(prfOutput.size == PRF_OUTPUT_SIZE) {
            "PRF output must be $PRF_OUTPUT_SIZE bytes, got ${prfOutput.size}"
        }

        // HKDF-Extract: PRK = HMAC-SHA256(salt, IKM)
        // Salt is empty (all zeros) — PRF output is already high-entropy
        val salt = ByteArray(HASH_LEN)
        val prk = hmacSha256(key = salt, data = prfOutput)

        // HKDF-Expand: OKM = HMAC-SHA256(PRK, info || 0x01)
        // We only need 32 bytes (one HMAC block), so single iteration
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val expandInput = ByteArray(infoBytes.size + 1)
        System.arraycopy(infoBytes, 0, expandInput, 0, infoBytes.size)
        expandInput[expandInput.size - 1] = 0x01 // counter byte

        return hmacSha256(key = prk, data = expandInput)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data)
    }

    const val PRF_OUTPUT_SIZE = 32
    private const val DEFAULT_INFO = "kuira-backup-encryption-v1"
}
