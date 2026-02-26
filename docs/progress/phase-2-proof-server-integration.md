# Phase 2 Enhancement: Proof Server Integration

**Date:** 2026-01-27
**Status:** Investigation Complete - Ready for Implementation Planning
**Priority:** CRITICAL - Transactions cannot be submitted without proving

---

## Executive Summary

### The Problem

Our Android wallet currently submits **unproven transactions** directly to the Midnight node, which rejects them with "Invalid Transaction (Custom error: 1)". The node expects **proven transactions** that have been processed by a proof server.

**Current Flow (BROKEN):**
```
Build Transaction → Sign → Serialize → Submit to Node → REJECTED
                                                            ↓
                                            "Invalid Transaction (Custom error: 1)"
```

**Required Flow (WORKING):**
```
Build Transaction → Sign → Prove via Server → Serialize → Submit to Node → ACCEPTED
```

### The Solution

Implement a **Proof Server Client** that integrates with Midnight's proof server (port 6300) to convert unproven transactions into proven transactions before submission.

---

## Conceptual Understanding

### What is "Proving" in Midnight?

**Zero-Knowledge Proofs for Privacy:**
- Midnight transactions can contain **shielded (private) components** even in "unshielded transfers"
- The proof server generates **zero-knowledge proofs** that verify transaction validity without revealing private data
- Even simple unshielded transfers need proving to validate the transaction structure

**Why Every Transaction Needs Proving:**
1. **Binding Commitment Validation:** Proves the binding commitment matches the transaction randomness
2. **Structural Integrity:** Validates the transaction follows Midnight's protocol rules
3. **Network Acceptance:** The node only accepts transactions with valid proofs

### Transaction Type Evolution

Midnight transactions evolve through three distinct states:

#### State 1: Unproven Transaction
```
Type: Transaction<Signature, ProofPreimageMarker, Pedersen>
Tag:  midnight:transaction[v6](signature[v1],proof-preimage,pedersen[v1])

Contains:
- Signatures: BIP-340 Schnorr signatures (present)
- Proof: ProofPreimageMarker (raw data to CREATE proofs, not actual proofs)
- Binding: Pedersen (unsealed curve point commitment)

Status: Cannot be submitted to network
```

**ProofPreimageMarker:** Think of this as the "proof recipe" - it contains all the raw data needed to construct a proof, but is not a cryptographic proof itself.

**Pedersen Commitment:** A curve point (g^r) that commits to the binding randomness (r). This is "unsealed" meaning it's in its mathematical form, not yet converted to the final binding format.

#### State 2: Proven Transaction
```
Type: Transaction<Signature, Proof, PureGeneratorPedersen>
Tag:  midnight:transaction[v6](signature[v1],proof,pedersen-schnorr[v1])

Contains:
- Signatures: BIP-340 Schnorr signatures (same)
- Proof: Actual zero-knowledge proof (computed by proof server)
- Binding: PureGeneratorPedersen (sealed Schnorr binding)

Status: Ready for submission to network
```

**Proof:** The actual zero-knowledge proof bytes computed by the proof server. This proves transaction validity without revealing private information.

**PureGeneratorPedersen (pedersen-schnorr[v1]):** The "sealed" binding commitment - converted from curve point to Schnorr format for network validation.

#### State 3: Finalized Transaction (Optional Concept)
```
Same as Proven Transaction, but TypeScript SDK uses this type alias to indicate
the transaction is fully ready for submission (proven + any final processing).
```

### The Proof Server's Role

**What it Does:**
1. **Accepts:** Serialized unproven transaction (binary data)
2. **Computes:** Zero-knowledge proofs for all proof obligations
3. **Seals:** Converts Pedersen commitments to PureGeneratorPedersen (Schnorr form)
4. **Returns:** Serialized proven transaction (binary data)

**Why it's Separate:**
- Proof generation is **computationally expensive** (CPU/memory intensive)
- Proof server can be **scaled horizontally** (multiple instances)
- Wallet remains **lightweight** (delegates heavy computation)
- Proof server has **specialized cryptography** (optimized proving algorithms)

**Proof Server Location:**
- Development: `http://localhost:6300`
- TestNet: `https://proof-server.testnet.midnight.network:6300` (hypothetical)
- MainNet: `https://proof-server.midnight.network:6300` (hypothetical)

