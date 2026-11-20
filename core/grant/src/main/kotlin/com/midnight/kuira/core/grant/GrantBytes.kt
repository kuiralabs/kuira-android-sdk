package com.midnight.kuira.core.grant

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * The byte layout shared by every structure of the grant spec: little-endian integers, 32-byte tags,
 * two-byte-length strings with stated bounds, and times below 2^40.
 *
 * Times are carried in a [Long]; a value the caller means as unsigned at or above 2^63 is negative, and
 * [checkTime] refuses it with the rest of what lies at or above 2^40.
 */
internal object GrantBytes {
    const val TAG_WIDTH = 32
    const val TIME_LIMIT = 1L shl 40
    const val MAX_LIFETIME = 2_592_000L
    const val MAX_REQUEST_WINDOW = 600L
    const val MAX_WIRE = 4096
    const val SIGNATURE_LENGTH = 64
    const val U32_MAX = 0xFFFF_FFFFL

    fun tag(text: String): ByteArray {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        require(bytes.size <= TAG_WIDTH) { "a tag wider than its pad" }
        return bytes.copyOf(TAG_WIDTH)
    }

    fun u16(value: Int): ByteArray = byteArrayOf((value and 0xff).toByte(), (value shr 8 and 0xff).toByte())

    fun u32(value: Long): ByteArray = ByteArray(4) { (value ushr (8 * it) and 0xff).toByte() }

    fun u64(value: Long): ByteArray = ByteArray(8) { (value ushr (8 * it) and 0xff).toByte() }

    fun str16(text: String): ByteArray = str16(text.toByteArray(Charsets.UTF_8))

    fun str16(bytes: ByteArray): ByteArray = u16(bytes.size) + bytes

    fun scope(features: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(features.size)
        for (feature in features) out.write(str16(feature))
        return out.toByteArray()
    }

    /** A feature is `<namespace>:<object>:<verb>`, each part `[a-z0-9_-]+`, 1–64 bytes in all. */
    fun isFeature(feature: String): Boolean {
        val bytes = feature.toByteArray(Charsets.UTF_8)
        if (bytes.size !in 1..64) return false
        val parts = feature.split(":")
        return parts.size == 3 && parts.all { part ->
            part.isNotEmpty() && part.toByteArray(Charsets.UTF_8).all { b ->
                val c = b.toInt() and 0xff
                c in 0x61..0x7a || c in 0x30..0x39 || c == 0x5f || c == 0x2d
            }
        }
    }

    /** 1–16 well-formed features, sorted bytewise, no duplicates. Never re-sorted. */
    fun checkScope(features: List<String>) {
        if (features.size !in 1..16) throw GrantRefusal.BadScope("a scope has 1 to 16 features")
        for (feature in features) {
            if (!isFeature(feature)) throw GrantRefusal.BadScope("`$feature` is not <namespace>:<object>:<verb>")
        }
        for (index in 1 until features.size) {
            val earlier = features[index - 1].toByteArray(Charsets.UTF_8)
            val later = features[index].toByteArray(Charsets.UTF_8)
            if (compareUnsigned(earlier, later) >= 0) {
                throw GrantRefusal.BadScope("features are sorted bytewise without duplicates")
            }
        }
    }

    fun checkTime(time: Long) {
        if (time < 0 || time >= TIME_LIMIT) throw GrantRefusal.TimeOutOfRange
    }

    /** The audience is an origin or `rdns:`, at most 300 bytes. */
    fun audience(text: String): GrantClientID {
        val id = clientID(text, 300)
        if (id.kind != GrantClientID.Kind.ORIGIN && id.kind != GrantClientID.Kind.REVERSE_DNS) {
            throw GrantRefusal.Malformed("an audience is an origin or rdns:")
        }
        return id
    }

    /** The grantee's client id is `self:`, `mais:` or `rdns:`, at most 300 bytes. */
    fun client(text: String): GrantClientID {
        val id = clientID(text, 300)
        if (id.kind == GrantClientID.Kind.ORIGIN) throw GrantRefusal.Malformed("a client id is self:, mais: or rdns:")
        return id
    }

    private fun clientID(text: String, maxBytes: Int): GrantClientID {
        if (text.toByteArray(Charsets.UTF_8).size !in 1..maxBytes) throw GrantRefusal.Malformed("an id is 1 to $maxBytes bytes")
        return try {
            GrantClientID(text)
        } catch (_: GrantError) {
            throw GrantRefusal.Malformed("`$text` is not a normal-form id")
        }
    }

