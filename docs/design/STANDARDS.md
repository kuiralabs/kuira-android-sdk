# Design standards — Kuira Wallet

Single source of truth for every token and rule referenced by the
screen prompts under `prompts/`. Screen prompts reference tokens **by
name**. If a token isn't here, don't invent one — extend this file.

---

## 1. Palette — DARK MODE (shipped)

Defined in `core/designsystem/src/main/kotlin/com/midnight/kuira/core/designsystem/theme/MidnightColors.kt`.

| Semantic role          | Token            | ARGB       |
|------------------------|------------------|------------|
| Primary background     | `Void`           | 0xFF000000 |
| Elevated surface       | `VoidSoft`       | 0xFF0A0A0A |
| Card surface           | `VoidElevated`   | 0xFF111111 |
| Primary fg / icons     | `Light`          | 0xFFFFFFFF |
| Secondary text (80%)   | `LightSoft`      | 0xCCFFFFFF |
| Tertiary / label (40%) | `LightMuted`     | 0x66FFFFFF |
| Separator / disabled (20%) | `LightFaint` | 0x33FFFFFF |
| Input bg / pressed (10%) | `LightBarely`  | 0x1AFFFFFF |
| Star bright            | `StarBright`     | 0xCCFFFFFF |
| Star dim               | `StarDim`        | 0x33FFFFFF |
| Primary button fill    | `Confirm`        | 0xFFFFFFFF |
| Secondary button fill  | `ConfirmSurface` | 0x1AFFFFFF |
| Cancel / reject text   | `RejectText`     | 0x66FFFFFF |

**Rule:** no accent color. No red, green, yellow, blue. Ever. Contrast
and weight carry meaning.

## 2. Palette — LIGHT MODE (design target; not yet in code)

Same semantic roles, inverted base. Same α values.

| Semantic role          | Light-mode value |
|------------------------|------------------|
| Primary background     | 0xFFF7F7F7       |
| Elevated surface       | 0xFFFFFFFF       |
| Card surface           | 0xFFFAFAFA       |
| Primary fg / icons     | 0xFF000000       |
| Secondary text         | 0xCC000000       |
| Tertiary / label       | 0x66000000       |
| Separator / disabled   | 0x33000000       |
| Input bg / pressed     | 0x0A000000       |
| Star bright            | 0x33000000       |
| Star dim               | 0x14000000       |
| Primary button fill    | 0xFF000000       |
| Secondary button fill  | 0x0A000000       |
| Cancel / reject text   | 0x66000000       |

Notes:
- Primary bg is not pure white so elevated surface reads as lifted.
- Ambient stars deliberately quieter on light bg — brand texture, not
  primary channel.
- Input bg is 4% black (not 10%) because white tolerates less dimming.

## 3. Spacing scale

| Token       | dp | Use                                            |
|-------------|----|------------------------------------------------|
| `space-4`   | 4  | Tight grouping, inline margins                 |
| `space-8`   | 8  | Button gaps, icon-to-text                      |
| `space-12`  | 12 | Chip inner padding; small radius              |
| `space-16`  | 16 | Card inner padding; screen horizontal inset   |
| `space-20`  | 20 | label→headline gap                             |
| `space-24`  | 24 | Section break                                  |
| `space-32`  | 32 | Major section break                            |
| `space-48`  | 48 | Gap before action stack                        |

**Screen horizontal inset is always `space-16`.** Vertical inset
top = top-bar height (56dp) + safe-area-insets.top. Vertical inset
bottom = `space-24` + safe-area-insets.bottom.

## 4. Typography scale

Font family: system sans (`Inter` / SF-like). No serif anywhere.

