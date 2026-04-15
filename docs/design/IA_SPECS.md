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
You are a senior mobile product designer extending an existing
Android wallet. You are NOT redesigning the brand — the visual
language is set by the already-shipped Onboarding screen. Your job
is to apply that exact language to new screens.

REFERENCE SCREEN (the north star — match this, do not reinvent it)
File: feature/onboarding/src/main/kotlin/com/midnight/kuira/feature/onboarding/OnboardingScreen.kt

Every screen in this app follows this template verbatim:

  [ambient star background — DuskEffect, subtle]
  [sheet, materializes in]
    ┌─ label      "UPPERCASE TINY", 11sp, letter-spacing 3sp,
    │              color = LightMuted (40%)
    ├─ spacer     20dp
    ├─ headline   18–22sp, FontWeight.W300 (light weight),
    │              color = Light (100%), lineHeight 28sp on 22sp
    ├─ spacer     4dp
    ├─ detail     13sp, color = LightMuted, lineHeight 18sp
    ├─ spacer     24–48dp
    ├─ CONTENT    (bullets, inputs, data…)
    ├─ spacer     48dp
    └─ actions    DuskPrimaryButton full-width, 8dp gap,
                  DuskSecondaryButton full-width
                  OR DuskButtonRow (secondary + primary, 2dp gap)

Deviating from this template needs a REASON. Volume must earn the
extra chrome.

BRAND — "Dusk" (from core/designsystem/.../theme/MidnightColors.kt)

There is NO accent color. No green for success, no red for error, no
yellow for warning. Emphasis is luminosity and weight, not hue.

Palette — DARK MODE (shipped, in MidnightColors.kt):

  Semantic role          Token            Value
  ───────────────────────────────────────────────────
  Primary background     Void             0xFF000000
  Elevated surface       VoidSoft         0xFF0A0A0A
  Card surface           VoidElevated     0xFF111111
  Primary fg / icons     Light            0xFFFFFFFF
  Secondary text         LightSoft        0xCCFFFFFF   (80%)
  Tertiary / small label LightMuted       0x66FFFFFF   (40%)
  Separator / disabled   LightFaint       0x33FFFFFF   (20%)
  Input bg / pressed     LightBarely      0x1AFFFFFF   (10%)
  Ambient star bright    StarBright       0xCCFFFFFF   (= LightSoft)
  Ambient star dim       StarDim          0x33FFFFFF   (= LightFaint)
  Primary button fill    Confirm          0xFFFFFFFF   (= Light)
  Secondary button fill  ConfirmSurface   0x1AFFFFFF   (= LightBarely)
  Tertiary / cancel txt  RejectText       0x66FFFFFF   (= LightMuted)

Palette — LIGHT MODE (design target for this sprint; inverted Dusk):

Use the SAME semantic roles. In dark mode "Void" is the background; in
light mode the background is a near-white surface with black foreground.
Keep the SAME α values — only swap the base color from 0xFFFFFF to
0x000000 for foreground-on-light, and from 0x000000 to 0xF7F7F7 for the
surface stack. This is not invented; it's a straight semantic inversion.

  Semantic role          Dark               Light
  ────────────────────────────────────────────────────────
  Primary background     Void 0xFF000000    0xFFF7F7F7
  Elevated surface       VoidSoft 0xFF0A0A0A 0xFFFFFFFF
  Card surface           VoidElevated 0xFF111111 0xFFFAFAFA
  Primary fg / icons     Light 0xFFFFFFFF   0xFF000000
  Secondary text         LightSoft 0xCCFFFFFF 0xCC000000
  Tertiary / small label LightMuted 0x66FFFFFF 0x66000000
  Separator / disabled   LightFaint 0x33FFFFFF 0x33000000
  Input bg / pressed     LightBarely 0x1AFFFFFF 0x0A000000
  Ambient star bright    StarBright 0xCCFFFFFF 0x33000000
  Ambient star dim       StarDim 0x33FFFFFF   0x14000000
  Primary button fill    Confirm 0xFFFFFFFF   0xFF000000
  Secondary button fill  ConfirmSurface 0x1AFFFFFF 0x0A000000
  Tertiary / cancel txt  RejectText 0x66FFFFFF 0x66000000

