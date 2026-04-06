# 6K: Developer-Facing Contract API

**Date:** 2026-04-06
**Status:** Planning Complete, Implementation Starting
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

### Step 1: ArgConverter + unit tests
- [ ] Pure logic, zero dependencies
- [ ] String escaping (single quotes, backslashes, newlines)
- [ ] Numbers (Int, Long, BigInteger)
- [ ] ByteArray --> `new Uint8Array([...])`
- [ ] Boolean
- [ ] Map --> JS object literal
- [ ] List --> JS array
- [ ] null
- [ ] Injection attempt tests (strings with `'`, `\`, etc.)

### Step 2: Result types
- [ ] `TransactionReceipt` (txHash, status, timings)
- [ ] `PreparedTransaction` (provenTxHex, metadata)
- [ ] `ContractCallStage` sealed class (FetchingState, Executing, Proving, Balancing, Submitting)
- [ ] `ContractCallException` sealed hierarchy (StateFetchFailed, CircuitExecutionFailed, ProvingFailed, BalancingFailed, SubmissionFailed, InvalidArgument)
- [ ] `PipelineTimings` data class

### Step 3: MidnightConfig
- [ ] Builder pattern with validation
- [ ] `localDev(context)` convenience factory
- [ ] Connection pool for DAppConnectorClient
- [ ] Owns IndexerClient, ProofProvider references
- [ ] `close()` for cleanup
- [ ] `submit(prepared)` for deferred submission

### Step 4: ContractPipeline
- [ ] Internal orchestration class
- [ ] Fetch state + ledger params
- [ ] Execute circuit via CircuitExecutor
- [ ] Prove via ProofProvider
- [ ] Balance + submit via DAppConnectorClient
- [ ] Progress callback invocation
- [ ] Timing measurement
- [ ] Error wrapping into ContractCallException hierarchy

### Step 5: MidnightContract
- [ ] DSL builder (`create(config) { ... }`)
- [ ] `call(circuitName, vararg args)` --> TransactionReceipt
- [ ] `call(circuitName, vararg args, onProgress)` --> TransactionReceipt
- [ ] `prepare(circuitName, vararg args)` --> PreparedTransaction
- [ ] Contract JS loaded from InputStream (not raw String)
- [ ] Witness registration via DSL
- [ ] Private state as Map

### Step 6: Integration test
- [ ] BBoard e2e using `MidnightContract.call("post", "Hello!")`
- [ ] Verify existing offline tests still pass
- [ ] Offline mode test with `prepare()` + `submit()`

---

## Verification Checklist

- [ ] `ArgConverter` unit tests pass (string escaping, injection, all types)
- [ ] All existing CircuitExecutor tests still pass (no modifications)
- [ ] All existing BBoard offline tests still pass
- [ ] New `MidnightContract.call()` e2e test submits to localnet
- [ ] Code review passes standards check

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
