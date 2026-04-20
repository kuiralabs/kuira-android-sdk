# Screen — Onboarding flow (T1-8 visual pass + new "All set" screen)

## 1. GOAL

Linear wizard for first-launch wallet creation. Five screens, each
with one job. Biometric-only auth (no passcode in v1.0). Ends with
Terms/Privacy agreement before entering Balance.

## 2. SITEMAP POSITION

- `from:` App first launch (no wallet exists)
- `to:` Recovery phrase view (08, post-creation) · Balance (01, after
  "All set" completion)

## 3. STATES

Onboarding is a linear wizard. Each step is its own composable.

| State           | Applies?  | Notes                                       |
|-----------------|-----------|---------------------------------------------|
| `default`       | ✓         | Each step in the wizard                     |
| `loading-first` | n/a       |                                             |
| `syncing`       | n/a       |                                             |
| `empty`         | n/a       |                                             |
| `no-results`    | n/a       |                                             |
| `error`         | ✓         | Wallet creation failure (rare)              |
| `offline`       | n/a       | Wallet creation is local                    |
| `pending`       | ✓         | Seed generation in progress (~2s)           |
| `success`       | n/a       | Handled by "All set" screen (step 7)        |

## 4. LAYOUT

### v1.0 onboarding flow (7 steps)

```
Step 1: WELCOME
Step 2: ENTER PASSCODE (6-digit PIN, custom numpad)
Step 3: CONFIRM PASSCODE (re-enter to verify)
Step 4: BIOMETRIC SETUP (optional upgrade over passcode)
Step 5: CREATING (RunnerWithDust animation, ~3s intentional)
Step 6: RECOVERY PHRASE (spec 08, onboarding variant)
Step 7: ALL SET (Terms/Privacy + completion)
→ Balance
```

### Step 1 — Welcome

```
[DuskScaffold] — ambient StarField

— flex — (center content vertically)

[KuiraMaterializeFrame]  — animated KUIRA wordmark with sparkle +
                           breathing glow (48sp, W300, white with
                           shadow glow). Brand hero — first impression.
space-32
  [Tagline]      "Where night protects the day."
                                   (type-headline-sm, LightSoft, centered,
                                    letter-spacing 1sp)
                 — from the Rarámuri belief that the soul is active
                   at night. Kuira derives from their language;
                   Midnight is the blockchain.

— flex —

[Action stack]
  DuskPrimaryButtonPaletted  "Create wallet"          (full-width)
  space-8
  DuskSecondaryButtonPaletted "Import existing wallet" (full-width)

space-24 above safe-area-insets.bottom
```

### Step 2 — Enter passcode

```
[DuskScaffold]

[Top bar] 56dp
  [icon-24 back]
  "Enter new passcode"   (type-body, Light)

— flex — (center vertically)

[6 dots]  — centered row, space-16 between dots
  PasscodeDotSize (16dp) circles. Filled (Light) for entered digits,
  unfilled (LightFaint) for remaining.

— flex —

[Custom numpad]  — 3×4 grid, centered
  NumpadButtonSize (72dp) circles, LightBarely bg, CircleShape.
  Digits 1-9, 0, backspace icon. Tapping fills dots.

space-24 above safe-area-insets.bottom
```

### Step 3 — Confirm passcode

Same layout as Step 2 with title "Confirm passcode". If mismatch,
dots flash briefly + haptic-tap + clear. User re-enters.

### Step 4 — Biometric setup

```
[DuskScaffold]

[Top bar] 56dp
  [icon-24 back]
  "Secure your wallet"   (type-body, Light)

— flex — (center content vertically)

[Centered content — no GlassPanel (simple icon + text)]
  icon-64        (Icons.Filled.Fingerprint, Light, centered)
  space-20
  headline       "Enable biometrics"    (type-headline-sm, Light, centered)
  space-8
  detail         "Secure your wallet with your face or
                  fingerprint. Required for sending and
                  viewing your recovery phrase."
                                        (type-detail, LightMuted, centered)

— flex —

[Action stack]
  DuskPrimaryButtonPaletted  "Enable biometrics"  (full-width)
  space-8
  space-8
  DuskSecondaryButtonPaletted  "Not now"  (skip — passcode remains primary auth)

space-24 above safe-area-insets.bottom
```

### Step 5 — Creating

```
[DuskScaffold]

(no top bar — non-dismissible)

— flex — (center)

[RunnerWithDust]  — Rarámuri runner + canyon dust trail
  Lottie animation tinted to palette.Light, centered.
  Intentionally shown for ≥3 seconds (brand moment).

space-32

[StepIndicator — centered, no GlassPanel]
  step label     "Creating wallet"    (type-body, Light)
  detail hint    "Generating keys…"   (type-detail, LightMuted)

— flex —

space-24 above safe-area-insets.bottom
```

### Step 6 — Recovery phrase

See `08-recovery-phrase.md` with `isOnboardingEntry = true` variant.
User must check "I understand" checkbox before proceeding.

### Step 7 — All set

```
[DuskScaffold]

(no top bar — no back from here)

— flex — (center)

icon-32        (Icons.Filled.Check, SuccessText, centered)
space-20
headline       "You're all set"        (type-headline-sm, Light, centered)
space-8
detail         "Your wallet is ready and only you
                control the keys."     (type-detail, LightMuted, centered)

— flex —

[Terms/Privacy]  (type-caption, LightMuted, centered)
  "By tapping the button below, you agree to our"
  "Terms of Service" (Light, underlined, tappable → browser)
  "and"
  "Privacy Policy" (Light, underlined, tappable → browser)

space-16

DuskPrimaryButtonPaletted  "Let's go"  (full-width)

space-24 above safe-area-insets.bottom
```

