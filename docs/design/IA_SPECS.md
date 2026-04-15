# IA Specs — Kuira Wallet v1.0

**Phase:** 8B.1 (design sprint) — prerequisite for 8B.3 Compose work (T1-8 Dusk L3 redesign).

**Gate:** no Compose changes on a screen until the entry below is marked `✅ IA approved`.

---

## How to use this doc

Each screen section is a stub you fill in as design progresses. The work is **engineer-driven, AI-assisted** per the T1-8 decision in `docs/planning/WALLET_PRODUCTIZATION_PLAN.md`:

1. Generate wireframes / mocks in your AI design tool (Figma AI, Galileo, v0, etc.).
2. Paste the final wireframe image path under **Wireframe**.
3. Fill in the Dusk component inventory — existing tokens used, new components needed.
4. Tick the acceptance checklist.
5. Change the status line to `✅ IA approved`.

Commit wireframes as images under `docs/design/wireframes/<screen>.png` (or attach via PR).

---

## Design principles (locked from Q1 + T1-8 decisions)

1. **Dusk palette only.** Black and white — luminosity is the only variable. See `core/designsystem/.../MidnightColors.kt`. No color introduces meaning; contrast does.
2. **Light and dark modes both ship** — light mode is an inversion of the Dusk tokens, not a second palette.
3. **Baseline a11y** — content descriptions on every interactive element, 48dp minimum touch targets, text scale ≥ 200% without clipping.
4. **Premium-UX bar** — every screen must look intentional the first second it's rendered. No zero-balance flickers (T1-21 fixed this), no loading spinners where a skeleton works, no raw stack traces in error states.
5. **Reuse before invent.** If a new component would duplicate something in `core:designsystem`, extend the existing one instead.

**AI-assisted design discipline (T1-8 risk acknowledgment):** AI wireframes are inputs, not outputs. Every AI-generated mock gets translated into Compose by an engineer who respects the existing component system, data flow, and performance constraints. Don't blindly transcribe — if the AI mock uses a pattern we already have a Dusk component for, use ours. If the mock fights `core:designsystem` tokens, redo the mock, don't fork the tokens.

---

## Existing Dusk inventory (2026-04-14)

| Component | Source | Notes |
|---|---|---|
| `MidnightColors` | `core/designsystem/.../theme/MidnightColors.kt` | Void / Light / Star / Confirm tokens |
| `DuskButton` | `core/designsystem/.../component/DuskButton.kt` | Primary + secondary variants |
| `DuskScaffold` | `core/designsystem/.../component/DuskScaffold.kt` | Full-screen Dusk shell |
| `DuskBulletLine` | `core/designsystem/.../component/DuskBulletLine.kt` | List row primitive |
| `MaterializeEffect` | `core/designsystem/.../effect/MaterializeEffect.kt` | Star-materialize intro animation (reuse for splash) |
| `DuskEffect` | `core/designsystem/.../effect/DuskEffect.kt` | Ambient background effect |

Anything beyond this list is **new** and must be enumerated under the screen that needs it.

---

## Status legend

| Symbol | Meaning |
|---|---|
| `📝 Stub` | Structure exists, design not yet started |
| `🎨 Drafting` | AI wireframes being iterated — not yet approved |
| `✅ IA approved` | Compose work for this screen may begin |
| `🏗 Implementing` | Compose work in flight |
| `✔ Shipped` | On `main`, L3 applied |

---

# Screens

## 1. Balance — `📝 Stub`

**Primary goal:** in under a second of opening the app, the user sees their verified NIGHT + DUST + shielded balances for the current network, and can initiate the two most common actions (send, receive).

**Existing implementation:** `feature/balance/src/main/kotlin/com/midnight/kuira/feature/balance/BalanceScreen.kt` — Material3 scaffolding, not yet Dusked.

**Must-show data**
- Current network badge (top nav, `T1-16`)
- NIGHT balance (unshielded)
- DUST balance (fee token)
- Shielded balance (if decrypted; otherwise "locked — tap to unlock")
- Last sync timestamp ("Synced N seconds ago")
- Addresses toggle (unshielded / shielded, copyable)
- **Non-dismissible "back up your phrase" banner** (per T1-2) — shown
  until the user has completed the recovery-phrase view flow at least
  once. Tapping the banner opens the phrase view (biometric-gated).
  The banner is the single reason most users will record their
  mnemonic; design it to be hard to ignore without being annoying.

**Interactions**
- Pull-to-refresh → manual sync
- Tap balance card → drill into token detail (TxHistory filtered to that token)
- FAB / primary action → Send
- Secondary action → Receive

**Competitive reference**
- **Rainbow** — aggressive typographic hero, token rows with 24h delta. Good reference for scale, wrong color direction for us.
- **Phantom** — minimalist hero balance + horizontal action row. Good match for Dusk aesthetic.
- **Trust Wallet** — dense token list, heavy color. Anti-pattern for us.
- **MetaMask Mobile** — total portfolio + network picker in top bar. Useful for network-badge placement.

**Dusk components**
- Existing: `DuskScaffold`, `DuskButton`
- New (enumerate after wireframe): `BalanceHero`, `TokenCardRow`, `NetworkBadge`

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] Works in light mode + dark mode
- [ ] Loading state never shows $0 (use skeleton or cached value)
- [ ] Balance visible within 1s of cold open on warm cache
- [ ] All interactive elements ≥ 48dp and labeled for TalkBack
- [ ] Network badge matches `T1-16` design exactly

