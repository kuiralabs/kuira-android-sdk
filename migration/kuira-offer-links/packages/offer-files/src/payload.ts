// This file is part of Kuira Offer Links.
// SPDX-License-Identifier: Apache-2.0

/**
 * MIP-0006 offer payload — the application-layer JSON wrapper shared between
 * peers and stored/served by MIP-0006 indexers (the Offer Store).
 *
 * "All compliant implementations MUST use the following offer payload
 * structure." MIP-0005 handles the binary serialization + bech32m envelope;
 * this layer adds the trade terms (`wants`/`gives`), optional metadata, and
 * optional maker authentication for discovery.
 */
import { isOfferFile } from './codec.js';

/** One side of the trade terms: a token type and a base-unit decimal amount. */
export interface TokenAmount {
  /** Token type identifier (hex). */
  token: string;
  /** Amount in base units, as a decimal string (u128-safe). */
  amount: string;
}

/** Optional human/context metadata attached by the maker. */
export interface OfferMetadata {
  /** RFC3339 creation timestamp. */
  createdAt?: string;
  /** RFC3339 expiry hint (the chain's TTL remains the source of truth). */
  expiresAt?: string;
  /** Free-text note from the maker. */
  makerNote?: string;
}

/** Optional maker authentication over the payload. */
export interface OfferAuth {
  signerPublicKey: string;
  signature: string;
  scheme: 'schnorr-bip340';
}

/** The MIP-0006 payload every compliant implementation exchanges. */
export interface OfferPayload {
  version: 1;
  /**
   * The bech32m-encoded Offer File (`zswapoffer1…`, MIP-0005). The field is
   * named `transaction` by the spec even though it carries the encoded offer.
   */
  transaction: string;
  /** What the maker wants to receive. */
  wants: TokenAmount[];
  /** What the maker gives. */
  gives: TokenAmount[];
  metadata?: OfferMetadata;
  auth?: OfferAuth;
}

/**
 * Validate an untrusted value (e.g. an indexer response or a pasted JSON blob)
 * as a MIP-0006 `OfferPayload`.
 *
 * Checks structure and that `transaction` is a checksum-valid Offer File.
 * It does NOT verify the offer is proven or live — those require the ledger
 * SDK and chain state respectively.
 *
 * @throws {Error} describing the first violated rule.
 */
export function parseOfferPayload(value: unknown): OfferPayload {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error('OfferPayload must be a JSON object');
  }
  const o = value as Record<string, unknown>;

  if (o.version !== 1) {
    throw new Error(`Unsupported OfferPayload version: ${String(o.version)}`);
  }
  if (typeof o.transaction !== 'string' || !isOfferFile(o.transaction)) {
    throw new Error("'transaction' must be a valid bech32m Offer File (zswapoffer1…)");
  }
  const wants = parseTokenAmounts(o.wants, 'wants');
  const gives = parseTokenAmounts(o.gives, 'gives');

  const payload: OfferPayload = { version: 1, transaction: o.transaction, wants, gives };

  if (o.metadata !== undefined) payload.metadata = parseMetadata(o.metadata);
  if (o.auth !== undefined) payload.auth = parseAuth(o.auth);
  return payload;
}

function parseTokenAmounts(value: unknown, field: string): TokenAmount[] {
  if (!Array.isArray(value)) throw new Error(`'${field}' must be an array`);
  return value.map((entry, i) => {
    if (typeof entry !== 'object' || entry === null) {
      throw new Error(`'${field}[${i}]' must be an object`);
    }
    const e = entry as Record<string, unknown>;
    if (typeof e.token !== 'string' || e.token.length === 0) {
      throw new Error(`'${field}[${i}].token' must be a non-empty string`);
    }
    if (typeof e.amount !== 'string' || !/^[0-9]+$/.test(e.amount)) {
      throw new Error(`'${field}[${i}].amount' must be a decimal string`);
    }
    return { token: e.token, amount: e.amount };
  });
}

function parseMetadata(value: unknown): OfferMetadata {
  if (typeof value !== 'object' || value === null) {
    throw new Error("'metadata' must be an object");
  }
  const m = value as Record<string, unknown>;
  const out: OfferMetadata = {};
  for (const key of ['createdAt', 'expiresAt', 'makerNote'] as const) {
    if (m[key] !== undefined) {
      if (typeof m[key] !== 'string') throw new Error(`'metadata.${key}' must be a string`);
      out[key] = m[key] as string;
    }
  }
  return out;
}

function parseAuth(value: unknown): OfferAuth {
  if (typeof value !== 'object' || value === null) {
    throw new Error("'auth' must be an object");
  }
  const a = value as Record<string, unknown>;
  if (a.scheme !== 'schnorr-bip340') {
    throw new Error(`'auth.scheme' must be 'schnorr-bip340', got ${String(a.scheme)}`);
  }
  if (typeof a.signerPublicKey !== 'string' || typeof a.signature !== 'string') {
    throw new Error("'auth.signerPublicKey' and 'auth.signature' must be strings");
  }
  return { signerPublicKey: a.signerPublicKey, signature: a.signature, scheme: 'schnorr-bip340' };
}
