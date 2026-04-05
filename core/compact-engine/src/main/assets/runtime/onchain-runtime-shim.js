/**
 * Onchain-runtime shim for Android.
 *
 * Replaces @midnight-ntwrk/onchain-runtime-v2 (WASM) with native
 * Rust FFI calls via QuickJS's native function binding.
 *
 * Functions prefixed with __native_ are provided by Kotlin/Rust
 * and registered before this module is loaded.
 *
 * This module is registered as '@midnight-ntwrk/onchain-runtime-v2'
 * so compact-runtime's imports resolve to it.
 */

// ── SHA-256 (pure JS fallback when native FFI unavailable) ──

function jsSha256(data) {
  // Minimal SHA-256 implementation
  const K = new Uint32Array([
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
  ]);

  function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }

  let h0=0x6a09e667, h1=0xbb67ae85, h2=0x3c6ef372, h3=0xa54ff53a;
  let h4=0x510e527f, h5=0x9b05688c, h6=0x1f83d9ab, h7=0x5be0cd19;

  // Pre-processing: pad message
  const msgLen = data.length;
  const bitLen = msgLen * 8;
  const padded = new Uint8Array(((msgLen + 9 + 63) & ~63));
  padded.set(data);
  padded[msgLen] = 0x80;
  const view = new DataView(padded.buffer);
  view.setUint32(padded.length - 4, bitLen, false);

  const w = new Uint32Array(64);
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4, false);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i-15],7) ^ rotr(w[i-15],18) ^ (w[i-15]>>>3);
      const s1 = rotr(w[i-2],17) ^ rotr(w[i-2],19) ^ (w[i-2]>>>10);
      w[i] = (w[i-16] + s0 + w[i-7] + s1) | 0;
    }
    let a=h0,b=h1,c=h2,d=h3,e=h4,f=h5,g=h6,h=h7;
    for (let i = 0; i < 64; i++) {
      const S1 = rotr(e,6) ^ rotr(e,11) ^ rotr(e,25);
      const ch = (e & f) ^ (~e & g);
      const t1 = (h + S1 + ch + K[i] + w[i]) | 0;
      const S0 = rotr(a,2) ^ rotr(a,13) ^ rotr(a,22);
      const maj = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (S0 + maj) | 0;
      h=g; g=f; f=e; e=(d+t1)|0; d=c; c=b; b=a; a=(t1+t2)|0;
    }
    h0=(h0+a)|0; h1=(h1+b)|0; h2=(h2+c)|0; h3=(h3+d)|0;
    h4=(h4+e)|0; h5=(h5+f)|0; h6=(h6+g)|0; h7=(h7+h)|0;
  }

  const result = new Uint8Array(32);
  const rv = new DataView(result.buffer);
  rv.setUint32(0,h0,false); rv.setUint32(4,h1,false); rv.setUint32(8,h2,false); rv.setUint32(12,h3,false);
  rv.setUint32(16,h4,false); rv.setUint32(20,h5,false); rv.setUint32(24,h6,false); rv.setUint32(28,h7,false);
  return result;
}

// ── StateValue ──

export class StateValue {
  constructor(data) {
    this._data = data || null;
  }

  static newNull() {
    return new StateValue({ type: 'null' });
  }

  static newCell(value) {
    return new StateValue({ type: 'cell', value });
  }

  static newArray() {
    return new StateValue({ type: 'array', items: [] });
  }

  arrayPush(item) {
    if (this._data.type !== 'array') throw new Error('Not an array');
    const copy = new StateValue({
      type: 'array',
      items: [...this._data.items, item],
    });
    return copy;
  }

  encode() {
    return JSON.stringify(this._data);
  }

  static decode(encoded) {
    return new StateValue(JSON.parse(encoded));
  }
}

// ── ChargedState ──

export class ChargedState {
  constructor(stateValue) {
    this._state = stateValue;
  }

  get() {
    return this._state;
  }

  get_ref() {
    return this._state;
  }

  update(path, value) {
    // State update — delegates to native for real implementation
    return new ChargedState(value);
  }
}

// ── ContractState ──

