# Local ZK Proving on Mobile — Implementation Plan

**Date:** 2026-04-01
**Status:** Planning
**Priority:** Before Phase 5 (DApp Connector) — this is core infrastructure
**Estimate:** 15-25 hours
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

### Step 1: Add midnight-zkir to kuira-crypto-ffi (4-6h)

**Cargo.toml changes:**
```toml
midnight-zkir = { path = "../../../../midnight/midnight-libraries/midnight-ledger/zkir" }
midnight-zk-stdlib = { path = "../../../../midnight/midnight-libraries/midnight-zk/zk_stdlib" }
```

**New FFI function:**
```rust
/// Proves a serialized ProofPreimage locally using the zkir engine.
///
/// Replaces the HTTP call to the proof server for k ≤ 15 circuits.
///
/// # Parameters
/// - `preimage_hex`: Tagged-serialized ProofPreimage (from the unproven transaction)
/// - `prover_key`: Proving key bytes (from cached file)
/// - `verifier_key`: Verifier key bytes (from cached file)
/// - `ir`: ZKIR bytes (from cached file)
/// - `params`: BLS params bytes (from cached file)
///
/// # Returns
/// Tagged-serialized Proof bytes (hex), or null on error.
#[no_mangle]
pub extern "C" fn zkir_prove_local(
    preimage_hex: *const c_char,
    prover_key: *const u8, prover_key_len: usize,
    verifier_key: *const u8, verifier_key_len: usize,
    ir: *const u8, ir_len: usize,
    params: *const u8, params_len: usize,
) -> *const c_char;
```

**Key challenge:** The `prove_unchecked` function is `async`. Need to bridge to sync FFI via `tokio::runtime::Runtime::block_on()` or a dedicated proving thread.

### Step 2: Proving Key Management (3-4h)

**Kotlin: `ProvingKeyManager`**
- Download keys from S3 on first use (same URLs as SDK)
- Cache in app internal storage (~24MB)
- Version-aware (keys change with ledger versions)
- Keys needed for wallet operations:
  - `zswap/9/spend.prover` (10.5 MB)
  - `zswap/9/output.prover` (5.5 MB)
  - `zswap/9/sign.prover` (2.7 MB)
  - `dust/9/spend.prover` (2.1 MB)
  - `bls_midnight_2p13` (1.5 MB)
  - Corresponding `.verifier` and `.bzkir` files (~small)

### Step 3: JNI Bridge + Kotlin Wrapper (2-3h)

Same pattern as all our other JNI bridges. The `LocalProver` Kotlin class wraps the FFI:

```kotlin
class LocalProver {
    companion object {
        /** Prove a single preimage locally. */
        fun prove(preimageHex: String, keyLocation: String): String?

        /** Prove an entire unproven transaction locally. */
        fun proveTransaction(unprovenTxHex: String): String?

        /** Check if proving keys are cached. */
        fun hasKeys(): Boolean

        /** Download and cache proving keys. */
        suspend fun downloadKeys(onProgress: (Float) -> Unit)
    }
}
```

### Step 4: Integration with Transaction Pipeline (3-4h)

Replace `ProofServerClient.proveTransaction()` with `LocalProver.proveTransaction()`:

```kotlin
// Before (remote):
val provenTxHex = proofServerClient.proveTransaction(unprovenTxHex)

// After (local):
val provenTxHex = if (localProver.hasKeys()) {
    localProver.proveTransaction(unprovenTxHex)
} else {
    proofServerClient.proveTransaction(unprovenTxHex)  // fallback
}
```

The rest of the pipeline (seal → submit) stays identical.

### Step 5: Android Build + Testing (3-5h)

- Cross-compile `midnight-zkir` + `midnight-zk` for ARM64
- Resolve any compilation issues (rayon thread pool config, etc.)
- Benchmark on real device vs proof server
- Integration test: prove → seal → submit → finalize on localnet

### Step 6: UX — Proving Key Download + Settings (2-3h)

- First-launch prompt: "Download proving keys (24MB) for offline transactions?"
- Settings: Proving Mode (Local / Remote / Auto)
- Progress indicator during proof generation
- Show proving time in transaction detail

---

## Dependency Chain

```
Step 1 (Rust FFI)
  → Step 2 (Key Management) — can be parallel
  → Step 3 (JNI + Kotlin)
    → Step 4 (Pipeline Integration)
      → Step 5 (Build + Test)
        → Step 6 (UX)
```

Steps 1 and 2 can be done in parallel. Total critical path: ~15-20 hours.

---

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| midnight-zkir doesn't compile for ARM64 | Low | High | Pure Rust, no platform deps. Test early. |
| rayon thread pool issues on Android | Medium | Low | Configure max threads, test on device |
| async prove() hard to bridge to FFI | Low | Medium | Use block_on() or background thread |
| Proving slower than expected on phone | Low | Low | Still have proof server fallback |
| Proving key format changes between versions | Medium | Low | Version-aware key management |

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
