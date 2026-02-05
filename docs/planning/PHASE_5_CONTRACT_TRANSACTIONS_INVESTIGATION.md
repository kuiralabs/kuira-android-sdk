# Phase 5: Contract Transactions - Deep Investigation & Implementation Plan

**Last Updated:** January 22, 2026
**Status:** Investigation Complete - Ready for Planning
**Estimated Effort:** 25-35 hours (revised from 15-20h after investigation)

---

## Executive Summary

Midnight smart contract interactions require significantly more infrastructure than simple token transfers. The wallet acts as a **transaction balancer and relayer** while DApps handle business logic and proof generation.

**Key Insight:** We need a DApp Connector API (similar to MetaMask/WalletConnect) to enable browser-based DApps to interact with our wallet.

---

## 1. Contract Architecture Overview

### How Contracts Work on Midnight

**Contracts are written in Compact language:**
- Define public **ledger state** (on-chain)
- Define private **state** (off-chain, user-specific)
- Export **circuits** (ZK functions that modify state)
- Declare **witnesses** (functions to access private data)

**Example Contract Structure:**
```compact
export ledger state: State;
export ledger message: Maybe<Opaque<"string">>;
export ledger owner: Bytes<32>;

witness localSecretKey(): Bytes<32>;

export circuit post(newMessage: Opaque<"string">): [] {
  assert(state == State.VACANT, "Board is occupied");
  owner = disclose(publicKey(localSecretKey(), ...));
  message = disclose(some<Opaque<"string">>(newMessage));
  state = State.OCCUPIED;
}
```

**Key Concepts:**
- **Circuits**: Public functions enforcing contract rules via ZK proofs
- **Witnesses**: Private oracles accessing off-chain data (wallet provides)
- **Ledger state**: Public data visible on blockchain
- **Private state**: User-specific data never on-chain
- **Disclose**: Makes private data public in transaction

---

## 2. Transaction Flow for Contract Calls

### Complete Flow (DApp → Wallet → Network)

```
1. DApp calls circuit
   ↓
   await contract.callTx.post("Hello World")

2. Circuit executes locally (DApp)
   ↓
   - Calls witness functions for private data
   - Executes circuit logic with ZK proofs
   - Generates public transcript

3. Create unproven transaction (DApp)
   ↓
   {
     public: { contractAddress, publicTranscript, ... },
     private: { input, output, result, newCoins, ... },
     proofs: null  // Not proven yet
   }

4. Generate ZK proofs (DApp or Proving Server)
   ↓
   Unproven → Proven transaction

5. Send to wallet for balancing (DApp → Wallet)
   ↓
   wallet.balanceUnsealedTransaction(tx)

6. Wallet balances transaction
   ↓
   - Add fee payment inputs (from user's UTXOs)
   - Add change outputs (return excess)
   - Create new intent if needed
   - Seal transaction with signatures

7. Wallet submits to network
   ↓
   wallet.submitTransaction(tx) → Midnight node

8. Transaction finalized on-chain
   ↓
   DApp watches for finalization
```

**Critical Phases:**
- **Guaranteed phase**: Must succeed or transaction rejected
- **Fallible phase**: Can fail but transaction still recorded

---

## 3. Wallet Requirements (What We Need to Build)

### Core Capabilities

#### 3.1. DApp Connector API
**Purpose:** Allow browser DApps to interact with wallet

**API Surface (injected into `window.midnight`):**
```typescript
interface MnLace {
  // Connection
  isEnabled(): Promise<boolean>;
  enable(): Promise<WalletApi>;

  // Configuration
  serviceUriConfig(): Promise<{
    indexerUri: string,
    proverServerUri: string,
    substrateNodeUri: string
  }>;
}

interface WalletApi {
  // State
  state(): Promise<{
    address: string,
    balance: { shielded: bigint, unshielded: bigint }
  }>;

  // Transaction Balancing
  balanceUnsealedTransaction(tx: string): Promise<{ tx: string }>;
  balanceSealedTransaction(tx: string): Promise<{ tx: string }>;

  // Transaction Submission
  submitTransaction(tx: string): Promise<void>;

  // Proving (optional - can delegate to proving server)
  getProvingProvider(keyProvider): Promise<ProvingProvider>;
}
```