| Token                  | sp | Letter-sp | Line-ht | Weight | Color default |
|------------------------|----|-----------|---------|--------|---------------|
| `type-label-tiny`      | 11 | 3sp       | 14sp    | W400   | `LightMuted`  |
| `type-headline-sm`     | 18 | 0         | 24sp    | W300   | `Light`       |
| `type-headline-md`     | 22 | 0         | 28sp    | W300   | `Light`       |
| `type-numeric-hero`    | 44 | −1        | 48sp    | W200   | `Light`       |
| `type-detail`          | 13 | 0         | 18sp    | W400   | `LightMuted`  |
| `type-body`            | 14 | 0         | 20sp    | W300   | `Light`       |
| `type-caption`         | 12 | 0         | 16sp    | W400   | `LightMuted`  |
| `type-mono`            | 12 | 0         | 16sp    | W400   | `Light` (mono)|
| `type-input`           | 14 | 0         | 20sp    | W300   | `Light`       |
| `type-input-placeholder`| 14| 0         | 20sp    | W300   | `LightFaint`  |
| `type-button-primary`  | 14 | 0.5sp     | 20sp    | W500   | `Void` (on `Confirm`) |
| `type-button-secondary`| 14 | 0.5sp     | 20sp    | W400   | `Light`       |

**Rules:**
- NEVER bold (W700+). Hierarchy is size + color, not weight.
- Numeric hero uses W200 for extra lightness; all other headlines use W300.
- Tiny labels are the signature: uppercase, letter-spaced, `LightMuted`.

## 5. Icon scale

| Token      | dp | Use                                         |
|------------|----|---------------------------------------------|
| `icon-16`  | 16 | Inline text decorators (chevron, dot)       |
| `icon-20`  | 20 | List row glyphs, status dots                |
| `icon-24`  | 24 | Top bar actions, inline button leading-icon |
| `icon-32`  | 32 | Hero badges, empty-state accents            |
| `icon-48`  | 48 | Minimum interactive footprint (icon-only button tap area) |

**Rule:** an icon-only button always has a 48×48 tap target with the
icon centered. The icon itself is `icon-24` inside that 48dp box.

## 6. Elevation

Three layers, no shadows.

| Layer | Token          | When to use                                              |
|-------|----------------|----------------------------------------------------------|
| 0     | `Void`         | App background (the default for every screen)            |
| 1     | `VoidSoft`     | Elevated panels, modal sheets, sticky bars              |
| 2     | `VoidElevated` | Cards / selected items inside a `VoidSoft` container    |

**Rule:** never stack more than 3 elevation levels in one composition.
Separation at the same level uses 1dp `LightFaint` hairline, not
elevation.

## 7. Motion tokens

| Token              | ms  | Easing                      | Use                                   |
|--------------------|-----|-----------------------------|---------------------------------------|
| `motion-fast`      | 150 | linear                      | Toasts, state-flips, haptic feedback  |
| `motion-standard`  | 250 | ease-in-out                 | Screen transitions, sheet open/close  |
| `motion-slow`      | 400 | ease-out                    | Hero pulses, emphasized reveals       |
| `motion-emphasize` | 500 | cubic-bezier(0.2,0,0,1)     | MaterializeEffect intro, splash       |

**Rule:** every motion must respect the OS reduce-motion preference.
When reduce-motion is on, snap to end state (0ms).

## 8. Haptic tokens

Android `HapticFeedbackConstants`.

| Token            | HapticFeedbackConstant       | Use                          |
|------------------|------------------------------|------------------------------|
| `haptic-tap`     | `KEYBOARD_TAP`               | Toggles, tabs, pill toasts   |
| `haptic-select` | `LONG_PRESS`                 | Long-press → context menu    |
| `haptic-confirm` | `CONFIRM`                    | Biometric success, tx submitted |

**Rule:** no "error" or "warning" haptic pattern. Same principle as
no accent color — errors are signaled by copy + weight, not modality.

## 9. Data formatting

