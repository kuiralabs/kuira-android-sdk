# Phase 6: Android DApp SDK

**Date:** 2026-04-03 (updated 2026-04-06)
**Status:** Complete — all steps done, SDK example app functional
**Depends on:** Phase 5 (DApp Connector) ✅

---

## Problem

A dApp needs to build a contract transaction before the wallet can balance and submit it. Phase 5 gave us the wallet side (balance + submit). The dApp side — circuit execution, proof generation, state management — has no Android equivalent. The JS SDK (`midnight-js`) handles this for web. We need the same for native Android.

---

## How a Contract Transaction Flows

1. **dApp executes circuit** — runs Compact contract logic with private inputs (witnesses), produces proof preimages + state changes
2. **dApp assembles unproven transaction** — packages proof preimages into a `ContractCallPrototype` → `Intent` → `UnprovenTransaction` using ledger types
3. **dApp proves the transaction** — generates a ZK proof, producing a proven transaction
4. **Wallet balances the proven transaction** — adds coins, pays fees, signs (we have `ConnectedAPI.balanceUnsealedTransaction`)
5. **Wallet submits to network** — relays the finalized transaction to blockchain (we have `ConnectedAPI.submitTransaction`)

Steps 4-5 are done (Phase 5). Steps 1-3 are done (6D-6J). The full pipeline works end-to-end on Android — circuit execution, ZK proving, balancing, and on-chain submission.

Important: the wallet receives a **proven** transaction (step 3), not an unproven one. The dApp must prove first, then send to the wallet for balancing.

---

## What Exists vs What's Needed

| Capability | JS SDK Package | Android Equivalent | Status |
|-----------|---------------|-------------------|--------|
| Circuit execution | `compact-runtime` | QuickJS + IIFE shim | ✅ Done (6D-6F) |
| Proof preimage extraction | `compact-runtime` | `CircuitResult` + `transformPublicTranscript` | ✅ Done (6G) |
| Transaction assembly | `midnight-js/contracts` | `contract_assemble_call_tx` Rust FFI | ✅ Done (6G) |
| Provider interfaces | `midnight-js/types` | `CircuitExecutor`, `ProofProvider`, `WitnessProvider` | ✅ Done (6H) |
| Circuit executor API | `midnight-js/contracts` | `CircuitExecutor.executeCircuit()` | ✅ Done (6H) |
| Private state storage | `level-private-state-provider` | `KeyStorePrivateStateProvider` (AES-256-GCM) | ✅ Done (6I) |
| ZK config management | `fetch-zk-config-provider` | `ProvingKeyManager` | ✅ Done (6I) |
| ZK proof generation | `http-client-proof-provider` | `LocalProver` via `prove_ffi.rs` | Done |
| Wallet integration | `dapp-connector-api` | `core:connector` | Done |
| Indexer queries | `indexer-public-data-provider` | `core:indexer` | Done |
| Zswap operations | `ledger-wasm/zswap_wasm.rs` | `zswap_ffi.rs` | Done |
| Key derivation | `midnight-wallet/hd` | `core:crypto` | Done |
| Transaction submission | `midnightProvider` | `TransactionSubmitter` | Done |
| Transaction structure | `@midnight-ntwrk/ledger` (WASM) | `midnight-ledger` Rust crate | Done (6G) |

---

## Architecture

### Decision: QuickJS for orchestration, Rust FFI for ALL data encoding and crypto

**Validated:** The bboard contract's `post` circuit executes end-to-end in QuickJS on Android. All crypto/encoding goes through Rust FFI. The circuit produces correct proof preimages. Transaction assembly produces a serialized `UnprovenTransaction`.

**Key finding:** Midnight's `AlignedValue`, `StateValue`, and `Op<ResultModeVerify>` types can't round-trip through JSON serde due to the `Storable` derive macro generating storage-aware deserialization. Our `parse_aligned_value`, `parse_state_value`, and `parse_transcript_ops` functions bypass serde and construct types directly from parsed JSON.

