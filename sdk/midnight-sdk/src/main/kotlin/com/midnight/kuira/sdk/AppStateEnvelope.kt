package com.midnight.kuira.sdk

/**
 * Wallet-level backup preferences that travel WITH the wallet (inside the seed-keyed
 * app-state blob) instead of dying with the install (package-local prefs).
 *
 * Why: on a fresh install the dust-backup toggle state was unrecoverable, so the app
 * couldn't know a cloud checkpoint existed and silently fell back to a genesis replay.
 * These prefs restore silently with the app-state blob (Block Store — no consent), letting
 * the host drive the restore-over-genesis flow. See internal design notes.
 *
 * @property dustBackupEnabled the user actually enabled dust cloud backup (the committed
 *  toggle — consent granted at least once), not merely "never opted out".
 * @property dustBackupOptedOut the user explicitly disabled backup. A restored
 *  opted-out wallet must never be re-prompted for consent.
 */
data class WalletBackupPrefs(
    val dustBackupEnabled: Boolean,
    val dustBackupOptedOut: Boolean,
)

/**
 * The decoded app-state blob: SDK-level [prefs] (null when the blob predates the envelope —
 * a legacy raw host payload — or was uploaded with no prefs set) + the host's own payload.
 */
data class RestoredAppState(
    val prefs: WalletBackupPrefs?,
    val hostPayload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is RestoredAppState && prefs == other.prefs && hostPayload.contentEquals(other.hostPayload)

    override fun hashCode(): Int = 31 * (prefs?.hashCode() ?: 0) + hostPayload.contentHashCode()
}

/**
 * Versioned envelope for the app-state blob: `magic ∥ flags ∥ hostLen ∥ hostPayload`.
 *
 * Wraps the host's payload (plaintext, BEFORE encryption) so wallet-level prefs ride the
 * same blob without changing the host-facing API — `fetchAppState` still returns exactly
 * the host payload.
 *
 * **Legacy compatibility:** blobs uploaded before the envelope are raw host bytes. Decode
 * treats anything that doesn't carry the magic AND a length field consistent with the blob
 * size as legacy: prefs = null, hostPayload = the whole blob. The double check (magic +
 * exact length) makes a false positive on a host payload that happens to start with the
 * magic effectively impossible.
 *
 * Binary, not JSON: the wire format mirrors [BalanceEnvelope]'s reasoning — no JSON
 * dependency, and the Android unit-test classpath has no working `org.json`.
 */
internal object AppStateEnvelope {

    // "KAS1" — Kuira App State v1. A new incompatible layout bumps the trailing digit.
    private val MAGIC = byteArrayOf(0x4B, 0x41, 0x53, 0x31)
    private const val FLAG_PREFS_PRESENT = 0x01
    private const val FLAG_DUST_ENABLED = 0x02
    private const val FLAG_DUST_OPTED_OUT = 0x04
    private const val HEADER_SIZE = 4 + 1 + 4 // magic + flags + hostLen

    fun encode(prefs: WalletBackupPrefs?, hostPayload: ByteArray): ByteArray {
        var flags = 0
        if (prefs != null) {
            flags = flags or FLAG_PREFS_PRESENT
            if (prefs.dustBackupEnabled) flags = flags or FLAG_DUST_ENABLED
            if (prefs.dustBackupOptedOut) flags = flags or FLAG_DUST_OPTED_OUT
        }
        val out = ByteArray(HEADER_SIZE + hostPayload.size)
        MAGIC.copyInto(out)
        out[4] = flags.toByte()
        val len = hostPayload.size
        out[5] = (len ushr 24).toByte()
        out[6] = (len ushr 16).toByte()
        out[7] = (len ushr 8).toByte()
        out[8] = len.toByte()
        hostPayload.copyInto(out, HEADER_SIZE)
        return out
    }

    fun decode(blob: ByteArray): RestoredAppState {
        if (blob.size < HEADER_SIZE) return RestoredAppState(prefs = null, hostPayload = blob)
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) return RestoredAppState(prefs = null, hostPayload = blob)
        }
        val len = ((blob[5].toInt() and 0xFF) shl 24) or
            ((blob[6].toInt() and 0xFF) shl 16) or
            ((blob[7].toInt() and 0xFF) shl 8) or
            (blob[8].toInt() and 0xFF)
        // Length must account for the blob exactly — otherwise this is a legacy host payload
        // that coincidentally starts with the magic bytes.
        if (len != blob.size - HEADER_SIZE) return RestoredAppState(prefs = null, hostPayload = blob)
        val flags = blob[4].toInt()
        val prefs = if (flags and FLAG_PREFS_PRESENT != 0) {
            WalletBackupPrefs(
                dustBackupEnabled = flags and FLAG_DUST_ENABLED != 0,
                dustBackupOptedOut = flags and FLAG_DUST_OPTED_OUT != 0,
            )
        } else null
        return RestoredAppState(prefs = prefs, hostPayload = blob.copyOfRange(HEADER_SIZE, blob.size))
    }
}