**Implementation Options:**
1. **WebView injection** (Android WebView with JS interface)
2. **Deep linking** (custom URL scheme)
3. **Local HTTP server** (wallet runs local API server)

**Recommended:** WebView injection (most compatible with existing DApps)

---

#### 3.2. Transaction Balancing
**Purpose:** Add fee inputs/outputs to DApp-created transactions

**Process:**
1. **Receive unproven/proven transaction** from DApp
2. **Calculate fees** required
3. **Select UTXOs** to pay fees (coin selection)
4. **Add inputs** (spending UTXOs)
5. **Add outputs** (change back to user)
6. **Balance transaction** (inputs = outputs + fees)
7. **Seal transaction** (cryptographic binding)

**Dependencies:**
- ✅ Phase 2B: UTXO selection (already have)
- ✅ Phase 2C: Transaction builder (already have)
- ⏸️ Phase 4B-Shielded: Shielded UTXO tracking (need for shielded contracts)

**New Components:**
- `ContractTransactionBalancer.kt` - Balance contract transactions
- `FeeEstimator.kt` - Calculate transaction fees
- `TransactionSealer.kt` - Seal transactions with signatures

---

#### 3.3. Transaction Submission
**Purpose:** Relay balanced transactions to Midnight network

**Process:**
1. **Serialize transaction** (SCALE codec)
2. **Submit to node** via RPC: `author_submitExtrinsic`
3. **Watch for finalization**
4. **Update UTXO pool** (mark spent, add new outputs)
5. **Notify DApp** of status

**Dependencies:**
- ⏸️ Phase 2E: Transaction submission (can reuse)
- ✅ Phase 4B: Indexer subscription (already have)

**New Components:**
- Extend `TransactionSubmitter.kt` for contract transactions
- Add contract-specific status tracking

---

#### 3.4. State Management
**Purpose:** Track contract instances and private state

**Requirements:**
- **Contract instance tracking**: Store deployed contract addresses
- **Private state providers**: Access user's private state for witnesses
- **Witness functions**: Provide data to DApp circuits
- **State synchronization**: Keep private state in sync with on-chain state

**Database Schema:**
```kotlin
@Entity(tableName = "contract_instances")
data class ContractInstanceEntity(
    @PrimaryKey val contractAddress: String,
    val contractName: String,
    val circuitIds: List<String>,
    val privateStateId: String?,
    val deployedAt: Long,
    val lastInteractionAt: Long
)

@Entity(tableName = "private_state")
data class PrivateStateEntity(
    @PrimaryKey val stateId: String,
    val contractAddress: String,
    val encryptedState: ByteArray,  // Encrypted with user's keys
    val version: Int,
    val updatedAt: Long
)
```

**New Components:**
- `ContractStateManager.kt` - Manage contract private state
- `WitnessProvider.kt` - Provide witness data to DApp
- `ContractInstanceDao.kt` - Database access

---

#### 3.5. Proving Provider (Optional)
**Purpose:** Generate ZK proofs for circuits

**Options:**
1. **DApp handles proving** (default)
   - DApp uses proving server
   - Wallet doesn't need to prove
   - Simpler wallet implementation

