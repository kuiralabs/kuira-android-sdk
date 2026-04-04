# Android DApp SDK — Implementation Plan

**Date:** 2026-04-03
**Status:** Planning
**Depends on:** Phase 5 (DApp Connector) ✅
**Goal:** Enable Android-native dApps to interact with Midnight contracts
without needing a browser, WebView, or JS runtime.

---

## The Gap

The Midnight JS ecosystem provides everything a web dApp needs:

```
midnight-js/packages/
├── contracts/      ← deploy, call, find contracts
├── compact/        ← compile Compact source
├── types/          ← providers, wallet, proof interfaces
├── indexer-public-data-provider/  ← read chain state
├── http-client-proof-provider/    ← generate ZK proofs
├── fetch-zk-config-provider/      ← download circuit keys
├── level-private-state-provider/  ← store dApp private state
└── utils/          ← serialization helpers
```

For Android, we need Kotlin equivalents of each. The good news: the heavy
lifting (circuit execution, ZK proving, transaction building) is done by
Rust crates in `midnight-ledger/` — the JS packages are wrappers over WASM
bindings. We can compile the same Rust crates for ARM64 and wrap them in
Kotlin via JNI, just like we did for Phase 3 (zswap FFI).

---

## Architecture: JS SDK vs Android SDK

```
JS SDK (web dApps):                    Android SDK (mobile dApps):
┌─────────────────────┐                ┌─────────────────────┐
│ midnight-js          │                │ midnight-android     │
│ (TypeScript)         │                │ (Kotlin)             │
│                     │                │                     │
│ contracts/          │    ═══════>    │ core:contract/       │
│   deploy, call,     │                │   deploy, call,      │
│   find, submit      │                │   find, submit       │
│                     │                │                     │
│ types/providers     │    ═══════>    │ core:providers/      │
│   wallet, proof,    │                │   wallet, proof,     │
│   publicData, zk    │                │   publicData, zk     │
└────────┬────────────┘                └────────┬────────────┘
         │                                      │
         │ WASM bindings                        │ JNI bindings
         │                                      │
┌────────▼────────────┐                ┌────────▼────────────┐
│ midnight-ledger     │                │ midnight-ledger      │
│ (Rust → WASM)       │                │ (Rust → ARM64 .so)   │
│                     │                │                     │
│ ledger-wasm/        │                │ kuira-crypto-ffi/    │
│   contract.rs       │                │   contract_ffi.rs    │
│   tx.rs             │                │   tx_ffi.rs          │
│   zswap_wasm.rs     │                │   zswap_ffi.rs ✅    │
│   state.rs          │                │   prove_ffi.rs ✅    │
└─────────────────────┘                └─────────────────────┘
```

The key insight: **same Rust crates, different target**. The JS SDK compiles
`midnight-ledger` to WASM. We compile it to ARM64 native. The Kotlin layer
mirrors the TypeScript layer.

---

## What We Already Have

| Component | JS SDK | Android (Kuira) | Status |
|-----------|--------|-----------------|--------|
| **Zswap operations** | `zswap_wasm.rs` | `zswap_ffi.rs` | ✅ Done (Phase 3) |
| **ZK proving** | `http-client-proof-provider` | `LocalProver` | ✅ Done (Phase 4C) |
| **Wallet connector** | `dapp-connector-api` | `core:connector` | ✅ Done (Phase 5) |
| **Indexer client** | `indexer-public-data-provider` | `core:indexer` | ✅ Done (Phase 4A) |
| **Transaction submit** | `midnightProvider.submitTx` | `TransactionSubmitter` | ✅ Done |
| **Balance transaction** | `walletProvider.balanceTx` | `ConnectedAPIHandler` | ✅ Done |
| **Key derivation** | `hd/` | `core:crypto` | ✅ Done (Phase 1) |
| **Contract execution** | `onchain-runtime` | — | ❌ Needed |
| **Contract call builder** | `contracts/call.ts` | — | ❌ Needed |
| **Contract deploy** | `contracts/deploy-contract.ts` | — | ❌ Needed |
| **Contract state reader** | `contracts/get-states.ts` | — | ❌ Needed |
| **Private state provider** | `level-private-state-provider` | — | ❌ Needed |
| **ZK config provider** | `fetch-zk-config-provider` | `ProvingKeyManager` (partial) | ⚠️ Partial |

