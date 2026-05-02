package com.midnight.kuira.core.identity.did

import com.midnight.kuira.core.identity.util.toFixedByteArray
import org.bitcoinj.core.Base58
import java.security.interfaces.ECPublicKey

/**
 * Generates W3C `did:key` identifiers from P-256 public keys.
 *
 * The DID is derived from the root passkey's P-256 public key — one DID per user,
 * stable across all Midnight dApps. This is the sigil's identity facet.
 *
 * Format: `did:key:z<base58btc(varint(0x1200) || compressed_P256_pubkey)>`
 *
 * Where:
 * - `0x1200` is the multicodec identifier for P-256 public keys
 * - varint encoding of `0x1200` = `[0x80, 0x24]`
 * - compressed P-256 public key = 33 bytes (0x02/0x03 prefix + 32-byte x coordinate)
 * - base58btc = Bitcoin-style base58 encoding (multibase prefix `z`)
 *
 * References:
 * - did:key spec: https://w3c-ccg.github.io/did-method-key/
 * - Multicodec table: https://github.com/multiformats/multicodec
 * - Multibase: https://github.com/multiformats/multibase
 */
object DidKeyGenerator {

    /** Multicodec varint for P-256 public key (0x1200 varint-encoded). */
    private val P256_MULTICODEC_VARINT = byteArrayOf(0x80.toByte(), 0x24.toByte())

    private const val DID_KEY_PREFIX = "did:key:z"
    private const val COMPRESSED_P256_KEY_SIZE = 33

    /**
     * Generates a `did:key` from a compressed P-256 public key.
     *
     * @param compressedPublicKey 33-byte compressed P-256 public key (0x02/0x03 + x)
     * @return The DID string, e.g. `did:key:zDn...`
     * @throws IllegalArgumentException if key is not 33 bytes or has invalid prefix
     */
    fun fromCompressedP256(compressedPublicKey: ByteArray): String {
        require(compressedPublicKey.size == COMPRESSED_P256_KEY_SIZE) {
            "Compressed P-256 key must be $COMPRESSED_P256_KEY_SIZE bytes, got ${compressedPublicKey.size}"
        }
        val prefix = compressedPublicKey[0].toInt() and 0xFF
        require(prefix == 0x02 || prefix == 0x03) {
            "Compressed key must start with 0x02 or 0x03, got 0x${"%02x".format(prefix)}"
        }

        // multicodec varint + compressed key
        val payload = P256_MULTICODEC_VARINT + compressedPublicKey
        val encoded = Base58.encode(payload)
        return "$DID_KEY_PREFIX$encoded"
    }

    /**
     * Generates a `did:key` from uncompressed P-256 x/y coordinates.
     *
     * @param x 32-byte x coordinate
     * @param y 32-byte y coordinate
     * @return The DID string
     */
    fun fromP256Coordinates(x: ByteArray, y: ByteArray): String {
        require(x.size == 32) { "x coordinate must be 32 bytes, got ${x.size}" }
        require(y.size == 32) { "y coordinate must be 32 bytes, got ${y.size}" }

        val compressed = compressP256(x, y)
        return fromCompressedP256(compressed)
    }

    /**
     * Generates a `did:key` from a Java [ECPublicKey] (P-256).
     *
     * @param publicKey The EC public key (must be on the P-256 curve)
     * @return The DID string
     */
    fun fromECPublicKey(publicKey: ECPublicKey): String {
        val point = publicKey.w
        val x = point.affineX.toFixedByteArray(COORDINATE_SIZE)
        val y = point.affineY.toFixedByteArray(COORDINATE_SIZE)
        return fromP256Coordinates(x, y)
    }

    /**
     * Extracts the compressed P-256 public key from a `did:key` string.
     *
     * @param did The DID string (must start with `did:key:z`)
     * @return 33-byte compressed P-256 public key
     * @throws IllegalArgumentException if DID format is invalid
     */
    fun toCompressedP256(did: String): ByteArray {
        require(did.startsWith(DID_KEY_PREFIX)) {
            "DID must start with '$DID_KEY_PREFIX', got: ${did.take(20)}"
        }

        val encoded = did.removePrefix(DID_KEY_PREFIX)
        val payload = Base58.decode(encoded)

        require(payload.size >= P256_MULTICODEC_VARINT.size + COMPRESSED_P256_KEY_SIZE) {
            "Decoded payload too short: ${payload.size} bytes"
        }

        // Verify multicodec prefix
        val prefixMatch = payload[0] == P256_MULTICODEC_VARINT[0] &&
            payload[1] == P256_MULTICODEC_VARINT[1]
        require(prefixMatch) {
            "Expected P-256 multicodec prefix [0x80, 0x24], got [0x${"%02x".format(payload[0])}, 0x${"%02x".format(payload[1])}]"
        }

        return payload.copyOfRange(P256_MULTICODEC_VARINT.size, payload.size)
    }

    /**
     * Compresses a P-256 public key from x/y coordinates.
     *
     * @param x 32-byte x coordinate
     * @param y 32-byte y coordinate
     * @return 33-byte compressed key (0x02 if y is even, 0x03 if y is odd)
     */
    fun compressP256(x: ByteArray, y: ByteArray): ByteArray {
        val prefix: Byte = if (y.last().toInt() and 1 == 0) 0x02 else 0x03
        return byteArrayOf(prefix) + x
    }

    private const val COORDINATE_SIZE = 32
}
