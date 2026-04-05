# Phase 6: Android DApp SDK

**Date:** 2026-04-03 (updated 2026-04-05)
**Status:** In Progress — 6G complete, starting 6H
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

Steps 4-5 are done (Phase 5). Steps 1-2 are done (6D-6G). Step 3 needs provider wiring (6H).

Important: the wallet receives a **proven** transaction (step 3), not an unproven one. The dApp must prove first, then send to the wallet for balancing.

---

## What Exists vs What's Needed

| Capability | JS SDK Package | Android Equivalent | Status |
|-----------|---------------|-------------------|--------|
| Circuit execution | `compact-runtime` | QuickJS + IIFE shim | ✅ Done (6D-6F) |
| Proof preimage extraction | `compact-runtime` | `CircuitResult` + `transformPublicTranscript` | ✅ Done (6G) |
| Transaction assembly | `midnight-js/contracts` | `contract_assemble_call_tx` Rust FFI | ✅ Done (6G) |
| Provider interfaces | `midnight-js/types` | — | **6H** |
| Circuit executor API | `midnight-js/contracts` | — | **6H** |
| Private state storage | `level-private-state-provider` | — | **6I** |
| ZK config management | `fetch-zk-config-provider` | `ProvingKeyManager` (partial) | **6I** |
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

### 3. Provider Interfaces (`core:compact-engine`) — 6H
Kotlin interfaces matching the JS SDK provider pattern. `CircuitExecutor` wraps QuickJS lifecycle. `ProofProvider` wraps local prover. `ContractCallContext` orchestrates the full flow.

### 4. Private State Storage — 6I
Encrypted on-device storage for dApp-specific secrets (e.g., bboard secret key).

### 5. ZK Config Manager — 6I
Download and cache proving/verifier keys for arbitrary contract circuits.

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

### Remaining:
- [ ] **6H: Provider interfaces + CircuitExecutor** — Clean Kotlin API for dApp developers:
  - `CircuitExecutor` — wraps QuickJS lifecycle, FFI registration, circuit execution, result extraction
  - `ProofProvider` interface — wraps local prover (`zkir_prove_transaction_local`)
  - `ContractDeployer` — deploy contract transactions (initial state → deploy tx)
  - Wire: `CircuitExecutor.executeCircuit()` → `assembleContractCallTx()` → `ProofProvider.prove()` → ready for wallet
- [ ] **6I: Private state + ZK config** — encrypted storage for dApp secrets, circuit key download/caching
- [ ] **6J: BBoard end-to-end** — deploy → post → prove → balance → submit → verify on chain
  - Fix `Transcript` gas/effects (currently zeroed — need PreTranscript flow for node acceptance)
  - Fix TTL (currently `MAX` — need parameterized reasonable TTL)
- [ ] **6K: Testing** — unit tests per library, integration test on localnet

---

## Known Limitations (with fix timeline)

| Limitation | Impact | Fix In |
|-----------|--------|--------|
| `Transcript` gas/effects defaulted to zero | Node may reject transaction | 6J — implement PreTranscript flow |
| `Timestamp::MAX` as TTL | Never expires, not realistic | 6H — parameterize in provider |
| `AlignedValue` JSON serde round-trip broken | Can't use serde_json for Midnight types | Worked around — manual parsers in 6G |
| No contract deploy support | Can only call existing contracts | 6H — `ContractDeployer` |
| `ContractOperation` always `None` verifier key | Prover loads keys separately | OK for now, may need for complex contracts |

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
