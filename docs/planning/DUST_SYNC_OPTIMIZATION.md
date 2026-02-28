# Dust Sync Optimization Strategy

## Current Problem

**Issue:** First transaction takes 10+ minutes due to inefficient dust event syncing.

**Root Cause:**
```kotlin
// Current implementation scans EVERY block individually
for (height in 0..71039) {  // 71,000+ HTTP requests!
    query { block(height) { dustLedgerEvents } }
}
```

**Why it's slow:**
- 71,000+ individual HTTP requests (one per block)
- Downloads ALL dust events from ALL users
- No pagination, no filtering
- Block-by-block scanning is extremely inefficient

**Comparison:**
- Lace wallet: < 5 seconds for first transaction
- Our wallet: 10+ minutes for first transaction

---

## Why Dust Events Are Global (By Design)

From Midnight indexer GraphQL schema analysis:

```graphql
# Filtered by address ✅
unshieldedTransactions(address: UnshieldedAddress!, transactionId: Int)

# Filtered by session ✅
shieldedTransactions(sessionId: HexEncoded!, index: Int)

# GLOBAL - No filtering ❌
dustLedgerEvents(id: Int)
zswapLedgerEvents(id: Int)
```

**Why no filtering:**
- Dust uses a global Merkle tree for zero-knowledge proofs
- ALL dust events affect the tree structure (even from other users)
- Clients MUST process all events to build correct tree state
- `DustLocalState.replayEvents()` filters internally (only processes relevant events)

**This is correct by design** - we need all events, but we're querying them inefficiently.

---

## Optimal Solutions (Ranked)

### **Solution 1: WebSocket Subscription (RECOMMENDED)** ⭐

**Strategy:** Stream all events via single WebSocket connection

```kotlin
class DustSyncManager {
    suspend fun syncFromGenesis(
        address: String,
        dustSeed: ByteArray
    ): DustLocalState {
        val state = DustLocalState.create()

        // Single WebSocket connection - streams ALL dust events efficiently
        indexerClient.subscribeToDustEvents(fromId = 0)
            .collect { event ->
                // DustLocalState filters internally
                state.replayEvent(dustSeed, event)

                // Stop when caught up to chain tip
                if (event.id >= event.maxId) {
                    break
                }
            }

        return state
    }
}
```

**Benefits:**
- Single WebSocket connection (not 71K HTTP requests)
- Server-push streaming (efficient)
- Processes all events, but minimal network overhead
- 10 minutes → 30-60 seconds

**Implementation:**
```graphql
subscription DustEvents($fromId: Int) {
  dustLedgerEvents(id: $fromId) {
    id
    raw
    maxId
  }
}
```

---

### **Solution 2: Bulk Query with Pagination**

**Strategy:** Query events in large batches (1000 at a time)

```kotlin
suspend fun queryAllDustEvents(): List<RawLedgerEvent> {
    val allEvents = mutableListOf<RawLedgerEvent>()
    var lastId = 0L

    while (true) {
        // Query in batches of 1000
        val batch = indexerClient.getEventsInRange(
            fromId = lastId,
            toId = lastId + 1000
        )

        if (batch.isEmpty()) break

        allEvents.addAll(batch)
        lastId = batch.last().id

        if (lastId >= batch.last().maxId) break
    }

    return allEvents
}
```

**Benefits:**
- ~71 HTTP requests instead of 71,000
- 100x faster than current approach
- 10 minutes → 1-2 minutes

**Note:** This leverages existing `getEventsInRange()` method in IndexerClient.

---

### **Solution 3: Incremental Sync (Production Architecture)**

**Strategy:** First sync is slow, subsequent syncs are fast

```kotlin
class DustRepository {
    // Store last synced event ID with cached state
    data class DustCache(
        val stateBytes: ByteArray,
        val lastEventId: Long,
        val lastSyncTime: Long
    )

    suspend fun syncFromBlockchain(address: String, dustSeed: ByteArray): Boolean {
        val cache = loadCache(address)

        if (cache == null) {
            // First sync: Use subscription (slow, one-time)
            fullSync(address, dustSeed)
        } else {
            // Incremental: Only new events since last sync (fast)
            val newEvents = queryEventsRange(fromId = cache.lastEventId + 1)
            replayEvents(cache.state, dustSeed, newEvents)
        }
    }
}
```

**Benefits:**
- First transaction: 30-60 seconds (subscription)
- Subsequent transactions: < 1 second (cached)
- Real-time sync in background
- Never blocks UI

---

### **Solution 4: Background Sync Manager (Best UX)**

**Strategy:** Sync happens in background, never blocks transactions

