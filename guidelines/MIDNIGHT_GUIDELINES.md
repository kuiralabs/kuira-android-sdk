# Midnight-Specific Implementation Guidelines
**Version:** 1.0.0 - LIVING DOCUMENT
**Last Updated:** January 10, 2026

**Purpose:** Patterns and requirements specific to Midnight blockchain integration.

**Related Guidelines:**
- For general security patterns → See `SECURITY_GUIDELINES.md`
- For Kotlin implementation → See `KOTLIN_GUIDELINES.md`
- For testing Midnight features → See `TESTING_GUIDELINES.md`

---

## ⚠️ Important: This is a Learning Document

**Confidence Levels:**
- ✅ **Verified** - We've implemented and tested this pattern
- 🔄 **Hypothesis** - Inferred from TypeScript SDK review, needs validation
- ❓ **Unknown** - We don't know yet, needs investigation

**Source:** These patterns are based on our analysis of the the Midnight TypeScript SDK TypeScript SDK, NOT official Midnight documentation. We will update this file as we implement and learn.

**Approach:**
- Start with hypotheses from SDK review
- Test during implementation
- Update with real findings
- Document what works (and what doesn't)

---

## Memory Management (🔄 Hypothesis)

**Source:** Observed in TypeScript SDK examples
**Confidence:** Medium - Saw the pattern, haven't implemented yet
**Needs Verification:** Does Kotlin port require same cleanup? What happens if we skip?

### ALWAYS Clear HDWallet

```kotlin
// ✅ CORRECT: Always clear in finally block
val hdWallet = HDWallet.fromSeed(seed)
try {
    val keys = hdWallet.selectAccount(0).deriveKeysAt(0)
    // use keys for derivation
    return keys.publicKey
} finally {
    hdWallet.clear()  // CRITICAL - wipes private key material from memory
}

// ❌ WRONG: No cleanup
val hdWallet = HDWallet.fromSeed(seed)
val keys = hdWallet.selectAccount(0).deriveKeysAt(0)
return keys.publicKey  // Private keys remain in memory!
```

### ALWAYS Clear ZswapSecretKeys

```kotlin
// ✅ CORRECT: Clear after use
val zswapKeys = ZswapSecretKeys.fromSeed(seed)
try {
    val tx = createShieldedTransaction(zswapKeys)
    return tx
} finally {
    zswapKeys.clear()  // CRITICAL - wipes proving keys
}

// ❌ WRONG: No cleanup
val zswapKeys = ZswapSecretKeys.fromSeed(seed)
return createShieldedTransaction(zswapKeys)  // Keys leak!
```

**Why this is critical:**
- Midnight uses cryptographic secrets that must not persist in memory
- `HDWallet` contains BIP-32 private keys
- `ZswapSecretKeys` contains ZK proving keys (nullifier secrets, spend keys)
- Android garbage collection is NOT immediate
- Memory dumps could expose private keys
- **ALWAYS use `try-finally` pattern for cleanup**

**📝 Update After Phase 1 Implementation:**
- [ ] Verify HDWallet.clear() exists in Kotlin port
- [ ] Test memory wiping effectiveness
- [ ] Document any differences from TypeScript implementation
- [ ] Add real code examples from our implementation

---

## Transaction TTL Management (🔄 Hypothesis)

**Source:** Found Intent.new(ttl) pattern in TypeScript SDK
**Confidence:** Medium - Understand the concept, haven't tested timing
**Needs Verification:** What's the optimal TTL? How does cleanup actually work?

### Set Reasonable TTL

```kotlin
// ✅ CORRECT: Set 10-minute TTL
fun createTransaction(): Transaction {
    val ttl = Date(System.currentTimeMillis() + 10.minutes.inWholeMilliseconds)
    return Intent.new(ttl)
}

// ✅ CORRECT: Account for network delays
fun createTransaction(): Transaction {
    // Give enough time for user confirmation + network submission
    val ttl = Date(System.currentTimeMillis() + 15.minutes.inWholeMilliseconds)
    return Intent.new(ttl)
}

// ❌ WRONG: TTL too short
fun createTransaction(): Transaction {
    val ttl = Date(System.currentTimeMillis() + 1.minutes.inWholeMilliseconds)
    // User might not confirm in time!
}
```

### Cleanup Expired Pending UTXOs

```kotlin
// ✅ CORRECT: Rollback expired transactions
suspend fun cleanupExpiredPending() {
    val now = Date()
    val expired = pendingUtxos.filter { it.ttl < now }

    expired.forEach { utxo ->
        // Mark UTXO as Available again
        rollbackUtxo(utxo)
        log.info("Rolled back expired UTXO: ${utxo.id}")
    }
}

// ✅ CORRECT: Run cleanup periodically
suspend fun startPeriodicCleanup() {
    while (isActive) {
        cleanupExpiredPending()
        delay(1.minutes)
    }
}
```

**Why TTL matters:**
- Transactions have expiration times (TTL)
- After TTL, transaction can't be included in blockchain
- If transaction expires, UTXOs remain "pending" but aren't actually spent
- Must rollback pending UTXOs to Available state
- Otherwise: balance appears lower than reality

**TTL lifecycle:**
```
UTXO: Available
  ↓ (Create transaction with TTL)
UTXO: Pending
  ↓ (Transaction confirmed OR TTL expired)
UTXO: Spent (if confirmed) OR Available (if expired)
```

**📝 Update After Phase 2 Implementation:**
- [ ] Test different TTL durations (5min, 10min, 15min)
- [ ] Measure actual transaction confirmation times
- [ ] Verify rollback mechanism works correctly
- [ ] Document real-world TTL recommendations

---

## Sync Before Operations (🔄 Hypothesis)

**Source:** Found waitForSyncedState() in WalletFacade
**Confidence:** High - This pattern makes sense, but need to verify sync behavior
**Needs Verification:** How long does sync take? What if sync fails?

### Wait for Synced State

```kotlin
// ✅ CORRECT: Wait for sync before transacting
suspend fun sendTransaction(recipient: String, amount: BigInteger) {
    // Wait for wallet to sync with blockchain
    val synced = wallet.waitForSyncedState()

    // Now safe to transact with accurate state
    val tx = wallet.makeTransfer(recipient, amount)
    return tx
}

// ❌ WRONG: Transact without waiting for sync
fun sendTransaction(recipient: String, amount: BigInteger) {
    val tx = wallet.makeTransfer(recipient, amount)
    // Might use stale state! Could double-spend!
}
```

### Monitor Sync Progress

```kotlin
// ✅ CORRECT: Show sync progress to user
@Composable
fun WalletScreen(viewModel: WalletViewModel) {
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()

    when (syncState) {
        is SyncState.Syncing -> {
            CircularProgressIndicator()
            Text("Syncing: ${syncState.progress}%")
        }
        is SyncState.Synced -> {
            WalletContent(...)
        }
        is SyncState.Error -> {
            ErrorMessage(syncState.error)
        }
    }
}
```

**Why sync is critical:**
- Wallet state (balance, UTXOs) comes from blockchain
- Indexer provides state updates
- Operating on stale state can cause:
  - Insufficient balance errors
  - Double-spend attempts
  - Transaction rejection
- **ALWAYS sync before:**
  - Creating transactions
  - Checking balances
  - Displaying transaction history

**📝 Update After Phase 4 Implementation:**
- [ ] Measure actual sync times on testnet
- [ ] Test sync failure scenarios
- [ ] Verify indexer integration behavior
- [ ] Document offline mode handling (if supported)

---

## UTXO State Management (🔄 Hypothesis)

**Source:** Inferred from SDK state management patterns
**Confidence:** Medium - Logical model, but need to verify with real implementation
**Needs Verification:** Does SDK handle this for us, or do we manage it?

### Track UTXO Lifecycle

```kotlin
// ✅ CORRECT: Explicit UTXO state tracking
sealed class UtxoState {
    object Available : UtxoState()
    data class Pending(val ttl: Date) : UtxoState()
    object Spent : UtxoState()
}

data class Utxo(
    val id: String,
    val value: BigInteger,
    val owner: String,
    val state: UtxoState
)

// Mark UTXO as pending when creating transaction
fun createTransaction(utxos: List<Utxo>) {
    utxos.forEach { utxo ->
        require(utxo.state is UtxoState.Available) {
            "Cannot use non-available UTXO: ${utxo.id}"
        }
        utxo.copy(state = UtxoState.Pending(ttl))
    }
}
```

### Coin Selection

```kotlin
// ✅ CORRECT: Only select Available UTXOs
fun selectCoins(target: BigInteger): Result<List<Utxo>> {
    val availableUtxos = allUtxos.filter {
        it.state is UtxoState.Available
    }

    // Use largest-first strategy to minimize transaction size
    val selected = availableUtxos
        .sortedByDescending { it.value }
        .takeWhileAccumulating { it.value >= target }

    return if (selected.sumOf { it.value } >= target) {
        Result.success(selected)
    } else {
        Result.failure(InsufficientFundsError(target, selected.sumOf { it.value }))
    }
}
```

**📝 Update After Phase 2 Implementation:**
- [ ] Test coin selection strategies (largest-first vs smallest-first)
- [ ] Verify UTXO state transitions work correctly
- [ ] Measure privacy implications of different selection strategies
- [ ] Document actual UTXO lifecycle from implementation

---

## Key Derivation Paths (✅ High Confidence)

**Source:** Found in SDK documentation and code
**Confidence:** High - Standard HD derivation path is documented
**Needs Verification:** Confirm 2400' is correct coin type

### Midnight HD Derivation

```kotlin
// Midnight uses: m/44'/2400'/account'/role/index

// ✅ CORRECT: Derive keys with proper roles
enum class KeyRole(val index: Int) {
    NIGHT_EXTERNAL(0),     // Public transactions (account 0)
    NIGHT_INTERNAL(1),     // Change addresses (account 0)
    DUST(2),               // Dust wallet
    ZSWAP(3),              // Shielded transactions
    METADATA(4)            // Metadata encryption
}

fun deriveAddress(
    hdWallet: HDWallet,
    account: Int = 0,
    role: KeyRole = KeyRole.NIGHT_EXTERNAL,
    index: Int = 0
): String {
    val keys = hdWallet
        .selectAccount(account)
        .selectRole(role.index)
        .deriveKeysAt(index)

    return keys.toAddress()
}
```

**Role usage:**
- **NIGHT_EXTERNAL (0):** Receiving addresses (show to others)
- **NIGHT_INTERNAL (1):** Change addresses (internal use only)
- **DUST (2):** Dust wallet (unshielded balance)
- **ZSWAP (3):** Shielded wallet (private balance)
- **METADATA (4):** Transaction metadata encryption

**📝 Update After Phase 1 Implementation:**
- [ ] Verify derivation path with test vectors
- [ ] Confirm coin type 2400' is correct
- [ ] Test address derivation matches official SDK
- [ ] Document any quirks in role usage

---

## Proof Server Integration (❓ Unknown)

**Source:** Mentioned in SDK, protocol unclear
**Confidence:** Low - We know it exists, don't know the API details
**Needs Verification:** What's the actual API? Binary format? Authentication?

### Binary HTTP Protocol

```kotlin
// ✅ CORRECT: Binary proof request
suspend fun generateProof(tx: ShieldedTransaction): Result<Proof> {
    val proofRequest = serializeProofRequest(tx)

    val response = httpClient.post(proofServerUrl) {
        contentType(ContentType.Application.OctetStream)
        setBody(proofRequest)
    }

    return if (response.status.isSuccess()) {
        val proof = response.readBytes()
        Result.success(Proof(proof))
    } else {
        Result.failure(ProofGenerationError(response.status.description))
    }
}
```

### Handle Proof Generation Delays

```kotlin
// ✅ CORRECT: Show progress, handle timeouts
suspend fun generateProofWithProgress(
    tx: ShieldedTransaction,
    onProgress: (Int) -> Unit
): Result<Proof> {
    return withTimeout(60.seconds) {  // Proof generation can take 10-30 seconds
        try {
            onProgress(10)
            val proof = proofClient.generateProof(tx)
            onProgress(100)
            Result.success(proof)
        } catch (e: TimeoutCancellationException) {
            Result.failure(ProofTimeoutError())
        }
    }
}
```

**Proof server considerations:**
- Proof generation is **compute-intensive** (10-30 seconds)
- Show progress indicator to user
- Handle network timeouts gracefully
- User must provide proof server URL (self-hosted or shared)
- Binary protocol (not JSON)

**📝 Update After Phase 3 Implementation:**
- [ ] Document actual proof server API (endpoint, request format, response format)
- [ ] Measure real proof generation times
- [ ] Test with actual proof server (self-hosted or provided)
- [ ] Document authentication requirements (if any)
- [ ] Add error handling for common failure modes

---

## Midnight-Specific Checklist

Before any Midnight operation:

- [ ] HDWallet cleared in `finally` block
- [ ] ZswapSecretKeys cleared in `finally` block
- [ ] Wallet synced before transaction
- [ ] TTL set appropriately (10-15 minutes)
- [ ] Expired pending UTXOs cleaned up
- [ ] Only Available UTXOs selected for coin selection
- [ ] Correct key role used for derivation
- [ ] Proof server timeout handled
- [ ] Transaction validated before submission
- [ ] User shown sync progress

---

## Common Midnight Pitfalls

### ❌ Forgetting to clear keys

```kotlin
// BAD - Memory leak
fun deriveAddress(seed: ByteArray): String {
    val hdWallet = HDWallet.fromSeed(seed)
    return hdWallet.selectAccount(0).deriveKeysAt(0).toAddress()
    // hdWallet never cleared!
}
```

### ❌ Operating on stale state

```kotlin
// BAD - Might use old balance
fun getBalance(): BigInteger {
    return wallet.getBalance()  // No sync check!
}
```

### ❌ Not rolling back expired transactions

```kotlin
// BAD - UTXOs stuck in Pending state
fun createTransaction() {
    val utxos = selectCoins()
    markAsPending(utxos)
    // If transaction expires, UTXOs never become Available again!
}
```

### ❌ Using wrong key role

```kotlin
// BAD - Using external key for internal change
fun generateChangeAddress(hdWallet: HDWallet): String {
    return deriveAddress(hdWallet, role = KeyRole.NIGHT_EXTERNAL)
    // Should use NIGHT_INTERNAL for change!
}
```

---

## How to Use This Document

### During Implementation:
1. **Start with hypotheses** - Use this as a starting point, not gospel
2. **Question everything** - If something seems wrong, it probably is
3. **Test assumptions** - Write tests to verify each pattern
4. **Document findings** - Update this file with real results

### After Each Phase:
1. **Review relevant sections** - Which patterns did we encounter?
2. **Update confidence levels** - Change 🔄 to ✅ or add ❌ if wrong
3. **Add real code examples** - Replace hypothetical code with actual implementation
4. **Document surprises** - What did we learn that wasn't expected?

### Updating Confidence Levels:
- **🔄 → ✅ Verified** - Pattern works as expected, we've tested it
- **🔄 → ❌ Incorrect** - Pattern was wrong, document what actually works
- **❓ → 🔄 Hypothesis** - We've learned more, have a theory to test
- **❓ → ✅ Verified** - We figured it out and confirmed it works

**Remember:** This is a learning journal, not a rulebook. Be honest about what we know and what we're still figuring out.
