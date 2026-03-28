# ADR-001: Composable Zswap FFI Primitives over Monolithic Transaction Builder

**Date:** 2026-03-27
**Status:** Accepted
**Decision:** Use composable FFI primitives for shielded (zswap) transfer building instead of a single monolithic function.

---

## Context

Step 7 of the Shielded Implementation Plan requires building shielded transfer transactions in Rust, exposed via FFI to Kotlin/Android. The existing unshielded transaction flow (`serialize.rs`) uses a monolithic approach — one large FFI function (`serialize_unshielded_transaction_with_dust`) that handles coin selection, signing, dust merging, and serialization in a single call.

We needed to decide whether to follow the same monolithic pattern for shielded transactions (Option A) or decompose the operation into composable primitives (Option B).

This decision was made in the context of Kuira's roadmap beyond Phase 3:
- **Phase 7:** DApp Connector (ConnectedAPI as Android Service)
- **Phase 8:** Agent Runtime (policy engine, x402, MCP bridge)
- **Phase 10:** Game SDK
- **Agent Store:** General-purpose agent marketplace (AIP standard)

---

## Decision

**We chose Option B: composable FFI primitives.**

The shielded transfer FFI will be decomposed into independent functions, each handling one step of the transaction building process:

```
1. zswap_select_coins()       — find coins of a given token type to cover an amount
2. zswap_spend_coin()         — create Input<ProofPreimage> (spending authorization)
3. zswap_create_output()      — create Output<ProofPreimage> (encrypted coin for recipient)
4. zswap_build_offer()        — assemble Offer from inputs + outputs
5. zswap_merge_offers()       — combine two offers (e.g., transfer + contract balancing)
6. zswap_serialize_offer()    — serialize unproven Offer for proof server
7. zswap_build_transaction()  — combine proven offer + dust → final Transaction
```

A Kotlin-side `ShieldedTransactionBuilder` wraps these primitives for simple use cases (basic transfers), while advanced callers (DApp connector, agent runtime) use primitives directly.

---

## Why Not Monolithic (Option A)

A monolithic function would be simpler today (~2-3 hours less development) but creates architectural debt that compounds across multiple future phases.

### 1. ConnectedAPI Requires Composition (Phase 7)

The Midnight ConnectedAPI (which Kuira must implement for DApp compatibility) has methods that fundamentally require composable transaction building:

| ConnectedAPI Method | What It Does | Why Monolithic Fails |
|---|---|---|
| `makeTransfer(shielded)` | Build a simple shielded send | Works — but this is the only one |
| `balanceUnsealedTransaction(tx)` | Take a contract-produced tx, add wallet's shielded + dust balancing | Monolithic can't add to an existing transaction — it builds from scratch |
| `balanceSealedTransaction(tx)` | Add dust to an already-proven tx | Monolithic couples proving with building |
| `makeIntent(inputs, outputs)` | DApp defines custom inputs/outputs, wallet provides spending auth | Monolithic controls what's built — DApp can't |

With composable primitives, each ConnectedAPI method is a different orchestration of the same building blocks. With monolithic, each method needs a separate code path or a parallel implementation.

### 2. Agent Policy Enforcement (Phase 8)

The Agent Runtime introduces a policy engine: "this agent can spend up to X NIGHT per day." Policy checks must happen **between** coin selection and spending:

```
agent requests transfer
  → zswap_select_coins()           ← knows the amount
  → policy engine checks limits    ← can approve or deny
  → zswap_spend_coin()             ← only if approved
```

A monolithic function selects and spends in one atomic call — there's no interception point for policy enforcement without breaking the function apart.

### 3. Shield ↔ Unshield Operations

Moving NIGHT between shielded and unshielded ledgers requires both a ZswapOffer (shielded input) and an UnshieldedOffer (unshielded output) in the same Transaction. This is a composition of two different offer types — impossible with a monolithic function that only produces one type.

### 4. Locked Coin Selection Strategy

A monolithic function hardcodes one coin selection algorithm. Different callers need different strategies:

- **Simple transfer:** spend fewest coins (minimize proof count)
- **Agent with policy:** only spend from specific keychain
- **Contract interaction:** spend exactly the coins the contract specifies
- **Privacy optimization:** split into smaller denominations for better anonymity

Composable primitives let the caller choose coins and pass them to `zswap_spend_coin()`.

### 5. Proof System Portability (Nightstream)

Charles Hoskinson described Nightstream — a lattice-based proof engine being built with the Linux Foundation that may replace the current proof server model. With composable primitives:

- Steps 1-6 (build, select, spend, output, offer, serialize) stay identical
- Only the proving call changes (different endpoint/protocol)
- Step 7 (build_transaction) adapts to new proven format

With monolithic, proving is coupled with transaction building. Swapping the prover means rewriting the entire function.

### 6. Agent Store / AIP Compatibility (Future)

