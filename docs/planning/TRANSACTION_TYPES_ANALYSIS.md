# Midnight Transaction Types — Complete Analysis

**Date:** 2026-03-24
**Sources:**
- `midnight-libraries/midnight-dapp-connector-api/src/api.ts` — ConnectedAPI spec (v4.0.1)
- `midnight-libraries/midnight-wallet/packages/facade/src/index.ts` — WalletFacade implementation
- `midnight-wallet-cli/src/lib/dapp-connector.ts` — CLI's working ConnectedAPI server
- `midnight-wallet-cli/packages/connector/src/` — Wallet Connector client library
- `midnight-wallet-cli/src/lib/tx-serde.ts` — Transaction serialization

---

## Complete Transaction Taxonomy

### 1. Token Transfer (`transferTransaction` / `makeTransfer`)

**What:** Move NIGHT or custom tokens between addresses.
**Supports:** Both shielded AND unshielded outputs in a single transaction.

| Aspect | Detail |
|--------|--------|
| Wallet-initiated | `facade.transferTransaction(outputs, secrets, { ttl, payFees })` |
| DApp-initiated | `connectedAPI.makeTransfer(desiredOutputs[], { payFees })` |
| Input type | `DesiredOutput { kind: 'shielded'\|'unshielded', type: TokenType, value: bigint, recipient: string }` |
| Output | `UnprovenTransactionRecipe` → sign → prove → bind → submit |
| Kuira status | **Unshielded only** (Phase 2). Shielded missing (Phase 3). |

**CLI implementation** (`dapp-connector.ts:432-458`):
- Parses `DesiredOutput[]` → groups by kind → converts to `CombinedTokenTransfer[]`
- Calls `facade.transferTransaction()` with dust retry logic
- Signs recipe → proves (with timeout) → serializes to hex
- Tracks pending tx per connection (auto-reverts if DApp never submits)

### 2. Contract Call — Balance Unsealed (`balanceUnsealedTransaction` / `balanceUnboundTransaction`)

**What:** DApp creates a contract transaction (with ZK proofs), wallet adds fees and balances it.
**This is the primary contract interaction method.**

| Aspect | Detail |
|--------|--------|
| Who creates | **DApp** — executes Compact circuit, generates ZK proofs |
| Who balances | **Wallet** — adds NIGHT inputs/outputs for fees, adds DUST fee payment |
| Tx type received | `Transaction<SignatureEnabled, Proof, PreBinding>` (unsealed — proven but not bound) |
| Tx type returned | `Transaction<SignatureEnabled, Proof, Binding>` (sealed — proven and bound) |
| Kuira status | **Missing** — needs Rust FFI for tx deserialization/serialization |

**CLI implementation** (`dapp-connector.ts:512-531`):
```
1. Receive hex string from DApp
2. Deserialize: Transaction.deserialize('signature', 'proof', 'pre-binding', bytes)
3. facade.balanceUnboundTransaction(unsealedTx, secrets, { ttl })
4. Sign recipe → prove → serialize back to hex
5. Track pending tx (auto-revert on abandon)
6. Return hex to DApp
```

### 3. Contract Call — Balance Sealed (`balanceSealedTransaction` / `balanceFinalizedTransaction`)

**What:** DApp sends an already-sealed transaction, wallet balances it in a **separate intent**.

| Aspect | Detail |
|--------|--------|
| Who creates | **DApp** — already proven AND bound |
| Who balances | **Wallet** — creates a separate balancing transaction, merges |
| Tx type received | `Transaction<SignatureEnabled, Proof, Binding>` (sealed) |
| When to use | When DApp wants wallet to balance in separate intent, or when tx doesn't have fallible sections |
| Kuira status | **Missing** |

**CLI implementation** (`dapp-connector.ts:534-553`): Same pattern as unsealed but uses `facade.balanceFinalizedTransaction()` and `deserializeSealed()`.

### 4. Swap / Intent (`initSwap` / `makeIntent`)