---

## Detailed Investigation Findings

### 1. Proof Server API Contract

**Base URL:** `http://localhost:6300` (development)

#### Endpoint: POST `/prove-tx` (Legacy)
**Purpose:** Prove a complete transaction

**Request:**
- **Method:** POST
- **Content-Type:** application/octet-stream (binary)
- **Body:** Raw binary (serialized unproven transaction)
- **Encoding:** SCALE codec with tagged format

**Response:**
- **Status:** 200 OK (success) or 500+ (error)
- **Content-Type:** application/octet-stream (binary)
- **Body:** Raw binary (serialized proven transaction)
- **Encoding:** SCALE codec with tagged format

**Timeout:** 300,000ms (5 minutes) - proof generation is slow

#### Endpoint: POST `/prove` (Newer)
**Purpose:** Generate proofs for proof preimages

**Request:**
- **Method:** POST
- **Content-Type:** application/octet-stream (binary)
- **Body:** Binary payload (serialized preimage + optional binding input)
- **Created via:** `ledger.createProvingPayload(serializedPreimage, overwriteBindingInput?)`

**Response:**
- **Status:** 200 OK
- **Body:** Binary proof bytes
- **Format:** Raw proof data (not full transaction)

**Retry Logic:** 3 attempts on 502-504 errors, exponential backoff (2s base, 2x multiplier)

#### Endpoint: POST `/check` (Validation)
**Purpose:** Check proof validity without generating proofs

**Request:**
- **Method:** POST
- **Content-Type:** application/octet-stream (binary)
- **Body:** Binary payload (serialized preimage + optional IR)
- **Created via:** `ledger.createCheckPayload(serializedPreimage, ir?)`

**Response:**
- **Status:** 200 OK
- **Body:** Binary array of (bigint | undefined)[] indicating validity

#### Endpoint: GET `/health`
**Purpose:** Health check

**Response:**
- **Status:** 200 OK if server is healthy
- **Body:** (format not documented)

### 2. Serialization Details

**Request Preparation (TypeScript SDK approach):**

**Option A: Full Transaction Proving (/prove-tx):**
```
1. Serialize unproven transaction using tagged_serialize()
2. Result: Binary SCALE-encoded data with tag prefix
3. Send entire binary payload to /prove-tx
4. Receive proven transaction binary
5. Deserialize using tagged_deserialize()
```

**Option B: Preimage Proving (/prove):**
```
1. Extract proof preimages from transaction
2. Call ledger.createProvingPayload(preimage, bindingInput?)
3. Send payload to /prove
4. Receive proof bytes
5. Reconstruct proven transaction with new proofs
6. Call .bind() to seal the transaction
```

**Android Rust FFI Approach:**
We already have `tagged_serialize()` working in Rust. The same function that serializes for submission can serialize for proving.

### 3. Network Communication

**HTTP Client Requirements:**
- **Binary POST:** Must support sending/receiving binary data (not JSON)
- **Content-Type:** application/octet-stream
- **Timeout:** 5+ minute timeout (proof generation is slow)
- **Error Handling:** Retry on 502-504 (server overload), fail on 400-499 (client error)

**Kotlin Implementation:**
- OkHttp or Ktor can handle binary POST/response
- Use `ByteArray` for request/response bodies
- Set appropriate headers and timeouts

**Error Categories:**
- **Client Errors (400-499):** Invalid transaction format, malformed request
- **Server Errors (500+):** Proof server overload, cryptography error
- **Network Errors:** Connection refused, timeout
- **Timeout:** Proof took too long (> 5 minutes typically means stuck)

### 4. Transaction Lifecycle with Proving

**Complete Flow:**

