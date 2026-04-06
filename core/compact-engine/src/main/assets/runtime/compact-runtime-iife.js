var __compactRuntime = (() => {
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __export = (target, all) => {
    for (var name in all)
      __defProp(target, name, { get: all[name], enumerable: true });
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toCommonJS = (mod) => __copyProps(__defProp({}, "__esModule", { value: true }), mod);

  // index.js
  var index_exports = {};
  __export(index_exports, {
    Bytes32Descriptor: () => Bytes32Descriptor,
    CONTRACT_ADDRESS_BYTE_LENGTH: () => CONTRACT_ADDRESS_BYTE_LENGTH,
    ChargedState: () => ChargedState,
    CompactError: () => CompactError,
    CompactTypeBoolean: () => CompactTypeBoolean,
    CompactTypeBytes: () => CompactTypeBytes,
    CompactTypeEnum: () => CompactTypeEnum,
    CompactTypeField: () => CompactTypeField,
    CompactTypeJubjubPoint: () => CompactTypeJubjubPoint,
    CompactTypeMerkleTreeDigest: () => CompactTypeMerkleTreeDigest,
    CompactTypeMerkleTreePath: () => CompactTypeMerkleTreePath,
    CompactTypeMerkleTreePathEntry: () => CompactTypeMerkleTreePathEntry,
    CompactTypeOpaqueString: () => CompactTypeOpaqueString,
    CompactTypeOpaqueUint8Array: () => CompactTypeOpaqueUint8Array,
    CompactTypeUnsignedInteger: () => CompactTypeUnsignedInteger,
    CompactTypeVector: () => CompactTypeVector,
    ContractAddressDescriptor: () => ContractAddressDescriptor,
    ContractMaintenanceAuthority: () => ContractMaintenanceAuthority,
    ContractOperation: () => ContractOperation,
    ContractState: () => ContractState,
    CostModel: () => CostModel,
    DUMMY_ADDRESS: () => DUMMY_ADDRESS,
    HEX_REGEX_NO_PREFIX: () => HEX_REGEX_NO_PREFIX,
    MAX_FIELD: () => MAX_FIELD,
    MaxUint8Descriptor: () => MaxUint8Descriptor,
    QueryContext: () => QueryContext,
    QueryResults: () => QueryResults,
    ShieldedCoinInfoDescriptor: () => ShieldedCoinInfoDescriptor,
    ShieldedCoinRecipientDescriptor: () => ShieldedCoinRecipientDescriptor,
    StateBoundedMerkleTree: () => StateBoundedMerkleTree,
    StateMap: () => StateMap,
    StateValue: () => StateValue,
    VmResults: () => VmResults,
    VmStack: () => VmStack,
    ZswapCoinPublicKeyDescriptor: () => ZswapCoinPublicKeyDescriptor,
    addField: () => addField,
    alignedConcat: () => alignedConcat,
    assert: () => assert,
    bigIntModFr: () => bigIntModFr,
    bigIntToValue: () => bigIntToValue,
    checkRuntimeVersion: () => checkRuntimeVersion,
    communicationCommitment: () => communicationCommitment,
    communicationCommitmentRandomness: () => communicationCommitmentRandomness,
    constructJubjubPoint: () => constructJubjubPoint,
    contractDependencies: () => contractDependencies,
    convertBytesToField: () => convertBytesToField,
    convertBytesToUint: () => convertBytesToUint,
    convertFieldToBytes: () => convertFieldToBytes,
    createCircuitContext: () => createCircuitContext,
    createConstructorContext: () => createConstructorContext,
    createWitnessContext: () => createWitnessContext,
    createZswapInput: () => createZswapInput,
    createZswapOutput: () => createZswapOutput,
    decodeCoinPublicKey: () => decodeCoinPublicKey,
    decodeContractAddress: () => decodeContractAddress,
    decodeQualifiedShieldedCoinInfo: () => decodeQualifiedShieldedCoinInfo,
    decodeRawTokenType: () => decodeRawTokenType,
    decodeRecipient: () => decodeRecipient,
    decodeShieldedCoinInfo: () => decodeShieldedCoinInfo,
    decodeUserAddress: () => decodeUserAddress,
    decodeZswapLocalState: () => decodeZswapLocalState,
    degradeToTransient: () => degradeToTransient2,
    dummyContractAddress: () => dummyContractAddress,
    dummyUserAddress: () => dummyUserAddress,
    ecAdd: () => ecAdd2,
    ecMul: () => ecMul2,
    ecMulGenerator: () => ecMulGenerator2,
    emptyRunningCost: () => emptyRunningCost,
    emptyZswapLocalState: () => emptyZswapLocalState,
    encodeCoinPublicKey: () => encodeCoinPublicKey,
    encodeContractAddress: () => encodeContractAddress,
    encodeQualifiedShieldedCoinInfo: () => encodeQualifiedShieldedCoinInfo,
    encodeRawTokenType: () => encodeRawTokenType,
    encodeRecipient: () => encodeRecipient,
    encodeShieldedCoinInfo: () => encodeShieldedCoinInfo,
    encodeUserAddress: () => encodeUserAddress,
    encodeZswapLocalState: () => encodeZswapLocalState,
    entryPointHash: () => entryPointHash,
    fromHex: () => fromHex,
    hasCoinCommitment: () => hasCoinCommitment,
    hashToCurve: () => hashToCurve2,
    isContractAddress: () => isContractAddress,
    isEncodedContractAddress: () => isEncodedContractAddress,
    jubjubPointX: () => jubjubPointX,
    jubjubPointY: () => jubjubPointY,
    leafHash: () => leafHash,
    maxAlignedSize: () => maxAlignedSize,
    maxField: () => maxField,
    mulField: () => mulField,
    ownPublicKey: () => ownPublicKey,
    persistentCommit: () => persistentCommit2,
    persistentHash: () => persistentHash2,
    proofDataIntoSerializedPreimage: () => proofDataIntoSerializedPreimage,
    queryLedgerState: () => queryLedgerState,
    rawTokenType: () => rawTokenType,
    runProgram: () => runProgram,
    runtimeCoinCommitment: () => runtimeCoinCommitment,
    sampleContractAddress: () => sampleContractAddress,
    sampleRawTokenType: () => sampleRawTokenType,
    sampleSigningKey: () => sampleSigningKey,
    sampleUserAddress: () => sampleUserAddress,
    signData: () => signData,
    signatureVerifyingKey: () => signatureVerifyingKey,
    signingKeyFromBip340: () => signingKeyFromBip340,
    subField: () => subField,
    toHex: () => toHex,
    transformPublicTranscript: () => transformPublicTranscript,
    transientCommit: () => transientCommit2,
    transientHash: () => transientHash2,
    typeError: () => typeError,
    upgradeFromTransient: () => upgradeFromTransient2,
    valueToBigInt: () => valueToBigInt,
    verifySignature: () => verifySignature,
    versionString: () => versionString
  });

  // object-inspect-shim.js
  function inspect(obj) {
    try {
      return JSON.stringify(obj);
    } catch (e) {
      return String(obj);
    }
  }
  var object_inspect_shim_default = inspect;

  // error.js
  var CompactError = class extends Error {
    constructor(msg) {
      super(msg);
      this.name = "CompactError";
    }
  };
  function assert(b, s) {
    if (!b) {
      const msg = `failed assert: ${s}`;
      throw new CompactError(msg);
    }
  }
  function typeError(who, what, where, type, x) {
    const msg = `type error: ${who} ${what} at ${where}; expected value of type ${type} but received ${object_inspect_shim_default(x)}`;
    throw new CompactError(msg);
  }

  // onchain-runtime-v3.js
  var StateValue = class _StateValue {
    constructor(data) {
      this._data = data || null;
    }
    static newNull() {
      return new _StateValue({ type: "null" });
    }
    static newCell(value) {
      return new _StateValue({ type: "cell", value });
    }
    static newArray() {
      return new _StateValue({ type: "array", items: [] });
    }
    arrayPush(item) {
      if (this._data.type !== "array") throw new Error("Not an array");
      const copy = new _StateValue({
        type: "array",
        items: [...this._data.items, item]
      });
      return copy;
    }
    encode() {
      return JSON.stringify(this._data);
    }
    static decode(encoded) {
      return new _StateValue(JSON.parse(encoded));
    }
  };
  var ChargedState = class _ChargedState {
    constructor(stateValue) {
      this._state = stateValue;
      this.state = stateValue;
      this._rustHandle = 0;
    }
    get() {
      return this._state;
    }
    get_ref() {
      return this._state;
    }
    update(path, value) {
      const cs = new _ChargedState(value);
      cs._rustHandle = this._rustHandle;
      return cs;
    }
  };
  var ContractState = class {
    constructor() {
      this._data = null;
      this._operations = {};
      this.balance = /* @__PURE__ */ new Map();
      this._rustHandle = 0;
    }
    get data() {
      return this._data;
    }
    set data(chargedState) {
      this._data = chargedState;
      if (chargedState && !this._rustHandle && typeof globalThis.__native_stateCreateWithNulls === "function") {
        this._ensureRustHandle();
      }
      if (chargedState && this._rustHandle) {
        chargedState._rustHandle = this._rustHandle;
      }
    }
    setOperation(name, op) {
      this._operations[name] = op;
      if (this._rustHandle && typeof globalThis.__native_stateSetOperation === "function") {
        globalThis.__native_stateSetOperation(this._rustHandle.toString(), name);
      }
    }
    operation(name) {
      return this._operations[name] || null;
    }
    _ensureRustHandle() {
      if (this._rustHandle) return this._rustHandle;
      if (typeof globalThis.__native_stateCreateWithNulls !== "function") return 0;
      let numSlots = 0;
      if (this._data && this._data._state && this._data._state._data && this._data._state._data.type === "array") {
        numSlots = this._data._state._data.items.length;
      }
      this._rustHandle = Number(globalThis.__native_stateCreateWithNulls(numSlots.toString()));
      for (const name of Object.keys(this._operations)) {
        if (typeof globalThis.__native_stateSetOperation === "function") {
          globalThis.__native_stateSetOperation(this._rustHandle.toString(), name);
        }
      }
      if (this._data) {
        this._data._rustHandle = this._rustHandle;
      }
      return this._rustHandle;
    }
  };
  var ContractOperation = class {
    constructor(vk) {
      this._vk = vk || null;
    }
    latest() {
      return this._vk;
    }
  };
  var ContractMaintenanceAuthority = class {
    constructor() {
    }
  };
  function transformOpForRust(op) {
    if (typeof op === "string") return op;
    if (typeof op !== "object" || op === null) return op;
    if ("push" in op) {
      return {
        push: {
          storage: op.push.storage,
          value: transformStateValue(op.push.value)
        }
      };
    }
    if ("idx" in op) {
      return {
        idx: {
          cached: op.idx.cached,
          pushPath: op.idx.pushPath || false,
          path: (op.idx.path || []).map((key) => {
            if (key.tag === "value") {
              return { tag: "value", value: transformAlignedValue(key.value) };
            }
            return key;
          })
        }
      };
    }
    if ("popeq" in op) {
      return { popeq: { cached: op.popeq.cached, result: null } };
    }
    return op;
  }
  function transformStateValue(sv) {
    if (sv === null || sv === void 0) return { tag: "null" };
    if (typeof sv === "string") {
      try {
        const parsed = JSON.parse(sv);
        return transformStateValueObj(parsed);
      } catch (e) {
        return { tag: "null" };
      }
    }
    if (typeof sv === "object") {
      if (sv._data) return transformStateValueObj(sv._data);
      return transformStateValueObj(sv);
    }
    return { tag: "null" };
  }
  function transformStateValueObj(obj) {
    if (!obj || obj.type === "null") return { tag: "null" };
    if (obj.type === "cell") {
      return { tag: "cell", content: transformAlignedValue(obj.value) };
    }
    if (obj.type === "array") {
      return {
        tag: "array",
        content: (obj.items || []).map((item) => transformStateValue(item))
      };
    }
    return obj;
  }
  function transformAlignedValue(av) {
    if (!av) return { value: [[]], alignment: [{ tag: "atom", value: { tag: "field" } }] };
    const value = (av.value || [[]]).map((v) => {
      if (v instanceof Uint8Array) return Array.from(v);
      if (Array.isArray(v)) return v;
      if (typeof v === "object" && v !== null) {
        const keys = Object.keys(v).filter((k) => !isNaN(k)).sort((a, b) => Number(a) - Number(b));
        if (keys.length === 0) return [];
        return keys.map((k) => v[k]);
      }
      return [];
    });
    return {
      value,
      alignment: av.alignment || [{ tag: "atom", value: { tag: "field" } }]
    };
  }
  function transformAlignedValuePadded(av) {
    if (!av) return { value: [[]], alignment: [{ tag: "atom", value: { tag: "field" } }] };
    const alignment = av.alignment || [{ tag: "atom", value: { tag: "field" } }];
    let value = (av.value || [[]]).map((v) => {
      if (v instanceof Uint8Array) return Array.from(v);
      if (Array.isArray(v)) return v;
      if (typeof v === "object" && v !== null) {
        const keys = Object.keys(v).filter((k) => !isNaN(k)).sort((a, b) => Number(a) - Number(b));
        if (keys.length === 0) return [];
        return keys.map((k) => v[k]);
      }
      return [];
    });
    if (alignment.length > 0 && value.length > 0) {
      value = value.map((slot, i) => {
        if (slot.length > 0) return slot;
        const align = alignment[i];
        if (!align) return slot;
        if (align.tag === "atom" && align.value) {
          if (align.value.tag === "field" || align.value.tag === "compress") {
            return new Array(32).fill(0);
          }
          if (align.value.tag === "bytes" && align.value.length) {
            return new Array(align.value.length).fill(0);
          }
        }
        return slot;
      });
    }
    if (value.length === 0 && alignment.length > 0) {
      value = alignment.map((align) => {
        if (align.tag === "atom" && align.value) {
          if (align.value.tag === "field" || align.value.tag === "compress") {
            return new Array(32).fill(0);
          }
          if (align.value.tag === "bytes" && align.value.length) {
            return new Array(align.value.length).fill(0);
          }
        }
        return [];
      });
    }
    return { value, alignment };
  }
  function transformPublicTranscript(transcript) {
    return transcript.map((op) => {
      if (typeof op === "string") return op;
      if (typeof op !== "object" || op === null) return op;
      if ("popeq" in op) {
        return {
          popeq: {
            cached: op.popeq.cached,
            result: transformAlignedValuePadded(op.popeq.result)
          }
        };
      }
      if ("idx" in op) {
        return {
          idx: {
            cached: op.idx.cached,
            pushPath: op.idx.pushPath || false,
            path: (op.idx.path || []).map((key) => {
              if (key.tag === "value") {
                return { tag: "value", value: transformAlignedValuePadded(key.value) };
              }
              return key;
            })
          }
        };
      }
      return transformOpForRust(op);
    });
  }
  var QueryContext = class _QueryContext {
    constructor(chargedState, contractAddress) {
      this.state = chargedState;
      this.address = contractAddress;
      this.block = {
        balance: /* @__PURE__ */ new Map(),
        ownAddress: contractAddress,
        secondsSinceEpoch: BigInt(Math.floor(Date.now() / 1e3))
      };
      this.effects = [];
      this.callContext = {};
      this._rustHandle = chargedState && chargedState._rustHandle || 0;
    }
    query(program, costModel, gasLimit) {
      if (typeof globalThis.__native_contractQuery === "function" && this._rustHandle) {
        try {
          const transformed = program.map((op) => transformOpForRust(op));
          const opcodesJson = JSON.stringify(transformed);
          const resultJson = globalThis.__native_contractQuery(
            this._rustHandle.toString(),
            opcodesJson
          );
          const result = JSON.parse(resultJson);
          if (result.error) {
            throw new Error(result.error);
          }
          this._rustHandle = result.handle;
          const events = (result.events || []).map((ev) => {
            if (ev.Read !== void 0) {
              const content = {
                value: ev.Read.value.map((arr) => new Uint8Array(arr)),
                alignment: ev.Read.alignment
              };
              return { tag: "read", content };
            }
            if (ev.Log !== void 0) {
              return { tag: "log", content: ev.Log };
            }
            return ev;
          });
          const newState = new ChargedState(this.state.get_ref());
          newState._rustHandle = result.handle;
          const newCtx = new _QueryContext(newState, this.address);
          newCtx._rustHandle = result.handle;
          const gas = result.gas || {};
          const gasCost = {
            readTime: BigInt(gas.readTime || 0),
            computeTime: BigInt(gas.computeTime || 0),
            bytesWritten: BigInt(gas.bytesWritten || 0),
            bytesDeleted: BigInt(gas.bytesDeleted || 0)
          };
          return { context: newCtx, events, gasCost };
        } catch (e) {
          throw new Error("Rust VM query failed: " + e.toString());
        }
      }
      throw new Error("QueryContext.query: no Rust handle available and no native FFI registered");
    }
  };
  var CostModel = class _CostModel {
    static initialCostModel() {
      return new _CostModel();
    }
  };
  var QueryResults = class {
    constructor(context, events) {
      this.context = context;
      this.events = events;
    }
  };
  var VmResults = class {
  };
  var VmStack = class {
  };
  var StateBoundedMerkleTree = class {
  };
  var StateMap = class {
  };
  function persistentHash(alignment, value) {
    if (typeof globalThis.__native_persistentHash_aligned === "function") {
      try {
        const aligned = {
          value: value.map((v) => v instanceof Uint8Array ? Array.from(v) : v),
          alignment
        };
        const json = JSON.stringify(aligned);
        const resultJson = globalThis.__native_persistentHash_aligned(json);
        const parsed = JSON.parse(resultJson);
        if (parsed.error) throw new Error(parsed.error);
        return parsed.map((arr) => new Uint8Array(arr));
      } catch (e) {
        throw new Error("persistentHash native call failed: " + e.toString());
      }
    }
    throw new Error("persistentHash: native FFI not available");
  }
  function persistentCommit(value, opening) {
    if (typeof globalThis.__native_persistentCommit === "function") {
      return globalThis.__native_persistentCommit(value, opening);
    }
    throw new Error("persistentCommit: native function not bound");
  }
  function transientHash(input) {
    if (typeof globalThis.__native_transientHash === "function") {
      return globalThis.__native_transientHash(input);
    }
    throw new Error("transientHash: native function not bound");
  }
  function transientCommit(value, opening) {
    throw new Error("transientCommit: not yet implemented");
  }
  function degradeToTransient(x) {
    return x;
  }
  function upgradeFromTransient(x) {
    return x;
  }
  function dummyContractAddress() {
    return "0".repeat(64);
  }
  function dummyUserAddress() {
    return "0".repeat(64);
  }
  function sampleContractAddress() {
    return "0".repeat(64);
  }
  function sampleUserAddress() {
    return "0".repeat(64);
  }
  function sampleRawTokenType() {
    return "0".repeat(64);
  }
  function rawTokenType() {
    return "0".repeat(64);
  }
  function encodeContractAddress(addr) {
    return addr;
  }
  function decodeContractAddress(addr) {
    return addr;
  }
  function encodeUserAddress(addr) {
    return addr;
  }
  function decodeUserAddress(addr) {
    return addr;
  }
  function encodeCoinPublicKey(pk) {
    return pk;
  }
  function decodeCoinPublicKey(pk) {
    return pk;
  }
  function encodeRawTokenType(t) {
    return t;
  }
  function decodeRawTokenType(t) {
    return t;
  }
  function encodeShieldedCoinInfo(info) {
    return info;
  }
  function decodeShieldedCoinInfo(info) {
    return info;
  }
  function encodeQualifiedShieldedCoinInfo(info) {
    return info;
  }
  function decodeQualifiedShieldedCoinInfo(info) {
    return info;
  }
  function valueToBigInt(value) {
    if (typeof value === "bigint") return value;
    if (typeof value === "number") return BigInt(value);
    if (typeof value === "string") return BigInt(value);
    if (typeof globalThis.__native_valueToBigInt === "function") {
      try {
        const json = JSON.stringify(value, (k, v) => v instanceof Uint8Array ? Array.from(v) : v);
        const result = globalThis.__native_valueToBigInt(json);
        return BigInt(result);
      } catch (e) {
        throw new Error("valueToBigInt native call failed: " + e.toString());
      }
    }
    throw new Error("valueToBigInt: native FFI not available");
  }
  function bigIntToValue(n) {
    if (typeof globalThis.__native_bigIntToValue === "function") {
      try {
        const hex = BigInt(n).toString(16);
        const json = globalThis.__native_bigIntToValue(hex);
        const parsed = JSON.parse(json);
        return parsed.map((arr) => new Uint8Array(arr));
      } catch (e) {
        throw new Error("bigIntToValue native call failed: " + e.toString());
      }
    }
    throw new Error("bigIntToValue: native FFI not available");
  }
  function maxAlignedSize() {
    return 32;
  }
  function maxField() {
    return 52435875175126190479447740508185965837690552500527637822603658699938581184512n;
  }
  function bigIntModFr(n) {
    const FR_MODULUS = 52435875175126190479447740508185965837690552500527637822603658699938581184512n;
    return (n % FR_MODULUS + FR_MODULUS) % FR_MODULUS;
  }
  function proofDataIntoSerializedPreimage(proofData) {
    return proofData;
  }
  function ecAdd(a, b) {
    throw new Error("ecAdd: not yet implemented");
  }
  function ecMul(point, scalar) {
    throw new Error("ecMul: not yet implemented");
  }
  function ecMulGenerator(scalar) {
    throw new Error("ecMulGenerator: not yet implemented");
  }
  function hashToCurve(data) {
    throw new Error("hashToCurve: not yet implemented");
  }
  function communicationCommitmentRandomness() {
    return new Uint8Array(32);
  }
  function communicationCommitment() {
    return new Uint8Array(32);
  }
  function entryPointHash() {
    return new Uint8Array(32);
  }
  function sampleSigningKey() {
    return new Uint8Array(32);
  }
  function signingKeyFromBip340() {
    return new Uint8Array(32);
  }
  function signData() {
    return new Uint8Array(64);
  }
  function signatureVerifyingKey() {
    return new Uint8Array(32);
  }
  function verifySignature() {
    return true;
  }
  function runtimeCoinCommitment() {
    return new Uint8Array(32);
  }
  function leafHash() {
    return new Uint8Array(32);
  }
  function runProgram(initial, program, gasLimit, costModel) {
    throw new Error("runProgram: not yet implemented \u2014 will delegate to Rust FFI");
  }

  // constants.js
  var MAX_FIELD = maxField();
  var DUMMY_ADDRESS = dummyContractAddress();

  // version.js
  var versionString = "0.15.0";
  var checkRuntimeVersion = (expectedRuntimeVersionString) => {
    const expectedRuntimeVersion = expectedRuntimeVersionString.split("-")[0].split(".").map(Number);
    const actualRuntimeVersion = versionString.split("-")[0].split(".").map(Number);
    if (expectedRuntimeVersion[0] !== actualRuntimeVersion[0] || actualRuntimeVersion[0] === 0 && expectedRuntimeVersion[1] !== actualRuntimeVersion[1] || expectedRuntimeVersion[1] > actualRuntimeVersion[1] || expectedRuntimeVersion[1] === actualRuntimeVersion[1] && expectedRuntimeVersion[2] > actualRuntimeVersion[2]) {
      throw new CompactError(`Version mismatch: compiled code expects ${expectedRuntimeVersionString}, runtime is ${versionString}`);
    }
    const MAX_FIELD2 = 52435875175126190479447740508185965837690552500527637822603658699938581184512n;
    if (MAX_FIELD2 !== MAX_FIELD) {
      throw new CompactError(`Maximum field mismatch: compiled code uses ${MAX_FIELD2}, runtime uses ${MAX_FIELD}`);
    }
  };

  // compact-types.js
  var CompactTypeJubjubPoint = {
    alignment() {
      return [
        { tag: "atom", value: { tag: "field" } },
        { tag: "atom", value: { tag: "field" } }
      ];
    },
    fromValue(value) {
      const x = value.shift();
      const y = value.shift();
      if (x == void 0 || y == void 0) {
        throw new CompactError("expected JubjubPoint");
      } else {
        return {
          x: valueToBigInt([x]),
          y: valueToBigInt([y])
        };
      }
    },
    toValue(value) {
      return bigIntToValue(value.x).concat(bigIntToValue(value.y));
    }
  };
  var CompactTypeMerkleTreeDigest = {
    alignment() {
      return [{ tag: "atom", value: { tag: "field" } }];
    },
    fromValue(value) {
      const val = value.shift();
      if (val == void 0) {
        throw new CompactError("expected MerkleTreeDigest");
      } else {
        return { field: valueToBigInt([val]) };
      }
    },
    toValue(value) {
      return bigIntToValue(value.field);
    }
  };
  var CompactTypeMerkleTreePathEntry = {
    alignment() {
      return CompactTypeMerkleTreeDigest.alignment().concat(CompactTypeBoolean.alignment());
    },
    fromValue(value) {
      const sibling = CompactTypeMerkleTreeDigest.fromValue(value);
      const goes_left = CompactTypeBoolean.fromValue(value);
      return {
        sibling,
        goes_left
      };
    },
    toValue(value) {
      return CompactTypeMerkleTreeDigest.toValue(value.sibling).concat(CompactTypeBoolean.toValue(value.goes_left));
    }
  };
  var CompactTypeMerkleTreePath = class {
    leaf;
    path;
    constructor(n, leaf) {
      this.leaf = leaf;
      this.path = new CompactTypeVector(n, CompactTypeMerkleTreePathEntry);
    }
    alignment() {
      return this.leaf.alignment().concat(this.path.alignment());
    }
    fromValue(value) {
      const leaf = this.leaf.fromValue(value);
      const path = this.path.fromValue(value);
      return {
        leaf,
        path
      };
    }
    toValue(value) {
      return this.leaf.toValue(value.leaf).concat(this.path.toValue(value.path));
    }
  };
  var CompactTypeField = {
    alignment() {
      return [{ tag: "atom", value: { tag: "field" } }];
    },
    fromValue(value) {
      const val = value.shift();
      if (val == void 0) {
        throw new CompactError("expected Field");
      } else {
        return valueToBigInt([val]);
      }
    },
    toValue(value) {
      return bigIntToValue(value);
    }
  };
  var CompactTypeEnum = class {
    maxValue;
    length;
    constructor(maxValue, length) {
      this.maxValue = maxValue;
      this.length = length;
    }
    alignment() {
      return [{ tag: "atom", value: { tag: "bytes", length: this.length } }];
    }
    fromValue(value) {
      const val = value.shift();
      if (val == void 0) {
        throw new CompactError(`expected Enum[<=${this.maxValue}]`);
      } else {
        let res = 0;
        for (let i = 0; i < val.length; i++) {
          res += (1 << 8 * i) * val[i];
        }
        if (res > this.maxValue) {
          throw new CompactError(`expected UnsignedInteger[<=${this.maxValue}]`);
        }
        return res;
      }
    }
    toValue(value) {
      return CompactTypeField.toValue(BigInt(value));
    }
  };
  var CompactTypeUnsignedInteger = class {
    maxValue;
    length;
    constructor(maxValue, length) {
      this.maxValue = maxValue;
      this.length = length;
    }
    alignment() {
      return [{ tag: "atom", value: { tag: "bytes", length: this.length } }];
    }
    fromValue(value) {
      const val = value.shift();
      if (val == void 0) {
        throw new CompactError(`expected UnsignedInteger[<=${this.maxValue}]`);
      } else {
        let res = 0n;
        for (let i = 0; i < val.length; i++) {
          res += (1n << 8n * BigInt(i)) * BigInt(val[i]);
        }
        if (res > this.maxValue) {
          throw new CompactError(`expected UnsignedInteger[<=${this.maxValue}]`);
        }
        return res;
      }
    }
    toValue(value) {
      return CompactTypeField.toValue(value);
    }
  };
  var CompactTypeVector = class {
    length;
    type;
    constructor(length, type) {
      this.length = length;
      this.type = type;
    }
    alignment() {
      const inner = this.type.alignment();
      let res = [];
      for (let i = 0; i < this.length; i++) {
        res = res.concat(inner);
      }
      return res;
    }
    fromValue(value) {
      const res = [];
      for (let i = 0; i < this.length; i++) {
        res.push(this.type.fromValue(value));
      }
      return res;
    }
    toValue(value) {
      if (value.length != this.length) {
        throw new CompactError(`expected ${this.length}-element array`);
      }
      let res = [];
      for (let i = 0; i < this.length; i++) {
        res = res.concat(this.type.toValue(value[i]));
      }
      return res;
    }
  };
  var CompactTypeBoolean = {
    alignment() {
      return [{ tag: "atom", value: { tag: "bytes", length: 1 } }];
    },
    fromValue(value) {
      const val = value.shift();
      if (val == void 0 || val.length > 1 || val.length == 1 && val[0] != 1) {
        throw new CompactError("expected Boolean");
      }
      return val.length == 1;
    },
    toValue(value) {
      if (value) {
        return [new Uint8Array([1])];
      } else {
        return [new Uint8Array(0)];
      }
    }
  };
  var CompactTypeBytes = class {
    length;
    constructor(length) {
      this.length = length;
    }
    alignment() {
      return [{ tag: "atom", value: { tag: "bytes", length: this.length } }];
    }
    fromValue(value) {
      const val = value.shift();
      if (val == void 0 || val.length > this.length) {
        throw new CompactError(`expected Bytes[${this.length}]`);
      }
      if (val.length == this.length) {
        return val;
      }
      const res = new Uint8Array(this.length);
      res.set(val, 0);
      return res;
    }
    toValue(value) {
      let end = value.length;
      while (end > 0 && value[end - 1] == 0) {
        end -= 1;
      }
      return [value.slice(0, end)];
    }
  };
  var CompactTypeOpaqueUint8Array = {
    alignment() {
      return [{ tag: "atom", value: { tag: "compress" } }];
    },
    fromValue(value) {
      return value.shift();
    },
    toValue(value) {
      return [value];
    }
  };
  var CompactTypeOpaqueString = {
    alignment() {
      return [{ tag: "atom", value: { tag: "compress" } }];
    },
    fromValue(value) {
      return new TextDecoder("utf-8").decode(value.shift());
    },
    toValue(value) {
      return [new TextEncoder().encode(value)];
    }
  };
  var Bytes32Descriptor = new CompactTypeBytes(32);
  var MaxUint8Descriptor = new CompactTypeUnsignedInteger(18446744073709551615n, 8);
  var ShieldedCoinInfoDescriptor = {
    alignment() {
      return Bytes32Descriptor.alignment().concat(Bytes32Descriptor.alignment().concat(MaxUint8Descriptor.alignment()));
    },
    fromValue(value) {
      return {
        nonce: Bytes32Descriptor.fromValue(value),
        color: Bytes32Descriptor.fromValue(value),
        value: MaxUint8Descriptor.fromValue(value)
      };
    },
    toValue(value) {
      return Bytes32Descriptor.toValue(value.nonce).concat(Bytes32Descriptor.toValue(value.color).concat(MaxUint8Descriptor.toValue(value.value)));
    }
  };
  var ZswapCoinPublicKeyDescriptor = {
    alignment() {
      return Bytes32Descriptor.alignment();
    },
    fromValue(value) {
      return {
        bytes: Bytes32Descriptor.fromValue(value)
      };
    },
    toValue(value) {
      return Bytes32Descriptor.toValue(value.bytes);
    }
  };
  var ContractAddressDescriptor = {
    alignment() {
      return Bytes32Descriptor.alignment();
    },
    fromValue(value) {
      return {
        bytes: Bytes32Descriptor.fromValue(value)
      };
    },
    toValue(value) {
      return Bytes32Descriptor.toValue(value.bytes);
    }
  };
  var ShieldedCoinRecipientDescriptor = {
    alignment() {
      return CompactTypeBoolean.alignment().concat(ZswapCoinPublicKeyDescriptor.alignment().concat(ContractAddressDescriptor.alignment()));
    },
    fromValue(value) {
      return {
        is_left: CompactTypeBoolean.fromValue(value),
        left: ZswapCoinPublicKeyDescriptor.fromValue(value),
        right: ContractAddressDescriptor.fromValue(value)
      };
    },
    toValue(value) {
      return CompactTypeBoolean.toValue(value.is_left).concat(ZswapCoinPublicKeyDescriptor.toValue(value.left).concat(ContractAddressDescriptor.toValue(value.right)));
    }
  };

  // built-ins.js
  var FIELD_MODULUS = MAX_FIELD + 1n;
  function addField(x, y) {
    const t = x + y;
    return t < FIELD_MODULUS ? t : t - FIELD_MODULUS;
  }
  function subField(x, y) {
    const t = x - y;
    return t >= 0 ? t : t + FIELD_MODULUS;
  }
  function mulField(x, y) {
    return x * y % FIELD_MODULUS;
  }
  function transientHash2(rtType, value) {
    return valueToBigInt(transientHash(rtType.alignment(), rtType.toValue(value)));
  }
  function transientCommit2(rtType, value, opening) {
    return valueToBigInt(transientCommit(rtType.alignment(), rtType.toValue(value), bigIntToValue(opening)));
  }
  function persistentHash2(rtType, value) {
    const wrapped = persistentHash(rtType.alignment(), rtType.toValue(value))[0];
    const res = new Uint8Array(32);
    res.set(wrapped, 0);
    return res;
  }
  function persistentCommit2(rtType, value, opening) {
    if (opening.length != 32) {
      throw new CompactError("Expected 32-byte string");
    }
    const wrapped = persistentCommit(rtType.alignment(), rtType.toValue(value), [opening])[0];
    const res = new Uint8Array(32);
    res.set(wrapped, 0);
    return res;
  }
  function degradeToTransient2(x) {
    if (x.length != 32) {
      throw new CompactError("Expected 32-byte string");
    }
    return valueToBigInt(degradeToTransient([x]));
  }
  function upgradeFromTransient2(x) {
    const wrapped = upgradeFromTransient(bigIntToValue(x))[0];
    const res = new Uint8Array(32);
    res.set(wrapped, 0);
    return res;
  }
  function jubjubPointX(pt) {
    return pt.x;
  }
  function jubjubPointY(pt) {
    return pt.y;
  }
  function constructJubjubPoint(x, y) {
    return { x, y };
  }
  function hashToCurve2(rtType, x) {
    return CompactTypeJubjubPoint.fromValue(hashToCurve(rtType.alignment(), rtType.toValue(x)));
  }
  function ecAdd2(a, b) {
    return CompactTypeJubjubPoint.fromValue(ecAdd(CompactTypeJubjubPoint.toValue(a), CompactTypeJubjubPoint.toValue(b)));
  }
  function ecMul2(a, b) {
    return CompactTypeJubjubPoint.fromValue(ecMul(CompactTypeJubjubPoint.toValue(a), bigIntToValue(b)));
  }
  function ecMulGenerator2(b) {
    return CompactTypeJubjubPoint.fromValue(ecMulGenerator(bigIntToValue(b)));
  }
  function alignedConcat(...values) {
    const res = { value: [], alignment: [] };
    for (const value of values) {
      res.value = res.value.concat(value.value);
      res.alignment = res.alignment.concat(value.alignment);
    }
    return res;
  }

  // casts.js
  function convertFieldToBytes(n, x, src) {
    const x_0 = x;
    const a = new Uint8Array(n);
    for (let i = 0; i < n; i++) {
      a[i] = Number(x & 0xffn);
      x = x / 0x100n;
      if (x == 0n)
        return a;
    }
    const msg = `range error at ${src}: Field or Uint value ${x_0} does not fit into ${n} bytes`;
    throw new CompactError(msg);
  }
  function convertBytesToField(n, a, src) {
    let x = 0n;
    for (let i = n - 1; i >= 0; i -= 1) {
      x = x * 0x100n + BigInt(a[i]);
      if (x > MAX_FIELD) {
        const msg = `range error at ${src}: the integer value of ${a} is greater than the maximum value of a Field`;
        throw new CompactError(msg);
      }
    }
    return x;
  }
  function convertBytesToUint(maxval, n, a, src) {
    let x = 0n;
    for (let i = n - 1; i >= 0; i -= 1) {
      x = x * 0x100n + BigInt(a[i]);
      if (x > maxval) {
        const msg = `range error at ${src}: the integer value of ${a} is greater than the maximum value of Uint<0..${maxval + 1}>`;
        throw new CompactError(msg);
      }
    }
    return x;
  }

  // utils.js
  var HEX_REGEX_NO_PREFIX = /^([0-9A-Fa-f]{2})*$/;
  var CONTRACT_ADDRESS_BYTE_LENGTH = 32;
  function isContractAddress(x) {
    return typeof x === "string" && x.length === CONTRACT_ADDRESS_BYTE_LENGTH * 2 && HEX_REGEX_NO_PREFIX.test(x);
  }
  function isEncodedContractAddress(x) {
    return typeof x === "object" && x !== null && x !== void 0 && "bytes" in x && x.bytes instanceof Uint8Array && x.bytes.length == CONTRACT_ADDRESS_BYTE_LENGTH;
  }
  var fromHex = (s) => Buffer.from(s, "hex");
  var toHex = (s) => Buffer.from(s).toString("hex");

  // zswap.js
  var emptyZswapLocalState = (coinPublicKey) => ({
    coinPublicKey: typeof coinPublicKey === "string" ? { bytes: encodeCoinPublicKey(coinPublicKey) } : coinPublicKey,
    currentIndex: 0n,
    inputs: [],
    outputs: []
  });
  var encodeRecipient = ({ is_left, left, right }) => ({
    is_left,
    left: { bytes: encodeCoinPublicKey(left) },
    right: { bytes: encodeContractAddress(right) }
  });
  var decodeRecipient = ({ is_left, left, right }) => ({
    is_left,
    left: decodeCoinPublicKey(left.bytes),
    right: decodeContractAddress(right.bytes)
  });
  var encodeZswapLocalState = (state) => ({
    coinPublicKey: { bytes: encodeCoinPublicKey(state.coinPublicKey) },
    currentIndex: state.currentIndex,
    inputs: state.inputs.map(encodeQualifiedShieldedCoinInfo),
    outputs: state.outputs.map(({ coinInfo, recipient }) => ({
      coinInfo: encodeShieldedCoinInfo(coinInfo),
      recipient: encodeRecipient(recipient)
    }))
  });
  var decodeZswapLocalState = (state) => ({
    coinPublicKey: decodeCoinPublicKey(state.coinPublicKey.bytes),
    currentIndex: state.currentIndex,
    inputs: state.inputs.map(decodeQualifiedShieldedCoinInfo),
    outputs: state.outputs.map(({ coinInfo, recipient }) => ({
      coinInfo: decodeShieldedCoinInfo(coinInfo),
      recipient: decodeRecipient(recipient)
    }))
  });
  function createZswapInput(circuitContext, qualifiedShieldedCoinInfo) {
    circuitContext.currentZswapLocalState = {
      ...circuitContext.currentZswapLocalState,
      inputs: circuitContext.currentZswapLocalState.inputs.concat(qualifiedShieldedCoinInfo)
    };
    return [];
  }
  function createCoinCommitment(coinInfo, recipient) {
    return runtimeCoinCommitment({
      value: ShieldedCoinInfoDescriptor.toValue(coinInfo),
      alignment: ShieldedCoinInfoDescriptor.alignment()
    }, {
      value: ShieldedCoinRecipientDescriptor.toValue(recipient),
      alignment: ShieldedCoinRecipientDescriptor.alignment()
    });
  }
  function createZswapOutput(circuitContext, coinInfo, recipient) {
    circuitContext.currentQueryContext = circuitContext.currentQueryContext.insertCommitment(Buffer.from(Bytes32Descriptor.fromValue(createCoinCommitment(coinInfo, recipient).value)).toString("hex"), circuitContext.currentZswapLocalState.currentIndex);
    circuitContext.currentZswapLocalState = {
      ...circuitContext.currentZswapLocalState,
      currentIndex: circuitContext.currentZswapLocalState.currentIndex + 1n,
      outputs: circuitContext.currentZswapLocalState.outputs.concat({
        coinInfo,
        recipient
      })
    };
    return [];
  }
  function ownPublicKey(circuitContext) {
    return circuitContext.currentZswapLocalState.coinPublicKey;
  }
  var hasCoinCommitment = (context, coinInfo, recipient) => context.currentQueryContext.comIndices.has(toHex(Bytes32Descriptor.fromValue(createCoinCommitment(coinInfo, recipient).value)));

  // constructor-context.js
  var createConstructorContext = (initialPrivateState, coinPublicKey) => ({
    initialPrivateState,
    initialZswapLocalState: emptyZswapLocalState(coinPublicKey)
  });

  // circuit-context.js
  var coerceToChargedState = (contractState) => {
    let state;
    if (contractState instanceof ChargedState) {
      state = contractState;
    } else if (contractState instanceof ContractState) {
      state = contractState.data;
    } else if (contractState instanceof StateValue) {
      state = new ChargedState(contractState);
    } else {
      throw new CompactError(`'contractState' parameter ${contractState} has unexpected type`);
    }
    return state;
  };
  var createInitialQueryContext = (contractState, contractAddress, time) => {
    const initialQueryContext = new QueryContext(coerceToChargedState(contractState), contractAddress);
    const balance = contractState instanceof ContractState ? contractState.balance : /* @__PURE__ */ new Map();
    initialQueryContext.block = {
      ...initialQueryContext.block,
      balance,
      ownAddress: contractAddress,
      secondsSinceEpoch: BigInt(time ?? Math.floor(Date.now() / 1e3))
    };
    return initialQueryContext;
  };
  var isZswapLocalState = (value) => {
    return typeof value === "object" && value !== null && "coinPublicKey" in value && typeof value.coinPublicKey === "string" && "currentIndex" in value && "inputs" in value && "outputs" in value;
  };
  var isEncodedZswapLocalState = (value) => {
    return typeof value === "object" && value !== null && "coinPublicKey" in value && typeof value.coinPublicKey === "object" && value.coinPublicKey !== null && "bytes" in value.coinPublicKey && "currentIndex" in value && "inputs" in value && "outputs" in value;
  };
  var createCircuitContext = (contractAddress, coinPublicKeyOrZswapState, contractState, privateState, gasLimit, costModel, time) => {
    const initialQueryContext = createInitialQueryContext(contractState, contractAddress, time);
    let zswapLocalState;
    if (isZswapLocalState(coinPublicKeyOrZswapState)) {
      zswapLocalState = encodeZswapLocalState(coinPublicKeyOrZswapState);
    } else if (isEncodedZswapLocalState(coinPublicKeyOrZswapState)) {
      zswapLocalState = coinPublicKeyOrZswapState;
    } else {
      zswapLocalState = emptyZswapLocalState(coinPublicKeyOrZswapState);
    }
    return {
      currentPrivateState: privateState,
      currentZswapLocalState: zswapLocalState,
      currentQueryContext: initialQueryContext,
      costModel: costModel ?? CostModel.initialCostModel(),
      gasLimit
    };
  };
  var emptyRunningCost = () => ({
    readTime: 0n,
    computeTime: 0n,
    bytesWritten: 0n,
    bytesDeleted: 0n
  });
  var queryLedgerState = (circuitContext, partialProofData, program) => {
    try {
      const res = circuitContext.currentQueryContext.query(program, circuitContext.costModel, circuitContext.gasLimit);
      circuitContext.currentQueryContext = res.context;
      circuitContext["gasCost"] = res.gasCost;
      const reads = res.events.filter((e) => e.tag === "read");
      let i = 0;
      partialProofData.publicTranscript = partialProofData.publicTranscript.concat(program.map((op) => typeof op === "object" && "popeq" in op ? {
        popeq: {
          ...op.popeq,
          result: reads[i++].content
        }
      } : op));
      if (res.events.length === 1) {
        const event = res.events[0];
        if (event.tag === "read") {
          return event.content;
        }
      }
      return res.events;
    } catch (err) {
      if (err instanceof Error) {
        throw new CompactError(err.toString());
      }
      throw err;
    }
  };

  // witness.js
  function createWitnessContext(ledger, privateState, contractAddress) {
    return {
      ledger,
      privateState,
      contractAddress
    };
  }

  // contract-dependencies.js
  function isCompactVector(x) {
    return Array.isArray(x) && x.every((element) => isCompactValue(element));
  }
  function isCompactStruct(x) {
    return typeof x === "object" && x !== null && x !== void 0 && Object.entries(x).every(([key, value]) => typeof key === "string" && isCompactValue(value));
  }
  function isCompactValue(x) {
    return isEncodedContractAddress(x) || isCompactVector(x) || isCompactStruct(x);
  }
  var expectedValueError = (expected, actual) => {
    throw new CompactError(`Expected ${expected} but received ${JSON.stringify(actual)}`);
  };
  function assertIsContractAddress(value) {
    if (!isEncodedContractAddress(value)) {
      expectedValueError("contract address", value);
    }
  }
  function assertIsCompactVector(value) {
    if (!isCompactVector(value)) {
      expectedValueError("vector", value);
    }
  }
  function assertIsCompactStruct(value) {
    if (!isCompactStruct(value)) {
      expectedValueError("struct", value);
    }
  }
  function assertIsCompactValue(x) {
    if (!isCompactValue(x)) {
      expectedValueError("Compact value", x);
    }
  }
  function toCompactValue(x) {
    assertIsCompactValue(x);
    return x;
  }
  var compactValueDependencies = (sparseCompactType, compactValue, dependencies) => {
    if (sparseCompactType.tag == "contractAddress") {
      assertIsContractAddress(compactValue);
      dependencies.add(decodeContractAddress(compactValue.bytes));
    } else if (sparseCompactType.tag == "struct") {
      assertIsCompactStruct(compactValue);
      Object.keys(compactValue).forEach((structElementId) => compactValueDependencies(sparseCompactType.elements[structElementId], compactValue[structElementId], dependencies));
    } else {
      assertIsCompactVector(compactValue);
      compactValue.forEach((vectorElement) => compactValueDependencies(sparseCompactType.sparseType, vectorElement, dependencies));
    }
  };
  var alignedValueToCompactValue = (descriptor, { value }) => toCompactValue(descriptor.fromValue(value));
  var stateValueToCompactValue = (descriptor, stateValue) => alignedValueToCompactValue(descriptor, stateValue.asCell());
  var compactCellDependencies = (sparseCompactCellADT, state, dependencies) => {
    const { sparseType, descriptor } = sparseCompactCellADT.valueType;
    compactValueDependencies(sparseType, stateValueToCompactValue(descriptor, state), dependencies);
  };
  var compactArrayLikeADTDependencies = (sparseCompactArrayLikeADT, states, dependencies) => {
    const { sparseType, descriptor } = sparseCompactArrayLikeADT.valueType;
    states.forEach((state) => compactValueDependencies(sparseType, stateValueToCompactValue(descriptor, state), dependencies));
  };
  var compactMapADTDependencies = (sparseCompactMapADT, stateMap, dependencies) => {
    const { keyType, valueType } = sparseCompactMapADT;
    stateMap.keys().forEach((key) => {
      if (keyType) {
        compactValueDependencies(keyType.sparseType, alignedValueToCompactValue(keyType.descriptor, key), dependencies);
      }
      if (valueType) {
        const value = stateMap.get(key);
        if (!value) {
          throw new CompactError(`State map ${stateMap.toString(false)} contains key without corresponding value`);
        }
        if (valueType.tag == "compactValue") {
          compactValueDependencies(valueType.sparseType, stateValueToCompactValue(valueType.descriptor, value), dependencies);
        } else {
          compactADTDependencies(valueType, value, dependencies);
        }
      }
    });
  };
  function assertCastSucceeded(s, stateValue, expectedCastOutput) {
    if (!s) {
      throw new CompactError(`State ${stateValue.toString(false)} cannot be cast to a ${expectedCastOutput}`);
    }
  }
  var compactADTDependencies = (sparseCompactADT, stateValue, dependencies) => {
    if (sparseCompactADT.tag == "cell") {
      compactCellDependencies(sparseCompactADT, stateValue, dependencies);
    } else if (sparseCompactADT.tag == "map") {
      const stateMap = stateValue.asMap();
      assertCastSucceeded(stateMap, stateValue, "map");
      compactMapADTDependencies(sparseCompactADT, stateMap, dependencies);
    } else if (sparseCompactADT.tag == "list" || sparseCompactADT.tag == "set") {
      const states = stateValue.asArray();
      assertCastSucceeded(states, stateValue, "array");
      compactArrayLikeADTDependencies(sparseCompactADT, states, dependencies);
    }
  };
  var castToStateArray = (state) => {
    const ledgerState = state.asArray();
    assertCastSucceeded(ledgerState, state, "array");
    return ledgerState;
  };
  var publicLedgerSegmentsDependencies = (publicLedgerSegments, state, dependencies) => {
    const ledgerState = castToStateArray(state);
    Object.keys(publicLedgerSegments.indices).map(parseInt).forEach((idx) => {
      const referenceLocations = publicLedgerSegments.indices[idx];
      if ("tag" in referenceLocations && referenceLocations["tag"] === "publicLedgerArray") {
        publicLedgerSegmentsDependencies(referenceLocations, ledgerState[idx], dependencies);
      } else {
        compactADTDependencies(referenceLocations, ledgerState[idx], dependencies);
      }
    });
  };
  var contractDependencies = (contractReferenceLocations, state) => {
    const dependencies = /* @__PURE__ */ new Set();
    if (contractReferenceLocations.indices) {
      publicLedgerSegmentsDependencies(contractReferenceLocations, state, dependencies);
    }
    return [...dependencies];
  };
  return __toCommonJS(index_exports);
})();
