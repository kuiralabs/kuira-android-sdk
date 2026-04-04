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

**Fully native Rust → ARM64.** No WebView, no JS, no WASM runtime.

Investigation revealed that the Rust `onchain-vm` has two execution modes:
- `ResultModeVerify` — node-side, verifies proofs on-chain
- `ResultModeGather` — **dApp-side, gathers proof preimages** (exactly what a dApp needs)

The JS `compact-runtime` is a TypeScript reimplementation of the Rust VM. We don't need it — the Rust crate already supports dApp-side execution. The `onchain-runtime` crate explicitly uses `ResultModeGather` for off-chain circuit execution.

All required Rust crates (`onchain-runtime`, `onchain-vm`, `ledger`, `onchain-state`) have **zero WASM dependencies**. Same crates the Midnight node runs on Linux x86. Will compile cleanly for ARM64 Android — same approach we already use for zswap and zkir.

**Why not WebView/WASM:**
- WebView adds 50-100MB RAM, larger attack surface, battery drain
- No standalone WASM runtime can load `ledger-wasm` (it uses wasm-bindgen with 733 JS-specific bindings)
- Every mobile wallet using WebView for WASM (SubWallet, 1AM) does it as a workaround, not by choice
- We already have the native Rust FFI pipeline proven and shipping

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

### 5. Contract Runtime (`core:contract`)
The critical piece. Compile `onchain-runtime` + `onchain-vm` for ARM64 and expose circuit execution via JNI. Uses `ResultModeGather` for dApp-side proof preimage generation — the same mode the Rust crate already supports.

---

## Steps

- [ ] **6A: Compile onchain-runtime for ARM64** — add `onchain-runtime`, `onchain-vm`, `onchain-state` to `kuira-crypto-ffi` Cargo.toml and cross-compile. Validate it links and runs on emulator. This is the highest-risk step.
- [ ] **6B: Contract FFI** — expose circuit execution (`run_program` with `ResultModeGather`) and contract transaction building (`ContractCallPrototype`) via JNI
- [ ] **6C: Provider interfaces** — define Kotlin contracts, wrap existing modules with matching implementations
- [ ] **6D: Private state** — encrypted dApp state storage on device
- [ ] **6E: ZK config** — circuit key download and caching for arbitrary contracts
- [ ] **6F: Contract runtime Kotlin wrapper** — deploy, call, find contracts via the FFI layer
- [ ] **6G: BBoard integration** — wire everything together, complete the Android bboard example end-to-end: deploy → post → see on chain
- [ ] **6H: Testing** — unit tests for each library, integration test with real contract on localnet

6A is the critical path — do it first. If the crates compile (they should — zero WASM deps), everything else follows. 6C-6E can run in parallel after 6A.

---

## Risks

### Transitive dependencies during ARM64 compilation
The top-level crates have zero WASM deps, but transitive dependencies might introduce platform-specific code. We already handle this for zswap and zkir — same mitigation applies (feature-gating, patching).

### Staying in sync with upstream
The `onchain-runtime` and `onchain-vm` crates evolve with each Midnight release. Our FFI layer must track version updates. Since we compile from source (same approach as zswap), we update by bumping the crate path — straightforward.

### Transaction structure versioning
The `midnight-ledger` crate is versioned (currently v8). The JS SDK uses `@midnight-ntwrk/ledger-v8`. Our FFI must match the same version. Already handled in our zswap FFI.

### Witness function integration
The JS SDK uses TypeScript witness functions to provide private inputs to circuits. In the Rust path, witnesses are handled differently — need to understand how `ResultModeGather` integrates with dApp-provided witnesses. This is the main unknown in step 6B.

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
