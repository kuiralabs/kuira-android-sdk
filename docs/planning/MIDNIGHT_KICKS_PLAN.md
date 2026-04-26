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

```
Midnight Kicks (Android app)
│
├── Unity (game layer — Unity as a Library / UaaL)
│   ├── 3D stadium, goal, ball, players
│   ├── Shooter aiming UI (drag to aim)
│   ├── Keeper positioning UI (drag to dive)
│   ├── Ball flight + save/goal animations
│   ├── Match lobby, score display, result screen
│   ├── Leaderboard UI
│   └── UaaL bridge → sends/receives events to Kotlin
│
├── Kotlin (blockchain layer — native Android)
│   ├── midnight-sdk.aar (single dependency)
│   │   ├── MidnightSdk — entry point, config
│   │   ├── MidnightWallet — key gen, UTXO tracking, balancing, signing
│   │   ├── MidnightContract — circuit execution, proving, submission
│   │   └── All internals bundled (crypto, indexer, ledger, network)
│   │
│   ├── Pairing (built in Kicks)
│   │   ├── QR code generation + scanning (CameraX)
│   │   ├── Deep link handling (midnight://kicks?match=...)
│   │   └── Contract-based matchmaking (match ID = contract address)
│   │
│   └── UaaL bridge → receives/sends events to Unity
│
└── Compact contract (deployed on PREPROD)
    ├── Private state
    │   ├── p1Choices: Field[5] (all 5 directions, hidden)
    │   ├── p2Choices: Field[5] (all 5 directions, hidden)
    │   ├── p1Nonces: Field[5] (commitment randomness)
    │   ├── p2Nonces: Field[5] (commitment randomness)
    │   └── sdChoices/Nonces: Field[5] (sudden death batches)
    ├── Ledger (public)
    │   ├── matchId: Bytes
    │   ├── player1: Bytes (address)
    │   ├── player2: Bytes (address)
    │   ├── score1: Counter
    │   ├── score2: Counter
    │   ├── roundResults: Bytes[5] (GOAL/SAVE per round)
    │   ├── sdRoundResults: Bytes[5] (sudden death, partial)
    │   ├── decisiveRound: Counter (SD stops here)
    │   ├── winner: Bytes (address, zero if in progress)
    │   ├── stake: Uint
    │   └── state: enum (WAITING, COMMITTING, PROVING, REPLAY, SD, COMPLETE)
    ├── Circuits
    │   ├── createMatch(stake) → deploy, escrow stake
    │   ├── joinMatch() → escrow stake
    │   ├── commitBatch(directions[5], nonces[5]) → hash all 5
    │   ├── resolveRegulation() → compare all 5, score + results
    │   ├── commitSuddenDeath(directions[5], nonces[5]) → hash
    │   ├── resolveSuddenDeath() → round-by-round until decisive
    │   └── claimPayout() → winner withdraws pot
    └── Witnesses
        ├── generateBatchCommitment(directions[5], nonces[5]) → hash
        └── checkDecisiveRound(results[5]) → first round with winner
```

## Unity ↔ Kotlin bridge (UaaL)

Unity as a Library (UaaL) lets Unity run as an Android View inside
a native Android app. Communication:

### Kotlin → Unity
```kotlin
// Send events to Unity game objects
UnityPlayer.UnitySendMessage(
    "GameManager",        // Unity GameObject name
    "OnMatchJoined",      // Method name
    matchId,              // String payload (JSON)
)
```

### Unity → Kotlin
```csharp
// Unity C# calls Android native
AndroidJavaObject bridge = new AndroidJavaObject("com.midnight.kicks.UnityBridge");
bridge.Call("onAllChoicesSubmitted", choicesJson); // all 5 at once
```

