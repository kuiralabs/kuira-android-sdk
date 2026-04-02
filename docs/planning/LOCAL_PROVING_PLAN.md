# Local ZK Proving on Mobile — Implementation Plan

**Date:** 2026-04-01
**Status:** Planning
**Priority:** Before Phase 5 (DApp Connector) — this is core infrastructure
**Estimate:** 18-26 hours
**ADR:** Pending (ADR-002)

---

## Why This Matters

Currently Kuira sends unproven transactions to a remote proof server (HTTP POST). This creates:
- **Privacy leak** — proof preimages leave the phone
- **Infrastructure dependency** — can't transact without proof server
- **Latency** — HTTP round-trip + Docker overhead adds seconds
- **Agent limitation** — agents can't prove offline

Local proving eliminates all four. The phone becomes a **self-contained ZK proving engine**.

---

## Feasibility — Proven

### The WASM prover already exists

The Midnight SDK ships `@midnight-ntwrk/zkir-v2` — a 2MB WASM module that proves transactions in-browser. The Lace wallet extension uses this for local proving. The source Rust crate (`midnight-zkir`) is in our midnight-libraries repo.

### The Rust source is available

```
midnight-libraries/
├── midnight-zk/           — Core ZK proving engine (halo2, BLS12-381)
│   ├── proofs/            — Halo2 proof system
│   ├── curves/            — BLS12-381 curve implementation
│   ├── circuits/          — Circuit definitions
│   └── zk_stdlib/         — Proving stdlib (prove/verify functions)
├── midnight-ledger/
│   ├── zkir/              — ZK intermediate representation + prove_unchecked()
│   └── zkir-wasm/         — WASM wrapper (thin layer over zkir)
```

### Same architecture as our existing FFI

We already compile Midnight Rust libraries for Android ARM64:
```
midnight-zswap  → kuira-crypto-ffi → JNI → Kotlin
midnight-ledger → kuira-crypto-ffi → JNI → Kotlin
```

Local proving follows the identical pattern:
```
midnight-zkir   → kuira-crypto-ffi → JNI → Kotlin
midnight-zk     → kuira-crypto-ffi → JNI → Kotlin
```

---

## Benchmark Data

**Source:** Adam Reynolds (Midnight team), Docker proof-server 8.0.3 on MacBook Pro

### Measured (Docker, single worker)

| k | Rows | Proving Time | Payload | SRS File |
|---|------|-------------|---------|----------|
| 10 | 719 | 0.17s | 15KB | 192KB |
| 11 | 1,454 | 0.60s | 2.7MB | 384KB |
| 12 | 2,158 | 0.78s | 5.0MB | 768KB |
| 13 | 4,270 | 1.3s | 9.5MB | 1.5MB |
| 14 | 8,494 | 2.4s | 18.6MB | 3.0MB |
| 15 | 16,942 | 4.2s | 36.7MB | 6.0MB |
| 16 | 33,134 | 7.7s | 73.0MB | 12MB |

### Key observations (from Adam)

- **Doubling rate:** Proving time and payload both ~2x per k increment
- **WASM hard limit:** k > 15 cannot be proved in-browser
- **Real-world range:** Most Compact contracts produce k=10-15
- **Minimum k:** k=10 due to 704 lines of zkir scaffolding
- **Wallet transactions (zswap/dust):** k=10-13

### What this means for mobile

**Wallet transactions are k=10-13 → 0.17s to 1.3s proving time on Docker.**

Native ARM64 on a modern phone (Snapdragon 8 Gen 3/4: 3.3+ GHz, 8 cores, NEON SIMD) should be **comparable or faster** than Docker on a MacBook — no virtualization overhead, no HTTP round-trip, direct memory access.

**Expected mobile proving times:**
- Dust spend (k~10): **<0.5s**
- Shielded transfer (k~13): **1-3s**
- Simple contract (k~15): **3-6s**

---

## Three-Tier Proving Strategy

Adam's recommendation from the Midnight team, adapted for Kuira:

### Tier 1: On-Phone Native Rust (k ≤ 15) — Default

```
Wallet transactions + simple contracts
Proving time: <1s to ~5s
Storage: ~24MB proving keys (one-time download, cached)
Network: NONE REQUIRED
Privacy: Proof preimage never leaves the phone
```

This covers **100% of wallet operations** and most dApp interactions.

### Tier 2: On-Phone Native Rust (k=16-17) — Optional

```
Complex contracts (rare)
Proving time: 8-15s
Storage: Additional ~36MB SRS files
Network: NONE REQUIRED
UX: Show progress bar
```