2. **Wallet handles proving** (advanced)
   - Wallet delegates to proving server
   - Better privacy (DApp doesn't see proving server)
   - More complex implementation

**Recommendation:** Start with option 1 (DApp proves), add option 2 later if needed

**If implementing option 2:**
- `ProvingProvider.kt` - Interface for proof generation
- `ProvingServerClient.kt` - HTTP client to proving server
- Proving server URL configuration

---

#### 3.6. UI/UX Components
**Purpose:** User interaction for contract transactions

**Required Screens:**

1. **DApp Connection Request**
   - Show DApp origin/URL
   - List requested permissions
   - Approve/Deny buttons
   - Remember choice checkbox

2. **Transaction Confirmation**
   - Contract name & function
   - Function arguments (if readable)
   - Estimated fees
   - Balance changes (inputs/outputs)
   - Confirm/Reject buttons

3. **Transaction Status**
   - Building transaction...
   - Balancing...
   - Submitting...
   - Waiting for finalization...
   - Success / Error

4. **Connected DApps Management**
   - List of connected DApps
   - Revoke access per DApp
   - View last interaction time

**New Components:**
- `DAppConnectionScreen.kt` - Connection approval UI
- `ContractTransactionConfirmationDialog.kt` - Transaction review
- `TransactionStatusDialog.kt` - Status updates
- `ConnectedDAppsScreen.kt` - Manage connections

---

## 4. Contracts vs Simple Transfers

| Aspect | Simple Transfer | Contract Call |
|--------|----------------|---------------|
| **Transaction Type** | Token movement only | Circuit execution + state change |
| **Proof Generation** | None | Zero-knowledge proofs required |
| **Wallet Role** | Create entire transaction | Balance and submit only |
| **Data Flow** | Wallet → Network | DApp → Wallet → Network |
| **Privacy** | Public or shielded amounts | Private state + ZK proofs |
| **Complexity** | Low (already implemented) | High (needs full DApp connector) |

**Key Difference:** Contracts use **zero-knowledge proofs** to enforce rules while preserving privacy.

---

## 5. Data Structures Required

### Transaction Types

**Unproven Transaction:**
```kotlin
data class UnprovenTransaction(
    val contractAddress: String,
    val circuitId: String,
    val publicTranscript: List<Operation>,
    val privateInputs: ByteArray,
    val privateOutputs: ByteArray,
    val zswapLocalState: ZswapLocalState?
)
```

**Proven Transaction:**
```kotlin
data class ProvenTransaction(
    val unprovenTx: UnprovenTransaction,
    val proofs: List<ZkProof>,
    val publicData: PublicTranscript
)
```

**Sealed Transaction:**
```kotlin
data class SealedTransaction(
    val provenTx: ProvenTransaction,
    val intent: Intent,  // Fee payment intent
    val signatures: List<Signature>,
    val bindingSignature: Signature
)
```

### Contract Instance

```kotlin
data class ContractInstance(
    val contractAddress: String,
    val contractName: String,
    val circuits: Map<String, CircuitId>,
    val privateStateId: String?,
    val constructor: CircuitId,
    val deployedAt: Long
)
```

---

## 6. Implementation Phases

### Phase 5A: DApp Connector Foundation (8-10h)

**Goal:** Enable browser DApps to connect to wallet

**Deliverables:**
- [ ] `DAppConnectorService.kt` - WebView JS bridge
- [ ] Inject `window.midnight.mnLace` API
- [ ] Connection permission management
- [ ] Service URI configuration (indexer, prover, node)
- [ ] Connection approval UI
- [ ] Connected DApps database schema

**Testing:**
- [ ] WebView injection works
- [ ] DApp can detect wallet
- [ ] Connection approval flow
- [ ] Permission persistence

**Estimate:** 8-10 hours

---

### Phase 5B: Transaction Balancing (6-8h)

**Goal:** Balance DApp-created transactions with fee inputs

**Deliverables:**
- [ ] `ContractTransactionBalancer.kt` - Add fee inputs/outputs
- [ ] `FeeEstimator.kt` - Calculate transaction fees
- [ ] `TransactionSealer.kt` - Seal with signatures
- [ ] Parse incoming transaction JSON
- [ ] Integrate with existing UTXO selection (Phase 2B)
- [ ] Serialize balanced transaction

**Testing:**
- [ ] Can parse DApp transaction
- [ ] Fee calculation accurate
- [ ] UTXO selection works for fees
- [ ] Transaction balances correctly
- [ ] Sealing produces valid signatures

**Estimate:** 6-8 hours

---

### Phase 5C: Transaction Submission & Status (3-4h)

**Goal:** Submit contract transactions and track status

**Deliverables:**
- [ ] Extend `TransactionSubmitter.kt` for contracts
- [ ] Contract transaction serialization (SCALE)
- [ ] Status tracking (building → finalized)
- [ ] UTXO pool updates after finalization
- [ ] Notify DApp of status changes

**Testing:**
- [ ] Can submit contract transaction
- [ ] Status updates correctly
- [ ] UTXOs marked spent
- [ ] DApp receives notifications

**Estimate:** 3-4 hours

---

### Phase 5D: State Management (4-6h)

**Goal:** Track contract instances and private state

**Deliverables:**
- [ ] `ContractStateManager.kt` - Private state management
- [ ] `ContractInstanceDao.kt` - Database operations
- [ ] Database schema (contract instances, private state)
- [ ] State encryption with user keys
- [ ] Witness data provider
- [ ] State synchronization

**Testing:**
- [ ] Can store contract instances
- [ ] Private state encrypted correctly
- [ ] Witness provider returns correct data
- [ ] State updates on transaction

**Estimate:** 4-6 hours

---

### Phase 5E: UI/UX (4-6h)

**Goal:** User interfaces for contract interactions

**Deliverables:**
- [ ] `DAppConnectionScreen.kt` - Connection approval
- [ ] `ContractTransactionConfirmationDialog.kt` - Review transaction
- [ ] `TransactionStatusDialog.kt` - Status updates
- [ ] `ConnectedDAppsScreen.kt` - Manage connections
- [ ] Transaction details formatting
- [ ] Error handling UI

**Testing:**
- [ ] Connection approval flow
- [ ] Transaction confirmation shows correct data
- [ ] Status updates in real-time
- [ ] Can revoke DApp access

**Estimate:** 4-6 hours

---

## 7. Total Estimate Breakdown

| Phase | Component | Estimate | Dependencies |
|-------|-----------|----------|--------------|
| 5A | DApp Connector Foundation | 8-10h | None (can start now) |
| 5B | Transaction Balancing | 6-8h | ✅ Phase 2B, 2C |
| 5C | Transaction Submission | 3-4h | ⏸️ Phase 2E |
| 5D | State Management | 4-6h | None |
| 5E | UI/UX | 4-6h | 5A (connection flow) |

**Total:** 25-34 hours (revised from 15-20h)

**Why More Than Original Estimate?**
- Original plan underestimated DApp connector complexity
- Didn't account for state management requirements
- Transaction balancing more complex than transfers
- UI/UX for connection approval not considered

---

## 8. Dependencies & Prerequisites

### Completed (Can Use)
- ✅ Phase 1B: Shielded keys (for witness data)
- ✅ Phase 2A: Transaction models (Intent, UnshieldedOffer)
- ✅ Phase 2B: UTXO selection (for fee payment)
- ✅ Phase 2C: Transaction builder (balancing logic)
- ✅ Phase 2D-FFI: Transaction signing (seal transactions)
- ✅ Phase 4B: WebSocket subscriptions (status tracking)

### Required Before Starting
- ⏸️ Phase 2E: Transaction submission layer (RPC client)
- ⏸️ Phase 2F: Send UI (transaction status patterns)

### Required for Shielded Contracts
- ⏸️ Phase 4B-Shielded: Shielded balance tracking
- ⏸️ Phase 3: Shielded transactions (understand shielded flow)

**Recommendation:** Complete Phase 2 (Unshielded TX) fully before starting Phase 5.

---

## 9. Architecture Decisions

### Decision 1: WebView vs Deep Linking
**Chosen:** WebView injection
**Reason:**
- Most compatible with existing DApps (expect `window.midnight`)
- Standard pattern (MetaMask, WalletConnect)
- Better UX (no app switching)

**Trade-off:**
- More complex (WebView setup, JS bridge)
- Security considerations (validate DApp origin)

---

### Decision 2: Proving Responsibility
**Chosen:** DApp handles proving (default)
**Reason:**
- Simpler wallet implementation
- Proving is computationally expensive
- DApps already have proving infrastructure

**Future:** Add wallet proving delegation later if needed

---

### Decision 3: State Storage
**Chosen:** Encrypted database
**Reason:**
- Private state must persist across sessions
- Need efficient queries (by contract, by DApp)
- Encryption protects sensitive data

**Trade-off:**
- More complex than in-memory
- Need key derivation for state encryption

---

## 10. Security Considerations

### Critical Security Requirements

**1. Origin Validation:**
- Verify DApp origin before granting access
- Prevent phishing attacks
- Use domain allowlist for known DApps

**2. Transaction Review:**
- Show human-readable transaction details
- Warn on unusual fees or large amounts
- Require explicit user confirmation

**3. State Encryption:**
- Encrypt private state with user's keys
- Never expose raw private state to DApp
- Zeroize decrypted state after use

**4. Connection Management:**
- Allow revoking DApp access
- Session expiration
- Re-authentication for sensitive operations

**5. Proving Data:**
- If wallet proves: isolate proving in separate process
- Validate proof structure before submission
- Don't trust DApp-provided proofs blindly

---

## 11. Testing Strategy

### Unit Tests
- [ ] DApp connector API surface
- [ ] Transaction balancing logic
- [ ] Fee estimation accuracy
- [ ] State encryption/decryption
- [ ] Witness data provision

### Integration Tests
- [ ] WebView injection
- [ ] End-to-end transaction flow
- [ ] Connection approval flow
- [ ] State persistence

### Manual Testing with Real DApp
- [ ] Deploy test contract (bulletin board)
- [ ] Connect wallet to DApp
- [ ] Call circuit from DApp
- [ ] Verify transaction on-chain
- [ ] Check state updates

**Test DApp:** Use Midnight's example bulletin board DApp

---

## 12. Known Challenges & Risks

### High Risk 🔴

**1. WebView JavaScript Bridge**
- **Risk:** Complex integration, security vulnerabilities
- **Mitigation:** Use Android WebView best practices, validate all inputs
- **Fallback:** Deep linking if WebView proves too difficult

**2. Transaction Parsing**
- **Risk:** Complex transaction format, parsing errors
- **Mitigation:** Use midnight-ledger serialization (via FFI)
- **Testing:** Extensive testing with test vectors

### Medium Risk 🟡

**3. State Synchronization**
- **Risk:** Private state out of sync with on-chain state
- **Mitigation:** Rebuild state from transaction history
- **Testing:** Test state recovery after long offline

**4. Fee Estimation**
- **Risk:** Underestimate fees, transactions rejected
- **Mitigation:** Conservative fee calculation, add buffer
- **Testing:** Test with various transaction sizes

### Low Risk 🟢

**5. UI/UX**
- **Risk:** Confusing to users (ZK proofs are complex)
- **Mitigation:** Clear explanations, tooltips
- **Testing:** User testing with non-technical users

---

## 13. Example Flow (Bulletin Board DApp)

**User Flow:**

1. **User opens DApp in browser**
   - DApp checks `window.midnight`
   - Finds `mnLace` (Kuira wallet)

2. **DApp requests connection**
   - `await mnLace.enable()`
   - Wallet shows connection approval dialog
   - User approves, grants access

3. **User posts message**
   - DApp: `contract.callTx.post("Hello World")`
   - Circuit executes with witness (secret key)
   - DApp generates ZK proof
   - Creates unproven transaction

4. **DApp sends to wallet for balancing**
   - `wallet.balanceUnsealedTransaction(tx)`
   - Wallet shows confirmation dialog:
     - "Post message to Bulletin Board"
     - Fee: 0.1 DUST
     - Approve / Reject

5. **User approves**
   - Wallet selects UTXOs for fee
   - Balances transaction
   - Seals with signatures

6. **Wallet submits transaction**
   - `wallet.submitTransaction(tx)`
   - Wallet shows "Submitting..." status
   - Transaction included in block

7. **DApp receives confirmation**
   - Wallet notifies DApp: "Transaction finalized"
   - DApp updates UI: "Message posted!"

---

## 14. Comparison with Other Wallets

### MetaMask (Ethereum)
- **Similar:** WebView injection, transaction signing
- **Different:** No ZK proofs, simpler transaction model

### Lace (Midnight Web Wallet)
- **Similar:** Full contract support, proving delegation
- **Different:** Browser extension (we're native Android)
- **We can reuse:** API surface, UX patterns

### WalletConnect (Multi-chain)
- **Similar:** Deep linking, QR code pairing
- **Different:** Remote signing (we're local)
- **Could adopt:** As alternative to WebView

---

## 15. Future Enhancements (Post-MVP)

**Phase 5+ (Optional Features):**

- **Proving delegation** (wallet as proving provider) - 4-6h
- **WalletConnect support** (alternative to WebView) - 6-8h
- **Contract ABI explorer** (understand contract functions) - 3-4h
- **Transaction history** (view past contract interactions) - 4-5h
- **Gas/fee optimization** (reduce transaction costs) - 3-4h
- **Multi-DApp management** (switch between connected DApps) - 2-3h

**Total Optional:** 22-30h

---

## 16. Recommendation

**Should we implement Phase 5 now?**

**Arguments FOR:**
- Enables full DApp ecosystem
- Core Midnight value proposition (privacy + smart contracts)
- DApp developers want mobile wallet support

**Arguments AGAINST:**
- 25-34 hours is significant investment
- Should finish Phase 2 (Unshielded TX) first
- Need Phase 4B-Shielded before shielded contracts

**Recommendation:**
1. ✅ Finish Phase 2 (Unshielded TX) - 2-3h remaining
2. ✅ Implement Phase 4B-Shielded (Shielded Balances) - 8-12h
3. ✅ Implement Phase 3 (Shielded TX) - 20-25h
4. ✅ Then Phase 5 (Contract TX) - 25-34h

**Reason:** Need complete transaction functionality (unshielded + shielded) before adding contract complexity.

---

## 17. Files to Reference (Midnight SDK)

**Contract Interaction:**
- `/midnight-dapp-connector-api/src/api.ts` - DApp connector interface
- `/midnight-js/packages/contracts/src/deploy-contract.ts` - Deployment
- `/midnight-js/packages/contracts/src/submit-call-tx.ts` - Circuit calls
- `/midnight-js/packages/contracts/src/call.ts` - Circuit execution

**Example DApps:**
- `/example-bboard/api/src/index.ts` - Bulletin board implementation
- `/example-bboard/ui/src/` - DApp UI patterns

**Documentation:**
- `/midnight-docs/blog/2025-04-16-connect-dapp-lace-wallet.mdx` - Lace wallet connection
- `/midnight-docs/docs/guides/` - Contract development guides

---

## 18. Success Criteria

**Phase 5 Complete When:**
- ✅ DApp can connect to wallet via WebView
- ✅ User can approve/deny connection
- ✅ DApp can send contract transaction for balancing
- ✅ Wallet balances transaction with fee inputs
- ✅ User can review and approve transaction
- ✅ Wallet submits transaction to network
- ✅ Transaction finalizes on-chain
- ✅ DApp receives confirmation
- ✅ Private state updates correctly
- ✅ Test DApp (bulletin board) works end-to-end
- ✅ All unit tests pass (>80% coverage)

---

## Conclusion

Contract transactions are **significantly more complex** than originally estimated (15-20h → 25-34h). They require a full DApp connector infrastructure similar to MetaMask or WalletConnect.

**Key Takeaway:** Wallet acts as transaction balancer and relayer, while DApps handle business logic and proving. This separation of concerns is fundamental to Midnight's architecture.

**Next Steps:**
1. Review this investigation with team
2. Decide on implementation priority
3. Update main PLAN.md with revised estimates
4. Consider phased rollout (MVP → Full Features)