### Event flow for a full match
```
Unity                          Kotlin                    Midnight
  │                              │                          │
  │  ── CHOICE PHASE ──          │                          │
  ├─ Player picks 5 dirs ───────►│                          │
  │  [L, C, R, L, R]            │                          │
  │                              ├─ commitBatch([5]) ──────►│
  │                              │  (builds tx, proves)     │
  │                              │◄─── tx confirmed ────────┤
  │◄── "WaitingForOpponent" ─────┤                          │
  │   (show "waiting..." UI)     │                          │
  │                              │◄─── opponent committed ──┤
  │                              │                          │
  │  ── PROVE PHASE ──           │                          │
  │◄── "MatchReady" ─────────────┤                          │
  │   (stadium intro, flyover,   ├─ resolveRegulation() ───►│
  │    crowd noise, walk-out)    │  (single ZK circuit)     │
  │   (5-10 sec feels like TV)   │◄─── 5 results ──────────┤
  │                              │                          │
  │  ── REPLAY PHASE ──          │                          │
  │◄── "PlayReplay([results])" ──┤                          │
  │   Round 1: GOAL! (1-0)      │                          │
  │   Round 2: SAVE! (1-0)      │                          │
  │   Round 3: GOAL! (2-0)      │                          │
  │   Round 4: GOAL! (2-1)      │                          │
  │   Round 5: SAVE! (3-2)      │                          │
  │   MATCH RESULT: P1 WINS     │                          │
  │                              │                          │
  │  ── SUDDEN DEATH (if tied) ──│                          │
  │   (back to choice phase)     │                          │
```

## Proof time = broadcast intro

On-device ZK proof generation takes 5-10 seconds. With batch
submission, this happens ONCE per match (not per round). The wait
is masked by a stadium flyover, crowd buildup, players walking out
of the tunnel — feels like a real TV broadcast intro.

Then the replay plays uninterrupted. Five rounds of pure football
drama with zero loading. The blockchain is invisible.

## The Midnight Android SDK

Midnight Kicks is the first consumer of the Midnight Android SDK.
The SDK is not a side artifact for the game — it's the product.
Kicks validates it, then every Midnight dApp on Android uses it.

```
midnight-sdk (published artifact)
├── Public: MidnightSdk, MidnightWallet, MidnightContract, MidnightConfig
├── Internal: compact-engine, crypto, indexer, ledger, network
├── Native: libkuira_crypto_ffi.so (arm64-v8a, x86_64)
└── Source: lives in kuira-android-wallet repo, published as AAR
```

| Component | Source module | Ships in SDK? | Open-sourced? |
|-----------|-------------|---------------|---------------|
| Circuit execution + proving | `core:compact-engine` | ✅ | SDK binary |
| Key derivation + signing | `core:crypto` | ✅ | SDK binary |
| UTXO tracking | `core:indexer` | ✅ | SDK binary |
| Tx balancing + submission | `core:ledger` | ✅ | SDK binary |
| Network config | `core:network` | ✅ | SDK binary |
| SDK facade (MidnightSdk, MidnightWallet) | New thin layer | ✅ | SDK binary |
| Connector (pairing, transports) | `core:connector` | Separate | Yes (open-source) |

The SDK AAR ships as a binary. The connector SDK ships separately
as open source so devs can see the pairing + transport patterns.

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

```
Midnight Kicks (standalone)
├── Midnight Android SDK (midnight-sdk.aar — single dependency)
│   ├── MidnightSdk.create(context) { network = PREPROD }
│   ├── MidnightWallet — key gen, UTXO tracking, balancing, signing
│   ├── MidnightContract — circuit execution, proving, submission
│   └── Everything embedded — no external wallet process
├── Pairing built in Kicks (QR + deep links)
├── Player identity = MidnightWallet keypair (Android Keystore)
└── Works completely independently — no other app needed
```

### Tier 2 — Enhanced (Kuira IS installed)

```
Midnight Kicks (Kuira-enhanced)
├── Discovers Kuira via Android intent / CredentialManager
├── Kuira provides TEE-backed keys (sigil identity)
│   ├── StrongBox / hardware-backed key storage
│   ├── Biometric gate for transaction approval
│   └── Cross-app identity — same sigil across all dApps
├── Connector delegates to Kuira's connector service
├── Match appears in "My Sigil" tab as connected app
├── Match history tied to sigil identity (portable)
└── Player gets: better security, unified identity, state browser
```

