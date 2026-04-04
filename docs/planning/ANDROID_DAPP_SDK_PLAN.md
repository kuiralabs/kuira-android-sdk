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
- The Rust `onchain-vm` has `ResultModeGather` for dApp-side execution — the VM itself is capable
- But the Compact compiler only outputs JavaScript — no binary opcode format exists
- The compiled JS interleaves VM opcodes with witness calls and control flow — opcodes can't be extracted without the orchestration logic
- No standalone WASM runtime can load `ledger-wasm` (733 wasm-bindgen JS bindings)
- WebView is 50-100MB RAM, wrong tool for a wallet

**The split:**

QuickJS handles circuit orchestration (~700KB, light computation):
- Runs the compiled contract JS unchanged
- Calls witness functions (bridges to Kotlin for private inputs)
- Collects proof preimages (publicTranscript, privateTranscriptOutputs)

Native Rust FFI handles heavy crypto (existing 10MB .so):
- ZK proof generation (LocalProver)
- Transaction building and serialization
- Hashing, signatures, coin selection
- Balance and submit via wallet IPC

This is not a compromise. The JS portion is trivial computation (~1ms). All the expensive work (proving ~1.7s, hashing, crypto) runs native. QuickJS is 700KB — smaller than a single proving key file.

**No lock-in:** if the Compact compiler ever adds a binary opcode output, we drop QuickJS and feed opcodes directly to the Rust VM. Zero architecture change — just swap the circuit executor.

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
QuickJS embedded in Android, loaded with `compact-runtime` and compiled contract JS. Provides Kotlin API to execute circuits, bridge witness callbacks, and extract proof preimages.

---

## Steps

- [ ] **6A: Extend Rust FFI for contract transactions** — add contract-related types from `midnight-ledger` to `kuira-crypto-ffi` (ContractCallPrototype, Intent, ContractDeploy). Cross-compile for ARM64. The `onchain-runtime` comes along as a transitive dependency of `midnight-ledger` — we don't call it directly.
- [ ] **6B: Embed QuickJS in Android** — integrate QuickJS into the build. Bundle `@midnight-ntwrk/compact-runtime` JS alongside it. Verify it can execute basic JS and load the compact-runtime module.
- [ ] **6C: Run a compiled contract in QuickJS** — load bboard's `index.js` in QuickJS on Android. Execute the `post` circuit with hardcoded inputs. Extract proof preimages (publicTranscript, privateTranscriptOutputs, input, output) from the result.
- [ ] **6D: Witness bridge** — implement Kotlin↔JS callback bridge so QuickJS can call Kotlin for witness data (private inputs like secret keys). Test with bboard's `localSecretKey` witness.
- [ ] **6E: Proof preimage → UnprovenTransaction** — bridge QuickJS output to Rust FFI. Pass proof preimages from JS to the Rust `ContractCallPrototype` → `Intent` → `UnprovenTransaction` pipeline. This is the critical glue between the two runtimes.
- [ ] **6F: Provider interfaces** — define Kotlin interfaces, implement using existing modules.
- [ ] **6G: Private state + ZK config** — encrypted storage and circuit key management.
- [ ] **6H: BBoard end-to-end** — deploy board → post message → see on chain. All on Android.
- [ ] **6I: Testing** — unit tests per library, integration test on localnet.

6A and 6B are independent — run in parallel. 6C-6D depend on 6B. 6E depends on 6A + 6C. 6F-6G can run anytime. 6H ties everything together.

---

## Risks

### QuickJS + compact-runtime compatibility
The compiled contract imports `@midnight-ntwrk/compact-runtime`. This JS library must run inside QuickJS (ES2020 compliant). Needs testing — step 6B validates this early.

### Witness function bridging
Witnesses are JS callbacks that need Kotlin data. The JS↔Kotlin bridge through QuickJS's C API is the most complex integration point. Step 6D is dedicated to solving this.

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
