# Screen — Send flow (3-screen wizard: Token+Mode → Recipient → Amount)

## 1. GOAL

Compose a transaction across three focused screens. Each screen asks
ONE question:

```
Screen 2a  "What are you sending?"      → pick token + privacy mode
Screen 2b  "To whom?"                   → enter recipient address
Screen 2c  "How much?"                  → enter amount (hero display)
```

Tapping "Review" on Screen 2c hands off to Send Confirmation (03).
No screen in this flow submits a transaction — Confirmation owns
biometric + submit + success/error.

## 2. SITEMAP POSITION

- `from:` Balance (Send quick-action circle) · inbound `midnight:`
  URI deeplink (can skip to 2b or 2c with prefilled fields)
- `to:` Send Confirmation (03, via "Review" on Screen 2c) ·
  Balance (back arrow on any screen pops the wizard)
- Internal flow: 2a → 2b → 2c (linear, back arrow pops one step)

## 3. STATES

### Screen 2a — Token + Mode

| State           | Applies?  | Notes                                      |
|-----------------|-----------|----------------------------------------------|
| `default`       | ✓         | Token list with mode sub-options             |
| `loading-first` | n/a       |                                              |
| `syncing`       | n/a       |                                              |
| `empty`         | n/a       | Always at least NIGHT in v1.0                |
| `no-results`    | n/a       |                                              |
| `error`         | n/a       |                                              |
| `offline`       | n/a       | Token list is local; no network needed       |
| `pending`       | n/a       |                                              |
| `success`       | n/a       |                                              |

### Screen 2b — Recipient

| State           | Applies?  | Notes                                        |
|-----------------|-----------|------------------------------------------------|
| `default`       | ✓         | Empty input, keyboard open                    |
| `loading-first` | n/a       |                                                |
| `syncing`       | n/a       |                                                |
| `empty`         | n/a       |                                                |
| `no-results`    | n/a       |                                                |
| `error`         | n/a       | See `default / invalid` variant                |
| `offline`       | n/a       | Input is local; no network needed              |
| `pending`       | n/a       |                                                |
| `success`       | n/a       |                                                |

**Variants:**

- `default / invalid` — address prefix doesn't match selected mode
  or address is malformed. Inline error caption in `LightMuted` (not
  `ErrorText` — address errors are "fix this" hints, not financial
  danger).
- `default / prefilled-from-uri` — inbound `midnight:` URI filled
  the address. Mode auto-flipped to match prefix. If URI also carried
  `?amount=`, auto-advance to Screen 2c after a 300ms pause.

### Screen 2c — Amount

| State           | Applies?  | Notes                                        |
|-----------------|-----------|------------------------------------------------|
| `default`       | ✓         | Hero number at 0, keyboard open               |
| `loading-first` | n/a       |                                                |
| `syncing`       | n/a       |                                                |
| `empty`         | n/a       |                                                |
| `no-results`    | n/a       |                                                |
| `error`         | n/a       | See `default / insufficient` variant           |
| `offline`       | ✓         | Cached balance shown; "Review" still tappable  |
| `pending`       | n/a       |                                                |
| `success`       | n/a       |                                                |

**Variants:**

- `default / insufficient` — amount exceeds available balance. Hero
  number + denomination turn `ErrorText` (red). Error caption in
  `ErrorText` below.
- `default / prefilled` — amount pre-filled from `midnight:` URI.
  Hero already shows the number; user can adjust before tapping Review.

## 4. LAYOUT

All three screens share: `DuskScaffold` with ambient `StarField`,
56dp top bar with back arrow, `space-16` horizontal screen inset.
Section rhythm follows `_prefix.md` LIST ROWS where sections apply.

### Screen 2a — Token + Mode

