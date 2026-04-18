# Screen — Send Confirmation (review receipt + submit state machine)

## 1. GOAL

Final gate before a transaction commits. Show a receipt-style review
with the amount as hero, mode badge, recipient, fee estimate, and
total. On Confirm: biometric → submit state machine → success or
error.

This is the ONLY place in the app where a transaction commits. The
Send flow (02) cannot submit. The biometric + spinner + success/error
all live here.

## 2. SITEMAP POSITION

- `from:` Send flow Screen 2c (Review text-button)
- `to:` Balance on dismiss · Tx History detail (via "View in history"
  once T1-5 is built) · Send flow Screen 2a (via "Send another")

## 3. STATES

| State           | Applies?  | Notes                                                           |
|-----------------|-----------|-----------------------------------------------------------------|
| `default`       | ✓         | Receipt visible; Confirm enabled; Cancel available              |
| `loading-first` | n/a       |                                                                 |
| `syncing`       | n/a       |                                                                 |
| `empty`         | n/a       |                                                                 |
| `no-results`    | n/a       |                                                                 |
| `error`         | ✓         | Submit failed. Receipt visible, ErrorCard below, Try Again + Cancel |
| `offline`       | ✓         | Cannot submit — Confirm disabled, inline hint below FEE section |
| `pending`       | ✓         | Post-biometric. Receipt fades; StepIndicator shows pipeline progress |
| `success`       | ✓         | Success hero + tx hash + "View in history" + "Send another"    |

**Variants:** none. State differences swap in/out whole sections per
the layouts below.

## 4. LAYOUT

### Layout — `default` (receipt review)

```
[DuskScaffold] — ambient StarField

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]  (Icons.AutoMirrored.Filled.ArrowBack)
  "Review"        (type-body, Light)

space-16 (screen horizontal inset throughout)

space-32 top spacing

[GlassPanel — receipt hero, contentPanel tint, star-protected, contentPadding = 24dp]
  [Amount]       <format-amount-night>  (type-numeric-hero, Light, centered)
  space-4
  [Denomination] "NIGHT"               (type-headline-sm, LightMuted, centered)
  space-8
  [Mode badge]   pill: "UNSHIELDED" or "SHIELDED"
                 (type-label-tiny, LightSoft, bg LightBarely, radius-full,
                  centered below denomination)

space-32

[GlassPanel — details card, contentPadding = 0.dp]
  SettingsRow (readOnly = true, rightValueMono = true,
               trailingIcon = Icons.Filled.ContentCopy)
    label       "To"
    rightValue  <format-address-short>
    onClick     copy full address + haptic-tap + "Copied" toast
  1dp LightFaint divider
  SettingsRow (readOnly = true)
    label       "Network fee"
    rightValue  "≈ <format-amount-night> NIGHT"  (LightMuted; ≈ signals estimate)
  1dp LightFaint divider
  SettingsRow (readOnly = true)
    label       "Total"
    rightValue  "<format-amount-night> NIGHT"    (Light; amount + fee)

space-48

[Action stack]
  DuskButtonRowPaletted
    secondary   "Cancel"
    primary     "Confirm"

space-24 above safe-area-insets.bottom
```

### Layout — `pending`

Receipt fades out. Single GlassPanel with StepIndicator replaces
the content area.

```
[Top bar] — back arrow DISABLED (alpha LightMuted). Title: "Review"

space-48 top spacing

[GlassPanel — hero, full-width, content-driven height, contentPadding = 24dp]
  StepIndicator
    step label     (type-body, Light, centered)
    detail hint    (type-detail, LightMuted, centered)

(no action stack — submit in flight)

space-24 above safe-area-insets.bottom
```

### Layout — `success`

```
[Top bar]
  left slot      "Done" (type-body, Light — tappable, dismisses to Balance)
  title          "Sent"

space-48 top spacing

[GlassPanel — success hero, contentPadding = 24dp]
  icon-32        (Icons.Filled.Check, Light, centered)
  space-20
  headline       "Transaction submitted"    (type-headline-sm, Light, centered)
  space-8
  detail         "It may take a few minutes to confirm."
                                            (type-detail, LightMuted, centered)

space-32

SettingsSectionHeader "TRANSACTION"
space-12

[GlassPanel — contentPadding = 0.dp]
  SettingsRow (readOnly = true, rightValueMono = true,
               trailingIcon = Icons.Filled.ContentCopy)
    label       "Hash"
    rightValue  <format-hash-short>
    onClick     copy full hash + haptic-tap + "Copied" toast
  1dp LightFaint divider
  SettingsRow (default — nav row with chevron)
    label       "View in history"
    onClick     navigate to Tx History (T1-5 stub: routes to Balance)

space-48

DuskPrimaryButtonPaletted "Send another"  (full-width)

space-24 above safe-area-insets.bottom
```

### Layout — `error`

Receipt stays visible. ErrorCard appears below the details panel.