### What QuickJS does (light, fast, ~1ms):
- Runs the compiled contract JS code
- Dispatches opcodes to the Rust VM
- Bridges witness callbacks to Kotlin
- Collects proof preimage references
- Transforms transcript to Rust format (`transformPublicTranscript`)

### What Rust FFI does (heavy, correct):
- `persistentHash(alignment, value)` — proper `binary_repr` + SHA-256
- `bigIntToValue(n)` / `valueToBigInt(value)` — proper field element encoding
- `contractQuery(state, opcodes)` — full VM execution with correct state encoding
- `assembleContractCallTx(params)` — builds `ContractCallPrototype` → `Intent` → `Transaction`, serializes to SCALE
- ZK proving, transaction balancing, SCALE serialization

---

## Libraries to Build

### 1. Circuit Executor (`core:compact-engine`) — ✅ Done
QuickJS + IIFE-bundled compact-runtime. The shim delegates ALL encoding/crypto to Rust FFI. Circuit output extracted into `CircuitResult` Kotlin data class.

### 2. Transaction Builder FFI — ✅ Done
`contract_assemble_call_tx` in Rust. Manual parsers for `AlignedValue`, `StateValue`, `Op<ResultModeVerify>` to bypass Midnight's broken JSON serde.

### 3. Provider Interfaces (`core:compact-engine`) — ✅ Done
`CircuitExecutor` wraps QuickJS lifecycle. `ProofProvider` wraps local prover. `WitnessProvider` for private inputs. Supports on-chain state via `onChainStateHex` and correct gas via `ledgerParametersHex`.

### 4. Private State Storage — ✅ Done
`KeyStorePrivateStateProvider`: AES-256-GCM via Android KeyStore. In `core:crypto:state`.

### 5. ZK Config Manager — ✅ Done
`ProvingKeyManager`: download, install, validate proving/verifier keys + BLS params.

---

## Steps

### Done:
- [x] **6A: Rust FFI foundation** — `persistentHash` exposed, `contract_query` with state handles compiled for ARM64
- [x] **6B: QuickJS embedded** — quickjs-kt 1.0.3, 5 POC tests passing on emulator
- [x] **6C: Onchain-runtime shim** — IIFE bundle (47KB), compact-runtime with shim replacing WASM
- [x] **6D: Run compiled contract** — bboard loads, initialState works, Contract class instantiates
- [x] **6E: Witness bridge** — Kotlin↔JS callback working. Secret key passed from Kotlin to JS
- [x] **6F: Replace JS fallbacks with Rust FFI** — All crypto/encoding delegates to native:
  - `persistentHash` → Rust `binary_repr` + SHA-256
  - `bigIntToValue` / `valueToBigInt` → Rust `Fr::from_le_bytes` field element encoding
  - `QueryContext.query()` → Rust `ContractStateExt::query` via state handles
  - All JS fallbacks removed — native FFI or throw, no silent wrong results
- [x] **6G: Proof preimage → UnprovenTransaction** — Full pipeline working:
  - `CircuitResult` / `ProofData` / `AlignedValue` Kotlin data classes
  - `transformPublicTranscript()` JS function for Rust serde format conversion
  - `contract_assemble_call_tx()` Rust FFI — manual JSON parsers bypass Midnight serde bugs
  - `ContractCallPrototype` → `Intent.add_call::<ProofPreimage>()` → `Transaction::from_intents()`
  - SCALE serialized `(Transaction, ProvingKeys)` tuple output
  - E2E test: circuit → extract → transform → assemble → serialized UnprovenTransaction ✅

- [x] **6H: Provider interfaces + CircuitExecutor** — Clean Kotlin API:
  - `CircuitExecutor` — wraps QuickJS lifecycle into single `executeCircuit()` call
  - `ProofProvider` interface + `LocalProofProvider` wrapping `LocalProver`
  - `WitnessProvider` / `WitnessResult` with secure zeroization
  - Input validation, state handle cleanup, `Dispatchers.IO` for blocking JNI
  - 5 device tests (happy path, error path, witness, different inputs, pipeline)
