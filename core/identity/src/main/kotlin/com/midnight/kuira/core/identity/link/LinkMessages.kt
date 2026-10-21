package com.midnight.kuira.core.identity.link

import java.security.KeyPair
import java.security.interfaces.ECPublicKey

/**
 * What the new device shows first: its ephemeral public key.
 *
 * The source device scans this. The scan is the authentication — an attacker cannot substitute a
 * key into a camera pointed at a phone in the user's own hand without being physically present.
 */
class LinkRequest private constructor(val publicKey: ByteArray) {

    /** The bytes to render as a QR. */
    fun encode(): ByteArray = byteArrayOf(LinkWireFormat.VERSION) + publicKey

    internal fun recipientKey(): ECPublicKey =
        try {
            Hpke.deserializePublicKey(publicKey)
        } catch (e: Exception) {
            throw LinkException.Malformed("public key is not a point on P-256")
        }

    companion object {
        /** Parses a scanned QR payload. */
        fun decode(bytes: ByteArray): LinkRequest {
            if (bytes.size != 1 + LinkWireFormat.P256_POINT_SIZE) {
                throw LinkException.Malformed("expected ${1 + LinkWireFormat.P256_POINT_SIZE} bytes, got ${bytes.size}")
            }
            if (bytes[0] != LinkWireFormat.VERSION) throw LinkException.UnsupportedVersion(bytes[0])
            val key = bytes.copyOfRange(1, bytes.size)
            // Reject a well-formed-looking payload that is not actually on the curve, so the failure
            // lands here on the scan rather than deeper inside HPKE.
            try {
                Hpke.deserializePublicKey(key)
            } catch (e: Exception) {
                throw LinkException.Malformed("public key is not a point on P-256")
            }
            return LinkRequest(key)
        }

        internal fun ofValidated(publicKey: ByteArray) = LinkRequest(publicKey)
    }
}

/**
 * What the source device shows back: the seed, sealed to the new device's key.
 *
 * Only the matching ephemeral private key opens it, so this QR is safe to photograph. It is not
 * safe to *trust* — a swapped ciphertext still decodes as a structurally valid response, which is
 * what the verification code exists to catch.
 */
class LinkResponse internal constructor(
    val encapsulatedKey: ByteArray,
    val ciphertext: ByteArray,
) {

    /** The bytes to render as a QR. */
    fun encode(): ByteArray = byteArrayOf(LinkWireFormat.VERSION) + encapsulatedKey + ciphertext

    companion object {
        /** Parses a scanned QR payload. */
        fun decode(bytes: ByteArray): LinkResponse {
            if (bytes.size != LinkWireFormat.SEALED_SIZE) {
                throw LinkException.Malformed("expected ${LinkWireFormat.SEALED_SIZE} bytes, got ${bytes.size}")
            }
            if (bytes[0] != LinkWireFormat.VERSION) throw LinkException.UnsupportedVersion(bytes[0])
            val split = 1 + LinkWireFormat.P256_POINT_SIZE
            return LinkResponse(
                encapsulatedKey = bytes.copyOfRange(1, split),
                ciphertext = bytes.copyOfRange(split, bytes.size),
            )
        }
    }
}

/**
 * The new device's ephemeral private key, held only for the length of one ceremony.
 *
 * Single-use and never persisted: that is what gives the ceremony forward secrecy, so a QR
 * photographed today cannot be opened by compromising the phone later.
 */
class LinkSecret internal constructor(internal val keyPair: KeyPair)
