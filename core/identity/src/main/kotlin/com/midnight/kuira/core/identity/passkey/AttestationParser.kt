package com.midnight.kuira.core.identity.passkey

import android.util.Base64
import com.midnight.kuira.core.identity.util.toFixedByteArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Parses WebAuthn registration responses to extract the P-256 public key.
 *
 * Two extraction paths, tried in order:
 * 1. **Level 3 `publicKey` field** — DER SubjectPublicKeyInfo, parsed via Java KeyFactory (preferred)
 * 2. **CBOR fallback** — attestationObject → authData → COSE key → x/y coordinates
 *
 * Google Password Manager uses `none` attestation by default, which simplifies parsing.
 */
object AttestationParser {

    /**
     * Extracts the P-256 public key coordinates from a WebAuthn registration response JSON.
     *
     * @param registrationResponseJson The full JSON from CreatePublicKeyCredentialResponse
     * @return [P256PublicKey] with x/y coordinates and compressed form
     * @throws AttestationParseException if the public key cannot be extracted
     */
    fun extractPublicKey(registrationResponseJson: String): P256PublicKey {
        val json = try {
            JSONObject(registrationResponseJson)
        } catch (e: Exception) {
            throw AttestationParseException("Invalid JSON: ${e.message}", e)
        }

        val response = json.optJSONObject("response")
            ?: throw AttestationParseException("Missing 'response' field in registration JSON")

        // Path 1: Level 3 publicKey field (DER-encoded SubjectPublicKeyInfo)
        val publicKeyB64 = response.optString("publicKey", "")
        if (publicKeyB64.isNotEmpty()) {
            try {
                return extractFromDer(decodeBase64Url(publicKeyB64))
            } catch (_: Exception) {
                // DER parsing failed — fall through to CBOR path
            }
        }

        // Path 2: CBOR fallback — parse attestationObject
        return extractFromAttestationObject(response)
    }

    /**
     * Extracts P-256 public key from DER-encoded SubjectPublicKeyInfo.
     */
    private fun extractFromDer(der: ByteArray): P256PublicKey {
        val keyFactory = KeyFactory.getInstance("EC")
        val publicKey = try {
            keyFactory.generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
        } catch (e: Exception) {
            throw AttestationParseException("Failed to parse DER public key: ${e.message}", e)
        }

        val point = publicKey.w
        val x = point.affineX.toFixedByteArray(COORDINATE_SIZE)
        val y = point.affineY.toFixedByteArray(COORDINATE_SIZE)

        return P256PublicKey(x = x, y = y)
    }

    /**
     * Extracts P-256 public key from the attestationObject via CBOR parsing.
     *
     * attestationObject (CBOR) → authData (bytes) → attestedCredentialData → COSE key
     */
    private fun extractFromAttestationObject(response: JSONObject): P256PublicKey {
        val attestationObjectB64 = response.optString("attestationObject", "")
        if (attestationObjectB64.isEmpty()) {
            throw AttestationParseException(
                "Neither 'publicKey' nor 'attestationObject' found in response"
            )
        }

        val attestationBytes = decodeBase64Url(attestationObjectB64)
        val authData = CborParser.extractAuthData(attestationBytes)
        return extractFromAuthData(authData)
    }

    /**
     * Extracts the COSE public key from authenticator data.
     *
     * authData format:
     * - rpIdHash: 32 bytes
     * - flags: 1 byte (bit 6 = attestedCredentialData present)
     * - signCount: 4 bytes (big-endian)
     * - attestedCredentialData (if flags bit 6 set):
     *   - AAGUID: 16 bytes
     *   - credentialIdLength: 2 bytes (big-endian)
     *   - credentialId: credentialIdLength bytes
     *   - credentialPublicKey: remaining bytes (CBOR COSE_Key)
     */
    private fun extractFromAuthData(authData: ByteArray): P256PublicKey {
        if (authData.size < AUTH_DATA_MIN_SIZE) {
            throw AttestationParseException(
                "authData too short: ${authData.size} bytes (minimum $AUTH_DATA_MIN_SIZE)"
            )
        }

        val flags = authData[RP_ID_HASH_SIZE].toInt() and 0xFF
        val hasAttestedCredData = (flags and ATTESTED_CRED_DATA_FLAG) != 0
        if (!hasAttestedCredData) {
            throw AttestationParseException("authData does not contain attested credential data")
        }

        var offset = AUTH_DATA_FIXED_SIZE // rpIdHash(32) + flags(1) + signCount(4)

        // Skip AAGUID (16 bytes)
        offset += AAGUID_SIZE

        // Read credentialId length (2 bytes, big-endian)
        if (offset + 2 > authData.size) {
            throw AttestationParseException("authData truncated at credentialId length")
        }
        val credIdLen = ByteBuffer.wrap(authData, offset, 2).short.toInt() and 0xFFFF
        offset += 2

        // Skip credentialId
        offset += credIdLen
        if (offset >= authData.size) {
            throw AttestationParseException("authData truncated at COSE key")
        }

        // Remaining bytes are the COSE_Key (CBOR)
        val coseKeyBytes = authData.copyOfRange(offset, authData.size)
        return CborParser.extractP256FromCoseKey(coseKeyBytes)
    }

    private fun decodeBase64Url(input: String): ByteArray {
        return Base64.decode(input, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private const val RP_ID_HASH_SIZE = 32
    private const val AUTH_DATA_FIXED_SIZE = 37 // rpIdHash(32) + flags(1) + signCount(4)
    private const val AUTH_DATA_MIN_SIZE = AUTH_DATA_FIXED_SIZE
    private const val AAGUID_SIZE = 16
    private const val ATTESTED_CRED_DATA_FLAG = 0x40
    private const val COORDINATE_SIZE = 32
}

/**
 * P-256 public key with both coordinate and compressed representations.
 */
class P256PublicKey(
    val x: ByteArray,
    val y: ByteArray,
) {
    init {
        require(x.size == 32) { "x must be 32 bytes" }
        require(y.size == 32) { "y must be 32 bytes" }
    }

    /** 33-byte compressed form: 0x02/0x03 prefix + x coordinate. */
    val compressed: ByteArray by lazy {
        val prefix: Byte = if (y.last().toInt() and 1 == 0) 0x02 else 0x03
        byteArrayOf(prefix) + x
    }

    /** 65-byte uncompressed form: 0x04 + x + y. */
    val uncompressed: ByteArray by lazy {
        byteArrayOf(0x04) + x + y
    }

    /** Hex string of the compressed key. */
    fun compressedHex(): String = compressed.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is P256PublicKey) return false
        return x.contentEquals(other.x) && y.contentEquals(other.y)
    }

    override fun hashCode(): Int = x.contentHashCode() * 31 + y.contentHashCode()
}

class AttestationParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