Notes on light-mode deltas:
- Primary bg is 0xFFF7F7F7 (slightly off-white) not pure white, so the
  elevated surface 0xFFFFFFFF can read as "lifted".
- Ambient stars become LESS prominent on light bg — the star effect
  is a brand texture, not a primary channel. Don't compete with text.
- Input bg 0x0A000000 (4% black) is lighter than its dark-mode sibling
  (10% white) because pure white bg tolerates less darkening before it
  looks "dirty".

GENERATE PAIRED FRAMES
Every screen wireframe ships as TWO frames: dark (left) and light (right),
side by side, so we can confirm semantic fidelity. If a component looks
right in one mode but wrong in the other, the component is wrong, not
the mode.

TYPOGRAPHY
- Sans-serif, no serif anywhere.
- FontWeight.W300 (light weight) for headlines and body. NEVER bold.
  Contrast comes from color/opacity, not weight.
- Tiny uppercase labels are the signature: 11sp, letter-spacing 3sp,
  LightMuted.
- Numerals: tight tracking; large balance numbers may use W200 for
  extra lightness.

SHAPES
- Rounded corners: 12dp on input fields, 8-10dp on cards, 20-24dp
  on full-width buttons. No perfect circles except FABs.
- No drop shadows. Elevation is VoidSoft / VoidElevated layered on Void.

VOICE
Minimalist, intentional, technical but not cold. Onboarding copy sets
the tone — reread it before generating new copy. No marketing
adjectives. No emoji in UI strings. No cute error messages.

EXISTING COMPONENTS (reuse; do not suggest duplicates)
- DuskScaffold        — full-screen shell with ambient DuskEffect
- DuskPrimaryButton   — filled, Light bg, Void text, full-width default
- DuskSecondaryButton — outlined, LightBarely bg, Light text
- DuskButtonRow       — secondary + primary horizontal pair
- DuskBulletLine      — bullet list row (feature / explainer lists)
- MaterializeEffect   — star-particle intro animation for hero areas
- DuskEffect          — ambient star background (already in DuskScaffold)

OUTPUT
- Portrait Android, 412 × 892 dp viewport (≈ Pixel 7).
- Low-fidelity is fine for first pass — greyboxes + typography specs.
- Show the DEFAULT loaded state (happy path, real-ish data), not empty.
- Include top nav if the screen has one; this app has no bottom nav.
- Label every custom spacing decision (4 / 8 / 12 / 16 / 20 / 24 / 32 / 48 dp).
- Mark 48dp min-touch-target on every interactive element.
- If the screen has multiple states (loading / error / success / empty),
  produce them side-by-side or as stacked frames in one output.
```

## Per-screen bodies

Each body follows the same shape: **GOAL → LAYOUT (onboarding template slots) → INTERACTIONS → LOCKED → INSPIRATION → NEW COMPONENTS**. No redundant prose.

### 1 — Balance

```
SCREEN: Balance (home)

GOAL
In under 1s of cold open, the user sees NIGHT + DUST + shielded
balances on the current network and can tap SEND or RECEIVE.

LAYOUT (top → bottom)
- Top strip: wallet name · network badge (T1-16) · settings icon
- Banner ONLY if recovery phrase never viewed:
  "Back up your recovery phrase" with a chevron.
  LightBarely bg, Light text, 12dp radius. No red.
- label    "BALANCE"
- headline Large NIGHT numeric (FontWeight.W200 allowed here)
- detail   "Synced 12s ago"
- content  Row: DUST value · Shielded value
           Shielded may read "locked — tap to unlock" if encrypted
- content  AddressChip (Unshielded / Shielded toggle, copyable)
- actions  DuskButtonRow: RECEIVE (secondary) · SEND (primary)

INTERACTIONS
- Pull-to-refresh → manual sync
- Tap balance hero → TxHistory filtered to that token
- Tap address chip → copy, haptic, "Copied" pill

