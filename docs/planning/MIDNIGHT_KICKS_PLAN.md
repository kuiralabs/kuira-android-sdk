# Midnight Kicks — Penalty Shootout on Midnight

**Status:** Planning
**Last updated:** 2026-04-25
**Target:** FIFA World Cup 2026 (June 11 - July 19)

---

## What is Midnight Kicks?

A PvP penalty shootout game for Android, built on the Midnight
blockchain. Two players, five rounds, real stakes (PREPROD NIGHT).
Unity 3D graphics with Midnight ZK proofs under the hood.

The game IS a ZK problem: shooter commits a direction, keeper commits
a direction, neither sees the other's choice until a ZK circuit
verifies both commitments and discloses the result. No server. No
trusted third party. The contract is the referee.

## Why

1. **Teaser for Midnight mobile.** Show the ecosystem what's coming
   before Kuira ships — a real game people play, not a demo.
2. **SDK validation.** Battle-test compact-engine, connector, and
   local proof server with real users before Kuira depends on them.
3. **World Cup timing.** Natural virality — people want penalty
   shootout games during the tournament.
4. **Trojan horse for ZK.** Players don't know they're using zero-
   knowledge proofs. They just know nobody can cheat. Technology
   that disappears into the experience is the best demo.
5. **Open-source play.** The connector SDK is open-sourced for
   dApp-to-wallet integration. The Midnight Android SDK ships as a
   binary AAR. Other developers see the pattern: one dependency,
   three objects, one `call()`. The full Kuira stack stays proprietary.

## Game mechanics

### The match

- **2 players**, matched via QR code scan or shared link
- **5 rounds** per match (standard penalty shootout)
- Both players act as shooter AND keeper (alternating roles)
- Players alternate roles each round
- If tied after 5: sudden death

### Batch submission — one transaction per player

Each player submits ALL 5 choices in a single transaction. No
per-round waiting. The circuit compares all 5 rounds at once. Then
Unity plays the full match as a cinematic replay — zero blockchain
latency during the action.

```
1. CHOICE PHASE — both players pick simultaneously
   Shooter picks all 5 directions: [L, C, R, L, R]
   Keeper picks all 5 directions:  [R, L, R, C, L]
   Each submits ONE transaction (commit all 5 + nonces)

2. PROVE PHASE — one ZK circuit runs
   Circuit compares all 5 rounds at once
   Results: [GOAL, SAVE, GOAL, GOAL, SAVE] → 3-2
   Single proof, single verification

3. REPLAY PHASE — Unity plays the match
   Stadium intro, crowd noise (masks any remaining latency)
   Round 1: ball flight... GOAL! (1-0)
   Round 2: ball flight... SAVE! (1-0)
   Round 3: ball flight... GOAL! (2-0)
   Round 4: ball flight... GOAL! (2-1)  ← opponent's turn
   Round 5: ball flight... SAVE! (3-2)  ← match result
   Full drama. Zero waiting. Pure football.
```

**Why batch is better than per-round:**

| Aspect | Per-round | Batch |
|--------|-----------|-------|
| Transactions per match | 20 (5 rounds × 2 players × 2 ops) | 2 (one per player) |
| Proof time felt by user | 5× during gameplay | Once, before replay |
| Gas cost | 20× | 2× |
| UX during rounds | Interrupted, "blockchain-y" | Cinematic, pure football |
| Cheating possible | No | No |

The single proof time (5-10 seconds) is masked by a stadium flyover,
player walk-out, and crowd buildup — feels like a real broadcast
intro, not a loading screen.

### Sudden death — batches of 5

If tied after regulation (e.g., 3-3), sudden death begins. Both
players submit another batch of 5 choices. The circuit evaluates
round-by-round and STOPS at the first decisive round:

```
SD Round 1: both score   → continue
SD Round 2: both miss    → continue
SD Round 3: P1 scores, P2 misses → P1 WINS
SD Rounds 4-5: never revealed (unnecessary)
```

