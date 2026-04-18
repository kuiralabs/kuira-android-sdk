# Screen — Dust (balance + registration CTA, T1-8 redesign)

## 1. GOAL

Show DUST token balance, generation progress, NIGHT backing amount,
and a registration CTA for wallets that haven't registered their dust
tank. The registration flow runs a multi-step state machine
(build → prove → seal → submit) with live progress.

## 2. SITEMAP POSITION

- `from:` Balance (DUST row tap)
- `to:` Balance (back arrow) · Tx History filtered to DUST
  (via hero tap, once T1-5 supports filtering)

## 3. STATES

| State           | Applies?  | Notes                                             |
|-----------------|-----------|---------------------------------------------------|
| `default`       | ✓         | Registered tank: balance + generation progress    |
| `loading-first` | ✓         | Fetching dust status from chain                   |
| `syncing`       | n/a       | Dust status is fetched on entry, not periodically |
| `empty`         | ✓         | Not registered — show registration CTA            |
| `no-results`    | n/a       |                                                   |
| `error`         | ✓         | Fetch or registration failed                      |
| `offline`       | n/a       | Dust requires network (no local cache for status) |
| `pending`       | ✓         | Registration in progress (multi-step)             |
| `success`       | ✓         | Registration complete — tx hash shown             |

## 4. LAYOUT

### Layout — `default` (registered tank)

```
[DuskScaffold] — ambient StarField

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]  (Icons.AutoMirrored.Filled.ArrowBack)
  "Dust"          (type-body, Light)

space-16 (screen horizontal inset throughout)

space-32 top spacing

[GlassPanel — hero, contentPadding = 24dp]
  label          "DUST BALANCE"        (type-label-tiny)
  space-20
  headline       <format-amount-dust>  (type-numeric-hero, Light)
  space-4
  denomination   "DUST"                (type-headline-sm, LightMuted)

space-32

SettingsSectionHeader "STATUS"
space-12

[GlassPanel — contentPadding = 0.dp]
  SettingsRow (readOnly = true)
    label       "NIGHT backing"
    rightValue  "<format-amount-night> NIGHT"
  divider
  SettingsRow (readOnly = true)
    label       "Generation"
    rightValue  "<percent>%"     (e.g., "42%")
  divider
  SettingsRow (readOnly = true)
    label       "Generation rate"
    rightValue  "<rate> DUST/block"

space-24 above safe-area-insets.bottom
```

### Layout — `empty` (not registered)

```
[Top bar] — same

space-32 top spacing

[GlassPanel — hero, contentPadding = 24dp]
  icon-32        (Icons.Filled.Token, LightMuted, centered)
  space-20
  headline       "Register your dust tank"  (type-headline-sm, Light, centered)
  space-8
  detail         "Dust generates passively from your NIGHT
                  balance. Register once to start earning."
                                            (type-detail, LightMuted, centered)

space-48

DuskPrimaryButtonPaletted  "Register"  (full-width)

space-24 above safe-area-insets.bottom
```

### Layout — `loading-first`

Same skeleton as `default`. Hero shows shimmer block for amount.
STATUS rows show shimmer for right values.

### Layout — `pending` (registration in progress)

```
[Top bar] — same; back arrow DISABLED during registration

space-48 top spacing

[GlassPanel — hero, contentPadding = 24dp]
  StepIndicator
    step label     (type-body, Light, centered)
    detail hint    (type-detail, LightMuted, centered)

(no action stack — registration in flight)

space-24 above safe-area-insets.bottom
```

Registration steps (reuses `StepIndicator` from 03-send-confirmation):

| Step      | Label                    | Detail hint                   |
|-----------|--------------------------|-------------------------------|
| Build     | `Building registration`  | `Preparing dust tank…`        |
| Prove     | `Generating proof`       | `This may take a few minutes.`|
| Seal      | `Sealing transaction`    | `Applying signature…`         |
| Submit    | `Submitting`             | `Broadcasting to the network…`|

### Layout — `success` (registration complete)

```
[Top bar] — back arrow re-enabled

space-48 top spacing

[GlassPanel — success hero, contentPadding = 24dp]
  icon-32        (Icons.Filled.Check, Light, centered)
  space-20
  headline       "Dust tank registered"  (type-headline-sm, Light, centered)
  space-8
  detail         "Generation will begin on the next block."
                                         (type-detail, LightMuted, centered)

space-32

SettingsSectionHeader "TRANSACTION"
space-12

[GlassPanel — contentPadding = 0.dp]
  SettingsRow (readOnly = true, rightValueMono = true,
               trailingIcon = Icons.Filled.ContentCopy)
    label       "Hash"
    rightValue  <format-hash-short>
    onClick     copy full hash · haptic-tap · "Copied" toast

space-32

DuskPrimaryButtonPaletted  "Check Dust Status"  (full-width, re-fetches)

space-24 above safe-area-insets.bottom
```

### Layout — `error`

```
[Top bar] — same

space-32 top spacing

ErrorCard
  headline       "Something went wrong"     (type-body, Light)
  body           <error-specific copy>      (type-detail, LightMuted)

space-48

DuskPrimaryButtonPaletted  "Try Again"  (full-width)

space-24 above safe-area-insets.bottom
```

## 5. INTERACTIONS

