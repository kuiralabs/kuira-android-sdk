# Phase 6: Android DApp SDK

**Date:** 2026-04-03
**Status:** Planning
**Depends on:** Phase 5 (DApp Connector) ✅

---

## What We're Building

The missing piece between "dApp connects to wallet" and "dApp calls a contract."

Phase 5 gave us the wallet side — `balanceUnsealedTransaction()` and `submitTransaction()`. But a dApp needs to build the transaction first: execute the Compact circuit, generate the ZK proof, and THEN send it to the wallet for balancing and submission.

The JS SDK (`midnight-js`) does this for web dApps. We need the same for Android.

---

## How a Contract Call Works (bboard.post example)

```
1. dApp runs circuit locally
   Input:  compiled contract + circuit args + current state + private state
   Output: UnprovenTransaction (contains proof preimages)

2. dApp proves the transaction
   Input:  UnprovenTransaction + prover key + ZKIR
   Output: ProvenTransaction (ZK proof attached, no signatures yet)

3. WALLET balances the transaction        ← Phase 5 handles this
   Input:  ProvenTransaction (serialized hex)
   Output: FinalizedTransaction (coins added, fees paid, signed)

4. WALLET submits to network              ← Phase 5 handles this
   Input:  FinalizedTransaction
   Output: transaction hash
```

Steps 1-2 happen in the dApp. Steps 3-4 happen in the wallet (Kuira).
We have 3-4. We need 1-2.

---

## What Exists vs What's Needed

```
                        JS SDK              Android (Kuira)
                        ──────              ───────────────
Step 1 (execute)        onchain-runtime     ❌ need contract_ffi.rs
Step 2 (prove)          proof-provider      ✅ LocalProver (Phase 4C)
Step 3 (balance)        wallet connector    ✅ ConnectedAPI (Phase 5)
Step 4 (submit)         wallet connector    ✅ ConnectedAPI (Phase 5)

Supporting:
Indexer queries         indexer-provider    ✅ IndexerClient (Phase 4A)
Key derivation          hd/                 ✅ core:crypto (Phase 1)
Zswap operations        zswap_wasm.rs       ✅ zswap_ffi.rs (Phase 3)
Private state storage   level-provider      ❌ need core:private-state
ZK key management       fetch-zk-config     ⚠️  ProvingKeyManager (partial)
Provider interfaces     types/              ❌ need core:providers
```

---

## Libraries to Build

### 1. core:providers — Interfaces

Define the Kotlin provider contracts. Implementations mostly wrap existing modules.

```kotlin
interface PublicDataProvider {
    suspend fun getContractState(address: String): ContractState
    suspend fun getZswapChainState(): ZswapChainState
    fun watchForTxData(txId: String): Flow<FinalizedTxData>
}

interface PrivateStateProvider<S> {
    suspend fun get(contractAddress: String): S?
    suspend fun set(contractAddress: String, state: S)
}

interface ProofProvider {
    suspend fun proveTx(unprovenTx: UnprovenTransaction): ProvenTransaction
}

interface ZkConfigProvider {
    suspend fun getZkir(circuitId: String): ByteArray
    suspend fun getProverKey(circuitId: String): ByteArray
    suspend fun getVerifierKey(circuitId: String): ByteArray
}

interface WalletProvider {
    suspend fun balanceTx(tx: ProvenTransaction): FinalizedTransaction
}

interface MidnightProvider {
    suspend fun submitTx(tx: FinalizedTransaction): String
}
```

Implementation mapping:
- `PublicDataProvider` → wraps existing `IndexerClient`
- `ProofProvider` → wraps existing `LocalProver`
- `WalletProvider` → wraps `KuiraWalletClient` (IPC to wallet)
- `MidnightProvider` → wraps `KuiraWalletClient`

### 2. core:private-state — Encrypted DApp State

Stores dApp-specific secrets (e.g., bboard's secret key) on device.
Uses Android `EncryptedSharedPreferences`.

### 3. core:zk-config — Circuit Key Manager

Downloads and caches ZKIR + prover/verifier keys per contract circuit.
Extends existing `ProvingKeyManager` pattern to support arbitrary contracts.

### 4. core:contract — Contract Runtime (Rust FFI)

The big one. Compile `midnight-onchain-runtime` for ARM64.

```
Rust crates to compile:
midnight-onchain-runtime  ← circuit execution
midnight-onchain-vm       ← Compact virtual machine
midnight-onchain-state    ← contract state management
midnight-ledger           ← transaction structure
midnight-coin-structure   ← already compiles ✅
midnight-base-crypto      ← already compiles ✅
midnight-serialize        ← already compiles ✅
```

FFI functions:

```rust
// contract_ffi.rs

// Execute a circuit (step 1)
fn contract_execute_circuit(
    compiled_contract: hex,
    circuit_id: string,
    args: json,
    contract_state: hex,
    zswap_state: hex,
    private_state: hex,
) -> UnprovenTransaction (hex)

// Create deploy transaction
fn contract_create_deploy_tx(
    compiled_contract: hex,
    initial_state: hex,
) -> UnprovenTransaction (hex)
```

Kotlin wrapper:

```kotlin
class ContractRuntime {
    fun executeCircuit(
        compiledContract: ByteArray,
        circuitId: String,
        args: List<Any>,
        contractState: ByteArray,
        zswapState: ByteArray,
        privateState: ByteArray,
    ): UnprovenTransaction

    fun createDeployTransaction(
        compiledContract: ByteArray,
        initialState: ByteArray,
    ): UnprovenTransaction
}
```

---

## Build Order

```
6A. core:providers          (~8h)   interfaces + wrap existing modules
6B. core:private-state      (~5h)   encrypted storage
    core:zk-config          (~5h)   circuit key download
6C. core:contract           (~25h)  Rust FFI for circuit execution
6D. bboard integration      (~10h)  end-to-end with real contract
```

6A and 6B can run in parallel. 6C is the critical path.
6D proves everything works: deploy bboard → post message → see on chain.

---

## Risk

The `midnight-onchain-runtime` crate might have WASM-specific dependencies.

Evidence it should compile:
- The Midnight node runs this on x86 Linux (not WASM)
- We already compile `midnight-zswap` and `midnight-zkir` for ARM64
- The core crates (`coin-structure`, `base-crypto`, `serialize`) work

Evidence it might not:
- `ledger-wasm` uses `wasm-bindgen` and `getrandom` with `js` feature
- The onchain runtime might pull in WASM-only transitive deps

Mitigation: try compiling `midnight-onchain-runtime` for ARM64 early.
If it fails, identify which deps need patching. This is the first thing
to validate in 6C.

---

## End State

A dApp developer writes:

```kotlin
// In their Android app
val contract = ContractRuntime()
val unproven = contract.executeCircuit(bboardContract, "post", listOf(message), ...)
val proven = prover.proveTx(unproven)
val finalized = wallet.balanceTx(proven)    // IPC to Kuira
val txId = wallet.submitTx(finalized)       // IPC to Kuira
```

No browser. No JS. No WebView. Pure Android.