| Token                   | Format                                                   |
|-------------------------|----------------------------------------------------------|
| `format-address-short`  | first 6 chars + `…` + last 4 (e.g. `mn_add…f5a2`)       |
| `format-address-full`   | full bech32m string                                      |
| `format-amount-night`   | up to 6 decimals, trailing zeros trimmed, `,` thousands  |
| `format-amount-dust`    | up to 12 decimals, trailing zeros trimmed                |
| `format-amount-specks`  | integer, `,` thousands                                   |
| `format-hash-short`     | first 8 + `…` + last 6 (e.g. `abc12345…def678`)          |
| `format-hash-full`      | full hex                                                 |
| `format-time-relative`  | `Just now` / `12s ago` / `5m ago` / `3h ago` / `2d ago` / `Mar 12` (≥7d) |
| `format-time-absolute`  | ISO-8601 `2026-04-15T03:27:11Z` (tx detail only)         |

**Rule on truncation-with-reveal:** any field using `format-*-short`
must be tappable to reveal the full value + copy to clipboard with
`haptic-tap` and a "Copied" toast.

## 10. State system

Every screen MUST state which of these apply. Write `n/a` if none.

| State           | Meaning                                                      |
|-----------------|--------------------------------------------------------------|
| `default`       | Primary loaded state (happy path)                            |
| `loading-first` | First launch; no cache yet; skeleton visible                 |
| `syncing`       | Cached data visible; refresh in progress                     |
| `empty`         | No data ever existed (e.g., zero transactions)               |
| `no-results`    | Data exists but filter/search returned zero                  |
| `error`         | Runtime failure; cached data may still be visible            |
| `offline`       | Network unavailable; cached data visible                     |
| `pending`       | Optimistic UI while an action is committing                  |
| `success`       | Post-action confirmation card                                |

Each listed state must be rendered as its own frame in both modes.

## 11. Copy patterns

| Pattern         | Shape                                                                 |
|-----------------|-----------------------------------------------------------------------|
| Error           | "Something went wrong." + 1-line context + `Retry` text button. No stack traces. No "Oops". |
| Empty           | label `NO <NOUN>` / headline `<verb> your first <noun>` / 1-line detail / optional action |
| Toast pill      | ≤ 1 line · 2s on screen · bottom safe-area inset · `VoidSoft` bg · `type-caption` |
| Confirm toast   | "Copied" (NOT "Copied to clipboard")                                  |
| Destructive confirm | 2nd sheet asks typed confirmation (e.g., `type WIPE`) — never 1-tap |

Voice: minimalist, intentional, no marketing adjectives, no emoji in
UI strings, never cute about errors.

## 12. Accessibility

| Area                | Rule                                                               |
|---------------------|--------------------------------------------------------------------|
| Content descriptions| Required on every icon-only button, chart, image                   |
| CD format           | Imperative verb + object. `"Send transaction"`, not `"Send button"` |
| Focus order         | Top-to-bottom, left-to-right reading order. System back first.     |
| Touch target        | 48dp minimum (icon-only buttons expand to 48×48 around `icon-24`)  |
| Dynamic type        | Every `type-*` token scales with user font preference; test @150% and @200% |
| Contrast            | WCAG AA minimum. Both modes verified against core combinations:     |
| ↳ Light on Void     | 21:1 (AAA)                                                         |
| ↳ LightMuted (40%) on Void | 7.4:1 (AAA)                                                 |
| ↳ LightFaint (20%) on Void | 3.7:1 (AA large only — never use for body)                  |
| ↳ Void on F7F7F7 (light)  | 19.1:1 (AAA)                                                |
| ↳ 40% black on F7F7F7    | 4.9:1 (AA)                                                   |
| Reduce motion       | `motion-*` tokens snap to end state when OS flag is set            |
| Screen-recording block | `FLAG_SECURE` required on Recovery phrase view                 |

## 13. Platform chrome

| Surface           | Rule                                                             |
|-------------------|------------------------------------------------------------------|
| Status bar        | Transparent. Icons = `Light` in dark mode, `Void` in light mode. |
| Top bar height    | 56dp                                                             |
| Top bar bg        | `Void` · border-bottom 1dp `LightFaint`                          |
| Bottom nav        | **none** — this app does not have a bottom nav                   |
| FAB               | **none** — primary actions live in `DuskButtonRow` at the bottom |
| Soft keyboard     | `adjustResize`. Content scrolls so the focused field sits above the keyboard with `space-24` breathing room. |
| System back       | Always wired. Never intercepted except on multi-step destructive flows. |