## 5. INTERACTIONS

| Element              | Gesture | Result                                      |
|----------------------|---------|---------------------------------------------|
| Create wallet        | Tap     | → Step 2 (biometric setup)                  |
| Import existing      | Tap     | → Seed restore flow (existing)              |
| Enable biometrics    | Tap     | System biometric prompt → on success: Step 3 |
| Back (Step 2)        | Tap     | → Step 1                                    |
| Checkbox (Step 4)    | Tap     | Toggle; enables Continue button              |
| Continue (Step 6)    | Tap     | Sets recovery_phrase_viewed → Step 7         |
| Terms of Service     | Tap     | Open browser (GitHub Pages hosted doc)       |
| Privacy Policy       | Tap     | Open browser (GitHub Pages hosted doc)       |
| Let's go (Step 7)    | Tap     | → Balance (01); onboarding complete          |

## 6. MOTION

- Step 1 entry: `MaterializeEffect` on the app symbol
  (`motion-emphasize`).
- Step transitions: `motion-standard` (nav push/pop).
- Step 3 spinner: StepIndicator label uses existing crossfade
  (`motion-fast`).
- Step 5 check icon: scale-from-97% (`motion-emphasize`) — same
  treatment as Send Confirmation success.
- Reduce-motion: all snap to end state.

## 7. HAPTICS

| Trigger                    | Token            |
|----------------------------|------------------|
| Create wallet / Import     | `haptic-tap`     |
| Biometric success          | `haptic-confirm` |
| Wallet created (Step 3→4)  | `haptic-confirm` |
| Let's go (Step 5)          | `haptic-confirm` |
| Checkbox toggle            | `haptic-tap`     |

## 8. COPY

Exact strings; do not rewrite.

### Step 1

- Wordmark: `KUIRA` (KuiraMaterializeFrame, animated)
- Tagline: `Where night protects the day.`
- Primary: `Create wallet`
- Secondary: `Import existing wallet`

### Steps 2 + 3

- Top bar (Step 2): `Enter new passcode`
- Top bar (Step 3): `Confirm passcode`

### Step 4

- Top bar: `Secure your wallet`
- Headline: `Enable biometrics`
- Detail: `Secure your wallet with your face or fingerprint. Required for sending and viewing your recovery phrase.`
- Button: `Enable biometrics`

### Step 5

- Step label: `Creating wallet`
- Detail hint: `Generating keys…`

### Step 6

- See 08-recovery-phrase.md §8

### Step 7

- Headline: `You're all set`
- Detail: `Your wallet is ready and only you control the keys.`
- Terms link: `Terms of Service`
- Privacy link: `Privacy Policy`
- Prefix: `By tapping the button below, you agree to our`
- Button: `Let's go`

## 9. A11Y

- Focus order: sequential through each step's content, top to bottom.
- Step 5 Terms/Privacy links: announced as "Terms of Service, link"
  and "Privacy Policy, link."
- All buttons 48dp minimum.
- Dynamic type scales all text.
- Biometric prompt: system chrome handles its own a11y.
- Step 3 spinner: announce "Creating wallet" on entry.

## 10. VISUAL LOCKED

- Dusk palette only. SuccessText on the Step 5 check icon. No other
  color.
- Step 1 uses a GlassPanel around the symbol+headline for
  star-protection. Steps 2 and 5 do NOT use GlassPanel — icon + text
  sit on the StarField (simple screens, minimal content, stars add
  texture without competing).
- Step 3 reuses StepIndicator in a GlassPanel (same as Send
  Confirmation pending and Dust registration pending).
- No passcode step in v1.0. Biometric is mandatory, not optional.
  There is no "Not now" on the biometric screen — unlike Solflare,
  Kuira requires biometric for seed encryption.
- Terms/Privacy links use Light color + underline for the tappable
  text, LightMuted for the surrounding copy.

## 11. PRODUCT LOCKED

- Onboarding is shown ONCE — after wallet creation, it never appears
  again (unless wallet is wiped from Settings).
- Biometric enrollment is MANDATORY. The user cannot skip it. Seed
  encryption requires a hardware-backed biometric key.
- "Import existing wallet" leads to a seed-phrase restore flow
  (existing code, not redesigned in this spec).
- Step 4 (recovery phrase) sets `recovery_phrase_viewed = true` which
  permanently hides the backup banner on Balance.
- Step 5 Terms/Privacy agreement is implicit — tapping "Let's go"
  constitutes acceptance. No explicit checkbox for Terms (the
  recovery phrase checkbox in Step 4 is the only checkbox).
- Terms and Privacy Policy are hosted on GitHub Pages (per T1-11
  decision). URLs are runtime-configurable.

## 12. NEW COMPONENTS

No new components. The flow reuses:

- `StepIndicator` (Step 3 — wallet creation progress)
- `DuskPrimaryButtonPaletted` / `DuskSecondaryButtonPaletted`
- `GlassPanel` (Steps 1, 3)
- `WordGrid` (Step 4, from 08-recovery-phrase §12)
- App symbol icon (from 10-app-icon.md)

---

End of Onboarding spec. Ship paired dark + light frames for each
step (1–5).
