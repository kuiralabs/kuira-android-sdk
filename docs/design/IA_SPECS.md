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