**ZK property:** unrevealed sudden death rounds stay private. The
circuit only discloses results up to the decisive round. The opponent
never learns your strategy for rounds that didn't matter.

If still tied after a sudden death batch → another batch of 5.
Repeat until resolved.

### Why this can't be cheated

- Each player commits all choices before the other reveals. The
  commitment is a hash of the 5 choices + nonces, stored as private
  state in a single transaction.
- The ZK circuit proves the revealed choices match the commitments
  without exposing the raw choices to the opponent.
- A player who committed [L,C,R,L,R] cannot later claim [R,R,R,R,R].
  The proof would fail.
- No server sees both players' choices. The contract verifies.

## Stakes

- **PREPROD NIGHT** — not real money, but real blockchain interaction.
  Each match has a configurable stake (default: 1 NIGHT).
- Winner takes the pot. Draw = both get their stake back.
- Proves the payment flow works: stake → escrow in contract → payout
  to winner.
- When mainnet launches, the same contract works with real NIGHT.

## Matchmaking

Simple, no central server required:

### QR code (in-person)
```
Player A: opens Midnight Kicks → "Create Match" → shows QR code
Player B: scans QR code → joins match
Both: see each other's address (short format), match begins
```

### Shared link (remote)
```
Player A: "Create Match" → generates midnight://kicks?match=<id>
Player A: shares link via any messaging app
Player B: taps link → Midnight Kicks opens → joins match
```

Both methods use standard Android deep links (`midnight://` URI
scheme). The match ID is the deployed contract address on PREPROD.
Pairing is built directly in Kicks — no connector SDK needed.

## Architecture

Three layers, cleanly separated:

- **Unity (game layer)** — 3D stadium, ball physics, animations,
  choice UI, replay system. Renders full-screen via UaaL. Knows
  nothing about blockchain.
- **Kotlin (blockchain layer)** — Midnight Android SDK for contract
  interaction, pairing logic (QR + deep links), UaaL bridge for
  passing events to/from Unity.
- **Compact contract (on-chain)** — deployed on PREPROD. Manages
  match state, commit-reveal, scoring, stakes, payouts.

### Contract concepts

- **Private state:** each player's 5 choices + nonces (hidden)
- **Public ledger:** match participants, scores, round results,
  winner, stake amount, match phase
- **Circuits:** create match (deploy + escrow), join, commit batch,
  resolve regulation, commit/resolve sudden death, claim payout
- **Witnesses:** generate commitment hash, find decisive SD round

### Unity ↔ Kotlin bridge

UaaL renders Unity full-screen as an Android Activity. Communication
is JSON strings both directions — Kotlin sends match results to
Unity for replay, Unity sends player choices to Kotlin for
blockchain submission. The bridge is simple message passing.

### Proof latency = broadcast intro

On-device proving happens ONCE per match (batch). The 5-10 second
wait is masked by a stadium flyover / crowd buildup / player walk-out
— feels like a real TV broadcast intro, not a loading screen. Then
the replay plays uninterrupted.

## The Midnight Android SDK

Midnight Kicks is the first consumer of the Midnight Android SDK.
The SDK is not a side artifact for the game — it's the product.
Kicks validates it, then every Midnight dApp on Android uses it.

**One dependency, one AAR.** Bundles all 5 existing Kuira core
modules (compact-engine, crypto, indexer, ledger, network) behind
a thin facade. Native .so included. Ships as a binary AAR.

**The connector SDK ships separately** as open source — for dApps
that need wallet-to-dApp integration patterns (transports, pairing).

Kuira Wallet itself consumes the same SDK — it's just a more
capable consumer (TEE keys, biometric gates, sigil management,
cross-app state). The SDK is the foundation for both.

## What gets validated

Every feature Midnight Kicks exercises is a feature Kuira depends on:

