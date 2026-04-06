# Kuira Wallet - Implementation Plan

**Project:** Midnight Wallet for Android — Agent-Native, Privacy-First
**Total Invested:** ~280h across completed phases
**Status:** Phase 1-5 ✅ | **Phase 6 (Android DApp SDK) → In Progress (6J-online ✅, 6K remaining)**

**Last Updated:** April 6, 2026

## Implementation Strategy

**Completed infrastructure:**
1. ✅ **Phase 1**: Crypto foundation (41h)
2. ✅ **Phase 4A**: Sync engine (21h)
3. ✅ **Phase 4B**: WebSocket + UTXO tracking (23.5h)
4. ✅ **Phase 2**: Unshielded transactions (81h)
5. ✅ **Phase 2F.1**: Dust tank (~12h)
6. ✅ **Phase 4B-Shielded**: Shielded balance tracking (~14h)
7. ✅ **Phase 3**: Shielded transactions (~22h) — E2E working on localnet
8. ✅ **Phase 4C**: Local ZK proving (~20h) — 1.7s proof on emulator, no proof server needed

9. ✅ **Phase 5**: DApp Connector (~30h) — ConnectedAPI, WebSocket, IPC, approval UI, 4 transport layers, bboard example
**Next:**
10. ⏭️ **Phase 6**: Android DApp SDK — QuickJS + Rust FFI for contract execution on mobile
11. ⏭️ **Phase 7**: Agent Runtime — on-chain agent authorization via Compact contracts
12. ⏭️ **Phase 8**: Production Polish — onboarding, settings, app store readiness

---

## Phase Structure

| Phase | Goal | Actual | Status |
|-------|------|--------|--------|
| **Phase 1: Crypto Foundation** | Key derivation & addresses | 41h | ✅ Complete |
| **Phase 4A: Full Sync Engine** | Event cache, reorg, balance calc | 21h | ✅ Complete |
| **Phase 4B: WebSocket + UTXO** | Subscriptions, local UTXO database | 23.5h | ✅ Complete |
| **Phase 2: Unshielded Tx** | Send/receive transparent tokens | 81h | ✅ Complete |
| **Phase 2F.1: Dust Tank** | Dust registration + display | ~12h | ✅ Complete |
| **Phase 4B-Shielded** | Shielded balance tracking (zswap events) | ~14h | ✅ Complete |
| **Phase 3: Shielded Tx** | Private ZK transactions E2E | ~22h | ✅ Complete |
| **Phase 4C: Local Proving** | On-phone ZK proofs (no proof server) | ~20h | ✅ Complete |
| **Phase 5: DApp Connector** | ConnectedAPI + transports + approval UI | ~30h | ✅ Complete |
| **Phase 6: Android DApp SDK** | Contract execution on mobile (QuickJS + Rust) | est 40-60h | 🔄 6K remaining |
| **Phase 7: Agent Runtime** | On-chain agent authorization | est 20-30h | ⏸️ Planned |
| **Phase 8: Production Polish** | Onboarding, settings, app store | est 15-20h | ⏸️ Planned |

**Invested:** ~280h | **Remaining:** ~75-110h to full production

### Phase 5 Summary (Complete)

98 unit tests + 32 device tests. See `docs/planning/DAPP_CONNECTOR_PLAN.md`.

**Delivered:**
- ConnectedAPIHandler — all 17 ConnectedAPI methods
- JSON-RPC 2.0 router with approval gate
- WebSocket server on localhost:9932
- 4 transport layers: WebSocket, Bound Service, WebView JS Bridge, Deep Link
- B&W approval UI with animated entrance (stars, KUIRA materialize)
- Cross-app IPC via Android Messenger (dApp-initiated approval)
- Foreground service with notification
- `core:designsystem` module (theme + effects)
- BBoard Android example (IPC-based dApp scaffold)

### Phase 6 Sub-Steps (see `docs/planning/ANDROID_DAPP_SDK_PLAN.md`)

| Step | Goal | Status |
|------|------|--------|
| 6A | Rust FFI foundation — `persistentHash`, `contract_query` via JNI | ✅ |
| 6B | Embed QuickJS in Android (quickjs-kt 1.0.3) | ✅ |
| 6C | Onchain-runtime shim — IIFE bundle replacing WASM with Rust FFI | ✅ |
| 6D | Run compiled bboard contract in QuickJS | ✅ |
| 6E | Witness bridge — Kotlin↔JS callbacks for private inputs | ✅ |
| 6F | Replace JS fallbacks with Rust FFI (all crypto/encoding native) | ✅ |
| 6G | Proof preimage → UnprovenTransaction pipeline | ✅ |
| 6H | Provider interfaces + CircuitExecutor clean API | ✅ |
| 6I | Private state (AES-256-GCM) + ZK config (ProvingKeyManager) | ✅ |
| 6J | BBoard e2e: execute → prove → balance → submit on localnet | ✅ |
| 6K-DX | Developer API (`MidnightContract.call()`) + testing | ⏭️ |

### Key Architecture Decisions

- **ADR-001**: Composable FFI primitives (enables `balanceUnsealedTransaction`, `makeIntent`)
- **ADR-002**: Local ZK proving (enables `getProvingProvider` without proof server)
- **Phase 5 investigation**: `docs/planning/PHASE_5_CONTRACT_TRANSACTIONS_INVESTIGATION.md`

### SDK Strategy (Multi-Platform)

The connector serves multiple client types:
- **Agents (TS)**: WebSocket JSON-RPC — same as CLI `mn serve`
- **Android native**: Direct Kotlin API (in-process)
- **iOS (future)**: Swift SDK
- **React Native (future)**: TS/JS with native modules — shares code with agents

---

## Phase 1: Crypto Foundation ✅ COMPLETE (41h)

**Goal:** Derive keys and addresses compatible with Lace wallet
**Status:** ✅ Both sub-phases complete, 90 unit tests + 24 Android tests passing

### 1A: Unshielded Crypto ✅ COMPLETE (30h actual / 20-25h estimate)

**Deliverables:**
- ✅ BIP-39 mnemonic generation (12/15/18/21/24 words)
- ✅ BIP-32 HD key derivation at `m/44'/2400'/account'/role/index`
- ✅ Midnight roles: NightExternal(0), NightInternal(1), Dust(2), Zswap(3), Metadata(4)
- ✅ Unshielded address: `SHA-256(publicKey)` → Bech32m encoding
- ✅ 74 tests passing

**Libraries:**
- BitcoinJ for BIP-39/32 (proven Android compatibility)
- Custom Bech32m implementation (ported from Midnight SDK)