---

## 2. Send — `📝 Stub`

**Primary goal:** a user sends NIGHT or shielded NIGHT to an address with confidence they're paying the right amount to the right recipient, with a clear bailout at every step.

**Existing implementation:** `feature/send/.../SendScreen.kt` — functional, not Dusked. Proving mode toggle (LOCAL / REMOTE) already present per earlier deferred decision.

**Must-show data**
- Source (mode-driven: unshielded / shielded picker)
- Recipient address (paste or scan QR from Receive of another wallet)
- Amount + denomination
- Estimated dust fee
- Proving mode indicator
- Final "Review" step (T1-15 confirmation screen — see separate entry below)

**Interactions**
- Scan QR button → camera
- Paste button → clipboard (with validation)
- MAX button → fill amount = balance − fee
- Primary button progresses through states: Compose → Review → Signing → Submitting → Result

**Competitive reference**
- **Rainbow** — single-screen compose with inline fee estimate. Tight layout, works for a two-step model.
- **Phantom** — clear address validation, good error copy.
- **Trust Wallet** — address book / recents. Out of scope for v1.0 per plan.
- **MetaMask Mobile** — explicit "review" step. Matches T1-15.

**Dusk components**
- Existing: `DuskScaffold`, `DuskButton`
- New (enumerate after wireframe): `AddressField`, `AmountField`, `FeeEstimateStrip`, `ProvingModeBadge`

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] Light + dark parity
- [ ] Compose → Review → Signing → Submitting → Result states all distinct
- [ ] Keyboard doesn't cover the amount field on small screens
- [ ] Address paste catches the wrong-network case with a specific error ("this is a Preview address; you're on Preprod")
- [ ] Dust fee visible before "Review"

---

## 3. Send Confirmation — `📝 Stub` (T1-15, separate screen)

**Primary goal:** the user re-reads the destination + amount + fee once more and commits, OR bails out. This is the **last gate** before a signed transaction is produced.

**Locked sub-decisions (T1-15, 2026-04-12):**
- **Fee estimation:** static estimate. No live dry-run round-trip — users on testnet don't care about fee accuracy to the wei, and the extra round-trip hurts UX.
- **Address display:** truncated (6…4) with tap-to-reveal the full address. No identicons (over-engineered for v1.0).
- **Post-send state:** a proper success card with the tx hash + "View in history" + "Send another" actions. Not a toast. Explorer link lives on the tx-detail screen (reached via "View in history").

**Must-show data**
- Recipient address — truncated, tap to expand to full
- Amount + denomination (no fiat — v1.0)
- Fee (DUST) — static estimate, single line
- Total debit
- Source address (so user confirms the intended "from") — truncated
- Proving mode badge (LOCAL on-device vs REMOTE via proof server)

**Interactions**
- Back → Send compose with every field preserved
- Confirm → signing flow (biometric prompt)
- On success → success card with: tx hash (truncated, tap-to-reveal + copy), "View in history" → TxDetail, "Send another" → fresh Send compose

**Competitive reference**
- **Ledger Live** — the gold standard confirm-before-sign; reused across hardware-wallet apps.
- **Phantom** — clean "Send this amount? → Y/N" sheet.

**Dusk components**
- Existing: `DuskScaffold`, `DuskButton`
- New: `ConfirmRow` (label + truncated/tap-to-reveal value pair, copyable), `TotalStrip`, `SuccessCard`, `ProvingModeBadge` (shared with Send)

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] Address shown truncated by default; tap reveals full + copies
- [ ] Cannot confirm without biometric challenge
- [ ] "Back" preserves all Send fields
- [ ] Success card shows tx hash, "View in history", "Send another"
- [ ] Light + dark parity

---

## 4. Dust — `📝 Stub`

**Primary goal:** the user sees their current dust (fee) balance, understands dust is generated from held NIGHT over time, and can register for dust generation if they haven't yet.

**Existing implementation:** `feature/dust/.../DustScreen.kt` — registration flow works end-to-end as of tonight's dust-registration fix.

**Must-show data**
- Dust balance (current)
- Dust generation rate (per NIGHT, per time)
- Registration status (registered / not registered)
- If not registered: clear CTA + brief explainer ("Register to start generating DUST — one-time transaction, small NIGHT fee")
- If registered: next fill time / estimate to full

**Interactions**
- Primary: Register (if unregistered) — biometric prompt, proof-server flow
- View: Registration proof link (opens explorer with tx hash)

**Competitive reference**
- No direct competitor equivalent — dust is Midnight-specific. Reference shape: Solana's "rent" explainer in Phantom.

**Dusk components**
- Existing: `DuskScaffold`, `DuskButton`, `DuskBulletLine` (for the explainer)
- New: `DustGenerationChart` (optional — maybe v1.1)

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] Unregistered → registered flow is 1-tap + 1 biometric
- [ ] Registered state shows meaningful "rate" info, not just a number
- [ ] Light + dark parity

---

## 5. Settings — `📝 Stub` (T1-1 host screen)

**Primary goal:** the user configures wallet-wide preferences, reviews network + security info, and accesses dangerous actions (view recovery phrase, wipe wallet) behind appropriate gates.

**Does not exist yet** — this is a new screen to build.