---

## Libraries to Build

### 1. `core:contract` — Contract Runtime (Rust FFI)

**JS equivalent:** `midnight-js/packages/contracts/`

Compile `midnight-ledger` Rust crates for ARM64 and expose via JNI:

```
midnight-ledger crates needed:
├── midnight-onchain-runtime  ← executes Compact circuits
├── midnight-onchain-vm       ← the virtual machine
├── midnight-ledger (core)    ← transaction structure
├── midnight-coin-structure   ← coin/UTXO handling
├── midnight-storage          ← state storage
└── midnight-serialize        ← SCALE encoding
```

**Kotlin API:**

```kotlin
// Deploy a contract
class ContractDeployer(private val compiledContract: ByteArray) {
    fun createDeployTransaction(
        initialState: ContractState,
        privateState: ByteArray,
    ): UnprovenTransaction
}

// Call a contract
class ContractCaller(
    private val contractAddress: String,
    private val compiledContract: ByteArray,
) {
    fun createCallTransaction(
        circuitId: String,
        args: List<Any>,
        currentState: ContractState,
        privateState: ByteArray,
        zswapState: ByteArray,
    ): UnprovenTransaction
}

// Find deployed contract
class ContractFinder(private val indexerClient: IndexerClient) {
    suspend fun findContract(address: String): DeployedContract
}
```

**Rust FFI functions needed:**

```rust
// In kuira-crypto-ffi/src/contract_ffi.rs

pub extern "C" fn contract_create_deploy_tx(...) -> *const c_char;
pub extern "C" fn contract_create_call_tx(...) -> *const c_char;
pub extern "C" fn contract_execute_circuit(...) -> *const c_char;
pub extern "C" fn contract_get_state(...) -> *const c_char;
```

**Effort:** Large — depends on how cleanly `midnight-onchain-runtime` compiles
for ARM64 (no WASM-specific deps like `wasm-bindgen`).

### 2. `core:providers` — Provider Interfaces

**JS equivalent:** `midnight-js/packages/types/`

Kotlin interfaces matching the JS provider pattern:

```kotlin
interface PublicDataProvider {
    suspend fun getContractState(address: String): ContractState
    suspend fun getZswapChainState(): ZswapChainState
    fun watchForTxData(txId: String): Flow<FinalizedTxData>
}

interface PrivateStateProvider<S> {
    suspend fun get(contractAddress: String): S?
    suspend fun set(contractAddress: String, state: S)
    suspend fun clear(contractAddress: String)
}

interface ProofProvider {
    suspend fun proveTx(unprovenTx: UnprovenTransaction): ProvenTransaction
}

interface ZkConfigProvider {
    suspend fun getZkir(circuitKeyLocation: String): ByteArray
    suspend fun getProverKey(circuitKeyLocation: String): ByteArray
    suspend fun getVerifierKey(circuitKeyLocation: String): ByteArray
}

interface WalletProvider {
    suspend fun getCoinPublicKey(): ByteArray
    suspend fun getEncryptionPublicKey(): ByteArray
    suspend fun balanceTx(tx: UnboundTransaction): FinalizedTransaction
}

interface MidnightProvider {
    suspend fun submitTx(tx: FinalizedTransaction): String
}
```

**Effort:** Medium — mostly type definitions and interface design.
Implementations reuse existing Kuira modules (IndexerClient, LocalProver,
ConnectedAPIHandler).

### 3. `core:private-state` — DApp Private State Storage

**JS equivalent:** `midnight-js/packages/level-private-state-provider/`