```
┌─────────────────────┐
│ 1. Build Unproven   │  Kotlin: TransactionBuilder
│    Transaction      │  FFI: build_and_serialize_intent()
│                     │  Result: Intent with ProofPreimageMarker
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│ 2. Sign Transaction │  Kotlin: TransactionSigner
│                     │  FFI: get_signing_message() + Schnorr sign
│                     │  Result: Signed Intent
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│ 3. Serialize for    │  FFI: serialize() with tagged_serialize()
│    Proving          │  Result: Binary SCALE bytes
│                     │  Tag: proof-preimage, pedersen[v1]
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│ 4. POST to Proof    │  Kotlin: ProofServerClient (NEW)
│    Server           │  HTTP POST /prove-tx
│                     │  Timeout: 5 minutes
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│ 5. Receive Proven   │  HTTP Response: 200 OK + binary body
│    Transaction      │  Result: Binary SCALE bytes
│                     │  Tag: proof, pedersen-schnorr[v1]
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│ 6. Wrap in          │  Kotlin: NodeRpcClient.submitTransaction()
│    Extrinsic        │  Existing code (already works)
└──────────┬──────────┘
           │
           ↓
┌─────────────────────┐
│ 7. Submit to Node   │  RPC: author_submitExtrinsic
│                     │  Result: Transaction hash or error
└─────────────────────┘
```

**Key Insight:** Steps 1-2 and 6-7 already exist and work. We only need to add steps 3-5 (proving).

### 5. Comparison: What We Have vs What We Need

#### What Currently Works ✅

**Transaction Building (Rust FFI):**
- ✅ Builds StandardTransaction with correct structure
- ✅ Creates Intent with UnshieldedOffer
- ✅ Uses correct type: `Intent<Signature, ProofPreimageMarker, Pedersen>`
- ✅ Generates binding randomness (32 bytes)
- ✅ Converts to binding commitment via `Pedersen::from(randomness)`

**Transaction Signing (Kotlin + FFI):**
- ✅ Gets signing message via FFI
- ✅ Signs with BIP-340 Schnorr
- ✅ Attaches signatures to Intent

**Serialization (Rust FFI):**
- ✅ Uses `tagged_serialize()` correctly
- ✅ Produces valid SCALE encoding
- ✅ Includes correct tag: `midnight:transaction[v6](signature[v1],proof-preimage,pedersen[v1]):`
- ✅ Serializes to hex string for Kotlin

**Node Submission (Kotlin):**
- ✅ Wraps transaction in extrinsic format
- ✅ Calls `author_submitExtrinsic` RPC
- ✅ Handles response/errors

#### What's Missing ❌

**Proof Server Integration:**
- ❌ No HTTP client for proof server
- ❌ No binary POST support in current codebase
- ❌ No endpoint configuration (proof server URL)
- ❌ No deserialization of proven transaction response
- ❌ No retry logic for proof server failures
- ❌ No timeout handling (5 min+)

**Flow Integration:**
- ❌ TransactionSubmitter goes directly from signing → submission
- ❌ No "prove then submit" workflow
- ❌ No storage for proven transactions (if caching needed)

**Testing:**
- ❌ No proof server integration tests
- ❌ No mock proof server for testing
- ❌ No error handling tests for proof failures

---

## Architecture Design Concepts

### Option 1: Minimal Integration (Recommended for MVP)

**New Component:** `ProofServerClient`

**Responsibility:**
- Single method: `prove(serializedUnprovenTx: String): String`
- Takes hex-encoded unproven transaction
- POSTs binary to `/prove-tx`
- Returns hex-encoded proven transaction

**Integration Point:**
- Modify `TransactionSubmitter.submitAndWait()`:
  ```
  OLD: serialize → submit
  NEW: serialize → prove → submit (with proven tx)
  ```

**Advantages:**
- Minimal code changes
- Reuses existing serialization
- Simple HTTP client (one endpoint)
- Easy to test

**Disadvantages:**
- Less flexible (all-or-nothing proving)
- Can't cache proof results
- Re-proves if retry needed

### Option 2: Recipe-Based Proving (TypeScript SDK Pattern)

**New Components:**
- `ProvingRecipe` sealed class (TransactionToProve, NothingToProve, etc.)
- `ProvingService` interface
- `HttpProvingService` implementation
- `TransactionProver` coordinator

**Responsibility:**
- Separates "what to prove" (recipe) from "how to prove" (service)
- Supports multiple proving strategies
- Can handle partial proving, proof caching, etc.

**Integration Point:**
- Add proving step between signing and submission
- TransactionBuilder returns `ProvingRecipe`
- TransactionProver.prove(recipe) → proven transaction
- TransactionSubmitter submits proven transaction

**Advantages:**
- Matches TypeScript SDK architecture
- More flexible for future features (shielded transactions, swaps)
- Better testability (mock ProvingService)
- Can add optimizations (proof caching, parallel proving)

**Disadvantages:**
- More upfront code
- More complex architecture
- Overhead for simple unshielded transfers