```kotlin
// On app start
class MainViewModel {
    init {
        viewModelScope.launch {
            // Background sync (doesn't block UI)
            dustSyncManager.initialize(address, dustSeed)
        }
    }
}

class DustSyncManager {
    fun initialize(address: String, dustSeed: ByteArray) {
        viewModelScope.launch {
            // 1. Load cached state (instant)
            val cached = dustRepository.loadCachedState(address)

            if (cached != null) {
                // 2. Incremental sync (fast - only new events)
                val lastEventId = cached.lastProcessedEventId
                val newEvents = indexerClient.getEventsInRange(
                    fromId = lastEventId + 1,
                    toId = lastEventId + 10000  // Batch size
                )
                dustRepository.replayEvents(newEvents)
            } else {
                // 3. Initial sync (one-time, in background)
                indexerClient.subscribeToDustEvents(fromId = 0)
                    .collect { event ->
                        dustRepository.applyEvent(event)
                    }
            }

            // 4. Subscribe for real-time updates
            indexerClient.subscribeToDustEvents(
                fromId = dustRepository.getLastEventId()
            ).collect { event ->
                dustRepository.applyEvent(event)
            }
        }
    }
}

// Send transaction
class SendViewModel {
    fun sendTransaction(...) {
        // Uses cached dust state - never waits for sync!
        val hasDust = dustRepository.hasCachedState(address)
        if (!hasDust) {
            showError("Please wait for dust sync to complete...")
            return
        }

        // Proceed with transaction...
    }
}
```

**Benefits:**
- Sync happens on app start (background)
- Transactions NEVER wait for sync
- Real-time updates as dust generates
- Best user experience

---

## Recommended Implementation Plan

### **Phase 1: Quick Win (Switch to Subscription)** — IMPLEMENTING NOW

**Goal:** 10 minutes → 30-60 seconds

#### File Changes (5 files)

**1. `core/indexer/src/main/kotlin/.../api/GraphQLQueries.kt`** — Add subscription query
- Add `SUBSCRIBE_DUST_LEDGER_EVENTS` constant
- Subscription: `dustLedgerEvents(id: $id) { id, raw, maxId }`
- Follows same pattern as existing `SUBSCRIBE_UNSHIELDED_TRANSACTIONS`

**2. `core/indexer/src/main/kotlin/.../api/IndexerClient.kt`** — Add interface method
- Add `subscribeToDustEvents(fromId: Long? = null): Flow<RawLedgerEvent>`
- Returns `Flow<RawLedgerEvent>` (same model already used by `getEventsInRange`)
- `fromId` is the event cursor — pass last known event ID to resume

**3. `core/indexer/src/main/kotlin/.../api/IndexerClientImpl.kt`** — Implement subscription + rewrite queryDustEvents
- Implement `subscribeToDustEvents()` using `GraphQLWebSocketClient.subscribe()`
  - Same pattern as `subscribeToUnshieldedTransactions()`: get/create WS client, connect, subscribe, parse response
  - Parse `subscription.dustLedgerEvents` → `RawLedgerEvent(id, rawHex, maxId)`
- Rewrite `queryDustEvents()` to use the subscription internally:
  - Subscribe from `fromId = 0`
  - Collect events until `event.id >= event.maxId` (caught up to chain tip)
  - Sort by ID, concatenate raw hex, return combined string
  - Same output format as before — `DustRepository` doesn't need changes
- Remove block-by-block HTTP loop and `DustEventData` helper class

**4. `DustRepository.kt`** — No changes needed
- Already calls `indexerClient.queryDustEvents(maxBlocks)` and gets combined hex string
- The switch is transparent — same input/output contract

**5. `DustViewModel.kt` / `DustScreen.kt`** — No changes needed
- UI layer is unaffected — the performance improvement is entirely in the transport layer

#### Data flow (before vs after)

**Before:**
```
DustRepository.syncFromBlockchain()
  → IndexerClientImpl.queryDustEvents()
    → HTTP POST per block (71,000+ requests)
    → Collect DustEventData list
    → Concatenate hex
  → DustLocalState.replayEvents(seed, combinedHex)
```

**After:**
```
DustRepository.syncFromBlockchain()
  → IndexerClientImpl.queryDustEvents()
    → WebSocket subscribe dustLedgerEvents(id: 0)
    → Stream RawLedgerEvent until id >= maxId
    → Concatenate hex
  → DustLocalState.replayEvents(seed, combinedHex)
```

#### Key detail: batching for replayEvents

`DustLocalState.replayEvents(seed, eventsHex)` returns a NEW state object (Rust FFI).
Calling it per-event would create/destroy thousands of FFI objects. Instead:
- Collect all events from subscription into a list
- Concatenate hex once
- Replay once (same as current behavior)
- This matches the SDK pattern (bufferCount(10) + batch replay)

---

### **Phase 2: Incremental Sync** (Future)

**Goal:** First sync 30s, subsequent syncs < 1s

1. Store `lastProcessedEventId` alongside cached DustLocalState in DataStore
2. On sync: subscribe from `id = lastProcessedEventId + 1` (not from 0)
3. Replay only new events onto existing state
4. `DustRepository` already has `saveState()` / `loadState()` — extend with event ID

**Time:** 3-4 hours
**Impact:** Subsequent syncs instant

---

### **Phase 3: Background Sync Manager** (Future)

**Goal:** Never block UI