```
[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]  (Icons.AutoMirrored.Filled.ArrowBack)
  "Send"          (type-body, Light)

space-16 (screen horizontal inset throughout)

space-32 top spacing

SettingsSectionHeader "SELECT TOKEN"
space-12

[TokenModeCard — NIGHT]   — see §12 NEW COMPONENTS
  [GlassPanel — contentPanel tint, LightFaint hairline]
    [Token header row]
      label         "NIGHT"                 (type-body, Light)
      rightValue    <format-amount-night>   (type-body, LightMuted)
    1dp LightFaint divider
    [Mode sub-row: Unshielded]
      label         "Unshielded"            (type-body, Light)
      hint          "Visible on chain"      (type-caption, LightMuted)
      trailing      icon-16 chevron         (LightMuted)
      tap           → Screen 2b with mode = unshielded
    1dp LightFaint divider
    [Mode sub-row: Shielded]
      label         "Shielded"              (type-body, Light)
      hint          "Private · ZK proof"    (type-caption, LightMuted)
      trailing      icon-16 chevron         (LightMuted)
      tap           → Screen 2b with mode = shielded

(v1.1+ : additional TokenModeCards for DUST, custom tokens. Same
 card pattern, same mode sub-rows per token.)

space-24 above safe-area-insets.bottom
```

### Screen 2b — Recipient

```
[Top bar] 56dp
  [icon-24 back]
  "Send NIGHT"    (type-body, Light — shows selected token)
  — flex —
  (no right-slot)

space-16 (screen horizontal inset throughout)

space-32 top spacing

[Mode badge]  — pill showing selected mode
  "UNSHIELDED" or "SHIELDED"   (type-label-tiny, LightSoft,
                                 bg LightBarely, radius-full)

space-24

SettingsSectionHeader "TO"
space-12

[GlassPanel]
  DuskInputField  (recipient, monospace = true)
    placeholder    "mn_addr_preprod1…" OR "mn_shield-addr_preprod1…"
                   (matches selected mode)
    trailingSlot   Row {
                     icon-20 paste (Icons.Filled.ContentPaste, LightMuted)
                     (v1.1+: icon-20 scan QR)
                   }
    error          see variant `default / invalid`

space-16

[Recently used]  — v1.1+ slot; empty in v1.0
  (type-caption, LightMuted: "No recent recipients")

— flex — (push button to bottom)

DuskPrimaryButtonPaletted  "Next"  (full-width, enabled when address valid)

space-24 above safe-area-insets.bottom
```

### Screen 2c — Amount (the hero screen)

```
[Top bar] 56dp
  [icon-24 back]
  "Enter Amount"  (type-body, Light)
  — flex —
  "Review"        (type-body, LightSoft — right-slot text button,
                   enabled when amount valid and > 0;
                   tapping navigates to Confirmation 03)

space-16 (screen horizontal inset throughout)

[Recipient chip]  — below top bar, full-width, bg LightBarely, radius-md
  "To: <format-address-short>"  (type-caption, LightMuted)
  trailing  icon-16 edit (Icons.Filled.Edit, LightMuted) — taps back to 2b

— flex — (vertically centers the hero in available space)

[GlassPanel — hero, contentPanel tint, star-protected]
  [AmountHeroInput]  — centered horizontally
    [Amount number]      <typed digits>   (type-numeric-hero, Light)
    [Denomination]       " NIGHT" or " USD"  (type-headline-sm, LightMuted,
                                              baseline-aligned with number)
    [Swap toggle ↕]      32dp circle, bg LightBarely, radius-full
                         tap flips between NIGHT and USD input modes
    space-8
    [Conversion hint]    NIGHT mode: "~$<value>"  (type-detail, LightMuted)
                         USD mode:   "~<value> NIGHT"
                         Hidden when amount is empty or zero.
    space-8
    [Error caption]      (only when amount > balance)
                         "Insufficient balance"
                         (type-detail, ErrorText)

    When insufficient: number + denomination also render in ErrorText
    instead of Light/LightMuted. The red IS the error signal — no
    border change, no modal, no toast.

  Two input modes (toggled by ↕):
    NIGHT: user types NIGHT, sees "~$X.XX" conversion below
    USD:   user types dollars, sees "~X.XX NIGHT" conversion below
  Mock rate ($1 = 1 NIGHT) for wireframe; real oracle in production.

— flex —

[Bottom bar]  — anchored above system nav inset
  Row {
    Column {
      "Available"                    (type-caption, LightMuted)
      "<format-amount-night> NIGHT"  (type-body, Light)
    }
    — flex —
    [MAX button]  — tappable pill, bg LightBarely, radius-full
      "MAX"        (type-label-tiny, LightSoft)
      48dp tap target
  }

space-16

[System numeric keyboard]  — no custom numpad; Android system keyboard

space-24 above safe-area-insets.bottom (when keyboard is hidden)
```

### Screen 2c — variant `default / insufficient`

Same as Screen 2c `default`, plus:

