# Phase 6: Android DApp SDK

**Date:** 2026-04-03
**Status:** Planning
**Depends on:** Phase 5 (DApp Connector) ✅

---

## Problem

A dApp needs to build a contract transaction before the wallet can balance and submit it. Phase 5 gave us the wallet side (balance + submit). The dApp side — circuit execution, proof generation, state management — has no Android equivalent. The JS SDK (`midnight-js`) handles this for web. We need the same for native Android.

---

## How a Contract Transaction Flows

1. **dApp executes circuit locally** — runs the Compact contract logic with private inputs, produces an unproven transaction
2. **dApp proves the transaction** — generates a ZK proof, producing a proven transaction
3. **Wallet balances the proven transaction** — adds coins, pays fees, signs (we have `ConnectedAPI.balanceUnsealedTransaction`)
4. **Wallet submits to network** — relays the finalized transaction to blockchain (we have `ConnectedAPI.submitTransaction`)

Steps 3-4 are done (Phase 5). Steps 1-2 need the Android DApp SDK.

Important: the wallet receives a **proven** transaction (step 3), not an unproven one. The dApp must prove first, then send to the wallet for balancing.

---

## What Exists vs What's Needed

| Capability | JS SDK Package | Android Equivalent | Status |
|-----------|---------------|-------------------|--------|
| Circuit execution | `compact-js` + `compact-runtime` | — | Needed |
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

Two layers to build:

**Layer 1 — Transaction structure and proving (Rust → ARM64)**
The `midnight-ledger` Rust crates handle transaction construction, serialization, and proving. We already compile parts of these (zswap, zkir) for ARM64. We need to extend to cover contract-related transaction types.

**Layer 2 — Circuit execution (the hard part)**
In the JS SDK, Compact circuits are executed by `compact-js` and `compact-runtime` — these are **pure TypeScript/JavaScript**, not Rust. The `midnight-onchain-runtime` Rust crate is for node-side on-chain validation, not dApp-side circuit execution.

This means we cannot simply "compile the Rust crate for ARM64" to get circuit execution. We need one of:

| Approach | Description | Tradeoff |
|----------|------------|---------|
| **A. Embedded JS runtime** | Run `compact-runtime` in an embedded V8/QuickJS/Hermes engine on Android | Fastest path — reuses existing JS code. Adds ~5-10MB. Some performance overhead. |
| **B. Port compact-runtime to Kotlin** | Rewrite the Compact VM in Kotlin | Clean native solution. Large effort. Must stay in sync with upstream. |
| **C. Port compact-runtime to Rust** | Rewrite in Rust, compile for ARM64 via FFI | Best performance. Largest effort. Could upstream to Midnight. |
| **D. WASM runtime on Android** | Run the existing `ledger-wasm` WASM binary via a WASM runtime (Wasmer/Wasmtime) | Uses the exact same code path as web. Adds ~2-5MB. Proven correct. |

**Recommendation:** Investigate D first (WASM runtime). It uses the same compiled output as the JS SDK, guaranteeing correctness. Wasmer and Wasmtime both support ARM64 Android. If performance is acceptable, this is the safest path.

Fallback to A (embedded JS) if WASM runtime has issues.

---

## Libraries to Build

### 1. Provider Interfaces (`core:providers`)
Kotlin interfaces matching the JS SDK provider pattern. Implementations wrap existing Kuira modules (IndexerClient, LocalProver, KuiraWalletClient).

### 2. Private State Storage (`core:private-state`)
Encrypted on-device storage for dApp-specific secrets (e.g., bboard's secret key). Uses Android EncryptedSharedPreferences.

### 3. ZK Config Manager (`core:zk-config`)
Download and cache ZKIR + prover/verifier keys for arbitrary contract circuits. Extends the existing ProvingKeyManager pattern beyond wallet-specific keys.

### 4. Transaction Builder (`core:contract-tx`)
Extends the Rust FFI layer to handle contract-related transaction types (deploy, call). Uses the `midnight-ledger` Rust crate which we already partially compile. This handles transaction construction and serialization, NOT circuit execution.

### 5. Contract Runtime (`core:compact-runtime`)
The critical piece. Executes Compact circuit code on Android. Approach depends on investigation results (WASM runtime vs embedded JS vs native port).

---

## Steps

- [ ] **6A: Investigate circuit execution approach** — try compiling `ledger-wasm` WASM binary and running it via Wasmer/Wasmtime on ARM64 Android. Measure performance. This determines the entire strategy. Do it first.
- [ ] **6B: Provider interfaces** — define Kotlin contracts, wrap existing modules with matching implementations
- [ ] **6C: Private state** — encrypted dApp state storage on device
- [ ] **6D: ZK config** — circuit key download and caching for arbitrary contracts
- [ ] **6E: Transaction builder FFI** — extend Rust FFI for contract transaction types (deploy, call) using midnight-ledger crate
- [ ] **6F: Contract runtime** — integrate the chosen circuit execution approach (WASM/JS/native) into a Kotlin-friendly API
- [ ] **6G: BBoard integration** — wire everything together, complete the Android bboard example end-to-end: deploy → post → see on chain
- [ ] **6H: Testing** — unit tests for each library, integration test with real contract on localnet

6A is the critical decision point — do it before anything else. 6B-6D can run in parallel after 6A. 6E-6F depend on 6A's outcome.

---

## Risks

### Circuit execution is pure JS, not Rust
The Compact runtime (`compact-js`, `compact-runtime`) is TypeScript/JavaScript. The Rust `midnight-onchain-runtime` crate is for node-side on-chain validation only. This means we can't just compile a Rust crate — we need a JS or WASM runtime on Android, or a port.

### WASM runtime performance on mobile
If we go with approach D (WASM runtime), we need to verify that Wasmer/Wasmtime on ARM64 Android provides acceptable performance for circuit execution. The WASM binary was designed for browser V8, which is highly optimized — a standalone WASM runtime may be slower.

### Staying in sync with upstream
The Compact runtime evolves with each Midnight release. Whatever approach we choose must be maintainable as the protocol changes. WASM or embedded JS approaches are easier to update than a native port.

### Transaction structure versioning
The `midnight-ledger` crate is versioned (currently v8). The JS SDK uses `@midnight-ntwrk/ledger-v8`. Our FFI must match the same version. This is already handled in our zswap FFI but needs attention for contract transaction types.

---

## End State

An Android dApp developer can execute Compact contract circuits, generate ZK proofs, and submit transactions — all on the phone. No browser, no WebView. The bboard example works end-to-end as proof.

---

## References

- JS SDK: `midnight-libraries/midnight-js/packages/`
- Compact runtime (JS): `@midnight-ntwrk/compact-js`, `@midnight-ntwrk/compact-runtime`
- Ledger WASM (Rust→WASM): `midnight-libraries/midnight-ledger/ledger-wasm/`
- Onchain runtime (Rust, node-side only): `midnight-libraries/midnight-ledger/onchain-runtime/`
- Transaction flow: `midnight-js/packages/contracts/src/submit-tx.ts` — `proveTx → balanceTx → submitTx`
- Existing FFI: `kuira-crypto-ffi/src/` (zswap_ffi.rs, prove_ffi.rs)
- BBoard contract: `midnight-libraries/example-bboard/contract/src/bboard.compact`
- BBoard API: `midnight-libraries/example-bboard/api/src/index.ts`
