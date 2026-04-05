# Phase 6: Android DApp SDK

**Date:** 2026-04-03 (updated 2026-04-04)
**Status:** In Progress
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

Steps 4-5 are done (Phase 5). Steps 1-3 need the Android DApp SDK. Step 1 runs in QuickJS. Steps 2-3 run in native Rust FFI.

Important: the wallet receives a **proven** transaction (step 3), not an unproven one. The dApp must prove first, then send to the wallet for balancing.

---

## What Exists vs What's Needed

| Capability | JS SDK Package | Android Equivalent | Status |
|-----------|---------------|-------------------|--------|
| Circuit execution | `compact-js` + `compact-runtime` | QuickJS + shim | Structural ✅, values wrong ❌ |
| Contract deploy/call/find | `midnight-js/contracts` | — | Needed |
| Provider interfaces | `midnight-js/types` | — | Needed |
| Private state storage | `level-private-state-provider` | — | Needed |
| ZK config management | `fetch-zk-config-provider` | `ProvingKeyManager` (partial) | Extend |
| ZK proof generation | `http-client-proof-provider` | `LocalProver` | Done |
| Wallet integration | `dapp-connector-api` | `core:connector` | Done |
| Indexer queries | `indexer-public-data-provider` | `core:indexer` | Done |
| Zswap operations | `ledger-wasm/zswap_wasm.rs` | `zswap_ffi.rs` | Done |
| Key derivation | `midnight-wallet/hd` | `core:crypto` | Done |
| Transaction submission | `midnightProvider` | `TransactionSubmitter` | Done |
| Transaction structure | `@midnight-ntwrk/ledger` (WASM) | `midnight-ledger` Rust crate | Partial (zswap parts done) |

---

## Architecture

### Decision: QuickJS for orchestration, Rust FFI for ALL data encoding and crypto

**What we proved:** The bboard contract's `post` circuit executes in QuickJS on Android. The witness bridge works (Kotlin → JS). The circuit produces proof data.

**What we found wrong:** JS-only implementations of `persistentHash`, `bigIntToValue`, `valueToBigInt`, and state encoding produce DIFFERENT values than the WASM/Rust versions. Specifically:

- `persistentHash(alignment, value)` doesn't just SHA-256 the raw bytes. It uses `binary_repr` to serialize the value according to its alignment FIRST, then hashes. Our JS fallback skips this.
- `bigIntToValue` / `valueToBigInt` encode field elements with specific alignment rules. Our JS 32-byte LE encoding may not match.
- `QueryContext.query()` returns `AlignedValue` with specific byte-level encoding. Our JS shim returns approximate values.

**The result:** The circuit runs structurally, but the proof preimages are numerically wrong. Proofs generated from these preimages will not verify on-chain.

**The rule:** JS handles ONLY orchestration (opcode dispatch, control flow, witness callbacks). ALL data encoding, crypto, and state manipulation MUST go through Rust FFI. No JS fallbacks in production.

### What QuickJS does (light, fast, ~1ms):
- Runs the compiled contract JS code
- Dispatches opcodes to the Rust VM
- Bridges witness callbacks to Kotlin
- Collects proof preimage references

### What Rust FFI does (heavy, correct):
- `persistentHash(alignment, value)` — proper `binary_repr` + SHA-256
- `bigIntToValue(n)` / `valueToBigInt(value)` — proper field element encoding
- `QueryContext.query(state, opcodes)` — full VM execution with correct state encoding
- `StateValue` construction/serialization — matches on-chain format
- ZK proving, transaction building, SCALE serialization

---

## Libraries to Build

### 1. Provider Interfaces (`core:providers`)
Kotlin interfaces matching the JS SDK provider pattern. Implementations wrap existing Kuira modules.

### 2. Private State Storage (`core:private-state`)
Encrypted on-device storage for dApp-specific secrets.

### 3. ZK Config Manager (`core:zk-config`)
Download and cache ZKIR + prover/verifier keys for arbitrary contract circuits.

### 4. Transaction Builder FFI (`core:contract-tx`)
Extends the Rust FFI for contract transaction types (deploy, call).