```
…(receipt hero + details GlassPanel render same as default)

space-32   (sectioned-list inter-section rhythm)

ErrorCard
  headline   "Submit failed"             (type-body, Light)
  body       <error-specific copy>       (type-detail, LightMuted)

space-48

DuskButtonRowPaletted
  secondary   "Cancel"
  primary     "Try Again"

space-24 above safe-area-insets.bottom
```

### Layout — `offline`

Same as `default`, plus:

- Below the details GlassPanel: a hint in `type-caption`, `LightMuted`:
  `You're offline. Connect to a network to send.`
- Confirm button is disabled (`LightBarely` bg, `LightMuted` text).
- Cancel button remains enabled.

## 5. INTERACTIONS

| Element                  | Gesture | Result                                                   |
|--------------------------|---------|----------------------------------------------------------|
| Back arrow (default)     | Tap     | Pop to Send flow Screen 2c (amount preserved)            |
| Back arrow (pending)     | Tap     | No-op (disabled)                                         |
| "Done" (success)         | Tap     | Pop entire Send flow back to Balance                     |
| To row (default/error)   | Tap     | Copy full address · `Copied` toast · `haptic-tap`        |
| Hash row (success)       | Tap     | Copy full hash · `Copied` toast · `haptic-tap`           |
| "View in history"        | Tap     | Navigate to Tx History detail (T1-5 stub: Balance)       |
| "Send another"           | Tap     | Pop to Send flow Screen 2a with form fully reset         |
| Cancel                   | Tap     | Pop to Send flow Screen 2c                               |
| Confirm                  | Tap     | Biometric prompt → on auth: transition to `pending`; on cancel: stay on `default` |
| Try Again                | Tap     | Biometric prompt → `pending` re-entry (same tx params)   |
| System back (success)    | Gesture | Same as "Done" — pop to Balance                         |
| System back (pending)    | Gesture | No-op (disabled, same as back arrow)                    |

## 6. MOTION

- Entry: `MaterializeEffect` on the receipt-hero amount
  (`motion-emphasize`).
- `default` → `pending`: receipt sections fade out (`motion-standard`,
  alpha); StepIndicator fades in with `motion-emphasize`.
- StepIndicator sub-step advance: label crossfade (`motion-fast`).
- `pending` → `success`: StepIndicator fades out; success hero fades
  in with `motion-emphasize` + subtle scale-from-97% on the check
  icon.
- `pending` → `error`: StepIndicator fades out; ErrorCard fades in
  with `motion-standard`.
- Biometric prompt: system chrome, follows OS motion.
- Reduce-motion: all of the above snap to end state.

## 7. HAPTICS

| Trigger                          | Token            |
|----------------------------------|------------------|
| Confirm / Try Again              | `haptic-tap`     |
| Biometric success                | `haptic-confirm` |
| Transaction submitted            | `haptic-confirm` |
| Submit failed                    | `haptic-tap`     |
| Copy (address or hash)           | `haptic-tap`     |
| Cancel / Done / Send another     | `haptic-tap`     |

No "error" haptic exists in the token set; failure is signaled with
`haptic-tap` + visual ErrorCard.

## 8. COPY

Exact strings; do not rewrite.

### Top bar

- Title (default/error/offline/pending): `Review`
- Title (success): `Sent`
- Left slot (success): `Done` (replaces back arrow)

### Labels

- Row labels: `To`, `Network fee`, `Total`, `Hash`, `View in history`
- Denomination: `NIGHT`
- Mode badge: `UNSHIELDED` / `SHIELDED`
- Fee prefix: `≈` (estimate signal)
- Success headline: `Transaction submitted`
- Success detail: `It may take a few minutes to confirm.`
- Section header (success): `TRANSACTION`

### Buttons

- Confirm (default): `Confirm`
- Cancel (default/error): `Cancel`
- Try Again (error): `Try Again`
- Send another (success): `Send another`

### StepIndicator step labels

| Internal step             | Label                  | Detail hint                                              |
|---------------------------|------------------------|----------------------------------------------------------|
| Building                  | `Building transaction` | `Preparing inputs…`                                      |
| Signing                   | `Signing`              | `Signing with device key…`                               |
| Proving (shielded only)   | `Generating proof`     | `This can take a few minutes on the first shielded send.` |
| Submitting                | `Submitting`           | `Broadcasting to the network…`                           |
| SyncingAndRetrying        | `Re-syncing`           | `Refreshing wallet state and retrying… (attempt <n>)`    |

### Error body copy (ErrorCard)

- Generic: `Something went wrong.` + one-line context (first clause
  of exception message, trimmed to 80 chars).
- Network unreachable: `No network. Connect and try again.`
- Biometric cancelled: no error card — remain on `default`.
- Insufficient balance (race condition): `Not enough NIGHT for amount + fee.`
  (`ErrorText` for this specific copy.)
- Stale UTXO (after auto-retry cap): `Wallet state drifted. Refresh and try again.`

### Offline hint

- `You're offline. Connect to a network to send.` (`type-caption`, `LightMuted`)

