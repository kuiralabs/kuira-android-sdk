# 6K: Developer-Facing Contract API

**Date:** 2026-04-06 (updated 2026-04-07)
**Status:** Complete — SDK API + BBoard example app functional
**Depends on:** 6A-6J (all complete)

---

## Problem

The current contract execution API exposes engine internals to dApp developers:

```kotlin
// CURRENT: 35 lines of manual orchestration, JS expressions as strings
val onChainStateHex = fetchContractState(contractAddress)
val ledgerParamsHex = fetchLedgerParameters()
val result = executor.executeCircuit(
    contractJs = loadAsset("runtime/bboard-contract-iife.js"),
    contractAddress = contractAddress,
    circuitName = "post",
    circuitArgs = listOf("'Hello from BBoard E2E!'"),  // JS expression!
    witnesses = mapOf("localSecretKey" to WitnessProvider { WitnessResult(null, key) }),
    initialPrivateState = "{ secretKey: new Uint8Array(32) }",  // JS expression!
    coinPublicKey = ByteArray(32),
    onChainStateHex = onChainStateHex,
    ledgerParametersHex = ledgerParamsHex,
)
val provenTxHex = proofProvider.prove(result.unprovenTxHex)
val connector = DAppConnectorClient("ws://10.0.2.2:9932")
connector.connect()
val balancedTxHex = connector.balanceTransaction(provenTxHex)
connector.submitTransaction(balancedTxHex)
connector.disconnect()
```

**Issues:**
- JS expressions as Kotlin Strings (type-unsafe, injection risk)
- Manual state/params fetching from indexer
- 5-step manual orchestration
- Raw hex strings everywhere
- Connection management is manual
- No progress feedback during proving (~1s)

---

## Goal

Match the JS SDK's DX quality:

```kotlin
// TARGET: 2 lines
val bboard = MidnightContract.create(config) { /* one-time setup */ }
val receipt = bboard.call("post", "Hello from Android!")
```

---

## Design Decisions

| Decision | Choice | Why |
|----------|--------|-----|
| Redesign vs wrap | **Wrap** CircuitExecutor | Proven engine, don't break it. Facade absorbs complexity. |
| Circuit args | **Kotlin values** with auto-conversion | `"Hello"` becomes `'Hello'` via `ArgConverter`. No JS injection. |
| Private state | **Map<String, Any?>** | Auto-serialized to JS object. Developer never writes JS. |
| State fetching | **Automatic** inside `call()` | Fetches from indexer transparently. |
| Connections | **Pooled** in MidnightConfig | Reuses WebSocket, auto connect/disconnect. |
| Errors | **Sealed hierarchy** | `ContractCallException.ProvingFailed` etc. |
| Progress | **Callback** on `call()` | `ContractCallStage` sealed class for UI spinners. |

---

## API Surface

### Layer 1: Config (once per app)

```kotlin
val config = MidnightConfig.Builder(context)
    .indexerUrl("http://10.0.2.2:8088/api/v3")
    .walletUrl("ws://10.0.2.2:9932")
    .networkId("undeployed")
    .build()
```

### Layer 2: Contract handle (once per contract)

```kotlin
val bboard = MidnightContract.create(config) {
    name = "bboard"
    contractJs = assets.open("runtime/bboard-contract-iife.js")
    address = "4b459404...ba91"
    witness("localSecretKey") { WitnessResult(null, secretKeyBytes) }
    initialPrivateState = mapOf("secretKey" to ByteArray(32))
    coinPublicKey = walletKeys.coinPublicKey
}
```

### Layer 3: Call circuit (per transaction)

```kotlin
// Simple
val receipt = bboard.call("post", "Hello from Android!")

// With progress
val receipt = bboard.call("post", "Hello!") { stage ->
    when (stage) {
        is ContractCallStage.FetchingState -> showSpinner("Loading...")
        is ContractCallStage.Proving -> showSpinner("Proving...")
        is ContractCallStage.Submitting -> showSpinner("Submitting...")
        else -> {}
    }
}

// Offline (execute + prove only, submit later)
val prepared = bboard.prepare("post", "Hello!")
val receipt = config.submit(prepared)
```