LOCKED
- Environment badge is always visible at the top.
- Backup banner is non-dismissible; disappears forever after first
  phrase-view completion. No color used to draw attention.
- No fiat values anywhere.

INSPIRATION
Phantom home (hero + action row). Do not copy Trust or MetaMask.

NEW COMPONENTS
- NetworkBadge, BackupBanner, AddressChip, BalanceHero
```

### 2 — Send (compose)

```
SCREEN: Send — step 1 of 2

GOAL
Enter recipient + amount. Clear about mode (unshielded/shielded) and
network. Cannot proceed without valid address + amount.

LAYOUT
- Top: back · "Send"
- label    "SEND"
- headline "From your <Unshielded|Shielded> wallet"
- detail   network name
- content  Mode selector (segmented: Unshielded / Shielded)
- content  AddressField
             - rounded 12dp, LightBarely bg (match onboarding input)
             - inline suffix: [Paste] [Scan]
             - below-field error text in LightMuted if wrong-network
- content  AmountField
             - numeric only, denomination "NIGHT" trailing
             - inline suffix: [MAX]
- content  FeeEstimateStrip: "≈ 0.0001 DUST · static estimate"
- content  ProvingModeBadge (read-only here)
- actions  DuskPrimaryButton "Review" (disabled until valid)

INTERACTIONS
- Paste validates address; wrong-network error is specific
  ("This is a Preview address; you're on Preprod")
- Scan opens camera, fills from `midnight:` URI
- MAX = balance − fee
- Review → Send Confirmation

LOCKED
- Static fee, no dry-run round-trip.
- ProvingModeBadge displays the current mode but is NOT editable
  from this screen (Settings owns the toggle).

INSPIRATION
Rainbow compose-in-one-screen. Phantom error copy.

NEW COMPONENTS
- AddressField, AmountField, FeeEstimateStrip, ProvingModeBadge,
  ModeSegmentedControl
```

### 3 — Send Confirmation

```
SCREEN: Send — step 2 of 2

GOAL
Last gate before signing. Re-read destination, amount, fee. No
surprises.

LAYOUT
- Top: back (preserves Send fields) · "Review"
- label    "REVIEW"
- headline "<amount> NIGHT"   (hero numeric)
- detail   "to <truncated address>"
- content  ConfirmRow TO     truncated 6…4, tap to expand + copy
- content  ConfirmRow FROM   truncated, tap to expand
- content  ConfirmRow FEE    "≈ 0.0001 DUST"
- content  ConfirmRow TOTAL  amount + fee
- content  ProvingModeBadge
- actions  DuskButtonRow: Cancel · "Confirm with biometric"

SUCCESS STATE (separate frame, same file)
- label    "SENT"
- headline "<amount> NIGHT sent"
- detail   tx hash truncated, tap to copy
- actions  DuskButtonRow: "View in history" · "Send another"

INTERACTIONS
- Back preserves every Send field (do not reset).
- Confirm triggers biometric; on success transitions to the Sent frame.
- Tap truncated address → expand + copy, haptic + "Copied" pill.

LOCKED
- Truncated 6…4 addresses, tap-to-reveal + copy. No identicons.
- Post-send is a card, not a toast.
- Confirm fires biometric prompt; cannot skip.

INSPIRATION
Ledger Live confirm (gold standard). Phantom confirm sheet.

NEW COMPONENTS
- ConfirmRow, TotalStrip, SuccessCard
```

### 4 — Dust

```
SCREEN: Dust

GOAL
See DUST balance + generation rate. If unregistered, 1-tap register
with a brief explainer.

LAYOUT — REGISTERED
- Top: back · "Dust"
- label    "DUST BALANCE"
- headline DUST numeric (hero)
- detail   "≈ 0.0002 DUST/hour from your <N> NIGHT"
- content  Row: ✓ Registered · "View proof" (explorer link)
- content  DuskBulletLine explainer (≤ 2 lines)

