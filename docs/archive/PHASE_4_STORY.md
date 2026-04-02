# Phase 4: The Complete Story - How Balance Tracking Works

**A narrative guide to understanding wallet balance tracking on Midnight blockchain**

---

## The Big Picture: What Are We Building?

You open your Kuira wallet app on Android. You see:
- **Unshielded balance:** 1,000 DUST tokens
- **Shielded balance:** 500 DUST tokens (private)
- Recent transactions scrolling below

**Question:** How does your wallet know your balance?

**The Challenge:** Unlike traditional banking where you call an API (`GET /balance`), blockchain wallets must **reconstruct their balance** by tracking all transactions on the blockchain.

---

## Act 1: Understanding Blockchain Wallets

### Traditional Banking (Centralized)
```
You → Bank Server → Database → "Your balance is $1,000"
```
The bank **stores** your balance in their database. Simple.

### Blockchain (Decentralized)
```
You → Blockchain → All Transactions Ever → "Calculate your own balance"
```
The blockchain only stores **transactions**. Nobody stores "balances". You must calculate it yourself by:
1. Finding all transactions that gave you coins (UTXOs)
2. Finding all transactions where you spent coins
3. Math: `Balance = Received - Spent`

---

## Act 2: What is a UTXO?

**UTXO = Unspent Transaction Output**

Think of cash in your wallet:
- You have a $10 bill, a $20 bill, and three $5 bills
- Your "balance" is $45, but you don't have a single "$45 bill"
- You have **5 separate pieces of money** that add up to $45

**That's how blockchain works:**
- Transaction 1 gave you 100 DUST → You have UTXO #1 (100 DUST)
- Transaction 2 gave you 50 DUST → You have UTXO #2 (50 DUST)
- Transaction 3 gave you 200 DUST → You have UTXO #3 (200 DUST)
- **Your balance:** 100 + 50 + 200 = 350 DUST

**When you spend:**
- You spend UTXO #1 (100 DUST) → It's now "spent"
- **Your new balance:** 50 + 200 = 250 DUST (only unspent UTXOs count)

---

## Act 3: Two Types of Wallets

### Full Node Wallet (Heavy)
- Downloads the **entire blockchain** (gigabytes)
- Scans every single transaction ever made
- Finds your transactions by checking every UTXO
- **Accurate but slow and resource-intensive**

Example: Bitcoin Core, Ethereum Geth

### Light Wallet (Our Approach)
- Doesn't download the blockchain
- Asks an **indexer server**: "Give me transactions for my address"
- Tracks only **your UTXOs** locally
- **Fast and mobile-friendly**

Example: MetaMask, Trust Wallet, **Kuira (our app)**

---

## Act 4: Enter the Indexer

**Midnight Indexer = A server that has already scanned the entire blockchain**

```
Midnight Blockchain (raw blocks)
    ↓
Indexer Server (organizes data, provides API)
    ↓
Your Wallet App (queries indexer)
```

**What the indexer does:**
1. Watches the blockchain 24/7
2. Indexes all transactions by address
3. Provides GraphQL API: "Give me transactions for address X"
4. Provides WebSocket subscriptions: "Tell me when new transactions arrive for address X"

**Why we need it:** Your mobile phone can't download gigabytes of blockchain data and scan millions of transactions. The indexer does the heavy lifting.

---

## Act 5: Phase 4A vs Phase 4B

### Phase 4A: The Sync Engine (Historical Data)
**Purpose:** Download transaction history when you first open your wallet

```
User opens wallet
  ↓
"What's my balance?"
  ↓
Query indexer: "Give me ALL past transactions for my address"
  ↓
Indexer responds with 50 transactions
  ↓
Process each transaction:
  - Transaction 1: +100 DUST (UTXO created)
  - Transaction 5: -100 DUST (UTXO spent)
  - Transaction 12: +200 DUST (UTXO created)
  ...
  ↓
Store UTXOs in local Room database
  ↓
Calculate balance: Sum of unspent UTXOs = 350 DUST
  ↓
Display to user: "350 DUST"
```