### Option 3: FFI-Integrated Proving (Rust Does Everything)

**Approach:**
- Add proof server HTTP client to Rust FFI
- FFI function: `prove_and_serialize(intent, proof_server_url) -> Result<String>`
- Rust code handles HTTP, serialization, deserialization

**Advantages:**
- Minimal Kotlin code changes
- Proof server communication in same layer as serialization
- Potentially better performance (no JNI boundary for large data)

**Disadvantages:**
- Rust HTTP client dependency (reqwest, hyper)
- Async handling in FFI (complex)
- Harder to mock/test in Kotlin integration tests
- Less idiomatic for Android architecture

---

## Proof Server Setup Requirements

### Development Environment

**Local Proof Server:**
```bash
# TypeScript SDK tests show:
docker run -d \
  --name midnight-proof-server \
  -p 6300:6300 \
  ghcr.io/midnight-ntwrk/proof-server:6.1.0-alpha.5

# Or via docker-compose
services:
  proof-server:
    image: ghcr.io/midnight-ntwrk/proof-server:6.1.0-alpha.5
    ports:
      - "6300:6300"
```

**Android Emulator Access:**
- Emulator sees localhost as 10.0.2.2
- Configure proof server URL: `http://10.0.2.2:6300`
- Same pattern as node URL (already working)

**Health Check:**
```bash
curl http://localhost:6300/health
# Should return 200 OK when server is ready
```

### TestNet/Production

**Assumption:** Midnight will provide public proof servers
- TestNet: `https://proof-server.testnet.midnight.network:6300`
- MainNet: `https://proof-server.midnight.network:6300`

**Configuration:**
- Add `proofServerUrl` to network config
- Default to localhost for development
- Override for TestNet/MainNet deployment

---

## Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                     ANDROID WALLET                                  │
│                                                                     │
│  ┌──────────────┐                                                   │
│  │ SendScreen   │                                                   │
│  │ (Compose UI) │                                                   │
│  └──────┬───────┘                                                   │
│         │ User clicks "Send"                                        │
│         ↓                                                           │
│  ┌──────────────┐                                                   │
│  │ SendViewModel│                                                   │
│  └──────┬───────┘                                                   │
│         │ initiateTransaction()                                     │
│         ↓                                                           │
│  ┌──────────────────┐                                               │
│  │TransactionBuilder│                                               │
│  └──────┬───────────┘                                               │
│         │ build() → Intent (unproven)                               │
│         ↓                                                           │
│  ┌──────────────────┐                                               │
│  │TransactionSigner │                                               │
│  └──────┬───────────┘                                               │
│         │ sign() → Intent (signed, unproven)                        │
│         ↓                                                           │
│  ┌──────────────────────┐                                           │
│  │TransactionSerializer │───┐                                       │
│  └──────────────────────┘   │ serialize() → hex string             │
│                              │ (unproven transaction)               │
│                              ↓                                      │
│                     ┌─────────────────┐                             │
│                     │ProofServerClient│  ← NEW COMPONENT            │
│                     └────────┬────────┘                             │
│                              │                                      │
└──────────────────────────────┼──────────────────────────────────────┘
                               │ prove(hex) → proven hex
                               │
                         ┌─────▼──────┐
                         │   NETWORK  │
                         └─────┬──────┘
                               │
        ┌──────────────────────┼──────────────────────┐
        │                      │                      │
        ↓                      ↓                      ↓
┌───────────────┐    ┌──────────────────┐   ┌───────────────┐
│ PROOF SERVER  │    │  INDEXER         │   │  NODE         │
│  :6300        │    │  :8088           │   │  :9944        │
└───────┬───────┘    └──────────────────┘   └───────┬───────┘
        │                                            │
        │ POST /prove-tx                             │
        │ Body: unproven tx (binary)                 │
        ├───────────────────────────────────────────>│
        │                                            │
        │ [5 seconds - 5 minutes]                    │
        │ Compute ZK proofs                          │
        │ Seal binding commitments                   │
        │                                            │
        │ Response: 200 OK                           │
        │ Body: proven tx (binary)                   │
        │<───────────────────────────────────────────┤
        │                                            │
        ↓                                            │
┌──────────────────────┐                            │
│ ProofServerClient    │                            │
│ receives proven tx   │                            │
└──────────┬───────────┘                            │
           │ returns proven hex                     │
           ↓                                        │