### What this means for Kicks development

Kicks ships with the lightweight wallet module for Tier 1. Tier 2
is automatic when Kuira is installed — no extra code in Kicks.
The lightweight wallet module becomes a reusable SDK artifact that
every Midnight dApp on Android can embed.

### What this means for the SDK

Today a dApp developer must either:
- Import 5 modules, wire Hilt, manage UTXO tracking manually, AND
  require Kuira or `mn serve` running as the wallet backend
- Or require Kuira installed on the device (blocks adoption)

The solution: **one SDK AAR that packages everything.**

#### Developer experience (target)

```kotlin
// build.gradle.kts — ONE dependency
implementation("midnight:sdk:1.0")
```

```kotlin
// Create SDK instance — configures network, indexer, node
val midnight = MidnightSdk.create(context) {
    network = MidnightNetwork.PREPROD
}

// Create or load wallet — keys in Android Keystore, no seed phrase
val wallet = midnight.wallet()

// Connect to a contract
val contract = midnight.contract {
    contractJs = assets.open("penalty.js")
    address = contractAddress
    witness("secret") { WitnessResult(null, wallet.secretKey) }
    coinPublicKey = wallet.coinPublicKey
}

// Execute circuit — wallet handles balancing + signing + submission
val receipt = contract.call("commitBatch", choices) { stage ->
    updateUI(stage)  // Fetching → Executing → Proving → Balancing → Submitting
}

// Read state
val state = midnight.queryState(contractAddress)
```

No Hilt. No 5 module imports. No external wallet process. No
`walletUrl`. Three objects: `MidnightSdk`, `wallet`, `contract`.
One `call()` does everything.

#### What's inside the AAR

```
midnight-sdk.aar
├── Public API (what developers see)
│   ├── MidnightSdk        — entry point, builder DSL
│   ├── MidnightWallet     — key gen + UTXO tracking + balancing + signing
│   ├── MidnightContract   — circuit execution + proving (existing)
│   ├── MidnightConfig     — network endpoints (existing)
│   ├── ContractCallStage  — progress tracking (existing)
│   └── ContractCallException — error types (existing)
│
├── Internals (hidden from developer)
│   ├── core:compact-engine — QuickJS runtime, contract execution
│   ├── core:crypto         — HDWallet, Schnorr, Rust FFI + native .so
│   ├── core:indexer        — UtxoManager, IndexerClient, SubscriptionManager
│   ├── core:ledger         — TransactionSubmitter, NodeRpcClient, ProofServerClient
│   └── core:network        — NetworkConfig, endpoint resolution
│
└── Native libraries
    ├── arm64-v8a/libkuira_crypto_ffi.so
    └── x86_64/libkuira_crypto_ffi.so
```

The 5 existing Kuira modules ARE the SDK. They just need a thin
facade (`MidnightSdk` + `MidnightWallet`) and packaging as a
single AAR. No new crypto, no new FFI, no new protocol code.

#### Tier 1 → Tier 2 upgrade path

When Kuira is installed on the device, `MidnightSdk` detects it
and transparently upgrades:

```kotlin
// Same code, no changes by the developer
val midnight = MidnightSdk.create(context) {
    network = MidnightNetwork.PREPROD
}

// Internally:
// - If Kuira NOT installed → uses embedded MidnightWallet (Tier 1)
// - If Kuira IS installed → delegates to Kuira's connector (Tier 2)
//   → TEE-backed keys, biometric gate, sigil identity, My Sigil tab
```

The developer writes Tier 1 code. Tier 2 happens automatically.

---

## SDK gap analysis

Building Midnight Kicks as an external consumer exposes exactly what's
missing for third-party developers. Every gap found here is a gap to
fix before SDK GA.

### What's ready (proven by BBoard example)

BBoard (`examples/bboard`) is an Android dApp that executes ZK circuits
and submits transactions. Two Gradle dependencies:
```kotlin
implementation(project(":core:compact-engine"))
runtimeOnly(project(":core:crypto"))  // provides native .so
```

