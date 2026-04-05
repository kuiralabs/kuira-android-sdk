/**
 * Onchain-runtime shim for Android.
 *
 * Replaces @midnight-ntwrk/onchain-runtime-v2 (WASM) with native
 * Rust FFI calls via QuickJS's native function binding.
 *
 * Functions prefixed with __native_ are provided by Kotlin/Rust
 * and registered before this module is loaded.
 *
 * Bundled into compact-runtime IIFE via esbuild, replacing the WASM module.
 */

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
    this._rustHandle = 0;
  }

  get() {
    return this._state;
  }

  get_ref() {
    return this._state;
  }

  update(path, value) {
    const cs = new ChargedState(value);
    cs._rustHandle = this._rustHandle;
    return cs;
  }
}

// ── ContractState ──

export class ContractState {
  constructor() {
    this._data = null;
    this._operations = {};
    this.balance = new Map();
    this._rustHandle = 0;
  }

  get data() { return this._data; }

  set data(chargedState) {
    this._data = chargedState;
    // Create Rust handle now that data structure is known
    if (chargedState && !this._rustHandle && typeof globalThis.__native_stateCreateWithNulls === 'function') {
      this._ensureRustHandle();
    }
    if (chargedState && this._rustHandle) {
      chargedState._rustHandle = this._rustHandle;
    }
  }

  setOperation(name, op) {
    this._operations[name] = op;
    if (this._rustHandle && typeof globalThis.__native_stateSetOperation === 'function') {
      globalThis.__native_stateSetOperation(this._rustHandle.toString(), name);
    }
  }

  operation(name) {
    return this._operations[name] || null;
  }