### Toasts

- After address copy: `Copied`
- After hash copy: `Copied`

### A11Y announcements

- On `pending` transition: announce current step label.
- On sub-step advance: announce new step label.
- On `success` transition: announce `Transaction submitted`.
- On `error` transition: announce ErrorCard headline + body.

## 9. A11Y

- Focus order (default): back arrow → amount (announced) → mode badge
  → To row → Network fee row → Total row → Cancel → Confirm.
- Focus order (pending): top-bar only (back disabled); StepIndicator
  announced via dynamic CD.
- Focus order (success): Done → Hash row → View in history → Send
  another.
- Focus order (error): back arrow → receipt rows → ErrorCard →
  Cancel → Try Again.
- Static content descriptions:
  - back arrow (default/error): `Back to amount`
  - "Done" (success): `Done, dismiss to balance`
  - copy icons: `Copy to clipboard`
  - View in history: `View this transaction in history`
- Touch targets: 56dp minimum on SettingsRow instances; 48dp minimum
  on all buttons and icon taps.
- Dynamic type: `type-numeric-hero` on the receipt hero scales; test
  at max font to verify layout.
- Biometric prompt: system chrome handles its own a11y.

## 10. VISUAL LOCKED

- Dusk palette only. No accent color for hierarchy or decoration.
  `ErrorText` is the only color token — see `_prefix.md` palette
  rules. Confirmation uses `ErrorText` for: insufficient-balance
  race-condition copy in ErrorCard body, fee-exceeds-balance hint.
  Success check glyph and ErrorCard icon stay `Light`.
- Receipt hero sits inside a `GlassPanel` (`contentPanel` tint,
  `contentPadding = 24dp`) for star-protection — same policy as
  Balance hero and Send Amount hero. Critical financial content
  must always read clearly over the ambient StarField.
- StepIndicator shows text-only state advancement — NO progress bar,
  NO percent, NO color-coded checkmarks. Current step label only.
- ErrorCard is self-contained — wraps its own GlassPanel.
- Details card and TRANSACTION card use `GlassPanel` with
  `contentPadding = 0.dp` — rows own their own padding.
- Section rhythm follows `_prefix.md` LIST ROWS (space-12 / space-32).
- Every spacing value MUST be a `space-*` token.
- Success state top bar replaces the back arrow with a "Done" text
  button (`type-body`, `Light`) in the left slot. This deviates from
  the `_prefix.md` TOP BAR template (icon-24 back arrow). Reason:
  after a successful submit, "back" is semantically wrong — the user
  isn't going back, they're dismissing. "Done" matches iOS/Android
  convention for post-action dismissal and is acknowledged in
  `_prefix.md` TOP BAR as an accepted left-slot variant.

## 11. PRODUCT LOCKED

- Biometric required for every submit. No caching, no bypass.
- Fee is a **static estimate** — no live dry-run. Server-actual fee
  wins silently at submit time; Tx History is the place to see the
  real fee.
- "Total" = amount + estimated fee.
- Back arrow disabled during `pending` (prevents backing out mid-proof).
  System-back also no-ops.
- "Send another" resets the entire Send flow (pops to Screen 2a). Does
  NOT preserve recipient — forces fresh intent.
- "View in history" routes to Tx History detail (T1-5). Until T1-5 is
  built, routes to Balance as a stub.
- `midnight:` URI deeplinks cannot land here directly. Only reachable
  from Send Screen 2c. No external source can push a user to a
  pre-filled biometric prompt.
- Stale-UTXO auto-recovery (SyncingAndRetrying) is visible as a step
  with attempt counter. User cannot cancel. Cycles until success or
  the submit-layer attempt cap fires → transition to `error`.

## 12. NEW COMPONENTS

| Component        | Shape                                                                       |
|------------------|-----------------------------------------------------------------------------|
| `StepIndicator`  | Text-only progress for the submit state machine. Centered column: step label (`type-body`, `Light`) + `space-8` + detail hint (`type-detail`, `LightMuted`, max 2 lines, centered). NO progress bar, NO percent, NO step-list, NO checkmarks. Label crossfades on advance (`motion-fast`). Wraps in a `GlassPanel` on the `pending` layout. |
| `ErrorCard`      | Self-contained error block. Wraps its own `GlassPanel` (`palette.contentPanel`, `LightFaint` border, `radius-md`). Internal: Row with `icon-20` ErrorOutline (`Light`) + `space-8` + Column with headline (`type-body`, `Light`) + `space-4` + body (`type-detail`, `LightMuted`, max 2 lines). Persistent until user picks Cancel or Try Again. |

**Reused components:** `SettingsRow` (review rows + success hash),
`SettingsSectionHeader`, `GlassPanel`, `DuskPrimaryButtonPaletted`,
`DuskButtonRowPaletted`, `ToastPill`, `MaterializeEffect`.

---

End of Send Confirmation spec. Ship paired dark + light frames for
`default`, `pending` (with each sub-step), `success`, `error`,
`offline`.
