# Midnight Kicks — Game Design Spec

**Source of truth for game logic, state machine, UI flows, and integration specs.**
Contract implementation: `examples/midnight-kicks/contract/src/penalty.compact`

---

## 1. Match state machine

```
                    ┌──────────────────────────────┐
                    │          WAITING              │
                    │  P1 created match, escrow in  │
                    │  Waiting for P2 to join       │
                    └──────────┬───────────────┬────┘
                               │               │
                          P2 joins         timeout
                               │               │
                    ┌──────────▼──────┐   ┌────▼─────┐
                    │   COMMITTING    │   │ COMPLETE  │
                    │  Both pick 5    │   │ P1 refund │
                    │  directions     │   └──────────┘
                    └──────┬─────┬────┘
                           │     │
                     both commit  timeout (one committed)
                           │     │
                    ┌──────▼──┐  ┌▼──────────┐
                    │REVEALING│  │  COMPLETE  │
                    │Both show│  │  Forfeit → │
                    │preimage │  │  committed │
                    └────┬────┘  │  player    │
                         │       └────────────┘
                    both reveal
                         │
                    ┌────▼─────────────────────┐
                    │  Resolve regulation       │
                    │  Compare 5 rounds         │
                    │  Score: e.g. 3-2          │
                    └─────┬──────────────┬──────┘
                          │              │
                     clear winner     tied
                          │              │
                   ┌──────▼──────┐  ┌───▼──────────┐
                   │  COMPLETE   │  │SD_COMMITTING  │
                   │  Payout to  │  │Both pick 5    │
                   │  winner     │  │new directions │
                   └─────────────┘  └───┬──────────┘
                                        │
                                   (same commit → reveal
                                    → resolve loop)
                                        │
                                   stop at first
                                   decisive round
                                        │
                                   ┌────▼────────┐
                                   │  COMPLETE    │
                                   │  SD winner   │
                                   │  or repeat   │
                                   └──────────────┘
```

### Phases (from contract)

| Phase | Description | Transitions to |
|-------|-------------|----------------|
| `WAITING` | P1 created match + escrowed stake. Waiting for P2. | → `COMMITTING` (P2 joins) or `COMPLETE` (timeout) |
| `COMMITTING` | Both players submit batch commitments (hash of 5 choices + nonce). | → `REVEALING` (both committed) or `COMPLETE` (timeout forfeit) |
| `REVEALING` | Both players reveal preimages. Circuit verifies against commitments. | → `COMPLETE` (clear winner) or `SD_COMMITTING` (tied) |
| `SD_COMMITTING` | Sudden death: both commit a new batch of 5. | → `SD_REVEALING` (both committed) or `COMPLETE` (timeout forfeit) |
| `SD_REVEALING` | Both reveal SD choices. Circuit resolves round-by-round, stops at decisive. | → `COMPLETE` (winner found) or `SD_COMMITTING` (still tied) |
| `COMPLETE` | Match over. Winner claims payout, or draw refund. | Terminal |

### Timeout rules

- Each phase has a `deadline` (unix seconds). Default: 5 minutes.
- If deadline passes with only one player having acted → that player can call `claimTimeout()` to win by forfeit.
- WAITING phase: P1 can cancel and reclaim stake if P2 never joins.

---

## 2. Batch commit-reveal protocol

### Direction encoding

| Value | Direction |
|-------|-----------|
| 0 | LEFT |
| 1 | CENTER |
| 2 | RIGHT |

Any value > 2 is rejected by `validDirection()` check in circuit.

### Commitment

```
commitment = persistentCommit(BatchPreimage { c0, c1, c2, c3, c4 }, nonce)
```

- `BatchPreimage`: struct of 5 `Uint<8>` direction choices
- `nonce`: 32-byte random (prevents rainbow table attacks on 3^5 = 243 possibilities)
- `persistentCommit`: Midnight's native Pedersen commitment (binding + hiding)

### Reveal

Player reveals all 5 choices + nonce. Circuit recomputes commitment and verifies it matches the stored hash. If mismatch → transaction fails (cheating detected).

### Scoring (regulation)

Each round: if shooter direction ≠ keeper direction → GOAL, else → SAVE.
Roles alternate: odd rounds P1 shoots, even rounds P2 shoots.

```
Round 1: P1 shoots, P2 keeps → compare p1c0 vs p2c0
Round 2: P2 shoots, P1 keeps → compare p2c1 vs p1c1
Round 3: P1 shoots, P2 keeps → compare p1c2 vs p2c2
Round 4: P2 shoots, P1 keeps → compare p2c3 vs p1c3
Round 5: P1 shoots, P2 keeps → compare p1c4 vs p2c4
```

If `shooter_dir != keeper_dir` → scorer gets +1.

### Scoring (sudden death)

Same batch format, same commit-reveal. But resolution stops at the first decisive round:

```
for each round i (0..4):
  P1 shoots: p1_scores = (p1c[i] != p2c[i])
  P2 shoots: p2_scores = (p2c[i] != p1c[i])
  if only one scored → that player wins (decisive)
  if both scored or both missed → continue
```

**ZK property:** rounds after the decisive one are never disclosed. The circuit only reveals results up to and including the decisive round.

If all 5 SD rounds are non-decisive → back to `SD_COMMITTING` for another batch.

---

## 3. Contract circuits

