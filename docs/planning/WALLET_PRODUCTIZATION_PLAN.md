# Wallet Productization Plan (Phase 8B)

**Status:** Drafting — strategic decisions in progress (grill-me session)
**Last updated:** 2026-04-12
**Supersedes:** The 10-15h "Settings, app store readiness" line in `PLAN.md`

---

## Why this doc exists

Phase 8A (auth + onboarding) is done. The `PLAN.md` entry for Phase 8B
is a one-liner ("Settings, app store readiness — est 10-15h") that is
off by roughly 5-10x for what "being viable on the Play Store" actually
requires in this codebase.

This plan captures:
1. The **strategic decisions** that define the scope of 8B (what is
   this product? what network? how do we handle backup? etc.). These
   are being resolved one-by-one in a shared conversation before any
   detailed task breakdown is attempted.
2. The **concept glossary** — shared definitions of terms the decisions
   hinge on, so we don't talk past each other.
3. The **codebase reality check** — a snapshot of what is actually
   built vs. what `PLAN.md` implies is built, so we can scope honestly.
4. (Eventually) the detailed task breakdown, once the strategic
   decisions are resolved.

---

## Concept glossary

### Backup story

**What happens when the user loses their phone?**

Kuira encrypts the wallet seed with an AES key stored in the phone's
hardware security chip (Android Keystore + StrongBox/TEE). That
Keystore key **cannot leave the device** — that is the entire point
of hardware-backed storage. So if the phone is lost, stolen, factory
reset, broken, or upgraded, the seed is gone forever and the funds
are locked on-chain with no way to spend them.

The "backup story" is the answer to: **how does the user recover
their wallet on a new device?** Without one, Kuira is a wallet that
permanently deletes your funds when you drop your phone.

**Three options:**

| Option | Mechanism | UX | Effort | Notes |
|---|---|---|---|---|
| **A. Mnemonic-only** | User writes down 24 BIP-39 words on paper during onboarding | Manual, familiar from other crypto wallets (MetaMask, Lace, Phantom) | ~1-2h (just wire the display + confirm-words screen) | Users lose the paper or take insecure screenshots. Standard crypto UX — bad by modern-app standards. |
| **B. Google Block Store** | Kuira silently stores an E2E-encrypted seed blob in Google's cloud. Auto-restores on new device | User never sees it — wallet just appears on new phone | ~20-25h | Requires Google Play Services. Block Store E2E encryption is opt-in and requires device screen lock. What banking apps do. Was the original `core:backup` plan in `WALLET_AUTH_ONBOARDING_PLAN.md` §2. |
| **C. Password-derived export** | User sets a backup password; we KDF it, encrypt the seed, user saves the file (email/Drive/USB) | Manual file management + password entry | ~15h | Works without Google Play Services. Password loss = backup loss. Compatible with custodial wallets that use similar patterns. |

**Decision implication:**
- A is acceptable only if the audience is technical (developers,
  crypto-natives who already manage mnemonics) OR if funds are
  testnet only (no real value at risk).
- B is the bar for "real wallet with real value on mainnet".
- C is a middle option — more friction than B, less dependency lock-in.

---

## Codebase reality check (as of 2026-04-12)

Before any 8B planning, these are the verified facts about what's
actually built. Several of them change the scope of what's commonly
called "polish".

| Area | State | Implication for 8B |
|---|---|---|
| App icon | Default Android Studio green droid (never branded) | Needs asset design — not just a checkbox |
| R8 / ProGuard | `isMinifyEnabled = false` — release build has never been minified | **High risk:** Rust FFI symbols, QuickJS JNI, and Hilt KSP all have R8 failure modes that won't be known until we turn it on |
| Default network | `MidnightNetwork.UNDEPLOYED` (localnet) | The current APK literally cannot work on Play Store — it talks to `127.0.0.1` via `adb reverse`. Must change before any release. |
| Transaction history | Zero infrastructure (no repo method, no DB query, no screen). UTXO DB has raw data | Build from scratch if we want it, not polish an existing screen |
| QR code | No dependency, no code | Build from scratch |
| Dusk design system | 1 of 4 screens (`OnboardingScreen` only). `BalanceScreen` / `SendScreen` / `DustScreen` use raw Material 3 | "Apply Dusk consistently" is ~3 screen rewrites, not a tweak |
| Backup | `core:backup` module was scoped in `WALLET_AUTH_ONBOARDING_PLAN.md` §2 but never built. Only a docstring reference remains in `SeedVault` | See backup story options |
| Settings screen | Does not exist (no route, no VM, no file) | Build from scratch |
| Network picker UI | `NetworkSelectorBar` exists in the app shell and can switch networks (requires app restart) | Half-built — need to migrate into Settings and handle restart gracefully |
| Play Store compliance | No privacy policy, no terms, no data safety declaration | All must be authored before submission |

