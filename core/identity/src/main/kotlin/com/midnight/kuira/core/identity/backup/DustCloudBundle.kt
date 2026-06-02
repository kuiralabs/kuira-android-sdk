package com.midnight.kuira.core.identity.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * One network's dust checkpoint: the serialized [com.midnight.kuira.core.crypto.dust.DustLocalState]
 * bytes plus the last-applied event id needed to resume as a delta. Keyed by the
 * network-prefixed wallet address (e.g. `mn_addr_preprod…`).
 */
data class DustBackupEntry(
    val address: String,
    val lastEventId: Long,
    val stateBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DustBackupEntry) return false
        return address == other.address &&
            lastEventId == other.lastEventId &&
            stateBytes.contentEquals(other.stateBytes)
    }

    override fun hashCode(): Int = address.hashCode()
}

/**
 * Plaintext (pre-encryption) wire format for the dust cloud backup — a single
 * versioned container holding one entry per network the user has dust on (≤3).
 *
 * ```
 * "KDB1" | version(1) | entryCount(1) | repeated {
 *     addrLen(2 BE) | addrUtf8 | lastEventId(8 BE) | stateLen(4 BE) | stateBytes
 * }
 * ```
 *
 * The 4-byte `stateLen` accommodates the ~500 KB dust state (the 2-byte field in
 * [AppStateBackupEncryptor] could not). Encrypt the encoded bytes with
 * [DustBackupEncryptor] before upload.
 */
object DustCloudBundleCodec {

    private val MAGIC = byteArrayOf('K'.code.toByte(), 'D'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val VERSION = 0x01
    private const val MAX_ENTRIES = 255

    fun encode(entries: List<DustBackupEntry>): ByteArray {
        require(entries.size <= MAX_ENTRIES) { "Too many entries: ${entries.size}" }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { w ->
            w.write(MAGIC)
            w.writeByte(VERSION)
            w.writeByte(entries.size)
            for (e in entries) {
                val addr = e.address.toByteArray(Charsets.UTF_8)
                w.writeShort(addr.size)
                w.write(addr)
                w.writeLong(e.lastEventId)
                w.writeInt(e.stateBytes.size)
                w.write(e.stateBytes)
            }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): List<DustBackupEntry> {
        DataInputStream(ByteArrayInputStream(bytes)).use { r ->
            val magic = ByteArray(MAGIC.size)
            r.readFully(magic)
            require(magic.contentEquals(MAGIC)) { "Bad dust bundle magic" }
            val version = r.readUnsignedByte()
            require(version == VERSION) { "Unsupported dust bundle version: $version" }
            val count = r.readUnsignedByte()
            return buildList {
                repeat(count) {
                    val addrLen = r.readUnsignedShort()
                    val addr = ByteArray(addrLen).also { r.readFully(it) }
                    val lastEventId = r.readLong()
                    val stateLen = r.readInt()
                    require(stateLen >= 0) { "Negative stateLen: $stateLen" }
                    val state = ByteArray(stateLen).also { r.readFully(it) }
                    add(DustBackupEntry(String(addr, Charsets.UTF_8), lastEventId, state))
                }
            }
        }
    }

    /** Decode and return the entry for [address], or null if the bundle has none. */
    fun entryFor(bytes: ByteArray, address: String): DustBackupEntry? =
        decode(bytes).firstOrNull { it.address == address }
}
