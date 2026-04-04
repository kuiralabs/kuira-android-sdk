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

### What the investigation found

**The Rust VM can execute contracts dApp-side.** The `onchain-vm` crate has two modes:
- `ResultModeVerify` — node-side, verifies proofs on-chain
- `ResultModeGather` — dApp-side, gathers proof preimages

All required Rust crates (`onchain-runtime`, `onchain-vm`, `ledger`, `onchain-state`) have zero WASM dependencies. They compile for ARM64.

**But there's a gap.** The Compact compiler (`compactc`) only outputs JavaScript — not binary opcodes. The compiled contract (`index.js`) is generated JS code that contains VM opcodes (dup, idx, popeq, push) embedded as JSON structures, interleaved with witness function calls and proof data collection.

The opcodes in the JS match the Rust VM's `Op` enum exactly. The Rust VM CAN execute them — but we need a way to feed them in without running JS.

### Options to bridge the gap

| Approach | Description | Effort | Risk |
|----------|------------|--------|------|
| **A. Embedded lightweight JS** | Run compiled contract JS in QuickJS (~700KB, not a WebView) | Medium | Low — uses existing compiler output as-is |
| **B. Binary opcode format** | Ask/contribute to Midnight to add a binary output to the Compact compiler | Medium | Depends on upstream acceptance |
| **C. JS-to-opcode extractor** | Parse the compiled JS to extract opcode sequences, feed to Rust VM | Large | Fragile — breaks if compiler output format changes |
| **D. Compact-to-Rust transpiler** | Convert compiler output to Rust Op sequences at build time | Large | Must track compiler changes |

**Recommendation:** Start with A (QuickJS). It's 700KB, runs the existing compiled contracts unchanged, and doesn't depend on upstream changes. The JS execution is only for circuit logic — the heavy crypto (hashing, ZK proving, transaction building) stays in native Rust via FFI.

Long-term, propose B to the Midnight team — a binary opcode format would enable fully native execution on any platform.

### Why not WebView/WASM

- No standalone WASM runtime can load `ledger-wasm` — it uses `wasm-bindgen` with 733 JS-specific bindings
- WebView adds 50-100MB RAM, larger attack surface, battery drain
- QuickJS is 700KB, embeddable, no browser dependencies

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

- [ ] **6A: Compile onchain-runtime for ARM64** — add `onchain-runtime`, `onchain-vm`, `onchain-state` to `kuira-crypto-ffi` Cargo.toml and cross-compile. Validate it links and runs on emulator.
- [ ] **6B: Embed QuickJS** — integrate QuickJS (~700KB) into the Android build. Verify it can load and execute a compiled Compact contract (`index.js`) with `compact-runtime` dependencies.
- [ ] **6C: Contract execution bridge** — Kotlin wrapper that loads compiled contract JS in QuickJS, provides witness callbacks from Kotlin, and extracts proof preimages from the result.
- [ ] **6D: Transaction builder FFI** — expose `ContractCallPrototype`, `ContractDeploy`, and transaction serialization via JNI using the Rust `midnight-ledger` crate.
- [ ] **6E: Provider interfaces** — define Kotlin contracts, wrap existing modules with matching implementations.
- [ ] **6F: Private state** — encrypted dApp state storage on device.
- [ ] **6G: ZK config** — circuit key download and caching for arbitrary contracts.
- [ ] **6H: BBoard integration** — wire everything together, complete the Android bboard example end-to-end: deploy → post → see on chain.
- [ ] **6I: Testing** — unit tests for each library, integration test with real contract on localnet.

6A and 6B can run in parallel (independent). 6C depends on both. 6D-6G can run in parallel after 6A. 6H ties everything together.

---

## Risks

### Compact compiler only outputs JavaScript
The compiled contract is JS — not a binary format the Rust VM can directly consume. Our bridge (QuickJS + Kotlin callbacks) adds a layer. If the Compact compiler ever adds a binary output, we can eliminate QuickJS entirely and go fully native.

### QuickJS + compact-runtime compatibility
The compiled contract imports `@midnight-ntwrk/compact-runtime`. We need this JS library to run inside QuickJS. It should work (QuickJS is ES2020 compliant) but needs testing with the specific runtime APIs used.

### Witness function bridging
Witnesses are JavaScript callbacks (e.g., `localSecretKey(witnessContext)`). In our architecture, the witness logic runs in Kotlin — QuickJS needs to call back into Kotlin to get private inputs. This JS↔Kotlin bridge is the most complex part of 6C.

### Transitive dependencies during ARM64 compilation
The top-level crates have zero WASM deps, but transitive dependencies might introduce platform-specific code. We already handle this for zswap and zkir.

### Transaction structure versioning
The `midnight-ledger` crate is versioned (currently v8). Our FFI must match. Already handled in our zswap FFI.

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
