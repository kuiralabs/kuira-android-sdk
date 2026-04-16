# Shared prefix — paste this FIRST, then the screen body below it

## ROLE

You are extending the Kuira Wallet Android app. You are NOT redesigning
the brand. The visual language is already set. Your job: produce a
wireframe for ONE screen that matches the existing style exactly.

## VISUAL LANGUAGE (the north star)

Every shipped screen follows this template. New screens MUST match it:

```
[ambient star background — subtle particle field on Void]
[content sheet, enters with star-particle materialize animation]

  LABEL        11sp · letter-spacing 3sp · UPPERCASE · 40% white
  ─── 20dp gap ───
  HEADLINE     18–22sp · FontWeight.W300 (light) · 100% white
  ─── 4dp gap ───
  DETAIL       13sp · line-height 18sp · 40% white
  ─── 24 to 48dp gap ───
  CONTENT      (screen-specific: inputs, lists, cards, data)
  ─── 48dp gap ───
  ACTIONS      full-width primary button (white bg, black text)
               8dp gap
               full-width secondary button (10% white bg, white text)
               — OR horizontal pair: secondary left + primary right —
```

Deviate from this template only with a stated reason.

## PALETTE — DARK MODE (primary)

No accent color. No red, green, yellow, blue. Ever. Contrast and
weight carry meaning.

```
SEMANTIC ROLE             TOKEN             ARGB         NOTE
─────────────────────────────────────────────────────────────────
Primary background        Void              0xFF000000
Elevated surface          VoidSoft          0xFF0A0A0A
Card surface              VoidElevated      0xFF111111
Primary fg / icons        Light             0xFFFFFFFF
Secondary text            LightSoft         0xCCFFFFFF   80%
Tertiary / small label    LightMuted        0x66FFFFFF   40%
Separator / disabled      LightFaint        0x33FFFFFF   20%
Input bg / pressed        LightBarely       0x1AFFFFFF   10%
Star bright               StarBright        0xCCFFFFFF   = LightSoft
Star dim                  StarDim           0x33FFFFFF   = LightFaint
Primary button fill       Confirm           0xFFFFFFFF   = Light
Secondary button fill     ConfirmSurface    0x1AFFFFFF   = LightBarely
Cancel / reject text      RejectText        0x66FFFFFF   = LightMuted
```

## PALETTE — LIGHT MODE (design target)

Same semantic roles, inverted base. Same alpha values.

```
SEMANTIC ROLE             LIGHT VALUE    NOTE
──────────────────────────────────────────────────
Primary background        0xFFF7F7F7     off-white so elevated reads as lifted
Elevated surface          0xFFFFFFFF
Card surface              0xFFFAFAFA
Primary fg / icons        0xFF000000
Secondary text            0xCC000000
Tertiary / small label    0x66000000
Separator / disabled      0x33000000
Input bg / pressed        0x0A000000     4% black (lighter than dark-mode's 10%)
Star bright               0x33000000     stars quieter on light bg
Star dim                  0x14000000
Primary button fill       0xFF000000
Secondary button fill     0x0A000000
Cancel / reject text      0x66000000
```

## SPACING SCALE

```
space-4    4dp    tight grouping, inline margins
space-8    8dp    button gaps, icon-to-text
space-12  12dp    chip inner padding, small radius
space-16  16dp    card padding, SCREEN HORIZONTAL INSET (always)
space-20  20dp    label→headline gap
space-24  24dp    section break
space-32  32dp    major section break
space-48  48dp    gap before action stack
```

## TYPOGRAPHY SCALE

Font: system sans. No serif. NEVER bold (W700+). Hierarchy is size +
opacity, not weight.

```
TOKEN                    sp   LETTER-SP  LINE-HT  WEIGHT  DEFAULT COLOR
──────────────────────────────────────────────────────────────────────────
type-label-tiny          11   3sp        14sp     W400    LightMuted (40%)
type-headline-sm         18   0          24sp     W300    Light (100%)
type-headline-md         22   0          28sp     W300    Light (100%)
type-numeric-hero        44   −1         48sp     W200    Light (100%)
type-detail              13   0          18sp     W400    LightMuted (40%)
type-body                14   0          20sp     W300    Light (100%)
type-caption             12   0          16sp     W400    LightMuted (40%)
type-mono                12   0          16sp     W400    Light (mono font)
type-input               14   0          20sp     W300    Light (100%)
type-input-placeholder   14   0          20sp     W300    LightFaint (20%)
type-button-primary      14   0.5sp      20sp     W500    Void (on Confirm bg)
type-button-secondary    14   0.5sp      20sp     W400    Light (100%)
```

## ICON SCALE

```
icon-16    inline text decorators (chevrons, dots)
icon-20    list row glyphs, status indicators
icon-24    top bar actions, inline button leading-icons
icon-32    hero badges, empty-state accents
icon-48    minimum interactive footprint (icon-only button tap area)
```