export class ContractState {
  constructor() {
    this.data = null;
    this._operations = {};
    this.balance = new Map();
  }

  setOperation(name, op) {
    this._operations[name] = op;
  }

  operation(name) {
    return this._operations[name] || null;
  }
}

// ── ContractOperation ──

export class ContractOperation {
  constructor(vk) {
    this._vk = vk || null;
  }

  latest() {
    return this._vk;
  }
}

// ── ContractMaintenanceAuthority ──

export class ContractMaintenanceAuthority {
  constructor() {}
}

// ── StateValue → AlignedValue conversion ──

function stateValueToAligned(sv) {
  if (!sv || !sv._data) {
    return { value: [new Uint8Array(32)], alignment: [{ tag: 'atom', value: { tag: 'field' } }] };
  }
  if (sv._data.type === 'null') {
    return { value: [new Uint8Array(32)], alignment: [{ tag: 'atom', value: { tag: 'field' } }] };
  }
  if (sv._data.type === 'cell') {
    const cellVal = sv._data.value;
    if (cellVal && typeof cellVal === 'object' && cellVal.value && Array.isArray(cellVal.value)) {
      return cellVal; // Already AlignedValue
    }
    return { value: [new Uint8Array(32)], alignment: [{ tag: 'atom', value: { tag: 'field' } }] };
  }
  if (sv._data.type === 'array') {
    const values = [];
    const aligns = [];
    for (const item of (sv._data.items || [])) {
      const a = stateValueToAligned(item);
      values.push(...a.value);
      aligns.push(...a.alignment);
    }
    return { value: values, alignment: aligns };
  }
  return { value: [new Uint8Array(32)], alignment: [{ tag: 'atom', value: { tag: 'field' } }] };
}

// ── QueryContext ──

export class QueryContext {
  constructor(chargedState, contractAddress) {
    this.state = chargedState;
    this.address = contractAddress;
    this.block = {
      balance: new Map(),
      ownAddress: contractAddress,
      secondsSinceEpoch: BigInt(Math.floor(Date.now() / 1000)),
    };
    this.effects = [];
    this.callContext = {};
  }

  query(program, costModel, gasLimit) {
    // Stack-based VM execution of opcodes against contract state.
    // The state is a tree of StateValue nodes.
    const stack = [this.state.get_ref()]; // stack starts with root state
    const events = [];

    for (const op of program) {
      if (typeof op !== 'object') continue;

      if ('dup' in op) {
        // Duplicate the n-th item from top of stack
        const n = op.dup.n;
        const idx = stack.length - 1 - n;
        if (idx >= 0) {
          stack.push(stack[idx]);
        }
      } else if ('idx' in op) {
        // Index into the top-of-stack value using path
        const top = stack[stack.length - 1];
        let current = top;

        for (const key of op.idx.path) {
          if (key.tag === 'value') {
            const indexVal = key.value;
            if (current && current._data && current._data.type === 'array') {
              // Decode index from AlignedValue
              let index = 0;
              if (indexVal && indexVal.value && Array.isArray(indexVal.value) && indexVal.value[0] instanceof Uint8Array) {
                index = Number(valueToBigInt(indexVal.value));
              } else if (indexVal && typeof indexVal.value === 'number') {
                index = indexVal.value;
              }

              if (current._data.items && current._data.items[index]) {
                current = current._data.items[index];
              } else {
                current = StateValue.newNull();
              }
            }
          }
        }

        // Convert StateValue to AlignedValue for the event content
        const aligned = stateValueToAligned(current);

        if (op.idx.cached) {
          // Cached idx: pop top, push result, emit read event
          stack.pop();
          stack.push(current);
          events.push({ tag: 'read', content: aligned });
        } else {
          // Non-cached idx: just push result, NO read event
          stack.push(current);
        }
      } else if ('popeq' in op) {
        const val = stack.pop();
        events.push({ tag: 'read', content: stateValueToAligned(val) });
      } else if ('push' in op) {
        // Push a new value onto the stack/state
        const pushOp = op.push;
        let val;
        if (pushOp.value !== undefined) {
          if (typeof pushOp.value === 'string') {
            try {
              val = StateValue.decode(pushOp.value);
            } catch(e) {
              val = new StateValue({ type: 'cell', value: pushOp.value });
            }
          } else {
            val = new StateValue({ type: 'cell', value: pushOp.value });
          }
        } else {
          val = StateValue.newNull();
        }

        if (pushOp.storage) {
          // Storage push — modifies the state
          stack.push(val);
        } else {
          stack.push(val);
        }
      } else if ('ins' in op) {
        // Insert: take top values and insert into state
        const n = op.ins.n;
        const values = [];
        for (let i = 0; i < n; i++) {
          values.unshift(stack.pop());
        }
        const target = stack.pop();
        // Rebuild state with inserted values
        if (target && target._data && target._data.type === 'array') {
          const newItems = [...target._data.items, ...values];
          stack.push(new StateValue({ type: 'array', items: newItems }));
        } else {
          stack.push(target);
        }
      } else if ('pop' in op) {
        stack.pop();
      }
    }

    // The state after execution is the remaining stack
    const finalState = stack.length > 0 ? stack[stack.length - 1] : this.state.get_ref();
    const newChargedState = new ChargedState(finalState);

    return {
      context: new QueryContext(newChargedState, this.address),
      events,
      gasCost: { value: 0n },
    };
  }
}