**Must-show sections (from T1-1 through T1-18 rows)**
- **Network** — current selected network, picker (T1-4), environment badge copy
- **Developer options** (T1-17) — proof server config (deferred — placeholder row for now), force re-sync button, build info
- **Security** — view recovery phrase (T1-2), biometric re-auth test (T2-3), wipe wallet (T1-3, destructive, doubly confirmed)
- **About** (T2-2) — app version, build hash, commit SHA, license link, GitHub link, support contact

**Interactions**
- Destructive actions (wipe, view phrase) → biometric gate + second confirmation
- Most rows are `DuskBulletLine` navigation rows

**Competitive reference**
- **Rainbow** — settings hero is a profile icon + identity. Skip for us (no identity in v1.0).
- **Phantom** — compact section list grouped by function. Good match.
- **MetaMask Mobile** — developer options gated behind "tap version 7 times". Fun easter egg, not needed for v1.0.

**Dusk components**
- Existing: `DuskScaffold`, `DuskBulletLine`, `DuskButton`
- New: `SettingsSectionHeader`, `DangerRow` (distinct visual weight for destructive actions), `NetworkPicker` sheet

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] Destructive actions clearly visually distinct from routine rows
- [ ] Every row has a TalkBack label
- [ ] Build info section shows the exact commit SHA the user is running (debuggable)
- [ ] Environment badge is visible both in the top nav bar (T1-16) AND on this screen's Network row

---

## 6. Transaction History — `📝 Stub` (T1-5)

**Primary goal:** the user reviews recent transactions with enough information to recognize them ("I sent 10 NIGHT to Bob yesterday"), and can drill into any one for full details + explorer link.

**Does not exist yet.**

**Locked sub-decisions (T1-5, 2026-04-12):**
- **Data source:** local Room-backed cache, indexer-backfilled. Same pattern as the balance UTXO DB — extension is a DAO + join, not a green-field feature. Offline viewing is a premium-UX signal.
- **Types shown:** ALL types with badges — unshielded, shielded, dust, contract. Part of the "reference implementation" pitch is surfacing what Midnight actually does; hiding contract calls would break that.
- **Detail drill-in:** dedicated detail screen AND an external explorer link.
- **Lifecycle state:** all three — Pending, Confirmed, Failed. The data already exists in the submit flow; hiding failures breaks trust.

**Must-show data (list row)**
- Direction (in / out) — subtle icon, no color
- Type badge (Unshielded / Shielded / Dust / Contract)
- Amount + token
- Counterparty address (truncated, 6…4 chars)
- Timestamp (relative: "2h ago", "3d ago")
- Status (confirmed / pending / failed)

**Must-show data (detail)**
- Full timestamp (ISO 8601 + relative)
- Full addresses (from + to, copyable)
- Amount, fee, total debit
- Tx hash (copyable)
- Block number
- Explorer link (opens in browser)
- Status + reason if failed
- Contract-call specifics if type=Contract (contract address, method name if known)

**Interactions**
- Pull to refresh
- Tap row → detail
- Detail "Open in explorer" → `https://{preprod,preview}.midnightexplorer.com/tx/<hash>` (path format TBD against live site) — uses the current `MidnightNetwork.rustNetworkId`
- Filter by token (from Balance drill-in)

**Competitive reference**
- **Rainbow** — excellent detail screen; density balanced.
- **Phantom** — activity list grouped by day. Good model.
- **Trust Wallet** — too dense.
- **Etherscan in-app view** — what NOT to do in a wallet (too data-heavy).

**Dusk components**
- Existing: `DuskScaffold`, `DuskBulletLine`
- New: `TxRow`, `TxStatusBadge`, `TxDetailField` (copyable key-value)

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] Day grouping headers
- [ ] Empty state isn't a blank page — explainer ("no transactions yet")
- [ ] Failed txs distinguishable from successful without relying on color
- [ ] Detail "Open in explorer" URL uses the right network per `MidnightNetwork.rustNetworkId`

---

## 7. Receive — `📝 Stub` (T1-6)

**Primary goal:** the user shows a QR code of their address to a sender, or copies the address to paste. Also supports sharing / saving the QR as an image and a full-screen mode for conference handoff.

**Does not exist yet.**

**Locked sub-decisions (T1-6, 2026-04-12):**
- **URI scheme the QR encodes:** `midnight:` payment URI (not bare address). Full spec: `midnight:<address>[?amount=<decimal>][&label=<urlencoded>][&message=<urlencoded>]`. See `WALLET_PRODUCTIZATION_PLAN.md` 2026-04-12 T1-6 entry for details and examples.
- **Address surfacing:** tabs (Unshielded / Shielded) — not a toggle. Privacy-first wallet must surface shielded as first-class.
- **Amount field on receive:** **NO**. The URI spec carries amount, but the Receive UI does NOT expose an input. Keeps the receive flow to a single glance / hand-off; amount-in-URI is reserved for deeplink / dApp integration scenarios that populate it programmatically.
- **Actions:** tap-to-copy + native share sheet + save-as-image + full-screen QR mode. Conference hand-off (your phone across a table, their camera) is a real use case for the audience.
- **Visual:** "Receive NIGHT on [network]" header with the `T1-16` network badge. No identicon.

**Must-show data**
- Header: "Receive NIGHT on Preprod" (or current network) + network badge
- Tabs: Unshielded / Shielded (tab selection persists per user)
- QR code of the `midnight:` URI for the selected address
- The address as text, copyable
- Actions: Copy / Share / Save / Full-screen