**What:** Create an unbalanced intent with desired inputs AND outputs — primary use case is atomic swaps.

| Aspect | Detail |
|--------|--------|
| Wallet-initiated | `facade.initSwap(desiredInputs, desiredOutputs, secrets, { ttl, payFees })` |
| DApp-initiated | `connectedAPI.makeIntent(desiredInputs[], desiredOutputs[], { intentId, payFees })` |
| Inputs | `DesiredInput { kind: 'shielded'\|'unshielded', type: TokenType, value: bigint }` |
| Outputs | `DesiredOutput[]` (same as transfers) |
| Intent ID | `number \| 'random'` — controls transaction merging behavior |
| Kuira status | **Missing** — not in any phase |

**CLI implementation** (`dapp-connector.ts:556-591`):
- Converts `DesiredInput[]` → `CombinedSwapInputs { shielded?: Record, unshielded?: Record }`
- Converts `DesiredOutput[]` → `CombinedTokenTransfer[]` (same as transfers)
- Calls `facade.initSwap()` with dust retry
- Signs → proves → serializes → tracks pending

### 5. Dust Registration (`registerNightUtxosForDustGeneration`)

**What:** Register NIGHT UTXOs for dust generation.

| Aspect | Detail |
|--------|--------|
| Wallet-only | Not exposed via ConnectedAPI (wallet manages internally) |
| Kuira status | **Done** (Phase 2F.1) — via Rust FFI |

### 6. Dust Deregistration (`deregisterFromDustGeneration`)

**What:** Stop generating dust from specific NIGHT UTXOs.

| Aspect | Detail |
|--------|--------|
| Wallet-only | Not exposed via ConnectedAPI |
| Kuira status | **Missing** — no phase planned |

### 7. Transaction Submission (`submitTransaction`)

**What:** Relay a pre-built, sealed transaction to the network.

| Aspect | Detail |
|--------|--------|
| Who builds | **DApp** (or wallet via makeTransfer/balanceUnsealed) |
| Wallet role | Pure relayer — deserializes, submits, tracks status |
| Kuira status | **Partial** — we submit our own txs, but not arbitrary DApp-built txs |

**CLI implementation** (`dapp-connector.ts:460-509`):
- Receives hex string, prompts approval
- Deserializes sealed tx, calls `facade.submitTransaction()`
- On failure: reverts pending tx to release locked dust coins
- Untracks pending tx on success

### 8. Data Signing (`signData`)

**What:** Sign arbitrary data with the wallet's unshielded key.

| Aspect | Detail |
|--------|--------|
| Encodings | `'hex' \| 'base64' \| 'text'` (text = UTF-8 normalized) |
| Key type | Only `'unshielded'` supported currently |
| Returns | `{ data, signature, verifyingKey }` |
| Kuira status | **Missing** |

**CLI implementation** (`dapp-connector.ts:593-634`):
- Decodes data based on encoding
- Calls `keystore.signData(payload)` (same Schnorr signing we already have)
- Returns signature + verifying key

### 9. Proving Provider (`getProvingProvider`)

**What:** Delegate ZK proving to the wallet (instead of DApp doing it).

| Aspect | Detail |
|--------|--------|
| Input | `KeyMaterialProvider { getZKIR, getProverKey, getVerifierKey }` |
| Returns | `ProvingProvider { check, prove }` + `proverServerUri` |
| CLI approach | Returns proof server URI — actual bidirectional proving not yet implemented |
| Kuira status | **Missing** |

### 10. Fee Estimation (`calculateTransactionFee` / `estimateTransactionFee`)

**What:** Estimate DUST fees before committing to a transaction.

| Aspect | Detail |
|--------|--------|
| Not in ConnectedAPI | Internal wallet method only (facade-level) |
| `calculateTransactionFee` | Fee for given tx only (no balancing overhead) |
| `estimateTransactionFee` | Total fee including the balancing transaction |
| Kuira status | **Missing** |

---

## Transaction Serialization Types

Three distinct serialization formats, identified by type markers:

