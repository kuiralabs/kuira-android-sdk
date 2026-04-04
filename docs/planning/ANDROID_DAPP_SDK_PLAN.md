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
2. **dApp proves the transaction** — generates ZK proof (we have `LocalProver`)
3. **Wallet balances the transaction** — adds coins, pays fees (we have `ConnectedAPI.balanceUnsealedTransaction`)
4. **Wallet submits to network** — relays to blockchain (we have `ConnectedAPI.submitTransaction`)

Steps 3-4 are done (Phase 5). Steps 1-2 need the Android DApp SDK.

---

## What Exists vs What's Needed

| Capability | JS SDK Package | Android Equivalent | Status |
|-----------|---------------|-------------------|--------|
| Circuit execution | `onchain-runtime` | — | Needed |
| Contract deploy/call | `midnight-js/contracts` | — | Needed |
| Provider interfaces | `midnight-js/types` | — | Needed |
| Private state storage | `level-private-state-provider` | — | Needed |
| ZK config management | `fetch-zk-config-provider` | `ProvingKeyManager` (partial) | Extend |
| ZK proof generation | `http-client-proof-provider` | `LocalProver` | Done |
| Wallet integration | `dapp-connector-api` | `core:connector` | Done |
| Indexer queries | `indexer-public-data-provider` | `core:indexer` | Done |
| Zswap operations | `ledger-wasm/zswap_wasm.rs` | `zswap_ffi.rs` | Done |
| Key derivation | `midnight-wallet/hd` | `core:crypto` | Done |
| Transaction submission | `midnightProvider` | `TransactionSubmitter` | Done |

---

## Architecture

Same Rust crates, different compilation target. The JS SDK compiles `midnight-ledger` to WASM for browsers. We compile it to ARM64 native for Android, accessed via JNI — the same pattern we use for zswap and zkir.

The Kotlin layer mirrors the TypeScript layer: provider interfaces, contract runtime wrapper, state management.

Key Rust crates to compile for ARM64:
- `midnight-onchain-runtime` — executes Compact circuits
- `midnight-onchain-vm` — the virtual machine for Compact
- `midnight-ledger` — transaction structure and construction
- `midnight-onchain-state` — contract state management
- `midnight-coin-structure` — already compiles (used by zswap FFI)
- `midnight-base-crypto` — already compiles (used by zswap FFI)

---

## Libraries to Build

### 1. Provider Interfaces (`core:providers`)
Kotlin interfaces matching the JS SDK provider pattern. Implementations wrap existing Kuira modules (IndexerClient, LocalProver, KuiraWalletClient).

### 2. Private State Storage (`core:private-state`)
Encrypted on-device storage for dApp-specific secrets (e.g., bboard's secret key). Uses Android EncryptedSharedPreferences.

### 3. ZK Config Manager (`core:zk-config`)
Download and cache ZKIR + prover/verifier keys for arbitrary contract circuits. Extends the existing ProvingKeyManager pattern beyond wallet-specific keys.

### 4. Contract Runtime (`core:contract`)
The critical piece. Rust FFI layer that compiles `midnight-onchain-runtime` for ARM64 and exposes circuit execution and transaction building via JNI. Same approach as `zswap_ffi.rs` and `prove_ffi.rs`.

---

## Steps

- [ ] **6A: Provider interfaces** — define Kotlin contracts, wrap existing modules with matching implementations
- [ ] **6B: Private state** — encrypted dApp state storage on device
- [ ] **6C: ZK config** — circuit key download and caching for arbitrary contracts
- [ ] **6D: Compile onchain-runtime for ARM64** — validate that the Rust crates compile without WASM-specific deps. This is the highest-risk step — do it early
- [ ] **6E: Contract FFI** — JNI bindings for circuit execution and transaction building
- [ ] **6F: Contract runtime Kotlin wrapper** — deploy, call, find contracts via the FFI layer
- [ ] **6G: BBoard integration** — wire everything together, complete the Android bboard example end-to-end: deploy → post → see on chain
- [ ] **6H: Testing** — unit tests for each library, integration test with real contract on localnet

6A-6C can run in parallel. 6D is the critical path — if the Rust crates don't compile cleanly for ARM64, everything else is blocked until that's resolved.

---

## Risk

The `midnight-onchain-runtime` crate might have WASM-specific transitive dependencies.

**Why it should work:** The Midnight node runs this crate on x86 Linux (not WASM). We already compile `midnight-zswap` and `midnight-zkir` for ARM64 successfully. The core crates are shared.

**Why it might not:** The `ledger-wasm` crate uses `wasm-bindgen` and `getrandom` with the `js` feature. If the onchain runtime pulls any of these transitively, we'd need to patch or feature-gate them.

**Mitigation:** Attempt the ARM64 compilation in step 6D before writing any Kotlin code. If it fails, identify exactly which dependencies need patching.

---

## End State

An Android dApp developer can execute Compact contract circuits, generate ZK proofs, and submit transactions — all natively on the phone. No browser, no JS runtime, no WebView. The bboard example works end-to-end as proof.

No other blockchain has a native Android SDK for ZK smart contracts.

---

## References

- JS SDK: `midnight-libraries/midnight-js/packages/`
- Ledger WASM bindings: `midnight-libraries/midnight-ledger/ledger-wasm/`
- Onchain runtime: `midnight-libraries/midnight-ledger/onchain-runtime/`
- Existing FFI: `kuira-crypto-ffi/src/` (zswap_ffi.rs, prove_ffi.rs)
- BBoard contract: `midnight-libraries/example-bboard/contract/src/bboard.compact`
- BBoard API: `midnight-libraries/example-bboard/api/src/index.ts`
