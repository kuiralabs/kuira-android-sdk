// This file is part of Kuira Offer Links.
// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { bech32m } from '@scure/base';
import { HRP, PREFIX, decode, decodeToHex, encode, encodeHex, findAll, isOfferFile } from '../src/codec.js';

const FIXTURES = join(dirname(fileURLToPath(import.meta.url)), 'fixtures');

/** Deterministic payloads mirrored EXACTLY in the Kotlin test suite. */
const bigPayload = () => Uint8Array.from({ length: 10_143 }, (_, i) => ((i * 31) ^ 0x5a) & 0xff);
const smallPayload = () => Uint8Array.from({ length: 64 }, (_, i) => (i * 7 + 3) & 0xff);

describe('OfferFile codec (MIP-0005 envelope)', () => {
  it('round-trips arbitrary offer bytes', () => {
    const offer = Uint8Array.from({ length: 256 }, (_, i) => (i * 7 + 3) & 0xff);
    expect(decode(encode(offer))).toEqual(offer);
  });

  it('starts with the zswapoffer prefix', () => {
    const file = encode(Uint8Array.from({ length: 32 }, (_, i) => i));
    expect(file.startsWith('zswapoffer1')).toBe(true);
    expect(HRP).toBe('zswapoffer');
    expect(PREFIX).toBe('zswapoffer1');
  });

  it('round-trips a realistic ~10 KB offer and expands to ~16 K chars', () => {
    const offer = bigPayload();
    const file = encode(offer);
    expect(decode(file)).toEqual(offer);
    // The post/MIP figure: a 10,143-byte offer is 16,246 characters of text.
    expect(file.length).toBe(16_246);
  });

  it('matches the Kotlin implementation byte-for-byte (cross-impl fixture, 16,246 chars)', () => {
    const expected = readFileSync(join(FIXTURES, 'offer-16246.txt'), 'utf8');
    expect(encode(bigPayload())).toBe(expected);
    expect(decode(expected)).toEqual(bigPayload());
  });

  it('matches the Kotlin implementation on the small fixture', () => {
    const expected = readFileSync(join(FIXTURES, 'offer-small.txt'), 'utf8');
    expect(encode(smallPayload())).toBe(expected);
    expect(decode(expected)).toEqual(smallPayload());
  });

  it('round-trips through the hex convenience helpers', () => {
    const hex = 'deadbeef0123456789abcdeffeedface';
    expect(decodeToHex(encodeHex(hex))).toBe(hex);
  });

  it('rejects empty offer bytes', () => {
    expect(() => encode(new Uint8Array(0))).toThrow();
  });

  it('rejects a corrupted checksum', () => {
    const file = encode(Uint8Array.from({ length: 64 }, (_, i) => i));
    const corrupted = file.slice(0, -1) + (file.endsWith('q') ? 'p' : 'q');
    expect(() => decode(corrupted)).toThrow();
  });

  it('rejects a valid bech32m string with the wrong HRP', () => {
    const address = bech32m.encode(
      'mn_addr_preview',
      bech32m.toWords(Uint8Array.from({ length: 32 }, (_, i) => i)),
      false,
    );
    expect(() => decode(address)).toThrow(/expected HRP/);
  });

  it('isOfferFile distinguishes offers from other text', () => {
    const file = encode(Uint8Array.from({ length: 48 }, (_, i) => i));
    const address = bech32m.encode(
      'mn_addr_preview',
      bech32m.toWords(Uint8Array.from({ length: 32 }, (_, i) => i)),
      false,
    );
    expect(isOfferFile(file)).toBe(true);
    expect(isOfferFile(`  ${file} \n`)).toBe(true); // whitespace tolerated
    expect(isOfferFile(address)).toBe(false);
    expect(isOfferFile('just a normal sentence')).toBe(false);
  });

  it('findAll extracts offers embedded in a social post', () => {
    const a = encode(Uint8Array.from({ length: 40 }, (_, i) => i));
    const b = encode(Uint8Array.from({ length: 40 }, (_, i) => (i + 100) & 0xff));
    const post = `gm! selling my rock ${a} and here's another ${b} — dm me`;
    expect(findAll(post)).toEqual([a, b]);
  });

  it('findAll drops corrupted lookalikes and returns nothing on prose', () => {
    const a = encode(Uint8Array.from({ length: 40 }, (_, i) => i));
    const corrupted = a.slice(0, -1) + (a.endsWith('q') ? 'p' : 'q');
    expect(findAll(`broken: ${corrupted}`)).toEqual([]);
    expect(findAll('no offers here, just vibes')).toEqual([]);
  });
});