The Agent Store vision has agents composing transactions:
- An agent produces a partial offer as its "action"
- A solver or coordinator completes and balances the transaction
- Verification happens at the offer level

Composable primitives make agents first-class transaction builders. A monolithic function makes agents dependent on the wallet's specific transaction shape.

### 7. Embeddable SDK (Strategy B, Future)

If we extract the Rust FFI as a standalone SDK for other wallet developers:
- Composable primitives are the correct API surface for a library
- Different wallets have different UX flows and orchestration needs
- A monolithic function imposes our specific flow on every consumer

---

## Why Option B Works Despite More Complexity

### State Management

The primary risk with composable primitives is state consistency — callers must pass updated state between calls. This is mitigated by the **immutable state pattern** already established in `zswap_ffi.rs`:

```rust
// Every mutation returns a NEW state pointer. The old state remains valid.
let (new_state, input) = state.spend(&mut rng, &secret_keys, &coin, None)?;
// new_state has coin in pending_spends; original state is unchanged
```

This prevents accidental double-spends — the caller must explicitly use the returned state for subsequent operations.

### Kotlin Convenience Wrapper

Simple callers never touch the primitives directly:

```kotlin
class ShieldedTransactionBuilder(private val state: ZswapLocalState) {
    fun buildTransfer(seed, recipient, amount, tokenType): UnprovenOffer {
        val coins = state.selectCoins(tokenType, amount)
        val inputs = coins.map { state.spend(seed, it) }
        val output = ZswapOutput.create(recipient, amount, tokenType)
        val change = if (needsChange) ZswapOutput.create(self, changeAmt, tokenType) else null
        return ZswapOffer.build(inputs, listOfNotNull(output, change))
    }
}
```

Advanced callers (ConnectedAPI, Agent Runtime) use primitives directly with full control.

### JNI Surface Area

7 JNI bridges instead of 1 is more code, but each bridge is simpler than one massive bridge. The JNI pattern is already established (see `zswap_ffi.rs` and `dust_ffi.rs`) — each function follows the same null-check → convert → call Rust → return pattern. The marginal cost per bridge is ~30 minutes.

---

## How This Maps to Rust SDK Types

Each FFI function corresponds to a well-defined Midnight SDK operation:

| FFI Function | Rust SDK Call | Input | Output |
|---|---|---|---|
| `zswap_select_coins` | Iterate `state.coins`, filter by type, sort by value | state + token_type + amount | JSON array of QualifiedCoinInfo |
| `zswap_spend_coin` | `state.spend(&mut OsRng, &secret_keys, &coin, segment)` | state + seed + coin | new state ptr + serialized Input<ProofPreimage> |
| `zswap_create_output` | `Output::new(&mut OsRng, &coin_info, segment, &cpk, Some(epk))` | coin_info + recipient_cpk + recipient_epk | serialized Output<ProofPreimage> |
| `zswap_build_offer` | `Offer::new(inputs, outputs, transients)` | serialized inputs + outputs | serialized Offer<ProofPreimage> |
| `zswap_merge_offers` | `offer1.merge(&offer2)` | two serialized offers | merged serialized offer |
| `zswap_serialize_offer` | `tagged_serialize(&offer)` | offer | SCALE hex for proof server |
| `zswap_build_transaction` | Assemble `Transaction` with proven offer + dust + TTL | proven offer + dust + TTL | SCALE hex for node submission |

---

## Trade-offs Accepted

| Trade-off | Accepted Because |
|---|---|
| More FFI code (~2-3 extra hours) | Prevents weeks of refactoring at Phase 7 |
| More JNI bridges (7 vs 1) | Each is simple and follows established pattern |
| Kotlin orchestration complexity | Wrapped in convenience builder; only advanced callers see primitives |
| Caller must manage state pointer | Immutable pattern prevents misuse; same as existing replay_events |

---

## References

- **Shielded Implementation Plan:** `docs/planning/SHIELDED_IMPLEMENTATION_PLAN.md` (Step 7)
- **Existing monolithic pattern:** `kuira-crypto-ffi/src/serialize.rs` (`serialize_unshielded_transaction_with_dust`)
- **Existing composable pattern:** `kuira-crypto-ffi/src/zswap_ffi.rs` (immutable state returns)
- **Midnight SDK source:** `midnight-ledger/zswap/src/local.rs` (State::spend), `construct.rs` (Output::new, Offer::new)
- **WASM reference:** `midnight-ledger/ledger-wasm/src/zswap_state.rs` (spend, fromInput, fromOutput, merge)
- **ConnectedAPI spec:** `KUIRA_VISION_V1.md` Phase 7 (17 methods)
- **Agent Store vision:** `docs/planning/AGENT_STORE_VISION.md` (AIP, composability requirements)
- **CLI connector reference:** `midnight-wallet-cli/src/lib/dapp-connector.ts` (balanceUnsealedTransaction, makeIntent)