- Hero number renders in `ErrorText` instead of `Light`.
- "NIGHT" denomination renders in `ErrorText` instead of `LightMuted`.
- Stars conversion hidden (invalid amount — no point converting).
- Error caption visible: "Insufficient balance" in `ErrorText`.
- "Review" text button (top-right) is disabled (`LightFaint`).

### Screen 2c — variant `default / prefilled`

Same as Screen 2c `default`, but:

- Hero number pre-filled from `midnight:` URI amount.
- "Review" is enabled (form pre-valid).
- One-time toast on entry: `Pasted from midnight: link` (ToastPill, 2s).

## 5. INTERACTIONS

### Screen 2a

| Element                | Gesture | Result                                           |
|------------------------|---------|--------------------------------------------------|
| Back arrow             | Tap     | Pop to Balance                                   |
| Mode sub-row           | Tap     | Navigate to Screen 2b with token + mode in args  |

### Screen 2b

| Element                | Gesture | Result                                           |
|------------------------|---------|--------------------------------------------------|
| Back arrow             | Tap     | Pop to Screen 2a                                 |
| Paste icon             | Tap     | Paste clipboard. `midnight:` URI → parse address + amount + mode. Plain text → address only. |
| Recipient field        | Type    | Live prefix validation; error on blur             |
| Next button            | Tap     | Navigate to Screen 2c with recipient in args     |

### Screen 2c

| Element                | Gesture | Result                                           |
|------------------------|---------|--------------------------------------------------|
| Back arrow             | Tap     | Pop to Screen 2b (recipient preserved)           |
| Recipient chip         | Tap     | Pop to Screen 2b (edit recipient)                |
| Amount digits          | Type    | Hero number grows; live balance check; conversion hint updates |
| Swap toggle (↕)        | Tap     | Flip input mode: NIGHT → USD or USD → NIGHT. Conversion hint swaps. Amount value resets to empty on flip. |
| MAX button             | Tap     | Fill with full available balance (always in NIGHT, regardless of input mode) · haptic-tap |
| Review (top-right)     | Tap     | Navigate to Send Confirmation (03) with mode + recipient + amount (always passed as NIGHT, converted if input was USD) |

## 6. MOTION

- Screen transitions: `motion-standard` (standard nav push/pop).
- Screen 2a entry: `MaterializeEffect` on "SELECT TOKEN" header
  (`motion-emphasize`).
- Screen 2c hero digits grow/shrink: `motion-fast` on each keystroke
  (number width adjusts to content).