LAYOUT — UNREGISTERED (separate frame)
- label    "DUST"
- headline "Not yet registered"
- detail   2-line explainer (what dust is, why register, 1-time cost)
- content  (none)
- actions  DuskPrimaryButton "Register"
- footer   Small LightMuted line:
           "Requires a NIGHT UTXO and a biometric prompt"

INTERACTIONS
- Register → biometric → proof-server flow → success returns to
  registered state

LOCKED
- Two states, same file.
- No color for status — icon weight + typography only.

INSPIRATION
Solana "rent" explainer in Phantom. No other direct parallel.

NEW COMPONENTS
- (Optional, probably v1.1) DustGenerationChart
```

### 5 — Settings

```
SCREEN: Settings (T1-1 host)

GOAL
Wallet-wide preferences, security, destructive actions behind gates.

LAYOUT (section list)
- Top: back · "Settings"
- SettingsSectionHeader "NETWORK"
  - DuskBulletLine "Current network"  → picker (dev-mode only; else
    read-only chevron absent)
  - DuskBulletLine "Last synced 12s ago" (read-only)

- SettingsSectionHeader "DEVELOPER OPTIONS" (hidden until user taps
  About → version seven times)
  - DuskBulletLine "Proof server"  placeholder row, shows
    "Default (local)", non-editable in v1.0 first pass
  - DuskBulletLine "Force re-sync"
  - DuskBulletLine "Build info"  version · commit SHA · env

- SettingsSectionHeader "SECURITY"
  - DuskBulletLine "View recovery phrase"  (biometric + FLAG_SECURE)
  - DuskBulletLine "Test biometric re-auth"
  - DangerRow "Wipe wallet"

- SettingsSectionHeader "ABOUT"
  - version, commit SHA, license, GitHub, support

INTERACTIONS
- Wipe wallet: biometric → confirm sheet → "type WIPE" challenge.
- Tapping App-version on About 7× toggles Developer section visibility.

LOCKED
- Developer section hidden by default.
- Destructive rows visually distinct WITHOUT color (heavier weight,
  icon, larger row height).
- Network picker is dev-mode-only.

INSPIRATION
Phantom section list density. Tailscale / 1Password for the
version-tap-for-developer pattern.

NEW COMPONENTS
- SettingsSectionHeader, DangerRow, NetworkPicker (bottom sheet)
```

### 6 — Transaction History + Detail (pair)

```
SCREEN A: Tx History list

GOAL
Recognize a past tx at a glance; drill in for full detail.

LAYOUT
- Top: back · "Activity" · filter icon
- Day-grouped sections: "Today", "Yesterday", "Mar 12", …
- TxRow per tx:
  [direction glyph]  [TxTypeBadge]  <counterparty 6…4>
                                           <amount>  <token>
                                           <relative>  <status>
  Direction glyph: up = out, down = in. Small, LightSoft.
  Status chrome: confirmed = none · pending = subtle pulse ·
  failed = glyph + heavier weight (no red).
- Empty state: label "ACTIVITY" · headline "No transactions yet" ·
  detail 1 line.

SCREEN B: Tx Detail

LAYOUT
- Top: back · "Transaction" · share icon
- label    "<direction> · <TxTypeBadge>"
- headline "<amount> <token>"
- detail   full ISO timestamp + relative
- content  ConfirmRow FROM, TO  (both full addresses, copyable)
- content  ConfirmRow AMOUNT, FEE, TOTAL
- content  ConfirmRow TX HASH  (copyable)
- content  ConfirmRow BLOCK
- content  DuskBulletLine "Open in explorer"  → browser
- content  (if failed) WarningBlock with reason
- content  (if CONTRACT) contract address, method name

INTERACTIONS
- List: pull-to-refresh, tap row → detail, filter icon → half-sheet
- Detail: share icon → share sheet (text summary)

LOCKED
- All four types (UNSHIELDED, SHIELDED, DUST, CONTRACT) shown.
- All three lifecycle states (PENDING, CONFIRMED, FAILED).
- Data source: local Room cache; list works offline.

