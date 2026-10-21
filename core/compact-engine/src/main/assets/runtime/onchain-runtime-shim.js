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

  // A Map/Set ledger value. Native StateValue::Map wraps a HashMap<AlignedValue,
  // StateValue> that serde-serializes (collect_map) to a JSON object. A constructor
  // only ever produces an EMPTY map (every Map/Set ledger field starts empty), which
  // is content `{}`. Non-empty maps are never built here — post-deploy inserts mutate
  // the native state handle via idx/ins ops, not through this factory.
  static newMap(stateMap) {
    return new StateValue({ type: 'map', map: stateMap || new StateMap() });
  }

  // A HistoricMerkleTree ledger field. Like newMap, this only ever produces an EMPTY
  // tree: a constructor cannot carry leaves, and post-deploy inserts mutate the native
  // state through the VM's idx/ins ops rather than coming back through this factory.
  //
  // The height has to travel, which is why it is kept rather than collapsed to a Null the
  // way other slots are: the tree's height is fixed when it is created and the VM's insert
  // op has nowhere to learn it from afterwards.
  static newBoundedMerkleTree(tree) {
    const height = tree && typeof tree.height === 'number' ? tree.height : 0;
    if (!(height > 0 && height <= 32)) {
      throw new Error(
        'newBoundedMerkleTree: height must be 1..=32, got ' + height +
        ' (the ledger cannot serialize a tree outside that range)');
    }
    return new StateValue({ type: 'boundedMerkleTree', height });
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
    this.state = stateValue; // Public property — v0.15.0 contract code accesses .state directly
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

function stateValueDescriptor(svData) {
  // Convert a JS StateValue's internal `_data` tree into the descriptor JSON the native
  // build_state_from_descriptor understands: array → {array:[...]} recursively,
  // boundedMerkleTree → {merkleTree:{height}}, everything else → null. Cells/nulls become
  // null skeletons — constructors fill leaf values via subsequent `ins` ops; only the
  // nested container shape must exist natively for those inserts to land.
  //
  // A tree is the one leaf that cannot be a null skeleton. Its height is fixed when it is
  // created and the `ins` op has nowhere to learn it from afterwards, so a tree field left
  // Null is a contract that cannot deploy at all — which is what a HistoricMerkleTree
  // contract used to hit. Everything else still starts Null exactly as before, Map fields
  // included; narrowing this to trees is deliberate, since widening it would change the
  // initial state of every contract already shipping.
  if (svData && svData.type === 'boundedMerkleTree') {
    return { merkleTree: { height: svData.height } };
  }
  if (!svData || svData.type !== 'array') return null;
  return { array: svData.items.map((item) => stateValueDescriptor(item && item._data)) };
}

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

    // Build the FULL nested skeleton, not just the top-level slot count. A contract whose
    // initial ledger state has nested containers (e.g. an array of records) needs those
    // sub-arrays to exist natively *before* the constructor's `ins` ops navigate into
    // them. Passing a flat count left nested slots as Null, so `ins` into slot 0 failed
    // with "attempted to ins, only array, map, and bmt are supported".
    const rootData = this._data && this._data._state && this._data._state._data;
    const descriptor = rootData && rootData.type === 'array'
      ? JSON.stringify(stateValueDescriptor(rootData))
      : '0';

    this._rustHandle = Number(globalThis.__native_stateCreateWithNulls(descriptor));

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

  if (obj.type === 'map') {
    // Native StateValue::Map(HashMap) serde = collect_map → a JSON object keyed by
    // the entries' AlignedValues. Constructor maps are always empty, so content = {}.
    const entries = (obj.map && obj.map.entries) || [];
    const content = {};
    for (const [key, value] of entries) {
      content[key] = transformStateValue(value);
    }
    return { tag: 'map', content };
  }

  if (obj.type === 'boundedMerkleTree') {
    // A pushed tree, which is how a HistoricMerkleTree field actually arrives: the
    // compiler emits it as an opcode value inside an array of [tree, counter, historic
    // roots], NOT through the initial-state descriptor. Content is [height, leaves], and
    // a constructor's tree is always blank, so the leaves are {}. The exact shape is
    // pinned on the native side by bmt_wire_format in contract_ffi.rs, because a drift
    // here is a failure to deploy rather than a degraded result.
    return { tag: 'boundedMerkleTree', content: [obj.height, {}] };
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

  // Empty value slots are emitted as EMPTY atoms ([]), NOT zero-padded
  // ([0,...,0]). Native transcript parsing is now upstream serde, whose
  // AlignedValue deserializer rejects any atom not in normal form: a
  // trailing-zero atom is not normal-form, an empty atom is (and
  // Alignment::fits accepts it for field/compress/bytes). The old hand-parser
  // tolerated the zero padding by truncating it; only placeholder/empty reads
  // were ever padded — real read values already arrive normal-form from the VM.
  if (value.length > 0) {
    value = value.map(slot => (slot.length > 0 ? slot : []));
  } else if (alignment.length > 0) {
    value = alignment.map(() => []);
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

/**
 * A bounded Merkle tree ledger value.
 *
 * Only the height lives here. The tree itself is held natively — it is created by
 * `__native_stateCreateWithNulls` from the descriptor this height ends up in, and every
 * later insert goes through the VM, so there is nothing for JS to keep in step. That is
 * deliberate: a tree whose leaves were tracked in JS as well would be a second source of
 * truth for a consensus-critical root.
 *
 * The methods the real runtime marks `@internal` (root, update, rehash, findPathForLeaf,
 * pathForLeaf, collapse) are not implemented, because generated contract code does not
 * call them — it reaches the tree through VM opcodes. They throw rather than return a
 * plausible wrong answer, which is what the previous empty stub effectively did.
 */
export class StateBoundedMerkleTree {
  constructor(height) {
    this.height = height;
  }

  root() { throw new Error('StateBoundedMerkleTree.root: the tree lives natively; read it through the VM'); }
  update() { throw new Error('StateBoundedMerkleTree.update: insert through the VM, not here'); }
  rehash() { throw new Error('StateBoundedMerkleTree.rehash: the VM rehashes natively'); }
  findPathForLeaf() { throw new Error('StateBoundedMerkleTree.findPathForLeaf: not available in the shim'); }
  pathForLeaf() { throw new Error('StateBoundedMerkleTree.pathForLeaf: not available in the shim'); }
  collapse() { throw new Error('StateBoundedMerkleTree.collapse: not available in the shim'); }
}
export class StateMap {
  constructor() {
    // [key, value] entries. Empty at construction — the only shape a contract
    // constructor produces for a Map/Set ledger field. Post-deploy inserts go
    // through native state ops, not this class.
    this.entries = [];
  }
}

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

export function transientHash(alignment, value) {
  // transientHash2 calls this with TWO args — (alignment, toValue(value)) —
  // exactly like persistentHash. The old 1-param signature dropped the value,
  // so the native side would have hashed the alignment alone and returned a
  // plausible-looking field element for the wrong preimage.
  if (typeof globalThis.__native_transientHash === 'function') {
    try {
      const aligned = {
        value: value.map(v => v instanceof Uint8Array ? Array.from(v) : v),
        alignment: alignment,
      };
      const resultJson = globalThis.__native_transientHash(JSON.stringify(aligned));
      const parsed = JSON.parse(resultJson);
      if (parsed.error) throw new Error(parsed.error);
      return parsed.map(arr => new Uint8Array(arr));
    } catch(e) {
      throw new Error('transientHash native call failed: ' + e.toString());
    }
  }
  throw new Error('transientHash: native function not bound');
}

export function transientCommit(value, opening) {
  throw new Error('transientCommit: not yet implemented');
}

function callNativeValueConversion(native, value, name) {
  if (typeof native !== 'function') {
    throw new Error(name + ': native function not bound');
  }
  try {
    const payload = value.map(v => v instanceof Uint8Array ? Array.from(v) : v);
    const parsed = JSON.parse(native(JSON.stringify(payload)));
    if (parsed.error) throw new Error(parsed.error);
    return parsed.map(arr => new Uint8Array(arr));
  } catch(e) {
    throw new Error(name + ' native call failed: ' + e.toString());
  }
}

export function degradeToTransient(x) {
  // Not the identity this used to be: the library op keeps the low 31 bytes and drops
  // the top one, so returning x disagreed with the on-chain circuit whenever that byte
  // was set — silently, which is worse than not being implemented.
  return callNativeValueConversion(
    globalThis.__native_degradeToTransient, x, 'degradeToTransient');
}

export function upgradeFromTransient(x) {
  return callNativeValueConversion(
    globalThis.__native_upgradeFromTransient, x, 'upgradeFromTransient');
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
/**
 * The domain-separated hash a Merkle tree leaf is stored under.
 *
 * `leaf_hash(v) = persistent_hash("mdn:lh" || binary_repr(v))`. The compiler emits a call
 * to this wherever a value is inserted into a HistoricMerkleTree — the generated insert
 * pushes `StateValue.newCell(leafHash(value))` — so it has to agree with the node exactly.
 *
 * This used to return 32 zero bytes. That is why it is delegated rather than written here:
 * a stub hashed every leaf alike, so no root could ever be right, and it did so quietly.
 *
 * Takes an AlignedValue and returns one, because the result goes straight into newCell.
 */
export function leafHash(value) {
  if (typeof globalThis.__native_leafHash !== 'function') {
    throw new Error('leafHash: native FFI not available');
  }
  const resultJson = globalThis.__native_leafHash(JSON.stringify(transformAlignedValue(value)));
  let parsed;
  try {
    parsed = JSON.parse(resultJson);
  } catch (e) {
    throw new Error('leafHash: native call returned unparseable JSON: ' + resultJson);
  }
  if (parsed.error) throw new Error('leafHash: ' + parsed.error);
  return {
    value: (parsed.value || []).map((arr) => new Uint8Array(arr)),
    alignment: parsed.alignment,
  };
}

// ── VM execution ──

export function runProgram(initial, program, gasLimit, costModel) {
  // TODO: delegate to native Rust run_program via FFI
  throw new Error('runProgram: not yet implemented — will delegate to Rust FFI');
}