**Interactions**
- Copy address (haptic feedback + visible "Copied" pill on success)
- Share → native Android share sheet (text + QR image)
- Save → writes PNG to Photos
- Full-screen → QR fills the display at maximum contrast for across-the-table scanning
- Swap tab → QR re-renders against the new address

**Competitive reference**
- **Rainbow** — just-the-QR-and-address, extremely minimal. Good fit.
- **Phantom** — similar.
- **Trust Wallet** — too many fields. We're deliberately simpler.

**Dusk components**
- Existing: `DuskScaffold`, `DuskButton`
- New: `QRCodeCanvas` (pick encoder lib — `zxing-android-embedded` or similar), `AddressChip` (copyable, short/long toggle), `FullScreenQrSheet`, reuse `NetworkBadge`

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] QR renders in light + dark (dark: white modules on Void; light: Void modules on Light)
- [ ] Address text is large enough to read over a sender's shoulder
- [ ] Copy action: haptic + visible "Copied" pill
- [ ] Share sheet sends both the URI text AND the QR image
- [ ] Save writes a sensibly-named PNG (`kuira-receive-<short-addr>.png`)
- [ ] Full-screen mode: no status bar, max contrast, tap-anywhere to exit
- [ ] `midnight:` URI encoded in the QR matches the v1.0 spec in `WALLET_PRODUCTIZATION_PLAN.md`

---

## 7a. Recovery phrase view — `📝 Stub` (T1-2)

**Primary goal:** the user sees their 24-word mnemonic, understands its gravity, and is warned if they try to copy it. Reached from (a) onboarding immediately after wallet creation, and (b) the non-dismissible Balance banner, and (c) Settings → Security → View recovery phrase.

**Does not exist yet** — onboarding currently creates the seed without forcing the user to view it.

**Locked sub-decisions (T1-2, 2026-04-12):**
- **Placement strategy:** Strategy 1 + twist — post-creation display with "I understand" checkbox confirmation, Settings-anytime viewing, plus a non-dismissible home banner until the user completes the phrase view flow at least once.
- **Copy-to-clipboard:** allowed, with a warning toast ("This phrase is the only way to recover your wallet. Clipboard contents can be read by other apps. Only paste into a trusted password manager."). Rejected option: block copy entirely — technical users have secure password managers; the warning flags risk without infantilizing.
- **Screenshot prevention:** `FLAG_SECURE` on the recovery screen. ~30 min to implement, prevents screen-recording leaks.
- **Re-auth interval:** always biometric-prompt when accessed from Settings. The mnemonic is the crown jewel; 2 s of friction is worth it.

**Must-show data**
- The 24 words in a 6×4 grid, numbered 1-24
- A prominent warning block above the words ("Anyone with these 24 words has full control of this wallet. Never share them. Never type them into a website. Never give them to 'support'.")
- An "I've safely recorded my phrase" checkbox that enables the confirm action
- Primary: Confirm → marks the backup as completed, dismisses the Balance banner, and returns to the previous destination (onboarding → home, or Settings → Settings)
- Secondary: Copy (with warning toast), Back

**Interactions**
- `FLAG_SECURE` set for the whole screen lifecycle
- Biometric prompt required on every entry from Settings (per decision)
- After confirmation, a flag is stored in DataStore (`recovery_phrase_viewed = true`) that dismisses the Balance banner permanently

**Dusk components**
- Existing: `DuskScaffold`, `DuskButton`
- New: `MnemonicGrid` (6×4 numbered cells, selectable typography), `WarningBlock` (high-contrast attention primitive)

**Wireframe:** _(paste path after AI drafting)_

**Acceptance checklist**
- [ ] `FLAG_SECURE` verified (screen-record produces black frames)
- [ ] Copy shows the warning toast
- [ ] Confirm checkbox gates the primary button
- [ ] Entry from Settings always triggers biometric
- [ ] On confirm, Balance banner disappears and doesn't come back
- [ ] 24 words visible without scroll on a typical 6" screen (textScale 100%)

---

## 8. Onboarding (visual consistency pass) — `📝 Stub`

**Primary goal:** no IA redesign; apply Dusk tokens consistently and confirm the onboarding → home transition is animated.

**Existing implementation:** `feature/onboarding/.../OnboardingScreen.kt` — three steps (Welcome / Create / Restore), functional with biometric + SeedVault integration.

**What this pass does**
- Audit every Composable for hardcoded Material3 colors; replace with `MidnightColors` tokens via the theme.
- Verify the recovery-phrase step uses a high-contrast treatment appropriate to the seriousness of the moment (this is the moment the user sees 24 words they must protect).
- Confirm the onboarding → home transition uses `MaterializeEffect` consistently.

**What this pass does NOT do**
- No new screens.
- No change to the biometric gating or SeedVault flow (8A shipped, working).

**Wireframe:** _(not needed — visual audit only)_

**Acceptance checklist**
- [ ] No hardcoded `Color(0xFF...)` outside `MidnightColors.kt`
- [ ] Recovery-phrase step passes a screenshot review
- [ ] Home transition animates (no hard cut)

---

# Brand

## 9. App icon — `📝 Stub` (T1-7)

**Primary goal:** a pure-symbol mark (no wordmark) that reads at 24dp and scales cleanly to 512dp. Dusk palette — black and white only.

