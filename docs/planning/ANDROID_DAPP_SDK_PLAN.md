# Phase 6: Android DApp SDK

**Date:** 2026-04-03
**Status:** Planning
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

### Decision: QuickJS for circuit execution, Rust FFI for everything else

**Investigation summary:**
- The Compact compiler outputs JavaScript — the compiled contract is JS code with embedded VM opcodes
- `compact-runtime` (the JS library compiled contracts import) is a thin JS glue layer (~1400 lines) on top of `@midnight-ntwrk/onchain-runtime-v2` — which is the Rust `onchain-runtime` compiled to **WASM**
- The heavy operations (state management, hashing, VM execution) are in the WASM, not in JS
- We already compile the same Rust `onchain-runtime` crate for ARM64 natively
- No standalone WASM runtime can load the WASM module (it uses wasm-bindgen with JS-specific bindings)

**The approach:**

QuickJS (~700KB) runs the compiled contract JS + compact-runtime glue. But the `onchain-runtime-v2` WASM import gets replaced with a **shim that calls our native Rust FFI**. The JS handles orchestration; all heavy computation runs native ARM64.

This means:
- Compiled contracts run unchanged (same JS the Compact compiler outputs)
- The ~1400 lines of compact-runtime JS glue runs in QuickJS
- Every call compact-runtime makes to the WASM module (`StateValue`, `QueryContext`, `runProgram`, etc.) is redirected to our existing Rust FFI
- ZK proving, transaction building, hashing — all native Rust

**No lock-in:** if the Compact compiler ever adds a binary opcode output, we drop QuickJS and go fully native.

---

## Libraries to Build

### 1. Provider Interfaces (`core:providers`)
Kotlin interfaces matching the JS SDK provider pattern. Implementations wrap existing Kuira modules (IndexerClient, LocalProver, KuiraWalletClient).