**Phase 4A Status:** ✅ Complete (83 tests passing)
- Can query indexer for past transactions
- Can process transaction ranges
- Can handle network state queries

**BUT:** Phase 4A alone is NOT enough! It only gets **past** transactions. What about **new** transactions?

### Phase 4B: Real-Time Updates (WebSocket Subscriptions)
**Purpose:** Get notified immediately when new transactions arrive

```
Wallet is open, showing 350 DUST balance
  ↓
Someone sends you 50 DUST
  ↓
WebSocket subscription fires
  ↓
"New transaction received!"
  ↓
Process new transaction: +50 DUST
  ↓
Update local database: Add new UTXO
  ↓
Recalculate balance: 350 + 50 = 400 DUST
  ↓
Update UI: "400 DUST" ← User sees balance change in real-time!
```

**Phase 4B Status:** 🔄 In Progress
- ✅ WebSocket connection working (just fixed!)
- ⏳ Need to implement subscription wrappers
- ⏳ Need to create UTXO database
- ⏳ Need to implement balance calculator

---

## Act 6: The Complete Flow - From Wallet Open to Balance Display

Let's trace the **entire journey** when you open Kuira wallet:

### Step 1: Wallet Initialization
```kotlin
// User opens app
WalletViewModel.init()
  ↓
// Generate address from mnemonic (Phase 1 - Crypto)
val address = "mn_addr_testnet1abc123..."
```

### Step 2: Historical Sync (Phase 4A)
```kotlin
// Query indexer for past transactions
val pastTransactions = indexerClient.getEventsInRange(
    address = address,
    fromBlock = 0,
    toBlock = currentBlock
)

// Process 50 historical transactions
pastTransactions.forEach { tx ->
    // Created UTXOs (received money)
    tx.createdUtxos.forEach { utxo ->
        database.insert(
            UnshieldedUtxoEntity(
                id = "${utxo.owner}_${utxo.intentHash}_${utxo.outputIndex}",
                owner = address,
                value = "100", // 100 DUST
                spentAtTx = null // unspent
            )
        )
    }

    // Spent UTXOs (sent money)
    tx.spentUtxos.forEach { utxo ->
        database.markAsSpent(
            id = utxo.id,
            txHash = tx.hash
        )
    }
}
```

### Step 3: Subscribe to New Transactions (Phase 4B)
```kotlin
// Open WebSocket connection
wsClient.connect() // ← Just fixed this!

// Subscribe to new transactions
wsClient.subscribe("""
    subscription {
        unshieldedTransactions(
            address: "$address",
            transactionId: $lastProcessedId
        ) {
            ... on UnshieldedTransaction {
                transaction { id, hash }
                createdUtxos {
                    owner
                    tokenType
                    value
                }
                spentUtxos {
                    owner
                    tokenType
                    value
                }
            }
        }
    }
""").collect { event ->
    // New transaction arrived!
    processTransaction(event)
    updateBalance()
}
```

### Step 4: Calculate and Display Balance
```kotlin
// Query local database for unspent UTXOs
val unspentUtxos = database.getUnspentUtxos(address)
// Result: [
//   UnshieldedUtxoEntity(value="100", spentAtTx=null),
//   UnshieldedUtxoEntity(value="50", spentAtTx=null),
//   UnshieldedUtxoEntity(value="200", spentAtTx=null)
// ]

// Calculate balance
val balance = unspentUtxos
    .groupBy { it.tokenType } // Group by token type (DUST, etc)
    .mapValues { (_, utxos) ->
        utxos.sumOf { BigInteger(it.value) }
    }
// Result: { "DUST" -> 350 }

// Display in UI
_balanceState.value = BalanceState.Success(balance = "350 DUST")
```