---

## Strategic decisions

Each decision must be resolved before the next can be answered sensibly.
Recommendations are my best guess based on the codebase state and the
stated goal ("viable on the Play Store"); they are not commitments.

### Q1 — Product identity

**What is the primary identity of this wallet when it is in the Play Store?**

Options:
- **A. Real wallet for real users.** Mainnet default, non-negotiable backup, financial-app scrutiny from Google, real support load.
- **B. Reference wallet for the Midnight ecosystem.** Preprod-first, audience is developers and Midnight-curious people, manual mnemonic backup acceptable, lower polish bar.
- **C. Demo app that accompanies the SDK.** The product is `core:compact-engine`; the wallet is a showcase. Wallet is essentially a polished BBoard example.
- **D. Dual identity.** Real wallet AND canonical SDK showcase. Hardest path — pays both audiences' costs.

**Recommendation:** B (reference wallet). Reasons:
1. Backup is a 20h build away from existing; B lets us ship without blocking on it.
2. Default network is already localnet; preprod is the smallest jump, mainnet is a full infrastructure story.
3. Audience is technical — they can handle "write down 24 words" and "this is testnet".
4. Doesn't preclude going to A later; A first is a one-way door.

**Status:** **Resolved 2026-04-12 → B.** See decisions log.

### Q2 — Target network

**What network does the Play Store build default to?**

Depends on Q1. Candidates: `PREPROD`, `PREVIEW`, `MAINNET` (if it exists), `UNDEPLOYED` (not shippable).

**Recommendation:** pending Q1.

**Status:** Blocked on Q1.

### Q3 — Backup story

**Which of the three backup options (A/B/C) does v1.0 ship with?**

**Recommendation:** pending Q1 + Q2.

**Status:** Blocked on Q1.

### Q4 — SDK release track

**Is Maven Central publishing of `core:compact-engine` in scope for 8B, or is it a separate 8C?**

The two audiences (end users vs. Kotlin/Android devs) need different
artifacts, different docs, different release cadence.

**Recommendation:** split. SDK gets its own phase.

**Status:** Open — low priority, can be resolved after the wallet track is scoped.

### Q5 — Agent Runtime (Phase 7)

**Ship wallet v1.0 without Agent Runtime and add it in v1.1, or build Agent Runtime first so v1.0 is differentiated?**

This is the "what makes Kuira different from Lace Mobile" question.

**Recommendation:** pending Q1. If B or C, defer Agent Runtime (focus on shipping). If A or D, reconsider.

**Status:** Blocked on Q1.

---

## Decisions log

Answers to the questions above, recorded as they are resolved. Each
answer should include a 1-2 sentence rationale for future archaeology.

### 2026-04-12 — Q1: Product identity = **B (Reference wallet) with a premium UX bar**

Kuira v1.0 on the Play Store is positioned as a reference implementation
for the Midnight ecosystem: technical audience, testnet-first, manual
mnemonic backup acceptable. Not a production wallet for real-value
users — that is a later-milestone rebrand, not the first release.

**Explicit refinement:** even though the scope is a reference wallet,
the UI/UX bar is intentionally high. "Looks beautiful, feels trustworthy"
is a ship requirement, not optional polish. Dusk design system applies
to every screen; dark mode works; empty and error states are designed,
not afterthoughts; app icon and splash are branded.

**Consequences that flow from this:**
- Target network defaults to a testnet (Q2).
- Backup story can be option A (manual mnemonic) for v1.0 (Q3).
- Play Store listing copy must be explicit: "testnet / reference
  implementation / not for production use with real funds".