**Direction**
- AI-generated concepts, then engineer-refined in a vector editor. Per T1-7 decision in plan doc.
- Keep it iconic and abstract — not a literal wallet or coin.
- Test at these sizes: 24, 48, 96, 192, 512 dp.

**Deliverables**
- `ic_launcher.xml` (adaptive icon — foreground + background layers)
- `ic_launcher_round.xml`
- `ic_launcher_foreground.xml`
- `ic_launcher_background.xml` (solid Void)
- Monochrome / themed-icon version (Android 13+)
- Play Store 512×512 PNG

**Acceptance checklist**
- [ ] Reads as intentional at 24dp (launcher min)
- [ ] Contrast passes against both light and dark home screens
- [ ] Monochrome variant is legible
- [ ] Foreground stays inside the adaptive safe zone (66dp of 108dp)

---

## 10. Splash animation — `📝 Stub` (T1-7)

**Primary goal:** a 400–800ms intro that resolves into the balance screen. Reuses the existing `MaterializeEffect` star-dissolve to tie the brand.

**Direction**
- The mark materializes from scattered stars → resolves into the final logo → fades to the balance screen.
- Android 12+ `SplashScreen` API; no third-party library.
- Respect reduced-motion preference (instant swap if user has it set).

**Acceptance checklist**
- [ ] Total length ≤ 800ms in the fastest case
- [ ] Reduced-motion path: no animation, just the mark shown then the app
- [ ] No white flash between splash and first frame of Balance
- [ ] Exists on the `DuskEffect` ambient background, doesn't paint over it

---

# Shared components to build

Enumerated for convenience. Each of these appears on multiple screens; building once is cheaper than per-screen.

| Component | Used by | Priority |
|---|---|---|
| `NetworkBadge` | Balance, Settings, top nav (T1-16), Receive header | P0 |
| `AddressChip` | Send, Receive, TxDetail | P0 |
| `AmountField` | Send | P0 |
| `TxRow` / `TxStatusBadge` / `TxTypeBadge` | TxHistory | P0 |
| `ConfirmRow` | Send confirmation | P0 |
| `ProvingModeBadge` | Send, Send confirmation | P0 |
| `QRCodeCanvas` | Receive | P1 (pick `zxing-android-embedded` or equivalent) |
| `FullScreenQrSheet` | Receive | P1 |
| `MnemonicGrid` | Recovery phrase view | P0 |
| `WarningBlock` | Recovery phrase view; reusable for any destructive-action screen | P0 |
| `SuccessCard` | Send confirmation post-send | P1 |
| `DangerRow` | Settings | P1 |
| `SettingsSectionHeader` | Settings | P1 |

---

# Review + sign-off

When every screen above is `✅ IA approved`, 8B.1 is done and 8B.3 Compose work may begin on the screens in the order listed in `WALLET_PRODUCTIZATION_PLAN.md` 8B.3 sequencing table.

---

# AI wireframe prompt templates

Copy-paste-ready prompts for Figma AI / Galileo / Uizard / v0 / Claude / etc. Each screen prompt is **prefix + body**. Paste the shared prefix once, then the screen-specific body. Iterate the body, not the prefix.

## Shared prefix (copy once per prompt)

```
ROLE
You are a senior mobile product designer laying out a low-fidelity
wireframe for an Android wallet app. You care about information
density, clear hierarchy, and premium polish.

BRAND — "Dusk"
- Black and white only. No color introduces meaning — contrast does.
- Light mode = inverted Dusk (Light foreground on Void background).
  Dark mode = the default (Light foreground on Void).
- Palette tokens already defined:
  Void       #000000  (primary dark bg)
  VoidSoft   #0A0A0A  (elevated surfaces)
  VoidElev   #111111  (cards)
  Light      #FFFFFF  (primary text / icons)
  LightSoft  80% Light (secondary text)
  LightMuted 40% Light (tertiary / placeholders)
  LightFaint 20% Light (separators)
  StarBright 80% Light  (accents)
- Typography: sans, tight tracking on numerals; NO serif.
- Shapes: rounded corners (8-12dp cards, 24dp FABs). No heavy shadows.
- Texture allowed: subtle ambient star effect in background hero areas.

VOICE
Minimalist, intentional. Think Phantom wallet crossed with Linear
crossed with Ledger Live. No cute copy. No emoji in UI strings.

OUTPUT
- Portrait Android, 412 × 892 dp viewport (≈ Pixel 7).
- Low-fidelity wireframe is fine for first pass — greyboxes + type.
- Show the DEFAULT loaded state (happy path, real-ish data), not empty.
- Include top nav + bottom nav if the screen has them.
- Label every custom spacing decision (8 / 16 / 24 dp grid).
- Include the A11y target box on interactive elements (48dp min).
- If the screen has multiple states (loading / error / success), produce them side-by-side.
```

## Per-screen bodies

### 1 — Balance

