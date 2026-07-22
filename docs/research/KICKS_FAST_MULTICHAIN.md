# Kicks — Faster Moves, Multi-Chain Commits

A plan to make Midnight Kicks moves feel fast by getting the high-frequency game
inputs off Midnight's per-move proving — optionally committing moves on a fast
chain (the safe-solver / EffectStream pattern), with Midnight reserved for
shielded stakes. Kuira is the on-device client.

Builds directly on [PAIMA_DUAL_CHAIN_PAIRING.md](./PAIMA_DUAL_CHAIN_PAIRING.md).

---

## The diagnosis

Every Kicks move today is a Midnight ZK proof + a tx + finality (the commit and
reveal circuits). On-device proving is seconds per move. Midnight is excellent at
hiding money and slow as a per-move input bus — you pay the proving tax on every
single interaction. That tax is the bottleneck, not the network.

## The mechanism (why this works)

Paima's model — EffectStream is Paima Engine's successor: *the chain is a queue,
the DB is a cache, the state machine is the rules.* Move the high-frequency inputs
off Midnight onto a fast chain's cheap events; an off-chain deterministic state
machine runs the game and projects state into a DB cache; Midnight is touched only
for the stakes (low-frequency, where ZK earns its cost). Moves escape the proving
tax, so they feel fast. State reads come from the cache, so they feel fast too.

## The reality to design around

EffectStream is a **server engine, not an on-device library:**

- The state machine, sync, database, and batcher run server-side (Bun / Postgres).
  The engine is not packaged to run on a phone — even though Paima determinism
  means state is re-derivable from chain data in principle.
- Its frontend SDK is browser-coupled (injected wallets, `window`/`document`
  gating). It does not run in a bare mobile JS engine; the only on-device-JS route
  is a WebView.

So the architecture is: **a hosted EffectStream node, with a thin native Kuira
client talking to it.** Running the engine on the phone is a non-goal. You operate
a node.

## The strategic fork — decide this first

**Route A — full dual-chain (safe-solver).** Moves go on a fast L2 as cheap events
(no ZK), stakes stay on Midnight, the EffectStream node reconciles. Fastest moves.
Cost: operate a node + a batcher + cross-chain gas reconciliation (DUST → ETH, the
operator-mediated tax the Paima doc details), an EVM signer on the phone (Kuira is
Midnight-only) or a batcher for the EVM leg, and tracking EffectStream's moving
alpha branch.

**Route B — Midnight-only, Paima-pattern.** One chain. Game logic moves off-chain
into a state machine; moves become cheap opaque Midnight events; ZK is reserved for
stakes and settlement. Simpler — no second chain, no gas reconciliation, no
operator for the moves. Smaller speedup: still Midnight-tx-bound per move, but with
lighter circuits and off-chain logic.

**Recommendation:** don't commit to operating a Paima node until the speed win is
proven. Validate it cheaply first (Phase 0). Lean Route B unless "moves feel
instant" is the hard bar and operating the full stack is acceptable.

## Phases

**Phase 0 — Validate the speed win (cheap, no EffectStream yet).**
Prove the bottleneck is the per-move proving and that escaping it delivers the feel
you want. Prototype a single Kicks move as a cheap commit off the proving path and
measure move latency against today. Decision gate: is the win worth an off-chain
state machine — and, for Route A, a second chain and an operator?

**Phase 1 — The Kicks state machine, off-chain, single chain.**
Model Kicks' rules (commit / reveal / resolve / settle) as state-transition
functions in an EffectStream node — or a minimal custom state machine — anchored to
Midnight. Moves become cheap events; the rules live off-chain. No second chain yet.

**Phase 2 — Kuira as the thin client.**
A native client to the node: REST for reads, live updates over WebSocket, and
on-device Midnight signing for the stake legs. The Kicks app reads game state from
the node and drives moves through it. Reuse EffectStream's pure encoding/crypto TS
only if it proves portable; otherwise it's native Kotlin.

**Phase 3 — Add the fast chain (Route A only).**
Move the high-frequency commits onto a fast L2. Bind the two halves with
hash-commit-reveal (the Paima doc's Pattern 2 — a clean graft, since Kicks already
commits then reveals). Put stakes in collateral escrow (Pattern 3) on the EVM side
with a shielded Compact equivalent on Midnight. Stand up the batcher and the gas
reconciliation. Sign the EVM leg on the phone or via the batcher.

**Phase 4 — Harden.**
The operator / trust model, finality and a fallback when the node is unreachable,
the alpha-churn risk (pin a version or fork), and spam / refill on the batcher.

## Open decisions

- Route A or B — is the bar "instant," or "snappy without a second chain to run"?
- Operate the node ourselves vs. a third party — the operator-trust question the
  Paima doc raised.
- Track EffectStream's alpha, pin or fork it, or roll a minimal custom state
  machine. EffectStream is heavy and moving fast.
- EVM signing on the phone vs. a batcher — Kuira has no EVM signer today.
- Which fast chain, if Route A.
- Does Kicks even need the dual-chain split, or is the Midnight-only restructure
  enough — the Paima doc already flags Midnight-only as possibly the right answer.

## Why this is lighter than the Kuira Midnight port

We are not reimplementing an engine in Kotlin. The engine is someone's running
node; Kuira stays the native client and the Midnight signer it already is. The new
code is a thin client plus the Kicks state-machine rules — not a chain port.