- Agent Runtime (Phase 7) can slip to v1.1 without undermining the
  v1.0 pitch, because v1.0's pitch is "first Midnight wallet on
  Android", not "first agent-native wallet" (Q5).
- Support load is bounded — ecosystem users, not retail.

---

## Feature scope for v1.0

Full candidate feature matrix, tiered. Each row is an independent ship/cut
decision — tiers are just the recommended grouping. Estimates are rough
engineering-hours assuming a single developer familiar with the codebase.

Legend:
- **Status today** — what exists in `main` right now (verified against
  codebase, not aspirational).
- **Decision** — `ship` / `cut` / `open`. `open` means we haven't
  resolved it yet.

### Tier 1 — Must ship

Without these, the product cannot be called "a reference wallet" or
cannot be submitted to the Play Store at all.

| # | Feature | Status today | Why Tier 1 | Est. | Decision |
|---|---|---|---|---|---|
| T1-1 | Settings screen | Does not exist | Hosts every other Settings-scoped feature | 4h | open |
| T1-2 | View recovery phrase | Not wired (no UI) | Deferred from 8A.9; primary backup mechanism for the mnemonic-only scheme | 4h | open |
| T1-3 | Wipe wallet | Not wired | Safety feature, also needed for re-onboarding during dev/test | 2h | open |
| T1-4 | Network picker in Settings | `NetworkSelectorBar` exists in app shell | Migrate into Settings, remove from top bar | 2h | open |
| T1-5 | Transaction history | Zero infrastructure (no repo, no query, no screen); UTXO DB has raw data | A wallet with no history is embarrassing | 16–20h | open |
| T1-6 | Receive screen with QR + copy | Zero infrastructure, no QR dependency added | Send + Receive is the wallet's whole job | 6h | open |
| T1-7 | App icon + adaptive icon + splash | Default Android Studio green droid | Play Store submission blocker + branding | 6h | open |
| T1-8 | Dusk design system on all 4 screens | 1 of 4 (`OnboardingScreen` only) | Premium UX bar from Q1 refinement | 20–24h | open |
| T1-9 | Empty / loading / error states | Inconsistent across screens | Part of premium UX bar | 8h | open |
| T1-10 | Copy pass (every user-facing string) | Dev copy (`"Failed to sync dust: ${e.message}"`) | Premium UX bar; also sets us up for localization later | 6h | open |
| T1-11 | Privacy policy + Terms (hosted URLs) | Nothing | Play Store submission blocker | 4h | open |
| T1-12 | Play Store listing + screenshots + feature graphic | Nothing | Submission blocker | 4h | open |
| T1-13 | R8 / ProGuard enablement + verification | `isMinifyEnabled = false` — release build never minified | **Unknown-risk unblock** — Rust FFI, QuickJS, Hilt KSP all have R8 failure modes | 8–24h | open |
| T1-14 | Production signing keystore + release build pipeline | Not set up | Submission blocker | 4h | open |

**Tier 1 subtotal:** ~94–134h.

### Tier 2 — Should ship (premium UX bar)

Strong argument for each; individually cuttable if budget demands.

| # | Feature | Why Tier 2 | Est. | Decision |
|---|---|---|---|---|
| T2-1 | Send confirmation screen (recipient / amount / fee before biometric) | Current "biometric IS the confirmation" is sketchy UX but functional | 6h | open |
| T2-2 | About / Help screen | Single card with version + links; content is trivial but legally useful | 2h | open |
| T2-3 | Biometric re-auth test in Settings | Helps diagnose "is my biometric broken" without a full send | 2h | open |
| T2-4 | Faucet link / deep link for testnet tokens | First-time users need NIGHT; link to preprod faucet | 2h | open |
| T2-5 | Dark mode support | Premium UX; cost depends on whether Dusk theme already has a dark variant (probably no) | 8–16h | open |
| T2-6 | Crashlytics or Sentry integration | Needed for meaningful post-release bug triage | 4h | open |
| T2-7 | Dust registration UI polish | Current flow works but feels dev-ish; premium UX bar implies rewrite | 6h | open |
| T2-8 | Transaction detail / drill-in from history | History list alone covers v1.0; drill-in is the obvious next iteration | 6h | open |