## 14. Component inventory

### Existing (in `core:designsystem`)

| Component              | File                                                          |
|------------------------|---------------------------------------------------------------|
| `DuskScaffold`         | `component/DuskScaffold.kt`                                   |
| `DuskPrimaryButton`    | `component/DuskButton.kt`                                     |
| `DuskSecondaryButton`  | `component/DuskButton.kt`                                     |
| `DuskButtonRow`        | `component/DuskButton.kt`                                     |
| `DuskBulletLine`       | `component/DuskBulletLine.kt`                                 |
| `MaterializeEffect`    | `effect/MaterializeEffect.kt`                                 |
| `DuskEffect`           | `effect/DuskEffect.kt`                                        |

### To build (in priority order)

| Component               | Used by                                  | Priority |
|-------------------------|------------------------------------------|----------|
| `NetworkBadge`          | Balance · Settings · Receive header · top bar (T1-16) | P0 |
| `BackupBanner`          | Balance (conditional)                    | P0       |
| `BalanceHero`           | Balance                                  | P0       |
| `AddressChip`           | Balance · Send · Receive · Tx Detail     | P0       |
| `AddressField`          | Send                                     | P0       |
| `AmountField`           | Send                                     | P0       |
| `FeeEstimateStrip`      | Send                                     | P0       |
| `ProvingModeBadge`      | Send · Send Confirmation                 | P0       |
| `ModeSegmentedControl`  | Send                                     | P0       |
| `ConfirmRow`            | Send Confirmation · Tx Detail            | P0       |
| `TotalStrip`            | Send Confirmation                        | P0       |
| `SuccessCard`           | Send Confirmation (post-send)            | P1       |
| `TxRow`                 | Tx History                               | P0       |
| `TxTypeBadge`           | Tx History · Tx Detail                   | P0       |
| `TxStatusBadge`         | Tx History · Tx Detail                   | P0       |
| `SettingsSectionHeader` | Settings                                 | P1       |
| `DangerRow`             | Settings · Wipe flow                     | P1       |
| `NetworkPicker`         | Settings (bottom sheet)                  | P1       |
| `MnemonicGrid`          | Recovery phrase view                     | P0       |
| `WarningBlock`          | Recovery phrase view · Tx failure        | P0       |
| `QRCodeCanvas`          | Receive                                  | P1       |
| `FullScreenQrSheet`     | Receive                                  | P1       |
| `ToastPill`             | App-wide confirmations                   | P0       |

## 15. File & wireframe deliverable rules

- Wireframe images commit under `docs/design/wireframes/<screen>/<mode>-<state>.png`
  e.g. `wireframes/balance/dark-default.png`, `wireframes/balance/light-syncing.png`.
- One PNG per state × mode. Annotation is welcome but not required on
  every frame — the screen prompt is the spec; the PNG is the visual.
- Each `prompts/NN-<screen>.md` stays under ~400 lines. Going longer
  means the spec is doing work that belongs in STANDARDS.md.

## 16. Follow-up code gate (blocks 8B.3 light-mode work)

`core/designsystem/.../theme/Theme.kt` still uses `Purple40 / Pink40`
Material defaults for `LightColorScheme`. Before a light-mode wireframe
can be implemented:

1. Introduce semantic aliases (`Surface`, `OnSurface`, `SurfaceElevated`,
   `OnSurfaceSoft`, `OnSurfaceMuted`, `OnSurfaceFaint`, `OnSurfaceBarely`,
   `Accent`, `OnAccent`, `AmbientStarBright`, `AmbientStarDim`).
2. Define `DuskLight` object populated with the light-mode values from
   §2 of this doc.
3. Rewrite `LightColorScheme` against the aliases; delete `Purple40 /
   Pink40` references.
4. Migrate Onboarding's direct `MidnightColors` references.

~4-6h. Can run in parallel with the design sprint.
