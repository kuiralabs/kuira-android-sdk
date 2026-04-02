# Indexer Module - The Complete Big Picture

**Understanding how all the pieces fit together**

---

## The 30-Second Summary 🎯

The `core:indexer` module is **your wallet's eyes and ears on the blockchain**. It:
1. **Talks to Midnight indexer server** (HTTP + WebSocket)
2. **Downloads transaction history** (Phase 4A sync engine)
3. **Listens for new transactions** (Phase 4B WebSocket subscriptions - in progress)
4. **Caches data locally** (so you're not re-downloading everything)
5. **Detects blockchain reorgs** (when the blockchain history changes)
6. **Tracks sync progress** (so UI can show "Syncing 45%...")

---

## Architecture Overview 🏗️

```
┌────────────────────────────────────────────────────────────────┐
│                    Your Wallet App                             │
│                    (ViewModel Layer)                            │
└───────────────────────────┬────────────────────────────────────┘
                            │
                            │ Uses IndexerClient interface
                            ↓
┌────────────────────────────────────────────────────────────────┐
│                  INDEXER MODULE (core:indexer)                  │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────┐                                          │
│  │  API Layer      │  ← Talks to Midnight indexer server      │
│  │  - IndexerClient│                                           │
│  │  - RetryPolicy  │                                           │
│  └────────┬────────┘                                           │
│           │                                                     │
│           ├──────────────┬──────────────┬──────────────┐       │
│           ↓              ↓              ↓              ↓       │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────┐  │
│  │ WebSocket  │  │  Storage   │  │   Reorg    │  │ Verify │  │
│  │  Layer     │  │   Layer    │  │  Detection │  │ Events │  │
│  │            │  │            │  │            │  │        │  │
│  │ - WS Client│  │ - Cache    │  │ - Detector │  │ - Check│  │
│  │ - Messages │  │ - Sync Mgr │  │            │  │  Sigs  │  │
│  └────────────┘  └────────────┘  └────────────┘  └────────┘  │
│                                                                 │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ HTTP + WebSocket
                             ↓
                ┌────────────────────────────┐
                │   Midnight Indexer Server   │
                │   (Testnet/Preview)         │
                └────────────────────────────┘
```

---

## Part 1: The API Layer - Talking to the Indexer

### 🎪 IndexerClient (Interface)

**Role:** The main contract - defines what you can ask the indexer

**Location:** `api/IndexerClient.kt`

**Think of it as:** A menu at a restaurant - lists all available operations

**Key Methods:**
```kotlin
interface IndexerClient {
    // QUERIES (HTTP)
    suspend fun getNetworkState(): NetworkState
    suspend fun getEventsInRange(fromId: Long, toId: Long): List<RawLedgerEvent>

    // SUBSCRIPTIONS (WebSocket - Phase 4B, in progress)
    fun subscribeToZswapEvents(fromId: Long?): Flow<RawLedgerEvent>
    fun subscribeToBlocks(): Flow<BlockInfo>
}
```

**What it provides:**
- Historical data (queries)
- Real-time updates (subscriptions)
- Network status info

**Who uses it:** Your ViewModel/Repository layer

---

### 🏭 IndexerClientImpl (Implementation)

**Role:** Actually does the work of talking to the server

**Location:** `api/IndexerClientImpl.kt`

**Think of it as:** The kitchen - takes your order and prepares it

**What it does:**
```kotlin
class IndexerClientImpl(
    private val baseUrl: String = "https://indexer.testnet-02.midnight.network/api/v3"
) : IndexerClient {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) { requestTimeoutMillis = 30_000 }
    }

    override suspend fun getNetworkState(): NetworkState {
        // Sends GraphQL query via HTTP POST
        val response = httpClient.post("$baseUrl/graphql") {
            setBody("""{"query": "query { networkState { ... } }"}""")
        }
        return response.body()
    }
}
```

**Responsibilities:**
1. **HTTP Communication** - Sends GraphQL queries
2. **WebSocket Management** - Opens WebSocket for subscriptions (Phase 4B)
3. **Response Parsing** - Converts JSON → Kotlin objects
4. **Error Handling** - Retries failed requests
5. **TLS Configuration** - Secure connections

**Key Components:**
- Ktor HttpClient (for HTTP requests)
- GraphQLWebSocketClient (for WebSocket subscriptions)
- JSON serialization (kotlinx.serialization)

---

### 🔄 RetryPolicy

**Role:** Decides when and how to retry failed requests

**Location:** `api/RetryPolicy.kt`

**Think of it as:** The persistent salesperson - keeps trying until it works

**Strategy:**
```kotlin
class ExponentialBackoffRetryPolicy(
    maxRetries: Int = 3,
    initialDelayMs: Long = 1000
) : RetryPolicy {

    override suspend fun execute(block: suspend () -> T): T {
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return block()  // Try the operation
            } catch (e: Exception) {
                lastException = e
                val delay = initialDelayMs * (2 ^ attempt)  // 1s, 2s, 4s...
                delay(delay)  // Wait before retry
            }
        }

        throw lastException!!  // All retries failed
    }
}
```

**When it helps:**
- Temporary network glitches
- Server temporarily overloaded
- Rate limiting

**When it gives up:**
- Bad credentials (won't fix with retry)
- Invalid request (won't fix with retry)
- Max retries exceeded

---

### 🔐 TlsConfiguration

**Role:** Configures secure connections (HTTPS/WSS)

**Location:** `api/TlsConfiguration.kt`

**Think of it as:** The bouncer - ensures secure communication

**What it does:**
- Validates server certificates
- Enforces TLS 1.2+
- Prevents man-in-the-middle attacks

---

### ⚠️ IndexerException

**Role:** Custom exception types for indexer errors

**Location:** `api/IndexerException.kt`

**Types:**
```kotlin
sealed class IndexerException : Exception() {
    class NetworkError : IndexerException()
    class ServerError(val code: Int) : IndexerException()
    class ParseError : IndexerException()
    class Timeout : IndexerException()
}
```

**Why custom exceptions:** Allows caller to handle different errors differently

---

## Part 2: The WebSocket Layer - Real-Time Updates

### 📡 GraphQLWebSocketClient

**Role:** Manages WebSocket connection to indexer

**Location:** `websocket/GraphQLWebSocketClient.kt`

**Think of it as:** A telephone line - stays open for instant communication

**What it does:**
```kotlin
class GraphQLWebSocketClient(
    private val httpClient: HttpClient,
    private val url: String
) {
    suspend fun connect() {
        // 1. WebSocket handshake
        session = httpClient.webSocketSession(url) {
            header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
        }

        // 2. Send connection_init
        sendMessage(ConnectionInit())

        // 3. Wait for connection_ack
        waitForAck()

        // 4. Start listening for messages
        startMessageProcessing()
    }

    fun subscribe(query: String): Flow<JsonElement> {
        // Create subscription, return Flow of results
    }
}
```

**Key Features:**
- Maintains persistent connection
- Routes messages to correct subscriptions
- Automatic ping/pong keep-alive
- Thread-safe (atomic operations)
- Handles reconnection on errors

**Why WebSocket?**
- Server can push data instantly (no polling)
- Battery efficient (one connection)
- Low latency (immediate updates)

---

### 📨 GraphQLWebSocketMessage

**Role:** Defines all GraphQL-WS protocol message types

**Location:** `websocket/GraphQLWebSocketMessage.kt`

**Think of it as:** The language both sides speak

**Message Types:**
```kotlin
sealed class GraphQLWebSocketMessage {
    data class ConnectionInit() : GraphQLWebSocketMessage()
    data class ConnectionAck() : GraphQLWebSocketMessage()
    data class Subscribe(id: String, payload: SubscribePayload) : GraphQLWebSocketMessage()
    data class Next(id: String, payload: JsonElement) : GraphQLWebSocketMessage()
    data class Error(id: String, payload: List<GraphQLError>) : GraphQLWebSocketMessage()
    data class Complete(id: String) : GraphQLWebSocketMessage()
    data class Ping() : GraphQLWebSocketMessage()
    data class Pong() : GraphQLWebSocketMessage()
}
```

**Protocol Flow:**
```
Client                      Server
  │                           │
  │── ConnectionInit ────────▶│
  │                           │
  │◀──── ConnectionAck ───────│
  │                           │
  │── Subscribe(id="1") ─────▶│
  │                           │
  │◀──── Next(id="1") ────────│ (data arrives)
  │◀──── Next(id="1") ────────│ (more data)
  │                           │
  │── Complete(id="1") ──────▶│
```

---

## Part 3: The Storage Layer - Local Data Management

### 💾 EventCache (Interface)

**Role:** Contract for storing events locally

**Location:** `storage/EventCache.kt`

**Think of it as:** Your wallet's memory - remembers what it's seen

**Why cache?**
- Don't re-download historical data every app restart
- Query faster (local database vs network)
- Work offline (show cached data)

**Methods:**
```kotlin
interface EventCache {
    suspend fun saveEvents(events: List<EventEntity>)
    suspend fun getEvents(fromId: Long, toId: Long): List<EventEntity>
    suspend fun getLatestEventId(): Long?
    suspend fun clear()
}
```

---

### 🧠 InMemoryEventCache (Implementation)

**Role:** Simple in-memory implementation of EventCache

**Location:** `storage/InMemoryEventCache.kt`

**Think of it as:** RAM storage - fast but temporary

**Current Status:** ✅ Implemented for testing/development

**What it is:**
```kotlin
class InMemoryEventCache : EventCache {
    private val events = mutableMapOf<Long, EventEntity>()

    override suspend fun saveEvents(events: List<EventEntity>) {
        events.forEach { event ->
            this.events[event.id] = event
        }
    }

    override suspend fun getEvents(fromId: Long, toId: Long): List<EventEntity> {
        return events.values
            .filter { it.id in fromId..toId }
            .sortedBy { it.id }
    }
}
```

**Future:** Will be replaced with Room database for persistent storage

---

### 📊 SyncStateManager

**Role:** Tracks wallet sync progress

**Location:** `storage/SyncStateManager.kt`

**Think of it as:** Your progress bar manager

**What it tracks:**
```kotlin
class SyncStateManager {
    private data class SyncState(
        val currentEventId: Long = 0,
        val maxEventId: Long = 0
    )

    fun updateProgress(currentEventId: Long, maxEventId: Long) {
        _syncState.value = SyncState(currentEventId, maxEventId)
    }

    fun getSyncProgress(): Flow<Float> {
        return _syncState.map { state ->
            state.currentEventId.toFloat() / state.maxEventId.toFloat()
        }
    }
}
```

**Usage in UI:**
```kotlin
// In ViewModel:
syncManager.getSyncProgress().collect { progress ->
    _uiState.value = UiState.Syncing(progress = (progress * 100).toInt())
}

// UI shows:
// "Syncing wallet... 45%"
// [████████░░░░░░░░░░░░] 45%
```

---

### 📦 EventEntity

**Role:** Database entity for storing events

**Location:** `storage/EventEntity.kt`

**Think of it as:** A single row in your events table

**Structure:**
```kotlin
data class EventEntity(
    val id: Long,              // Event ID (primary key)
    val raw: String,           // Raw event data (JSON)
    val blockHeight: Long,     // Which block this event is in
    val timestamp: Long,       // When event occurred
    val processed: Boolean = false  // Have we processed this?
)
```

**Why store raw data?**
- Future-proof (if we add features, can reprocess old events)
- Audit trail (can verify against blockchain)
- Debugging (see exact data received)

---

## Part 4: The Reorg Detection Layer - Blockchain Safety

### 🔀 ReorgDetector (Interface)

**Role:** Detects when blockchain history changes

**Location:** `reorg/ReorgDetector.kt`

**Think of it as:** Time-travel detector - notices when history is rewritten

**What is a Reorg?**
```
Normal blockchain:
... → Block 100 → Block 101 → Block 102 → Block 103

Reorg happens (longer chain found):
... → Block 100 → Block 101' → Block 102' → Block 103' → Block 104'

Events in old Block 101/102/103 are now INVALID!
```

**Why it matters:**
```
Scenario: You receive 100 DUST in Block 102

Without reorg detection:
- Your wallet shows: "Balance: 100 DUST" ✅
- Reorg happens (Block 102 discarded)
- Your wallet still shows: "Balance: 100 DUST" ❌ (WRONG!)
- You try to spend it → Transaction fails (funds don't exist!)

With reorg detection:
- Your wallet shows: "Balance: 100 DUST" ✅
- Reorg happens
- Detector notices Block 102 hash doesn't match
- Rolls back to Block 100
- Re-syncs from Block 101'
- Your wallet shows: "Balance: 0 DUST" ✅ (CORRECT!)
```

**Interface:**
```kotlin
interface ReorgDetector {
    suspend fun recordBlock(block: BlockInfo, parentHash: String): ReorgEvent?

    suspend fun getReorgEvents(): Flow<ReorgEvent>
}

data class ReorgEvent(
    val oldHeight: Long,      // Height where reorg detected
    val newChainHash: String, // Hash of new canonical block
    val rollbackTo: Long      // Need to rollback to this height
)
```

---

### 🛠️ ReorgDetectorImpl (Implementation)

**Role:** Actually detects reorgs by tracking block hashes

**Location:** `reorg/ReorgDetectorImpl.kt`

**How it works:**
```kotlin
class ReorgDetectorImpl : ReorgDetector {
    private val blockHashes = mutableMapOf<Long, String>()

    override suspend fun recordBlock(block: BlockInfo, parentHash: String): ReorgEvent? {
        val expectedParent = blockHashes[block.height - 1]

        if (expectedParent != null && expectedParent != parentHash) {
            // Parent hash doesn't match! Reorg detected!
            return ReorgEvent(
                oldHeight = block.height,
                newChainHash = block.hash,
                rollbackTo = findCommonAncestor(block.height)
            )
        }

        // Normal block, record it
        blockHashes[block.height] = block.hash
        return null
    }
}
```

**Detection Strategy:**
1. Store hash of every block we see
2. When new block arrives, check parent hash matches previous block
3. If mismatch → reorg detected!
4. Find common ancestor (last valid block)
5. Rollback local data to that point
6. Re-sync forward with new chain

---

## Part 5: The Verification Layer - Data Integrity

### ✅ EventVerifier (Interface)

**Role:** Verifies event signatures are valid

**Location:** `verification/EventVerifier.kt`

**Think of it as:** The bouncer checking IDs - ensures data is legitimate

**Why verify?**
- Indexer could be compromised
- Network could have man-in-the-middle
- Verify data came from real Midnight blockchain

**Interface:**
```kotlin
interface EventVerifier {
    suspend fun verify(event: RawLedgerEvent): Boolean
}
```

---

### 🔓 PlaceholderEventVerifier (Implementation)

**Role:** Placeholder that always returns true (for now)

**Location:** `verification/PlaceholderEventVerifier.kt`

**Current Status:** ⚠️ Not yet implemented (Phase 5)

**What it does now:**
```kotlin
class PlaceholderEventVerifier : EventVerifier {
    override suspend fun verify(event: RawLedgerEvent): Boolean {
        return true  // Trust indexer for now
    }
}
```

**Future Implementation:**
```kotlin
class CryptographicEventVerifier : EventVerifier {
    override suspend fun verify(event: RawLedgerEvent): Boolean {
        // 1. Parse event signature
        val signature = extractSignature(event.raw)

        // 2. Get validator public key
        val validatorPubKey = getValidatorPublicKey(event.blockHeight)

        // 3. Verify signature
        return cryptoVerify(
            message = event.raw,
            signature = signature,
            publicKey = validatorPubKey
        )
    }
}
```

---

## Part 6: The Model Layer - Data Structures

### 📋 RawLedgerEvent

**Role:** Represents a single blockchain event

**Location:** `model/RawLedgerEvent.kt`

**Structure:**
```kotlin
data class RawLedgerEvent(
    val id: Long,        // Event ID (sequential)
    val raw: String,     // Raw event data (JSON)
    val maxId: Long      // Highest known event ID (for sync progress)
)
```

**Example:**
```json
{
  "id": 12345,
  "raw": "{\"type\":\"transaction\",\"hash\":\"0x123...\",\"utxos\":[...]}",
  "maxId": 50000
}
```

---

### 🧱 BlockInfo

**Role:** Metadata about a blockchain block

**Location:** `model/BlockInfo.kt`

**Structure:**
```kotlin
data class BlockInfo(
    val height: Long,       // Block number (0, 1, 2, ...)
    val hash: String,       // Block hash (unique identifier)
    val timestamp: Long,    // When block was created (Unix timestamp)
    val parentHash: String? = null  // Hash of previous block (for reorg detection)
)
```

**Example:**
```kotlin
BlockInfo(
    height = 123456,
    hash = "0xabc123...",
    timestamp = 1705483200,
    parentHash = "0xdef456..."
)
```

---

### 🌐 NetworkState

**Role:** Current state of the blockchain network

**Location:** `model/NetworkState.kt`

**Structure:**
```kotlin
data class NetworkState(
    val currentBlock: Long,  // Latest block height we've synced
    val maxBlock: Long,      // Latest block height on network
    val chainId: String      // Which network (testnet-02, preview, mainnet)
)
```

**Usage:**
```kotlin
val networkState = indexerClient.getNetworkState()

if (networkState.currentBlock < networkState.maxBlock) {
    println("Behind by ${networkState.maxBlock - networkState.currentBlock} blocks")
    // Start syncing...
}
```

---

## Part 7: How Everything Works Together

### Scenario 1: App First Launch (Full Sync)

```
┌─────────────────┐
│  App Launches   │
└────────┬────────┘
         │
         ↓
┌─────────────────────────────────────────┐
│ ViewModel calls IndexerClient            │
└────────┬────────────────────────────────┘
         │
         ↓
┌──────────────────────────────────────────────────┐
│ IndexerClient.getNetworkState()                  │
│ → Queries indexer: "What's the latest block?"    │
│ ← Response: currentBlock=0, maxBlock=123,456     │
└────────┬─────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ SyncStateManager.updateProgress(0, 123456)        │
│ → UI shows: "Syncing 0%"                          │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ IndexerClient.getEventsInRange(0, 10000)          │
│ → HTTP query: Get first 10,000 events             │
│ ← Response: 10,000 events                         │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ For each event:                                    │
│   1. EventVerifier.verify(event)                  │
│   2. EventCache.saveEvent(event)                  │
│   3. Process event (extract UTXOs, update balance)│
│   4. SyncStateManager.updateProgress(eventId, max)│
└────────┬──────────────────────────────────────────┘
         │
         ↓ (repeat for next 10,000 events)
         │
         ↓
┌───────────────────────────────────────────────────┐
│ All 123,456 events processed                      │
│ SyncStateManager.isFullySynced() = true           │
│ → UI shows: "Wallet synced ✅"                    │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ Connect WebSocket for real-time updates           │
│ GraphQLWebSocketClient.connect()                  │
│ → Subscribe to new transactions                   │
└───────────────────────────────────────────────────┘
```

---

### Scenario 2: App Restart (Quick Sync)

```
┌─────────────────┐
│  App Launches   │
└────────┬────────┘
         │
         ↓
┌─────────────────────────────────────────┐
│ EventCache.getLatestEventId()           │
│ → Response: 123,456 (from last session) │
└────────┬────────────────────────────────┘
         │
         ↓
┌──────────────────────────────────────────────────┐
│ IndexerClient.getNetworkState()                  │
│ → Response: currentBlock=123456, maxBlock=123500 │
│ → Only 44 new blocks since last time!            │
└────────┬─────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ IndexerClient.getEventsInRange(123456, 123500)   │
│ → Quick sync: Only 44 blocks                      │
│ → Takes 1 second instead of 5 minutes!            │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ UI shows balance immediately (from cached data)   │
│ → User sees: "Balance: 1,000 DUST" ✅             │
└───────────────────────────────────────────────────┘
```

---

### Scenario 3: Receiving Money (Real-Time)

```
Someone sends you 50 DUST
         │
         ↓
Transaction confirmed in Block 123,501
         │
         ↓
Indexer processes new block
         │
         ↓
┌───────────────────────────────────────────────────┐
│ Indexer pushes via WebSocket                      │
│ → Frame: {"type":"next","payload":{...}}          │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ GraphQLWebSocketClient receives frame             │
│ → parseMessage()                                  │
│ → handleMessage() routes to subscription          │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ IndexerClient.subscribeToZswapEvents()            │
│ → Flow emits new event                            │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ ViewModel processes event                         │
│ → Extract UTXO: +50 DUST                          │
│ → Update local database                           │
│ → Recalculate balance: 1000 + 50 = 1050           │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ UI updates automatically                          │
│ → Shows: "Balance: 1,050 DUST ✅"                 │
│ → Notification: "Received 50 DUST"                │
└───────────────────────────────────────────────────┘

Total time: <1 second from transaction to UI update!
```

---

### Scenario 4: Blockchain Reorg Detected

```
Normal sync (Block 123,500 → 123,501)
         │
         ↓
┌───────────────────────────────────────────────────┐
│ ReorgDetector.recordBlock(Block 123,501)          │
│ → Expected parent: 0xABC...                       │
│ → Actual parent: 0xDEF... ❌                      │
│ → MISMATCH! Reorg detected!                       │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ ReorgEvent emitted                                │
│ → oldHeight: 123,501                              │
│ → rollbackTo: 123,499                             │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ Rollback local data                               │
│ → EventCache.deleteAfter(123,499)                 │
│ → UtxoDatabase.rollbackToBlock(123,499)           │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ Re-sync from rollback point                       │
│ → IndexerClient.getEventsInRange(123499, 123502) │
│ → Process events on new canonical chain           │
└────────┬──────────────────────────────────────────┘
         │
         ↓
┌───────────────────────────────────────────────────┐
│ Balance recalculated with correct chain           │
│ → Old: 1,050 DUST (included invalid tx)          │
│ → New: 1,000 DUST (invalid tx removed) ✅        │
└───────────────────────────────────────────────────┘
```

---

## Part 8: Class Responsibility Summary

| Class | Package | Role | Status |
|-------|---------|------|--------|
| **IndexerClient** | `api` | Interface for indexer operations | ✅ Complete |
| **IndexerClientImpl** | `api` | HTTP + WebSocket communication | 🔄 In Progress (WebSocket subscriptions) |
| **RetryPolicy** | `api` | Retry failed requests | ✅ Complete |
| **TlsConfiguration** | `api` | Secure connections | ✅ Complete |
| **IndexerException** | `api` | Custom exception types | ✅ Complete |
| **GraphQLWebSocketClient** | `websocket` | WebSocket connection manager | ✅ Complete |
| **GraphQLWebSocketMessage** | `websocket` | Protocol message types | ✅ Complete |
| **EventCache** | `storage` | Interface for local storage | ✅ Complete |
| **InMemoryEventCache** | `storage` | Temporary in-memory cache | ✅ Complete |
| **SyncStateManager** | `storage` | Track sync progress | ✅ Complete |
| **EventEntity** | `storage` | Database entity | ✅ Complete |
| **ReorgDetector** | `reorg` | Interface for reorg detection | ✅ Complete |
| **ReorgDetectorImpl** | `reorg` | Detects blockchain reorgs | ✅ Complete |
| **EventVerifier** | `verification` | Interface for signature checking | ✅ Complete |
| **PlaceholderEventVerifier** | `verification` | Placeholder (always returns true) | ⚠️ TODO: Real crypto verification |
| **RawLedgerEvent** | `model` | Event data structure | ✅ Complete |
| **BlockInfo** | `model` | Block metadata | ✅ Complete |
| **NetworkState** | `model` | Network sync state | ✅ Complete |

---

## Part 9: What's Missing (Phase 4B Completion)

### Still TODO:

1. **WebSocket Subscription Wrappers**
```kotlin
// Need to add to IndexerClient:
fun subscribeToUnshieldedTransactions(address: String): Flow<UnshieldedTransaction>
fun subscribeToShieldedTransactions(sessionId: String): Flow<ShieldedTransaction>
```

2. **UTXO Database (Room)**
```kotlin
@Entity
data class UnshieldedUtxoEntity(...)

@Dao
interface UnshieldedUtxoDao {
    suspend fun getUnspentUtxos(address: String): List<UnshieldedUtxoEntity>
}
```

3. **Balance Calculator**
```kotlin
class BalanceCalculator(dao: UnshieldedUtxoDao) {
    suspend fun getBalance(address: String): Map<String, BigInteger>
}
```

4. **Transaction Model Classes**
```kotlin
data class UnshieldedTransaction(
    transaction: Transaction,
    createdUtxos: List<UnshieldedUtxo>,
    spentUtxos: List<UnshieldedUtxo>
)
```

---

## Part 10: How to Use the Indexer Module

### In Your ViewModel:

```kotlin
class WalletViewModel(
    private val indexerClient: IndexerClient,
    private val syncManager: SyncStateManager
) : ViewModel() {

    fun loadWallet(address: String) = viewModelScope.launch {
        // 1. Get network state
        val networkState = indexerClient.getNetworkState()

        // 2. Sync historical data
        syncHistoricalData(address, networkState.maxBlock)

        // 3. Subscribe to real-time updates
        subscribeToNewTransactions(address)
    }

    private suspend fun syncHistoricalData(address: String, maxBlock: Long) {
        // Get events in batches
        for (fromBlock in 0..maxBlock step 10000) {
            val events = indexerClient.getEventsInRange(fromBlock, fromBlock + 10000)

            events.forEach { event ->
                processEvent(event)
                syncManager.updateProgress(event.id, maxBlock)
            }
        }
    }

    private fun subscribeToNewTransactions(address: String) {
        indexerClient.subscribeToUnshieldedTransactions(address)
            .onEach { transaction ->
                processTransaction(transaction)
                updateBalance()
            }
            .launchIn(viewModelScope)
    }
}
```

---

## Summary: The Mental Model

**Think of the indexer module as:**

🏢 **A Bank Branch** connecting you to the Midnight blockchain

- **IndexerClient** = Bank teller (handles your requests)
- **GraphQLWebSocketClient** = Direct phone line (instant notifications)
- **EventCache** = Your transaction history records
- **SyncStateManager** = Progress tracker ("Downloading statements... 45%")
- **ReorgDetector** = Fraud prevention (catches when history changes)
- **EventVerifier** = Signature verification (ensures authenticity)

**Data Flow:**
```
Blockchain → Indexer Server → IndexerClient → Your Wallet → UI
```

**Two Modes:**
1. **Sync Mode** (HTTP): Download past history
2. **Live Mode** (WebSocket): Receive real-time updates

**Key Principle:** Trust but verify
- Download data from indexer (fast)
- Verify signatures (secure)
- Cache locally (efficient)
- Detect reorgs (safe)

---

**Now you understand the entire indexer module! 🎉**

Any questions about specific classes or flows?