**Files:**
```
core/crypto/
├── bip39/
│   ├── BIP39.kt                    # Interface
│   ├── MnemonicService.kt          # Implementation
│   └── BitcoinJMnemonicService.kt  # BitcoinJ wrapper
├── bip32/
│   ├── HDWallet.kt                 # HD key derivation
│   ├── MidnightKeyRole.kt          # Role enum
│   └── DerivedKey.kt               # Key wrapper
└── address/
    └── Bech32m.kt                  # Address encoding
```

### 1B: Shielded Keys ✅ COMPLETE (11h actual / 10-15h estimate)

**Why JNI?**
- Shielded keys use JubJub curve (ZK-friendly, complex crypto)
- Reimplementing in Kotlin = high risk for wallet
- Bridge to Rust FFI = use Midnight's battle-tested code (98% confidence)

**Deliverables:**
- ✅ Derive shielded coin public key (32 bytes)
- ✅ Derive shielded encryption public key (32 bytes)
- ✅ JNI bridge: Kotlin → C → Rust FFI → midnight-zswap v6.1.0-alpha.5
- ✅ Cross-compile for ARM64, ARM32, x86_64, x86
- ✅ 24 Android integration tests passing
- ✅ Keys match Midnight SDK v6.1.0-alpha.6

**Files:**
```
core/crypto/src/main/kotlin/.../shielded/
├── ShieldedKeys.kt              # Data class (coin_pk, enc_pk)
├── MemoryUtils.kt               # Secure memory wiping
└── ShieldedKeyDeriver.kt        # JNI wrapper

rust/kuira-crypto-ffi/
├── src/lib.rs                   # Rust FFI (derive_shielded_keys)
├── jni/kuira_crypto_jni.c       # JNI C glue (Step 2)
└── build-android.sh             # Cross-compile script (Step 2)
```

**Test Vector:** (For validation)
```
Mnemonic: "abandon abandon ... art" (24 words)
Path: m/44'/2400'/0'/3/0
Expected Coin PK: 274c79e90fdf0e29468299ff624dc7092423041ba3976b76464feae3a07b994a
```

**Critical:** Must use midnight-zswap v6.1.0-alpha.5 (matches Lace wallet SDK)

---

## Phase 4A-Full: Full Sync Engine ✅ COMPLETE (21h actual / 8-11h estimate)

**Goal:** Full wallet sync infrastructure (event caching, reorg detection, balance calculation)
**Status:** ✅ Complete - 118 tests passing
**Note:** Built as optional/advanced feature (over-engineered for mobile)