### Step 5: Real-Time Update
```
[30 seconds later, someone sends you 50 DUST]

WebSocket fires:
  ↓
processTransaction(newTx) {
    // Add new UTXO to database
    database.insert(
        UnshieldedUtxoEntity(
            value = "50",
            spentAtTx = null
        )
    )
}
  ↓
updateBalance() {
    val newBalance = database.getUnspentUtxos(address)
        .sumOf { BigInteger(it.value) }
    // newBalance = 400 DUST

    _balanceState.value = BalanceState.Success(balance = "400 DUST")
}
  ↓
UI updates automatically (Compose observes StateFlow)
  ↓
User sees: "400 DUST" ← Balance changed in real-time!
```

---

## Act 7: Why WebSockets? Why Not Just Poll?

### Polling Approach (Bad)
```kotlin
while (true) {
    delay(5000) // Wait 5 seconds
    val transactions = indexerClient.getLatestTransactions(address)
    // Process transactions
}
```

**Problems:**
- ❌ Wastes battery (constant HTTP requests)
- ❌ Wastes bandwidth (99% of requests return "no new data")
- ❌ Delayed updates (5-second lag)
- ❌ Server load (thousands of wallets polling)

### WebSocket Approach (Good)
```kotlin
wsClient.subscribe(query).collect { transaction ->
    // Server pushes updates only when something happens
}
```

**Benefits:**
- ✅ Battery efficient (single persistent connection)
- ✅ Bandwidth efficient (only sends data when needed)
- ✅ Instant updates (server pushes immediately)
- ✅ Scalable (server pushes to thousands of clients efficiently)

---

## Act 8: The GraphQL-WS Protocol

### What We're Actually Sending Over WebSocket

**1. Connection Initialization**
```json
Client → Server: {"type": "connection_init", "payload": null}
Server → Client: {"type": "connection_ack"}
```

**2. Subscribe to Transactions**
```json
Client → Server: {
    "type": "subscribe",
    "id": "sub_1",
    "payload": {
        "query": "subscription { unshieldedTransactions(address: \"...\") { ... } }"
    }
}
```

**3. Receive Transaction Events**
```json
Server → Client: {
    "type": "next",
    "id": "sub_1",
    "payload": {
        "data": {
            "unshieldedTransactions": {
                "transaction": { "hash": "0x123..." },
                "createdUtxos": [
                    { "owner": "mn_addr_...", "value": "50" }
                ]
            }
        }
    }
}
```

**4. Subscription Complete**
```json
Server → Client: {"type": "complete", "id": "sub_1"}
```

**5. Keep-Alive**
```json
Client ↔ Server: {"type": "ping"} / {"type": "pong"}
```

---

## Act 9: The Room Database - Our Local UTXO Store

### Why We Need a Local Database

**Without database:**
```
App restarts
  ↓
"What's my balance?"
  ↓
Query indexer for ALL transactions (slow!)
  ↓
Process 1,000 transactions every time (slow!)
  ↓
User waits 10 seconds to see balance ❌
```

**With database:**
```
App restarts
  ↓
"What's my balance?"
  ↓
Query local Room database (instant!)
  ↓
Sum unspent UTXOs from SQLite (fast!)
  ↓
User sees balance immediately ✅
```

### The Database Schema

```kotlin
@Entity(tableName = "unshielded_utxos")
data class UnshieldedUtxoEntity(
    @PrimaryKey
    val id: String, // "mn_addr_xxx_0x123_0"

    val owner: String, // "mn_addr_testnet1abc..."
    val tokenType: String, // "DUST" (hex-encoded)
    val value: String, // "100" (BigInteger as string)

    val intentHash: String, // "0x123..." (transaction ID)
    val outputIndex: Int, // 0, 1, 2... (UTXO index in transaction)

    val createdAtBlock: Long, // Block number when created
    val createdAtTx: String, // Transaction hash

    val spentAtBlock: Long?, // null if unspent
    val spentAtTx: String? // null if unspent
)
```

### The DAO (Data Access Object)

