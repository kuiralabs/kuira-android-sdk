# Midnight Kicks — Penalty Shootout on Midnight

**Target:** FIFA World Cup 2026 (June 11 - July 19)
**Updated:** 2026-04-28

---

## Concept

PvP penalty shootout on Android. Two players, five rounds, real stakes (PREPROD NIGHT). Unity 3D + Midnight ZK proofs. The contract is the referee — no server, no trust.

**Why:** World Cup timing. Trojan horse for ZK (players don't know they're using zero-knowledge proofs). SDK validation before Kuira ships. Open-source connector SDK as the "Build on Midnight" story.

## Game flow

1. **CHOICE** — both players pick 5 directions simultaneously, submit ONE transaction each (batch commit)
2. **PROVE** — one ZK circuit compares all 5 rounds at once, single proof
3. **REPLAY** — Unity plays the full match cinematically (stadium intro masks proof latency)

Sudden death: batches of 5, circuit stops at decisive round. Unrevealed rounds stay private (ZK property).

**Anti-cheat:** commit-reveal. Hash of 5 choices + nonces stored as private state. ZK circuit proves revealed choices match commitments. Cannot change choices after commit.

## Architecture

- **Unity (UaaL)** — 3D stadium, ball physics, choice UI, cinematic replay. Knows nothing about blockchain.
- **Kotlin (native)** — SDK for contract interaction, pairing (QR + deep links), UaaL bridge (JSON messages).
- **Compact contract** — match lifecycle, commit-reveal, scoring, stake escrow, payouts. Each match = new contract instance.

## Identity (two-tier)

- **Tier 1 (standalone):** SDK generates keys (Android Keystore), manages UTXOs, signs/submits. No external wallet.
- **Tier 2 (Kuira enhanced):** SDK detects Kuira → delegates to TEE-backed sigil. Automatic upgrade, no code change.

Passkey (P-256) → `did:key` → secp256k1 access key → self-verifiable keyAuthorization. PRF-encrypted cloud backup (zero words). Full details in `docs/planning/IDENTITY_INVESTIGATION.md`.

---

## Progress

- [x] **Phase 1 — Compact contract**
  - [x] penalty.compact V2 (commit-reveal, batch, sudden death, timeout)
  - [x] Deploy to undeployed + 27 tests + security registry
- [x] **Phase 2 — Midnight Android SDK** (validated 2026-04-28)
  - [x] MidnightSdk facade + embedded wallet (balance + prove + submit)
  - [x] Proving key auto-download
  - [x] BBoard standalone on PREPROD (no mn serve)
  - [x] Balance progress callbacks
  - [ ] Contract deployment API (needed for Kicks — each match = new contract)
  - [ ] Passkey identity
  - [ ] PRF-encrypted cloud backup
- [ ] **Phase 3 — Unity game**
  - [ ] Asset Store template → strip AI → batch choice UI
  - [ ] Replay system (5 rounds from JSON) + stadium intro cinematic
  - [ ] Export as UaaL module
- [ ] **Phase 4 — Android app**
  - [ ] Onboarding (passkey → biometric → play)
  - [ ] Matchmaking (QR + deep link)
  - [ ] SDK wiring + UaaL bridge + state polling
  - [ ] Results + leaderboard
- [ ] **Phase 5 — Integration + polish**
  - [ ] E2E on two devices, proof latency tuning, error handling
  - [ ] APK size audit (< 100MB), Play Store listing
- [ ] **Phase 6 — Release**
  - [ ] Closed beta → open beta → announce (World Cup timing)

---

## SDK friction log

Every friction point building BBoard standalone → becomes SDK improvement.

| # | Friction | Severity | Fix |
|---|---------|----------|-----|
| 1 | Fee fallback to INITIAL_PARAMETERS → 66T specks → imbalanced tx → error 170 | Critical | Zero-fee detection. Need convergence loop for mainnet. |
| 2 | DustLocalState serialize/deserialize corrupts Merkle roots | High | In-memory only workaround. Needs SCALE codec fix. |
| 3 | Full dust sync 60s on PREPROD (253k events) | Medium | Background sync + progress bar. Optimize later. |
| 4 | `fromId: null` skips early events (indexer treats null ≠ 0) | Critical | Always pass `id: 0`. |
| 5 | Tag-prefix hex splitting corrupts events at scale | Critical | Line-per-event file format. |
| 6 | No progress during balance+submit (60s opaque) | High | BalanceProgress callbacks (6 stages). |
| 7 | FFI pointer write-back corrupts cached state | High | Don't write back post-spend state. |
| 8 | WebSocket backpressure OOM on 250k events | Medium | File streaming, Rust native memory. |
| 9 | No contract deployment API | Medium | Needed for Kicks. Not built yet. |
| 10 | Content behind system status bar | Low | WindowInsets padding. |

---

## Decision log

| Decision | Choice | Why |
|----------|--------|-----|
| Batch vs per-round | Batch (5 choices, 1 tx) | 2 txs per match vs 20. Cinematic replay. |
| Sudden death | Batches of 5, stop at decisive | Unrevealed rounds private. No infinite loops. |
| Unity vs Compose | Unity (UaaL) | 3D stadium, ball physics, cameras. |
| Standalone repo | Separate from Kuira | Tests SDK boundaries. Separate release. |
| Pairing | QR + deep links (built in Kicks) | Simpler than connector transports. |
| Key curve | secp256k1 (advocate P-256) | Midnight accepts secp256k1 today. |
| keyAuthorization | Self-verifiable (TEE signs) | No server trust needed. |
| DID | One per user from root passkey | Sigil = one identity. |
| Recovery | PRF-encrypted cloud backup | Zero words. Passkey syncs → biometric → restored. |
| Gas | PREPROD faucet (provider-pay on mainnet) | Lowest friction for testnet. |
| Proving | On-device (local) | Proves hardware capability. No server. |
