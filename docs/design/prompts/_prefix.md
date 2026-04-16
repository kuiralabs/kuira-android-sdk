# Shared prefix — paste this FIRST, then the screen body below it

## ROLE

You are extending the Kuira Wallet Android app. You are NOT redesigning
the brand. The visual language is set by the already-shipped Onboarding
screen. Your job: produce a wireframe for ONE screen that matches that
language exactly.

## REFERENCE SCREEN

File: `feature/onboarding/src/main/kotlin/com/midnight/kuira/feature/onboarding/OnboardingScreen.kt`

Observed template slots (every shipped screen follows this):

```
[ambient star bg: DuskEffect, already inside DuskScaffold]
[sheet, MaterializeEffect intro]
  label      — type-label-tiny
  space-20
  headline   — type-headline-sm OR type-headline-md OR type-numeric-hero
  space-4
  detail     — type-detail
  space-24 to space-48
  CONTENT
  space-48
  actions    — DuskPrimaryButton stacked OR DuskButtonRow
```

## TOKENS

Every token (palette, spacing, type, icon, elevation, motion, haptic,
format, state, copy, a11y) is defined in `docs/design/STANDARDS.md`.

**Use tokens by name. Do NOT invent new tokens.** If something needs a
token that doesn't exist, stop and ask; don't improvise a value.

## MODES

Generate every layout in BOTH modes:
- **Dark** (primary): `Void` bg, `Light` fg
- **Light** (design target): `0xFFF7F7F7` bg, `Void` fg

Ship as paired frames. If a component looks right in one mode but wrong
in the other, the component is wrong — not the mode.

## STATES

The screen body lists which of the states defined in STANDARDS.md §10
apply. Ship one layout frame per **state × mode** combination (so a
screen with 5 states × 2 modes = 10 frames).

## SECTION SCHEMA PER SCREEN

Every screen body MUST contain these 12 sections, in this order, with
these exact headings. If a section does not apply, write `none` — never
omit the heading.

```
 1.  GOAL
 2.  SITEMAP POSITION
 3.  STATES
 4.  LAYOUT
 5.  INTERACTIONS
 6.  MOTION
 7.  HAPTICS
 8.  COPY
 9.  A11Y
10.  VISUAL LOCKED
11.  PRODUCT LOCKED
12.  NEW COMPONENTS
```

- **VISUAL LOCKED** = non-negotiable visual rules (palette, typography,
  spacing). Ignoring these makes the mock wrong.
- **PRODUCT LOCKED** = non-negotiable product-logic rules (e.g., "static
  fee, no dry-run"). The wireframe must reflect them but the rule is
  not a pure visual constraint.

## OUTPUT

- Portrait Android, 412 × 892 dp viewport (Pixel 7 class).
- Paired dark + light frame per state, laid out side by side or as
  separate PNGs.
- Label every spacing decision with its `space-*` token.
- Label every type choice with its `type-*` token.
- Mark 48dp minimum touch target on every interactive element.
- Save each frame as `docs/design/wireframes/<screen>/<mode>-<state>.png`.

## VOICE

Minimalist, intentional, technical. No marketing adjectives. No emoji
in UI strings. No cute error messages. Onboarding copy is the
reference — re-read it before writing new strings.

## DO NOT

- Do not introduce color (red / green / yellow / blue). Contrast and
  weight carry meaning.
- Do not use shadows. Elevation is `Void → VoidSoft → VoidElevated`.
- Do not use bold (W700+). Hierarchy is size + color, not weight.
- Do not invent components that duplicate something in the §14
  existing-component list in STANDARDS.md.
- Do not show fiat values anywhere.
- Do not show identicons.