- Screen 2c error state: number color flip to `ErrorText` is instant
  (no animation — it's a danger signal, not a transition).
- Stars conversion appears/hides: `motion-fast` alpha fade.
- `midnight:` URI auto-advance (2b → 2c): `motion-standard`.
- Reduce-motion: all of the above snap to end state.

## 7. HAPTICS

| Trigger                  | Token           |
|--------------------------|-----------------|
| Mode sub-row tap         | `haptic-tap`    |
| Paste-from-clipboard     | `haptic-tap`    |
| Swap toggle (↕)          | `haptic-tap`    |
| Next (valid form)        | `haptic-tap`    |
| MAX button               | `haptic-tap`    |
| Review (valid amount)    | `haptic-tap`    |

## 8. COPY

Exact strings; do not rewrite.

### Top bar titles

- Screen 2a: `Send`
- Screen 2b: `Send NIGHT` (includes selected token name)
- Screen 2c: `Enter Amount` (left) · `Review` (right, text button)

### Labels

- Screen 2a section header: `SELECT TOKEN`
- Screen 2a token: `NIGHT`
- Screen 2a mode labels: `Unshielded`, `Shielded`
- Screen 2a mode hints: `Visible on chain`, `Private · ZK proof`
- Screen 2b section header: `TO`
- Screen 2b mode badge: `UNSHIELDED` / `SHIELDED`
- Screen 2b empty recent: `No recent recipients`
- Screen 2b button: `Next`
- Screen 2c recipient chip prefix: `To:`
- Screen 2c denomination (NIGHT mode): `NIGHT`
- Screen 2c denomination (USD mode): `USD`
- Screen 2c conversion hint (NIGHT mode): `~$<value>`
- Screen 2c conversion hint (USD mode): `~<value> NIGHT`
- Screen 2c available label: `Available`
- Screen 2c MAX button: `MAX`

### Placeholders

- Unshielded recipient: `mn_addr_preprod1…`
- Shielded recipient: `mn_shield-addr_preprod1…`

### Error copy

- Wrong-mode address (unshielded selected): `This is not an unshielded address`  (`LightMuted`)
- Wrong-mode address (shielded selected): `This is not a shielded address`  (`LightMuted`)
- Malformed address: `This doesn't look like a Midnight address`  (`LightMuted`)
- Insufficient balance: `Insufficient balance`  (`ErrorText`)

### Toasts

- After `midnight:` URI paste: `Pasted from midnight: link`
- After paste-from-clipboard (non-URI): none

## 9. A11Y

### Screen 2a

- Focus order: back arrow → NIGHT token header → Unshielded sub-row
  → Shielded sub-row.
- Content descriptions:
  - back arrow: `Back to balance`
  - Unshielded sub-row: `Send NIGHT, unshielded, visible on chain`
  - Shielded sub-row: `Send NIGHT, shielded, private with ZK proof`

### Screen 2b

- Focus order: back arrow → mode badge → recipient input → paste
  icon → Next button.
- Content descriptions:
  - back arrow: `Back to token selection`
  - paste icon: `Paste recipient from clipboard`
  - mode badge: not interactive, announced as `Mode: <mode>`
- Dynamic CD: after paste, announce `Recipient address filled from
  clipboard` on the input field.

### Screen 2c

- Focus order: back arrow → recipient chip → amount input → swap
  toggle → MAX → Review (top-right).
- Content descriptions:
  - back arrow: `Back to recipient`
  - recipient chip: `Sending to <address short>. Tap to edit.`
  - swap toggle: `Switch between NIGHT and USD input. Current: <mode>`
  - MAX: `Fill with available balance`
  - Review: `Review transaction`
- Dynamic CD: announce `Insufficient balance` when error appears.
  Announce `Switched to <mode> input` when swap toggle is tapped.

### All screens

- Touch targets: 48dp minimum everywhere. Mode sub-rows and recipient
  chip follow LIST ROWS 56dp minimum. MAX button has 48dp tap target
  around its pill glyph.
- Dynamic type: `type-numeric-hero` scales; on largest font setting,
  the hero may need a smaller fallback size to prevent text overflow.
  Test at max font.
- Reduce-motion: all transitions snap to end state.

## 10. VISUAL LOCKED

- Dusk palette only. No accent color for hierarchy or decoration.
  `ErrorText` is the only color token — see `_prefix.md` palette
  rules. Send uses `ErrorText` for: amount hero number + denomination
  + error caption when amount exceeds balance. Address validation
  stays `LightMuted` — not a financial danger signal.
- Mode sub-rows on Screen 2a are NOT a segmented toggle — they are
  independent tappable rows inside a GlassPanel. Each row navigates
  forward. This is different from the `ModeToggle` component used in
  the old single-form spec; the new flow replaces the toggle with
  card-based selection.
- Screen 2c: the amount hero sits inside a `GlassPanel` (`contentPanel`
  tint, `LightFaint` border, `contentPadding = 24.dp`) for star-
  protection — same content-protection policy as Balance's hero panel.
  The hero number is critical financial content; star noise behind it
  is a distraction, not texture.
- Screen 2c: bottom bar (Available + MAX) is anchored above the
  keyboard, not scrollable. It stays visible at all times while
  typing. Implementation note: use `imePadding()` on the bottom bar
  so it rides above the system keyboard on Android.
- Screen 2b: "Next" button at the bottom must also stay above the
  keyboard — same `imePadding()` approach.
- Screen 2b: `space-24` gap between the mode badge and the TO section
  header is intentionally tighter than the `space-32` inter-section
  standard. The badge is a standalone label, not a section panel — it
  doesn't carry the "grey gutter" weight of a panel-to-header gap.
- Screen 2c top bar uses a text button ("Review") in the right slot
  instead of an `icon-24` action. This deviates from the `_prefix.md`
  TOP BAR template. Reason: the hero screen fills the viewport with
  the number; a fat bottom button competes with the keyboard and
  clutters the hero space. A top-right text action keeps the hero
  clean. This matches Phantom's "Next" pattern.
- Token+Mode card (Screen 2a) uses GlassPanel with `contentPanel`
  tint and `contentPadding = 0.dp` — rows own their own padding
  (same pattern as Settings).
- Every spacing value MUST be a `space-*` token.

## 11. PRODUCT LOCKED

- No screen in this flow submits a transaction. Review navigates to
  Send Confirmation (03).
- Proving mode selector does NOT appear anywhere in the send flow.
  It lives in Settings Developer Options per T1-18.
- `midnight:` URI paste auto-flips mode to match the address prefix
  — the URI wins over the user's previous selection.
- MAX fills the full available balance. Fee deduction happens on
  Confirmation — the MAX-filled amount may reduce on the review
  screen.
- Form state across screens is preserved in the nav back-stack.
  Backing from 2c → 2b preserves the recipient; backing from 2b → 2a
  clears the recipient (fresh start on re-entry).
- Exiting the flow entirely (back from 2a → Balance) clears all
  state. No draft-save.
- Internal unit name is `stars` (matches code: `NIGHT × 1,000,000 =
  Stars`). Do not invent parallel names.
- Amount input enforces max 6 decimals at the input-filter level —
  keystrokes beyond the 6th decimal place are silently rejected. No
  error copy needed; the user simply cannot type an invalid precision.
  This matches `_prefix.md` `format-amount-night` (up to 6 decimals).
- Token list in v1.0 shows NIGHT only. The empty-token and
  multi-token patterns are deferred to v1.1+.

## 12. NEW COMPONENTS

| Component           | Shape                                                                    |
|---------------------|--------------------------------------------------------------------------|
| `TokenModeCard`     | Full-width card inside a `GlassPanel` for a single token. Header row: reuses `SettingsRow` with `readOnly = true`, `label = token name`, `rightValue = balance` — inherits 56dp minimum and all SettingsRow styling. Below: 1+ mode sub-rows, each a custom tappable row with: label (`type-body`, `Light`) on the first line, hint below label (`type-caption`, `LightMuted`) on a second line, trailing `icon-16` chevron (`LightMuted`). Sub-rows are 56dp minimum (LIST ROWS standard) but render TWO lines of content so they're naturally taller. Dividers between header and sub-rows, and between sub-rows. In v1.0 there is one card (NIGHT) with two sub-rows. In v1.1+: one card per token; the pattern scales without layout changes. |
| `AmountHeroInput`   | Hero-scale amount input with denomination swap. NOT a `DuskInputField`. Display `Text` (styled as `type-numeric-hero`, 44sp, W200, Light) renders the typed digits; a hidden `BasicTextField` captures keyboard input via `FocusRequester`. Denomination label (`type-headline-sm`, `LightMuted`, shows `NIGHT` or `USD` per input mode) baseline-aligned to the right. **Swap toggle (↕):** 32dp circle, `LightBarely` bg, `radius-full`, to the right of the denomination; tap flips between NIGHT and USD input modes. Conversion hint below (`type-detail`, `LightMuted`): NIGHT mode shows `~$X.XX`, USD mode shows `~X.XX NIGHT`. On insufficient-balance error: number + denomination flip to `ErrorText`; error caption appears below in `ErrorText`. Wrapped in a `GlassPanel` by the caller for star-protection. System numeric keyboard drives input; max 6 decimals enforced at input-filter level. |
| `RecipientChip`     | Compact bar below the top bar on Screen 2c. Full-width, `LightBarely` bg, `radius-md`. Shows `To: <format-address-short>` (`type-caption`, `LightMuted`). Trailing `icon-16` edit glyph. Tap navigates back to Screen 2b. Height content-driven with 48dp minimum (accessibility floor). |

**Reused components (no contract changes):** `DuskInputField` (Screen
2b recipient — with `monospace = true` and trailing paste icon),
`SettingsSectionHeader`, `GlassPanel`, `DuskPrimaryButtonPaletted`,
`ToastPill`.

**Retired components (from old single-form spec):** `ModeToggle`
(replaced by `TokenModeCard` sub-rows — the mode choice is no longer
a toggle; it's a selection that navigates forward). `FromAddressRow`
concept (the FROM section is gone — the wallet address is implicit
and the available balance moves to Screen 2c's bottom bar).

---

End of Send flow spec. Ship paired dark + light frames for each
screen: 2a `default`, 2b `default` / `default / invalid` /
`default / prefilled-from-uri`, 2c `default` / `default / insufficient`
/ `default / prefilled` / `offline`.