### 5. Circuit Executor (`core:compact-engine`)
QuickJS + IIFE-bundled compact-runtime. The shim delegates ALL encoding/crypto to Rust FFI via Kotlin callbacks registered in QuickJS.

---

## Steps

### Done:
- [x] **6A: Rust FFI foundation** — `persistentHash` exposed, `contract_query` with state handles compiled for ARM64. onchain-runtime crates added to Cargo.toml.
- [x] **6B: QuickJS embedded** — quickjs-kt 1.0.3, 5 POC tests passing on emulator.
- [x] **6C: Onchain-runtime shim** — IIFE bundle (39KB), compact-runtime with shim replacing WASM. Structural execution works.
- [x] **6D: Run compiled contract** — bboard loads, initialState works, Contract class instantiates.
- [x] **6E: Witness bridge** — Kotlin↔JS callback working. Secret key passed from Kotlin to JS.
- [x] **Milestone: circuit executes** — bboard post() runs end-to-end, produces proof data. BUT values are wrong due to JS fallbacks.

### Remaining:
- [ ] **6F: Replace JS fallbacks with Rust FFI** — This is the critical correctness step. For each function the shim currently handles in JS, wire it to the Rust FFI instead:
  - `persistentHash(alignment, value)` → Rust `binary_repr` + SHA-256
  - `bigIntToValue(n)` / `valueToBigInt(value)` → Rust field element encoding
  - `QueryContext.query(state, opcodes)` → Rust `ContractStateExt::query` via state handles
  - `StateValue` construction → Rust via handles
  - Validate: proof preimages match what the WASM would produce
- [ ] **6G: Proof preimage → UnprovenTransaction** — bridge correct proof preimages to Rust transaction builder
- [ ] **6H: Provider interfaces** — Kotlin interfaces, wrap existing modules
- [ ] **6I: Private state + ZK config** — encrypted storage, circuit key management
- [ ] **6J: BBoard end-to-end** — deploy → post → prove → balance → submit → see on chain
- [ ] **6K: Testing** — unit tests per library, integration test on localnet, verify proofs match WASM output

6F is the critical path. Without correct values, nothing downstream works. Test by comparing Rust FFI output against WASM output for the same inputs.

---

## Risks

### Value encoding correctness
The #1 risk. Every function that touches data encoding must produce byte-identical output to the WASM version. One bit difference = invalid proof. Mitigation: test each FFI function against the WASM version with identical inputs.

### Rust FFI surface area
~12 functions need to be exposed through JNI with proper serialization at each boundary (JS ↔ Kotlin ↔ JNI ↔ Rust). Each boundary is a potential source of bugs. Mitigation: test each function in isolation before integration.

### QuickJS ↔ Rust data passing
Passing `AlignedValue` (Array<Uint8Array>) between JS and Rust through Kotlin requires careful serialization. Options: JSON, hex-encoded SCALE, or opaque handles. Handles are cleanest but require more FFI functions.

### Transaction structure versioning
The `midnight-ledger` crate is versioned (currently v8). Our FFI must match.

---

## End State

An Android dApp developer can execute Compact contract circuits, generate ZK proofs, and submit transactions — all on the phone. No browser, no WebView. QuickJS (700KB) handles circuit orchestration. Rust (native) handles all crypto and encoding. Proof preimages are byte-identical to what the WASM would produce. The bboard example works end-to-end as proof.

---

## References

- JS SDK: `midnight-libraries/midnight-js/packages/`
- Compact runtime (JS): `@midnight-ntwrk/compact-js`, `@midnight-ntwrk/compact-runtime`
- WASM primitives: `midnight-ledger/onchain-runtime-wasm/src/primitives.rs` — `persistentHash`, `bigIntToValue`, etc.
- WASM state: `midnight-ledger/onchain-runtime-wasm/src/state.rs` — `ContractState.query()`
- Compiled contract example: `example-bboard/contract/src/managed/bboard/contract/index.js`
- Onchain VM (Rust): `midnight-ledger/onchain-vm/` — `run_program`, `ResultModeGather`, `Op` enum
- QuickJS: quickjs-kt 1.0.3 (io.github.dokar3), IIFE bundle format
- Existing FFI: `kuira-crypto-ffi/src/` (zswap_ffi.rs, prove_ffi.rs, contract_ffi.rs)