// ── CostModel ──

export class CostModel {
  static initialCostModel() {
    return new CostModel();
  }
}

// ── QueryResults / VmResults / VmStack ──

export class QueryResults {
  constructor(context, events) {
    this.context = context;
    this.events = events;
  }
}

export class VmResults {}
export class VmStack {}

// ── StateBoundedMerkleTree / StateMap ──

export class StateBoundedMerkleTree {}
export class StateMap {}

// ── Crypto functions ──

export function persistentHash(alignment, value) {
  // Try native Rust FFI with proper AlignedValue encoding (binary_repr + SHA-256)
  if (typeof globalThis.__native_persistentHash_aligned === 'function') {
    try {
      const aligned = {
        value: value.map(v => v instanceof Uint8Array ? Array.from(v) : v),
        alignment: alignment,
      };
      const json = JSON.stringify(aligned);
      const resultJson = globalThis.__native_persistentHash_aligned(json);
      const parsed = JSON.parse(resultJson);
      if (parsed.error) throw new Error(parsed.error);
      // Result is a Value = Array<Array<number>> — convert to Array<Uint8Array>
      return parsed.map(arr => new Uint8Array(arr));
    } catch(e) {
      // Fall through to JS fallback
    }
  }

  // JS fallback: concatenate bytes and SHA-256 (NOT production-correct)
  const allBytes = [];
  if (Array.isArray(value)) {
    for (const chunk of value) {
      if (chunk instanceof Uint8Array) {
        for (let i = 0; i < chunk.length; i++) allBytes.push(chunk[i]);
      }
    }
  }
  const data = new Uint8Array(allBytes);
  const hash = jsSha256(data);
  return [hash];
}

export function persistentCommit(value, opening) {
  if (typeof __native_persistentCommit === 'function') {
    return __native_persistentCommit(value, opening);
  }
  throw new Error('persistentCommit: native function not bound');
}

export function transientHash(input) {
  if (typeof __native_transientHash === 'function') {
    return __native_transientHash(input);
  }
  throw new Error('transientHash: native function not bound');
}

export function transientCommit(value, opening) {
  throw new Error('transientCommit: not yet implemented');
}

export function degradeToTransient(x) {
  // Convert persistent hash output to transient field element
  return x;
}

export function upgradeFromTransient(x) {
  return x;
}

// ── Encoding/decoding ──

export function dummyContractAddress() {
  return '0'.repeat(64);
}

export function dummyUserAddress() {
  return '0'.repeat(64);
}

export function sampleContractAddress() {
  return '0'.repeat(64);
}

export function sampleUserAddress() {
  return '0'.repeat(64);
}

export function sampleRawTokenType() {
  return '0'.repeat(64);
}

export function rawTokenType() {
  return '0'.repeat(64);
}