| Element                | Gesture | Result                                         |
|------------------------|---------|------------------------------------------------|
| Back arrow             | Tap     | Pop to Balance (disabled during `pending`)     |
| Hero (default)         | Tap     | Tx History filtered to DUST (stub: Tx History unfiltered until v1.1+) |
| Register button        | Tap     | Biometric → `pending` (registration state machine) |
| Check Dust Status      | Tap     | Re-fetch dust status → `loading-first` → `default` |
| Try Again              | Tap     | Re-attempt the failed operation                |
| Hash row (success)     | Tap     | Copy full hash · `Copied` toast · `haptic-tap` |

## 6. MOTION

- Entry: `MaterializeEffect` on the hero panel (`motion-emphasize`).
- `empty` → `pending`: CTA fades out; StepIndicator fades in
  (`motion-standard` + `motion-emphasize`).
- StepIndicator step advance: label crossfade (`motion-fast`).
- `pending` → `success`: StepIndicator fades out; success hero fades
  in with `motion-emphasize` + scale-from-97% on check icon.
- `pending` → `error`: StepIndicator fades out; ErrorCard fades in
  (`motion-standard`).
- Reduce-motion: all snap to end state.

## 7. HAPTICS

| Trigger                          | Token            |
|----------------------------------|------------------|
| Register (pre-biometric)         | `haptic-tap`     |
| Biometric success                | `haptic-confirm` |
| Registration complete            | `haptic-confirm` |
| Registration failed              | `haptic-tap`     |
| Copy hash                        | `haptic-tap`     |
| Check Dust Status                | `haptic-tap`     |
| Try Again                        | `haptic-tap`     |

## 8. COPY

Exact strings; do not rewrite.

### Labels

- Top bar title: `Dust`
- Hero label (default): `DUST BALANCE`
- Hero denomination: `DUST`
- Status section header: `STATUS`
- Status row labels: `NIGHT backing`, `Generation`, `Generation rate`
- Generation rate unit: `DUST/block`
- Success section header: `TRANSACTION`

### Empty state

- Headline: `Register your dust tank`
- Detail: `Dust generates passively from your NIGHT balance. Register once to start earning.`
- Button: `Register`

### Success state

- Headline: `Dust tank registered`
- Detail: `Generation will begin on the next block.`
- Button: `Check Dust Status`

### Error state

- Headline: `Something went wrong`
- Button: `Try Again`

### Registration steps

- Build: `Building registration` / `Preparing dust tank…`
- Prove: `Generating proof` / `This may take a few minutes.`
- Seal: `Sealing transaction` / `Applying signature…`
- Submit: `Submitting` / `Broadcasting to the network…`

### Toasts

- After hash copy: `Copied`

## 9. A11Y

- Focus order (default): back arrow → hero amount → NIGHT backing →
  Generation → Generation rate.
- Focus order (empty): back arrow → headline → detail → Register.
- Focus order (pending): back arrow (disabled) → StepIndicator
  (announced via dynamic CD).
- Focus order (success): back arrow → headline → Hash row →
  Check Dust Status.
- Content descriptions:
  - back arrow: `Back to balance`
  - hero: `<amount> DUST balance`
  - Register: `Register dust tank`
  - StepIndicator: announces current step label on transition
- Touch targets: SettingsRow instances 56dp minimum. Buttons 48dp.
- Dynamic type: `type-numeric-hero` scales; test at max font.
- Reduce-motion: all transitions snap.

## 10. VISUAL LOCKED

- Dusk palette only. `ErrorText` is not used on this screen (dust
  registration errors are operational failures, not financial danger).
- Hero balance sits in a `GlassPanel` for star-protection (same
  policy as Balance hero).
- StepIndicator reuses the exact same component and GlassPanel
  wrapping from Send Confirmation `pending` layout.
- ErrorCard is self-contained (wraps its own GlassPanel) — same
  component from 03-send-confirmation §12.
- Success layout mirrors Send Confirmation success: check icon +
  headline + detail in a GlassPanel, then TRANSACTION section below.
- Day-group-style rhythm not applicable (no list). Spacing follows
  the visual language template (space-32 between hero and STATUS).
- Every spacing value MUST be a `space-*` token.

## 11. PRODUCT LOCKED

- Registration requires biometric. No bypass.
- Registration is a single on-chain transaction (build → prove →
  seal → submit). The proving step is the longest (may take minutes
  on first run).
- Back arrow disabled during `pending` — same rule as Send
  Confirmation. Prevents wasting a proof.
- "Check Dust Status" on success re-fetches from chain — the tank
  should now be active and showing generation progress.
- Dust generation rate + NIGHT backing are read from chain state.
  The screen does not compute these locally.
- The existing `DustScreen.kt` in `feature:dust` is functional but
  uses raw Material 3 styling. This spec covers the Dusk redesign
  (T1-8 scope); the production code's IA may be kept but the visual
  layer is replaced.

## 12. NEW COMPONENTS

No new components. This screen reuses:

- `StepIndicator` (from 03-send-confirmation §12) for registration
  progress
- `ErrorCard` (from 03-send-confirmation §12) for failure states
- `SettingsRow` (from 05-settings §12) for STATUS detail rows and
  success hash row
- `SettingsSectionHeader` for section headers
- `GlassPanel` for hero panels and row containers
- `DuskPrimaryButtonPaletted` for actions
- `ToastPill` for copy confirmations

---

End of Dust spec. Ship paired dark + light frames for `default`,
`loading-first`, `empty`, `pending` (with each step), `success`,
`error`.