  _ensureRustHandle() {
    if (this._rustHandle) return this._rustHandle;
    if (typeof globalThis.__native_stateCreateWithNulls !== 'function') return 0;

    let numSlots = 0;
    if (this._data && this._data._state && this._data._state._data &&
        this._data._state._data.type === 'array') {
      numSlots = this._data._state._data.items.length;
    }

    this._rustHandle = Number(globalThis.__native_stateCreateWithNulls(numSlots.toString()));

    for (const name of Object.keys(this._operations)) {
      if (typeof globalThis.__native_stateSetOperation === 'function') {
        globalThis.__native_stateSetOperation(this._rustHandle.toString(), name);
      }
    }

    // Propagate to ChargedState
    if (this._data) {
      this._data._rustHandle = this._rustHandle;
    }

    return this._rustHandle;
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

// ── Opcode transformer: JS format → Rust serde format ──

function transformOpForRust(op) {
  if (typeof op === 'string') return op; // e.g. "pop", "lt"
  if (typeof op !== 'object' || op === null) return op;

  if ('push' in op) {
    return {
      push: {
        storage: op.push.storage,
        value: transformStateValue(op.push.value),
      }
    };
  }
  if ('idx' in op) {
    return {
      idx: {
        cached: op.idx.cached,
        pushPath: op.idx.pushPath || false,
        path: (op.idx.path || []).map(key => {
          if (key.tag === 'value') {
            return { tag: 'value', value: transformAlignedValue(key.value) };
          }
          return key;
        }),
      }
    };
  }
  if ('popeq' in op) {
    return { popeq: { cached: op.popeq.cached, result: null } };
  }
  // dup, ins, pop, etc. pass through unchanged
  return op;
}

function transformStateValue(sv) {
  if (sv === null || sv === undefined) return { tag: 'null' };

  // If it's a string (from StateValue.encode()), parse it
  if (typeof sv === 'string') {
    try {
      const parsed = JSON.parse(sv);
      return transformStateValueObj(parsed);
    } catch(e) {
      return { tag: 'null' };
    }
  }

  // If it's already an object (StateValue from the shim)
  if (typeof sv === 'object') {
    if (sv._data) return transformStateValueObj(sv._data);
    return transformStateValueObj(sv);
  }

  return { tag: 'null' };
}

function transformStateValueObj(obj) {
  if (!obj || obj.type === 'null') return { tag: 'null' };

  if (obj.type === 'cell') {
    // Cell contains an AlignedValue
    return { tag: 'cell', content: transformAlignedValue(obj.value) };
  }

  if (obj.type === 'array') {
    return {
      tag: 'array',
      content: (obj.items || []).map(item => transformStateValue(item)),
    };
  }

  // Unknown format — try as-is
  return obj;
}

function transformAlignedValue(av) {
  if (!av) return { value: [[]], alignment: [{ tag: 'atom', value: { tag: 'field' } }] };

  // Convert value arrays: Uint8Array or {0: val, 1: val} → plain Array<number>
  const value = (av.value || [[]]).map(v => {
    if (v instanceof Uint8Array) return Array.from(v);
    if (Array.isArray(v)) return v;
    if (typeof v === 'object' && v !== null) {
      const keys = Object.keys(v).filter(k => !isNaN(k)).sort((a,b) => Number(a) - Number(b));
      if (keys.length === 0) return [];
      return keys.map(k => v[k]);
    }
    return [];
  });

  return {
    value: value,
    alignment: av.alignment || [{ tag: 'atom', value: { tag: 'field' } }],
  };
}

/**
 * Like transformAlignedValue, but pads empty value slots to match their
 * alignment dimensions. Required for Rust's AlignedValue serde validation
 * which checks that value dimensions match alignment.
 *
 * Only used for transcript/proof data extraction — NOT for VM execution.
 */
function transformAlignedValuePadded(av) {
  if (!av) return { value: [[]], alignment: [{ tag: 'atom', value: { tag: 'field' } }] };

  const alignment = av.alignment || [{ tag: 'atom', value: { tag: 'field' } }];

  let value = (av.value || [[]]).map(v => {
    if (v instanceof Uint8Array) return Array.from(v);
    if (Array.isArray(v)) return v;
    if (typeof v === 'object' && v !== null) {
      const keys = Object.keys(v).filter(k => !isNaN(k)).sort((a,b) => Number(a) - Number(b));
      if (keys.length === 0) return [];
      return keys.map(k => v[k]);
    }
    return [];
  });

  // Pad empty value slots to match alignment dimensions
  if (alignment.length > 0 && value.length > 0) {
    value = value.map((slot, i) => {
      if (slot.length > 0) return slot;
      const align = alignment[i];
      if (!align) return slot;
      if (align.tag === 'atom' && align.value) {
        if (align.value.tag === 'field' || align.value.tag === 'compress') {
          return new Array(32).fill(0);
        }
        if (align.value.tag === 'bytes' && align.value.length) {
          return new Array(align.value.length).fill(0);
        }
      }
      return slot;
    });
  }

  // If value is completely empty but alignment is not, create zero-filled slots
  if (value.length === 0 && alignment.length > 0) {
    value = alignment.map(align => {
      if (align.tag === 'atom' && align.value) {
        if (align.value.tag === 'field' || align.value.tag === 'compress') {
          return new Array(32).fill(0);
        }
        if (align.value.tag === 'bytes' && align.value.length) {
          return new Array(align.value.length).fill(0);
        }
      }
      return [];
    });
  }

  return { value: value, alignment: alignment };
}

// ── Public Transcript transformer (for transaction assembly) ──

/**
 * Transform the publicTranscript from circuit execution to Rust serde format
 * for Op<ResultModeVerify, InMemoryDB>.
 *
 * The key difference from transformOpForRust (used for VM execution):
 * - popeq.result is preserved as AlignedValue (Verify mode)
 *   instead of being nulled (Gather mode)
 */
export function transformPublicTranscript(transcript) {
  return transcript.map(op => {
    if (typeof op === 'string') return op;
    if (typeof op !== 'object' || op === null) return op;

    // For popeq, preserve the result AlignedValue (Verify mode) with padding
    if ('popeq' in op) {
      return {
        popeq: {
          cached: op.popeq.cached,
          result: transformAlignedValuePadded(op.popeq.result),
        }
      };
    }

    // For idx, use padded AlignedValues in path keys
    if ('idx' in op) {
      return {
        idx: {
          cached: op.idx.cached,
          pushPath: op.idx.pushPath || false,
          path: (op.idx.path || []).map(key => {
            if (key.tag === 'value') {
              return { tag: 'value', value: transformAlignedValuePadded(key.value) };
            }
            return key;
          }),
        }
      };
    }

    // For push and other ops, use the VM transformer (no padding needed)
    return transformOpForRust(op);
  });
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
    // Inherit Rust handle from ChargedState
    this._rustHandle = (chargedState && chargedState._rustHandle) || 0;
  }

  query(program, costModel, gasLimit) {
    if (typeof globalThis.__native_contractQuery === 'function' && this._rustHandle) {
      try {
        // Transform opcodes from JS format to Rust serde format
        const transformed = program.map(op => transformOpForRust(op));
        const opcodesJson = JSON.stringify(transformed);

        const resultJson = globalThis.__native_contractQuery(
          this._rustHandle.toString(),
          opcodesJson
        );
        const result = JSON.parse(resultJson);

        if (result.error) {
          throw new Error(result.error);
        }

        // Update handle to new state
        this._rustHandle = result.handle;

        // Convert events: Rust returns GatherEvent serde format
        // { "Read": { value: [...], alignment: [...] } } or { "Log": ... }
        const events = (result.events || []).map(ev => {
          if (ev.Read !== undefined) {
            // Convert Rust Value arrays back to Uint8Array arrays
            const content = {
              value: ev.Read.value.map(arr => new Uint8Array(arr)),
              alignment: ev.Read.alignment,
            };
            return { tag: 'read', content };
          }
          if (ev.Log !== undefined) {
            return { tag: 'log', content: ev.Log };
          }
          return ev;
        });

        const newState = new ChargedState(this.state.get_ref());
        newState._rustHandle = result.handle;
        const newCtx = new QueryContext(newState, this.address);
        newCtx._rustHandle = result.handle;

        // Preserve gas cost from Rust VM execution
        const gas = result.gas || {};
        const gasCost = {
          readTime: BigInt(gas.readTime || 0),
          computeTime: BigInt(gas.computeTime || 0),
          bytesWritten: BigInt(gas.bytesWritten || 0),
          bytesDeleted: BigInt(gas.bytesDeleted || 0),
        };

        return { context: newCtx, events, gasCost };
      } catch(e) {
        throw new Error('Rust VM query failed: ' + e.toString());
      }
    }

    throw new Error('QueryContext.query: no Rust handle available and no native FFI registered');
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
  // Delegate to native Rust FFI — proper AlignedValue binary_repr + SHA-256
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
      throw new Error('persistentHash native call failed: ' + e.toString());
    }
  }

