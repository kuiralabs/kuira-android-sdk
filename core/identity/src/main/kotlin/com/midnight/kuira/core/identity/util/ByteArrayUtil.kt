package com.midnight.kuira.core.identity.util

import java.math.BigInteger

/**
 * Converts a [BigInteger] to a fixed-size unsigned byte array,
 * padding with leading zeros or stripping the sign byte as needed.
 *
 * BigInteger.toByteArray() may include a leading zero byte for positive
 * numbers when the MSB is set, or return fewer bytes than expected.
 * This normalizes to exactly [size] bytes.
 *
 * Used by DID derivation and attestation parsing to handle EC point coordinates.
 */
internal fun BigInteger.toFixedByteArray(size: Int): ByteArray {
    val bytes = toByteArray()
    return when {
        bytes.size == size + 1 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, bytes.size)
        bytes.size == size -> bytes
        bytes.size < size -> ByteArray(size - bytes.size) + bytes
        else -> throw IllegalArgumentException(
            "BigInteger value too large for $size bytes (got ${bytes.size})"
        )
    }
}