| Circuit | When | Inputs (public) | Witnesses (private) | Effects |
|---------|------|-----------------|--------------------|---------| 
| `constructor` | Deploy | — | — | Set phase=WAITING, P1=caller, deadline=now+1hr |
| `joinMatch` | P2 joins | — | secretKey | Set P2, escrow stake, phase→COMMITTING, deadline=now+5min |
| `commitBatch` | Each player | — | 5 choices + nonce | Store commitment hash, set committed flag |
| `revealBatch` | Each player | — | 5 choices + nonce | Verify vs commitment, store revealed choices |
| `resolveRegulation` | After both reveal | — | — | Score 5 rounds, set winner or phase→SD_COMMITTING |
| `sdCommitBatch` | SD commit | — | 5 choices + nonce | Same as commitBatch for SD |
| `sdRevealAndResolve` | SD reveal | — | 5 choices + nonce | Verify, score round-by-round, stop at decisive |
| `claimTimeout` | Deadline passed | — | — | Forfeit opponent, payout to caller |
| `claimPayout` | COMPLETE phase | — | secretKey | Transfer stake to winner |

---

## 4. Screen flow (IA)

```
[Splash] → [Home]
              │
              ├─ Create Match → [Waiting for Opponent] → [Choice Phase]
              │                   (shows QR + share link)
              │
              ├─ Join Match → [Choice Phase]
              │   (scan QR or deep link)
              │
              └─ Leaderboard → [Rankings]

[Choice Phase]
  Pick 5 directions (L/C/R per round)
  "Lock in" → commit tx → [Waiting for Opponent]

[Waiting for Opponent]
  Opponent committed? → [Stadium Intro]
  Timeout? → [Forfeit Win]

[Stadium Intro]
  Flyover + crowd buildup (masks proof + reveal tx time)
  Prove + reveal in background
  Both revealed? → [Match Replay]

[Match Replay]
  Unity cinematic: 5 rounds played sequentially
  Each round: ball flight → GOAL! or SAVE!
  Running score overlay
  After round 5:
    Clear winner → [Result Screen]
    Tied → [Sudden Death Choice Phase]

[Sudden Death Choice Phase]
  Same as Choice Phase but labeled "SUDDEN DEATH"
  After reveal → replay only decisive rounds

[Result Screen]
  Winner announcement + final score
  "Claim winnings" → payout tx
  "Play again" → back to Home

[Forfeit Win]
  "Opponent didn't respond"
  "Claim winnings" → payout tx
```

### State polling

While waiting for opponent actions, the Kotlin layer polls the contract state via the indexer subscription. When the opponent's committed/revealed flags change → transition to next screen.

---

## 5. Unity ↔ Kotlin bridge

UaaL renders Unity full-screen as an Android Activity. Communication via `UnitySendMessage` (Kotlin → Unity) and `AndroidJavaObject` callbacks (Unity → Kotlin).

### Kotlin → Unity messages

```json
// Start choice phase
{ "type": "choicePhase", "round": "regulation", "playerRole": "shooter" }

// Start replay with results
{
  "type": "replay",
  "rounds": [
    { "round": 1, "shooter": "P1", "shootDir": 0, "keepDir": 2, "result": "goal" },
    { "round": 2, "shooter": "P2", "shootDir": 1, "keepDir": 1, "result": "save" },
    { "round": 3, "shooter": "P1", "shootDir": 2, "keepDir": 0, "result": "goal" },
    { "round": 4, "shooter": "P2", "shootDir": 0, "keepDir": 2, "result": "goal" },
    { "round": 5, "shooter": "P1", "shootDir": 1, "keepDir": 0, "result": "goal" }
  ],
  "finalScore": { "p1": 3, "p2": 2 },
  "winner": "P1"
}

// Sudden death replay (only decisive rounds shown)
{
  "type": "suddenDeathReplay",
  "rounds": [
    { "round": 1, "shooter": "P1", "shootDir": 2, "keepDir": 2, "result": "save" },
    { "round": 1, "shooter": "P2", "shootDir": 0, "keepDir": 0, "result": "save" },
    { "round": 2, "shooter": "P1", "shootDir": 1, "keepDir": 0, "result": "goal" },
    { "round": 2, "shooter": "P2", "shootDir": 2, "keepDir": 2, "result": "save" }
  ],
  "winner": "P1"
}
```

### Unity → Kotlin messages

```json
// Player made choices
{
  "type": "choicesLocked",
  "choices": [0, 1, 2, 0, 1]
}

// Replay finished
{ "type": "replayComplete" }
```

---

## 6. Edge cases

| Scenario | Handling |
|----------|----------|
| Both players pick identical directions all 5 rounds | Score 0-0 → sudden death |
| Both miss all 5 in sudden death | Non-decisive → another SD batch |
| Player commits then closes app | Timeout → opponent claims forfeit |
| Both players timeout (neither commits) | Both can reclaim their stake |
| Player tries to reveal wrong preimage | Circuit rejects (commitment mismatch) |
| Player submits invalid direction (>2) | `validDirection()` check fails in circuit |
| Network disconnect during replay | On-chain result unchanged, replay is cosmetic |
| Player opens app after match resolved | Sees result screen, can claim payout |
| 10+ sudden death batches (extreme tie) | Protocol supports infinite batches. Practically impossible (3^5 = 243 combinations). |