```kotlin
@Dao
interface UnshieldedUtxoDao {
    // Get all unspent UTXOs for an address (for balance calculation)
    @Query("SELECT * FROM unshielded_utxos WHERE owner = :address AND spentAtTx IS NULL")
    suspend fun getUnspentUtxos(address: String): List<UnshieldedUtxoEntity>

    // Insert new UTXOs (when receiving transactions)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUtxos(utxos: List<UnshieldedUtxoEntity>)

    // Mark UTXOs as spent (when sending transactions)
    @Query("UPDATE unshielded_utxos SET spentAtTx = :txHash, spentAtBlock = :blockHeight WHERE id IN (:ids)")
    suspend fun markAsSpent(ids: List<String>, txHash: String, blockHeight: Long)
}
```

---

## Act 10: The Balance Calculator

```kotlin
class BalanceCalculator(private val utxoDao: UnshieldedUtxoDao) {

    suspend fun getBalance(address: String): Map<String, BigInteger> {
        // Step 1: Get all unspent UTXOs
        val unspentUtxos = utxoDao.getUnspentUtxos(address)
        // [
        //   UnshieldedUtxoEntity(tokenType="DUST", value="100"),
        //   UnshieldedUtxoEntity(tokenType="DUST", value="50"),
        //   UnshieldedUtxoEntity(tokenType="USDC", value="1000")
        // ]

        // Step 2: Group by token type
        val grouped = unspentUtxos.groupBy { it.tokenType }
        // {
        //   "DUST" -> [UTXO(100), UTXO(50)],
        //   "USDC" -> [UTXO(1000)]
        // }

        // Step 3: Sum values for each token
        return grouped.mapValues { (_, utxos) ->
            utxos.sumOf { BigInteger(it.value) }
        }
        // {
        //   "DUST" -> BigInteger(150),
        //   "USDC" -> BigInteger(1000)
        // }
    }

    suspend fun getBalanceForToken(
        address: String,
        tokenType: String
    ): BigInteger {
        val balances = getBalance(address)
        return balances[tokenType] ?: BigInteger.ZERO
    }
}
```

---

## Act 11: Shielded vs Unshielded

### Unshielded Transactions (Public)
- **Like Bitcoin:** Everyone can see who sent what to whom
- **Address:** `mn_addr_testnet1abc...`
- **Privacy:** None - all transactions visible
- **Subscription:** Simple - just provide address

```kotlin
subscription {
    unshieldedTransactions(address: "mn_addr_testnet1abc...") {
        createdUtxos { owner, value }
        spentUtxos { owner, value }
    }
}
```

### Shielded Transactions (Private)
- **Like Zcash:** Encrypted transactions, amounts hidden
- **Address:** Uses viewing key to decrypt
- **Privacy:** Full - nobody can see amounts or recipients
- **Subscription:** Requires viewing key

```kotlin
// Step 1: Connect with viewing key
mutation {
    connect(viewingKey: "vk_abc...")
    // Returns sessionId: "session_xyz"
}

// Step 2: Subscribe using sessionId
subscription {
    shieldedTransactions(sessionId: "session_xyz") {
        createdNotes { value }
        spentNotes { value }
    }
}

// Step 3: Disconnect when done
mutation {
    disconnect(sessionId: "session_xyz")
}
```

**Why the complexity?** Shielded transactions are encrypted on the blockchain. The indexer can't decrypt them without your viewing key. The `connect` mutation gives the indexer temporary access to decrypt transactions for your subscription.

---

