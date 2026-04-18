# Screen — Onboarding visual pass (T1-8 scope, token audit)

## 1. GOAL

Audit the existing onboarding screens (welcome, create wallet,
biometric setup, success) and align them to the Dusk design system
tokens established in `_prefix.md`. NOT a redesign — the IA, copy,
and flow are already shipped. This is a visual consistency sweep.

## 2. SITEMAP POSITION

- `from:` App first launch (no wallet exists)
- `to:` Recovery phrase view (08, post-creation) · Balance (01, after
  onboarding completes)

## 3. STATES

Onboarding is a linear wizard. Each step is its own composable. No
canonical state system applies — the steps ARE the states.

| State           | Applies?  | Notes                                       |
|-----------------|-----------|---------------------------------------------|
| `default`       | ✓         | Each step in the wizard is a "default" state|
| `loading-first` | n/a       |                                             |
| `syncing`       | n/a       |                                             |
| `empty`         | n/a       |                                             |
| `no-results`    | n/a       |                                             |
| `error`         | ✓         | Wallet creation can fail (keygen error)     |
| `offline`       | n/a       | Wallet creation is local                    |
| `pending`       | ✓         | Wallet creation in progress (brief)         |
| `success`       | ✓         | Creation complete — transitions to phrase view |

## 4. LAYOUT

The onboarding flow already uses `MaterializeEffect` and the Dusk
star background. The visual pass ensures:

### Token audit checklist

```
CHECK  RULE                                    CURRENT STATUS
──────────────────────────────────────────────────────────────
[ ]    All text uses type-* tokens             Verify each Text composable
[ ]    All spacing uses space-* tokens         Verify each Spacer/padding
[ ]    All colors use palette tokens           Verify no hardcoded Color()
[ ]    All radii use radius-* tokens           Verify RoundedCornerShape calls
[ ]    All icons use icon-* scale              Verify Modifier.size() calls
[ ]    Buttons use DuskPrimaryButton           Verify no raw Material Button
       (or DuskPrimaryButtonPaletted)
[ ]    Touch targets ≥ 48dp                    Verify all clickable elements
[ ]    GlassPanel on hero content              Verify star-protection on titles
[ ]    Light mode renders correctly             Test with DuskPalette.LightMode
[ ]    StarField uses palette-aware params      Verify color + alpha params
```

### Screens to audit

1. **Welcome** — app logo + "Welcome to Kuira" headline + "Create
   wallet" CTA. Check: headline uses type-headline-md, button uses
   DuskPrimaryButton, star background present.

2. **Create wallet** — biometric enrollment prompt + progress.
   Check: step indicators use type-detail, progress text uses
   type-body.

3. **Biometric setup** — system biometric prompt (no Dusk control)
   + pre/post UI. Check: explanation text uses type-detail in
   LightMuted, CTA uses DuskPrimaryButton.

4. **Success** — "Wallet created" confirmation + MaterializeEffect.
   Check: headline uses type-headline-sm, detail uses type-detail,
   check icon uses icon-32 in Light (no green), transition to
   recovery phrase view (08).

### What to change vs what to leave

- **Change:** any hardcoded `Color()`, `fontSize`, `fontWeight`, or
  `dp` value that should be a design-system token
- **Change:** any `MaterialTheme.colorScheme.*` reference → Dusk
  palette token
- **Leave:** the flow structure, copy strings, and navigation — those
  are shipped and working
- **Leave:** system chrome (biometric dialog, status bar) — not Dusk
  controlled

## 5. INTERACTIONS

No interaction changes. The audit is visual only.

## 6. MOTION

Verify existing `MaterializeEffect` on the welcome/success screens
uses `motion-emphasize` (500ms). No new motion added.

## 7. HAPTICS

Verify wallet-creation success triggers `haptic-confirm`. No new
haptics added.

## 8. COPY

No copy changes. Existing strings are shipped.

## 9. A11Y

Verify:
- All icon-only buttons have content descriptions
- Touch targets ≥ 48dp on all interactive elements
- Focus order reads top-to-bottom
- Dynamic type scales all text

## 10. VISUAL LOCKED

- This is a TOKEN AUDIT, not a redesign. Do not change the IA,
  flow structure, or screen count.
- Every existing screen MUST render correctly in BOTH dark and light
  mode after the audit.
- `ErrorText` is not expected on onboarding screens (wallet creation
  errors are operational, not financial danger).

## 11. PRODUCT LOCKED

- Onboarding flow is shipped and working. The visual pass does NOT
  change the biometric enrollment flow, wallet creation steps, or
  navigation sequence.
- After the visual pass, onboarding should transition seamlessly to
  the recovery phrase view (08) for first-time phrase display.

## 12. NEW COMPONENTS

None. This audit applies existing Dusk components and tokens to
existing screens. If a screen needs a component that doesn't exist
yet, flag it as a new component in this section during implementation.

---

End of Onboarding visual pass spec. No new frames — the deliverable
is the audit checklist completed + code changes that pass the checks.
