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

    /**
     * The source device could not read its own seed. Usually a declined biometric prompt, which is
     * a choice rather than a fault, so it is reported separately from a protocol failure — telling
     * someone the crypto broke when they simply dismissed Face ID sends them to re-run a ceremony
     * that was never wrong.
     */
    class SeedUnavailable(detail: String) : LinkException("Could not read this device's wallet: $detail")

    /**
     * The new device opened the payload but could not store it. The wallet is not on this device,
     * and the distinction matters: everything about the ceremony itself was correct, so the fix is
     * on this phone rather than back at the other one.
     */
    class StorageFailed(detail: String) : LinkException("Could not store the wallet on this device: $detail")
}