Rule: icon-only buttons always have a 48×48dp tap target with the
icon (usually icon-24) centered inside.

## ELEVATION

Three layers. No shadows.

```
Layer 0    Void           app background (every screen default)
Layer 1    VoidSoft       elevated panels, modal sheets, sticky bars
Layer 2    VoidElevated   cards/selected items inside a VoidSoft container
```

Never stack more than 3 levels. Same-level separation uses a 1dp
LightFaint hairline, not elevation.

## MOTION TOKENS

```
motion-fast       150ms   linear              toasts, state-flips
motion-standard   250ms   ease-in-out         screen transitions, sheet open
motion-slow       400ms   ease-out            hero pulses, emphasized reveals
motion-emphasize  500ms   cubic-bezier(0.2,0,0,1)  materialize intros, splash
```

All motion respects OS reduce-motion preference (snap to end state).

## HAPTIC TOKENS

```
haptic-tap       light click     toggles, tabs, copy confirmations
haptic-select    long-press      long-press actions, selection changes
haptic-confirm   confirmation    biometric success, tx submitted
```

No "error" or "warning" haptic. Same principle as no accent color.

## DATA FORMATTING

```
format-address-short    first 6 chars + … + last 4    (mn_add…f5a2)
format-address-full     complete bech32m string
format-amount-night     up to 6 decimals, trailing zeros trimmed, comma thousands
format-amount-dust      up to 12 decimals, trailing zeros trimmed
format-hash-short       first 8 + … + last 6          (abc12345…def678)
format-hash-full        complete hex
format-time-relative    Just now / 12s ago / 5m ago / 3h ago / 2d ago / Mar 12
format-time-absolute    ISO-8601 (2026-04-15T03:27:11Z)
```

Any field using format-*-short MUST be tappable to reveal the full
value + copy with haptic-tap and a "Copied" toast.

## STATE SYSTEM

Every screen lists which of these states apply. Write "n/a" for
those that don't.

```
default          primary loaded state (happy path)
loading-first    first launch, no cache, skeleton visible
syncing          cached data visible, refresh in progress
empty            no data ever existed
no-results       data exists but filter returned zero
error            runtime failure, cached data may still show
offline          network unavailable, cached data visible
pending          optimistic UI while action is committing
success          post-action confirmation card
```

Each listed state is rendered as its own frame in BOTH modes.

## COPY PATTERNS

```
Error       "Something went wrong." + 1-line context + "Retry" button
Empty       label "NO <NOUN>" / headline "verb your first noun" / 1 line
Toast       ≤ 1 line · 2s on screen · bottom safe area · VoidSoft bg
Copied      "Copied" (not "Copied to clipboard")
Destructive second confirmation always typed (e.g. "type WIPE")
```

Voice: minimalist, no marketing adjectives, no emoji in UI, never
cute about errors.

## ACCESSIBILITY

```
Content descriptions   required on every icon-only button, chart, image
CD format              imperative verb + object ("Send transaction")
Focus order            top-to-bottom, left-to-right reading order
Touch target           48dp minimum everywhere
Dynamic type           all type-* tokens scale with user font preference
Reduce motion          motion-* tokens snap to end state when OS flag set
Screen-record block    FLAG_SECURE on recovery phrase view only
```

## EXISTING COMPONENTS (do not duplicate)

```
DuskScaffold           full-screen shell with ambient star background
DuskPrimaryButton      filled (white bg / black text), full-width default
DuskSecondaryButton    10% white bg / white text, full-width default
DuskButtonRow          secondary + primary horizontal pair, 2dp gap
DuskBulletLine         bullet list row for feature/explainer lists
MaterializeEffect      star-particle intro animation for hero areas
DuskEffect             ambient star background (inside DuskScaffold)
```

## MODES

Generate every layout in BOTH modes side by side:
- Dark (primary): Void bg, Light fg
- Light (design target): 0xFFF7F7F7 bg, 0xFF000000 fg

If a component looks right in one mode but wrong in the other, the
component is wrong — not the mode.

## STATES × MODES

The screen body lists which states apply. Ship one layout frame per
state × mode combination.

## SECTION SCHEMA PER SCREEN

Every screen body MUST contain these 12 sections in this exact order
with these exact headings. If a section does not apply, write "none".
Never omit a heading.

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

VISUAL LOCKED = non-negotiable visual rules. Ignoring makes the mock wrong.
PRODUCT LOCKED = non-negotiable product logic. Wireframe must reflect it.

## OUTPUT

- Portrait Android, 412 × 892 dp viewport
- Paired dark + light frame per state (side by side or separate PNGs)
- Label every spacing with its space-* token name
- Label every type choice with its type-* token name
- Mark 48dp minimum touch-target on all interactive elements

## DO NOT

- Introduce color (red / green / yellow / blue)
- Use shadows (elevation is Void → VoidSoft → VoidElevated)
- Use bold (W700+)
- Show fiat values
- Show identicons
- Invent new tokens — use only what's defined above