Viable on flagship phones. Optional download for users who need it.

### Tier 3: Remote Proof Server (k ≥ 18) — Fallback

```
Massive contracts (theoretical, "never going to happen" per Adam)
Payloads: 146MB+ (impractical for local)
Only option: dedicated proof server
```

Kuira falls back to HTTP proof server for these. In practice, this tier is almost never needed.

### User-facing setting

```
Proving Mode:
  ● Local (recommended) — Fastest, most private. Proves on your phone.
  ○ Remote — Uses proof server. Requires network.
  ○ Auto — Local for wallet txs, remote for complex contracts.
```

---

## Implementation Plan

### Implementation Approach: TDD Tracer Bullet

Start with the smallest provable unit (single dust preimage, k=10) to validate the
entire pipeline end-to-end. Then scale to full transaction proving.

```
Tracer bullet: prove 1 dust preimage locally → verify proof is valid
  → Scale to: prove full Transaction with multiple preimages
    → Integrate: replace ProofServerClient in pipeline
      → Polish: key management, UX, settings
```

### Step 1: Rust FFI — Transaction-Level Proving (5-7h)

**Cargo.toml additions:**
```toml
midnight-zkir = { path = "../../../../midnight/midnight-libraries/midnight-ledger/zkir" }
midnight-zk-stdlib = { path = "../../../../midnight/midnight-libraries/midnight-zk/zk_stdlib" }
tokio = { version = "1", features = ["rt"] }
```

**Verified:** `midnight-zkir` cross-compiles for `aarch64-linux-android` (tested April 1 2026).

**New FFI function — prove an entire Transaction (not individual preimages):**

```rust
/// Proves all ZK preimages in a Transaction locally.
///
/// Mirrors the proof server's /prove-tx endpoint:
/// 1. Deserializes the (Transaction, HashMap) tuple
/// 2. Creates a LocalProvingProvider with keys from the given directory
/// 3. Calls tx.prove(provider, cost_model) — iterates all preimages
/// 4. Serializes the proven Transaction
///
/// # Parameters
/// - `unproven_tx_hex`: Tagged-serialized (Transaction, HashMap) tuple (same format as proof server input)
/// - `keys_dir`: Path to directory containing cached proving keys
/// - `cost_model_hex`: Serialized CostModel (from indexer's ledger parameters)
///
/// # Returns
/// Tagged-serialized proven Transaction (hex), same format as proof server output.
/// Returns null on error.
#[no_mangle]
pub extern "C" fn zkir_prove_transaction_local(
    unproven_tx_hex: *const c_char,
    keys_dir: *const c_char,
    cost_model_hex: *const c_char,
) -> *const c_char;
```

**Key design decisions:**

1. **Transaction-level proving** (not preimage-level) — matches proof server behavior.
   The `Transaction.prove(provider, cost_model)` method iterates all preimages internally
   (zswap spend, output, sign, dust spend — 4+ proofs per shielded transfer).

2. **Directory-based key resolution** — Rust implements `Resolver` trait that reads keys
   from a local directory path (e.g., `/data/data/com.midnight.kuira/files/proving_keys/`).
   This avoids passing 20MB+ of key bytes across FFI boundaries.

   ```rust
   struct LocalFileResolver {
       keys_dir: PathBuf,
       tx_keys: HashMap<String, ProvingKeyMaterial>,  // from (Transaction, HashMap) input
       params_cache: HashMap<u8, ParamsProver>,
   }

   impl Resolver for LocalFileResolver {
       async fn resolve_key(&self, key: KeyLocation) -> io::Result<Option<ProvingKeyMaterial>> {
           // 1. Check transaction-specific keys first (for contract circuits)
           if let Some(km) = self.tx_keys.get(key.0.as_ref()) {
               return Ok(Some(km.clone()));
           }
           // 2. Fall back to local files for built-in keys:
           //    "midnight/zswap/spend" → keys_dir/zswap/spend.{prover,verifier,bzkir}
           //    "midnight/dust/spend"  → keys_dir/dust/spend.{prover,verifier,bzkir}
       }
   }

   impl ParamsProverProvider for LocalFileResolver {
       async fn get_params(&self, k: u8) -> io::Result<ParamsProver> {
           // Read keys_dir/bls_midnight_2p{k}
       }
   }
   ```

   This matches the proof server's `Resolver::new()` which chains:
   transaction-specific keys (HashMap) → dust keys (DustResolver) → public params.
   For wallet-only transactions the HashMap is empty; for future DApp Connector
   support, contract-specific keys will be passed through.