## Act 12: Phase 4 - Complete Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      Kuira Android App                       │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌────────────┐         ┌────────────┐                       │
│  │ Wallet UI  │◄────────┤ ViewModel  │                       │
│  │ (Compose)  │         │ (Balance   │                       │
│  │            │         │  Display)  │                       │
│  └────────────┘         └─────┬──────┘                       │
│                               │                               │
│                               ▼                               │
│                     ┌─────────────────┐                       │
│                     │ BalanceCalculator│                       │
│                     └────────┬─────────┘                       │
│                              │                                │
│                              ▼                                │
│               ┌──────────────────────────┐                    │
│               │   Room Database (SQLite)  │                    │
│               │  ┌────────────────────┐  │                    │
│               │  │ UnshieldedUtxoDao  │  │                    │
│               │  │  getUnspentUtxos() │  │                    │
│               │  │  insertUtxos()     │  │                    │
│               │  │  markAsSpent()     │  │                    │
│               │  └────────────────────┘  │                    │
│               └──────────┬───────────────┘                    │
│                          ▲                                    │
│                          │                                    │
│          ┌───────────────┴────────────────┐                   │
│          │                                │                   │
│          │                                │                   │
│  ┌───────▼────────┐              ┌────────▼─────────┐         │
│  │  Phase 4A:     │              │  Phase 4B:       │         │
│  │  Sync Engine   │              │  WebSocket       │         │
│  │                │              │  Subscriptions   │         │
│  │  getEventsInRange()           │  subscribe()     │         │
│  │  getNetworkState()            │  connect()       │         │
│  └───────┬────────┘              └────────┬─────────┘         │
│          │                                │                   │
│          └────────────┬───────────────────┘                   │
│                       │                                       │
│                       ▼                                       │
│            ┌──────────────────────┐                           │
│            │   IndexerClient      │                           │
│            │  ┌────────────────┐  │                           │
│            │  │ HTTP Client    │  │ (GraphQL queries)         │
│            │  │ (Ktor)         │  │                           │
│            │  └────────────────┘  │                           │
│            │  ┌────────────────┐  │                           │
│            │  │ WebSocket      │  │ (GraphQL subscriptions)   │
│            │  │ Client (Ktor)  │  │                           │
│            │  └────────────────┘  │                           │
│            └──────────┬───────────┘                           │
└────────────────────────┼──────────────────────────────────────┘
                         │
                         │ Internet
                         │
                         ▼
        ┌────────────────────────────────┐
        │   Midnight Indexer Server      │
        │   indexer.preview.midnight.    │
        │        network                 │
        ├────────────────────────────────┤
        │ HTTP:  /api/v3/graphql         │
        │ WebSocket: /api/v3/graphql/ws  │
        └────────────┬───────────────────┘
                     │
                     ▼
        ┌────────────────────────────────┐
        │    Midnight Blockchain         │
        │    (Testnet / Preview)         │
        │                                │
        │  Blocks → Transactions →       │
        │  UTXOs → Smart Contracts       │
        └────────────────────────────────┘
```

---

## Act 13: The Complete User Experience

### Scenario: Alice Opens Her Wallet

**1. App Launch (First Time)**
```
[Alice opens Kuira wallet]

App: "Loading your wallet..."

Background:
  ├─ Generate address from mnemonic (Phase 1)
  ├─ Query indexer: getNetworkState() (Phase 4A)
  │   └─ Response: currentBlock = 1,234,567
  ├─ Query indexer: getEventsInRange(address, 0, 1234567) (Phase 4A)
  │   └─ Processing 150 historical transactions...
  │   └─ Inserting UTXOs to database...
  │   └─ Marking spent UTXOs...
  ├─ Calculate balance from database
  │   └─ Balance = 1,000 DUST
  └─ Connect WebSocket subscription (Phase 4B)
      └─ Listening for new transactions...

App: "Balance: 1,000 DUST ✅"
```

**2. Receiving Money (Real-Time)**
```
[Bob sends Alice 100 DUST]

Bob's Wallet:
  └─ Creates transaction on blockchain
      └─ Block 1,234,568 contains: Alice receives 100 DUST

Indexer:
  ├─ Sees new block 1,234,568
  ├─ Scans transactions
  ├─ Finds transaction for Alice's address
  └─ Pushes via WebSocket ───────┐
                                 │
Alice's Wallet: ◄────────────────┘
  ├─ WebSocket receives new transaction
  ├─ Processes transaction:
  │   └─ Insert new UTXO (100 DUST, unspent)
  ├─ Recalculates balance:
  │   └─ 1,000 + 100 = 1,100 DUST
  └─ Updates UI