---

## Internal Architecture

```
bboard.call("post", "Hello!")
    |
    v
ArgConverter.toJsExpression("Hello!")        --> "'Hello!'"
PrivateStateConverter.toJs(map)              --> "{ secretKey: new Uint8Array([0,...]) }"
    |
    v
IndexerClient.queryContractState(address)    --> onChainStateHex
IndexerClient.getCurrentBlockWithParams()    --> ledgerParametersHex
    |
    v
CircuitExecutor.executeCircuit(...)          <-- UNCHANGED
    |
    v
ProofProvider.prove(unprovenTxHex)           <-- UNCHANGED
    |
    v
DAppConnectorClient.balanceTransaction(...)  <-- UNCHANGED
DAppConnectorClient.submitTransaction(...)   <-- UNCHANGED
    |
    v
TransactionReceipt(txHash, status, timings)
```

**No existing files are modified.** Purely additive.

---

## New Files

All in `core/compact-engine/src/main/kotlin/com/midnight/kuira/core/compact/`:

| File | Type | Purpose |
|------|------|---------|
| `ArgConverter.kt` | Internal | Kotlin values --> JS expressions (type-safe) |
| `MidnightConfig.kt` | Public | Config builder, connection pool, provider wiring |
| `MidnightContract.kt` | Public | Contract facade with `call()` and `prepare()` |
| `ContractPipeline.kt` | Internal | Orchestration (fetch --> execute --> prove --> balance --> submit) |
| `TransactionReceipt.kt` | Public | Result types |
| `ContractCallStage.kt` | Public | Progress stages sealed class |
| `ContractCallException.kt` | Public | Sealed error hierarchy |

---

## Implementation Steps (TDD)