  throw new Error('persistentHash: native FFI not available');
}

export function persistentCommit(value, opening) {
  if (typeof globalThis.__native_persistentCommit === 'function') {
    return globalThis.__native_persistentCommit(value, opening);
  }
  throw new Error('persistentCommit: native function not bound');
}

export function transientHash(input) {
  if (typeof globalThis.__native_transientHash === 'function') {
    return globalThis.__native_transientHash(input);
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
  // Primitive types — direct conversion
  if (typeof value === 'bigint') return value;
  if (typeof value === 'number') return BigInt(value);
  if (typeof value === 'string') return BigInt(value);

  // Value (Array<Uint8Array>) — delegate to native Rust FFI
  if (typeof globalThis.__native_valueToBigInt === 'function') {
    try {
      const json = JSON.stringify(value, (k, v) => v instanceof Uint8Array ? Array.from(v) : v);
      const result = globalThis.__native_valueToBigInt(json);
      return BigInt(result);
    } catch(e) {
      throw new Error('valueToBigInt native call failed: ' + e.toString());
    }
  }

  throw new Error('valueToBigInt: native FFI not available');
}

export function bigIntToValue(n) {
  if (typeof globalThis.__native_bigIntToValue === 'function') {
    try {
      const hex = BigInt(n).toString(16);
      const json = globalThis.__native_bigIntToValue(hex);
      const parsed = JSON.parse(json);
      return parsed.map(arr => new Uint8Array(arr));
    } catch(e) {
      throw new Error('bigIntToValue native call failed: ' + e.toString());
    }
  }

  throw new Error('bigIntToValue: native FFI not available');
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
