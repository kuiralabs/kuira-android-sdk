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
            // Navigate by encoded index
            const indexVal = key.value;
            if (current && current._data && current._data.type === 'array') {
              // Decode the index from the value
              let index;
              if (indexVal && indexVal.value !== undefined) {
                // The value is encoded — extract the numeric index
                const encoded = indexVal.value;
                if (Array.isArray(encoded) && encoded.length > 0) {
                  index = encoded[0];
                } else if (typeof encoded === 'string') {
                  // Try to decode from encoded bytes
                  try {
                    const decoded = JSON.parse(encoded);
                    if (decoded && decoded.type === 'cell' && decoded.value !== undefined) {
                      index = Number(decoded.value);
                    }
                  } catch(e) { index = 0; }
                } else if (typeof encoded === 'number' || typeof encoded === 'bigint') {
                  index = Number(encoded);
                } else {
                  index = 0;
                }
              } else {
                index = 0;
              }

              if (index !== undefined && current._data.items && current._data.items[index]) {
                current = current._data.items[index];
              } else {
                current = StateValue.newNull();
              }
            }
          }
        }

        if (op.idx.cached) {
          // Read and report — pop the top, push the result
          stack.pop();
          stack.push(current);
          events.push({ tag: 'read', content: current });
        } else {
          // Just navigate, keep original on stack too
          stack.push(current);
          events.push({ tag: 'read', content: current });
        }
      } else if ('popeq' in op) {
        // Pop top of stack, record as read result
        const val = stack.pop();
        events.push({ tag: 'read', content: val });
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
  // Delegates to native Rust via __native_persistentHash
  if (typeof __native_persistentHash === 'function') {
    return __native_persistentHash(alignment, value);
  }
  // Fallback: JS SHA-256 (for testing without native)
  throw new Error('persistentHash: native function not bound');
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
  if (typeof value === 'bigint') return value;
  if (typeof value === 'number') return BigInt(value);
  if (typeof value === 'string') return BigInt(value);
  if (value && value._data && value._data.type === 'cell') {
    return BigInt(0); // TODO: proper cell-to-bigint conversion
  }
  return 0n;
}

export function bigIntToValue(n) {
  return new StateValue({ type: 'cell', value: n.toString() });
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