### Step 1: ArgConverter + unit tests ✅
- [x] Pure logic, zero dependencies
- [x] String escaping (single quotes, backslashes, newlines)
- [x] Numbers (Int, Long, BigInteger)
- [x] ByteArray --> `new Uint8Array([...])`
- [x] Boolean
- [x] Map --> JS object literal
- [x] List --> JS array
- [x] null
- [x] Injection attempt tests (strings with `'`, `\`, etc.)
- [x] 29 unit tests passing

### Step 2: Result types ✅
- [x] `TransactionReceipt` (txHash, status, timings)
- [x] `PreparedTransaction` (provenTxHex, metadata)
- [x] `ContractCallStage` sealed class (FetchingState, Executing, Proving, Balancing, Submitting)
- [x] `ContractCallException` sealed hierarchy (6 subclasses)
- [x] `PipelineTimings` data class with `totalMs` computed property

### Step 3: MidnightConfig ✅
- [x] Builder pattern with URL validation
- [x] `localDev(context)` convenience factory
- [x] Mutex-protected connection pool for DAppConnectorClient
- [x] Owns CircuitExecutor, ProofProvider references
- [x] `close()` for cleanup
- [x] `submit(prepared)` for deferred submission
- [x] Internal `graphqlQuery()` for indexer calls (no dependency on core:indexer)

### Step 4+5: MidnightContract + Pipeline ✅
- [x] DSL builder (`create(config) { ... }`)
- [x] `call(circuitName, vararg args)` --> TransactionReceipt
- [x] `call(circuitName, vararg args, onProgress)` --> TransactionReceipt
- [x] `prepare(circuitName, vararg args)` --> PreparedTransaction
- [x] Contract JS loaded from InputStream (closed via `.use {}`)
- [x] Witness registration via DSL
- [x] Private state as Map (auto-converted via ArgConverter)
- [x] `coinPublicKey` required (no zero-default)
- [x] Pipeline: fetch → execute → prove → balance → submit
- [x] Progress callback invocation at each stage
- [x] Timing measurement per stage
- [x] Error wrapping into ContractCallException hierarchy

### Step 6: Integration test ✅
- [x] `bboard.call("post", "Hello!")` e2e on localnet
- [x] Progress callback test (all 5 stages in order)
- [x] Offline mode test with `prepare()`
- [x] Gradle `bboardSetup` task for automated infrastructure
- [x] Existing offline tests still pass
- [x] 29 ArgConverter unit tests still pass

### Step 7: BBoard Example App ✅
- [x] Re-architected `examples/bboard` to use `MidnightContract` SDK
- [x] `BBoardViewModel` uses `MidnightConfig` + `MidnightContract.call()`
- [x] `BBoardRepository` reads board state from indexer
- [x] Setup screen with contract address input + network selector
- [x] Progress stages shown during circuit execution + proving
- [x] Deleted `KuiraWalletClient` (replaced by SDK)
- [x] Contract JS bundled as asset
- [x] App builds, installs, launches on emulator

### Step 8: Open Source (Future)
- [ ] Extract to standalone `midnight-bboard-android` repo
- [ ] Self-contained (no internal path dependencies)
- [ ] Publish SDK as Maven artifact
- [ ] README with step-by-step guide

---

## Verification Checklist

- [x] `ArgConverter` unit tests pass (29 tests, string escaping, injection, all types)
- [x] All existing CircuitExecutor tests still pass (no modifications)
- [x] All existing BBoard offline tests still pass
- [x] New `MidnightContract.call()` e2e test submits to localnet
- [x] Code review fixes applied (mutex, InputStream close, coinPublicKey required)
- [x] BBoard example app re-architected to use SDK (Step 7)
- [ ] Open-source as standalone repo (Step 8 — future)

---

## Reference: JS SDK Equivalent

The JS SDK's developer flow for comparison:

```typescript
// Setup
const providers = { publicDataProvider, privateStateProvider, zkConfigProvider, proofProvider, walletProvider, midnightProvider };
const contract = await deployContract(providers, { compiledContract: BBoardContract });

// Call — one line
const txData = await contract.callTx.post("Hello!");
```

Our target matches this DX: `bboard.call("post", "Hello!")`.

---

## Future: SDK Examples & Open Source (Not Yet Started)

The developer API is the foundation for open-source example dApps.
These need careful design since they'll be the first thing new Midnight Android developers see.

### BBoard Android Example App

**Repository:** `midnight-bboard-android` (to be open-sourced)
**Location:** `/Users/norman/Development/android/projects/midnight-bboard-android`

**Principles:**
- Self-contained — everything needed to build, deploy, and run in one repo
- No dependency on `midnight-libraries` or internal paths
- Works out of the box: clone → build → run

**Structure (planned):**
```
midnight-bboard-android/
  contract/
    managed/bboard/contract/   # Compiled Compact contract artifacts
      index.js                 # IIFE contract JS
      index.d.ts               # Type definitions
    src/                       # Compact source (for reference)
  app/
    src/main/
      assets/runtime/
        bboard-contract-iife.js  # Contract JS bundled as asset
      kotlin/.../
        BBoardActivity.kt      # Simple UI: EditText + Post button
        BBoardViewModel.kt     # Uses MidnightContract.call()
  gradle/
    bboard-setup.gradle.kts    # Automated localnet setup task
  README.md                    # Step-by-step guide for new developers
```

**Automated test infrastructure:**
- Gradle `bboardSetup` task: localnet check → wallet → airdrop → dust → deploy → push config
- Runs as dependency of `connectedAndroidTest` — zero manual steps
- CI-compatible: just needs Docker + Android emulator

**Open questions for later:**
- Should the SDK be published as a Maven artifact? (`com.midnight:contract-sdk`)
- How do developers get proving keys? (Download on first run? Bundle in APK?)
- Should the example include a pre-deployed contract address on preview/preprod?
- How to handle wallet integration? (mn serve for dev, real wallet for production)

### SDK Distribution Strategy (Future)

| Component | Distribution | Status |
|-----------|-------------|--------|
| `MidnightConfig` / `MidnightContract` | Maven artifact from `core:compact-engine` | Planned |
| Contract IIFE JS | Bundled in example app assets | Done |
| Proving keys | Downloaded via `ProvingKeyManager` | Done |
| BLS params | Downloaded via `ProvingKeyManager` | Done |
| Rust FFI (`.so`) | Bundled in `core:crypto` AAR | Done |
| Example app | Separate open-source repo | Planned |