```
SCREEN: Balance (home)

PRIMARY GOAL
In under a second of opening the app, the user sees their verified
NIGHT + DUST + shielded balances for the current network, and can
initiate the two most common actions (send, receive).

MUST SHOW
- Top nav: wallet name | environment badge (e.g. "PREPROD") | settings icon
- Hero: total balance (NIGHT), big numeric
- Secondary row: DUST balance · Shielded balance ("locked — tap to unlock" if encrypted)
- Last-sync timestamp (relative, "Synced 12s ago")
- Address toggle chip (Unshielded / Shielded)
- Primary actions: SEND and RECEIVE (equal weight, side-by-side)
- Non-dismissible banner above balance: "Back up your recovery phrase →"
  (only until the user has completed phrase view once; hide forever after)

INTERACTIONS
- Pull-to-refresh on the whole screen
- Tap balance hero → drill into a per-token detail (TxHistory filtered)
- Tap address chip → copy to clipboard with haptic + "Copied" pill

LOCKED — don't deviate
- Dusk palette only. No red "warning" color on the backup banner —
  contrast and weight only.
- Environment badge is always visible at the top (T1-16).
- Banner is non-dismissible but must not block scrolling.

INSPIRATIONS (for hierarchy, not color)
- Phantom wallet home — minimalist hero + horizontal action row
- Rainbow — typographic scale of the balance hero
- AVOID: Trust Wallet (too dense), MetaMask (too utilitarian)

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold (top-level wrapper)
- DuskButton (primary + secondary)
- DuskEffect (ambient star background)
- MaterializeEffect (intro animation for the hero)
```

### 2 — Send (compose step)

```
SCREEN: Send (step 1 of 2 — compose)

PRIMARY GOAL
Enter recipient + amount with confidence about which wallet mode
(unshielded / shielded) and which network is being used.

MUST SHOW
- Top nav: back · "Send" · mode indicator
- Mode selector: Unshielded / Shielded (segmented control)
- "To" field: address input with Paste + Scan QR buttons
- "Amount" field: numeric with denomination ("NIGHT") + MAX button
- Static estimated dust fee strip ("≈ 0.0001 DUST")
- Proving mode badge (LOCAL on-device / REMOTE via proof server)
- Primary button: "Review" (disabled until address + amount valid)

INTERACTIONS
- Paste → validates address, shows inline error if wrong network
  (e.g. "This is a Preview address; you're on Preprod")
- Scan QR → opens camera, auto-fills from `midnight:` URI
- MAX → fills amount with balance − fee
- Review → navigates to Send Confirmation (next screen)

LOCKED
- Static fee estimate — NO live dry-run round-trip. Single line.
- Address field must support both bech32m and `midnight:` URIs.
- Proving mode badge is visible but not editable from this screen
  (dev-mode Settings is the editor).

INSPIRATIONS
- Rainbow send — tight compose-in-one-screen
- Phantom — address validation error copy
- Ledger Live — fee estimate chrome

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold, DuskButton
- (New components to design) AddressField, AmountField, FeeEstimateStrip, ProvingModeBadge
```

### 3 — Send Confirmation

```
SCREEN: Send Confirmation (step 2 of 2)

PRIMARY GOAL
Last gate before the user commits. Reread destination, amount, fee.
Tap-to-reveal truncated fields. No surprises.

MUST SHOW
- Top nav: back (preserves all Send fields) · "Review"
- "You're sending" hero (amount + denomination)
- ConfirmRow — TO: truncated address (mn_addr_preprod1abc…xyz, tap to expand)
- ConfirmRow — FROM: truncated source address (same pattern)
- ConfirmRow — FEE: ≈ 0.0001 DUST (static)
- ConfirmRow — TOTAL: amount + fee
- Proving mode badge
- Primary button: "Confirm with biometric"
- Secondary button: "Cancel"

ALSO DESIGN — success state
After signing + submission:
- Success card (not a toast): "Sent" headline + tx hash (truncated, tap to copy)
- Two actions: "View in history" → TxDetail; "Send another" → fresh Send

LOCKED
- Addresses truncated 6…4 by default, tap-to-reveal full + copy
- NO identicons. Text only.
- Post-send is a card, NOT a toast. User must dismiss.
- Cannot confirm without biometric prompt intercepting

INSPIRATIONS
- Ledger Live confirm screen (gold standard)
- Phantom confirm sheet — clean Y/N
- AVOID: MetaMask (too much chrome)

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold, DuskButton
- (New) ConfirmRow, TotalStrip, SuccessCard, ProvingModeBadge (shared with Send)
```

### 4 — Dust

```
SCREEN: Dust

PRIMARY GOAL
See current dust balance, understand dust is generated from held NIGHT
over time, and register for generation if not yet registered.

MUST SHOW (registered state)
- Top nav: back · "Dust" · info icon
- Hero: DUST balance (large numeric)
- Subtitle: generation rate ("≈ 0.0002 DUST/hour from your 10 NIGHT")
- "Next fill estimate" line (optional, if known)
- Registration status: ✓ Registered · link to proof in explorer
- Quiet explainer strip at bottom (≤ 2 lines)

MUST SHOW (UNregistered state — alternate layout, same file)
- Hero: "Not yet registered"
- Body copy (2-3 lines): what dust is, why you register, what it costs
  ("Register to start generating DUST from your NIGHT. One-time
  transaction, costs ~1000 Specks.")
- Primary button: "Register"
- Small disclaimer under: "Requires a NIGHT UTXO and a biometric prompt"

INTERACTIONS
- Register → biometric prompt → proof-server flow → success returns here
- Explorer link → browser
- Info icon → half-sheet explaining dust mechanics (reuse DuskBulletLine)

LOCKED
- Two distinct states (registered / unregistered) in one layout file
- No color for status — use icon weight + typography

INSPIRATIONS
- Solana "rent" explainer in Phantom
- No direct competitor for dust specifically — design from first principles

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold, DuskButton, DuskBulletLine
```

### 5 — Settings