- [x] **6I: Private state + ZK config** — encrypted storage + contract key support:
  - `PrivateStateProvider` interface + `KeyStorePrivateStateProvider` (AES-256-GCM via Android KeyStore)
  - Moved to `core:crypto:state` (security primitive, not compact-engine concern)
  - `clearContract()`, `isKeyStoreHealthy()`, size guard, collision-safe keys
  - `ProvingKeyManager` extended: `hasContractKeys()`, `installContractKeys(overwrite)`,
    `removeContractKeys()`, `downloadContractKeys()`, `hasBLSParams()`
  - Path traversal validation on all filesystem inputs
  - 13 device tests (round-trip, isolation, corruption, size limit, clear, keystore health)

- [x] **6J-offline: Execute → assemble → prove on device** ✅
  - BBoard post circuit proven locally in 0.93s on ARM64 Android
  - 920 bytes UnprovenTransaction → 5066 bytes ProvenTransaction
  - Fixed queryLedgerState value mutation bug (Array.shift() corrupting transcript)
  - Fixed ChargedState missing `.state` public property (v0.15.0 compatibility)
  - Fixed persistentHash AlignedValue serde bypass (parse_aligned_value)
  - Transcript gas computed via QueryContext re-execution against cloned initial state
  - Upgraded to compact-runtime 0.15.0, onchain-runtime-v3, ledger-v8
  - BLS params + circuit keys installed via @Before from /data/local/tmp

- [x] **6J-online: Execute → prove → balance → submit on chain** ✅
  - Fetch on-chain contract state from indexer via GraphQL (`queryContractState`)
  - `contract_state_create` supports tagged deserialization (indexer SCALE format)
  - `CircuitExecutor` accepts `onChainStateHex` to execute against deployed state
  - Fetch ledger parameters for correct gas cost model (`ledgerParametersHex`)
  - Fixed OutOfGas rejection: node uses different cost model than `INITIAL_COST_MODEL`
  - TTL parameterized: `Timestamp::from_secs(current_time + 3600)`
  - BBoard `post` transaction submitted to Midnight localnet from Android ✅
  - Full pipeline: indexer fetch → circuit execute → local prove → DApp Connector balance → node submit
  - `mn` CLI upgraded: indexer-standalone 4.0.0 → 4.0.1 (per support matrix)

- [x] **6K-DX: Developer API + BBoard Example App** ✅ (see `docs/planning/6K_DEVELOPER_API_PLAN.md`)
  - `MidnightContract` facade: `contract.call("post", "Hello!")` — one-line circuit calls
  - `MidnightConfig` builder: indexer, wallet, prover wired once
  - `ArgConverter`: Kotlin values → JS expressions (type-safe, no injection)
  - Sealed error hierarchy (`ContractCallException`), progress callbacks (`ContractCallStage`)
  - `prepare()` for offline mode, `TransactionReceipt` with timing breakdown
  - 29 ArgConverter unit tests + 3 device integration tests
  - BBoard Android example app (`examples/bboard`) re-architected to use SDK
  - Verified on localnet, preview, preprod, mainnet (read-only)
  - Gradle `bboardSetup` task for automated test infrastructure

### Future Improvements:

**SDK Features:**
- [ ] **Typed state queries** — use contract `.d.ts` type definitions to return named fields (`{posterCount: 1, message: "Hello"}`) instead of raw JSON array indices
- [ ] **Private state persistence** — persist private state between app sessions using `KeyStorePrivateStateProvider`. Currently the secret key is hardcoded; real dApps need encrypted storage tied to the wallet
- [ ] **Contract state subscriptions** — observe on-chain state changes via indexer WebSocket for reactive UI updates (e.g., board state changes when someone else posts)
- [ ] **Auto proving key download** — `ProvingKeyManager` fetches circuit keys + BLS params on first `call()` if missing, instead of requiring manual `adb push` or bundled assets
- [ ] **Contract deploy from Android** — `MidnightContract.deploy()` for deploying new contracts directly from the phone (currently deploy-only via CLI)