### 2. Private State Storage (`core:private-state`)
Encrypted on-device storage for dApp-specific secrets (e.g., bboard's secret key). Uses Android EncryptedSharedPreferences.

### 3. ZK Config Manager (`core:zk-config`)
Download and cache ZKIR + prover/verifier keys for arbitrary contract circuits. Extends the existing ProvingKeyManager pattern beyond wallet-specific keys.

### 4. Transaction Builder FFI (`core:contract-tx`)
Extends the Rust FFI layer to handle contract-related transaction types (deploy, call). Uses the `midnight-ledger` Rust crate. Handles transaction construction and serialization.

### 5. Circuit Executor (`core:compact-engine`)
QuickJS embedded in Android, loaded with `compact-runtime` JS glue and compiled contract JS. The `@midnight-ntwrk/onchain-runtime-v2` WASM import is replaced with a native shim that calls our Rust FFI (`StateValue`, `QueryContext`, `runProgram`, etc.). Provides Kotlin API to execute circuits, bridge witness callbacks, and extract proof preimages.

---

## Steps

- [ ] **6A: Extend Rust FFI for contract operations** — expose `onchain-runtime` types via JNI: `StateValue`, `QueryContext`, `ContractState`, `ChargedState`, `CostModel`, `runProgram`, `persistentHash`, plus transaction types `ContractCallPrototype`, `Intent`, `ContractDeploy`. These crates already compile for ARM64 as transitive deps of `midnight-ledger` (confirmed in build artifacts) — step is to expose them through JNI, not compile them for the first time.
- [ ] **6B: Embed QuickJS in Android** — integrate QuickJS into the build. Verify it can execute basic JS.
- [ ] **6C: Build onchain-runtime shim** — create a JS module that implements the `@midnight-ntwrk/onchain-runtime-v2` API (StateValue, QueryContext, runProgram, etc.) by calling our Rust FFI through QuickJS's C API. Bundle compact-runtime JS glue (~1400 lines) with this shim replacing the WASM import.
- [ ] **6D: Run a compiled contract** — load bboard's `index.js` + shimmed compact-runtime in QuickJS on Android. Execute the `post` circuit with hardcoded inputs. Extract proof preimages from the result.
- [ ] **6E: Witness bridge** — implement Kotlin↔JS callback bridge so QuickJS can call Kotlin for witness data (private inputs like secret keys). Test with bboard's `localSecretKey` witness.
- [ ] **6F: Proof preimage → UnprovenTransaction** — bridge QuickJS circuit output to Rust FFI. Pass proof preimages from JS to the Rust `ContractCallPrototype` → `Intent` → `UnprovenTransaction` pipeline. This is the critical glue between the two runtimes.
- [ ] **6G: Provider interfaces** — define Kotlin interfaces, implement using existing modules.
- [ ] **6H: Private state + ZK config** — encrypted storage and circuit key management.
- [ ] **6I: BBoard end-to-end** — deploy board → post message → see on chain. All on Android.
- [ ] **6J: Testing** — unit tests per library, integration test on localnet.

6A and 6B are independent — run in parallel. 6C depends on 6A (needs the Rust FFI for the shim). 6D-6E depend on 6B + 6C. 6F depends on 6A + 6D. 6G-6H can run anytime. 6I ties everything together.

---

## Risks

### Onchain-runtime shim completeness
`compact-runtime` re-exports 47 functions/types from `onchain-runtime-v2`. For bboard, ~12 are actively used including `StateValue`, `QueryContext`, `ContractState`, `ChargedState`, `CostModel`, `persistentHash`, `dummyContractAddress`, and `valueToBigInt`. Each needs a matching Rust FFI call in the QuickJS shim. Step 6D tests with a real contract to catch any gaps.

### queryLedgerState complexity
`queryLedgerState` (in compact-runtime JS) calls `QueryContext.query()` which runs VM opcodes via the Rust `runProgram`. It then processes the results to build the `publicTranscript` for the proof. This orchestration between JS and Rust is the most intricate part of the shim — the JS collects `read` events from the VM results and splices them into the opcode stream.

### QuickJS + compact-runtime compatibility
The ~1400 lines of compact-runtime JS glue must run inside QuickJS (ES2020). Most of it is type handling and re-exports. The main concern is any use of Node.js or browser-specific APIs (`Buffer`, `crypto`, `fetch`).

### Witness function bridging
Witnesses are JS callbacks that need Kotlin data. The JS↔Kotlin bridge through QuickJS's C API is the most complex integration point. Step 6E is dedicated to solving this.

### Transitive dependencies during ARM64 compilation
The Rust crates have zero WASM deps at the top level, but transitive deps might introduce platform-specific code. Already handled for zswap and zkir — same mitigation.

### Transaction structure versioning
The `midnight-ledger` crate is versioned (currently v8). Our FFI must match. Already handled in our zswap FFI.

---

## End State

An Android dApp developer can execute Compact contract circuits, generate ZK proofs, and submit transactions — all on the phone. No browser, no WebView. QuickJS (700KB) handles circuit orchestration. Rust (native) handles all crypto. The bboard example works end-to-end as proof.

---

## References

- JS SDK: `midnight-libraries/midnight-js/packages/`
- Compact runtime (JS): `@midnight-ntwrk/compact-js`, `@midnight-ntwrk/compact-runtime`
- Compiled contract example: `example-bboard/contract/src/managed/bboard/contract/index.js`
- Onchain VM (Rust): `midnight-ledger/onchain-vm/` — `run_program`, `ResultModeGather`, `Op` enum
- Transaction flow: `midnight-js/packages/contracts/src/submit-tx.ts` — `proveTx → balanceTx → submitTx`
- QuickJS: https://bellard.org/quickjs/
- Existing FFI: `kuira-crypto-ffi/src/` (zswap_ffi.rs, prove_ffi.rs)
- BBoard contract: `example-bboard/contract/src/bboard.compact`
- BBoard API: `example-bboard/api/src/index.ts`