| Name | Markers | Description | When Used |
|------|---------|-------------|-----------|
| **Unsealed** | `('signature', 'proof', 'pre-binding')` | Proven but not cryptographically bound | DApp sends to `balanceUnsealedTransaction` |
| **Sealed** | `('signature', 'proof', 'binding')` | Proven and bound — ready for submission | DApp sends to `submitTransaction` or `balanceSealedTransaction` |
| **Unproven** | `('signature', 'pre-proof', 'pre-binding')` | Not yet proven — needs proof server | Internal wallet use only |

The CLI's `tx-serde.ts` implements these as:
```typescript
deserializeUnsealed(hex) → Transaction<SignatureEnabled, Proof, PreBinding>
deserializeSealed(hex)   → Transaction<SignatureEnabled, Proof, Binding>
deserializeUnproven(hex) → Transaction<SignatureEnabled, PreProof, PreBinding>
serializeTx(tx)          → hex string (works with any Transaction)
```

**For Kuira:** This needs to be in the Rust FFI layer — deserialize hex bytes into the appropriate typed Transaction, then call the relevant balancing/signing/proving methods.

---

## CLI Architecture Patterns to Adapt for Kuira

### 1. Pending Transaction Tracking (per-connection)

The CLI tracks balanced-but-not-yet-submitted transactions per DApp connection:
- When wallet balances a tx → locks dust coins → tracks tx
- If DApp submits → untrack, coins are spent
- If DApp disconnects or abandons → auto-revert, release dust coins
- Abandon timeout: configurable (prevents dust coin lockup)

**Kuira equivalent:** Android Service tracks pending txs per connected DApp. Biometric approval replaces terminal Y/n.

### 2. Dust Retry Logic

The CLI retries facade calls that fail with "No dust tokens":
- Waits for dust to appear in synced state
- Up to N attempts with configurable delay
- Observes `facade.state()` for dust availability

**Kuira equivalent:** Same pattern — observe DustLocalState for dust availability before retrying.

### 3. Phase Tracking with Progress Notifications

The CLI sends JSON-RPC notifications to DApps during long operations:
- `approval:pending` / `approval:resolved` — approval flow
- `progress` — phase updates (building, signing, proving, submitting)

**Kuira equivalent:** Android notifications or callback interface for DApp SDK.

### 4. Transaction Inspection

The CLI's `tx-inspect.ts` extracts human-readable details from serialized transactions for the approval UI — showing amounts, recipients, contract addresses.

**Kuira equivalent:** Parse tx hex in Rust FFI → return JSON with human-readable details for the approval screen.

---

## Revised Phase Plan for Kuira

### What's Missing (prioritized)

| Priority | Capability | Estimated Hours | Dependency |
|----------|-----------|----------------|------------|
| **P0** | Phase 4B-Shielded (shielded balance tracking) | 8-12h | None |
| **P0** | Phase 3 (shielded transfers) | 20-25h | Phase 4B-Shielded |
| **P1** | Transaction Serialization FFI (deserialize unsealed/sealed/unproven hex) | 8-10h | None |
| **P1** | DApp Connector Core (ConnectedAPI server — read methods + makeTransfer) | 10-12h | None |
| **P1** | Transaction Balancing Service (balanceUnsealed + balanceSealed) | 8-10h | Tx Serde FFI |
| **P2** | Swap/Intent Support (makeIntent / initSwap) | 6-8h | Balancing Service |
| **P2** | Data Signing (signData) | 2-3h | None |
| **P2** | Pending Tx Tracking + Auto-Revert | 4-6h | Balancing Service |
| **P2** | Fee Estimation | 3-4h | Dust integration |
| **P3** | Proving Provider delegation | 4-6h | Proof server |
| **P3** | Dust Deregistration | 3-4h | Dust module |
| **P3** | Tx Inspection (human-readable approval details) | 4-6h | Tx Serde FFI |

### Proposed Phase Restructure

