package com.midnight.kuira.core.identity.passkey

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * Minimal CBOR parser for WebAuthn attestation objects and COSE keys.
 *
 * Only handles the CBOR types needed for passkey registration:
 * - Major type 0: Unsigned integer
 * - Major type 1: Negative integer
 * - Major type 2: Byte string
 * - Major type 3: Text string
 * - Major type 5: Map
 *
 * This is NOT a general-purpose CBOR library — it parses exactly what
 * WebAuthn attestation and COSE_Key structures require.
 *
 * Safety: all buffer reads are wrapped to convert [BufferUnderflowException]
 * into [AttestationParseException]. Length fields are bounds-checked against
 * remaining buffer capacity to prevent OOM from crafted payloads.
 */
internal object CborParser {

    /** Maximum allowed byte/text string length (1 MB). Prevents OOM from crafted payloads. */
    private const val MAX_STRING_LENGTH = 1_048_576

    /** Maximum allowed map entry count. Prevents OOM from crafted payloads. */
    private const val MAX_MAP_ENTRIES = 256

    /**
     * Extracts the `authData` byte string from a CBOR-encoded attestation object.
     */
    fun extractAuthData(attestationObject: ByteArray): ByteArray {
        return wrapBufferErrors("attestationObject") {
            val buffer = ByteBuffer.wrap(attestationObject)
            val map = readMap(buffer)

            val authData = map["authData"]
                ?: throw AttestationParseException("attestationObject missing 'authData' key")

            if (authData !is ByteArray) {
                throw AttestationParseException(
                    "Expected authData to be byte string, got ${authData::class.simpleName}"
                )
            }

            authData
        }
    }

    /**
     * Extracts P-256 x/y coordinates from a CBOR-encoded COSE_Key.
     */
    fun extractP256FromCoseKey(coseKeyBytes: ByteArray): P256PublicKey {
        return wrapBufferErrors("COSE key") {
            val buffer = ByteBuffer.wrap(coseKeyBytes)
            val map = readMap(buffer)

            val kty = map[1L]
            if (kty != 2L) {
                throw AttestationParseException("COSE key type is $kty, expected 2 (EC2)")
            }

            val crv = map[-1L]
            if (crv != 1L) {
                throw AttestationParseException("COSE curve is $crv, expected 1 (P-256)")
            }

            val x = map[-2L] as? ByteArray
                ?: throw AttestationParseException("COSE key missing x coordinate (-2)")
            val y = map[-3L] as? ByteArray
                ?: throw AttestationParseException("COSE key missing y coordinate (-3)")

            if (x.size != 32) {
                throw AttestationParseException("x coordinate must be 32 bytes, got ${x.size}")
            }
            if (y.size != 32) {
                throw AttestationParseException("y coordinate must be 32 bytes, got ${y.size}")
            }

            P256PublicKey(x = x, y = y)
        }
    }

    // --- CBOR decoding primitives ---

    private fun readMap(buffer: ByteBuffer): Map<Any, Any> {
        val initial = buffer.get().toInt() and 0xFF
        val majorType = initial shr 5
        if (majorType != 5) {
            throw AttestationParseException(
                "Expected CBOR map (major type 5), got major type $majorType"
            )
        }

        val count = readArgument(initial, buffer)
        if (count > MAX_MAP_ENTRIES) {
            throw AttestationParseException("CBOR map too large: $count entries (max $MAX_MAP_ENTRIES)")
        }

        val map = LinkedHashMap<Any, Any>(count.toInt())
        for (i in 0 until count) {
            val key = readValue(buffer)
            val value = readValue(buffer)
            map[key] = value
        }

        return map
    }

    private fun readValue(buffer: ByteBuffer): Any {
        val initial = buffer.get().toInt() and 0xFF
        val majorType = initial shr 5

        return when (majorType) {
            0 -> readArgument(initial, buffer)
            1 -> -1L - readArgument(initial, buffer)
            2 -> readByteString(initial, buffer)
            3 -> readTextString(initial, buffer)
            5 -> {
                val count = readArgument(initial, buffer)
                if (count > MAX_MAP_ENTRIES) {
                    throw AttestationParseException("CBOR nested map too large: $count entries")
                }
                val map = LinkedHashMap<Any, Any>(count.toInt())
                for (i in 0 until count) {
                    val key = readValue(buffer)
                    val value = readValue(buffer)
                    map[key] = value
                }
                map
            }
            else -> throw AttestationParseException("Unsupported CBOR major type: $majorType")
        }
    }

    private fun readArgument(initial: Int, buffer: ByteBuffer): Long {
        val additionalInfo = initial and 0x1F
        return when {
            additionalInfo < 24 -> additionalInfo.toLong()
            additionalInfo == 24 -> (buffer.get().toInt() and 0xFF).toLong()
            additionalInfo == 25 -> (buffer.short.toInt() and 0xFFFF).toLong()
            additionalInfo == 26 -> (buffer.int.toLong() and 0xFFFFFFFFL)
            additionalInfo == 27 -> buffer.long
            else -> throw AttestationParseException(
                "Unsupported CBOR additional info: $additionalInfo"
            )
        }
    }

    private fun readByteString(initial: Int, buffer: ByteBuffer): ByteArray {
        val length = readArgument(initial, buffer).toInt()
        if (length < 0 || length > MAX_STRING_LENGTH) {
            throw AttestationParseException("CBOR byte string length out of bounds: $length")
        }
        if (length > buffer.remaining()) {
            throw AttestationParseException(
                "CBOR byte string length ($length) exceeds remaining data (${buffer.remaining()})"
            )
        }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return bytes
    }

    private fun readTextString(initial: Int, buffer: ByteBuffer): String {
        val length = readArgument(initial, buffer).toInt()
        if (length < 0 || length > MAX_STRING_LENGTH) {
            throw AttestationParseException("CBOR text string length out of bounds: $length")
        }
        if (length > buffer.remaining()) {
            throw AttestationParseException(
                "CBOR text string length ($length) exceeds remaining data (${buffer.remaining()})"
            )
        }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Wraps buffer operations to convert underflow into AttestationParseException. */
    private inline fun <T> wrapBufferErrors(context: String, block: () -> T): T {
        return try {
            block()
        } catch (e: AttestationParseException) {
            throw e
        } catch (e: BufferUnderflowException) {
            throw AttestationParseException("Truncated CBOR data while parsing $context", e)
        }
    }
}