Alice sees: "Balance: 1,100 DUST ✅"
           [New transaction notification! +100 DUST]
```

**3. App Restart (Fast Resume)**
```
[Alice closes app, reopens 1 hour later]

App: "Loading your wallet..."

Background:
  ├─ Load UTXOs from local database (instant!)
  │   └─ Balance = 1,100 DUST
  ├─ Query indexer: getEventsInRange(1234568, 1234600) (Phase 4A)
  │   └─ Only 32 new blocks (fast sync)
  │   └─ Found 2 new transactions
  │   └─ Update database
  │   └─ New balance = 1,250 DUST
  └─ Reconnect WebSocket subscription (Phase 4B)

App: "Balance: 1,250 DUST ✅"

[Total load time: ~2 seconds instead of re-syncing everything]
```

**4. Sending Money**
```
[Alice sends 50 DUST to Charlie]

Alice's Wallet:
  ├─ Select UTXOs to spend:
  │   └─ UTXO #5 (100 DUST) - big enough to cover 50 DUST + fee
  ├─ Create transaction (Phase 2 - will implement later):
  │   ├─ Input: UTXO #5 (100 DUST)
  │   ├─ Output 1: Charlie receives 48 DUST (recipient)
  │   ├─ Output 2: Alice receives 50 DUST (change back to self)
  │   └─ Fee: 2 DUST
  ├─ Sign transaction with private key
  └─ Broadcast to blockchain

Blockchain:
  └─ Transaction confirmed in block 1,234,601

WebSocket fires:
  ├─ New transaction detected!
  ├─ Process transaction:
  │   ├─ Mark UTXO #5 as spent (100 DUST spent)
  │   └─ Add new UTXO #123 (50 DUST change, unspent)
  ├─ Recalculate balance:
  │   └─ 1,250 - 100 + 50 = 1,200 DUST
  └─ Update UI

Alice sees: "Balance: 1,200 DUST ✅"
           [Transaction sent! -50 DUST to Charlie]
```

---

## Act 14: What We've Built So Far

### Phase 4A: Sync Engine ✅ (Complete)
```kotlin
// These work NOW:
indexerClient.getNetworkState()
  // Returns: NetworkState(currentBlock=1234567, chainId="midnight-testnet-02")

indexerClient.getEventsInRange(address, fromBlock, toBlock)
  // Returns: List of historical transactions

indexerClient.subscribeToZswapEvents()
  // Returns: Flow of ZSwap DEX events
```

**What we CAN'T do yet:**
- ❌ No local UTXO database (data not persisted)
- ❌ No balance calculator (can't compute balance)
- ❌ No real-time updates (no WebSocket subscriptions)

### Phase 4B: WebSocket Client ✅ (Just Fixed!)
```kotlin
// This works NOW:
wsClient.connect()
  // Connects to wss://indexer.preview.midnight.network/api/v3/graphql/ws
  // Sends: {"type": "connection_init", "payload": null}
  // Receives: {"type": "connection_ack"}
  // ✅ Connection successful!

wsClient.subscribe(query)
  // Can subscribe to GraphQL operations
  // Returns: Flow<JsonElement>
```

**What we CAN'T do yet:**
- ❌ No subscription wrappers (raw JSON, not type-safe)
- ❌ No IndexerClient integration (can't use from ViewModel)
- ❌ No UTXO processing (can't update database from subscriptions)

---

## Act 15: What's Next - Remaining Work

### Task 1: Create UTXO Database (3-4 hours)
```kotlin
// Files to create:
@Entity
data class UnshieldedUtxoEntity(...)

@Dao
interface UnshieldedUtxoDao {
    suspend fun getUnspentUtxos(address: String): List<UnshieldedUtxoEntity>
    suspend fun insertUtxos(utxos: List<UnshieldedUtxoEntity>)
    suspend fun markAsSpent(ids: List<String>, txHash: String)
}

