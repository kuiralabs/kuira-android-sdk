// This file is part of Kuira Offer Links.
// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest';
import { encode } from '../src/codec.js';
import { parseOfferPayload } from '../src/payload.js';

const offerFile = () => encode(Uint8Array.from({ length: 64 }, (_, i) => i));

const valid = () => ({
  version: 1,
  transaction: offerFile(),
  wants: [{ token: '00'.repeat(32), amount: '80000000' }],
  gives: [{ token: 'ab'.repeat(32), amount: '1' }],
});

describe('OfferPayload (MIP-0006)', () => {
  it('accepts a minimal valid payload', () => {
    const p = parseOfferPayload(valid());
    expect(p.version).toBe(1);
    expect(p.wants).toHaveLength(1);
    expect(p.gives[0]?.amount).toBe('1');
  });

  it('accepts optional metadata and auth', () => {
    const p = parseOfferPayload({
      ...valid(),
      metadata: { createdAt: '2026-08-04T00:00:00Z', makerNote: 'CryptoRock #42' },
      auth: { signerPublicKey: 'aa'.repeat(32), signature: 'bb'.repeat(64), scheme: 'schnorr-bip340' },
    });
    expect(p.metadata?.makerNote).toBe('CryptoRock #42');
    expect(p.auth?.scheme).toBe('schnorr-bip340');
  });

  it('rejects a wrong version', () => {
    expect(() => parseOfferPayload({ ...valid(), version: 2 })).toThrow(/version/);
  });

  it('rejects a transaction that is not a valid Offer File', () => {
    expect(() => parseOfferPayload({ ...valid(), transaction: 'zswapoffer1notvalid' })).toThrow(/Offer File/);
    expect(() => parseOfferPayload({ ...valid(), transaction: 'mn_addr_preview1abc' })).toThrow(/Offer File/);
  });

  it('rejects malformed wants/gives entries', () => {
    expect(() => parseOfferPayload({ ...valid(), wants: [{ token: '', amount: '1' }] })).toThrow(/token/);
    expect(() => parseOfferPayload({ ...valid(), gives: [{ token: 'aa', amount: '1.5' }] })).toThrow(/amount/);
    expect(() => parseOfferPayload({ ...valid(), wants: 'nope' })).toThrow(/array/);
  });

  it('rejects an unknown auth scheme', () => {
    expect(() =>
      parseOfferPayload({ ...valid(), auth: { signerPublicKey: 'aa', signature: 'bb', scheme: 'ed25519' } }),
    ).toThrow(/schnorr-bip340/);
  });

  it('rejects non-object input', () => {
    expect(() => parseOfferPayload('gm')).toThrow(/object/);
    expect(() => parseOfferPayload(null)).toThrow(/object/);
    expect(() => parseOfferPayload([valid()])).toThrow(/object/);
  });
});