┌──────────────────────┐                            │
│ TransactionSubmitter │                            │
└──────────┬───────────┘                            │
           │ submitTransaction(proven hex)          │
           │                                        │
           ├────────────────────────────────────────>
           │ RPC: author_submitExtrinsic            │
           │ Body: JSON-RPC with hex extrinsic      │
           │                                        │
           │ Response: txHash or error              │
           │<────────────────────────────────────────┤
           │                                        │
           ↓                                        ↓
    ┌──────────────┐                        ┌──────────────┐
    │ IndexerClient│                        │ Transaction  │
    │ waitForTx()  │                        │   in Mempool │
    └──────────────┘                        └──────────────┘
```

---

## Implementation Considerations

### 1. Proof Server URL Configuration

**Options:**

**A) Hardcoded per Network:**
```kotlin
enum class Network {
    LOCAL {
        override val proofServerUrl = "http://10.0.2.2:6300"
    },
    TESTNET {
        override val proofServerUrl = "https://proof-server.testnet.midnight.network:6300"
    },
    MAINNET {
        override val proofServerUrl = "https://proof-server.midnight.network:6300"
    };

    abstract val proofServerUrl: String
}
```

**B) Configuration File:**
```kotlin
data class NetworkConfig(
    val nodeUrl: String,
    val indexerUrl: String,
    val proofServerUrl: String,  // Add this
    val networkId: String
)
```

**C) Environment Variable / Build Config:**
```gradle
buildConfigField("String", "PROOF_SERVER_URL", "\"http://10.0.2.2:6300\"")
```

**Recommendation:** Option B (Configuration File) - matches existing pattern for nodeUrl/indexerUrl.

### 2. Timeout Handling

**Proof Generation Timing:**
- Simple unshielded transfer: 2-10 seconds (typically fast)
- Complex shielded transaction: 30 seconds - 5 minutes (can be slow)
- Proof server overload: Can timeout or return 502-504

**Timeout Strategy:**
```kotlin
// Conservative timeout for production
val PROOF_TIMEOUT_MS = 300_000L  // 5 minutes