@Database
abstract class UtxoDatabase : RoomDatabase() {
    abstract fun unshieldedUtxoDao(): UnshieldedUtxoDao
}
```

### Task 2: Implement Balance Calculator (1-2 hours)
```kotlin
class BalanceCalculator(private val dao: UnshieldedUtxoDao) {
    suspend fun getBalance(address: String): Map<String, BigInteger> {
        return dao.getUnspentUtxos(address)
            .groupBy { it.tokenType }
            .mapValues { (_, utxos) ->
                utxos.sumOf { BigInteger(it.value) }
            }
    }
}
```

### Task 3: Add Subscription Wrappers to IndexerClient (2-3 hours)
```kotlin
interface IndexerClient {
    // Add these methods:
    fun subscribeToUnshieldedTransactions(
        address: String
    ): Flow<UnshieldedTransaction>

    fun subscribeToShieldedTransactions(
        sessionId: String
    ): Flow<ShieldedTransaction>

    suspend fun connect(viewingKey: String): String // returns sessionId
    suspend fun disconnect(sessionId: String)
}
```

### Task 4: Create Transaction Model Classes (1-2 hours)
```kotlin
data class UnshieldedTransaction(
    val transaction: Transaction,
    val createdUtxos: List<UnshieldedUtxo>,
    val spentUtxos: List<UnshieldedUtxo>
)

data class UnshieldedUtxo(
    val owner: String,
    val tokenType: String,
    val value: String,
    val intentHash: String,
    val outputIndex: Int
)
```

### Task 5: Wire Everything Together (2-3 hours)
```kotlin
class WalletViewModel(
    private val indexerClient: IndexerClient,
    private val balanceCalculator: BalanceCalculator,
    private val utxoDao: UnshieldedUtxoDao
) {
    fun loadWallet(address: String) = viewModelScope.launch {
        // 1. Sync historical transactions
        val pastTxs = indexerClient.getEventsInRange(address, 0, currentBlock)
        pastTxs.forEach { processTransaction(it) }

        // 2. Calculate and display balance
        val balance = balanceCalculator.getBalance(address)
        _balanceState.value = BalanceState.Success(balance)

        // 3. Subscribe to new transactions
        indexerClient.subscribeToUnshieldedTransactions(address)
            .collect { tx ->
                processTransaction(tx)
                val newBalance = balanceCalculator.getBalance(address)
                _balanceState.value = BalanceState.Success(newBalance)
            }
    }

    private suspend fun processTransaction(tx: UnshieldedTransaction) {
        // Insert created UTXOs
        utxoDao.insertUtxos(tx.createdUtxos.map { it.toEntity() })

        // Mark spent UTXOs
        utxoDao.markAsSpent(
            ids = tx.spentUtxos.map { it.id },
            txHash = tx.transaction.hash
        )
    }
}
```

---

## Summary: The Complete Picture

**What Phase 4 Does:** Enables your Android wallet to track cryptocurrency balances by:

1. **Historical Sync (4A):** Downloads past transactions from indexer
2. **Real-Time Updates (4B):** Subscribes to new transactions via WebSocket
3. **Local Storage:** Saves UTXOs in Room database for fast access
4. **Balance Calculation:** Sums unspent UTXOs to show current balance

**Why It's Complex:** Because blockchain wallets must reconstruct state from transaction history, unlike traditional apps that query a "balance" API.

**Why It's Worth It:** Once complete, users get:
- ✅ Instant balance display on app open
- ✅ Real-time updates when receiving money
- ✅ Works offline (shows last known balance)
- ✅ Privacy preserved (no centralized server knows your balance)

**Current Status:**
- ✅ HTTP queries working (Phase 4A)
- ✅ WebSocket connection working (Phase 4B partial)
- ⏳ Need database, balance calculator, subscriptions (Phase 4B remainder)

**Next Steps:** Implement Tasks 1-5 above to complete Phase 4B.

---

**Questions? Ask about any part of this flow!**
