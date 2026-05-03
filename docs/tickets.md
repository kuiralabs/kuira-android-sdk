# SDK Tickets

Tracked issues for the Midnight SDK. Move to GitHub Issues when the repo goes public.

---

## SDK-001: Post-spend DustLocalState serialize/deserialize corrupts Merkle roots

**Priority:** High  
**Component:** `kuira-crypto-ffi/src/dust_ffi.rs`, `DustRepository`  
**Status:** Workaround in place (in-memory only state, never deserialize)

After a successful dust spend, the modified `DustLocalState` is serialized to disk. On the next transaction, deserializing this state produces different Merkle tree roots than the original in-memory state. The node rejects the dust spend proof (error 170).

**Current workaround:** Removed `saveState` from `tryBalance`. Error 170 retry does `forceFullSync` (delete + re-sync from genesis, ~60s on PREPROD).

**Root cause hypothesis:** The Merkle tree uses arena-allocated `Sp<T>` pointers. After `spend()` modifies the tree (marks UTXO as pending), the serialization of the arena structure may not round-trip correctly for the complex post-spend state.

**Proper fix:** Either fix the serialize/deserialize for post-spend state, or only save the "clean" pre-spend state and let delta sync pick up on-chain changes. Investigate by adding a Rust test: `replay → spend → serialize → deserialize → assert roots match`.

---

## SDK-002: TransactionBalancer needs progress callbacks for client UX

**Priority:** Medium  
**Component:** `sdk/midnight-sdk`, `core/compact-engine`  
**Status:** FIXED (2026-04-28)

Implemented `BalanceProgress` sealed class with 6 stages: SyncingDust, SyncingDustProgress, ProvingDust, Submitting, WaitingFinalization, RetryingDustSync. Piped through `TransactionBalancer.balanceAndSubmit()` via optional callback. BBoard shows inline progress bar during dust sync and stage labels during balance+submit.

---

## SDK-003: Full dust sync takes 60s on PREPROD (250k+ events)

**Priority:** Medium  
**Component:** `DustRepository`, `dust_ffi.rs`  
**Status:** Functional but slow

Single-pass replay of 250k events takes ~53s on device. The file-based approach (stream hex to temp file, Rust reads in native memory) works but is slow. The old chunked approach was faster but produced wrong Merkle roots.

**Options to investigate:**
1. Block-aligned chunking — group events by block boundary, replay per block. Collapses and rehash happen once per block (matching the node's pattern).
2. Binary event file — write raw bytes instead of hex to the file, halving the file size and avoiding hex encode/decode overhead.
3. Incremental checkpoint that preserves roots — investigate what exactly in `replay_events_with_changes` makes chunked replay produce different roots. If only the generation collapse ordering matters, we could flush per-block instead of per-N-events.

---

## SDK-004: Fee calculation uses INITIAL_PARAMETERS fallback (66 trillion specks)

**Priority:** Medium  
**Component:** `kuira-crypto-ffi/src/balance_ffi.rs`  
**Status:** FIXED (2026-04-27) — zero-fee path bypasses dust entirely

PREPROD has zero fees (`feesWithMargin` returns 0). The old code fell back to `INITIAL_PARAMETERS` (66 trillion specks), creating an imbalanced tx that the node rejected. Now `balance_ffi` checks real ledger params first — if fee=0, seals the proven tx without dust.

**Remaining work:** When fees are non-zero (mainnet), we need the facade's convergence loop for correct fee calculation. The `INITIAL_PARAMETERS` fallback is still wrong for production.

---

## SDK-005: GraphQL WebSocket "No active subscription found" log spam

**Priority:** Low  
**Component:** `core/indexer/.../GraphQLWebSocketClient.kt`  
**Status:** Cosmetic

After a subscription completes, hundreds of "No active subscription found for sub_N" warnings flood logcat. The WebSocket receives buffered messages after the subscription is cleaned up. Not harmful but makes logs unreadable.

**Fix:** Suppress the warning after the first occurrence per subscription ID, or drain the channel silently after cleanup.