    /** The grantee key, arm `1` (`k1`) and envelope `0` only. */
    fun grantee(arm: Int, envelope: Int, key: ByteArray): GranteeKey {
        if (arm != 1 || envelope != 0) throw GrantRefusal.UnknownArm
        return try {
            GranteeKey(GranteeKey.Arm.K1(0), key.copyOfRange(0, 32), key.copyOfRange(32, 64))
        } catch (_: GrantError) {
            throw GrantRefusal.BadGranteeKey
        }
    }

    fun granteeBytes(key: GranteeKey): ByteArray = byteArrayOf(1, 0) + key.x + key.y

    /** `preimage || signature`, split, within the wire-size limit. */
    fun split(wire: ByteArray): Pair<ByteArray, ByteArray> {
        if (wire.size <= SIGNATURE_LENGTH || wire.size > MAX_WIRE) throw GrantRefusal.Malformed("wire size")
        val cut = wire.size - SIGNATURE_LENGTH
        return wire.copyOfRange(0, cut) to wire.copyOfRange(cut, wire.size)
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (index in 0 until minOf(a.size, b.size)) {
            val difference = (a[index].toInt() and 0xff) - (b[index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return a.size - b.size
    }
}

/** Reads one structure's bytes, refusing any field outside its bounds and any byte left over. */
internal class GrantReader(private val bytes: ByteArray) {
    private var offset = 0

    fun bytes(count: Int): ByteArray {
        if (count > bytes.size - offset) throw GrantRefusal.Malformed("truncated")
        val out = bytes.copyOfRange(offset, offset + count)
        offset += count
        return out
    }

    fun u8(): Int = bytes(1)[0].toInt() and 0xff

    fun u16(): Int {
        val raw = bytes(2)
        return (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
    }

    fun u32(): Long = littleEndian(bytes(4))

    fun u64(): Long = littleEndian(bytes(8))

    fun time(): Long {
        val value = u64()
        GrantBytes.checkTime(value)
        return value
    }

    fun str16(bounds: IntRange): ByteArray {
        val count = u16()
        if (count !in bounds) throw GrantRefusal.Malformed("a field of $count bytes, outside $bounds")
        return bytes(count)
    }

    fun text(bounds: IntRange): String {
        val raw = str16(bounds)
        if (!raw.all { it in 0x20..0x7e }) throw GrantRefusal.Malformed("a text field that is not printable ASCII")
        return String(raw, Charsets.US_ASCII)
    }

    fun scope(): List<String> {
        val count = u8()
        if (count !in 1..16) throw GrantRefusal.BadScope("a scope has 1 to 16 features")
        val features = List(count) { text(1..64) }
        GrantBytes.checkScope(features)
        return features
    }

    /** The tag, refused unless it is [expected]. */
    fun tag(expected: String) {
        if (!bytes(GrantBytes.TAG_WIDTH).contentEquals(GrantBytes.tag(expected))) throw GrantRefusal.UnknownTag
    }

    fun version() {
        if (u8() != 1) throw GrantRefusal.UnknownVersion
    }

    fun rootKind() {
        if (u8() != 1) throw GrantRefusal.UnknownKind
    }

    fun end() {
        if (offset != bytes.size) throw GrantRefusal.Malformed("${bytes.size - offset} bytes left over")
    }

    private fun littleEndian(raw: ByteArray): Long =
        raw.indices.fold(0L) { value, index -> value or ((raw[index].toLong() and 0xff) shl (8 * index)) }
}

internal object Base64URL {
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray {
        if (!text.all { isAlphabet(it) } || text.length % 4 == 1) throw GrantRefusal.Malformed("not base64url")
        val bytes = try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            throw GrantRefusal.Malformed("not base64url")
        }
        if (encode(bytes) != text) throw GrantRefusal.Malformed("not canonical base64url")
        return bytes
    }

    private fun isAlphabet(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'
}

/** Lowercase hex. */
internal fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) {
        val v = b.toInt() and 0xff
        append(HEX_DIGITS[v shr 4])
        append(HEX_DIGITS[v and 0x0f])
    }
}

/** Lowercase hex only; `null` for odd length or any other character. */
internal fun hexBytes(text: String): ByteArray? {
    if (text.length % 2 != 0) return null
    val out = ByteArray(text.length / 2)
    for (index in out.indices) {
        val high = nibble(text[2 * index]) ?: return null
        val low = nibble(text[2 * index + 1]) ?: return null
        out[index] = (high shl 4 or low).toByte()
    }
    return out
}

private const val HEX_DIGITS = "0123456789abcdef"

private fun nibble(c: Char): Int? = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> null
}
