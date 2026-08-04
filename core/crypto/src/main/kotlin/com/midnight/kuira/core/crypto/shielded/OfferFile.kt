// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

import com.midnight.kuira.core.crypto.address.Bech32m

/**
 * Encodes and decodes **Offer Files** (MIP-0005) — the shareable,
 * copy-pasteable form of a proven Zswap offer.
 *
 * **What goes inside (MIP-0005, normative):**
 * The payload MUST be "the canonical ledger serialization of a proven zswap
 * offer" — `Offer<Proof>` via the ledger's `Serializable::serialize`, with no
 * tag prefix, no version byte, and no network id. The spec is explicit that
 * "offer files MUST contain proven offers only. Unproven and proof-erased
 * offers MUST NOT be encoded as offer files."
 *
 * ⚠️ In this codebase, [OfferResult.offerHex] is an *unproven* builder offer
 * (proving happens later, at transaction level), so it must never be passed
 * here. The canonical proven-offer bytes come from the ledger FFI — see the
 * note in [ZswapTransferBuilder].
 *
 * **Why the wrapping exists:**
 * A proven offer is ~10 KB of opaque bytes — impossible to paste into a chat
 * or copy without risk of silent corruption. [Bech32m] fixes both:
 *
 * - **Human-transportable** — plain ASCII (`zswapoffer1…`) that survives chat
 *   apps, Open Graph link previews, and clipboards.
 * - **Self-checking** — the checksum makes [decode] reject a copy-paste error
 *   instead of silently producing a broken offer.
 *
 * The wrapping is purely a transport concern: only the compact bytes are ever
 * posted on-chain. A proven offer is tamper-proof by construction, so sharing
 * the file is safe — the worst a stranger can do with it is discard it.
 *
 * **Format:** `zswapoffer1<bech32m-data><checksum>`. Per MIP-0005, bech32's
 * standard 90-character limit MUST NOT be enforced: a 1-input/1-output offer of
 * ~10 KB expands to ~16 K characters (too large for a standard QR code, so
 * Offer Files travel as text or behind a URL, never scanned).
 *
 * **Layering:** this codec enforces the envelope rules (HRP, checksum, size).
 * The remaining MIP-0005 decode rule — "payloads that fail deserialization
 * [as a proven zswap offer] MUST be rejected" — is a ledger concern and is
 * enforced natively when the decoded bytes are handed to the FFI. MIP-0006
 * then wraps the encoded string in its JSON `OfferPayload` for discovery.
 *
 * **Unit-testable** — this object performs no I/O and needs no native library.
 */
object OfferFile {

    /**
     * Human-readable part for a shielded (Zswap) Offer File.
     *
     * Chosen so a shared file reads `zswapoffer1…`, matching MIP-0005 and making
     * the payload self-describing to any client that scans for it.
     */
    const val HRP: String = "zswapoffer"

    /** The literal prefix every Offer File starts with (`hrp` + separator). */
    const val PREFIX: String = "$HRP" + "1"

    /**
     * Matches an Offer File embedded anywhere in free text — a social post, a
     * chat message, a web page. Used by clients (e.g. a browser extension) that
     * "unfurl" offers found in the wild. The body uses the Bech32 charset only,
     * and requires at least the 6-character checksum after the separator.
     */
    private val OFFER_FILE_REGEX: Regex =
        Regex("$PREFIX[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{6,}")

    /**
     * Wrap raw serialized offer bytes into a shareable Offer File string.
     *
     * @param offerBytes Canonical ledger serialization of a **proven** Zswap
     *   offer. Per MIP-0005, unproven or proof-erased offers MUST NOT be
     *   encoded — this codec cannot verify proven-ness (that requires the
     *   ledger FFI), so the caller owns that contract.
     * @return Bech32m Offer File, e.g. `zswapoffer1qpzry9x8g…`.
     * @throws IllegalArgumentException if [offerBytes] is empty.
     */
    fun encode(offerBytes: ByteArray): String {
        require(offerBytes.isNotEmpty()) { "Offer bytes must not be empty" }
        return Bech32m.encode(HRP, offerBytes)
    }

    /**
     * Unwrap an Offer File back into its raw serialized offer bytes.
     *
     * Verifies both the checksum and the human-readable part, so a truncated,
     * corrupted, or wrong-kind string is rejected rather than silently decoded.
     *
     * @param offerFile A `zswapoffer1…` string.
     * @return The serialized offer bytes.
     * @throws IllegalArgumentException if the string is not a valid Offer File
     *   (bad checksum, or an HRP other than [HRP], such as an address).
     */
    fun decode(offerFile: String): ByteArray {
        val (hrp, bytes) = Bech32m.decode(offerFile.trim())
        require(hrp == HRP) {
            "Not an Offer File: expected HRP '$HRP', got '$hrp'"
        }
        return bytes
    }

    /**
     * Convenience: wrap an offer given as a hex string (the form returned by the
     * FFI serializer) into an Offer File.
     */
    fun encodeHex(offerHex: String): String = encode(hexToBytes(offerHex))

    /**
     * Convenience: unwrap an Offer File to the hex form the FFI consumes.
     */
    fun decodeToHex(offerFile: String): String = bytesToHex(decode(offerFile))

    /**
     * True if [text] is exactly one Offer File (ignoring surrounding whitespace).
     */
    fun isOfferFile(text: String): Boolean {
        val trimmed = text.trim()
        return OFFER_FILE_REGEX.matches(trimmed) && runCatching { decode(trimmed) }.isSuccess
    }

    /**
     * Extract every Offer File found inside a larger block of text.
     *
     * Intended for clients that scan social posts or pages for offers to unfurl.
     * Only checksum-valid files are returned, so decorative or truncated matches
     * are dropped.
     */
    fun findAll(text: String): List<String> =
        OFFER_FILE_REGEX.findAll(text)
            .map { it.value }
            .filter { candidate -> runCatching { decode(candidate) }.isSuccess }
            .toList()

    // ── Internal hex helpers (self-contained so the codec stays dependency-free) ──

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.removePrefix("0x")
        require(clean.isNotEmpty()) { "Hex string must not be empty" }
        require(clean.length % 2 == 0) { "Hex string must have even length, got ${clean.length}" }
        return ByteArray(clean.length / 2) { i ->
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character in offer serialization" }
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX_CHARS = "0123456789abcdef"
}