No Hilt. No Kuira internal modules. Clean developer pattern. BUT:
**BBoard requires Kuira (or `mn serve`) running as the wallet backend.**
The `walletUrl` in `MidnightConfig` points to the connector WebSocket.
Without it, the Balancing and Submitting stages fail — the SDK needs
an external wallet to provide UTXOs, sign, and submit.

```
BBoard app                    Kuira / mn serve (REQUIRED)
    │                              │
    ├─ MidnightConfig(walletUrl) ─►│ WebSocket on :9932
    │                              │
    ├─ contract.call("post") ─────►│
    │   Fetch state (indexer) ✅    │ (direct, no wallet needed)
    │   Execute circuit ✅          │ (local QuickJS, no wallet)
    │   Prove ✅                    │ (local Rust FFI, no wallet)
    │   Balance tx ───────────────►│ wallet provides UTXOs + signs
    │   Submit tx ────────────────►│ wallet submits to node
    │                              │
```

**What BBoard proves works (SDK side):**

| Capability | Status | Pattern |
|-----------|--------|---------|
| SDK config | ✅ | `MidnightConfig.Builder(context).indexerUrl().walletUrl().networkId().build()` |
| Contract handle | ✅ | `MidnightContract.create(config) { contractJs, address, witness, ... }` |
| Circuit execution | ✅ | `contract.call("post", message) { stage -> updateUI(stage) }` |
| State reading | ✅ | `config.queryState(contractAddress)` returns JSONArray |
| Progress tracking | ✅ | `ContractCallStage` sealed class (Fetching → Executing → Proving → Balancing → Submitting) |
| Error handling | ✅ | `ContractCallException` subtypes per stage |
| Network presets | ✅ | `NetworkChoice` enum with URLs per environment |

**What BBoard does NOT prove (wallet side):**

| Capability | Status | Notes |
|-----------|--------|-------|
| Standalone operation | ❌ | Requires external wallet process for balancing + submission |
| Key management | ❌ | Hardcoded `SECRET_KEY = ByteArray(32) { (it + 1).toByte() }` |
| UTXO ownership | ❌ | Wallet provides UTXOs, not the dApp |
| Transaction signing | ❌ | Wallet signs, not the dApp |
| Contract deployment | ❌ | Must use `mn contract deploy` CLI |
| Proving key install | ❌ | Manual `adb push` from `/data/local/tmp/` |

### What's blocked (needs work in Kuira first)

| Gap | Impact | Fix needed |
|-----|--------|-----------|
| **Connector has stale Gradle deps** | `build.gradle.kts` lists 5 internal modules (crypto, indexer, ledger, network, wallet) but the source code only imports `core:network.NetworkConfig`. The handler uses interfaces/lambdas for everything else. The stale deps prevent shipping a clean AAR — remove them from Gradle and the connector is already standalone. | Remove unused deps from `core:connector/build.gradle.kts`. Verify build passes with only `core:network`. |
| **No contract deployment from SDK** | Kicks needs to deploy the penalty contract. Currently only the `mn` CLI deploys. MidnightContract assumes an existing `address`. | Add `MidnightContract.deploy(config, contractJs, constructorArgs)` to compact-engine |
| **No QR pairing in SDK** | Kicks uses QR/link to match players. QR generation/scanning is not in the connector — it only handles WebSocket/Binder/JsBridge transports. | Build QR + deep link pairing as a new lightweight module or directly in Kicks |
| **Proving keys not bundled** | Developer must manually download proving keys. BBoard example copies from `/data/local/tmp/`. Not viable for Play Store app. | Add `ProvingKeyManager.downloadFromNetwork(circuitNames)` or bundle keys in AAR |
| **SDK requires external wallet process** | `MidnightContract.call()` requires `walletUrl` — an external wallet (Kuira or `mn serve`) for balancing + signing + submission. A standalone dApp can't transact without it. This is the biggest gap. | Package a single `midnight-sdk.aar` that bundles all 5 core modules (compact-engine, crypto, indexer, ledger, network) behind a thin facade: `MidnightSdk.create()` + `MidnightWallet` + `MidnightContract`. The wallet operations run embedded — no external process. This IS the Midnight Android SDK. |