3. **Async bridge** — create a single-threaded tokio runtime per prove call (same pattern
   as proof server: `tokio::runtime::Builder::new_current_thread().build().block_on()`).

4. **CostModel from indexer** — the `cost_model_hex` parameter comes from the indexer's
   ledger parameters (already fetched by `SendViewModel` for fee calculation). This is NOT
   hardcoded. Note: the proof server's deprecated `/prove-tx` endpoint uses
   `INITIAL_TRANSACTION_COST_MODEL` which may differ from the current on-chain cost model.
   We use the current ledger params (same as the newer `/prove` endpoint approach).

5. **SplittableRng** — `OsRng` implements `SplittableRng` in the Midnight crates via a
   blanket impl. No issue.

### Step 2: Proving Key Management — Kotlin (3-4h)

**`ProvingKeyManager.kt`** — manages key download, caching, and version tracking.

```kotlin
class ProvingKeyManager(
    private val context: Context,
    private val networkConfig: NetworkConfig,
) {
    /** Directory where proving keys are cached. */
    val keysDir: File = File(context.filesDir, "proving_keys")

    /** Whether all required wallet keys are cached. */
    fun hasWalletKeys(): Boolean

    /** Download all wallet proving keys from S3. Shows progress. */
    suspend fun downloadWalletKeys(onProgress: (Float) -> Unit)

    /** Total size of cached keys. */
    fun cachedSizeBytes(): Long

    /** Delete all cached keys. */
    fun clearCache()
}
```

**Key file layout on device:**
```
/data/data/com.midnight.kuira/files/proving_keys/
├── version.txt                    (e.g., "9")
├── zswap/
│   ├── spend.prover               (10.5 MB)
│   ├── spend.verifier             (~2 KB)
│   ├── spend.bzkir                (~500 B)
│   ├── output.prover              (5.5 MB)
│   ├── output.verifier
│   ├── output.bzkir
│   ├── sign.prover                (2.7 MB)
│   ├── sign.verifier
│   └── sign.bzkir
├── dust/
│   ├── spend.prover               (2.1 MB)
│   ├── spend.verifier
│   └── spend.bzkir
└── bls_midnight_2p13              (1.5 MB)
```

**Total first download: ~24 MB.** Cached permanently, invalidated only on version change.

**S3 URLs (same as SDK — `WasmProver.makeDefaultKeyMaterialProvider()`):**
```
https://midnight-s3-fileshare-dev-eu-west-1.s3.eu-west-1.amazonaws.com/zswap/{ver}/spend.prover
https://midnight-s3-fileshare-dev-eu-west-1.s3.eu-west-1.amazonaws.com/dust/{ver}/spend.prover
https://midnight-s3-fileshare-dev-eu-west-1.s3.eu-west-1.amazonaws.com/bls_midnight_2p{k}
```

**Version detection:** Read from ledger parameters (already available via indexer).
Fallback: hardcode current version `9` with version check on app update.

### Step 3: JNI Bridge + Kotlin `LocalProver` (2-3h)

```kotlin
class LocalProver private constructor() {
    companion object {
        /**
         * Prove a transaction locally using cached proving keys.
         *
         * @param unprovenTxHex Same format sent to proof server
         * @param keysDir Path to proving keys directory
         * @param costModelHex Serialized CostModel from ledger params
         * @return Proven transaction hex (same format as proof server response)
         */
        fun proveTransaction(
            unprovenTxHex: String,
            keysDir: String,
            costModelHex: String,
        ): String?

        @JvmStatic private external fun nativeProveTransactionLocal(
            unprovenTxHex: String,
            keysDir: String,
            costModelHex: String,
        ): String?
    }
}
```

### Step 4: Pipeline Integration (3-4h)

Modify `TransactionSubmitter` to support both local and remote proving:

```kotlin
// In submitPrebuiltTransaction or submitWithFees:
val provenTxHex = if (provingKeyManager.hasWalletKeys()) {
    // LOCAL proving — no network needed
    val costModelHex = indexerClient.getLedgerParametersHex()
    LocalProver.proveTransaction(unprovenTxHex, provingKeyManager.keysDir.path, costModelHex)
        ?: throw ProofComputationException("Local proving failed")
} else {
    // REMOTE proving — fallback to proof server
    proofServerClient.proveTransaction(unprovenTxHex)
}
// seal → submit pipeline is unchanged
```

Also update `SendViewModel.sendShieldedTransaction()` and `submitWithFees()` to use
the same local/remote routing.

### Step 5: Android Build + Integration Testing (3-5h)

