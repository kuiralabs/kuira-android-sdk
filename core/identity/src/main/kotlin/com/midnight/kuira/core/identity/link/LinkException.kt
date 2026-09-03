package com.midnight.kuira.core.identity.link

/**
 * Why a device link failed.
 *
 * Deliberately coarse. A link failure is shown to someone standing next to their other phone, and
 * the useful distinctions are "scan it again" versus "start over" — not which byte was wrong. Finer
 * detail would also hand an attacker an oracle for probing a substituted QR.
 */
sealed class LinkException(message: String) : Exception(message) {

    /** The scanned bytes are not a Kuira link QR, or are truncated. */
    class Malformed(detail: String) : LinkException("Not a Kuira link code: $detail")

    /** A link QR from a different protocol version. */
    class UnsupportedVersion(val version: Byte) : LinkException("Unsupported link version: $version")

    /** The entropy handed over is not the 32 bytes this version transfers. */
    class UnsupportedEntropySize(val size: Int) : LinkException("Link transfers 32 bytes, got $size")

    /** The sealed payload did not open: wrong recipient key, or the ciphertext was altered. */
    class SealFailed(detail: String) : LinkException("Could not open the sealed wallet: $detail")
}