INSPIRATION
Rainbow detail density. Phantom day-grouped list.

NEW COMPONENTS
- TxRow, TxStatusBadge, TxTypeBadge, TxDetailField
  (TxDetailField ≈ ConfirmRow for reuse)
```

### 7 — Receive

```
SCREEN: Receive

GOAL
Show a QR encoding a `midnight:` URI of the current address; copy,
share, save, or go full-screen for conference hand-off.

LAYOUT
- Top: back · "Receive NIGHT on <network>" · full-screen icon
- Tabs: Unshielded / Shielded   (tab choice persists)
- content  QR canvas, centered
           modules: Light (#FFFFFF)  on bg: Void (#000000)
           quiet-zone ≥ 4 modules
- content  AddressChip: full address, tap-to-copy
- actions  DuskButtonRow: Copy · Share · Save (three secondary)
- (Full-screen sheet): QR fills display, status bar hidden,
  tap-anywhere exit

INTERACTIONS
- Tab swap → QR re-renders against the new address.
- Copy → haptic + "Copied" pill.
- Share → native Android share sheet (URI text + QR image).
- Save → writes PNG `kuira-receive-<short-addr>.png` to Photos.
- Full-screen icon → enters edge-to-edge QR mode; tap anywhere exits.

LOCKED
- QR encodes the `midnight:` URI (not bare address).
- Tabs, not toggle.
- NO amount / memo inputs on UI — URI scheme carries them, UI does
  not.
- NO identicons.
- Full-screen mode is first-class, not behind overflow.

INSPIRATION
Rainbow / Phantom receive (minimal QR + address). Avoid Trust Wallet.

NEW COMPONENTS
- QRCodeCanvas, FullScreenQrSheet
```

### 7a — Recovery phrase view

```
SCREEN: Recovery phrase view

GOAL
Show 24 words with gravity. Warn about copy. Biometric-gated.
Screen-recording blocked (FLAG_SECURE).

LAYOUT
- Top: back · "Recovery phrase"
- label    "RECOVERY PHRASE"
- headline "Write these words in order"
- detail   "Anyone with these 24 words has full control of this
            wallet. Never share them. Never type them into a
            website. Never give them to 'support'."  (3 lines, W300)
- content  MnemonicGrid: 6 rows × 4 cols, "<n>. <word>" per cell
           Numerals in LightMuted, word in Light, mono or tight-tracked
- content  Checkbox: "I've safely recorded my phrase"
- actions  DuskButtonRow: Copy (fires warning toast) · Confirm
           (primary, disabled until checkbox ticked)

INTERACTIONS
- Copy toast: "Clipboard contents can be read by other apps. Only
  paste into a trusted password manager."
- Confirm writes `recovery_phrase_viewed = true`, dismisses the
  Balance banner forever.
- Back without confirm leaves the flag un-set.

LOCKED
- 24 words, no 12-word variant.
- FLAG_SECURE on for the screen's lifecycle.
- Biometric ALWAYS required when entered from Settings.
- Warning copy uses weight + size, NOT red.

INSPIRATION
Ledger Live seed display.

NEW COMPONENTS
- MnemonicGrid, WarningBlock (shared with Tx Detail failure)
```

### 8 — Onboarding visual pass

```
SCOPE: Onboarding audit — NO IA change

EXISTING SCREENS
- Welcome, Create, Restore, Status (checking / creating / success),
  NeedsAuthSetup, Error

WHAT THIS PASS DOES
- Confirm every color is a MidnightColors token, not a hex literal
  or a Material3 default.
- Confirm headline/label/detail hierarchy matches the onboarding
  template exactly (it currently does — this is the validation).
- Confirm MaterializeEffect fires on Onboarding → Home transition.

WHAT THIS PASS DOES NOT DO
- No new screens, no IA change, no re-copy.
- No change to biometric gating or SeedVault flow (8A is frozen).

OUTPUT
For each existing onboarding frame: annotated screenshot showing
which tokens are used where, plus a list of any `Color(0xFF…)`
literals or Material3 default color references to remove from the
Compose code.
```

## App icon prompt (T1-7)

```
OUTPUT: Android app icon concepts — Dusk palette

GOAL
A pure-symbol mark (no wordmark). Reads at 24dp, scales cleanly to
512dp. Represents "light in darkness — stars against void". No
literal wallet, coin, lock, shield.

DELIVERABLES
- Adaptive icon foreground (fits the 66dp safe zone of a 108dp canvas)
- Adaptive icon background = solid Void (#000000)
- Monochrome themed variant for Android 13+ (mask shape only)
- Round variant
- Play Store 512×512 PNG

CONSTRAINTS
- Must read at 24dp in a crowded launcher grid
- Must maintain contrast against both light and dark wallpapers
- Monochrome variant must be legible (single-color, no gradient)

GENERATE 5-8 concepts. Engineer refines one in a vector editor.
```

## Splash prompt (T1-7)

```
OUTPUT: Android 12+ splash animation storyboard — 4-6 keyframes

GOAL
App-launch → Balance-ready in ≤ 800ms. Reuses the existing
MaterializeEffect (see core/designsystem/.../effect/MaterializeEffect.kt)
so the splash feels like the same visual language as every other hero
transition.

KEYFRAMES
1.  0ms — Void background; single bright StarBright point centered.
2. 100ms — Additional stars scatter in from off-screen.
3. 400ms — Stars converge into the app-icon glyph (full mark).
4. 500ms — Mark pulses once (scale 1.0 → 1.04 → 1.0).
5. 700ms — Mark fades; Balance screen fades in on the same Void.
6. 800ms — Balance visible; MaterializeEffect on the balance hero
           continues the same star language seamlessly.

CONSTRAINTS
- Respect reduced-motion (instant swap, no animation).
- No white flash between splash and first frame of Balance.
- Android 12+ SplashScreen API; no third-party animation lib.

PROVIDE AS
Storyboard frames (5-8 PNGs) plus the easing curve between each
frame (e.g. "1→2: ease-out, 250ms").
```

## How to iterate

1. Paste the shared prefix + one screen body into your AI design tool.
2. Review. If the output deviates, the fix is almost always in the **LOCKED** block — either the tool ignored a constraint, or the constraint is overconstrained. Tighten the prompt, don't patch the mock.
3. When a mock looks right, save the PNG/Figma frame, paste the path into the stub's `Wireframe:` line, fill the component inventory, tick the checklist, flip status to `✅ IA approved`.
4. Commit mocks under `docs/design/wireframes/<screen>.png`.

## Follow-up code task (gate for 8B.3 Compose work on light mode)

`core/designsystem/.../theme/Theme.kt` still defines `LightColorScheme`
using `Purple40 / PurpleGrey40 / Pink40` — Material defaults from the
Compose project template. The inverted-Dusk tokens this doc specifies
for light mode are NOT in code yet. Before any screen can be
implemented against a light-mode wireframe, an engineer must:

1. Introduce semantic aliases (`Surface`, `OnSurface`, `SurfaceElevated`,
   `OnSurfaceSoft`, `OnSurfaceMuted`, `OnSurfaceFaint`, `OnSurfaceBarely`,
   `Accent`, `OnAccent`, `AmbientStarBright`, `AmbientStarDim`) that
   resolve to the correct Dusk token per mode. Either a `@Composable`
   provider reading `isSystemInDarkTheme()` or a pair of objects
   (`DuskDark` / `DuskLight`) selected at the theme level.
2. Populate `DuskLight` with the ARGB values listed in the LIGHT MODE
   table in the prefix above.
3. Rewrite `LightColorScheme` in `Theme.kt` against those aliases;
   delete the `Purple*` / `Pink*` references.
4. Audit existing Composables (Onboarding primarily) to migrate from
   direct `MidnightColors.Light` / `MidnightColors.Void` references to
   the semantic aliases. Leaves the dark-mode rendering unchanged but
   unblocks light-mode.

This is ~4-6h of work and does not depend on any of the screen
wireframes. It can run in parallel with this design sprint.