```
SCREEN: Settings (host screen, T1-1)

PRIMARY GOAL
Configure wallet-wide preferences, review network + security, access
destructive actions behind gates.

SECTIONS (in order)
1. NETWORK
   - Current network row (tap opens picker — dev-mode only; in
     non-dev, the row is read-only and shows "Preprod (cannot change)")
   - Network sync status ("Last sync: 12s ago")

2. DEVELOPER OPTIONS (collapsible, hidden by default;
   tap version 7 times on About to reveal)
   - Proof server URL (PLACEHOLDER FOR NOW — shows "Default (local)",
     not editable in v1.0 first pass — see deferred decision)
   - Force re-sync button (danger styling)
   - Build info: version, commit SHA, env

3. SECURITY
   - View recovery phrase (biometric + FLAG_SECURE gate)
   - Test biometric re-auth
   - Wipe wallet (DANGER row — distinct visual weight)

4. ABOUT
   - Version
   - Commit SHA
   - License link
   - GitHub link
   - Support contact

INTERACTIONS
- Every row is a DuskBulletLine with a chevron
- Destructive rows (Wipe, View phrase) visually distinct — NOT red,
  just a heavier treatment (icon + weight)
- Wipe requires: biometric → second confirmation sheet → third "type WIPE" challenge

LOCKED
- Developer section is HIDDEN by default (dev-mode gate)
- Destructive rows visually distinct WITHOUT color
- Network picker is dev-mode-only (non-dev users see read-only row)

INSPIRATIONS
- Phantom settings — compact section list, good density
- Tailscale / 1Password — developer-options-under-version-tap pattern
- AVOID: Rainbow (profile-hero wastes the top of the screen)

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold, DuskBulletLine, DuskButton
- (New) SettingsSectionHeader, DangerRow, NetworkPicker (bottom sheet)
```

### 6 — Transaction History + Detail

```
SCREEN(S): TxHistory list + TxDetail (two-screen pair)

LIST — PRIMARY GOAL
Recognize "I sent 10 NIGHT to Bob yesterday" at a glance. Drill into
any row for full details.

LIST — MUST SHOW
- Top nav: back · "Activity" · filter icon
- Day-grouped sections (Today, Yesterday, Mar 12, etc.)
- Row (per tx):
  • Direction icon (up-arrow = out, down-arrow = in), small
  • Type badge (UNSHIELDED / SHIELDED / DUST / CONTRACT) — short chip, light-on-dark
  • Counterparty address, truncated 6…4
  • Amount + token, right-aligned
  • Relative timestamp, tiny
  • Status affordance: ✓ confirmed (no chrome) · pending (subtle pulse) ·
    failed (weight + icon, NOT red)
- Empty state: explainer line + illustration-free "no activity yet"

DETAIL — MUST SHOW
- Top nav: back · "Transaction" · share icon
- Headline: amount + direction ("Sent 10 NIGHT" or "Received 10 NIGHT")
- Status chip + full timestamp (ISO)
- Type badge
- Full addresses (FROM and TO, both copyable)
- Amount / fee / total
- Tx hash (copyable)
- Block number
- "Open in explorer" row (opens browser)
- Failure reason block (if failed)
- Contract specifics (if CONTRACT type): contract address, method name if known

INTERACTIONS
- Pull-to-refresh on list
- Filter icon → half-sheet filter (by type, by token, by status)
- Tap row → detail
- Detail share icon → share sheet (text summary)

LOCKED
- All four types shown (UNSHIELDED, SHIELDED, DUST, CONTRACT)
- Three lifecycle states shown (PENDING, CONFIRMED, FAILED)
- Data source: local Room cache, indexer-backfilled
  (so list must work offline — indicate "last synced" subtly)

INSPIRATIONS
- Rainbow detail screen (gold standard for density)
- Phantom activity list (day-grouped)
- AVOID: Trust Wallet (too dense), Etherscan embed (data-dump aesthetic)

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold, DuskBulletLine
- (New) TxRow, TxStatusBadge, TxTypeBadge, TxDetailField
```

### 7 — Receive

```
SCREEN: Receive

PRIMARY GOAL
Show a QR code of the current address encoded as a `midnight:` URI,
copy / share / save / full-screen it. Conference-table hand-off is a
real use case.

MUST SHOW
- Top nav: back · "Receive NIGHT on [network]" · full-screen icon
- Tabs: Unshielded / Shielded (tab selection persists across sessions)
- QR code (large, centered, white modules on Void)
- Address text (large enough to read over the user's shoulder, truncated
  with tap-to-reveal full)
- Action row (equal weight): Copy · Share · Save
- Network badge near the address (NOT on the QR)

DO NOT SHOW
- Amount input field. The `midnight:` URI spec supports amount but we
  do NOT surface it on the Receive UI. Keeps the flow to a glance.
- Memo / label input (also URI-only).
- Identicons.

FULL-SCREEN MODE
- QR fills the display edge-to-edge
- Network + address text at top
- Tap anywhere to exit
- Status bar hidden

LOCKED
- QR encodes the `midnight:` URI (not bare address)
- Tabs, not toggle
- Full-screen mode is first-class (not hidden in an overflow)

INSPIRATIONS
- Rainbow receive (just QR + address, minimal)
- Phantom receive (similar)
- AVOID: Trust Wallet (too many fields)

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold, DuskButton
- (New) QRCodeCanvas, AddressChip, FullScreenQrSheet, NetworkBadge (shared)
```

