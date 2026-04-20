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

## AMBIENT STAR BACKGROUND (always present)

Every screen has a subtle star field behind all content. This is the
brand signature — "light in darkness, stars against void." The AI
must render it in every wireframe, not leave a flat black background.

Visual description:
- 25 small white dots scattered randomly across the full screen
- Dot size: 0.5 to 2.3 dp radius (tiny — barely visible individually)
- Dot brightness: 30% to 80% white, varying per dot
- Each dot gently twinkles (sine-wave alpha oscillation, ~1-2 Hz)
- The field is static in position — dots don't move, they only pulse
- Overall effect: a quiet, living night sky behind the content
- In DARK MODE: white dots on Void (0xFF000000)
- In LIGHT MODE: very faint dark dots on off-white (0xFFF7F7F7) —
  much subtler than dark mode, barely perceptible, just enough to
  break the flatness of the white surface

Do NOT make the stars prominent. They are texture, not content. If
the stars compete with text readability, they are too bright. The
content must always read clearly over the star field.

In wireframes: render the star field as a subtle dot pattern behind
all content layers. Even in a low-fidelity wireframe, include a few
faint dots to signal the texture is present.

## TOP BAR (every screen with navigation)

```
Height: 56dp (fixed platform chrome — not a space-* token)
Background: Void
Border-bottom: 1dp LightFaint hairline
Left slot: icon-24 back arrow (if not home) OR icon-24 app glyph (home)
             Success state may replace arrow with "Done" text (type-body, Light)
Center slot: optional title (type-body, Light)
Right slot: 1-2 icon-24 actions (48dp tap each)
             Hero screens may use a text button (e.g. "Review") instead —
             state the deviation reason in §10 VISUAL LOCKED
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

No accent color for hierarchy or decoration. Contrast and weight
carry meaning. One exception: `ErrorText` is reserved for financial
danger signals — the user is about to lose money or has typed an
impossible amount. No green, yellow, blue. Ever.

```
SEMANTIC ROLE             TOKEN             ARGB         NOTE
─────────────────────────────────────────────────────────────────
Primary background        Void              0xFF000000
Elevated surface          VoidSoft          0xFF0A0A0A
Card surface              VoidElevated      0xFF111111
Primary fg / icons        Light             0xFFFFFFFF
Secondary text            LightSoft         0xCCFFFFFF   80%
Tertiary / small label    LightMuted        0x80FFFFFF   50%
Separator / disabled      LightFaint        0x33FFFFFF   20%
Input bg / pressed        LightBarely       0x1AFFFFFF   10%
Star bright               StarBright        0xCCFFFFFF   = LightSoft
Star dim                  StarDim           0x33FFFFFF   = LightFaint
Primary button fill       Confirm           0xFFFFFFFF   = Light
Secondary button fill     ConfirmSurface    0x1AFFFFFF   = LightBarely
Cancel / reject text      RejectText        0x66FFFFFF   = LightMuted
Financial danger          ErrorText         0xFFFF4444   red — see rules below
Positive status           SuccessText       0xFF4CAF50   green — see rules below
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
LightMuted        0x80000000
LightFaint        0x33000000
LightBarely       0x0A000000     4% black (lighter than dark-mode's 10%)
StarBright        0x33000000     stars quieter on light bg
StarDim           0x14000000
Confirm           0xFF000000
ConfirmSurface    0x0A000000
RejectText        0x66000000
ErrorText         0xFFCC0000     darker red on light bg for contrast
SuccessText       0xFF2E7D32     darker green on light bg for contrast
```

### `ErrorText` + `SuccessText` usage rules

`ErrorText` and `SuccessText` are the ONLY color tokens in the palette.
They exist because status signals must be unmistakable — monochrome
dimming is ambiguous (could mean loading, disabled, or low emphasis).

**`ErrorText` (red) applies to:**
- Amount hero number when amount > available balance
- Amount error caption ("Insufficient balance")
- Destructive confirmation highlights (e.g., "WIPE" challenge text)
- Insufficient-fee warnings on review/summary screens
- "Failed" status text in Tx History

**`SuccessText` (green) applies to:**
- Check icon on success heroes (Send Confirmation, Dust registration)
- "Confirmed" status text in Tx History
- Sync-complete indicators

**Where NEITHER applies:**
- Buttons (still `Confirm` / `ConfirmSurface` opposite-pole)
- Input borders (still `LightFaint` → `Light` opacity flip)
- General form validation (wrong address prefix = `LightMuted` caption)
- Decorative elements, badges, or icons outside status contexts
- "Pending" status (uses `LightMuted` — neutral, no color)

**The principle:** color only for status signals. Everything else
uses contrast and weight.

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
type-label-tiny          11   3sp        14sp     W400    LightMuted (50%)
type-headline-sm         18   0          24sp     W300    Light (100%)
type-headline-md         22   0          28sp     W300    Light (100%)
type-numeric-hero        44   −1         48sp     W200    Light (100%)
type-detail              13   0          18sp     W400    LightMuted (50%)
type-body                14   0          20sp     W300    Light (100%)
type-caption             12   0          16sp     W400    LightMuted (50%)
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

## LIST ROWS (settings, tx history, address book, any scannable list)

Any row in a scannable list follows production-wallet sizing — NOT the
48dp accessibility floor. 48dp is the minimum a tap target may ever be;
56dp + 16dp inner padding is where production-wallet lists actually sit
(Phantom, Rainbow, MetaMask) and where Material 3 ListItem / iOS HIG
grouped lists land.

```
Row height           56dp minimum (not 48dp)
Vertical padding     space-16 inner (16dp)
Horizontal padding   space-16 inner (16dp)
Leading icon         icon-24, space-12 gap to label
Trailing chevron     icon-16 (navigational rows only)
Divider              1dp LightFaint hairline between rows
```

Rule: floor the height at 56dp, don't target 48dp. Cramped rows read as
"dev demo"; 56dp reads as "product."

### Label/value emphasis rule

In key-value rows, **labels are context and values are data.** Data
should always be the brightest element. The emphasis flips based on
whether the row is navigational or data-display:

```
Nav rows (readOnly=false):    label Light (100%)   value LightSoft (80%)
Data rows (readOnly=true):    label LightSoft (80%) value Light (100%)
```

The principle: on a nav row, you're scanning WHAT TO TAP (label pops).
On a data row, you're reading THE VALUE (value pops). Never dim
data values to LightMuted — that's for hints and placeholders, not
content the user came here to read.

**Applies to:** Settings rows, tx history rows, address book rows,
recipient picker rows — any vertically-stacked nav/scan list.

**Does NOT apply to:** data-display rows inside hero cards (e.g.,
Balance's TokenRow showing DUST balance — those are display widgets,
not nav primaries, and use their own content-driven heights). Even so,
any *clickable* row must still honor the 48dp accessibility floor —
the carve-out is only on the 56dp target, not the 48dp floor.

### Sectioned lists (Settings, Tx history by day, etc.)

Lists composed of multiple named groups use these rhythms — inherited
from M3 List section spacing and iOS HIG grouped-list "grey gutter":

```
Section header → panel gap      space-12   (label → GlassPanel)
Inter-section gap               space-32   (panel → next section header)
Top-of-list → first header      space-16 to space-32 (space-16 for dense
                                 lists like Settings; space-32 for hero or
                                 form screens like Send — more breathing room)
Bottom-of-list → nav-inset      space-24   (above system nav bar)
```

**Centered-hero layouts** (pending spinners, success heroes, empty
states with a single icon+headline): use `space-48` top spacing.
This pushes the content down so it doesn't feel pinned to the top bar
when there's only one content block on screen.

These are targets for new screens. Existing screens may override if
the override has a stated reason in their `10. VISUAL LOCKED` section.

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

No "error" or "warning" haptic. Failure is signaled visually
(ErrorCard + `haptic-tap`), not through a distinct haptic pattern.

## DATA FORMATTING

```
format-address-short    first 6 chars + … + last 4    (mn_add…f5a2)
format-address-full     complete bech32m string
format-amount-night     up to 6 decimals, trailing zeros trimmed, comma thousands
format-amount-dust      up to 12 decimals, trailing zeros trimmed
format-amount-stars     integer, comma thousands
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

### Core (in `core:designsystem`, available to all modules)

```
DuskScaffold           full-screen shell with ambient star background
DuskPrimaryButton      filled (Confirm bg / Void text), full-width default
                       NOTE: hardcodes MidnightColors (dark mode only).
                       Use DuskPrimaryButtonPaletted for light-mode wireframes.
DuskSecondaryButton    ConfirmSurface bg / RejectText text, full-width
DuskButtonRow          secondary + primary horizontal pair, 2dp gap
DuskBulletLine         bullet list row for feature/explainer lists
MaterializeEffect      star-particle intro animation for hero areas
DuskEffect             ambient star background (inside DuskScaffold)
ToastPill              confirmation pill ("Copied"), bottom safe area, 2s, VoidSoft bg
GlassPanel             content-protection container, opaque tint (contentPanel),
                       1dp LightFaint border, radius-md. Defined in 01-balance §12.
```

### Palette-aware (wireframe-scoped, promote to core in T1-8)

```
DuskPrimaryButtonPaletted   same as DuskPrimaryButton but takes a DuskPalette
                            param for correct rendering in both modes
DuskSecondaryButtonPaletted same
DuskButtonRowPaletted       same
```

### Screen-defined (check individual screen specs §12)

Each screen spec defines its own components in §12 NEW COMPONENTS.
Before inventing a new component, check whether an existing screen
already defines one with the right shape:

- **01-balance §12:** NetworkBadge, BackupBanner, BalanceHero, TokenRow,
  QuickActionCircle, AddressChip
- **02-send §12:** TokenModeCard, AmountHeroInput, RecipientChip
- **03-send-confirmation §12:** StepIndicator, ErrorCard
- **04-dust §12:** DustVortex, DustLifecycleGraph
- **05-settings §12:** SettingsSectionHeader, SettingsRow (reused across
  multiple screens), DangerRow, ConfirmationSheet, NetworkPicker
- **06-tx-history §12:** TxRow, TxTypeBadge
- **07-receive §12:** ActionPill, FullScreenQR
- **08-recovery-phrase §12:** WordGrid
- **09-onboarding-visual-pass §12:** none (audit, not new components)
- **10-app-icon §12:** none (asset deliverable)
- **11-splash §12:** none (uses existing MaterializeEffect)

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

- Introduce color beyond `ErrorText` — no green, yellow, blue. Red
  is reserved for financial danger signals only (see `ErrorText` rules)
- Use shadows (elevation is Void → VoidSoft → VoidElevated)
- Use bold (W700+)
- Show fiat values on balance or history screens. Fiat conversion is
  allowed ONLY on the amount entry screen (Send 2c) as a secondary
  denomination swap (NIGHT ↔ USD). The USD figure is a convenience
  hint, not a valuation claim.
- Show identicons
- Invent new tokens — use only what's defined above

## REFERENCES (for values defined above)

The tokens and sizing rules above are Kuira-specific, but the floors
and conventions they enforce trace to canonical mobile-platform
standards. When an AI agent needs to judge an edge case this prefix
doesn't cover, default to these sources:

- **Material Design 3** — https://m3.material.io
  - Touch target 48dp minimum (Accessibility → Touch targets)
  - ListItem single-line default 56dp height, 16dp inner padding (Components → Lists)
  - Dynamic type scaling (Foundations → Typography)
- **Apple Human Interface Guidelines** — https://developer.apple.com/design/human-interface-guidelines
  - Tap target 44pt × 44pt minimum (Inputs → Layout)
  - Grouped list row rhythm (~60pt) with generous section gutters (Components → Lists and tables)
  - Dynamic Type (Foundations → Typography)
- **Android Material Components (Compose)** — reference implementation
  of the Material 3 list sizing used here:
  https://developer.android.com/jetpack/compose/components/list

Kuira tightens these floors where it matters (LIST ROWS = 56dp, not
48dp) and keeps them where industry consensus is already correct (icon
scale, dynamic type). Do not loosen a Kuira rule by citing a minimum
from these references — the prefix is authoritative; the references
are there to orient, not to override.