| Feature | Kicks validates | Kuira benefits |
|---------|----------------|----------------|
| On-device proving | Proof time on real hardware | Proof server reliability |
| Contract deployment | Deploy from mobile | dApp interactions |
| Private state | Commit-reveal pattern | Shielded transactions |
| Transaction flow | Build → prove → submit | Send flow |
| QR pairing | Match joining | dApp connector |
| Deep links | `midnight://kicks?...` | `midnight://connect?...` |
| PREPROD network | Real blockchain ops | Network switching |
| Key management | Match signing keys | Wallet key management |
| User feedback | App Store reviews | UX insights |

## Repo structure

Separate repo: `midnight-kicks/`. Three main areas:
- **app/** — Android app (Kotlin + Compose + UaaL integration)
- **unity/** — Unity project (exported as UaaL module)
- **contract/** — Compact smart contract source

File structure documented in README at release time, not here.

## Key references

- Unity as a Library: https://docs.unity3d.com/Manual/UnityasaLibrary-Android.html
- Kuira compact-engine SDK: `core:compact-engine/`
- Kuira Connector: `core:connector/`
- Compact language: Midnight SDK docs
- PREPROD network config: `core:network/NetworkConfig.kt`
- QR + deep link patterns: `core:connector/transport/`
- Existing Compact example: `examples:bboard/` (BBoard contract)

## Relationship to Kuira

Midnight Kicks is NOT part of the Kuira codebase. It's a separate
repo that consumes Kuira modules as pre-built AARs. This is
intentional:

1. **Proves the SDK works standalone** — if Kicks can't build without
   the full Kuira repo, the SDK isn't self-contained enough.
2. **Tests the module boundary** — forces clean APIs between modules.
3. **Separate release cycle** — Kicks ships on its own timeline.
4. **Open-source boundary** — Kicks repo can be public (or partially
   public) without exposing Kuira internals.

After Kicks ships, the open-sourced connector SDK + documentation
become the "Build on Midnight (Android)" developer story. Other
devs follow the same pattern: Unity/native app + connector SDK +
pre-built compact-engine AAR.

---

## Two-tier identity: standalone + Kuira-enhanced

Midnight dApps exist standalone. Kuira is the upgrade, not the
prerequisite. This is core to the Kuira vision — "you shouldn't
need a wallet to use Midnight apps."

### Tier 1 — Standalone (Kuira NOT installed)

SDK embedded in the dApp. Generates its own keys (Android Keystore),
tracks UTXOs, balances/signs/submits transactions — all internally.
No external wallet process. No seed phrase. Player identity is a
keypair managed by the SDK. Works completely independently.

### Tier 2 — Enhanced (Kuira IS installed)

SDK detects Kuira on the device and transparently delegates to it.
Player gets TEE-backed keys (sigil identity), biometric approval,
cross-app identity, and their match appears in the My Sigil tab.
No code change in the dApp — the upgrade is automatic.

### Identity primitives in the SDK (research)

The SDK must generate identities that are **forward-compatible with
the sigil model.** If a standalone dApp creates a throwaway keypair,
there's no migration path when the player installs Kuira. But if
the SDK uses the same identity primitives as Kuira from the start,
the player's standalone identity IS their future sigil.

| Primitive | SDK (Tier 1) | Kuira upgrade (Tier 2) |
|-----------|-------------|----------------------|
| **Passkey (P-256)** | Generate via Android CredentialManager. Stored in Keystore. | Promote to StrongBox/TEE. Add biometric gate. Sync via Google Password Manager. |
| **DID** | Derive from passkey. Interoperable with rvcas midnightOS Passkeys format. | Manage in sigil dashboard. Show in My Sigil tab. |
| **Access key delegation** | Generate per-dApp secp256k1 key, linked to passkey via keyAuthorization. | Manage delegation policies (silent/notify/approve tiers). Revocation from My Sigil. |

**Status: all three are research phase.** Need to investigate:
- CredentialManager provider registration for passkey generation
- DID format interop with rvcas (`passkeys.rvcas.dev`)
- keyAuthorization model (how the root passkey authorizes access keys)

These primitives live in the SDK, not in Kuira. Kuira upgrades
them — it doesn't own them. A standalone dApp generates a passkey,
derives a DID, delegates an access key. Kuira wraps that with
TEE-hardened storage, biometric gates, and cross-app management.

**Key references for this investigation:**
- rvcas fake-app (consumer pattern): `git@github.com:rvcas/fake-app.git`
- midnightOS Passkeys (identity provider): `https://passkeys.rvcas.dev`
- midnightOS Passkeys embed endpoint: `https://passkeys.rvcas.dev/embed`
- webauthx (WebAuthn wrapper, P-256): `npm:webauthx@0.1.0` (by wevm, wraps `ox/webauthn`)
- Android CredentialManager API: `https://developer.android.com/identity/sign-in/credential-manager`
- Kuira sigil model: `docs/planning/KUIRA_IDENTITY_VISION.md`
- Prior rvcas analysis: `memory/project_passkey_investigation.md`

### What this means

- **For Kicks:** ships with Tier 1. Tier 2 is free when Kuira exists.
  Player identity created in Kicks carries over to Kuira seamlessly.
- **For the SDK:** one AAR packages the 5 existing Kuira core modules
  + identity primitives behind a thin facade. The developer imports
  one dependency. No Hilt, no external wallet, no `walletUrl`.
- **For Kuira:** consumes the same SDK with added TEE hardening,
  biometric gates, and sigil management on top. The SDK is the
  foundation for both.

---

## SDK gap analysis

Building Midnight Kicks as an external consumer exposes exactly what's
missing for third-party developers. Every gap found here is a gap to
fix before SDK GA.

### What's ready (proven by BBoard example)

BBoard (`examples/bboard`) is an Android dApp that executes ZK circuits
and submits transactions. Uses only compact-engine + crypto modules.
No Hilt, no Kuira internal modules. Clean developer pattern. BUT:
**BBoard requires Kuira (or `mn serve`) running as the wallet backend.**
Circuit execution and proving run locally, but transaction balancing
and submission need an external wallet process. BBoard also hardcodes
a test key and requires manual proving key installation.

**BBoard proves:** SDK config, contract handles, circuit execution,
state reading, progress tracking, typed error handling.

**BBoard does NOT prove:** standalone operation, key management,
UTXO ownership, transaction signing, contract deployment from mobile,
or automatic proving key installation.

### What's blocked (needs work in Kuira first)

| Gap | Impact |
|-----|--------|
| **SDK requires external wallet** | Biggest gap. Circuit execution + proving work locally, but balancing + signing + submission need a wallet process. The SDK needs an embedded wallet that handles the full tx pipeline internally. |
| **No contract deployment from SDK** | Only the CLI can deploy. The SDK needs a deploy API for mobile-first dApps. |
| **Proving keys not auto-downloadable** | Manual `adb push` isn't viable for Play Store. Need auto-download + cache on first launch. |
| **No QR/link pairing** | Connector handles transports (WebSocket, Binder, JsBridge) but not matchmaking/pairing. Build in Kicks or as a reusable pattern. |
| **Connector has stale Gradle deps** | 4 of 5 internal module deps are unused in source code. Easy cleanup — remove from build.gradle.kts. |

### What Kicks builds that Kuira doesn't have yet

| Feature | Kicks needs it | Kuira benefits |
|---------|---------------|----------------|
| Match pairing (QR + deep link) | Two players find each other | Becomes the connector-sdk pairing pattern |
| Batch circuit calls | 5 choices committed in one tx | Proves batch witness patterns work |
| On-chain state polling | Wait for opponent's commit | Subscription/polling pattern for SDK |
| Simple onboarding (no seed phrase) | Generate keys, start playing | Validates the "no wallet needed" UX |

---

## Implementation plan

### Phase 1: Compact contract

The contract is the foundation — everything else builds on it.

**Deliverable:** Penalty shootout contract deployed on PREPROD.

**Concepts:** Match lifecycle (create → join → commit → resolve →
payout), batch commitment scheme (5 choices hashed together),
regulation resolution (compare all 5), sudden death resolution
(stop at decisive round), stake escrow + winner payout.

**Validated by:** create match → both commit → resolve → verify
results → draw → sudden death → resolve.

### Phase 2: Midnight Android SDK (parallel with Phase 1)

**Deliverable:** Single AAR that a standalone app imports with one
Gradle line. No external wallet process needed.

**Concepts:** Thin facade over existing 5 core modules. Embedded
wallet (key gen, UTXO tracking, tx balancing, signing, submission)
replaces the external wallet dependency. Auto-download proving keys
on first launch. Native .so bundled in AAR.

**Validated by:** migrate BBoard to use the new SDK without
`mn serve` running. If BBoard works standalone, the SDK is ready.

### Phase 3: Unity game

**Deliverable:** Unity project that plays a 5-round penalty replay
from JSON input, and collects 5 direction choices from the player.

**Concepts:** Buy Asset Store template for 3D assets + animations.
Strip single-player AI, build batch choice UI (pick 5 directions),
build replay system (play 5 rounds from results JSON), build stadium
intro cinematic (masks proof latency). Export as UaaL module.

### Phase 4: Android app — native layer

**Deliverable:** Working Android app that pairs two players, submits
choices to PREPROD, and plays the match in Unity.

**Concepts:** Compose screens (onboarding, lobby, waiting, results,
leaderboard). UaaL integration for Unity game. SDK wiring for
contract calls. QR + deep link pairing for matchmaking. State
polling to detect when opponent has committed.

### Phase 5: Integration + polish

**Concepts:** E2E testing on two physical devices. Proof latency
tuning (adjust stadium intro to match real proof time). Error
handling (network failures, opponent disconnect, proof failures).
APK size audit (Unity + native .so — target < 100MB). Play Store
listing prep.

### Phase 6: Release

Ship before or during World Cup opening. Closed beta → open beta →
announce → monitor (crash reports, proof success rates, match
completion rates).

---

## SDK friction log

Track every friction point encountered while building Kicks. Each
entry becomes a Kuira SDK improvement task.

| # | Friction | Severity | SDK fix |
|---|---------|----------|---------|
| | (populate during implementation) | | |

This log is the primary deliverable for the Kuira SDK team. If
building Kicks is hard, building any Midnight dApp is hard.

---

## Decision log

| Decision | Choice | Why |
|----------|--------|-----|
| Batch vs per-round | Batch (5 choices, 1 tx) | 2 transactions per match vs 20. Cinematic replay vs interrupted gameplay. |
| Sudden death | Batches of 5, stop at decisive round | Unrevealed rounds stay private (ZK property). No infinite single-round loops. |
| Unity vs native rendering | Unity (UaaL) | 3D stadium, ball physics, cinematic cameras. Can't do this in Compose. |
| Standalone vs inside Kuira | Standalone repo | Tests SDK boundaries. Separate release cycle. Open-source boundary. |
| Pairing approach | Built in Kicks (QR + deep links) | Connector's transport layers (WebSocket, Binder, JsBridge) aren't needed for matchmaking. QR + deep links are simpler. Connector ships separately as open-source SDK for dApps that need wallet integration. |
| Key management | MidnightWallet (embedded in SDK) | No seed phrase for a game. One-tap onboarding. SDK handles key gen, UTXO tracking, balancing, signing, submission internally. |
| Proving | On-device (local) | Proves the local prover works on real hardware. No server dependency. |
| Network | PREPROD only | Real blockchain, test tokens. Same contract works on mainnet later. |