### 7a — Recovery phrase view

```
SCREEN: Recovery phrase view

PRIMARY GOAL
Present the user's 24-word mnemonic with appropriate gravity. Warn
about copy. Gate on biometric (when entered from Settings). Prevent
screenshots (FLAG_SECURE).

MUST SHOW
- Top nav: back · "Recovery phrase"
- Above the grid: a high-contrast warning block
  "Anyone with these 24 words has full control of this wallet.
   Never share them. Never type them into a website.
   Never give them to 'support'."
- Mnemonic grid: 6 rows × 4 columns, each cell shows "<n>. <word>"
  numbered 1-24, serif-or-mono typography so each word is distinct
- Checkbox: "I've safely recorded my phrase"
- Primary button: "Confirm" (disabled until checkbox is ticked)
- Secondary: "Copy" (fires a warning toast on tap)
- Secondary: "Back" (no confirm prompt — backup flag stays un-set)

INTERACTIONS
- Tap Copy → warning toast:
  "This phrase is the only way to recover your wallet.
   Clipboard contents can be read by other apps.
   Only paste into a trusted password manager."
- Tap Confirm → marks `recovery_phrase_viewed = true` in DataStore,
  dismisses the Balance banner, navigates back
- FLAG_SECURE prevents screen-recording (screen-record produces black)

LOCKED
- 24-word grid, no 12-word option
- FLAG_SECURE always on for this screen's lifecycle
- Copy is allowed but with warning toast
- Biometric ALWAYS required on entry from Settings

INSPIRATIONS
- Ledger Live seed-display pattern
- Bitcoin Core "back up your wallet" UX

COMPONENTS ALREADY IN SYSTEM
- DuskScaffold, DuskButton
- (New) MnemonicGrid, WarningBlock
```

### 8 — Onboarding visual pass

```
SCOPE: Onboarding visual-consistency pass (NO IA change)

EXISTING SCREENS (do not redesign, just restyle)
- Welcome screen (app logo + two primary actions: Create / Restore)
- Create wallet flow (seed generation + biometric setup)
- Restore wallet flow (24-word input)
- Success → Home transition

WHAT THIS PASS DOES
- Apply Dusk color tokens consistently (no hardcoded Material3 colors)
- Verify the recovery-phrase step (if it currently exists separately
  from the new Recovery phrase view screen) matches the new pattern
- Confirm Onboarding → Home transition uses MaterializeEffect
- Ensure every screen has the DuskEffect ambient background

WHAT THIS PASS DOES NOT DO
- No new screens
- No change to biometric gating or SeedVault flow (8A shipped, working)
- No change to the 24-word input grid / word-autocomplete (in Restore)

OUTPUT
Provide annotated screenshots of EACH existing onboarding screen with
Dusk tokens applied. Note any hardcoded color references that need
to be removed from the Compose code.
```

## Icon prompt (T1-7)

```
OUTPUT: Android app icon, Dusk palette (black + white only)

GOAL
A pure-symbol mark, no wordmark, that reads at 24dp and scales cleanly
to 512dp. No literal wallet, coin, lock, shield. The mark represents:
"light in darkness — stars against void". Luminosity is the only
variable. Think single abstract glyph.

DELIVERABLES
- Adaptive icon: foreground vector (fits 66dp safe zone on a 108dp canvas)
  and background (solid Void #000000)
- Monochrome / themed variant for Android 13+ (mask fill only)
- Play Store 512×512 PNG
- Round variant

CONSTRAINTS
- Must read at 24dp in a crowded launcher grid
- Must pass contrast against both light and dark wallpapers
- Monochrome variant must be legible (single-color, no gradient)

GENERATE 5-8 CONCEPTS. The engineer will refine one in a vector tool.
```

## Splash prompt (T1-7)

```
OUTPUT: Android 12+ splash animation storyboard (4-6 keyframes)

GOAL
Intro from app-launch to Balance screen ready. Length ≤ 800ms.
Reuses the existing MaterializeEffect (scattered stars resolving into
the glyph) — see core/designsystem/.../effect/MaterializeEffect.kt

KEYFRAMES
1. 0ms — Void background, no mark. Single star point (bright).
2. ~100ms — Stars scatter in from off-screen, resolve toward center.
3. ~400ms — Stars converge into the app icon glyph (full mark visible).
4. ~500ms — Mark pulses once (subtle scale 1.0 → 1.04 → 1.0).
5. ~700ms — Mark fades out; Balance screen fades in on the same Void.
6. 800ms — Balance screen visible, MaterializeEffect on the balance hero
   continues with the same star language.

CONSTRAINTS
- Must respect reduced-motion preference (instant swap if set).
- No white flash between splash and first frame of Balance.
- Android 12+ SplashScreen API; no third-party animation library.

PROVIDE AS
Storyboard frames (5-8 PNGs) + short description of the easing between
each frame (e.g. "1→2: ease-out, 250ms").
```

## How to iterate

1. Paste prefix + body into your AI design tool.
2. Review the first pass. If something looks wrong, the fix is usually in the **LOCKED** block — either the tool ignored a constraint, or our constraint is overconstrained.
3. When a mock looks right, save the PNG / Figma frame, paste the path into the matching stub's `Wireframe:` line, fill the component inventory, tick the checklist.
4. Commit the mocks under `docs/design/wireframes/<screen>.png` so they ship with the repo.
