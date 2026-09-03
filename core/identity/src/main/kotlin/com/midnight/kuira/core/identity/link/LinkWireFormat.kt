package com.midnight.kuira.core.identity.link


/**
 * The bytes that cross between two phones, and the constants that pin them.
 *
 * Every value here is shared with the iOS `KuiraLink` module — an iOS source device seals for an
 * Android new device and the reverse — so anything not fixed in one place is somewhere the two
 * implementations can silently disagree. `CrossPlatformLinkVectorTest` holds them against blobs
 * iOS actually produced.
 */
internal object LinkWireFormat {

    /** First byte of both QRs, so a future change is a clean rejection rather than a parse failure. */
    const val VERSION: Byte = 0x01

    /**
     * HPKE domain separation. Kuira-specific and deliberately not borrowed from any other
     * implementation — it is what stops a sealed blob from another protocol opening here.
     */
    val INFO = "kuira:link-device:v1".toByteArray(Charsets.UTF_8)

    /** Domain separation for the verification code, distinct from [INFO] so the two cannot collide. */
    val VERIFICATION_INFO = "kuira:link-verify:v1".toByteArray(Charsets.UTF_8)

    /** An uncompressed P-256 point: `0x04 || x(32) || y(32)`. */
    const val P256_POINT_SIZE = 65

    /**
     * Only the 32-byte entropy travels; the receiver re-derives the mnemonic and the 64-byte seed.
     * Shorter BIP-39 entropies are not a v1 case, and admitting them would make the payload length
     * variable for no gain.
     */
    const val ENTROPY_SIZE = 32

    /** AES-256-GCM tag. */
    const val TAG_SIZE = 16

    /** `version || enc(65) || ciphertext(32) || tag(16)` — 114 bytes, well inside a static QR. */
    const val SEALED_SIZE = 1 + P256_POINT_SIZE + ENTROPY_SIZE + TAG_SIZE

    /** Six digits, so the modulus is fixed rather than derived from a formatting string. */
    const val VERIFICATION_MODULUS = 1_000_000

    // RFC 9180 base mode, DHKEM(P-256, HKDF-SHA256) / HKDF-SHA256 / AES-256-GCM — see [Hpke].
    // The QR scan and the verification code authenticate the peers, so the sender-authenticated
    // modes buy nothing.
}
