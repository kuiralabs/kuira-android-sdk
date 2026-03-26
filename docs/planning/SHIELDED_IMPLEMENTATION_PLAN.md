# Shielded Implementation Plan for Kuira

**Date:** 2026-03-25
**Status:** Reviewed — Fact-checked, all questions resolved. Ready for implementation.
**Estimate:** Phase 4B-Shielded (~12h) + Phase 3 (~15-20h) = ~27-32h total
**Prerequisites:** Ledger v8 upgrade complete, 64-byte seed fix applied, dust registration working
**Reference Implementation:** CLI wallet `shielded` branch (`midnight-wallet-cli`)

---

## Table of Contents

1. [Architecture](#architecture)
2. [Protocol Facts](#protocol-facts)
3. [Phase 4B-Shielded: Balance Tracking](#phase-4b-shielded-balance-tracking)
4. [Phase 3: Shielded Transactions](#phase-3-shielded-transactions)
5. [Implementation Order](#implementation-order)
6. [Testing Strategy](#testing-strategy)
7. [Open Questions](#open-questions)
8. [Reference Files](#reference-files)

---

## Architecture

### How the CLI Does It (TypeScript SDK)

The CLI uses `WalletFacade` which internally manages:
- `ShieldedWalletAPI` — syncs zswap events, tracks shielded coins
- `ZswapLocalState` (WASM) — Rust state exposed to JS via wasm-bindgen
- `ZswapSecretKeys` — derived from BIP-32 seed at `m/44'/2400'/0'/3/0`

For balance: `facade.state()` emits `FacadeState` with `shielded.balances` (Map of token → amount).
For transfers: `facade.transferTransaction([{ type: 'shielded', outputs: [...] }], secrets, { ttl })`.

### How Kuira Will Do It (Rust FFI)

Same Rust crate (`midnight-zswap`), but called directly via JNI instead of through WASM:

```
Kotlin (Android)
  └── ZswapLocalState.kt (wrapper)
        └── JNI Bridge (kuira_crypto_jni.c)
              └── zswap_ffi.rs (new FFI module)
                    └── zswap::local::State<InMemoryDB> (native Rust)
```

This mirrors the existing pattern for `DustLocalState`:
```
DustLocalState.kt → JNI → dust_ffi.rs → dust::local::DustLocalState<InMemoryDB>
```

### Key Rust Types

From `midnight-ledger/zswap/src/local.rs`:

```rust
pub struct State<D: DB> {
    // Internal fields — opaque to Kotlin
}

impl<D: DB> State<D> {
    pub fn new() -> Self;
    pub fn apply(&self, secret_keys: &SecretKeys, tx: &Offer<P, D>) -> State<D>;
    pub fn apply_failed(&self, tx: &Offer<P, D>) -> State<D>;
    pub fn revert_transaction(&self, tx: &Offer<P, D>) -> State<D>;
    pub fn spend(&self, ...) -> (Input<D>, State<D>);
    pub fn spend_from_output(&self, ...) -> (Input<D>, State<D>);
    pub fn watch_for(&self, coin_public_key, coin_info) -> State<D>;
}
```

From `midnight-ledger/ledger-wasm/src/zswap_state.rs` (WASM bindings — our reference):

| WASM Method | Kuira FFI Equivalent | Purpose |
|---|---|---|
| `new ZswapLocalState()` | `create_zswap_local_state()` | Create empty state |
| `replayEvents(secretKeys, events)` | `zswap_replay_events(state, seed, events_hex)` | Process blockchain events |
| `coins` (getter → Set) | `zswap_get_balances(state)` → JSON | Get coins/balances |
| `serialize()` | `zswap_serialize(state)` | Persist state |
| `deserialize(bytes)` | `zswap_deserialize(bytes)` | Restore state |
| `spend(...)` | `zswap_spend(state, ...)` | Create spend input (Phase 3) |
| `revertTransaction(tx)` | `zswap_revert_transaction(state, tx)` | Undo pending tx (Phase 3) |

### SecretKeys Derivation

ZswapSecretKeys are derived from the BIP-32 key at `m/44'/2400'/0'/3/0` (Role 3 = Zswap):

```
BIP-39 mnemonic → 64-byte seed → HDWallet → m/44'/2400'/0'/3/0 → 32-byte private key
  → SecretKeys::from(Seed::from(private_key))  // No from_seed() — use From<Seed> trait
    → coin_secret_key, encryption_secret_key
    → coin_public_key, encryption_public_key
```

We already derive these in `ShieldedKeyDeriver.kt` (Phase 1B). For the FFI, we need `SecretKeys` (the struct), not just the public keys.

---

## Protocol Facts

Verified from CLI implementation and testing:

1. **No self-shielding** — Cannot move own NIGHT from unshielded → shielded
2. **Shielded coins come from receiving** — Another wallet sends you shielded NIGHT
3. **Genesis wallet has 250M shielded NIGHT** on localnet (seed `0x01`)
4. **`transferTransaction` with `type: 'shielded'`** draws from sender's shielded balance
5. **Shielded address available from keys** — No sync needed, available immediately from `ZswapSecretKeys` public keys
6. **Shielded address prefix:** `mn_shield-addr_<network>1...`
7. **`initSwap` is NOT for self-shielding** — It's for two-party DEX swaps (rejected on localnet when used for self-shielding)
8. **Events are different from unshielded** — Zswap events use `zswapLedgerEvents` subscription, not `unshieldedTransactions`
9. **Events are NOT filtered by address** — ALL zswap events are returned; the Rust state decrypts only what belongs to the wallet's keys

---

## Phase 4B-Shielded: Balance Tracking

### Step 1: Rust FFI — ZswapLocalState (4-5h)

**New file:** `kuira-crypto-ffi/src/zswap_ffi.rs`

**FFI Functions:**

```rust
// ── Lifecycle ──
#[no_mangle]
pub extern "C" fn create_zswap_local_state() -> *mut ZswapLocalState<InMemoryDB>;

#[no_mangle]
pub extern "C" fn free_zswap_local_state(ptr: *mut ZswapLocalState<InMemoryDB>);

// ── Event Replay ──
// Takes: state pointer, 32-byte zswap seed, hex-encoded events from indexer
// Returns: new state pointer (old state remains valid)
#[no_mangle]
pub extern "C" fn zswap_replay_events(
    state_ptr: *const ZswapLocalState<InMemoryDB>,
    seed_ptr: *const u8,
    seed_len: usize,
    events_hex: *const c_char,
) -> *mut ZswapLocalState<InMemoryDB>;

// ── Balance Queries ──
// Returns JSON: {"0000...0000": "5000000", ...} (token_type_hex → balance_string)
#[no_mangle]
pub extern "C" fn zswap_get_balances(
    state_ptr: *const ZswapLocalState<InMemoryDB>,
) -> *const c_char;

// Number of coins in state
#[no_mangle]
pub extern "C" fn zswap_get_coin_count(
    state_ptr: *const ZswapLocalState<InMemoryDB>,
) -> i32;

// ── Persistence ──
#[no_mangle]
pub extern "C" fn zswap_serialize(
    state_ptr: *const ZswapLocalState<InMemoryDB>,
) -> *const c_char;  // hex-encoded bytes

#[no_mangle]
pub extern "C" fn zswap_deserialize(
    hex_ptr: *const c_char,
) -> *mut ZswapLocalState<InMemoryDB>;

// ── Memory ──
#[no_mangle]
pub extern "C" fn free_zswap_string(ptr: *mut c_char);
```

**Key Implementation Detail — SecretKeys:**

The WASM `replayEvents` takes `ZswapSecretKeys`. Internally this is `zswap::keys::SecretKeys` which contains `coin_secret_key` and `encryption_secret_key`. Derivation from the 32-byte zswap seed:

```rust
use midnight_zswap::keys::{Seed, SecretKeys};

fn derive_secret_keys(seed: &[u8; 32]) -> SecretKeys {
    // No from_seed() method — use From<Seed> trait chain
    let zswap_seed = Seed::from(*seed);
    SecretKeys::from(zswap_seed)
}
```

This is the same derivation that `ShieldedKeyDeriver` already does for public keys — we just keep the secret keys too.

**Key Implementation Detail — Event Format (VERIFIED):**

Zswap and dust events use the **same** unified `Event<D>` structure with `event[v9]` tag. The `zswapLedgerEvents` subscription returns `{ id, raw, maxId }` — the `raw` field is opaque hex passed directly to Rust `replay_events()`. No prefix splitting needed (unlike our dust FFI which splits by `"midnight:event[v9]:"` — for zswap, pass each event's `raw` hex individually).

**Key Implementation Detail — Balances (VERIFIED):**

No built-in `balances()` method exists. Iterate `state.coins` (a `Map<Nullifier, QualifiedCoinInfo>`) and sum by token type:
```rust
// Each coin has: type_: ShieldedTokenType, value: u128
let mut balances: HashMap<ShieldedTokenType, u128> = HashMap::new();
for (_, coin) in state.coins.iter() {
    *balances.entry(coin.type_).or_insert(0) += coin.value;
}
// Return as JSON: {"token_hex": "balance_string"}
```

**Tests:**
- Create state → replay empty events → verify zero balance
- Create state → replay test events → verify non-zero balance
- Serialize → deserialize → verify state preserved
- Thread safety (concurrent replays)

### Step 2: JNI Bridge (1-2h)

Add to `kuira_crypto_jni.c` — same pattern as dust JNI functions:

```c
JNIEXPORT jlong JNICALL Java_...ZswapLocalState_nativeCreate(JNIEnv *, jclass);
JNIEXPORT void JNICALL Java_...ZswapLocalState_nativeFree(JNIEnv *, jclass, jlong);
JNIEXPORT jlong JNICALL Java_...ZswapLocalState_nativeReplayEvents(JNIEnv *, jclass, jlong, jbyteArray, jstring);
JNIEXPORT jstring JNICALL Java_...ZswapLocalState_nativeGetBalances(JNIEnv *, jclass, jlong);
JNIEXPORT jint JNICALL Java_...ZswapLocalState_nativeGetCoinCount(JNIEnv *, jclass, jlong);
JNIEXPORT jstring JNICALL Java_...ZswapLocalState_nativeSerialize(JNIEnv *, jclass, jlong);
JNIEXPORT jlong JNICALL Java_...ZswapLocalState_nativeDeserialize(JNIEnv *, jclass, jstring);
```

### Step 3: Kotlin Wrapper — ZswapLocalState.kt (1-2h)

**File:** `core/crypto/src/main/kotlin/.../shielded/ZswapLocalState.kt`

Mirror `DustLocalState.kt`:
- `AutoCloseable` with `close()` calling `nativeFree()`
- `replayEvents(seed: ByteArray, eventsHex: String): ZswapLocalState?`
- `getBalances(): Map<String, BigInteger>`
- `getCoinCount(): Int`
- `serialize(): ByteArray?`
- `deserialize(bytes: ByteArray): ZswapLocalState?` (companion)

### Step 4: GraphQL Subscription for Zswap Events (1h)

**Add to `GraphQLQueries.kt`:**

```graphql
subscription ZswapEvents($id: Int) {  # Note: $id is OPTIONAL (not required)
  zswapLedgerEvents(id: $id) {
    id
    raw
    maxId
  }
}
```

**Add to `IndexerClientImpl.kt`:**

```kotlin
fun subscribeToZswapEvents(startId: Long): Flow<ZswapEventUpdate>
```

**Key difference from unshielded subscription:**
- Unshielded: filtered by address, returns parsed JSON
- Shielded: NOT filtered (all events), returns raw hex bytes
- Kotlin does NOT parse the events — passes raw hex directly to Rust FFI

### Step 5: ShieldedSyncManager + Repository (2-3h)

**`ShieldedSyncManager.kt`:**
1. Load serialized state from DataStore (or create new)
2. Get last processed event ID
3. Subscribe to `zswapLedgerEvents(id: lastEventId + 1)`
4. For each batch: collect raw hex → call `state.replayEvents(seed, hex)` → serialize new state
5. Emit sync progress

**`ShieldedBalanceRepository.kt`:**
- `getCurrentBalance(seed: ByteArray): Map<String, BigInteger>` — load state, query balances
- `observeBalance(): Flow<Map<String, BigInteger>>` — watches DataStore for changes

**Persistence:**
- Use DataStore (same as DustRepository) — store serialized state as hex string
- Key: `"zswap_state_{address}"`
- Also store `lastEventId` for resume

### Step 6: UI Integration (1-2h)

Add shielded section to `BalanceScreen.kt`:
- "Shielded Balance" card below unshielded balance
- Show shielded NIGHT balance
- Show shielded address (derived from keys)
- Sync progress indicator

---

## Phase 3: Shielded Transactions

### Step 7: Rust FFI — Shielded Transfer Building (5-6h)

This is the most complex step. Need to build a shielded transfer transaction entirely in Rust.

**What `facade.transferTransaction({ type: 'shielded' })` does internally:**

1. `shielded.transferTransaction(secretKeys, outputs)` — creates an `UnprovenTransaction` that:
   - Selects coins from ZswapLocalState to spend
   - Creates ZK proof preimages for each spent coin
   - Creates encrypted outputs for recipient
   - Balances the zswap offer (inputs = outputs)
2. Merged with dust fee payment (same as unshielded)
3. Signed → Proved → Sealed → Submitted

**New FFI function:**

```rust
#[no_mangle]
pub extern "C" fn zswap_build_transfer(
    state_ptr: *mut ZswapLocalState<InMemoryDB>,
    secret_keys_seed: *const u8,
    seed_len: usize,
    recipient_address_hex: *const c_char,  // shielded address (coin_pk + enc_pk)
    token_type_hex: *const c_char,
    amount: *const c_char,  // string for u128
    ttl_ms: u64,
) -> *const c_char;  // hex-encoded unproven transaction (or null on error)
```

This returns an unproven transaction that goes through the existing prove → seal → submit pipeline.

**Now understood (from fact-check):**
- `State::spend()` selects a coin, creates `Input<ProofPreimage>` with nullifier + Merkle proof path + value commitment. Coin moves to `pending_spends`.
- `Output::new(coin, segment, recipient_coin_pk, recipient_enc_pk)` creates encrypted output with `CoinCiphertext`. Returns `Output<ProofPreimage>`.
- Offers are assembled from inputs + outputs via `ZswapOffer::fromInput/fromOutput` then merged.
- The unproven transaction (with `ProofPreimage` objects) goes to the proof server for ZK proof generation, same `/prove-tx` endpoint as unshielded.
- Merge with dust: same `Transaction::merge()` pattern as unshielded — create dust intent separately, merge into base transaction.

### Step 8: Shielded Send UI (3-4h)

- Add shielded toggle to `SendScreen`
- Validate shielded address prefix (`mn_shield-addr_`)
- Show shielded balance available
- Same prove → seal → submit flow

### Step 9: Shielded Address Display (2h)

- Derive shielded address from ZswapSecretKeys public keys
- Format as `mn_shield-addr_<network>1<bech32m(coin_pk || enc_pk)>`
- Display on wallet info / balance screen
- Already partially done (ShieldedKeys.kt has coin_pk + enc_pk)

### Step 10: Integration Testing (3-4h)

1. **Airdrop shielded NIGHT to Alice** via CLI: `mn wallet use genesis && mn transfer <alice-shielded-addr> 100 --shielded`
2. **View shielded balance in Kuira** — verify it shows 100 NIGHT shielded
3. **Send shielded from Alice to Bob** via Kuira app
4. **Verify** Bob's shielded balance via CLI

---

## Implementation Order

```
Step 1 (Rust FFI)
  → Step 2 (JNI Bridge)
    → Step 3 (Kotlin Wrapper)
      → Step 6 (UI Integration)

Step 4 (GraphQL Subscription)  ← can be done in parallel with Steps 1-3
Step 5 (Sync + Repository)     ← needs Steps 3 + 4

Step 7 (Transfer FFI)          ← needs Steps 1-5 working
  → Step 8 (Send UI)
  → Step 9 (Address Display)
  → Step 10 (Integration Test)
```

**Suggested sprints:**
- **Sprint 1 (4B-Shielded):** Steps 1-6 — shielded balance viewing (~12h)
- **Sprint 2 (Phase 3):** Steps 7-10 — shielded transactions (~15-20h)

---

## Testing Strategy

### Unit Tests (no network)
- ZswapLocalState Rust FFI: create, replay empty, serialize/deserialize round-trip
- Kotlin wrapper: same patterns as DustLocalState tests
- Balance calculation: mock state with known coins → verify sum

### Integration Tests (localnet)
- Replay real zswap events from indexer → verify balance matches CLI
- Full shielded transfer: build → prove → seal → submit → verify on chain

### Cross-Wallet Verification
- CLI airdrop shielded → Kuira shows balance
- Kuira send shielded → CLI shows received

---

## Open Questions (Updated After Fact-Check)

### Resolved

1. **Zswap event format** — RESOLVED: Zswap and dust events use the **same** unified `Event<D>` structure with `event[v9]` tag. No separate format. Raw hex from indexer is opaque — passed directly to Rust `replay_events()` for deserialization.

2. **Balance extraction** — RESOLVED: No built-in `balances()` method. Must iterate `state.coins` and sum by token type. Each coin is a `QualifiedCoinInfo` with `type_: ShieldedTokenType` and `value: u128`.

3. **Shielded address Bech32m encoding** — RESOLVED: Already tested in integration tests. Format: `mn_shield-addr_<network>1<bech32m(coin_pk || enc_pk)>`. Matches Lace.

4. **Event replay mechanism** — RESOLVED: `replayEvents` does NOT call `apply()`. It uses a fold pattern over events, handling `ZswapInput` (remove coin from active set) and `ZswapOutput` (update merkle tree, add coin, decrypt with wallet keys) individually.

### Resolved (Round 2)

5. **Shielded transfer construction** — RESOLVED: ~85-90% of the transfer is built in Rust (client-side), proof server is mandatory for the final step.
   - **In Rust:** Coin selection → nullifier generation → coin encryption (recipient's public key) → Merkle tree proof path → value commitments (Pedersen) → ProofPreimage construction → Offer assembly
   - **Proof server required:** Converting `ProofPreimage` → actual ZK `Proof`. Same as unshielded — the proof server endpoint (`/prove-tx`) handles both.
   - **Key types:** `Input<ProofPreimage>` (spending) and `Output<ProofPreimage>` (creating encrypted coins). Each has a `key_location`: `"midnight/zswap/spend"` or `"midnight/zswap/output"`.
   - **Output encryption:** `CoinCiphertext::new(rng, coin, recipient_encryption_public_key)` — happens entirely in Rust, encrypts coin info so only recipient can decrypt.
   - **Flow:** Build UnprovenTransaction (Rust) → Proof server proves it → Seal (Rust) → Submit to node.

6. **ZswapSecretKeys lifecycle** — RESOLVED: Create per-operation and let Rust auto-wipe. `SecretKeys` derives `Zeroize` + `ZeroizeOnDrop`, so it auto-wipes when dropped. For the FFI, derive `SecretKeys` inside each FFI call from the seed bytes, use them, and let them drop. No caching needed — derivation is <1ms.

7. **Event replay performance** — RESOLVED: Incremental sync IS supported and should be implemented from the start.
   - **How:** The SDK tracks `appliedIndex` (last event ID processed). On resume, request events from `appliedIndex + 1`.
   - **`firstFree`** is the Merkle tree index (next available position), NOT an event ID. Events must arrive in order (`mt_index == firstFree` or `NonLinearInsertion` error).
   - **First sync:** Must replay all events from 0. Typical localnet: hundreds to low thousands of events, 1-5 seconds.
   - **Subsequent syncs:** Only new events since last index. ~10-100ms per event.
   - **Implementation:** Serialize ZswapLocalState + `appliedIndex` to DataStore after sync. Deserialize on app restart, resume from saved index.
   - **Phase 1:** Basic persistence (serialize after full sync completes). **Phase 2:** Incremental batching.

8. **Genesis shielded balance** — PARTIALLY RESOLVED: Genesis seed is `0x01` (defined in CLI `constants.ts` as `GENESIS_SEED = "0000...0001"`). The 250M amount is embedded in the genesis state binary file (`genesis_state_undeployed.mn`), not human-readable. The CLI airdrop command dynamically checks `genesisState.shielded.balances[nightToken]` before transferring. **Recommendation:** Verify live with `mn balance --shielded` on genesis wallet before depending on specific amount.

### Fact-Check Notes

- `SecretKeys::from_seed()` does NOT exist. Correct API: `SecretKeys::from(Seed::from(seed_bytes))`
- GraphQL subscription parameter `$id` is optional (`Int`), not required (`Int!`)
- ZswapLocalState has additional methods not in the plan: `apply_claim()`, `authorize_claim()`, `replayEventsWithChanges()`, `clearPending()`, `revertTransaction()` — some may be needed for Phase 3
- Serialization uses SCALE codec with `"zswap-local-state[v6]"` tag

---

## Reference Files

### CLI (reference implementation)
- `midnight-wallet-cli/tasks/shielded-implementation.md` — CLI's shielded plan
- `midnight-wallet-cli/src/commands/transfer.ts` (shielded branch) — shielded transfer flow
- `midnight-wallet-cli/src/commands/balance.ts` (shielded branch) — shielded balance via facade
- `midnight-wallet-cli/src/commands/airdrop.ts` (shielded branch) — shielded airdrop

### Midnight Libraries (Rust source)
- `midnight-ledger/zswap/src/local.rs` — `zswap::local::State` (core state management)
- `midnight-ledger/ledger-wasm/src/zswap_state.rs` — WASM bindings (our API reference)
- `midnight-ledger/zswap/src/keys.rs` — `SecretKeys` derivation

### Kuira (existing code to mirror)
- `kuira-crypto-ffi/src/dust_ffi.rs` — Dust FFI (same pattern)
- `core/crypto/.../dust/DustLocalState.kt` — Kotlin wrapper (same pattern)
- `core/indexer/.../repository/DustRepository.kt` — Repository (same pattern)
- `core/indexer/.../api/GraphQLQueries.kt` — Add zswap subscription query

### Kuira PLAN
- `docs/PLAN.md` — Phase 4B-Shielded section (8 detailed steps)
- `docs/learning/SHIELDED_BALANCE_DEEP_DIVE.md` — Technical deep dive
- `docs/learning/SHIELDED_SDK_CODE_REFERENCE.md` — Annotated Rust SDK code
