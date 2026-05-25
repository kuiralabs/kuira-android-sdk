# Phase 4A - Corrected Implementation

## What Changed

### Before (Incorrect - Phase 4A-Lite)
- ❌ Invented fake balance query APIs (`getUnshieldedBalance`, `getShieldedBalance`)
- ❌ These queries don't exist in Midnight GraphQL schema
- ❌ Created fake cache manager for non-existent data
- ❌ 12 tests testing fake implementations
- ❌ Would never work with real indexer

### After (Correct - Phase 4A Sync Engine)
- ✅ Kept Phase 4A-Full sync engine (the real implementation)
- ✅ Deleted fake balance queries
- ✅ Deleted fake cache manager
- ✅ Deleted 12 fake tests
- ✅ Fixed default URLs to testnet
- ✅ 83 real tests passing (sync engine, UTXO tracking, reorg detection)

## Test Results

**Before cleanup:** 237 tests (225 real + 12 fake)
**After cleanup:** 225 tests (all real)

### Test Breakdown
- `core/crypto`: 107 tests ✅
- `core/wallet`: 35 tests ✅
- `core/indexer`: 83 tests ✅ (Phase 4A sync engine)

## Phase 4A Architecture (Correct)

### What Phase 4A Actually Provides

**Sync Engine Components:**
1. **Event Cache** (`InMemoryEventCache`) - Stores zswap ledger events
2. **Reorg Detector** (`ReorgDetectorImpl`) - Detects blockchain reorganizations
3. **Network State** (`NetworkState`) - Tracks sync progress
4. **Retry Policy** - Exponential backoff for failed requests

**Files:**
```
core/indexer/src/main/kotlin/com/midnight/kuira/core/indexer/
├── api/
│   ├── IndexerClient.kt              # Interface (corrected)
│   ├── IndexerClientImpl.kt          # Implementation (URLs fixed)
│   ├── IndexerException.kt          # Error types
│   └── RetryPolicy.kt               # Retry logic
├── model/
│   ├── BlockInfo.kt                 # Block metadata
│   ├── NetworkState.kt              # Sync state
│   └── RawLedgerEvent.kt            # Event data
├── reorg/
│   ├── ReorgDetector.kt             # Interface
│   └── ReorgDetectorImpl.kt         # Detection logic
└── storage/
    ├── EventCache.kt                # Interface
    ├── EventEntity.kt               # Room entity
    ├── InMemoryEventCache.kt        # Memory implementation
    └── SyncStateManager.kt          # State management
```

### How Balance Tracking Actually Works (Midnight Architecture)

**IMPORTANT:** Balances are NOT queried - they're calculated locally from UTXOs!

1. **Subscribe to Transactions** (Phase 4B - WebSocket)
   ```graphql
   subscription {
     unshieldedTransactions(address: "mn_addr_testnet1...") {
       transaction { ... }
       createdUtxos { ... }
       spentUtxos { ... }
     }
   }
   ```

2. **Build Local UTXO Set** (Phase 4A - Sync Engine)
   - Receive transaction events
   - Add `createdUtxos` to database
   - Remove `spentUtxos` from database
   - Store in Room database

3. **Calculate Balance** (Local)
   ```kotlin
   fun calculateBalance(utxos: List<UnshieldedUtxo>): Map<TokenType, BigInteger> {
       return utxos.groupBy { it.tokenType }
           .mapValues { (_, utxos) ->
               utxos.sumOf { it.value }
           }
   }
   ```

4. **Display in UI**
   - Query local UTXO database
   - Calculate balance
   - Show to user

## Corrected URLs

### Before (Wrong)
```kotlin
baseUrl = "https://indexer.midnight.network/api/v3"  // ❌ Domain doesn't exist
```

### After (Correct)
```kotlin
baseUrl = "https://indexer.testnet-02.midnight.network/api/v3"  // ✅ Testnet
```

**Full Endpoints:**
- HTTP Queries: `https://indexer.testnet-02.midnight.network/api/v3/graphql`
- WebSocket: `wss://indexer.testnet-02.midnight.network/api/v3/graphql/ws`

## What's Next: Phase 4B

### WebSocket Subscriptions (15-20 hours)

**Goal:** Subscribe to transaction events in real-time

**Implementation:**
1. **WebSocket Client** (`WebSocketClient.kt`)
   - Connect to `wss://indexer.testnet-02.midnight.network/api/v3/graphql/ws`
   - Implement `graphql-ws` protocol
   - Handle connection lifecycle

2. **Mutations** (for shielded wallet)
   ```kotlin
   // Connect wallet
   suspend fun connect(viewingKey: String): String  // Returns sessionId

   // Disconnect wallet
   suspend fun disconnect(sessionId: String)
   ```

3. **Subscriptions**
   ```kotlin
   // Unshielded transactions
   fun subscribeToUnshieldedTransactions(address: String): Flow<UnshieldedTransaction>

   // Shielded transactions (requires session)
   fun subscribeToShieldedTransactions(sessionId: String): Flow<ShieldedTransaction>
   ```

4. **UTXO Database** (Room)
   ```kotlin
   @Entity(tableName = "unshielded_utxos")
   data class UnshieldedUtxoEntity(
       @PrimaryKey val id: String,
       val owner: String,
       val tokenType: String,
       val value: String,
       val intentHash: String,
       val outputIndex: Int,
       val createdAt: Long,
       val spentAt: Long?
   )
   ```

5. **Balance Calculator**
   ```kotlin
   class BalanceCalculator(private val utxoDao: UtxoDao) {
       suspend fun getBalance(address: String): Map<String, BigInteger> {
           val utxos = utxoDao.getUnspentUtxos(address)
           return calculateFromUtxos(utxos)
       }
   }
   ```

### Integration Test Plan

1. Connect to live testnet indexer
2. Subscribe to known address from verification test
3. Send test transaction
4. Verify UTXO events received
5. Verify balance calculated correctly
6. Display in UI

## Files Modified

### Deleted Files
- ❌ `core/indexer/src/main/kotlin/.../cache/BalanceCacheManager.kt`
- ❌ `core/indexer/src/main/kotlin/.../cache/BalanceDao.kt`
- ❌ `core/indexer/src/main/kotlin/.../cache/BalanceDatabase.kt`
- ❌ `core/indexer/src/main/kotlin/.../cache/CachedBalance.kt`
- ❌ `core/indexer/src/test/kotlin/.../api/LightWalletQueriesTest.kt`
- ❌ `core/indexer/src/test/kotlin/.../cache/BalanceCacheManagerTest.kt`

### Modified Files
- ✅ `IndexerClient.kt` - Removed fake balance query methods
- ✅ `IndexerClientImpl.kt` - Removed implementations, fixed URLs to testnet

## Lessons Learned

1. **Always verify API schema first** - Check official GraphQL schema before implementing
2. **Study reference implementation** - Lace wallet source code shows the correct approach
3. **Light wallet ≠ Simple queries** - Light wallet still needs local state, just not full node
4. **WebSocket subscriptions required** - Midnight architecture mandates subscriptions for transaction tracking
5. **No shortcuts** - Can't avoid complexity by inventing simpler APIs

## Summary

✅ **Phase 4A Corrected** - Sync engine ready for Phase 4B
✅ **225 tests passing** - All fake tests removed
✅ **URLs fixed** - Now pointing to testnet
✅ **Ready for WebSocket implementation** - Clean foundation

Next: Implement Phase 4B WebSocket subscriptions to enable balance tracking!
