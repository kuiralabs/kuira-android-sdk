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

### **Phase 1: Quick Win (Switch to Subscription)**

**Goal:** 10 minutes → 30-60 seconds

```kotlin
// core/indexer/api/IndexerClientImpl.kt
override suspend fun queryDustEvents(maxBlocks: Int): String {
    // Replace block scanning with subscription
    return subscribeToDustEventsOnce(fromId = 0)
}

private suspend fun subscribeToDustEventsOnce(fromId: Long): String {
    val events = mutableListOf<String>()
    var maxId = Long.MAX_VALUE

    subscribeToDustEvents(fromId).collect { event ->
        events.add(event.rawHex)
        maxId = event.maxId
        if (event.id >= maxId) {
            cancel() // Stop when caught up
        }
    }

    return events.joinToString("")
}
```

**Time:** 2-3 hours
**Impact:** 20x faster (10 min → 30 sec)

---

### **Phase 2: Incremental Sync**

**Goal:** First sync 30s, subsequent syncs < 1s

1. Store `lastProcessedEventId` with cached DustLocalState
2. On sync: query only events WHERE `id > lastProcessedEventId`
3. Replay only new events

**Time:** 3-4 hours
**Impact:** Subsequent syncs instant

---

### **Phase 3: Background Sync Manager**

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

## Open Questions

1. **Does subscription stream efficiently?** Need to test with real indexer
2. **What's the optimal batch size?** For pagination approach
3. **How to handle sync failures?** Retry strategy, fallback to cached state
4. **Cache invalidation?** When to force full re-sync

---

## References

- Midnight Indexer Schema: `midnight-libraries/midnight-indexer/indexer-api/graphql/schema-v3.graphql`
- TypeScript SDK Dust Wallet: `midnight-libraries/midnight-wallet/packages/dust-wallet/src/DustCoreWallet.ts`
- Our Current Implementation: `core/indexer/api/IndexerClientImpl.kt:queryDustEvents()`
