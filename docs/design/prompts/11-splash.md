# Screen — Splash animation (T1-7, ≤800ms intro)

## 1. GOAL

Animated splash screen using the Android 12+ Splash Screen API. Shows
the app icon symbol + "KUIRA" wordmark, then transitions to Balance.
Total duration ≤ 800ms. The splash is the first thing the user sees —
it sets the brand tone.

## 2. SITEMAP POSITION

- `from:` App cold start · process restart
- `to:` Balance (01) on existing wallet · Onboarding (09) on first
  launch

## 3. STATES

| State           | Applies?  | Notes                                       |
|-----------------|-----------|---------------------------------------------|
| `default`       | ✓         | The splash animation sequence               |
| `loading-first` | n/a       |                                             |
| `syncing`       | n/a       |                                             |
| `empty`         | n/a       |                                             |
| `no-results`    | n/a       |                                             |
| `error`         | n/a       |                                             |
| `offline`       | n/a       |                                             |
| `pending`       | n/a       |                                             |
| `success`       | n/a       |                                             |

## 4. LAYOUT

### Splash sequence (≤ 800ms total)

```
Phase 1: System splash (0–200ms, system-controlled)
  [Void background]
  [App icon symbol]  centered, using ic_launcher foreground vector
  (This phase is controlled by Android Splash Screen API — we
   provide the icon + bg color, system handles the render.)

Phase 2: Animated transition (200–600ms, custom)
  [MaterializeEffect on the symbol]
    Stars scatter outward from the symbol → converge back → symbol
    solidifies. Uses the existing MaterializeEffect composable.
  [Wordmark fade-in]
    "KUIRA" fades in below the symbol
    type-headline-md (22sp, W300, Light, letter-spacing 3sp)
    alpha 0 → 1 over 200ms, ease-out

Phase 3: Hold + exit (600–800ms)
  Hold the complete composition for ~200ms so the user registers it.
  Then crossfade to Balance (01) or Onboarding (09).
```

### Layout frame (static representation of Phase 2 end-state)

```
[Full-screen Void bg — no StarField during splash]

— vertical center —

[Symbol]     app icon foreground (from 10-app-icon.md)
             rendered at 64dp (larger than launcher for splash emphasis)
             Light (#FFFFFF)

space-20

[Wordmark]   "KUIRA"
             type-headline-md, Light, letter-spacing 3sp, centered

— vertical center —
```

## 5. INTERACTIONS

None. Splash is non-interactive. Taps are ignored. System back is
ignored (system splash behavior).

## 6. MOTION

- Phase 1 → Phase 2: system-to-custom handoff. The Splash Screen API
  supports `setOnExitAnimationListener` — the custom activity uses
  this to start the MaterializeEffect the moment the system splash
  begins its exit.
- MaterializeEffect: `motion-emphasize` (500ms, cubic-bezier(0.2,0,0,1)).
  Stars particle burst → converge → reveal. Same composable used on
  Balance hero entry.
- Wordmark fade: `motion-standard` (250ms, ease-out) starting at the
  MaterializeEffect midpoint (~300ms in).
- Exit crossfade to Balance/Onboarding: `motion-fast` (150ms, linear).
- **Reduce-motion:** Splash Screen API respects OS reduce-motion. When
  active, Phase 1 renders static icon, Phase 2 is skipped (no
  MaterializeEffect, no wordmark fade), Phase 3 immediately crossfades
  to Balance/Onboarding.

## 7. HAPTICS

None. Splash is passive — no user action, no feedback.

## 8. COPY

- Wordmark: `KUIRA` (uppercase, letter-spacing 3sp — matches the
  Balance top-bar wordmark)
- No tagline, no version number, no "loading" text.

## 9. A11Y

- Screen reader: announce `Kuira Wallet, loading` during splash,
  then announce the target screen (Balance or Onboarding) on arrival.
- Reduce-motion: all animation skipped; static icon + immediate
  transition.
- No interactive elements — no touch targets to audit.

## 10. VISUAL LOCKED

- Dusk palette only. Void bg, Light fg. No accent color.
- No StarField during splash — the MaterializeEffect IS the star
  moment. Adding StarField behind the animation would create visual
  noise that competes with the particle burst.
- Symbol renders at 64dp on splash (vs ~24dp on launcher) — the
  larger scale justifies detail that would be invisible at launcher
  sizes. The symbol design (10-app-icon.md) must work at BOTH scales.
- Wordmark uses type-headline-md with 3sp letter-spacing — matches
  the "KUIRA" text in Balance's top bar so the visual language is
  consistent from first frame to home screen.
- Total duration hard-capped at 800ms. Users perceive splash screens
  longer than 1s as broken. 800ms is the sweet spot: long enough to
  register the brand, short enough to feel instant.
- No progress bar, no loading spinner, no percent. The animation
  itself signals "app is starting."

## 11. PRODUCT LOCKED

- Splash Screen API (API 31+) is mandatory. Kuira targets API 35+ so
  this is available unconditionally.
- `setOnExitAnimationListener` is used for the Phase 1→2 handoff.
  This means the custom activity's content is composed UNDER the
  system splash; when the system splash exits, the custom animation
  is already running.
- Routing: splash checks `SeedVault.hasSeed()`. If true → Balance.
  If false → Onboarding. This check happens during Phase 1 (system
  splash); by Phase 2, the target is known.
- Cold-start performance: the splash must NOT perform heavy
  initialization (network, DB migration, key derivation). Those
  happen lazily after the target screen loads. The splash is pure
  presentation — its job is to look good for ≤800ms, not to mask
  loading.

## 12. NEW COMPONENTS

No new runtime components. The splash uses:

- `MaterializeEffect` (existing, from `core:designsystem`)
- App icon symbol (from 10-app-icon.md asset deliverable)
- Splash Screen API system integration (not a Compose component)

**Deliverables:**
- `SplashActivity.kt` (or `installSplashScreen()` in `MainActivity`)
  with `setOnExitAnimationListener` for the Phase 1→2 handoff
- Splash theme in `themes.xml` with `windowSplashScreenBackground`
  = Void and `windowSplashScreenAnimatedIcon` = symbol vector

---

End of Splash animation spec. Deliverable: working splash on cold
start. Ship one recorded video (or GIF) showing the full 800ms
sequence in dark mode.