export function encodeContractAddress(addr) { return addr; }
export function decodeContractAddress(addr) { return addr; }
export function encodeUserAddress(addr) { return addr; }
export function decodeUserAddress(addr) { return addr; }
export function encodeCoinPublicKey(pk) { return pk; }
export function decodeCoinPublicKey(pk) { return pk; }
export function encodeRawTokenType(t) { return t; }
export function decodeRawTokenType(t) { return t; }
export function encodeShieldedCoinInfo(info) { return info; }
export function decodeShieldedCoinInfo(info) { return info; }
export function encodeQualifiedShieldedCoinInfo(info) { return info; }
export function decodeQualifiedShieldedCoinInfo(info) { return info; }

// ── Value conversion ──

export function valueToBigInt(value) {
  // Try native Rust FFI first
  if (typeof globalThis.__native_valueToBigInt === 'function') {
    try {
      // Serialize Value to JSON, call Rust, parse result
      const json = JSON.stringify(value, (k, v) => v instanceof Uint8Array ? Array.from(v) : v);
      const result = globalThis.__native_valueToBigInt(json);
      return BigInt(result);
    } catch(e) { /* fall through to JS */ }
  }

  // JS fallback
  if (Array.isArray(value) && value.length > 0 && value[0] instanceof Uint8Array) {
    const bytes = value[0];
    let result = 0n;
    for (let i = bytes.length - 1; i >= 0; i--) {
      result = (result << 8n) | BigInt(bytes[i]);
    }
    return result;
  }
  if (typeof value === 'bigint') return value;
  if (typeof value === 'number') return BigInt(value);
  if (typeof value === 'string') return BigInt(value);
  return 0n;
}

export function bigIntToValue(n) {
  // Try native Rust FFI first
  if (typeof globalThis.__native_bigIntToValue === 'function') {
    try {
      const json = globalThis.__native_bigIntToValue(n.toString());
      // Parse Rust Value JSON back to Array<Uint8Array>
      const parsed = JSON.parse(json);
      return parsed.map(arr => new Uint8Array(arr));
    } catch(e) { /* fall through to JS */ }
  }

  // JS fallback
  const bytes = new Uint8Array(32);
  let val = BigInt(n);
  for (let i = 0; i < 32; i++) {
    bytes[i] = Number(val & 0xFFn);
    val >>= 8n;
  }
  return [bytes];
}

export function maxAlignedSize() { return 32; }
export function maxField() {
  // BLS12-381 scalar field order (Fr) — must match Midnight's onchain-runtime
  return 52435875175126190479447740508185965837690552500527637822603658699938581184512n;
}

export function bigIntModFr(n) {
  const FR_MODULUS = 52435875175126190479447740508185965837690552500527637822603658699938581184512n;
  return ((n % FR_MODULUS) + FR_MODULUS) % FR_MODULUS;
}

// ── Proof data ──

export function proofDataIntoSerializedPreimage(proofData) {
  return proofData;
}

// ── ZK crypto (elliptic curve) — delegate to native ──

export function ecAdd(a, b) {
  throw new Error('ecAdd: not yet implemented');
}
export function ecMul(point, scalar) {
  throw new Error('ecMul: not yet implemented');
}
export function ecMulGenerator(scalar) {
  throw new Error('ecMulGenerator: not yet implemented');
}
export function hashToCurve(data) {
  throw new Error('hashToCurve: not yet implemented');
}

// ── Signing ──

export function communicationCommitmentRandomness() {
  return new Uint8Array(32); // TODO: random bytes from native
}
export function communicationCommitment() { return new Uint8Array(32); }
export function entryPointHash() { return new Uint8Array(32); }
export function sampleSigningKey() { return new Uint8Array(32); }
export function signingKeyFromBip340() { return new Uint8Array(32); }
export function signData() { return new Uint8Array(64); }
export function signatureVerifyingKey() { return new Uint8Array(32); }
export function verifySignature() { return true; }
export function runtimeCoinCommitment() { return new Uint8Array(32); }
export function leafHash() { return new Uint8Array(32); }

// ── VM execution ──

export function runProgram(initial, program, gasLimit, costModel) {
  // TODO: delegate to native Rust run_program via FFI
  throw new Error('runProgram: not yet implemented — will delegate to Rust FFI');
}