**Distribution:**
- [ ] **Open-source BBoard** as standalone `midnight-bboard-android` repo (self-contained, no internal dependencies)
- [ ] **Publish SDK as Maven artifact** (`com.midnight:contract-sdk`) so external dApps can depend on it without cloning the wallet repo
- [ ] **Developer README** — step-by-step guide from zero to on-chain transaction

**CLI:**
- [x] **Fixed `mn contract call` takeDown bug** — deterministic secret key derived from wallet (was random per invocation)

---

## Known Limitations (with fix timeline)

| Limitation | Impact | Fix In |
|-----------|--------|--------|
| ~~`Transcript` gas/effects defaulted to zero~~ | ~~Node may reject~~ | ✅ Fixed — QueryContext re-execution computes correct gas |
| ~~`Timestamp::MAX` as TTL~~ | ~~Never expires~~ | ✅ Fixed — `current_time + 3600` parameterized |
| `AlignedValue` JSON serde round-trip broken | Can't use serde_json for Midnight types | ✅ Worked around — manual parsers |
| ~~Gas cost model mismatch~~ | ~~Node rejects with OutOfGas~~ | ✅ Fixed — uses ledger parameters cost model, not INITIAL_COST_MODEL |
| No contract deploy from Android | Can only call existing contracts | Out of scope — deploy via CLI |
| `ContractOperation` always `None` verifier key | Prover loads keys separately | OK — prover resolves from filesystem |
| ~~queryLedgerState value mutation~~ | ~~`fromValue().shift()` corrupts transcript~~ | ✅ Fixed — clone in circuit-context.js |

---

## Risks

### Value encoding correctness ✅ Mitigated
All encoding goes through Rust FFI. No JS fallbacks. Tested with bboard circuit producing valid proof preimages.

### Midnight serde compatibility ⚠️ Managed
`AlignedValue`, `StateValue`, `Op<ResultModeVerify>` can't round-trip through JSON serde. Manual parsers bypass this. Risk: new Op variants or AlignmentAtom variants could appear in future contracts. Mitigation: comprehensive Op parser covers all 20+ variants.

### Transaction structure versioning
The `midnight-ledger` crate is versioned (currently v8). Our FFI must match. Risk is low — we compile against the same crate.

---

## End State

An Android dApp developer can execute Compact contract circuits, generate ZK proofs, and submit transactions — all on the phone. No browser, no WebView. QuickJS (700KB) handles circuit orchestration. Rust (native) handles all crypto and encoding. The bboard example works end-to-end as proof.

---

## References

- JS SDK: `midnight-libraries/midnight-js/packages/`
- Compact runtime (JS): `@midnight-ntwrk/compact-js`, `@midnight-ntwrk/compact-runtime`
- WASM primitives: `midnight-ledger/onchain-runtime-wasm/src/primitives.rs`
- WASM state: `midnight-ledger/onchain-runtime-wasm/src/state.rs`
- Compiled contract example: `example-bboard/contract/src/managed/bboard/contract/index.js`
- Onchain VM (Rust): `midnight-ledger/onchain-vm/` — `Op` enum, `ResultModeGather`/`ResultModeVerify`
- QuickJS: quickjs-kt 1.0.3 (io.github.dokar3), IIFE bundle format
- Existing FFI: `kuira-crypto-ffi/src/` (zswap_ffi.rs, prove_ffi.rs, contract_ffi.rs, serialize.rs)
- Transaction assembly: `midnight-ledger/ledger/src/construct.rs` — `ContractCallPrototype`, `Intent::add_call`