// For testing/development, can use shorter
val PROOF_TIMEOUT_DEV_MS = 60_000L  // 1 minute
```

**User Experience:**
- Show progress indicator ("Proving transaction...")
- Display estimated time (if available from server)
- Allow cancel after 30 seconds (but don't retry immediately)
- Clear error message on timeout

### 3. Error Handling

**Proof Server Error Categories:**

**Client Errors (400-499):**
- 400 Bad Request: Malformed transaction, invalid preimage
- 401/403: Authentication failure (if proof server requires auth)
- 404: Endpoint not found (wrong URL or outdated client)
- **Action:** Don't retry, show error to user, log for debugging

**Server Errors (500-599):**
- 500 Internal Server Error: Proof computation failed
- 502/503/504: Server overload, temporary unavailable
- **Action:** Retry with exponential backoff (up to 3 attempts)

**Network Errors:**
- Connection refused: Proof server not running
- Timeout: Proof took too long
- DNS resolution failure: Wrong URL
- **Action:** Show clear error, suggest checking server status

**Retry Logic:**
```
Attempt 1: Immediate
Attempt 2: Wait 2 seconds
Attempt 3: Wait 4 seconds
Fail: Show error to user
```

### 4. Testing Strategy

**Unit Tests:**
- Mock ProofServerClient
- Test transaction flow with fake proven response
- Test error handling (timeout, 500, network errors)

**Integration Tests:**
- Real proof server in docker container
- End-to-end flow: build → sign → prove → submit
- Verify transaction accepted by test node

**E2E Tests:**
- Complete wallet flow with real proof server
- TestNet submission
- Verify transaction confirmation

**Mock Proof Server:**
- For offline testing
- Immediately returns unmodified transaction (for testing flow)
- Or returns pre-computed proven transaction (for testing node submission)

### 5. Performance Considerations

**Proof Server Latency:**
- Typical: 2-10 seconds for simple transactions
- Peak: Up to 5 minutes for complex transactions
- User should see "Proving..." indicator

**Optimization Opportunities (Future):**
- **Proof Caching:** If transaction is rejected (e.g., insufficient fee), don't re-prove identical transaction
- **Parallel Proving:** If multiple transactions in queue, prove concurrently
- **Local Proving:** Future consideration - embed proof server in wallet (heavy, complex)

**Not Recommended for Phase 2:**
- Keep it simple: serialize → prove → submit synchronously
- Optimizations can come later if needed

---

## Security Considerations

### 1. Proof Server Trust Model

**What the Proof Server Sees:**
- Complete transaction structure (inputs, outputs, amounts)
- Addresses (sender and receiver)
- All transaction metadata

**What the Proof Server CANNOT Do:**
- Cannot steal funds (doesn't have private keys)
- Cannot modify transaction (signatures would become invalid)
- Cannot submit transaction on user's behalf (only proves, doesn't submit)

**Threat Model:**
- Malicious proof server could log transaction data (privacy leak)
- Compromised proof server could return invalid proofs (transaction rejected by node, no funds lost)
- DDoS on proof server could prevent transaction submission (availability issue)

**Mitigation:**
- Use trusted proof server (official Midnight infrastructure)
- For sensitive transactions, users could run own proof server
- Validate proof server certificate (HTTPS in production)

### 2. Man-in-the-Middle Protection

**Development (HTTP):**
- Acceptable: localhost only, not exposed to network
- Risk: Low (local machine only)

**Production (HTTPS):**
- Required: TLS certificate validation
- Pin certificate (optional, for maximum security)
- Verify hostname matches certificate

### 3. Transaction Privacy

**Data Sent to Proof Server:**
- Unshielded transactions: All data visible (amounts, addresses)
- Shielded transactions: Some data visible (structure), amounts/destinations hidden

**Recommendation:**
- Document privacy implications
- Allow users to choose proof server (advanced setting)
- Consider privacy warnings for large transactions

---

## Open Questions for Tomorrow's Planning

### 1. Proof Server Availability

**Question:** Do we need fallback proof servers?
- **Scenario:** Primary proof server is down or overloaded
- **Solution:** Configure multiple proof server URLs, try secondary on failure
- **Decision:** Needed for production? Or rely on Midnight infrastructure SLA?

### 2. Proof Caching

**Question:** Should we cache proven transactions?
- **Scenario:** Transaction rejected by node (e.g., fee too low), user adjusts and retries
- **Problem:** Re-proving identical transaction wastes time (5+ seconds)
- **Solution:** Cache `unproven_tx_hash → proven_tx` for 10 minutes
- **Trade-off:** Complexity vs user experience improvement
- **Decision:** MVP or Phase 3 feature?

### 3. Background Proving

**Question:** Should proving happen in background service?
- **Scenario:** User hits "Send", proving takes 30 seconds, app backgrounded
- **Problem:** Activity destroyed, transaction lost
- **Solution:** Use WorkManager for proving step
- **Trade-off:** Complexity vs reliability
- **Decision:** MVP or Phase 2 enhancement?

### 4. Proof Server Selection

**Question:** Should users be able to choose proof server?
- **Scenario:** Privacy-conscious user wants to use own proof server
- **Solution:** Advanced setting: "Custom proof server URL"
- **Trade-off:** UX complexity vs user control
- **Decision:** MVP (hardcoded) or include in Phase 2?

### 5. Error Recovery

**Question:** What should happen if proving fails?
- **Option A:** Show error, user must retry manually
- **Option B:** Automatically retry with exponential backoff
- **Option C:** Queue for later retry (WorkManager)
- **Decision:** Which provides best UX without overcomplicating?

### 6. Proof Server Health Monitoring

**Question:** Should we check proof server health before proving?
- **Scenario:** Proof server is down, avoid waiting 5 minutes for timeout
- **Solution:** GET /health before POST /prove-tx, fail fast if unhealthy
- **Trade-off:** Extra network call vs faster failure
- **Decision:** Worth the complexity?

---

## Recommended Implementation Order

### Phase 2A: Basic Proof Server Integration (Days 1-2)

**Goal:** Get transactions submitting successfully

1. **Create ProofServerClient** (minimal)
   - HTTP POST to `/prove-tx`
   - Binary request/response
   - 5-minute timeout
   - Basic error handling

2. **Integrate into TransactionSubmitter**
   - Add proving step between serialization and submission
   - Pass proven transaction to node

3. **Configuration**
   - Add `proofServerUrl` to NetworkConfig
   - Use `http://10.0.2.2:6300` for local development

