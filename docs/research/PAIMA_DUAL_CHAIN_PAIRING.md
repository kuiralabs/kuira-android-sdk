# Paima Dual-Chain Pairing — Research Findings

Findings from studying `effectstream/safe-solver` (Paima Engine reference template for an on-chain game spanning Arbitrum + Midnight). Captured for future work, primarily relevant to Midnight Kicks but applies to any multi-chain game pattern we consider.

Source studied: https://github.com/effectstream/safe-solver

---

## Mental model

Paima Engine treats blockchains as **authenticated, ordered, censorship-resistant command logs**. It does not put game logic on-chain. Game logic is a deterministic off-chain state machine that consumes the merged event stream from N chains and projects it into a database.

> The chain is a queue. The DB is a cache. The state machine is the rules. The batcher is the postman.

A node operator runs the engine. Anyone can replay the chain history and derive the same DB state, so the operator cannot lie about *what happened* — only potentially censor or reorder by withholding tx submission.

## What the EVM contract actually contains

`PaimaL2Contract` is fewer than 50 meaningful lines. It exposes one user-facing entrypoint:

- `paimaSubmitGameInput(bytes data) payable` — emits `PaimaGameInteraction(sender, data, value)`
- Owner-only fee withdrawal, owner change, fee adjustment

The contract is game-agnostic. It does not parse `data`, validate it, or know the game exists. Every Paima game on EVM inherits this with a one-line constructor.

## What the Midnight contract actually contains

In Safe Solver, a single Compact circuit `storeValue(payload: Opaque<"string">)` that writes an opaque string to a ledger slot. Also game-agnostic, also opaque to the EVM side.

## How the two chains are reconciled

They aren't, cryptographically. Paima Engine reads both event streams and merges them into a single ordered command sequence by **NTP wall-clock timestamp** (NTP is configured as the "main" sync protocol; EVM and Midnight are "parallel" sync protocols anchored to it). The state machine sees one logical input stream.

Neither contract references the other. Neither stores hashes, signatures, or pointers tying its event to a counterpart event on the other chain. There is no on-chain coupling whatsoever.

## Gas reconciliation

Safe Solver does not solve cross-chain gas. The model is:

- User signs a message in browser (zero cost)
- A batcher service receives signed messages, submits them on Arbitrum via `paimaSubmitGameInput`, paying L2 gas from its own EOA (`BATCHER_EVM_SECRET_KEY`)
- The contract `fee` parameter is zero in dev; in prod it accrues to the owner but is still paid by the batcher, not the user

To charge end users in DUST while still posting to Arbitrum, the operator must build a reconciliation layer:

- User pays DUST on Midnight to an operator-controlled address (or pre-deposits into a credit balance)
- State machine validates the DUST payment off-chain
- Batcher relays the EVM action
- Operator periodically swaps collected DUST → ETH off-chain to refill the batcher

There is no atomic DUST-pays-ETH-gas primitive. It is always operator-mediated reconciliation. The operator absorbs volatility, spam, and refill risk.

If a game's authoritative log is Midnight (because that is where stakes live), the EVM side may be unnecessary, and the gas reconciliation problem disappears entirely.

## The pairing problem

If a single user action is meant to span both chains (one EVM event + one Midnight event that "belong together"), Paima's base model gives no guarantees that:

- Both halves arrive
- The halves submitted are the ones the user intended to pair
- A malicious operator does not selectively delay, drop, or reorder one side
- A user does not submit one half twice

Pairing must be enforced explicitly. Three patterns, in increasing strength:

### Pattern 1 — Shared correlation ID, state-machine enforced

Both payloads carry the same opaque `actionId`. The state machine parks halves in a pending table and only applies effects when both arrive. Sweeper expires unmatched halves.

- Trusts the operator to run the state machine honestly
- Cheapest to build, no contract changes
- Insufficient when operator has incentive to cheat outcomes

### Pattern 2 — Hash commitment on one chain, reveal on the other

The EVM payload contains `hash(midnightPayload || salt)`. The Midnight payload contains `(midnightPayload, salt)`. State machine recomputes the hash on reveal and rejects mismatches.

- Cryptographic binding: reveal cannot be substituted for a different value
- Operator can still censor the reveal, but censorship is publicly detectable
- Natural fit for commit-reveal games (matches the pattern already chosen for Midnight Kicks)

### Pattern 3 — Collateral-backed, slashable

Custom Solidity escrow (not stock `PaimaL2Contract`) holds stakes. Non-revealers can be slashed by their counterparty after a timeout. Real game-theoretic enforcement, not just operator trust.

- Required when meaningful money is at stake
- Custom contracts replace or wrap `PaimaL2Contract` on the EVM side
- Compact equivalents on the Midnight side for shielded value flows

## What Safe Solver actually demonstrates

The template ships the plumbing (two chains feeding one state machine) but no pairing logic. Its `event_midnight` handler is a `console.log`. The repo is a starting skeleton, not a working multi-chain coupling reference.

## Implications for Midnight Kicks

- Stakes belong in real escrow contracts (Pattern 3), not in a `PaimaL2Contract` event log
- Commit-reveal moves benefit from hash binding across chains (Pattern 2)
- A pre-deposited DUST credit balance fits high-frequency per-action signing better than a per-action DUST tx
- The dual-chain split is optional, not required — a Midnight-only design eliminates the gas reconciliation question and may be the right answer
- The Paima Engine pattern is still useful as a state-machine framework even on a single chain

## Open questions to revisit before adopting Paima

- Does relying on a single operator (batcher + engine + DB) match the trust model we want for Kuira-adjacent games?
- Can we run the engine ourselves vs. depend on a third-party operator?
- Does an EVM leg add real value over a Midnight-only design for any product we are likely to ship?
- How does this interact with [[project_ows_alignment]] — does using Paima foreclose any OWS-compatible identity choices?

## References

- `effectstream/safe-solver` — template studied
- `@paimaexample/*` 0.10.0 — engine packages
- Compact 0.27.0 — Midnight contract language
- Project memory: [[project_kicks_contract_design]], [[project_midnight_kicks]]