**Tier 2 subtotal:** ~36–44h.

### Tier 3 — Nice to have (likely cut from v1.0)

Individually small, but together they bloat scope without changing the
product identity.

| # | Feature | Recommended | Reason |
|---|---|---|---|
| T3-1 | Saved recipients / contact book | cut | Non-essential, new data model |
| T3-2 | Transaction memos / notes | cut | Extra metadata store + UI |
| T3-3 | Push notifications for incoming tx | cut | Requires server-side infra, FCM tokens |
| T3-4 | Tablet / foldable layouts | cut | Ship phone-only |
| T3-5 | Lock-screen widget (biometric-free address) | cut | Widget support is a chunk of work |
| T3-6 | In-app QR scanner (for pasting recipients) | cut | `paste from clipboard` works fine for v1.0 |
| T3-7 | Export transaction history as CSV | cut | Technical audience can query the indexer directly |

### Hard cuts — not in v1.0, not negotiable

| # | Feature | Why cut |
|---|---|---|
| C-1 | Multi-account / HD account switching | Product-complexity explosion; we're single-account |
| C-2 | Localization beyond English | Scope multiplier |
| C-3 | Fiat currency conversion / price ticker | Testnet has no fiat price |
| C-4 | dApp browser UI polish | Connector code exists but productionizing its UI is a separate phase |
| C-5 | Agent Runtime (Phase 7) | Separate phase; defer to v1.1 per Q1 |
| C-6 | Cloud backup (Block Store / option B) | That is the B → A transition, a separate phase |
| C-7 | Hardware wallet integration (Ledger, Trezor) | Not relevant at reference tier |
| C-8 | In-app swap / DeFi / lending | Not a wallet feature at this stage |
| C-9 | NFC tap-to-pay | Not relevant at this tier |
| C-10 | Custodial backup service | Would fundamentally change liability posture |

---

## Budget and risk summary

| Aggregate | Range |
|---|---|
| Tier 1 only | ~94–134h |
| Tier 1 + half of Tier 2 | ~115–160h |
| Tier 1 + all of Tier 2 | ~130–180h |

**Original PLAN.md estimate:** 10–15h. Reality: **10× that** for Tier 1 alone.

**Biggest unknown:** T1-13 (R8). If minification breaks the Rust FFI or
QuickJS bridge in non-trivial ways, it can balloon by 20–40h of
symbol-retention-rule debugging. De-risk **early** in 8B — do not save
it for release week.

**Biggest time sink:** T1-5 (Transaction History) + T1-6 (Receive QR) +
T1-8 (Dusk everywhere) together are ~45–50h of UI work that cannot be
parallelized cleanly by one developer.

---

## Task breakdown

_Will be populated once feature decisions (above) are locked and Q2 + Q3
are resolved. The tier table above is the input; this section turns it
into ordered, dependency-aware subphase tasks._

Draft structure (subject to the feature decisions above):
- **8B.0** — De-risk early (R8 + a minified release build on device), before any UI investment
- **8B.1** — Missing features (Tier 1 items: Settings, recovery phrase, tx history, receive QR, wipe)
- **8B.2** — UX polish (Tier 1 items: Dusk everywhere, empty/error states, copy, icon, splash)
- **8B.3** — Release engineering (T1-11 through T1-14 + any Tier 2 infra)
- **8B.4** — Play Store compliance + submission
- **8B.5** — Tier 2 selections (depends on what survives the cut)

Note: the classic "backup story" subphase is intentionally absent
because Q3 will resolve to option A (manual mnemonic only) if Q1 stays
at B, which collapses backup into T1-2 (view recovery phrase) and does
not need its own subphase.

---

## References

- `docs/PLAN.md` — master plan (Phase 8B line needs updating)
- `docs/planning/WALLET_AUTH_ONBOARDING_PLAN.md` — Phase 8A spec + unbuilt `core:backup` module §2
- `guidelines/COMPOSE_GUIDELINES.md`, `SECURITY_GUIDELINES.md`, `ARCHITECTURE_GUIDELINES.md` — engineering bar