1. Create `DustSyncManager` in `core:indexer`
2. Initialize on app start (background)
3. Keep subscription open for real-time updates
4. Transactions check cache, never wait

**Time:** 4-6 hours
**Impact:** Perfect UX (like Lace)

---

## Technical Details

### Existing IndexerClient Methods

```kotlin
// Already implemented - can use for pagination
suspend fun getEventsInRange(fromId: Long, toId: Long): List<RawLedgerEvent>

// Need to add for streaming
fun subscribeToDustEvents(fromId: Long? = null): Flow<RawLedgerEvent>
```

### GraphQL Subscription

```graphql
subscription DustEventsSubscription($fromId: Int) {
  dustLedgerEvents(id: $fromId) {
    id
    raw
    maxId
  }
}
```

### WebSocket Flow

```
App → WebSocket CONNECT → Indexer
App → Subscribe(fromId=0) → Indexer
Indexer → Event 1 → App (process)
Indexer → Event 2 → App (process)
...
Indexer → Event N (id=maxId) → App (done)
App → Disconnect
```

---

## Performance Comparison

| Approach | First Sync | Incremental | Network | UX |
|----------|-----------|-------------|---------|-----|
| Current (block scanning) | 10 min | 10 min | 71K requests | ❌ Terrible |
| Pagination (getEventsInRange) | 1-2 min | 1-2 min | ~71 requests | 🟡 OK |
| Subscription (streaming) | 30-60 sec | 30-60 sec | 1 connection | ✅ Good |
| Background + Cache | 30-60 sec | < 1 sec | 1 connection | ⭐ Excellent |

---

## Next Steps

1. **Immediate:** Implement subscription-based sync (Phase 1)
2. **Short-term:** Add incremental sync (Phase 2)
3. **Medium-term:** Background sync manager (Phase 3)
4. **Polish:** Progress UI ("Syncing dust: 1250/5000 events")

---

## Confirmed Findings (from SDK Investigation)

These were open questions that are now resolved after reading the TypeScript SDK source code.

### 1. Does subscription stream efficiently?
**YES — confirmed.** The TypeScript SDK uses a single WebSocket subscription via `dustLedgerEvents(id: $id)`. The CLI (`midnight-wallet-cli/src/commands/dust.ts`) uses `WalletFacade` with `DustWallet`, which connects to the indexer via WebSocket (`indexerWsUrl`) and streams all events in a single connection. First sync takes seconds, not minutes.

### 2. What's the optimal batch size?
**SDK uses 10 events per batch.** From `midnight-wallet/packages/dust-wallet/src/Sync.ts`:
- `bufferCount(10)` — collects 10 events per batch
- `bufferTime(1)` — 1ms timeout (flush partial batches quickly)
- `throttleTime(4)` — applies batches every 4ms
- This means: stream events → batch 10 at a time → replay into `DustLocalState`

### 3. How to handle sync failures?
**Resume via `maxId`.** Each event response includes `maxId` (total events in the ledger). The subscription parameter `id` acts as a cursor — pass the last successfully processed event ID to resume. The SDK stores `lastEventId` and reconnects from there.

### 4. Cache invalidation?
**Incremental by design.** Once initial sync is complete, subsequent syncs only need events from `lastEventId + 1`. No full re-sync needed unless local state is corrupted/deleted.

### 5. GraphQL subscription schema (confirmed from SDK)
```graphql
subscription DustLedgerEvents($id: Int) {
  dustLedgerEvents(id: $id) {
    id          # Sequential event ID (cursor for resume)
    raw         # Hex-encoded event data (passed to DustLocalState.replayEvents)
    maxId       # Total events in ledger (for progress tracking)
  }
}
```

### 6. Event types (4 types, all in `raw` field)
- `ParamChange` — Network parameter updates
- `DustInitialUtxo` — New dust registration
- `DustGenerationDtimeUpdate` — Generation timestamp update
- `DustSpendProcessed` — Dust spent in transaction

All events are global (not filtered by address). `DustLocalState.replayEvents()` filters internally using the dust seed.

---

## References

- Midnight Indexer Schema: `midnight-libraries/midnight-indexer/indexer-api/graphql/schema-v3.graphql`
- TypeScript SDK Dust Wallet: `midnight-libraries/midnight-wallet/packages/dust-wallet/src/DustCoreWallet.ts`
- TypeScript SDK Dust Sync: `midnight-libraries/midnight-wallet/packages/dust-wallet/src/Sync.ts`
- TypeScript SDK Subscription: `midnight-libraries/midnight-wallet/packages/indexer-client/src/graphql/subscriptions/DustLedgerEvents.ts`
- CLI Dust Command: `midnight-wallet-cli/src/commands/dust.ts` (uses WalletFacade → WebSocket)
- CLI Facade: `midnight-wallet-cli/src/lib/facade.ts` (DustWallet connects to `indexerWsUrl`)
- Our Current Implementation: `core/indexer/api/IndexerClientImpl.kt:queryDustEvents()` (block-by-block — to be replaced)
