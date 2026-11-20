package com.midnight.kuira.core.grant

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** SLIP-0010 for Ed25519: hardened derivation only. */
internal object Slip10Ed25519 {
    /** A derived node: 32-byte key and 32-byte chain code, both private; [wipe] zeroes them in place. */
    class Node(val key: ByteArray, val chainCode: ByteArray) {
        fun wipe() {
            key.fill(0)
            chainCode.fill(0)
        }
    }

    private const val HARDENED_OFFSET = 0x8000_0000L

    /** `m/44'/2400'/0'/6'`, the grant-root role (spec section 2.1). */
    val GRANT_ROOT_PATH: List<Long> = listOf(44, 2400, 0, 6)

    /**
     * Derives down [path], every level hardened (raw indices; the offset is applied here). Each intermediate
     * node is wiped as the derivation descends; the caller wipes the returned leaf.
     */
    fun derive(seed: ByteArray, path: List<Long>): Node {
        var node = split(hmacSHA512("ed25519 seed".toByteArray(Charsets.US_ASCII), seed))
        for (index in path) {
            require(index in 0 until HARDENED_OFFSET) { "path indices are raw (unhardened) numbers" }
            val child = childHardened(node, index or HARDENED_OFFSET)
            node.wipe()
            node = child
        }
        return node
    }

    /** `I = HMAC-SHA512(key: chainCode, data: 0x00 ‖ key ‖ ser32(index))`. */
    private fun childHardened(parent: Node, index: Long): Node {
        val data = ByteArray(37)
        System.arraycopy(parent.key, 0, data, 1, 32)
        for (i in 0 until 4) data[33 + i] = (index ushr (8 * (3 - i)) and 0xff).toByte()
        try {
            return split(hmacSHA512(parent.chainCode, data))
        } finally {
            data.fill(0)
        }
    }

    private fun split(mac: ByteArray): Node {
        try {
            return Node(mac.copyOfRange(0, 32), mac.copyOfRange(32, 64))
        } finally {
            mac.fill(0)
        }
    }

    fun hmacSHA512(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(key, "HmacSHA512"))
        return mac.doFinal(data)
    }
}
