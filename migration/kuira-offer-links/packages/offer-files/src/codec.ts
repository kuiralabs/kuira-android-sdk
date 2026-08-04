// This file is part of Kuira Offer Links.
// SPDX-License-Identifier: Apache-2.0

/**
 * MIP-0005 Offer File codec — the bech32m envelope for a proven Zswap offer.
 *
 * What goes inside (MIP-0005, normative): the payload MUST be "the canonical
 * ledger serialization of a proven zswap offer" — `ZswapOffer.serialize()` in
 * the Midnight TypeScript SDK — with no tag prefix, no version byte, and no
 * network id. "Offer files MUST contain proven offers only. Unproven and
 * proof-erased offers MUST NOT be encoded as offer files." This codec cannot
 * verify proven-ness (that requires deserializing with the ledger SDK), so the
 * caller owns that contract.
 *
 * Envelope rules enforced here (also normative):
 * - HRP `zswapoffer`, separator `1`, bech32m checksum (BIP-350).
 * - "Bech32's standard 90-character limit MUST NOT be enforced" — a
 *   1-input/1-output offer is ~10 KB of bytes ≈ 16 K characters of text.
 * - Decode MUST reject an incorrect HRP or an invalid checksum. (The third
 *   rejection rule — payloads that fail deserialization as a proven offer —
 *   is the ledger SDK's job, applied to the bytes this codec returns.)
 */
import { bech32m } from '@scure/base';

/** Human-readable part of every Offer File. */
export const HRP = 'zswapoffer';

/** The literal prefix every Offer File starts with (HRP + separator). */
export const PREFIX = `${HRP}1`;

/** Bech32 data charset (BIP-173/350). */
const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l';

/**
 * Matches an Offer File embedded anywhere in free text — a social post, a chat
 * message, a web page. Requires at least the 6-character checksum after the
 * separator. Candidates still have to pass checksum verification via decode().
 */
const EMBEDDED_RE = new RegExp(`${PREFIX}[${CHARSET}]{6,}`, 'g');
const EXACT_RE = new RegExp(`^${PREFIX}[${CHARSET}]{6,}$`);

/**
 * Wrap raw serialized offer bytes into a shareable Offer File string.
 *
 * @param offerBytes Canonical ledger serialization of a **proven** Zswap
 *   offer (MIP-0005 forbids encoding unproven or proof-erased offers).
 * @returns Bech32m Offer File, e.g. `zswapoffer1qpzry9x8g…`.
 * @throws {Error} if `offerBytes` is empty.
 */
export function encode(offerBytes: Uint8Array): string {
  if (offerBytes.length === 0) throw new Error('Offer bytes must not be empty');
  return bech32m.encode(HRP, bech32m.toWords(offerBytes), false);
}

/**
 * Unwrap an Offer File back into its raw serialized offer bytes.
 *
 * Verifies both the checksum and the human-readable part, so a truncated,
 * corrupted, or wrong-kind string (e.g. an address) is rejected rather than
 * silently decoded.
 *
 * @param offerFile A `zswapoffer1…` string (surrounding whitespace tolerated).
 * @returns The serialized offer bytes, ready for the ledger SDK to
 *   deserialize (which is where proven-offer validation happens).
 * @throws {Error} on bad checksum or wrong HRP.
 */
export function decode(offerFile: string): Uint8Array {
  const { prefix, words } = bech32m.decode(
    offerFile.trim() as `${string}1${string}`,
    false,
  );
  if (prefix !== HRP) {
    throw new Error(`Not an Offer File: expected HRP '${HRP}', got '${prefix}'`);
  }
  return bech32m.fromWords(words);
}

/** Convenience: wrap an offer given as a hex string into an Offer File. */
export function encodeHex(offerHex: string): string {
  return encode(hexToBytes(offerHex));
}

/** Convenience: unwrap an Offer File to lowercase hex. */
export function decodeToHex(offerFile: string): string {
  return bytesToHex(decode(offerFile));
}

/** True if `text` is exactly one Offer File (ignoring surrounding whitespace). */
export function isOfferFile(text: string): boolean {
  const trimmed = text.trim();
  if (!EXACT_RE.test(trimmed)) return false;
  try {
    decode(trimmed);
    return true;
  } catch {
    return false;
  }
}

/**
 * Extract every Offer File found inside a larger block of text.
 *
 * Intended for clients that scan social posts or pages for offers to unfurl
 * (e.g. the browser extension). Only checksum-valid files are returned, so
 * decorative or truncated matches are dropped.
 */
export function findAll(text: string): string[] {
  const out: string[] = [];
  for (const match of text.matchAll(EMBEDDED_RE)) {
    try {
      decode(match[0]);
      out.push(match[0]);
    } catch {
      // truncated or corrupted lookalike — skip
    }
  }
  return out;
}

// ── Hex helpers (dependency-free) ──

function hexToBytes(hex: string): Uint8Array {
  const clean = hex.startsWith('0x') ? hex.slice(2) : hex;
  if (clean.length === 0) throw new Error('Hex string must not be empty');
  if (clean.length % 2 !== 0) {
    throw new Error(`Hex string must have even length, got ${clean.length}`);
  }
  if (!/^[0-9a-fA-F]*$/.test(clean)) {
    throw new Error('Invalid hex character in offer serialization');
  }
  const bytes = new Uint8Array(clean.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

function bytesToHex(bytes: Uint8Array): string {
  let s = '';
  for (const b of bytes) s += b.toString(16).padStart(2, '0');
  return s;
}
