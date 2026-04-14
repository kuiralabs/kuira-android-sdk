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

**Status:** **Resolved 2026-04-12 → Preprod default, dev-gated picker, both proof-server modes, mainnet code-gated.** See decisions log.

### Q3 — Backup story

**Which of the three backup options (A/B/C) does v1.0 ship with?**

**Recommendation:** pending Q1 + Q2.

**Status:** **Resolved 2026-04-12 → A (manual mnemonic).** See decisions log.

### Q4 — SDK release track

**Is Maven Central publishing of `core:compact-engine` in scope for 8B, or is it a separate 8C?**

The two audiences (end users vs. Kotlin/Android devs) need different
artifacts, different docs, different release cadence.

**Recommendation:** split. SDK gets its own phase.

**Status:** **Resolved 2026-04-13 → Option C (hybrid).** Alpha parallel to 8B, full GA in 8C. See decisions log.

### Q5 — Agent Runtime (Phase 7)

**Ship wallet v1.0 without Agent Runtime and add it in v1.1, or build Agent Runtime first so v1.0 is differentiated?**

This is the "what makes Kuira different from Lace Mobile" question.

**See the [Agent Mode scenarios](#agent-mode-scenarios-q5-context) section below for concrete examples of what Phase 7 delivers.** Recommendation has been updated from the original "defer if Q1=B" after reviewing `KUIRA_VISION_V1.md` + `AGENT_STORE_VISION.md` — Phase 7 is much bigger and more strategic than the 20-30h PLAN.md line suggests.

**Recommendation:** Option C (minimal agent seed — MCP Bridge + structured output in v1.0, defer the other four pillars to v1.1).

**Status:** **Resolved 2026-04-13 → C (minimal agent seed).** See decisions log.

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

### 2026-04-12 — T1-5: Transaction history = **ship (full scope)**

Resolved every axis per recommendations + added external explorer link.

| Axis | Choice | Rationale |
|---|---|---|
| Data source | (b) Local Room-backed cache, indexer-backfilled | UTXO DB already does half of this for balance; extension is a DAO + join, not a green-field feature. Offline viewing is a premium-UX signal. |
| Transaction types shown | (a) All types with badges (unshielded, shielded, dust, contract) | Part of the "reference implementation" pitch is surfacing what Midnight actually does. |
| Detail drill-in | (b) + (c) — detail screen + external explorer link | Detail is needed; explorer link is ~0 incremental work once we have the URL pattern. |
| Lifecycle state | (c) All three: Pending, Confirmed, Failed | The data already exists in the submit flow; hiding failures breaks trust. |

**External explorer URL pattern** (confirmed by user):
- Preprod: `https://preprod.midnightexplorer.com/`
- Preview: `https://preview.midnightexplorer.com/`
- Mainnet: `https://mainnet-lite.midnightexplorer.com/` (not relevant for v1.0)

Path format to be verified during implementation (likely `/tx/{hash}`
or `/transactions/{hash}` — confirm against the live site).

**Consequence:** T2-8 (detail drill-in) is promoted into T1-5's scope
and crossed off Tier 2. Revised T1-5 budget: 22-26h (was 16-20h).

### 2026-04-12 — T1-15 (was T2-1): Send confirmation screen = **ship (Tier 1)**

Promoted from Tier 2 to Tier 1. Reclassified as a correctness feature
rather than polish: the current flow goes straight from Send button to
biometric with no review step, making amount typos unrecoverable.

Sub-decisions resolved per recommendations:

| Sub-decision | Choice | Rationale |
|---|---|---|
| Fee estimation | (ii) Static estimate | Users on testnet don't care about fee accuracy; live dry-run round-trip hurts UX more than it helps |
| Address display | (ii) Truncated with tap-to-reveal | Identicons over-engineered for v1.0; full-address is noise |
| Post-send state | (ii) Success card with tx hash + "View in history" + "Send another" | Real card, not a toast; explorer link belongs in tx detail |

**Consequence:** Tier 1 gains ~9h (net), Tier 2 loses 6h. New row T1-15.

### 2026-04-12 — Q3: Backup story = **A (manual mnemonic)**

Resolved as a downstream consequence of Q1 (reference wallet, testnet).
Since v1.0 does not hold real-value funds, the Android standard
"write down 24 words on paper" mnemonic recovery is sufficient, and
the user's loss-of-phone scenario is an inconvenience rather than a
financial liability.

**Implications for scope:**
- No new `core:backup` module for v1.0.
- T1-2 (view recovery phrase) is the sole backup UI surface.
- A proper onboarding "confirm you wrote down your phrase" step needs
  to be designed — this is the *only* moment we have to force the user
  to set up their backup, and skipping it means most users never
  record their phrase at all.
- Play Store listing copy must explicitly mention self-custody and
  mnemonic responsibility.

**Status:** Resolved 2026-04-12 → A. Block Store (option B) moves to
a hypothetical v1.1 alongside the B → A product identity transition.

### 2026-04-12 — T1-2: Recovery phrase placement = **Strategy 1 + twist**

Chosen strategy: post-creation display with "I understand" checkbox
confirmation, Settings-anytime viewing (biometric-gated), plus a
non-dismissible "back up your phrase" banner on the home screen until
the user completes the phrase view flow at least once.

Rejected alternatives:
- **Strategy 2 (forced word-confirmation)** — too much friction for a
  technical audience; the "tap word #3, #17, #22" flow feels
  patronizing to users who already understand mnemonic custody.
- **Strategy 3 (Settings-only, no onboarding display)** — loses the
  backup guarantee. Most users never navigate to Settings after
  onboarding.

Sub-decisions resolved per recommendations:

| Sub-decision | Choice | Rationale |
|---|---|---|
| Copy-to-clipboard | (iii) Allow with warning toast | Technical users have secure password managers; the warning flags risk without infantilizing |
| Screenshot prevention | (i) Set `FLAG_SECURE` on the recovery screen | ~30min to implement; prevents screen-recording leaks |
| Re-auth interval for Settings view | (i) Always biometric-prompt | Mnemonic is the crown jewel; 2s friction is worth it |

**Consequence:** T1-2 budget grew from 4h to 8-10h (added: banner
state management, `FLAG_SECURE` wiring, clipboard warning, re-view
flow from Settings).

### 2026-04-12 — Play Store compliance research added

New section "Google Play Store compliance cheat sheet" summarizes the
policy reality for this app. Key findings:

- **We are exempt** from the Cryptocurrency Exchanges and Software
  Wallets policy because we're non-custodial. Must declare correctly
  on the Financial Features Declaration form.
- **Target SDK 35+** is required. We are at 36. ✓
- **Developer account verification + Organization registration +
  D-U-N-S** likely needed for a financial app. 60-day buffer.
- **14-day / 12-tester closed beta is mandatory** for new developer
  accounts before production release. Added as T2-10 — marked **ship**
  because it's a non-negotiable scheduling gate, not a feature choice.
- **Play Integrity API** recommended for wallets. Added as T2-9;
  decision still open.
- **Copy restrictions**: must say "testnet only / not real money /
  self-custody"; cannot say "official / anonymous / investment /
  yield / earn / guaranteed"; use "privacy-preserving" not
  "anonymous".
- **Data Safety form** has nuances that are easy to get wrong — full
  cell-by-cell guidance in the cheat sheet section.

### 2026-04-12 — Q2: Target network = **Preprod default, picker dev-gated, both proof-server modes, mainnet code-gated**

Sub-decisions resolved:

| Sub-decision | Choice | Rationale |
|---|---|---|
| Default network on fresh install | **Preprod** (per recommendation) | Where Midnight's ecosystem activity is most active; matches Play Store listing copy ("testnet only"). |
| Network picker visibility | **Dev-mode-gated (option ii)** with an always-visible **environment badge in the top nav bar** | Protects non-dev users from accidentally switching to a network with no funds. Badge keeps the current network visible at all times — reinforces "testnet only" messaging Play Store requires and helps devs track which network they are on mid-session. |
| Proof server | **Both** — local proving AND a user-configurable remote proof server URL | Local is the default/fallback path (Phase 4C complete). Remote is dev-mode-gated config in Settings because "there will be a lot" of proof servers in the ecosystem and no one canonical URL yet. No default URL hardcoded in v1.0. |
| `MAINNET` enum case | **Added to enum, runtime-gated via Firebase Remote Config `mainnet_enabled` (default false)** | Ships mainnet-ready code; flipping the remote flag enables mainnet without an app update. Upgraded from the original BuildConfig recommendation on 2026-04-12 per user direction — the kill-switch / gradual-rollout / A-B capability justifies the Firebase dependency. |

**Consequences:**
- Four new Tier-1 rows created: T1-16 (environment badge), T1-17
  (developer-options toggle), T1-18 (proof server config), T1-19
  (mainnet enum + Firebase Remote Config gate). Combined ~23-25h of
  engineering (T1-19 grew from 2-3h to 10-12h when Firebase Remote
  Config was adopted).
- `NetworkConfig.proofServerUrl` hardcoding must go — becomes a
  nullable runtime value read from DataStore in T1-18.
- `SendViewModel.toggleProvingMode()` behavior changes: visible only
  in dev mode, and REMOTE mode requires a non-null custom URL.
- Play Store listing copy anchors on "Preprod testnet" in v1.0 —
  future listing updates to mention mainnet happen when we flip the
  remote config flag.

### 2026-04-12 — Firebase scope (T1-19 follow-up + T2-6 locked)

User adopted Firebase for the mainnet gate (T1-19), which changes
T2-6's calculus — incremental cost of Crashlytics on top of Remote
Config is ~1h vs ~3h for a fresh Sentry integration.

| Firebase product | v1.0 | Rationale |
|---|---|---|
| Remote Config | **ship** (T1-19) | Mainnet kill-switch / gradual rollout |
| Crashlytics | **ship** (T2-6) | ~1h incremental, needed for first-release triage |
| Analytics | **opt out** | Privacy red flag for a self-custody crypto wallet; Data Safety form stays leaner; can add in v1.1 if needed |
| Performance Monitoring | **opt out** | Not worth Data Safety complication |
| Auth / Firestore / Storage | **opt out** | Wrong tools for a non-custodial wallet |

**Data Safety form deltas:**
- "Crash logs" collected + shared with Google (Crashlytics)
- "Device identifiers" — Firebase Installation ID (both products)
- **No** user activity, **no** analytics events

### 2026-04-12 — T1-8: Dusk rollout = **Level 3 (full IA redesign), engineer-driven, light + dark, baseline a11y**

User chose Level 3 (information-architecture redesign of all existing
screens) over the recommended Level 2 (layout refresh). This is the
most ambitious option — particularly so without a designer, which is
the riskiest combination because the engineer must do design thinking
in addition to engineering.

Sub-decisions resolved:

| Sub-decision | Choice |
|---|---|
| Designer involvement | **None** (engineer-driven design + implementation) |
| Light + dark mode | **Both** (Dusk theme already supports `DarkColorScheme`/`LightColorScheme`/`isSystemInDarkTheme`; light-only would be regressive) |
| Accessibility scope | **Baseline** (content descriptions, 48dp touch targets, contrast — not full TalkBack-tested flow) |

**Risks formally acknowledged:**
- Local-optimum screens (each looks fine alone, product doesn't cohere)
- Design-thinking-in-code (2-3x slower than design-on-paper-first)
- Inconsistent component patterns across screens

**De-risking approach baked into the budget (AI-design-assisted):**
1. Up-front design work (~3-4h before any screen implementation, accelerated by AI tools — v0, Galileo, Uizard, Figma AI, or Claude/GPT iterating on Compose code):
   - AI-generated wireframes per screen, iterated until the IA looks right
   - Competitive study still useful (Rainbow, Phantom, Trust Wallet, MetaMask Mobile) — give the AI references to work from
   - Per-screen IA spec written into this plan doc
   - Token + component inventory; decide what new Dusk components are needed
2. THEN screen-by-screen Compose implementation against the spec.

**Residual risk:** AI design tools produce visually-good output but may
not respect Compose performance constraints, the existing component
system, or data-flow conventions. The engineer is responsible for
translating AI output into solid Compose, not blindly transcribing.

**Revised T1-8 budget: 32-40h** (was 38-46h before AI-design-assist
adjustment; saves ~6h on the up-front design step).

### 2026-04-12 — T1-6: Receive screen + `midnight:` URI scheme = **ship (full scope)**

User chose to formalize a `midnight:` payment URI scheme rather than
shipping address-only QRs (rejecting my conservative recommendation).
Reasoning: someone has to set the standard for Midnight wallets on
mobile, being first-mover is a feature for a reference wallet, and
**the same scheme is what SDK / dApp / faucet integrations will use
for deeplinks** — we need URI parsing on the inbound side anyway, so
emitting URIs from receive QRs is consistent.

**Existing context discovered:** Phase 5's `DeepLinkTransport` already
defines `midnight://connect?networkId=...&callback=...` action URIs —
in test code, but NOT yet registered in the production `AndroidManifest`.
T1-6 wires up the production intent-filter for the first time.

#### Midnight payment URI v1.0 spec

```
midnight:<address>[?amount=<decimal>][&label=<urlencoded>][&message=<urlencoded>]
```

| Param | Required | Notes |
|---|---|---|
| `<address>` | yes | bech32m, in path position; network implicit from prefix (`mn_addr_*` or `mn_shield-addr_*`) |
| `amount` | no | Decimal NIGHT, e.g. `5.5`. Token is implicitly NIGHT in v1.0; future: add `&token=<id>` |
| `label` | no | Human-readable label for the recipient, percent-encoded |
| `message` | no | Sender-attached message, percent-encoded |

Examples:
- `midnight:mn_addr_preprod1abc...` (address only)
- `midnight:mn_shield-addr_preprod1xyz...?amount=5.5` (with amount)
- `midnight:mn_addr_preprod1abc...?amount=5&label=Coffee%20shop` (with metadata)

Coexists with the existing action URI form `midnight://<action>?<params>`
used by Phase 5 connector. Single `<data android:scheme="midnight" />`
intent-filter on `MainActivity` covers both forms; activity routes by
URI shape (host present → action URI; host absent → payment URI).

#### Sub-decisions resolved

| Sub-decision | Choice | Notes |
|---|---|---|
| URI scheme | (ii) `midnight:` payment URI | Per user direction; sets de-facto standard, unlocks deeplinks |
| Address tabs | (iii) Tabs — Unshielded / Shielded | Privacy-first wallet must surface shielded as first-class |
| Amount field on receive | (i) No | URI scheme can carry amount, but receive UI doesn't surface a field — keeps recv UX simple |
| Share / save / actions | (iii) Tap-to-copy + share + save-as-image + full-screen QR | Conference handoff is a real use case for our audience |
| Visual design | "Receive NIGHT on [network]" header + network badge from T1-16; no identicon | Identicons don't earn their keep at this tier |

**Revised T1-6 budget: 14-16h** (was 6h before sub-decisions, then
8-10h after B/D/E, then 14-16h after URI scheme adoption — added URI
parser, intent-filter wiring, Send screen prefill integration, spec
documentation).

### 2026-04-12 — T1-13: R8 / ProGuard = **week-1 de-risk, optimize-on, shrink-on, auto-mapping-upload**

The unknown-risk item gets resolved early, before any UI investment.
If R8 is going to break (Rust FFI symbol matching, QuickJS reflection,
Compose nav, etc.), we want to know in week 1, not week 12.

Sub-decisions resolved per recommendations:

| Sub-decision | Choice | Rationale |
|---|---|---|
| Schedule | (i) Week 1 of 8B | Cost of finding it broken at the end > cost of finding it now |
| Aggressiveness | (ii) `proguard-android-optimize.txt` + R8 | Standard production-app config; balanced safety/optimization |
| Resource shrinking | (i) On (`isShrinkResources = true`) | No string-based resource lookups in current code; save the bytes |
| Crashlytics mapping upload | (i) Automated via Firebase plugin | Trivial wiring (~30min, paired with T2-6); spares us obfuscated stack traces forever |

**Operational note:** R8 can be exercised in the `debug` build variant
during de-risking — full release-build pipeline (T1-14 keystore + Play
App Signing) is not gated by R8 work. Means we can find R8 issues
without T1-14 done.

**Budget kept at 8-24h** to capture the realistic range:
- Best case: Hilt + Room + Compose handle themselves; only Rust FFI + QuickJS need standard `-keep` rules. ~8h.
- Realistic case: some symbol breakage in QuickJS or Compose nav. ~16h.
- Worst case: deep JNI / cmake symbol issues. ~24-40h.

### 2026-04-12 — Batch confirm: T1-1, T1-3, T1-11, T1-12, T1-14 = **ship**

All five obvious Tier 1 ships confirmed in a single decision. None
have meaningful scope ambiguity remaining; they're either submission
blockers or trivial features that the rest of the plan depends on.

**T1-11 sub-decisions resolved:**
- Hosting: **GitHub Pages** on the project repo. Public, easy to
  update, anchored to commits.
- Drafting: **LLM-drafted** for v1.0, using the Play Store research
  cheat sheet as input. Lawyer review explicitly NOT in v1.0 scope.
- Review process: **Multiple review rounds before submission.** This
  is a quality gate — first draft, internal review, revision,
  re-review, etc. Treat the legal text the same way we treat code
  reviews. Per-round time budget: ~1-2h per round; expect 3-4 rounds
  before submission-ready.

### 2026-04-12 — T1-7: App icon + animated splash = **ship (AI-draft → engineer-refine, animated, Dusk palette)**

Sub-decisions resolved per recommendations:

| Sub-decision | Choice | Rationale |
|---|---|---|
| Designer | (iv) AI-generated draft (Midjourney/DALL-E/Firefly) → engineer refinement in Figma/Inkscape | AI generates striking concepts; refinement makes them work at small sizes |
| Symbol vs wordmark | (i) Pure symbol on launcher icon | Wordmarks don't survive small sizes; adaptive-icon mask crops text weirdly |
| Wordmark placement | On splash + Play Store feature graphic | Wordmark belongs where size permits |
| Splash animation | (ii) Animated via Splash Screen API (API 31+) reusing existing `MaterializeEffect` | Premium UX bar implies first impression matters; static splash undersells |
| Color palette | Restricted to existing Dusk colors (`MidnightColors.kt`) | Brand cohesion; no orphan colors only used by the icon |

**Revised T1-7 budget: 8-10h** (was 6h; +2-4h for animated splash and Figma vector refinement).

### 2026-04-12 — T2-9: Play Integrity = **ship (scheduled last in 8B)**

User accepted the recommendation to include Play Integrity in v1.0
but flagged it for end-of-phase scheduling — astute call.

**Why end-of-phase, not earlier:**
1. Play Integrity fails on rooted/emulated/sideloaded devices — the
   exact config most developers iterate on. Building it early creates
   constant friction.
2. No upstream feature work depends on it; it slots in at sensitive
   operations (transaction signing) as a pre-submission gate.
3. Reviewer signal — a Play Store crypto-wallet reviewer who spots
   Play Integrity wired up reads "this team did their homework",
   which helps smooth the Financial Features Declaration manual
   review.

**Implementation hook:** the `Verdicts` returned from a standard
request are checked at the start of the send-confirmation handler
(T1-15). On failure: refuse the operation and surface a clear error
("This device cannot be verified by Play Integrity. If you are
running a rooted or emulated device, this is expected.").

**Scheduling consequence:** T2-9 lands in subphase 8B.5 (final
pre-release sprint) alongside T2-10 (closed-beta), not in early /
mid 8B work.

### 2026-04-13 — Batch confirm: T2-2, T2-3, T2-4 = **ship**

Three trivial Tier 2 Settings rows, ~2h each, no strategic decisions
inside. About screen, biometric re-auth test, preprod faucet link.
All three implicitly confirmed after two rounds of batch-ask with no
pushback; total ~6h.

### 2026-04-13 — Q4: SDK release track = **Option C (hybrid: alpha parallel to 8B, GA in 8C)**

User chose Option C, rejecting both the single-launch bundle (A) and
the full-defer (B) alternatives. **User's reasoning:** "our wallet
will not be mainnet so the risk is less" — i.e., since both the
wallet v1.0 and the SDK alpha are explicitly non-production-grade,
publishing an alpha SDK parallel to the wallet's testnet launch
matches the audience's expectations cleanly.

**What ships parallel to 8B (alpha tier):**
- `com.midnight.kuira:compact-engine:0.x.0-alphaN` published to a
  snapshot/pre-release Maven repo (likely Maven Central snapshots or
  Sonatype's OSSRH staging)
- BBoard packaged as a public sample repo that depends on the alpha
- Bare minimum documentation — README with a quick-start and a big
  "this is alpha, API will break" disclaimer
- No SemVer commitment yet (alpha tag = all bets off)

**What 8C delivers (GA tier):**
- Public API audit: `internal` vs `public` locked down
- KDoc on every public symbol with usage examples
- `@Stable` / `@Experimental` annotations
- Full Maven Central publishing (GPG, javadoc, sources jars, POM)
- SemVer commitment + `CHANGELOG.md`
- Proper quick-start page + conceptual documentation
- Apache 2.0 license headers audited on every file
- Self-containment re-verified (`core:compact-engine` has no hidden
  wallet-module dependencies)

**Scope added to 8B by this decision:** ~18-20h for alpha publishing
infrastructure (Maven snapshot setup, POM metadata, first alpha
release, BBoard public-repo extraction, CI for alpha bumps). This
slots into subphase 8B.5 or earlier — the alpha does not block
wallet submission.

**Scope deferred to 8C:** ~60-80h for GA-level polish, API audit,
and proper documentation.

### 2026-04-13 — Q5: Agent Runtime = **Option C (minimal agent seed in v1.0, full runtime + CipherDefense in v1.1)**

User chose Option C over A (ship-first) and B (differentiate-first).
Rejected A because it cedes the agent-native narrative to whoever
ships it first. Rejected B because full Phase 7 in 8B blows the
schedule and dilutes v1.0 focus. Option C delivers the strategic
win by coordinating v1.1 with a **companion game (CipherDefense)**
that ships alongside the full Agent Runtime.

**Two-launch narrative:**
1. **v1.0 wallet** — "First Midnight wallet on Android, MCP-compatible".
   Claude-queries-wallet (Scenario 1) is the public demo. Ships on
   its own timeline, proves fundamentals, earns Play Store presence.
2. **v1.1 wallet + CipherDefense game** — "First agent-native wallet
   on Android, launching alongside the first Midnight-native agent
   game". The Fog-Arena-style scenario (Scenario 2) is demonstrated
   live with an actual shipped product, not a mock-up.

**What v1.0 ships (Phase 8B scope addition, ~20-25h):**
- MCP Bridge (pillar 1 of 5): WebSocket-based MCP server exposing
  wallet methods as tools
- Structured JSON output everywhere (mirrors CLI `--json` flag)
- Pattern A pairing: LAN-only WebSocket, manual `claude_desktop_config.json`
  edit, 6-digit PIN + biometric first-pair approval
- Settings → Agent Mode (with Enable toggle, endpoint display,
  pairing PIN generation, paired-clients list, revoke)
- New `@JsonSerializable` data classes for every exposed method
- Audit log visible in-app (which agent called which method when)

**What v1.0 does NOT ship (Option B pillars, deferred to v1.1):**
- Agent Mode as a full-featured always-on background service
  (v1.0's version is just the WebSocket server; v1.1 adds richer
  discovery, foreground-service promotion, push model, etc.)
- Policy Engine (auto-approve within bounds, per-agent limits)
- Agent Registration flow with session tokens + scoped permissions
- x402 Handler for `midnight://pay` URIs
- In-app UI for per-agent policy editing + spend tracking

**Companion product: CipherDefense**
- Path: `/Users/norman/Development/android/projects/CipherDefense`
  (do not explore without explicit user direction — when Phase 7
  planning begins, read CipherDefense to understand its integration
  requirements)
- Coupled to v1.1 wallet release (cannot demonstrate auto-pay
  without the wallet's Policy Engine)
- Integrates via the Kuira SDK (Q4 Option C — hybrid alpha parallel
  to 8B is the natural integration surface)

**Scope impact on 8B:** +20-25h for the MCP Bridge pillar, bringing
8B total to ~260-290h (was ~240h).

**Scope deferred to Phase 7 / v1.1:** full four-pillar Agent Runtime
(~60-100h depending on scope of each pillar, not yet re-estimated).

**Absorbs three other rows:**
- T1-9 (empty / loading / error states) — Level 3 covers by necessity
- T1-10 (copy pass) — copy is part of IA / hierarchy decisions
- T2-7 (dust registration UI polish) — subsumed by Dust screen redesign

**Revised T1-8 budget: 38-46h** (was 20-24h before promotion to Level 3
and absorption of T1-9 + T1-10 + T2-7). Net delta against the
absorbed items: T1 grows by ~14h, Tier 2 loses ~6h.

### 2026-04-14 — T1-21: Incremental UTXO sync = **ship (Tier 1, slotted first in 8B.3)**

Surfaced during 8B.3 (post-network-switch device testing): the user
observed the balance screen flickering through zero on every app
launch. Root cause:
`SubscriptionManager.checkAndHandleResyncNeeded` unconditionally
wipes the UTXO DB and replays the subscription from
`transactionId = null` at every subscription start. The behavior is
intentional per an earlier design (code comment at
`SubscriptionManager.kt:85-93` — "guarantees we NEVER show
incorrect balances, trade-off: re-sync on every app start"), but it
compounds badly with the premium-UX bar from Q1 and with any
real-world indexer where replay-from-genesis is non-trivial.

**Why now (Tier 1, not Tier 2):**
- Incompatible with "premium UX" (Q1) — zero-balance flicker on
  every launch is an immediate tell that the app is rough
- Every downstream UI screen reads balance, so the L3 redesign
  (T1-8) will look broken on real indexers until this is fixed
- Production wallets (MetaMask, Phantom, Rainbow, Trust) all do
  incremental sync with reorg detection — this is table stakes,
  not an optimization
- Dust cache already works correctly across launches; aligning
  UTXO sync with the same pattern is a small, contained refactor

**Scope:**
- Persist `lastProcessedTransactionId` in `SyncStateManager` across
  app launches (already partially there; confirm and fix any wipe
  logic that runs at launch)
- On launch: resume subscription from the cached tx ID
- On indexer signalling a reorg (tx ID not known to indexer, or
  indexer reports chain rollback): roll back UTXOs to the fork
  point and resume from there
- On "indexer completely unknown state" (e.g. dev localnet was
  wiped): fall back to full re-sync, log warning
- Add a "Force re-sync" button in the Developer-options screen
  (T1-17) for the user-escape case

**Estimate: 8-12h.** Contained to `SubscriptionManager` +
`SyncStateManager` + possibly `UtxoManager.clearUtxos()` behavior.
No UI dependencies beyond the Settings row.

**Slotted as the FIRST item in 8B.3** so all downstream screen
implementations (Settings, recovery phrase, tx history, send
confirmation, receive, L3 Dusk redesign) observe a wallet that
opens with a correct cached balance in sub-second time. Avoids
building polish on top of a known UX regression.

**8B.3 budget delta:** 104-132h → 112-144h (+8-12h). No other
subphase is affected.

---

### 2026-04-14 — Settings: proof-server + proving-mode scope = **deferred (not in 8B.3 first pass)**

Surfaced while planning the Settings screen (8B.3 T1-17). Today the
app has two implicit pieces of wallet-wide configuration that the
user can't see or change:

- **Proof-server URL** — hardcoded to `127.0.0.1:6300` for every
  network in `NetworkConfig.proofServerUrl`. No custom override.
- **Proving mode** (LOCAL on-device ZK proving vs REMOTE to proof
  server) — exists as `@Volatile` on `TransactionSubmitter`, hot-
  swapped only from the Send screen's toggle. Dust registration
  calls `proofServerClient.proveTransaction()` directly and cannot
  prove locally at all.

Surfacing both in global Settings is the right end-state, but the
refactor is larger than it looks: to make proving-mode truly app-
wide we'd extract a `TransactionProver` service that routes
prove(unprovenHex) by mode, then migrate DustViewModel off the raw
ProofServerClient so the global toggle takes effect everywhere.

**Deferred from 8B.3.** Current behavior works (dust registration
and send both succeed against local proof server for the default
network), so this is polish, not a blocker. Promoting to Tier 2
with explicit re-entry in 8B.4 or later.

**Open design questions to answer before implementation:**

- Q-PS1: Does the user-settable custom proof-server URL ship to
  end users (needs URL validation + "data exposure" warning), or
  is it dev-only (hidden behind Developer options / long-press)?
- Q-PS2: When proving-mode = LOCAL but on-device keys aren't
  downloaded, does the app fall back to REMOTE silently
  (TransactionSubmitter's current rule at line 72) or hard-fail
  with a "download keys first" prompt? Needs consistent policy
  for both Send and Dust paths.

**Shape of the eventual work (for reference, not committed scope):**

| # | Change | Restart? |
|---|---|---|
| 1 | `ProofServerSelection` (sealed: LocalDefault / Custom) + `ProofServerRepository` + `NetworkConfig` derives URL | yes |
| 2 | Settings UI — proof-server section (pending Q-PS1) | yes (reuses `restartApp`) |
| 3 | Extract `TransactionProver` service; route `DustViewModel` through it | no |
| 4 | `ProvingModeRepository` (DataStore-backed) + migrate `SendViewModel.toggleProvingMode` to write to it | no |
| 5 | Settings UI — proving-mode section (global toggle) | no |

Deliberately ordered so proof-server-URL (restart-required,
symmetrical with network switch) lands before proving-mode
(hot-swap, touches more ViewModels).

**Estimate (when it lands): 10-14h.** Most of the cost is step 3
(TransactionProver extraction) and the DustViewModel migration,
not the Settings UI.

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
| T1-1 | Settings screen | Does not exist | Hosts every other Settings-scoped feature | 4h | **ship** |
| T1-2 | View recovery phrase (see 2026-04-12 resolution below) | Not wired (no UI) | Deferred from 8A.9; primary backup mechanism for the mnemonic-only scheme | 8–10h | **ship** |
| T1-3 | Wipe wallet | Not wired | Safety feature, also needed for re-onboarding during dev/test | 2h | **ship** |
| T1-4 | Network picker (in Settings, dev-mode-gated) | `NetworkSelectorBar` exists in app shell | Migrate into Settings; hide behind developer-mode toggle (see T1-17); replace top-bar picker with environment badge (T1-16) | 2h | **ship** |
| T1-5 | Transaction history (see 2026-04-12 resolution below) | Zero infrastructure (no repo, no query, no screen); UTXO DB has raw data | A wallet with no history is embarrassing | 22–26h | **ship** |
| T1-6 | Receive screen with QR + `midnight:` URI scheme + intent-filter (see 2026-04-12 resolution) | Zero infrastructure for receive UI; `midnight://` scheme designed in Phase 5 test code but NOT registered in production manifest | Send + Receive is the wallet's whole job; URI scheme also unlocks deeplinks for SDK / dApp / faucet integrations | 14–16h | **ship** |
| T1-7 | App icon + adaptive icon + animated splash (see 2026-04-12 resolution) | Default Android Studio green droid | Play Store submission blocker + branding; first-impression UX | 8–10h | **ship** |
| T1-8 | **Level 3** Dusk rollout — IA redesign of all 4 existing screens + new screens (see 2026-04-12 resolution; AI-design-assisted) | 1 of 4 (`OnboardingScreen` only) uses Dusk | Premium UX bar from Q1 refinement; user chose Level 3 (full IA redesign) over Level 2 (layout refresh); AI design assistance reduces risk vs pure engineer-driven design | 32–40h | **ship** |
| ~~T1-9~~ | ~~Empty / loading / error states~~ | **Absorbed into T1-8 on 2026-04-12.** Level 3 redesign covers these by necessity. | ~~8h~~ | absorbed |
| ~~T1-10~~ | ~~Copy pass~~ | **Absorbed into T1-8 on 2026-04-12.** Copy decisions are part of IA / hierarchy decisions. | ~~6h~~ | absorbed |
| T1-11 | Privacy policy + Terms (LLM-drafted, GitHub Pages hosted, multi-round review) | Nothing | Play Store blocker; multiple review rounds before submission | 4h drafting + iterative review | **ship** |
| T1-12 | Play Store listing + screenshots + feature graphic | Nothing | Submission blocker; screenshots come AFTER L3 UI is built (T1-8 dependency) | 4h | **ship** |
| T1-13 | R8 / ProGuard enablement + verification (week-1 de-risk; see 2026-04-12 resolution) | `isMinifyEnabled = false` — release build never minified | **Unknown-risk unblock** — Rust FFI, QuickJS, Hilt KSP all have R8 failure modes | 8–24h | **ship** |
| T1-14 | Production signing keystore + release build pipeline | Not set up | Submission blocker | 4h | **ship** |
| T1-15 | Send confirmation screen (recipient / amount / fee before biometric) | Current flow goes straight from Send button → biometric with no review step | Correctness feature, not polish — prevents typo-sends; core trust signal for the premium UX bar | 8–10h | **ship** |
| T1-16 | Environment badge in top nav bar | Not present | Surfaces current network ("PREPROD", "PREVIEW", "MAINNET") persistently at the top of every screen; reinforces the Play-Store-mandated "testnet only" messaging; helps devs who switch networks remember where they are | 2–3h | **ship** |
| T1-17 | Developer-options toggle in Settings | Not present | Gates the network picker (T1-4) and proof-server URL config (T1-18); off by default; toggling on reveals power-user controls. Also the natural home for "View local proving keys cache size", "Clear cached state", etc. later | 3h | **ship** |
| T1-18 | Proof server configuration (local + remote, dev-mode-gated) | `NetworkConfig.proofServerUrl` hardcoded to `http://$host:6300`; `SendViewModel.toggleProvingMode()` toggles mode but URL is fixed | Support both local proving AND a user-configurable remote proof server URL. DataStore-persisted. Validation + "test connection" button. Falls back to local if remote fails. No default URL baked in — user or the ecosystem decides | 8h | **ship** |
| T1-19 | `MAINNET` enum case + Firebase Remote Config gate | Not in `MidnightNetwork` enum; no Firebase integration yet | Add `MAINNET` to the enum with endpoint URLs stubbed. Hide UI visibility (picker + badge) behind a Firebase Remote Config boolean `mainnet_enabled` (default `false`, refresh on app startup + once per hour). Flipping to `true` in the Firebase console enables mainnet without an app update. Requires Firebase project setup + Google Play Services dependency + Data Safety form update | 10–12h | **ship** |
| T1-20 | MCP Bridge + structured JSON output (Agent Runtime seed, Pattern A pairing) | Zero infrastructure; CLI wallet has MCP Server prior art with 24 tools | Q5 Option C — minimal agent seed. Enables "Claude Desktop queries my wallet" demo (Scenario 1). Full Agent Runtime (4 remaining pillars) deferred to Phase 7 / v1.1 alongside CipherDefense | 20–25h | **ship** |
| T1-21 | Incremental UTXO sync (persist `SyncStateManager` across launches, reorg-aware resume, fallback to full re-sync on indexer state loss) | `SubscriptionManager.checkAndHandleResyncNeeded` currently wipes the UTXO DB and replays from `transactionId = null` on every app launch — safe, but produces a zero-balance flicker + slow balance load every time the app opens. Dust cache is separate and works correctly. | High-priority optimization — incompatible with the premium-UX bar. Wallet startup should resume from last known tx ID; indexer signals reorg → invalidate back to fork point. Add a "Force re-sync" button in Settings for the user-escape case. | 8–12h | **ship** |

**Tier 1 subtotal:** ~94–134h.

### Tier 2 — Should ship (premium UX bar)

Strong argument for each; individually cuttable if budget demands.

| # | Feature | Why Tier 2 | Est. | Decision |
|---|---|---|---|---|
| ~~T2-1~~ | ~~Send confirmation screen~~ | **Promoted to Tier 1 (now T1-15) on 2026-04-12.** See decisions log. | ~~6h~~ | promoted |
| T2-2 | About / Help screen | Single card with version + build hash + license + GitHub link + support email; required for Play Store support-contact disclosure | 2h | **ship** |
| T2-3 | Biometric re-auth test in Settings | Helps diagnose "is my biometric broken" without a full send; ~30 lines of Compose | 2h | **ship** |
| T2-4 | Faucet link / deep link for preprod testnet | First-time users need test NIGHT; Settings row + onboarding hint card → opens preprod faucet URL | 2h | **ship** |
| ~~T2-5~~ | ~~Dark mode support~~ | **Absorbed into T1-8 on 2026-04-12.** Dusk theme already has `DarkColorScheme` / `LightColorScheme` / `isSystemInDarkTheme()` wired; Level 3 rollout handles dark mode as part of the color-reference sweep. | ~~8–16h~~ | absorbed |
| T2-6 | Firebase Crashlytics integration (no Analytics, no Performance) | Needed for first-release bug triage; marginal cost ~1h given Firebase is already wired for T1-19 | 1h incremental | **ship** |
| ~~T2-7~~ | ~~Dust registration UI polish~~ | **Absorbed into T1-8 on 2026-04-12.** Level 3 redesign of Dust covers this. | ~~6h~~ | absorbed |
| ~~T2-8~~ | ~~Transaction detail / drill-in from history~~ | **Promoted into T1-5 scope on 2026-04-12.** See decisions log. | ~~6h~~ | promoted |
| T2-9 | Play Integrity API integration (scheduled LAST in 8B) | Google explicitly recommends for wallets; positive reviewer signal; defends transaction signing on rooted/emulated devices. Scheduled at end of 8B because (a) trips dev/emulator/rooted devices during iteration, (b) no upstream code depends on it, (c) acts as a final pre-submission hardening gate | 6–8h | **ship (last)** |
| T2-10 | Closed-beta testing round (≥12 testers, ≥14 consecutive days) | Mandatory gate before production access for new developer accounts. Not optional. Not an engineering feature — a scheduling constraint with tooling implications (tester recruitment, internal-test track wiring) | 4h setup + 2 calendar weeks waiting | **ship** |

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

## Google Play Store compliance cheat sheet

Researched 2026-04-12. Summary of what applies to this specific app
(self-custody / non-custodial / testnet / Midnight). Canonical URLs
at the bottom; before submission have a human re-read them in a
browser, Google updates policies without changelogs.

### The one thing that saves us

**Google's Oct 29 2025 "Cryptocurrency Exchanges and Software Wallets"
policy explicitly exempts non-custodial wallets**, because "the
service provider never takes custody of the user's private keys."
This removes the per-jurisdiction financial licensing regime (FinCEN
MSB in US, MiCA CASP in EU, FCA in UK) from our submission path.

We stay exempt only if we remain non-custodial. Do not build features
that take custody of user keys in any form.

### Submission-blockers (must be right on first try)

- **Target SDK ≥ 35.** We are at 36. ✓
- **64-bit binaries** for every ABI (arm64-v8a, x86_64). ✓
- **Play App Signing** (not optional for new apps). Not yet set up.
- **Developer identity verification.** Personal account: legal name,
  address, gov ID, phone. Financial apps are pushed toward
  **Organization** registration, which additionally requires a
  **D-U-N-S number** (free but takes days). Budget 60 days and allow
  an extension.
- **Financial Features Declaration.** Play Console → App Content →
  Financial Features. Check **"Cryptocurrency exchanges and software
  wallets"** → **"Software wallet (non-custodial)"**. This is what
  invokes the exemption. Filing it wrong or skipping it is a
  guaranteed rejection.
- **Data Safety form.** Detailed guidance below.
- **14-day, 12-tester closed testing** is required for new developer
  accounts *before* the app can be promoted to production. Counts
  consecutive days with ≥12 opted-in testers. Plan **2+ weeks between
  account ready and production-live.**

### Data Safety form — how to answer for this app

| Field | Correct answer | Why |
|---|---|---|
| Seed stored encrypted locally (Keystore) | **Not "data collection"** — declare under *Security practices → Encrypted at rest* | Collection = data leaves device. Keystore never does. |
| Wallet addresses sent to indexer | **Collected** — *Financial info → Other financial info*. Purpose: *App functionality*. Shared: **Yes** (the indexer operator is a third party) | Addresses are public but they leave the device |
| End-to-end encryption claim | **Do NOT claim E2EE** | Google defines E2EE narrowly (sender↔recipient only). TLS ≠ E2EE in this form. |
| User can delete data | **Yes** (uninstall wipes Keystore) | Truthful |
| Biometric data | **No** (we use BiometricPrompt but the biometric never leaves the OS, we only receive the auth result) | Standard treatment per Google docs |

### Content rating (IARC)

Target rating: **Everyone / PEGI 3 / ESRB E**. Answer "No" to all
gambling / simulated-gambling questions — sending tokens is not
gambling per IARC guidance. Over-declaring drives up the rating for
no reason.

### Play Store listing — copy rules

**Must say prominently (defuses "deceptive financial product"
rejection):**
- "Testnet only — tokens have no monetary value"
- "You are solely responsible for your recovery phrase"
- "Non-custodial / self-custody"

**OK to say:**
- "Self-custody wallet for the Midnight blockchain"
- "Reference implementation"
- "Zero-knowledge privacy via Midnight's shielded protocol"
- "Open source" (if true)
- "Privacy-preserving"

**Do not say (rejection triggers):**
- "Official Midnight wallet" (unless written authorization from
  Midnight/IOG; Impersonation policy)
- "Investment," "returns," "earn," "yield," "guaranteed"
- "Anonymous" (use "privacy-preserving" — "anonymous" invites
  AML-posture review)
- Mining-related language for ZK proof generation (which is NOT
  mining, but Google's scanners aren't subtle)

### Permissions — low vs high scrutiny

**Low scrutiny (what we have):** `USE_BIOMETRIC`, `INTERNET`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`.

**High scrutiny (avoid unless absolutely required):**
`REQUEST_INSTALL_PACKAGES`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`,
any accessibility service. These trigger the Permissions Declaration
form and frequently cause rejection.

### Forbidden features (any of these = instant rejection)

- WebView + `@JavascriptInterface` loading untrusted URLs (relevant
  if we ever expose the dApp browser in v1 — don't).
- Native cryptomining on device (ZK proofs are fine, just don't call
  it mining anywhere).
- In-app token purchases or fiat on-ramp without custodial-service
  declaration (we're not doing this — keep it that way).
- Airdrops / giveaways offered in-app.
- Misleading NFT or gambling mechanics.

### What's NOT forbidden (contrary to folk wisdom)

- Privacy coins. Monero wallets are live on Play Store.
- Zero-knowledge features. No policy against them.
- Shielded / private transactions. No policy against them.

### Review timeline

- First submission on a new developer account with financial
  features: typically 3–7 days; can escalate to 2–3 weeks if the
  Financial Features Declaration triggers manual review.
- After initial approval: subsequent updates usually reviewed in
  1–3 days.
- Rejection appeal: Play Console → Policy status → "Contact our
  policy support team." Include the non-custodial exemption language
  verbatim from `answer/16329703`.

### Canonical URLs (re-read in a browser before submission)

- [Crypto Exchanges & Software Wallets policy](https://support.google.com/googleplay/android-developer/answer/16329703)
- [Financial Features declaration](https://support.google.com/googleplay/android-developer/answer/13849271)
- [Financial Services policy](https://support.google.com/googleplay/android-developer/answer/9876821)
- [Data Safety form](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Target API level](https://developer.android.com/google/play/requirements/target-sdk)
- [Developer verification](https://support.google.com/googleplay/android-developer/answer/10841920)
- [Play Integrity overview](https://developer.android.com/google/play/integrity/overview)
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Device & Network Abuse policy](https://support.google.com/googleplay/android-developer/answer/16559646)
- [Content ratings](https://support.google.com/googleplay/android-developer/answer/9898843)

---

## Agent Mode scenarios (Q5 context)

Two concrete end-to-end scenarios illustrating what Phase 7 / Agent
Runtime actually delivers. The first uses only the **MCP Bridge**
pillar — what Q5 Option C (my recommendation) would ship in v1.0.
The second uses **all five pillars** — what Q5 Option B would ship.
Both are summarized from `KUIRA_VISION_V1.md` §3 + CLI wallet prior
art (MCP server with 24 tools).

### Scenario 1 — Claude Desktop queries Alice's testnet wallet

**Ships in: Option B or C.** This is the read-only MCP bridge demo.

Setup: Kuira on phone (Preprod testnet), Claude Desktop on MacBook,
both on the same Wi-Fi.

**Pairing (Pattern A — LAN WebSocket + manual config):**
1. Alice opens Kuira → Settings → Agent Mode → Enable. Phone starts
   a background WebSocket server on `ws://<phone-ip>:9933` and shows:
   ```
   Endpoint: ws://192.168.1.47:9933
   Pairing PIN: 412-859
   ```
2. Alice edits `claude_desktop_config.json` on her Mac:
   ```json
   {
     "mcpServers": {
       "kuira": {
         "url": "ws://192.168.1.47:9933",
         "pairingPin": "412859"
       }
     }
   }
   ```
3. Restarts Claude Desktop. Claude tries to connect with the PIN.
4. Kuira on the phone validates the PIN, shows a biometric approval
   prompt: "Claude Desktop wants to pair with Kuira. Allow?" Alice
   approves — PIN is consumed, a long-lived session token replaces it.

**Pairing constraint:** works only on the same Wi-Fi. Good enough for
developers / home use. Pattern B (WalletConnect-style QR + relay
server) is the consumer-friendly alternative but requires relay
infrastructure we don't have; out of scope for v1.0/v1.1.

**Day-to-day:**

> **Alice:** "What's my Midnight balance?"
>
> Claude calls MCP tool `kuira.getBalances()` → WebSocket request to
> phone → Kuira reads from Room DB → returns JSON.
>
> **Claude:** "You have 47.5 NIGHT (unshielded) and 12.3 NIGHT
> (shielded) on Preprod testnet."

No biometric fires — this is a read-only call within Claude's granted
scope. Kuira's notification bar shows a small audit entry
"Agent call: getBalances".

> **Alice:** "Show me my last 5 transactions."
>
> Claude calls `kuira.getTxHistory(limit=5)` → summary.

**Scope to ship this pillar:** ~20-25h. MCP protocol implementation on
Android + background service + pairing PIN flow. CLI has MCP Server
prior art (24 tools).

### Scenario 2 — Autonomous game agent buys loot within a spending cap

**Ships in: Option B only.** This is the full five-pillar demo.

Setup: Alice plays *Fog Arena* (Midnight-native game — the reference
game from `AGENT_STORE_VISION.md`). Consumables (health potions,
weapons) priced in NIGHT. Mid-battle her character needs healing —
without Agent Mode, pausing to approve a send would ruin combat.

**One-time policy grant (Agent Registration pillar):** First time
Alice plays, the game deep-links `midnight://register-agent` into
Kuira. Kuira opens a policy approval screen:

```
Fog Arena wants to act on your behalf

Permissions requested:
  - Send unshielded NIGHT
  - Read balance and tx history

Policy (editable):
  Max per transaction:    [ 2.0 ] NIGHT
  Max per day:            [ 20.0 ] NIGHT
  Policy expires:         [ 7 days ▼ ]
  Recipients allowed:     [ mn_addr_preprod1game... ]

  [ Approve (biometric) ]  [ Deny ]
```

Alice approves. Kuira generates a session token tied to this policy
and returns it to Fog Arena. The game stores the token.

**Mid-gameplay (Policy Engine + MCP Bridge + Agent Mode):** Alice taps
"Buy Healing Potion" (0.5 NIGHT):

1. Fog Arena calls `kuira.makeTransfer({to: "mn_addr_preprod1game...", amount: "0.5"})` with its session token, via MCP-over-WebSocket
2. Kuira's Policy Engine evaluates:
   - Amount 0.5 ≤ 2.0 per-tx ✓
   - Daily spent (3.0) + 0.5 = 3.5 ≤ 20.0 daily ✓
   - Recipient in allowlist ✓
   - Session unexpired ✓
3. All pass → Kuira **auto-approves**, no biometric. Signs with the
   stored key, submits.
4. Game gets tx hash, shows healing animation. ~2-3s latency.
5. Kuira logs the tx in history with an "agent: Fog Arena" badge.

Combat never interrupted.

**Policy exceeded (fallback to manual):** Later the game offers a
5 NIGHT loot drop. Policy Engine rejects (over 2.0 per-tx). Kuira
falls back to the normal flow — full send-confirmation screen with
biometric. Alice reviews the 5 NIGHT purchase manually. This is the
"auto-approve within bounds, biometric when exceeded" pattern from
`KUIRA_VISION_V1.md:363`.

**Observability:** Settings → Agent Mode → Fog Arena:

```
Fog Arena
  Active since: Apr 5
  Policy: 2 NIGHT/tx, 20 NIGHT/day, expires Apr 12
  Today's spend: 3.5 / 20.0 NIGHT
  Total spend: 47.2 NIGHT
  Last call: 14s ago (makeTransfer 0.5 NIGHT)

  [ Revoke session ]  [ Edit policy ]
```

Revocation is instant — next game call fails with "session revoked".

**x402 variant (not shown in this scenario):** The same Policy Engine
+ session token mechanism also handles the x402 case — when an AI
agent running elsewhere (e.g. a web browser agent) hits an
`HTTP 402 Payment Required` response from a paywalled API, Kuira can
auto-pay if the payment is within the agent's session policy. The
`midnight://pay` intent handler is how the agent hands the payment
request to Kuira.

### Pillars used by each scenario

| Pillar | Scenario 1 (MCP only) | Scenario 2 (all five) |
|---|---|---|
| Agent Mode (background service + local API) | ✓ | ✓ |
| MCP Bridge | ✓ | ✓ |
| Agent Registration (session token + scoped permissions) | Partial (simple pair) | ✓ (full policy grant) |
| Policy Engine (auto-approve within bounds) | — | ✓ |
| x402 Handler | — | (not in this scenario but same mechanism) |

### Why these scenarios matter for Q5

- **Option A** (ship-first) ships neither scenario in v1.0. Kuira is
  a wallet with Send / Receive / Balance / Dust. Competent, not
  special.
- **Option C** (my new recommendation) ships Scenario 1. "MCP-compatible
  wallet for Midnight" — demonstrable with a Claude Desktop demo.
- **Option B** (differentiate-first) ships Scenario 2. "First
  agent-native wallet" — a full live demo where a game plays itself
  without breaking immersion.

Option B is the most compelling product, Option C is the realistic
middle, Option A cedes the narrative.

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

Ordered subphases for executing 8B. Sequencing reflects real
dependencies — later subphases cannot start meaningfully until their
upstream subphase is done. Some subphases can run in parallel
(explicitly noted).

Notation:
- **Ordering** — `[n]` means subphase n comes after n-1 unless
  marked "parallel to".
- **Gates** — `gate:` means this subphase blocks later work until done.

### [0] 8B.0 — Week-1 de-risk

**Gate: unknown R8 behavior.** Must happen before any UI investment.
The worst case for R8 breakage (20-40h debugging Rust FFI / QuickJS
symbol tables) must be discovered in week 1, not week 12.

| Task | Rows | Est. | Status |
|---|---|---|---|
| Enable R8 + `proguard-android-optimize.txt` + resource shrinking in the release build; build a release APK; install on device; fix whatever breaks; write the `proguard-rules.pro` set we can trust | T1-13 | 8–24h | **✅ Done 2026-04-13** |
| Firebase project creation + Remote Config setup (just the infra, no code use yet) | T1-19 infra | 2h | ⏳ User-action pending (Firebase console) |

**Deliverable:** release APK with `isMinifyEnabled = true` installs
and runs on device; Firebase project exists and `mainnet_enabled`
boolean flag is declared (default `false`).

**Actual 8B.0 outcome (retrospective):**
- **R8 passed first try.** Speculative keep-rules derived from a
  codebase survey (67 `external fun` declarations across 11 FFI
  classes) were correct on first attempt — no iteration on the
  proguard rules required. Release APK builds in ~44s incremental.
- **Size win:** debug 79 MB → release 37 MB (54% reduction).
- **One unrelated release-build issue surfaced** (commit `ee5fef0`):
  Android's default release network security policy blocks cleartext
  HTTP. Our UNDEPLOYED network uses `http://127.0.0.1:8088`. Fixed
  by adding `app/src/main/res/xml/network_security_config.xml` that
  permits cleartext for `127.0.0.1` / `10.0.2.2` / `localhost` only
  (HTTPS required for everything else, matching production
  expectations).
- **One latent bug surfaced in runtime testing** (logged as task #86
  for 8B.3 fix): `WalletAddressCache` stores addresses with the
  network prefix embedded from onboarding-time, so switching network
  leaves the wallet using the wrong-prefix address against the new
  indexer. This belongs in T1-4 / T1-17 network picker migration.
  Workaround for ongoing testing: `adb shell pm clear com.midnight.kuira`
  and re-onboard on the target network.
- **Build artefacts:**
  - `./gradlew :app:assembleRelease` → signed release APK
  - Release signing falls back to debug keystore when
    `rootProject/release.keystore` is absent (drops in seamlessly
    when T1-14 lands)
  - Mapping file at `app/build/outputs/mapping/release/mapping.txt`
    (~58 MB) ready for Crashlytics auto-upload in 8B.2

### [1] 8B.1 — Design sprint (parallel to 8B.2)

**Gate: no Compose work starts until IA spec is approved.**
AI-assisted but engineer-driven, per T1-8 resolution.

| Task | Rows | Est. |
|---|---|---|
| Competitive study (Rainbow, Phantom, Trust Wallet, MetaMask Mobile); AI-assisted wireframes per screen (BalanceScreen, SendScreen, DustScreen, SettingsScreen, TxHistoryScreen, ReceiveScreen); per-screen IA spec written into this plan doc; token + new-component inventory | T1-8 design | 3–4h |
| App icon concept generation (AI drafts of pure-symbol marks in Dusk palette); splash animation storyboard reusing `MaterializeEffect` | T1-7 concepts | 3–4h |

**Deliverable:** every screen has an agreed wireframe in this plan
doc; icon/splash direction green-lit.

### [1] 8B.2 — Infrastructure features (parallel to 8B.1)

**Gate: downstream UI work (8B.3) cannot wire Firebase features or
test signed-release flows until this is done.**

| Task | Rows | Est. |
|---|---|---|
| Production signing keystore generation + secure storage + release `signingConfigs` in `build.gradle.kts` + Play App Signing setup | T1-14 | 4h |
| Firebase Remote Config client code + `mainnet_enabled` flag fetch/cache/apply logic + `MAINNET` enum case with stubbed URLs + BuildConfig flag as local override for tests | T1-19 code | 8–10h |
| Firebase Crashlytics integration + auto-upload mapping files for deobfuscation + test that stack traces land in Firebase console | T2-6 | 1h (incremental) |

**Deliverable:** release builds signed with production key, crashes
appear in Firebase Crashlytics with readable stack traces,
`mainnet_enabled = false` hides MAINNET from the network picker.

### [2] 8B.3 — Core UI + features

**The biggest block.** All Level 3 Compose work, new screens, and
feature wiring. Order inside the subphase: incremental sync + Settings
first (the former unblocks "wallet opens fast" UX that every screen
observes; the latter hosts other rows), then screen-by-screen.

Sequence within 8B.3:
1. **Incremental UTXO sync (T1-21)** — persist `SyncStateManager`
   across launches, reorg-aware resume, "Force re-sync" escape hatch.
   **Do this first** so every downstream screen gets the fast-open UX
   and the zero-balance flicker is gone before we invest in the L3
   visual polish. 8-12h.
2. Settings screen skeleton + navigation wiring (T1-1)
3. Developer toggle + network picker inside Settings (T1-17, T1-4)
4. Environment badge in top nav bar (T1-16)
5. Recovery phrase view + non-dismissible home banner (T1-2)
6. Wipe wallet (T1-3)
7. Proof server config UI (T1-18)
8. Send confirmation screen (T1-15)
9. Transaction history + detail drill-in + explorer link (T1-5)
10. Receive screen + QR + `midnight:` URI parser + intent-filter (T1-6)
11. Level 3 Dusk redesign applied across all existing screens (T1-8 Compose implementation)

| Rows | Combined est. |
|---|---|
| T1-1, T1-2, T1-3, T1-4, T1-5, T1-6, T1-8, T1-15, T1-16, T1-17, T1-18, T1-21 | 112–144h |

**Deliverable:** feature-complete testnet wallet with polished L3 UX
across every screen; wallet opens in under a second with cached
balance (T1-21); dark mode verified via `MaterialTheme.colorScheme`
audit; baseline a11y (content descriptions, 48dp touch targets).

### [3] 8B.4 — Agent seed + Tier 2 quick wins

**Dependency:** Settings screen (T1-1) from 8B.3 must exist as a host.

| Task | Rows | Est. |
|---|---|---|
| MCP Bridge over WebSocket + Pattern A pairing (PIN + biometric approval) + Settings → Agent Mode screen + session token management + audit log of agent calls + structured JSON output for every exposed method | T1-20 | 20–25h |
| About / Help screen (version, build hash, license, GitHub, support email) | T2-2 | 2h |
| Biometric re-auth test row in Settings | T2-3 | 2h |
| Faucet link + first-launch hint card | T2-4 | 2h |

**Deliverable:** Claude Desktop can connect over LAN and query wallet
(Scenario 1); Settings is complete.

### [4] 8B.5 — Release assets + compliance

**Dependency:** 8B.3 must be done (screenshots need polished UI).

| Task | Rows | Est. |
|---|---|---|
| Icon vector refinement (AI draft → Figma/Inkscape), adaptive icon layers, animated splash implementation using Splash Screen API + `MaterializeEffect` | T1-7 impl | 5–6h |
| Privacy policy + Terms of Use drafted; reviewed round 1 → revisions → round 2 → revisions (multi-round per T1-11 resolution); published to GitHub Pages | T1-11 | 4h drafting + ~4–8h review |
| Play Store listing copy (respecting the content restrictions from the compliance cheat sheet); screenshots of every major screen in light + dark mode; feature graphic (1024×500); Data Safety form answers per the cheat sheet; Financial Features Declaration with "Software wallet (non-custodial)" checked | T1-12 | 4h |

**Deliverable:** Play Console submission package assembled and
staged; legal text public at stable URLs.

### [5] 8B.6 — Final hardening

**Last engineering work before beta.** Scheduled here because Play
Integrity fails on rooted/emulated dev devices and would impede
iteration if added earlier.

| Task | Rows | Est. |
|---|---|---|
| Play Integrity API integration; verdict check at start of send-confirmation handler (T1-15); graceful failure message ("This device cannot be verified by Play Integrity. If you are running a rooted or emulated device, this is expected."); developer bypass via `BuildConfig.DEBUG` check | T2-9 | 6–8h |

**Deliverable:** production build enforces Play Integrity on
transaction submission.

### [6] 8B.7 — Closed beta + production

**14-day mandatory gate** for new developer accounts before Play
Store promotion to production. Plan accordingly.

| Task | Rows | Est. |
|---|---|---|
| Internal testing track setup in Play Console; closed testing track with ≥12 opted-in testers; 14 consecutive days of testing (non-negotiable Google gate); collect + triage feedback; fix critical bugs; upload incremented build | T2-10 | 4h setup + 14 calendar days |
| Production submission; monitor review queue; respond to any Google policy queries (Financial Features Declaration clarifications, Data Safety form corrections) | — | 4–8h contingent |

**Deliverable:** v1.0 live on Play Store.

### Parallel track — SDK alpha (Q4 Option C)

Runs alongside 8B, does not gate any wallet subphase.

| Task | Rows | Est. |
|---|---|---|
| Maven snapshot / Sonatype OSSRH staging setup; POM metadata for `com.midnight.kuira:compact-engine`; GPG signing for snapshots; first alpha release `0.x.0-alpha1`; public sample repo extraction for BBoard; alpha-tag disclaimer on the README | — | 18–20h |

**Deliverable:** external Kotlin/Android developers can `implementation("com.midnight.kuira:compact-engine:0.x.0-alphaN")`
from a snapshot repo. Full GA polish deferred to Phase 8C.

### Subphase dependency graph

```
        ┌──► 8B.1 (design) ──┐
8B.0 ───┤                    ├──► 8B.3 (core UI) ──► 8B.4 ──► 8B.5 ──► 8B.6 ──► 8B.7
        └──► 8B.2 (infra)   ─┘                                              (14 days)

SDK alpha track — runs parallel to everything in 8B, no gate.
```

### Calendar estimate (one engineer, full-time)

| Subphase | Eng. hours | Calendar weeks (1 dev, ~30 productive h/wk) |
|---|---|---|
| 8B.0 de-risk | 10–26h | 0.5–1 |
| 8B.1 design (parallel) | 6–8h | 0.25 |
| 8B.2 infra (parallel) | 13–15h | 0.5 |
| 8B.3 core UI | 112–144h | 4–5 |
| 8B.4 agent seed + T2 | 26–31h | 1 |
| 8B.5 release assets | 17–22h | 0.75 |
| 8B.6 Play Integrity | 6–8h | 0.25 |
| 8B.7 closed beta + submit | 8–12h + 14 calendar days | 3 (gate-bound) |
| SDK alpha (parallel) | 18–20h | — (absorbed into gaps) |
| **Total** | **~188–242h** | **~10–14 calendar weeks** |

Realistic ship date: **2.5–3.5 months** from kickoff for a single
full-time engineer, assuming no R8 worst-case and no Play Store
rejection round-trip.

---

## References

- `docs/PLAN.md` — master plan (Phase 8B line needs updating)
- `docs/planning/WALLET_AUTH_ONBOARDING_PLAN.md` — Phase 8A spec + unbuilt `core:backup` module §2
- `guidelines/COMPOSE_GUIDELINES.md`, `SECURITY_GUIDELINES.md`, `ARCHITECTURE_GUIDELINES.md` — engineering bar