### What Kicks builds that Kuira doesn't have yet

| Feature | Kicks needs it | Kuira benefits |
|---------|---------------|----------------|
| Match pairing (QR + deep link) | Two players find each other | Becomes the connector-sdk pairing pattern |
| Batch circuit calls | 5 choices committed in one tx | Proves batch witness patterns work |
| On-chain state polling | Wait for opponent's commit | Subscription/polling pattern for SDK |
| Simple onboarding (no seed phrase) | Generate keys, start playing | Validates the "no wallet needed" UX |

---

## Implementation plan

### Phase 1: Compact contract (Week 1)

The contract is the foundation — everything else builds on it.

**Deliverable:** `penalty.compact` deployed on PREPROD, tested via
BBoard-style harness.

```
penalty.compact
├── Ledger state
│   ├── player1, player2: Bytes (addresses)
│   ├── p1Committed, p2Committed: Boolean
│   ├── score1, score2: Counter
│   ├── roundResults: Bytes[5]
│   ├── sdRoundResults: Bytes[5]
│   ├── decisiveRound: Counter
│   ├── winner: Bytes
│   ├── stake: Uint
│   └── phase: enum (WAITING, COMMITTED, RESOLVED, SD, COMPLETE)
│
├── Circuits
│   ├── constructor(stake) → deploy match, escrow
│   ├── join() → second player joins, escrow
│   ├── commitBatch(hash) → store commitment hash
│   ├── resolveRegulation(choices[5], nonces[5]) → reveal + compare
│   ├── commitSuddenDeath(hash) → SD commitment
│   ├── resolveSuddenDeath(choices[5], nonces[5]) → partial reveal
│   └── claimPayout() → winner withdraws
│
└── Witnesses
    ├── generateCommitmentHash(choices[5], nonces[5]) → hash
    └── findDecisiveRound(p1Results[5], p2Results[5]) → round index
```

**Tasks:**
1. Write penalty.compact — circuits + witnesses + ledger
2. Compile with Compact compiler → contract IIFE JS
3. Deploy to PREPROD via mn CLI
4. Test: create match → commit → resolve → verify results
5. Test: draw → sudden death → resolve

### Phase 2: Midnight Android SDK (Week 1-2, parallel with Phase 1)

Build the `MidnightSdk` facade + `MidnightWallet` class, package
all 5 existing core modules into a single `midnight-sdk.aar`.

**Deliverable:** `midnight-sdk.aar` that a standalone app imports
with one Gradle line. No external wallet process needed.

**Tasks:**
1. **MidnightSdk facade** — thin entry point that wires
   MidnightConfig, indexer, ledger, network internally. Builder DSL:
   `MidnightSdk.create(context) { network = PREPROD }`.
2. **MidnightWallet class** — key generation (Android Keystore),
   UTXO tracking (wraps UtxoManager), transaction balancing (wraps
   TransactionSubmitter), signing, submission. No seed phrase.
   Replaces the `walletUrl` dependency — all operations embedded.
3. **Wire MidnightContract.call()** — replace the WebSocket-to-wallet
   balancing/submission path with direct calls to MidnightWallet.
   The circuit execution + proving path is unchanged.
4. **Proving key auto-download** — implement
   `ProvingKeyManager.downloadFromNetwork(circuitNames)` so apps
   don't need manual `adb push`. Cache in `context.filesDir`.
5. **Bundle native .so** — pre-built `libkuira_crypto_ffi.so` for
   arm64-v8a + x86_64 in the AAR's jniLibs.
6. **Package as single AAR** — Gradle module that depends on all 5
   core modules, exports only the public API surface (MidnightSdk,
   MidnightWallet, MidnightContract, MidnightConfig, stage/error types).
7. **Test with BBoard** — migrate BBoard example to use
   `midnight-sdk.aar` instead of `core:compact-engine` + `walletUrl`.
   If BBoard works without `mn serve`, the SDK is ready.

### Phase 3: Unity game (Week 2-3)