**What We Built:**
This is a **full wallet sync engine** suitable for:
- Privacy mode (don't reveal addresses to indexer)
- Offline transaction building (local UTXO set)
- Desktop applications
- Advanced users

**Deliverables:**
- ✅ GraphQL HTTP client (Ktor)
- ✅ Event caching with LRU eviction
- ✅ Blockchain reorg detection (shallow + deep)
- ✅ Balance calculator from events
- ✅ Thread-safe storage with Mutex
- ✅ Retry policy with exponential backoff
- ✅ Comprehensive error handling
- ✅ Input validation on all models
- ✅ 118 tests passing (100% pass rate)

**Why This is Optional:**
For mobile wallet balance viewing, we don't need to:
- Sync all blockchain events
- Store thousands of events locally
- Calculate balances from events
- Handle blockchain reorgs

We just need to query the indexer: "What's the balance for this address?"

**Decision:** Keep this code as "advanced feature" for future privacy mode or desktop app.

---

## Phase 4B: WebSocket + UTXO Tracking ✅ COMPLETE (23.5h actual / 25-35h estimate)

**Goal:** Real-time transaction subscriptions + local UTXO database for balance calculation
**Status:** ✅ **COMPLETE** - Unshielded balance tracking working end-to-end

**⚠️ IMPORTANT LIMITATION:** Only UNSHIELDED balances implemented. Shielded balance tracking deferred (see "Missing: Shielded Balances" section below).

**Critical Discovery:**
Midnight's indexer does NOT provide simple balance query APIs like `getUnshieldedBalance(address)`. Light wallets must:
1. Subscribe to transaction events via WebSocket (GraphQL-WS protocol)
2. Track UTXOs locally in Room database
3. Calculate balances by summing unspent UTXOs

This is the ONLY way to view balances in a Midnight wallet.

### 4B-1: WebSocket Client ✅ COMPLETE (8h actual)

**Status:** ✅ WebSocket connection working, GraphQL-WS protocol implemented
**Test Results:** 87 tests total, 0 failures (4 integration tests marked @Ignore for manual execution)

**Deliverables:**
- ✅ GraphQL-WS protocol implementation (8 message types)
- ✅ Connection lifecycle (ConnectionInit → ConnectionAck)
- ✅ Subscribe/Next/Complete/Error handling
- ✅ Ping/Pong keepalive
- ✅ Thread-safe connection state (AtomicBoolean)
- ✅ Subscription management (concurrent map)
- ✅ Auto-increment operation IDs (AtomicInteger)
- ✅ Proper sub-protocol header (`Sec-WebSocket-Protocol: graphql-transport-ws`)
- ✅ JSON encoding with defaults (`encodeDefaults = true`)

**Key Implementation:**
```kotlin
// CRITICAL: Use block parameter for sub-protocol header
session = httpClient.webSocketSession(
    urlString = url,
    block = {
        header(HttpHeaders.SecWebSocketProtocol, "graphql-transport-ws")
    }
)

// CRITICAL: Always encode default values
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true  // Required for type field
}
```

**Lesson Learned:**
> "Please investigate the documentation before making any assumptions!"

Examining Midnight's TypeScript `indexer-client` implementation revealed the exact GraphQL-WS protocol requirements, which led to the successful connection solution.

**Files:**
```
core/indexer/src/main/kotlin/.../websocket/
├── GraphQLWebSocketClient.kt     # WebSocket client
├── GraphQLWebSocketMessage.kt    # 8 message types
└── SubscriptionFlow.kt           # Flow-based subscriptions
```

**Documentation Created:**
- `docs/learning/WEBSOCKET_SOLUTION.md` - Complete troubleshooting guide
- `docs/learning/PHASE_4_STORY.md` - End-to-end architecture explanation
- `docs/learning/KTOR_WEBSOCKET_CRASH_COURSE.md` - Ktor/channels/atomics deep dive
- `docs/learning/WEBSOCKET_FRAMES_EXPLAINED.md` - WebSocket frames from first principles
- `docs/learning/CHANNEL_VS_FLOW.md` - Channel vs Flow explanation
- `docs/learning/INDEXER_MODULE_BIG_PICTURE.md` - Complete indexer architecture

### 4B-2: UTXO Database + Subscriptions ✅ COMPLETE (2.5h actual / 10h estimate)

**Goal:** Subscribe to transactions and track UTXOs locally
**Status:** ✅ Complete - 157 tests passing (100% pass rate)

**Completed Deliverables:**
- ✅ Subscription methods in IndexerClient
  - `subscribeToUnshieldedTransactions(address, transactionId?): Flow<UnshieldedTransactionUpdate>`
  - GraphQL query refactoring (extracted to GraphQLQueries.kt)
- ✅ Room database for UTXO tracking
  - `UnshieldedUtxoEntity` (intentHash, outputIndex, value, owner, tokenType, spent)
  - `TokenBalanceEntity` (aggregated balance view)
  - `UnshieldedUtxoDao` (insert, update, query, delete operations)
  - `KuiraDatabase` v2 (migration from v1)
- ✅ Transaction model classes
  - `UnshieldedTransactionUpdate` (sealed class: Transaction | Progress)
  - `TransactionDetails` (id, hash, fees, result, timestamp)
  - `UnshieldedUtxo` (value, owner, tokenType, intentHash, outputIndex)
- ✅ UTXO state management
  - `UnshieldedBalanceManager` (WebSocket → Database bridge)
  - Mark UTXOs as spent when consumed
  - Track confirmed vs pending UTXOs
  - Transaction replay from `transactionId` (catch-up after offline)
  - UTXO deduplication (composite primary key: intentHash + outputIndex)

**Tests:** 157 passing
- UnshieldedUtxoDaoTest: 31 tests
- UnshieldedBalanceManagerTest: 122 tests
- GraphQLWebSocketClientTest: 4 integration tests (live testnet)

**GraphQL Subscriptions:**
```graphql
subscription UnshieldedTransactions($address: String!) {
  unshieldedTransactions(address: $address) {
    txHash
    inputs { txHash, index, amount, tokenType }
    outputs { index, amount, tokenType, address }
    timestamp
  }
}

subscription ShieldedTransactions($sessionId: String!) {
  shieldedTransactions(sessionId: $sessionId) {
    commitments
    nullifiers
    timestamp
  }
}
```

**Files:**
```
core/indexer/src/main/kotlin/.../api/
└── IndexerClientImpl.kt          # Add subscription wrappers

core/indexer/src/main/kotlin/.../model/
├── UnshieldedTransaction.kt      # Transaction models
├── ShieldedTransaction.kt
└── Utxo.kt

core/indexer/src/main/kotlin/.../database/
├── UtxoDatabase.kt               # Room database
├── UnshieldedUtxoDao.kt          # CRUD operations
├── ShieldedUtxoDao.kt
├── UnshieldedUtxoEntity.kt       # Database entities
└── ShieldedUtxoEntity.kt
```

### 4B-3: Balance Repository ✅ COMPLETE (6h actual / ~3h estimate)

**Goal:** Repository layer for UI consumption (aggregate balances, expose Flows)
**Status:** ✅ Complete - BalanceViewModel with 69 tests, 93.3% method coverage
**Duration:** January 18, 2026

**Completed Deliverables:**

#### Repository Layer (From Phase 4B-2)
- ✅ `BalanceRepository` - Aggregate balances from database
  - `observeBalances(address): Flow<List<TokenBalance>>` - All tokens
  - `observeTokenBalance(address, tokenType): Flow<TokenBalance?>` - Single token
  - `observeTotalBalance(address): Flow<Long>` - Sum across all tokens
  - Group by token type and calculate totals
  - Sort by largest balance first (UX optimization)
  - Singleton pattern (@Inject @Singleton)

#### ViewModel Layer (TODAY)
- ✅ `BalanceViewModel` - State management for balance screen
  - Observes balances from BalanceRepository (reactive updates)
  - Transforms domain models to UI models (BalanceDisplay)
  - Handles loading/error states (BalanceUiState sealed class)
  - Tracks last updated timestamp with live formatting ("2 min ago" → "3 min ago")
  - Pull-to-refresh support (flatMapLatest pattern, single collection)
  - Address validation (blank check, mn_ prefix)
  - User-friendly error messages (network, timeout, database)
  - Memory leak prevention (job cancellation on multiple loads)

- ✅ `BalanceUiState` - Sealed class for UI states
  - Loading(isRefreshing: Boolean) - Initial load or pull-to-refresh
  - Success(balances, lastUpdated, totalBalance) - Display data
  - Error(message, throwable) - User-friendly error

#### Blockchain Sync Integration (TODAY)
- ✅ Hilt DI Module (`IndexerModule.kt`)
  - Provides IndexerClient (singleton)
  - Provides SyncStateManager (singleton)
  - Provides SubscriptionManagerFactory (non-singleton)
  - Proper scope annotations and lifecycle management

- ✅ SubscriptionManager Integration
  - BalanceViewModel orchestrates blockchain sync via SubscriptionManager
  - Separate syncState Flow exposes sync progress to UI
  - SyncState transitions: Connecting → Syncing → Synced → Error
  - Automatic sync on loadBalances() and refresh()
  - Progress tracking (processedCount, highestTransactionId)
  - Retry with exponential backoff (handled by SubscriptionManager)
  - Automatic cleanup when ViewModel cleared

#### BalanceFormatter
- ✅ Format amounts for display with decimals
  - "1234567" → "1.234567 TNIGHT"
  - Handles all token types (TNIGHT, DUST, etc.)
  - BigInteger support for financial calculations

#### Comprehensive Testing (TODAY)
- ✅ **69 tests** covering all ViewModel functionality
  - **Method coverage:** 93.3% (14/15 methods)
  - **Line coverage:** 80.7% (67/83 lines)
  - **Branch coverage:** 56.5% (26/46 branches)

**Test Categories:**
- Initial state (1 test)
- Balance loading success (6 tests)
- Balance loading errors (3 tests)
- Empty balances (1 test)
- Refresh behavior (3 tests)
- Multiple token types (2 tests)
- Total balance calculation (2 tests)
- Timestamp persistence (5 tests)
- Memory leak prevention (2 tests)
- Address validation (3 tests)
- Flow state consistency (3 tests)
- Blockchain sync integration (8 tests)
  - Sync job cancellation (2 tests)
  - Sync error handling (2 tests)
  - Concurrent sync and balance updates (1 test)
  - Sync state transitions (3 tests)
- Factory pattern verification (1 test)
- Edge cases (27 tests covering rapid refresh, zero balances, etc.)

**Test Quality Improvements:**
- Fixed 1 critical test that was testing wrong behavior
- Enhanced timing explanations for race conditions
- Renamed 3 misleading test names
- Added explicit factory non-singleton test
- All tests now accurately reflect production code behavior

**Coverage Report:**
- HTML report generated at `htmlReport/index.html`
- All critical paths covered (93.3% of methods)
- Edge cases well-tested (rapid refresh, concurrent updates, etc.)
- Production-ready quality

**Files Created:**
```
core/indexer/src/main/kotlin/.../
├── repository/
│   └── BalanceRepository.kt          # UI-facing repository
├── di/
│   └── IndexerModule.kt              # Hilt DI configuration
└── ui/
    └── BalanceFormatter.kt           # Amount formatting

feature/balance/src/main/kotlin/.../
├── BalanceViewModel.kt               # State management (306 lines)
├── BalanceUiState.kt                 # UI state sealed class
└── BalanceDisplay.kt                 # Display model

feature/balance/src/test/kotlin/.../
└── BalanceViewModelTest.kt           # 69 comprehensive tests (1195 lines)
```

**Documentation Created:**
```
docs/
├── TEST_COVERAGE_ANALYSIS.md         # Coverage gap analysis
├── TEST_QUALITY_REVIEW.md            # Detailed quality audit
└── COVERAGE_REPORT_SUMMARY.md        # Coverage metrics breakdown
```

### 4B-4: UI Integration ✅ COMPLETE (7h actual / ~5-8h estimate)

**Goal:** Display balances to user
**Status:** ✅ Complete - January 18, 2026

**⚠️ LIMITATION:** Only displays UNSHIELDED balances. Shielded balance UI not implemented.

**Completed Deliverables:**
- [x] Balance screen (Jetpack Compose)
- [x] Display unshielded address & balance (multiple tokens)
- [x] Pull-to-refresh gesture
- [x] "Last updated X min ago" timestamp with live updates
- [x] Loading states (skeleton screens)
- [x] Error handling UI (offline, network errors)
- [x] Total balance calculation across all tokens
- [ ] ~~Display shielded address & balance~~ **DEFERRED** (not implemented)
- [ ] ~~Copy address button~~ **DEFERRED** (not critical for MVP)

**Test Coverage:**
- 69 tests for BalanceViewModel
- 93.3% method coverage
- 80.7% line coverage
- All critical paths tested

**Files:**
```
feature/balance/src/main/kotlin/.../
├── BalanceViewModel.kt               # State management (306 lines)
├── BalanceUiState.kt                 # UI state sealed class
├── BalanceDisplay.kt                 # Display model
└── BalanceScreen.kt                  # Composable UI

feature/balance/src/test/kotlin/.../
└── BalanceViewModelTest.kt           # 69 comprehensive tests (1195 lines)
```

---

## ⚠️ Phase 4B-Shielded: Shielded Balance Tracking

**Status:** ⏸️ **NOT STARTED** — Dust Registration complete (Phase 2F.1 ✅), ready to start
**Estimate:** 8-12 hours
**Priority:** HIGH — Required before Phase 3 (Shielded Transactions)

### What Was Built (Phase 4B)
✅ **Unshielded balances ONLY:**
- `subscribeToUnshieldedTransactions(address)` — WebSocket subscription
- `UnshieldedUtxoEntity` — Room database entity
- `UnshieldedUtxoDao` — Database operations
- `UnshieldedBalanceManager` — UTXO tracking
- `BalanceViewModel` — UI state management
- Balance display UI (Compose)

### Why This Matters
**Cannot implement Phase 3 (Shielded Transactions) without this:**
- Need to track shielded UTXOs before spending them
- Need to decrypt shielded notes with encryption keys from Phase 1B
- Need to display shielded balances to test transactions

### Dependency Order

```
Step 1 (Rust FFI) → Step 2 (JNI Bridge) → Step 3 (Kotlin Wrapper)
                                                      ↓
Step 4 (Event Models) ──────────────────────→ Step 6 (ShieldedSyncManager)
Step 5 (Database Layer) ────────────────────→        ↓
                                              Step 7 (ShieldedBalanceRepository)
                                                      ↓
                                              Step 8 (UI Integration)
```

Steps 1→2→3 are sequential (each depends on previous).
Steps 4 and 5 can be done in parallel with Steps 1-3.
Step 6 requires Steps 3, 4, and 5.
Steps 7→8 are sequential after Step 6.

---

### Step 1: Rust FFI — Version-Abstract ZswapLocalState

**Goal:** Expose Midnight's `zswap::local::State` through a stable, version-abstract C FFI layer. The FFI contract is the abstraction boundary — when v8 comes, only the Rust implementation changes, not the function signatures or return formats.

**What to build:**
- New file `rust/kuira-crypto-ffi/src/zswap_ffi.rs`
- Stable FFI contract (these signatures won't change across versions):
  - `create` — empty state
  - `free` — cleanup
  - `serialize` / `deserialize` — persistence (opaque blob, format is Rust's concern)
  - `replay_events` — process blockchain events (takes seed + hex events, returns new state)
  - `get_balances` — query balance (returns JSON, format is the contract)
  - `get_coin_count` / `get_coin_at` — iterate coins
  - `get_state_changes` — returns what changed in last replay (received/spent coins) as JSON
- A test-only helper: `create_test_zswap_output_event(seed, token_type_hex, value)` — generates a valid encrypted output event so Kotlin tests work without a live indexer
- Register module in `lib.rs`

**Version abstraction principle:** Kotlin never sees Rust types. All data crosses the FFI as JSON strings or opaque byte blobs. If v8 changes internals, the JSON schema stays the same.

**Key files:**
- `rust/kuira-crypto-ffi/src/lib.rs` — register the new module
- `rust/kuira-crypto-ffi/Cargo.toml` — depends on `midnight-zswap` v7

---

### Step 2: JNI C Bridge for ZswapLocalState

**Goal:** Create the JNI C interface that marshals Kotlin calls to the Rust FFI.

**What to build:**
- New file: `core/crypto/src/main/cpp/zswap_bridge.c`
- JNI function stubs (one per FFI function): `nativeCreate`, `nativeReplayEvents`, `nativeGetBalances`, `nativeSerialize`, `nativeDeserialize`, `nativeFree`, etc.
- Error handling wrapper: All FFI calls are wrapped with try-catch for FFI panics
- Link in CMakeLists.txt (already configured in Phase 1B; add `zswap_bridge.c` to sources)

**Blocked by:** Step 1

---

### Step 3: Kotlin ZswapLocalState Wrapper + Android Tests

**Goal:** Create a safe, idiomatic Kotlin wrapper that mirrors the `DustLocalState` pattern.

**What to build:**
- `core/crypto/src/main/kotlin/.../shielded/ZswapLocalState.kt`
  - `replayEvents(seed, eventsHex)` → new state
  - `getBalances()` → `Map<String, BigInteger>`
  - `getCoinCount()` → Int
  - `serialize()` / `deserialize()` — persistence
  - `AutoCloseable` pattern for `native_free()` lifecycle
  - Memory safety following `DustLocalState` security policies

**Tests:**
- `ZswapLocalStateTest.kt` — unit tests with mock Rust FFI
- `ZswapLocalStateIntegrationTest.kt` — integration test with real FFI
  - Replay test events, verify balance
  - Serialize/deserialize round-trip
  - Multiple state instances don't interfere

**Blocked by:** Step 2

---

### Step 4: Shielded Event Models + GraphQL Subscription

**Goal:** Define the event data structures and add a GraphQL subscription query for shielded events.

**What to build:**

**Event Model:**
- `core/indexer/src/main/kotlin/.../model/ShieldedEvent.kt`
  - `ShieldedEventUpdate(id: Long, raw: String, maxId: Long)`

**GraphQL Subscription:**
- Add to `GraphQLQueries.kt`:
  ```graphql
  subscription ZswapEvents($id: Int!) {
    zswapLedgerEvents(id: $id) {
      id
      raw
      maxId
    }
  }
  ```

**WebSocket Client Integration:**
- Add `subscribeToZswapEvents(startId: Long): Flow<ShieldedEventUpdate>` to `GraphQLWebSocketClient`
- Reuse existing WebSocket infrastructure from Phase 4B-1

**Key difference from unshielded:**
- Unshielded: filtered by address, returns parsed JSON with readable data
- Shielded: NOT filtered (receives ALL events), returns raw hex bytes (Rust processes entirely)
- The `raw` field is opaque — do NOT parse in Kotlin, pass directly to Rust FFI

**Can be done in parallel with Steps 1-3.**

---

### Step 5: Database Layer — State Persistence

**Goal:** Room entity to store serialized ZswapLocalState so syncing resumes across app restarts.

**What to build:**

**Entity:**
- `core/indexer/src/main/kotlin/.../storage/ShieldedStateEntity.kt`
  - Singleton row (`id = 0`)
  - `serializedState: ByteArray` — result of `state.serialize()`
  - `lastEventId: Long` — last processed event (for resume)
  - `lastUpdated: Long` — timestamp

**DAO:**
- `core/indexer/src/main/kotlin/.../storage/ShieldedStateDao.kt`
  - `getState()`, `updateState()`, `clear()`

**Migration:**
- Add `ShieldedStateEntity` to `KuiraDatabase.kt` entities list
- Create migration from previous version

**Key design decision:** Store the entire serialized state as a blob. Don't normalize — the Rust state is opaque to Kotlin.

**Can be done in parallel with Steps 1-3.**

---

### Step 6: ShieldedSyncManager — Orchestration

**Goal:** Connect WebSocket subscription → event processing → database persistence → balance queries.

**What to build:**
- `core/indexer/src/main/kotlin/.../sync/ShieldedSyncManager.kt`
- Responsibilities:
  1. **Load state:** Deserialize stored `ShieldedStateEntity` to get `lastEventId`
  2. **Subscribe:** Connect WebSocket, subscribe to `zswapLedgerEvents(id: lastEventId + 1)`
  3. **Process:** For each batch of events: collect raw hex → call `state.replayEvents(seed, hexList)` → retrieve changes → serialize new state to database
  4. **Progress tracking:** Emit `SyncProgress` (currentEventId, highestEventId, isConnected)
  5. **Error handling:** On connection loss, retry with exponential backoff; preserve last known state

**Comparison to unshielded:**
- Unshielded: Subscription → parse JSON → UtxoManager → Room entities → BalanceRepository
- Shielded: Subscription → raw hex → Rust FFI → Room blob → ShieldedBalanceRepository

**Blocked by:** Steps 3, 4, and 5

---

### Step 7: ShieldedBalanceRepository

**Goal:** Query balance from Rust state and expose via Flow for the UI layer.

**What to build:**
- `core/indexer/src/main/kotlin/.../repository/ShieldedBalanceRepository.kt`
  - `getBalance()` → `Map<String, BigInteger>` (on-demand from serialized state)
  - `observeBalance()` → `Flow<Map<String, BigInteger>>` (watches database for updates)

**Design decisions:**
- Balance queried on-demand from serialized state (no separate balance table)
- Each query requires deserialization (acceptable — state is small, <1MB)
- Observe pattern uses Room's Flow to detect database changes

**Blocked by:** Step 6

---

### Step 8: UI Integration — Balance Screen

**Goal:** Display shielded balance on the existing Balance screen, with loading and sync progress.

**What to build:**

**ViewModel:**
- `ShieldedBalanceViewModel` (or extend existing `BalanceViewModel`)
  - Observe `ShieldedBalanceRepository.observeBalance()`
  - Expose sync progress from `ShieldedSyncManager`

**Compose UI:**
- Add shielded balance section to existing `BalanceScreen.kt`
- Sync progress indicator (LinearProgressIndicator: currentEventId / highestEventId)
- Loading/error states

**Navigation:**
- Add shielded balance to existing balance screen (not a separate screen)

**Blocked by:** Step 7

---

### Supporting Documentation

- `docs/learning/SHIELDED_BALANCE_DEEP_DIVE.md` — Why shielded is different, key hierarchy, commitments/nullifiers, ZswapLocalState internals
- `docs/learning/SHIELDED_SDK_CODE_REFERENCE.md` — Annotated Rust SDK code (curves, encoding, encryption, Merkle tree, event replay)

### Files Affected

**Rust (New):**
- `rust/kuira-crypto-ffi/src/zswap_ffi.rs`
- `rust/kuira-crypto-ffi/src/lib.rs` (modify)

**C/JNI (New):**
- JNI bridge additions in `rust/kuira-crypto-ffi/jni/kuira_crypto_jni.c`

**Kotlin (New):**
- `core/crypto/.../shielded/ZswapLocalState.kt`
- `core/indexer/.../model/ShieldedEvent.kt`
- `core/indexer/.../storage/ShieldedStateEntity.kt`
- `core/indexer/.../storage/ShieldedStateDao.kt`
- `core/indexer/.../sync/ShieldedSyncManager.kt`
- `core/indexer/.../repository/ShieldedBalanceRepository.kt`

**Kotlin (Modify):**
- `core/indexer/.../graphql/GraphQLQueries.kt` — add subscription
- `core/indexer/.../websocket/GraphQLWebSocketClient.kt` — add method
- `core/indexer/.../database/KuiraDatabase.kt` — add entity + migration
- `feature/balance/.../BalanceScreen.kt` — add shielded section

---

## Phase 3: Shielded Transactions (20-25h)

**Goal:** Private ZK transactions with zswap
**Status:** ⏸️ After balance viewing works

**Why After Phase 4A-UI?**
- Need balance viewing to test transaction correctness
- Core Midnight feature (privacy-first)
- Phase 1 shielded keys already working ✅ (JNI/Rust FFI)
- More complex than unshielded, do while crypto knowledge is fresh

**Architecture:**
- Uses shielded keys from Phase 1B ✅
- Zero-knowledge proofs via proof server
- Separate UTXO set (shielded pool)
- Transaction submission to Midnight node

**Deliverables:**
- [ ] Shielded UTXO tracking
- [ ] ZK proof generation (via proof server)
- [ ] Shielded transaction builder
- [ ] Transaction signing & submission
- [ ] Convert: shielded ↔ unshielded

**Dependencies:**
- ✅ Phase 1B (shielded key derivation via JNI)
- ⏳ Phase 4A-UI (balance viewing for testing)

**Testing:**
- Manual verification via node logs
- GraphQL transaction status queries
- Mock balance viewing (until Phase 4B complete)

**Files:**
```
core/ledger/
├── ShieldedTransactionBuilder.kt # ZK transaction builder
├── ProofServerClient.kt          # Proof generation
├── ShieldedUtxoManager.kt        # UTXO tracking
└── ShieldedSigner.kt             # Transaction signing

core/network/
├── SubstrateClient.kt            # Node RPC client
└── ScaleCodec.kt                 # Binary serialization
```


---

## Phase 2: Unshielded Transactions ✅ COMPLETE (81h)

**Goal:** Send/receive transparent tokens (no privacy)
**Status:** ✅ **COMPLETE** - Full transaction pipeline working, consecutive transactions verified

**See:** **`docs/PHASE_2_PLAN.md`** for detailed implementation breakdown

**Why Before Phase 3?** (Changed from original plan)
- Simpler than shielded transactions (no ZK proofs)
- Can test immediately with Phase 4B balance viewing
- Build confidence before tackling complex shielded txs

**Architecture:**
- Intent-based transactions (Midnight's unique transaction model)
- UTXO state machine (Available → Pending → Spent)
- Smallest-first coin selection (privacy optimization)
- Schnorr signing via midnight-ledger JNI (NOT pure Kotlin)
- SCALE codec via midnight-ledger FFI (same as TypeScript SDK)

**Completed Sub-Phases:** ✅ Phase 2A, 2B, 2C, 2D-FFI (37h actual)
- ✅ 2A: Transaction models (Intent, UnshieldedOffer, UtxoSpend) - 52 tests (3h)
- ✅ 2B: UTXO Manager with coin selection (smallest-first) - 25 tests (3.5h)
- ✅ 2C: Transaction Builder - 10 tests (1.5h)
- ✅ 2D-FFI: JNI Ledger Wrapper (Schnorr signing + verification) - 50 tests, **production-ready** (29h)

**Deliverables:**
- [x] Transaction models (Intent, UnshieldedOffer, UtxoSpend, UtxoOutput)
- [x] UTXO selection (smallest-first strategy for privacy)
- [x] Transaction builder (balancing, TTL, change calculation)
- [x] Rust FFI layer (Schnorr signing, signature verification, cryptographic correctness proven)
- [x] JNI C bridge (Kotlin → C → Rust, security-hardened with zeroization)
- [x] Kotlin wrapper (TransactionSigner.kt, production-ready, 50 Android tests)
- [x] Dust fee payment (DustLocalState replay, spend serialization, 1% fee overhead)
- [x] Transaction submission via RPC (NodeRpcClient, extrinsic wrapping)
- [x] WebSocket-based finalization confirmation
- [x] Consecutive transaction support (dust state cleanup after finalization)
- [x] Send UI (test UI for validation)

**Dependencies:**
- ✅ Phase 1 (BIP-32 key derivation for private keys)
- ✅ Phase 4B (UTXO tracking, balance viewing)
- ✅ midnight-ledger v6.1.0-alpha.5 (Rust library, already used for shielded keys)

**Critical:** Schnorr BIP-340 signing is handled by midnight-ledger via JNI (same pattern as Phase 1B shielded keys). There is NO pure Kotlin Schnorr implementation.

**Files:**
```
core/ledger/
├── model/
│   ├── Intent.kt                      # ✅ Complete (Phase 2A)
│   ├── UnshieldedOffer.kt             # ✅ Complete (Phase 2A)
│   ├── UtxoSpend.kt                   # ✅ Complete (Phase 2A)
│   └── UtxoOutput.kt                  # ✅ Complete (Phase 2A)
├── builder/
│   └── UnshieldedTransactionBuilder.kt # ✅ Complete (Phase 2C)
└── signer/
    └── TransactionSigner.kt           # ✅ Complete (Phase 2D-FFI)

rust/kuira-crypto-ffi/                 # ✅ Complete (Phase 2D-FFI)
├── src/
│   ├── lib.rs                         # ✅ Shielded keys (Phase 1B)
│   └── transaction_ffi.rs             # ✅ Schnorr signing + verification (Phase 2D-FFI)
├── Cargo.toml                         # ✅ midnight-ledger v6.1.0-alpha.5
└── jni/kuira_crypto_jni.c             # ✅ Complete - JNI bridge with security hardening

core/ledger/src/androidTest/kotlin/.../signer/
├── TransactionSignerIntegrationTest.kt  # ✅ 20 tests (basic functionality)
└── TransactionSignerSecurityTest.kt     # ✅ 30 tests (security + verification)

docs/reviews/                          # ✅ Complete (Phase 2D-FFI)
├── PHASE_2D_FFI_CODE_REVIEW.md        # ✅ Peer review (found 9 issues)
├── PHASE_2D_FFI_TEST_REVIEW.md        # ✅ Test review (found false positive)
├── PHASE_2D_FFI_FIXES_APPLIED.md      # ✅ All fixes applied (5.7 → 8.5 quality)
├── PHASE_2D_FFI_QUALITY_GAPS.md       # ✅ Gap analysis (8.5 → 10/10)
├── PHASE_2D_FFI_QUALITY_10.md         # ✅ Final quality report (10/10 achieved)
└── PHASE_2D_FFI_SAFETY_DOCUMENTATION.md # ✅ Comprehensive FFI safety docs
```

---

## Phase 5: DApp Connector & All Transaction Types (44-59h) — REVISED

**Goal:** Implement full Midnight ConnectedAPI (16 wallet methods + hintUsage + connect handshake = 18 handlers) — same protocol as our CLI wallet's `mn serve`
**Status:** ⏸️ Investigation Complete, plan revised March 2026
**Estimate:** 44-59 hours (revised from 25-35h after full transaction type analysis)
**Analysis:** See `docs/planning/TRANSACTION_TYPES_ANALYSIS.md` for complete breakdown

**⚠️ URGENCY:** Mainnet launched March 23, 2026. dApps deploying within days. Lace is the only wallet. Kuira has a window to be the first Android alternative.

**Key Insight:** The wallet is NOT just a transfer tool. It's a **transaction balancer, relayer, signer, and proving bridge** for ALL dApp interactions. Our CLI wallet (`midnight-wallet-cli`) already implements all 18 methods — Kuira adapts the same architecture to Android.

### All Midnight Transaction Types & Capabilities

**Transaction operations (7):**

| # | Type | ConnectedAPI Method | Facade Method | Who Creates | Kuira Status |
|---|------|--------------------|--------------| -------------|-------------|
| 1 | Token Transfer | `makeTransfer` | `transferTransaction` | Wallet | Unshielded ✅, Shielded ⏸️ |
| 2 | Contract Call (unsealed) | `balanceUnsealedTransaction` | `balanceUnboundTransaction` | DApp creates, wallet balances | Missing |
| 3 | Contract Call (sealed) | `balanceSealedTransaction` | `balanceFinalizedTransaction` | DApp creates, wallet balances | Missing |
| 4 | Swap / Intent | `makeIntent` | `initSwap` | Wallet | Missing |
| 5 | Dust Registration | N/A (wallet-only) | `registerNightUtxosForDustGeneration` | Wallet | ✅ Done |
| 6 | Dust Deregistration | N/A (wallet-only) | `deregisterFromDustGeneration` | Wallet | Missing |
| 7 | Transaction Relay | `submitTransaction` | `submitTransaction` | DApp creates, wallet relays | Partial |

**Wallet capabilities (3):**

| # | Capability | ConnectedAPI Method | Kuira Status |
|---|-----------|--------------------| -------------|
| 8 | Data Signing | `signData` | Missing |
| 9 | Proving Provider | `getProvingProvider` | Missing |
| 10 | Fee Estimation | N/A (facade-only: `calculateFee` / `estimateFee`) | Missing |

> **Note:** The facade also has `balanceUnprovenTransaction` which is NOT in the ConnectedAPI — it's internal wallet use only. May be useful for wallet-initiated flows.

### Transaction Serialization Formats

Three formats identified by type markers (from CLI `tx-serde.ts`):

| Name | Markers | Description |
|------|---------|-------------|
| **Unsealed** | `('signature', 'proof', 'pre-binding')` | Proven, not bound — DApp sends to `balanceUnsealedTransaction` |
| **Sealed** | `('signature', 'proof', 'binding')` | Proven + bound — DApp sends to `submitTransaction` |
| **Unproven** | `('signature', 'pre-proof', 'pre-binding')` | Not proven — internal wallet use |

### Architecture: JSON-RPC over Local WebSocket

**Same protocol as CLI `mn serve`** — dApps work with both CLI and Kuira:

```
DApp (browser/native)              Kuira Android Service
    │                                      │
    │  ws://localhost:9932                  │
    ├──── connect("Undeployed") ──────────►│ Biometric approval
    │◄──── { networkId } ─────────────────┤
    │                                      │
    │──── makeTransfer([...]) ────────────►│ Biometric: "Send 10 NIGHT to mn_addr...?"
    │◄──── { tx: "hex..." } ──────────────┤ Build → Balance → Sign → Prove → Serialize
    │                                      │
    │──── submitTransaction(tx) ──────────►│ Biometric: "Submit transaction?"
    │◄──── void ──────────────────────────┤ Deserialize → Submit → Track
```

**Also supports:** Android deep links (`midnight://`), bound service for co-installed apps.

### Dependencies

**Prerequisites (Must Complete First):**
- ✅ Phase 2: Unshielded transactions (UTXO selection, signing, submission)
- ✅ Phase 2F.1: Dust (registration, fee payment)
- ⏸️ Phase 4B-Shielded: Shielded balances (for shielded methods)
- ⏸️ Phase 3: Shielded transactions (for shielded transfers/balancing)

**Can Reuse:**
- ✅ Phase 1B: Shielded keys (signing, witness data)
- ✅ Phase 2B: UTXO selection (coin selection for fee payment)
- ✅ Phase 2D-FFI: Transaction signing (Schnorr via Rust FFI)
- ✅ Phase 4B: WebSocket infrastructure (client already built)
- ✅ Dust module: DustLocalState, fee calculation

**Reference Implementation:**
- CLI connector: `/Users/norman/Development/tech-moderator/midnight-wallet-cli/src/lib/dapp-connector.ts` (675 lines, all 18 methods)
- CLI tx-serde: `/Users/norman/Development/tech-moderator/midnight-wallet-cli/src/lib/tx-serde.ts` (serialization)
- Connector client: `/Users/norman/Development/tech-moderator/midnight-wallet-cli/packages/connector/` (npm package)

### Sub-Phases

#### Phase 5A: Transaction Serialization FFI (8-10h)

**Goal:** Rust FFI to deserialize/serialize all three transaction formats.

**What to build:**
- `rust/kuira-crypto-ffi/src/tx_serde_ffi.rs`:
  - `deserialize_unsealed(hex) → opaque pointer` (SignatureEnabled, Proof, PreBinding)
  - `deserialize_sealed(hex) → opaque pointer` (SignatureEnabled, Proof, Binding)
  - `deserialize_unproven(hex) → opaque pointer` (SignatureEnabled, PreProof, PreBinding)
  - `serialize_tx(tx) → hex string`
  - `inspect_tx(hex, type) → JSON` (human-readable details for approval UI)
  - `merge_transactions(tx_a, tx_b) → merged tx`
- JNI bridge + Kotlin wrapper (`TransactionSerializer.kt`)

**Why first:** Every write method in the connector needs this. Can't balance or submit DApp txs without deserialization.

#### Phase 5B: DApp Connector — Read Methods + makeTransfer (12-15h)

**Goal:** Android Service implementing ConnectedAPI, starting with read methods and simple transfers.

**What to build:**
- `core/dapp/connector/DAppConnectorService.kt` — Android foreground service
- `core/dapp/connector/JsonRpcServer.kt` — WebSocket JSON-RPC server (reuse Ktor WebSocket from Phase 4B)
- `core/dapp/connector/ConnectionManager.kt` — track connected DApps, permissions
- All 9 read methods:
  - `getUnshieldedBalances`, `getShieldedBalances`, `getDustBalance`
  - `getUnshieldedAddress`, `getShieldedAddresses`, `getDustAddress`
  - `getTxHistory`, `getConfiguration`, `getConnectionStatus`
- Write methods:
  - `makeTransfer` — builds on Phase 2 transfer pipeline
  - `submitTransaction` — relay pre-built sealed txs
  - `hintUsage` — permission hints
- Biometric approval flow for write methods
- Connection handshake (`connect` with network validation)

**Test:** Connect midnight-starship (or bboard example) to Kuira via WebSocket.

#### Phase 5C: Contract Transaction Balancing (10-14h)

**Goal:** The core contract interaction — balance DApp-created transactions.

**What to build:**
- `balanceUnsealedTransaction`:
  1. Deserialize unsealed tx from hex (Phase 5A)
  2. Add unshielded inputs/outputs to balance
  3. Add shielded balancing (if applicable)
  4. Add dust fee payment
  5. Sign → prove → bind → serialize back to hex
- `balanceSealedTransaction`: Same flow but for already-sealed txs (separate intent)
- Pending tx tracking per connection:
  - Lock dust coins on balance
  - Auto-revert on disconnect/abandon (same pattern as CLI)
  - Abandon timeout (configurable)
- Dust retry logic:
  - Retry on "No dust tokens" error
  - Watch DustLocalState for dust availability
  - Configurable retry count and delay
- Fee estimation: `calculateFee`, `estimateFee`

**This is where contract dApps actually work with Kuira.**

#### Phase 5D: Swaps, Signing, and Proving (8-12h)

**Goal:** Complete the remaining ConnectedAPI methods.

**What to build:**
- `makeIntent` / `initSwap`:
  - Parse `DesiredInput[]` → `CombinedSwapInputs` (shielded + unshielded)
  - Parse `DesiredOutput[]` → `CombinedTokenTransfer[]`
  - Build swap transaction with optional fee payment
  - Intent ID support (`number | 'random'`)
- `signData`:
  - Decode data (hex/base64/text)
  - Sign with unshielded key (Schnorr — already have in Phase 2D-FFI)
  - Return `{ data, signature, verifyingKey }`
- `getProvingProvider`:
  - Return proof server URI for DApps that need to prove
  - Future: bidirectional proving over WebSocket
- Dust deregistration (wallet-only, not ConnectedAPI)

#### Phase 5E: Approval UI & DApp Management (6-8h)

**Goal:** Production-quality approval experience.

**What to build:**
- Transaction inspection:
  - Parse tx hex via Rust FFI → JSON with amounts, recipients, contract addresses
  - Human-readable confirmation: "Send 10 NIGHT to mn_addr...?" / "Balance contract transaction (est. fee: 0.05 DUST)?"
- Biometric confirmation dialogs (fingerprint/face)
- Connected DApps management screen (list, revoke)
- Transaction status notifications (building → proving → submitting → finalized)
- Progress notifications to DApps (same as CLI's `approval:pending`, `progress` events)

### Files to Create

```
core/dapp/
├── connector/
│   ├── DAppConnectorService.kt        # Android foreground service
│   ├── JsonRpcServer.kt               # WebSocket JSON-RPC (Ktor)
│   ├── ConnectionManager.kt           # Track connected DApps
│   ├── PendingTxTracker.kt            # Per-connection tx tracking + auto-revert
│   └── DustRetryHelper.kt             # Retry on "no dust tokens"
├── balancer/
│   ├── TransactionBalancer.kt         # Balance unsealed + sealed txs
│   └── FeeEstimator.kt               # Fee calculation
├── serde/
│   └── TransactionSerializer.kt       # Kotlin wrapper for Rust FFI tx serde
└── ui/
    ├── ApprovalDialog.kt              # Biometric tx confirmation
    ├── TxInspector.kt                 # Human-readable tx details
    ├── ConnectedDAppsScreen.kt        # DApp management
    └── TxStatusNotifier.kt           # Progress notifications

rust/kuira-crypto-ffi/src/
└── tx_serde_ffi.rs                    # Deserialize/serialize/inspect transactions

feature/dapp/                          # New feature module
├── DAppScreen.kt                      # DApp browser/management UI
└── DAppViewModel.kt                   # DApp connection state
```

### Security Considerations

- Validate DApp origin / connection ID (prevent spoofing)
- Biometric required for ALL write methods
- Show human-readable tx details before approval (never blind-sign)
- Auto-revert pending txs on disconnect (prevent dust coin lockup)
- Abandon timeout for unsubmitted balanced txs
- Rate limiting on connection attempts

### Testing Strategy

1. **Unit tests:** Each handler method, tx serde round-trips, pending tx lifecycle
2. **Integration test:** Connect `midnight-wallet-connector` client → Kuira service → verify all 18 methods
3. **E2E test:** midnight-starship game → Kuira connector → live testnet transaction
4. **Security test:** Blind-sign prevention, connection spoofing, abandon timeout

### Suggested Order

1. ⏸️ Phase 4B-Shielded (8-12h) — needed for shielded connector methods
2. ⏸️ Phase 3 (20-25h) — needed for shielded transfers
3. **Phase 5A** (8-10h) — tx serde FFI (unblocks everything else)
4. **Phase 5B** (12-15h) — read methods + makeTransfer (first working connector)
5. **Phase 5C** (10-14h) — contract balancing (dApps can use Kuira)
6. **Phase 5D** (8-12h) — swaps, signing, proving
7. **Phase 5E** (6-8h) — approval UI polish

**Total to Full DApp Support:** ~73-100h from current state (Phase 4B-Shielded through Phase 5E)

---

## Phase 6: UI & Polish (15-20h)

**Goal:** Production-ready Android app

**Deliverables:**
- [ ] Material Design 3 UI
- [ ] Wallet creation/restore flow
- [ ] Send/receive screens
- [ ] Transaction history
- [ ] Settings & security
- [ ] App icon & branding

---

## Critical Compatibility Requirements

### Lace Wallet Compatibility ⚠️
**MUST** generate identical addresses/keys for same mnemonic:
- BIP-39: Use `@scure/bip39` algorithm (done via BitcoinJ)
- BIP-32: Exact path `m/44'/2400'/account'/role/index`
- Shielded: Use midnight-zswap v6.1.0-alpha.5 (NOT v7.0+)
- Addresses: SHA-256(publicKey) → Bech32m with "mn" prefix

**Test:** Generate wallet in Kuira → Import in Lace → Addresses match ✅

### Version Locking 🔒
**midnight-zswap:** MUST use v6.1.0-alpha.5
- Reason: v7.0 changed key derivation algorithm → incompatible keys
- Impact: Using wrong version = wallet can't be restored in Lace

---

## Architecture Decisions

### ✅ Pure Kotlin/JNI (No WASM)
**Reason:** Midnight WASM uses externref (unsupported on mobile)
**Trade-off:** More dev time, but cleaner architecture

### ✅ BitcoinJ for BIP-39/32
**Reason:** Battle-tested, Android-optimized, BIP-compliant
**Alternative:** Port @scure libraries (more work, same result)

### ✅ JNI for Shielded Keys
**Reason:** JubJub curve too complex, use Midnight's Rust code
**Trade-off:** +2 MB APK, but 98% confidence vs 85% pure Kotlin

### ✅ Direct Substrate RPC
**Reason:** No official Android SDK, build minimal client
**Trade-off:** Must handle reconnection, state sync manually

---

## Resources

- **Midnight SDK (TypeScript):** Reference for algorithms
- **midnight-ledger (Rust):** Core crypto implementation
- **Lace wallet:** Reference implementation for testing
- **Polkadot.js:** Substrate RPC patterns

---

## Risk Mitigation

**High Risk:**
- Version compatibility (test with Lace extensively)
- JNI memory leaks (use LeakCanary during testing)

**Medium Risk:**
- RPC client stability (implement reconnection, offline mode)
- UTXO state sync (test rollback scenarios)

**Low Risk:**
- UI/UX (iterate based on feedback)
