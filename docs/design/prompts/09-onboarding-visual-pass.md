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
| `success`       | n/a       | Handled by "All set" screen (step 5)        |

## 4. LAYOUT

### v1.0 onboarding flow

```
Step 1: WELCOME
Step 2: BIOMETRIC SETUP
Step 3: CREATING (spinner, ~2s)
Step 4: RECOVERY PHRASE (spec 08)
Step 5: ALL SET (new — Terms/Privacy + completion)
→ Balance
```

### Step 1 — Welcome

```
[DuskScaffold] — ambient StarField

— flex — (center content vertically)

[GlassPanel — hero, contentPadding = 24dp]
  [App symbol]   icon from 10-app-icon.md, rendered at 64dp, Light
  space-20
  headline       "KUIRA"           (type-headline-md, Light, centered,
                                    letter-spacing 3sp)
  space-8
  detail         "Your private wallet on Midnight"
                                   (type-detail, LightMuted, centered)

— flex —

[Action stack]
  DuskPrimaryButtonPaletted  "Create wallet"          (full-width)
  space-8
  DuskSecondaryButtonPaletted "Import existing wallet" (full-width)

space-24 above safe-area-insets.bottom
```

### Step 2 — Biometric setup

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
  (no "Not now" — biometric is required in Kuira, not optional)

space-24 above safe-area-insets.bottom
```

### Step 3 — Creating

```
[DuskScaffold]

(no top bar — non-dismissible)

— flex — (center)

[GlassPanel — hero, contentPadding = 24dp]
  StepIndicator
    step label     "Creating wallet"    (type-body, Light, centered)
    detail hint    "Generating keys…"   (type-detail, LightMuted, centered)

— flex —

space-24 above safe-area-insets.bottom
```

### Step 4 — Recovery phrase

See `08-recovery-phrase.md` with `isOnboardingEntry = true` variant.
User must check "I understand" checkbox before proceeding.

### Step 5 — All set (NEW)

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
| Continue (Step 4)    | Tap     | Sets recovery_phrase_viewed → Step 5         |
| Terms of Service     | Tap     | Open browser (GitHub Pages hosted doc)       |
| Privacy Policy       | Tap     | Open browser (GitHub Pages hosted doc)       |
| Let's go (Step 5)    | Tap     | → Balance (01); onboarding complete          |

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

- Headline: `KUIRA`
- Detail: `Your private wallet on Midnight`
- Primary: `Create wallet`
- Secondary: `Import existing wallet`

### Step 2

- Top bar: `Secure your wallet`
- Headline: `Enable biometrics`
- Detail: `Secure your wallet with your face or fingerprint. Required for sending and viewing your recovery phrase.`
- Button: `Enable biometrics`

### Step 3

- Step label: `Creating wallet`
- Detail hint: `Generating keys…`

### Step 4

- See 08-recovery-phrase.md §8

### Step 5

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