Buy a template, strip it, wire the bridge.

**Deliverable:** Unity project that plays a 5-round penalty replay
from a JSON input, and collects 5 direction choices from the player.

**Tasks:**
1. **Buy template** — Penalty Kick Complete Game Template or
   Football Penalty Shoot Controller 3D from Asset Store
2. **Strip single-player AI** — remove keeper AI, score tracking,
   progression system. Keep: stadium, players, ball, animations,
   camera angles
3. **Build choice UI** — player picks 5 directions before the match.
   Carousel or grid: "Round 1: [L] [C] [R] → Round 2: ..."
   Confirm button when all 5 are selected.
4. **Build replay system** — `PlayReplay(string json)` receives
   5 round results, plays each sequentially:
   - Camera positions for each round
   - Ball trajectory based on shooter direction
   - Keeper dive based on keeper direction
   - GOAL or SAVE animation
   - Score overlay update
   - 2-3 second pause between rounds
5. **Build result screen** — winner celebration, final score,
   "Play Again" button
6. **Stadium intro** — 5-10 second cinematic (flyover, crowd noise)
   to mask proof latency. Triggered by `ShowIntro()`.
7. **Export as UaaL** — File → Build Settings → Android → Export
   Project. This produces the `unityLibrary` module.
8. **Test on Android** — standalone Unity app, hardcoded JSON results

### Phase 4: Android app — native layer (Week 3-4)

Compose screens + SDK wiring + UaaL integration.

**Deliverable:** Working Android app that pairs two players, submits
choices to PREPROD, and plays the match in Unity.

**Tasks:**
1. **Create repo** — `midnight-kicks/` with app module + libs/
2. **Import UaaL** — add `unityLibrary` as module, wire in
   `settings.gradle.kts`
3. **Import SDK** — `implementation(files("libs/midnight-sdk.aar"))`
   — single dependency
4. **Onboarding screen** (Compose) — generate keypair, store locally.
   No seed phrase — just biometric + auto-generated key. "Tap to
   start playing."
5. **Lobby screen** (Compose) — "Create Match" (shows QR + shareable
   link) or "Join Match" (scan QR or paste link)
6. **Match pairing** — deep link handler (`midnight://kicks?match=<addr>`),
   QR scanner (CameraX), contract join() call
7. **Choice bridge** — receive 5 directions from Unity via UaaL bridge,
   call `commitBatch()` on the Compact contract
8. **Waiting screen** (Compose) — "Waiting for opponent..." with
   state polling. When both committed, call `resolveRegulation()`.
9. **Prove + resolve** — single ZK proof, get 5 round results
10. **Launch replay** — pass results JSON to Unity via
    `UnitySendMessage("GameManager", "PlayReplay", json)`
11. **Result screen** (Compose) — final score, on-chain tx link,
    leaderboard position, "Play Again"
12. **Leaderboard screen** (Compose) — query indexer for match
    history, aggregate win/loss records

### Phase 5: Integration + polish (Week 4-5)

End-to-end testing, UX polish, performance.

**Tasks:**
1. **E2E test** — two physical devices, full flow: pair → choose →
   prove → replay → result → payout
2. **Proof latency tuning** — measure real proof time on target
   devices (Pixel 7 class). Adjust stadium intro length.
3. **Error handling** — network failures, opponent disconnect,
   proof failures, timeout handling
4. **Polish** — loading animations, haptic feedback, sound effects,
   Dusk-themed Compose screens (Void bg, Light text)
5. **Deep link testing** — `midnight://kicks?match=...` opens app
   correctly from any messaging app
6. **APK size audit** — Unity + native .so can bloat. Target < 100MB.
7. **Play Store listing** — screenshots, description, privacy policy

### Phase 6: Release (Week 5-6)

Ship before or during World Cup opening.

**Tasks:**
1. **Closed beta** — internal testing with Midnight team
2. **Open beta** — Play Store open testing track
3. **Announce** — blog post, social media, Midnight Discord
4. **Monitor** — crash reports (Firebase Crashlytics), proof success
   rates, match completion rates

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