- Update `build-android.sh` to compile `midnight-zkir` + `midnight-zk` for all Android ABIs
- Configure rayon thread pool for mobile (limit to 4 threads on <8 core devices)
- Integration test on localnet:
  - Download proving keys from S3
  - Build unproven shielded transfer
  - Prove locally
  - Seal
  - Submit to node
  - Verify finalization
- Benchmark: local proving time vs proof server time
- Memory profiling: peak RAM during proving

### Step 6: UX — Key Download + Proving Mode (2-3h)

- First-launch prompt: "Download proving keys (24MB) for faster, more private transactions?"
- Settings screen: Proving Mode toggle (Local / Remote / Auto)
- Progress indicator during proof generation ("Generating ZK proof...")
- Transaction detail: show proving mode used and time taken

---

## Dependency Chain

```
Step 1 (Rust FFI — LocalFileResolver + prove)
  → Step 2 (Key Management) — can start in parallel
  → Step 3 (JNI + Kotlin LocalProver)
    → Step 4 (Pipeline Integration)
      → Step 5 (Build + Integration Test)
        → Step 6 (UX)
```

Steps 1 and 2 can be done in parallel. Total critical path: ~18-24 hours.

---

## Gaps Identified During Review (April 1 2026)

| Gap | Resolution |
|---|---|
| Plan proposed per-preimage FFI, but pipeline sends full Transaction | Changed to `zkir_prove_transaction_local` — proves entire Transaction |
| No design for key delivery across FFI | Directory-based `LocalFileResolver` — Rust reads files, no FFI byte transfer |
| Missing CostModel parameter | Added `cost_model_hex` from indexer's ledger parameters |
| `SplittableRng` trait concern | Verified: `OsRng` has blanket impl in Midnight crates |
| Async `prove()` bridge to FFI | Use `tokio::runtime::Builder::new_current_thread().block_on()` (same as proof server) |
| Transaction.prove() returns new Transaction type | Proven tx serialized with `tagged_serialize` — same format as proof server response |
| Version sync for proving keys | Read from ledger parameters, fallback to hardcoded with version check |
| Resolver must handle tx-specific keys (HashMap from input tuple) | LocalFileResolver checks tx_keys HashMap first, then local files |
| Cost model source differs from deprecated /prove-tx | Use current ledger params from indexer (not INITIAL_TRANSACTION_COST_MODEL) |

## Risks (Updated)

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| midnight-zkir doesn't compile for ARM64 | **Eliminated** | — | Verified April 1 2026 |
| rayon thread pool issues on Android | Medium | Low | Configure max threads, test on device |
| Proving slower than expected on phone | Low | Low | Still have proof server fallback |
| Proving key format changes between versions | Medium | Low | Version-aware key management |
| File I/O latency reading keys from storage | Low | Low | Keys are memory-mapped or read once and cached |
| Memory pressure during proving (peak ~30MB) | Low | Low | Modern phones have 6-12GB RAM |

---

## What This Unlocks

1. **Fully offline shielded transactions** — no network needed
2. **True privacy** — proof preimages never leave the phone
3. **Agent autonomy** — agents prove locally without server dependency
4. **Reduced infrastructure** — no proof server needed for wallet operations
5. **Faster transactions** — no HTTP round-trip overhead
6. **Competitive moat** — no other mobile wallet can generate ZK proofs locally

---

## Phase Ordering Update

```
CURRENT:
  Phase 3 (Shielded Tx) ✅ → Phase 5 (DApp Connector) → Phase 6 (UI)

PROPOSED:
  Phase 3 (Shielded Tx) ✅ → Phase 4C (Local Proving) → Phase 5 (DApp Connector) → Phase 6 (UI)
```

Local proving is infrastructure that benefits everything downstream:
- DApp Connector uses local proving for contract interactions
- Agent Runtime uses local proving for autonomous transactions
- Game SDK uses local proving for ZK leaderboard proofs

---

## References

- **Adam Reynolds benchmarks:** compact-zkir-lint repo (GitHub: adamreynolds-io)
- **WASM prover source:** `midnight-wallet/packages/prover-client/src/effect/WasmProver.ts`
- **Rust proving engine:** `midnight-ledger/zkir/src/ir.rs` → `prove_unchecked()`
- **WASM proving demo:** `midnight-ledger/wasm-proving-demos/`
- **Key material S3:** `https://midnight-s3-fileshare-dev-eu-west-1.s3.eu-west-1.amazonaws.com/`
- **ADR-001:** Composable FFI primitives (established pattern for this integration)
- **Kuira Vision V1:** `docs/planning/KUIRA_VISION_V1.md`