Stores dApp-specific private state (e.g., bboard's secret key) securely
on the device.

```kotlin
class EncryptedPrivateStateProvider<S>(
    private val context: Context,
    private val serializer: (S) -> ByteArray,
    private val deserializer: (ByteArray) -> S,
) : PrivateStateProvider<S> {
    // Uses Android EncryptedSharedPreferences or EncryptedFile
}
```

**Effort:** Small — straightforward Android storage with encryption.

### 4. `core:zk-config` — ZK Configuration Provider

**JS equivalent:** `midnight-js/packages/fetch-zk-config-provider/`

Downloads and caches circuit keys (ZKIR, prover key, verifier key) for
contract circuits. Extends the existing `ProvingKeyManager`.

```kotlin
class ZkConfigProvider(
    private val cacheDir: File,
    private val baseUrl: String,
) {
    suspend fun getZkir(circuitId: String): ByteArray
    suspend fun getProverKey(circuitId: String): ByteArray
    suspend fun getVerifierKey(circuitId: String): ByteArray
}
```

**Effort:** Small — HTTP download + file cache, similar to ProvingKeyManager.

---

## Implementation Order

```
Phase 6A: Provider interfaces (core:providers)
  → Define Kotlin types matching JS SDK
  → Implement PublicDataProvider using existing IndexerClient
  → Implement ProofProvider using existing LocalProver
  → Implement WalletProvider using ConnectedAPIHandler
  → ~8-10 hours

Phase 6B: Private state + ZK config (core:private-state, core:zk-config)
  → Encrypted storage for dApp state
  → Circuit key download/cache
  → ~5-8 hours

Phase 6C: Contract runtime FFI (core:contract)
  → Compile midnight-onchain-runtime for ARM64
  → JNI bindings for circuit execution
  → Contract deploy/call transaction builders
  → ~20-30 hours (largest piece, depends on Rust compilation)

Phase 6D: Integration + bboard example
  → Wire everything together
  → Complete the bboard Android example with real contract calls
  → End-to-end test: deploy board → post message → see on chain
  → ~10-15 hours
```

---

## Risk: Rust Compilation for ARM64

The `midnight-onchain-runtime` crate depends on:
- `midnight-onchain-vm` — the Compact VM (likely portable Rust)
- `midnight-onchain-state` — state management (likely portable)
- `midnight-coin-structure` — already compiles (we use it in zswap_ffi)
- `midnight-base-crypto` — already compiles (we use it)

The main risk: the onchain runtime might have dependencies that assume
WASM target (e.g., `wasm-bindgen`, `js-sys`). The `ledger-wasm` crate
explicitly uses these, but the underlying `onchain-runtime` crate should
be target-agnostic since it's also used by the Midnight node (which runs
on x86 Linux, not WASM).

**Mitigation:** We already successfully compile `midnight-zswap` and
`midnight-zkir` for ARM64. The onchain runtime uses the same core crates.

---

## What This Unlocks

With the Android DApp SDK, developers can build:
- **BBoard** — fully native, no browser needed
- **Games** (Starship) — native Android with wallet integration
- **DeFi apps** — swap, stake, lend on mobile
- **Agent apps** — AI agents running Compact contracts from Android
- **Any Midnight dApp** — complete feature parity with web

No other blockchain has a native Android SDK for ZK smart contracts.
Kuira + this SDK would be a first.

---

## References

- **JS SDK source:** `midnight-libraries/midnight-js/packages/`
- **Ledger WASM:** `midnight-libraries/midnight-ledger/ledger-wasm/`
- **Onchain runtime:** `midnight-libraries/midnight-ledger/onchain-runtime/`
- **Existing FFI:** `kuira-crypto-ffi/src/` (zswap_ffi.rs, prove_ffi.rs)
- **BBoard contract:** `midnight-libraries/example-bboard/contract/src/bboard.compact`
- **BBoard API:** `midnight-libraries/example-bboard/api/src/index.ts`