4. **Testing**
   - Integration test with real proof server (docker)
   - Verify successful transaction submission

### Phase 2B: Robust Error Handling (Day 3)

**Goal:** Handle proof server failures gracefully

1. **Retry Logic**
   - Exponential backoff for 502-504 errors
   - 3 attempts maximum
   - Clear error messages

2. **Timeout Handling**
   - Show "Proving..." progress to user
   - Allow cancellation after 30 seconds
   - Handle timeout gracefully

3. **Error Messages**
   - User-friendly messages for each error type
   - Actionable suggestions ("Check network", "Try again")

### Phase 2C: Testing & Validation (Day 4)

**Goal:** Ensure reliability

1. **Unit Tests**
   - Mock ProofServerClient
   - Test all error scenarios
   - Test retry logic

2. **Integration Tests**
   - End-to-end flow with real proof server
   - TestNet submission
   - Verify transaction confirmation

3. **E2E Tests**
   - Complete wallet flow
   - Real proof server + real node
   - Verify funds transferred

### Phase 2D: Production Readiness (Day 5)

**Goal:** Ready for TestNet deployment

1. **Configuration**
   - Add TestNet proof server URL
   - Add production TLS certificate validation
   - Add logging/monitoring

2. **Documentation**
   - API documentation
   - Architecture diagrams
   - Troubleshooting guide

3. **Deployment**
   - Deploy to TestNet
   - Verify end-to-end flow
   - Monitor error rates

---

## Success Metrics

### Phase 2 Complete When:

✅ Unshielded transactions submit successfully to local node
✅ Proven transactions accepted by node (not rejected)
✅ Transaction appears in mempool and gets included in block
✅ Funds transferred from sender to recipient
✅ Proof server errors handled gracefully (no crashes)
✅ User sees clear progress/error messages
✅ Integration tests pass with real proof server
✅ E2E tests pass on TestNet

### Measurable Outcomes:

- **Success Rate:** >95% of transactions successfully prove and submit
- **Latency:** Proof + submission < 15 seconds for typical unshielded transfer
- **Error Recovery:** Failures retry successfully >80% of the time
- **User Experience:** Clear progress indicators, no hanging/frozen UI

---

## Appendix: Key TypeScript SDK References

### Files to Study (for implementation reference):

1. **HTTP Client Pattern:**
   - `/packages/http-client-proof-provider/src/http-client-proof-provider.ts`
   - Shows binary POST, timeout handling, error codes

2. **Proving Service:**
   - `/packages/shielded-wallet/src/v1/Proving.ts`
   - Shows recipe pattern, transaction proving flow

3. **Effect-based Client:**
   - `/packages/prover-client/src/effect/HttpProverClient.ts`
   - Shows retry logic, exponential backoff

4. **Integration Tests:**
   - `/midnight-ledger/integration-tests/src/proof-provider.ts`
   - Shows actual HTTP requests to proof server

5. **Test Setup:**
   - `/midnight-wallet/packages/shielded-wallet/test/utils.ts`
   - Shows docker proof server setup

---

## Next Steps

Tomorrow's planning session should address:

1. **Architecture Decision:** Option 1 (minimal) vs Option 2 (recipe-based)?
2. **Open Questions:** Review and decide on each
3. **Implementation Plan:** Break Phase 2A-2D into specific tasks
4. **Testing Strategy:** What tests do we write first?
5. **Configuration:** Where do we store proof server URLs?

**Key Decision Point:** Start with Option 1 (minimal) for MVP, evolve to Option 2 if needed for shielded transactions later.

---

## Conclusion

**The Missing Piece:** Proof server integration is the ONLY remaining blocker for transaction submission.

**Complexity Assessment:** Low-to-medium
- HTTP client integration is straightforward
- Binary POST/response is well-understood
- Retry/timeout logic is standard practice
- No new cryptography needed (proof server handles it)

**Timeline Estimate:** 4-5 days for full Phase 2A-2D implementation

**Risk Assessment:** Low
- Well-documented by TypeScript SDK
- Proof server API is stable
- Clear integration points in existing code
- Easy to test with local docker instance

**Confidence Level:** HIGH - This is a solved problem in TypeScript SDK, we're just porting the approach to Kotlin/Android.
