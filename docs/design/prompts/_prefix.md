# Shared prefix — paste this FIRST, then the screen body below it

## ROLE

You are designing a MOBILE PHONE screen for the Kuira Wallet Android
app. Portrait only, single-hand reachable, 412 × 892 dp viewport
(Pixel 7 class). This is NOT a tablet, desktop, or web app.

You are NOT redesigning the brand. The visual language is already set.
Your job: produce a wireframe for ONE screen that matches the existing
style exactly.

## VISUAL LANGUAGE (the north star)

Every shipped screen follows this template. New screens MUST match it:

```
[DuskScaffold — full-screen shell, ambient star background on Void]
[main content area fades in via MaterializeEffect star-particle animation]

  LABEL        type-label-tiny
  ─── space-20 ───
  HEADLINE     type-headline-sm OR type-headline-md OR type-numeric-hero
  ─── space-4 ───
  DETAIL       type-detail
  ─── space-24 to space-48 ───
  CONTENT      (screen-specific: inputs, lists, cards, data)
  ─── space-48 ───
  ACTIONS      DuskPrimaryButton (full-width)
               space-8
               DuskSecondaryButton (full-width)
               — OR DuskButtonRow: secondary left + primary right —
```

Deviate from this template only with a stated reason.

## TOP BAR (every screen with navigation)

```
Height: 56dp (fixed platform chrome — not a space-* token)
Background: Void
Border-bottom: 1dp LightFaint hairline
Left slot: icon-24 back arrow (if not home) OR icon-24 app glyph (home)
Center slot: optional title (type-body, Light)
Right slot: 1-2 icon-24 actions (48dp tap each)
```

Home screen (Balance) has no back arrow. All other screens do.

## SHAPES

```
radius-sm     8dp     small cards, badges
radius-md    12dp     input fields, chips, banners, most components
radius-lg    20dp     full-width buttons, FAB-like elements
radius-full  9999dp   pills (NetworkBadge, ToastPill)
```

No border-radius token above radius-lg except radius-full.
No rounded-square or squircle — only circular arcs.

## SAFE AREAS

```
Top:    status bar height (system, not tokenized) + top bar (56dp, not tokenized)
Bottom: space-24 content padding (tokenized) + system nav inset (not tokenized)
Left/Right: space-16 content inset (tokenized, always, on all screens)
```

Label system chrome (status bar, nav bar, top bar height) as fixed
values, NOT as space-* tokens. Content padding between chrome and
content (space-24, space-16) IS tokenized.

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

Use the SAME token names as dark mode. The agent labels elements with
the token name (e.g., "bg = Void") regardless of mode — only the
resolved value changes.

```
TOKEN             LIGHT VALUE    NOTE
──────────────────────────────────────────────────
Void              0xFFF7F7F7     off-white so elevated reads as lifted
VoidSoft          0xFFFFFFFF
VoidElevated      0xFFFAFAFA
Light             0xFF000000
LightSoft         0xCC000000
LightMuted        0x66000000
LightFaint        0x33000000
LightBarely       0x0A000000     4% black (lighter than dark-mode's 10%)
StarBright        0x33000000     stars quieter on light bg
StarDim           0x14000000
Confirm           0xFF000000
ConfirmSurface    0x0A000000
RejectText        0x66000000
```

Button text in light mode: primary button fill (`Confirm`) is dark
(0xFF000000), so button text uses `Void` (0xFFF7F7F7 — near-white).
This is the inverse of dark mode where fill is white and text is black.
The rule: button text is always the opposite-pole token from the fill.

## SPACING SCALE

```
space-4    4dp    tight grouping, inline margins
space-8    8dp    button gaps, icon-to-text
space-12  12dp    chip inner padding
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
```

These are GLYPH sizes, not tap targets. An icon-only button renders
an icon-24 glyph centered inside a 48×48dp tap target (the tap
target is an accessibility rule, not an icon token).

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
format-amount-specks    integer, comma thousands
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
ToastPill              confirmation pill ("Copied"), bottom safe area, 2s, VoidSoft bg
```

## MODES × STATES

Generate every layout in BOTH modes:
- Dark (primary): Void bg, Light fg
- Light (design target): 0xFFF7F7F7 bg, 0xFF000000 fg

The screen body lists which states apply. Ship one layout frame per
state × mode combination (e.g., 5 states × 2 modes = 10 frames).
Place dark and light side by side for each state.

If a component looks right in one mode but wrong in the other, the
component is wrong — not the mode.

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
- Label content spacing with its space-* token name (space-8, space-24, etc.)
- Label type choices with its type-* token name (type-detail, type-headline-sm, etc.)
- Label radii with its radius-* token name (radius-md, radius-full, etc.)
- Fixed chrome dimensions (top bar 56dp, system status bar, nav bar inset) are
  labeled as-is, NOT as space-* tokens
- Component-internal spacing below space-4 (e.g., DuskButtonRow 2dp gap) is
  not tokenized — label as component default
- Mark 48dp minimum touch-target on all interactive elements

## DO NOT

- Introduce color (red / green / yellow / blue)
- Use shadows (elevation is Void → VoidSoft → VoidElevated)
- Use bold (W700+)
- Show fiat values
- Show identicons
- Invent new tokens — use only what's defined above