**Phase 5A: Transaction Serialization FFI** (8-10h)
- Rust FFI functions: `deserialize_unsealed(hex)`, `deserialize_sealed(hex)`, `serialize_tx(tx)`
- JNI bridge for these functions
- Kotlin wrapper with type safety
- This is the foundation for ALL connector write methods

**Phase 5B: DApp Connector — Read Methods + Basic Writes** (12-15h)
- Android Service with JSON-RPC over local WebSocket (same protocol as CLI)
- All 9 read methods (balances, addresses, config, history, status)
- `makeTransfer` (builds on existing Phase 2 transfer code)
- `submitTransaction` (relay pre-built txs)
- Biometric approval flow for write methods
- Connection management (connect/disconnect)

**Phase 5C: Contract Transaction Balancing** (10-14h)
- `balanceUnsealedTransaction` — deserialize DApp tx, call facade equivalent, re-serialize
- `balanceSealedTransaction` — same for sealed txs
- Pending tx tracking per connection (with auto-revert)
- Dust retry logic (same pattern as CLI)
- This is where contract interactions actually work

**Phase 5D: Swaps, Signing, and Utilities** (8-12h)
- `makeIntent` / `initSwap` — swap/intent creation
- `signData` — arbitrary data signing
- `getProvingProvider` — proof server URI delegation
- `hintUsage` — permission hints
- Fee estimation methods

**Phase 5E: DApp Approval UI** (6-8h)
- Transaction inspection (human-readable details from tx hex)
- Biometric confirmation dialogs
- Connected DApps management screen
- Transaction status notifications

**Total revised Phase 5: 44-59h** (vs original 25-35h estimate)

---

## Ledger Version Note

| Consumer | Ledger Version | Notes |
|----------|---------------|-------|
| Official wallet facade | `@midnight-ntwrk/ledger-v8` | Latest SDK |
| CLI wallet (`mn`) | `@midnight-ntwrk/ledger-v7` | One version behind |
| Kuira (Rust FFI) | Local path deps (`midnight-ledger/ledger/`) | Same Rust crate, no version pinning |

The Transaction serialization format and type markers (`'signature'`, `'proof'`, `'binding'`, etc.) are stable across v7/v8. Kuira links directly to the Rust crate source, so it gets whatever version is in the local midnight-libraries checkout. This needs verification when implementing Phase 5A.

## ProvingProvider Note

The official ConnectedAPI returns `Promise<ProvingProvider>` (with `check` and `prove` methods only). Our CLI extends this to `WalletProvingProvider` which adds `proverServerUri` — this is a **CLI-specific extension**, not part of the official spec. Kuira should implement the official interface but can extend it similarly for the proof server URI shortcut.

## Key Architecture Decision: Rust FFI vs Kotlin

The CLI uses the TypeScript SDK's `Transaction.deserialize()` directly. Kuira can't — we need to decide:

**Option A: Rust FFI for all tx operations**
- Add `deserialize_unsealed`, `deserialize_sealed`, `balance_transaction`, `serialize_tx` to Rust FFI
- Pro: Uses battle-tested midnight-ledger Rust code directly (we already have `midnight-ledger` as a Cargo dep)
- Pro: `Transaction::deserialize()` is a Rust-native function — calling it from Rust is the most direct path
- Con: Large FFI surface, complex JNI bridge

**Option B: WebView bridge (hybrid)**
- Run the Midnight JS SDK in a headless WebView
- DApps communicate via WebView JS interface
- Pro: 100% compatible with existing DApps, minimal new code
- Con: WebView overhead, harder to test, two runtimes

**Option C: Direct ledger WASM**
- Load `midnight-ledger-wasm` in Android via wasm runtime
- Pro: Same code as browser wallets
- Con: WASM runtime integration complexity

**Recommendation:** Start with **Option A** (Rust FFI) for Phase 5A-C since we already have the FFI infrastructure and the Rust midnight-ledger crate as a dependency. Evaluate Option B for Phase 5D+ if the FFI surface becomes too large.
