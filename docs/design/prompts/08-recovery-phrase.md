# Screen — Recovery phrase view (T1-2, 24-word grid + FLAG_SECURE)

## 1. GOAL

Display the user's 24-word BIP-39 recovery phrase for backup. Gated
behind biometric. `FLAG_SECURE` prevents screenshots and screen
recording. Accessible from onboarding post-creation AND from
Settings at any time.

## 2. SITEMAP POSITION

- `from:` Settings ("View recovery phrase" row, biometric-gated) ·
  Balance (backup banner, biometric-gated) ·
  Onboarding post-creation flow (first display)
- `to:` Settings (back arrow) · Balance (if entered from onboarding,
  after "I understand" confirmation)

## 3. STATES

| State           | Applies?  | Notes                                       |
|-----------------|-----------|---------------------------------------------|
| `default`       | ✓         | 24 words visible in numbered grid           |
| `loading-first` | n/a       | Phrase is local; loads instantly             |
| `syncing`       | n/a       |                                             |
| `empty`         | n/a       |                                             |
| `no-results`    | n/a       |                                             |
| `error`         | n/a       | Biometric failure stays on the calling screen (Settings/Onboarding), not here |
| `offline`       | n/a       |                                             |
| `pending`       | n/a       |                                             |
| `success`       | n/a       |                                             |

**Variants:**

- `default / onboarding-entry` — entered from onboarding post-creation.
  "I understand" checkbox + "Continue" button replace the back arrow
  dismiss. The user cannot proceed to Balance without checking the box.

## 4. LAYOUT

### Layout — `default` (from Settings)

```
[DuskScaffold] — ambient StarField
[FLAG_SECURE active — screenshots + screen recording blocked]

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]  (Icons.AutoMirrored.Filled.ArrowBack)
  "Recovery phrase"  (type-body, Light)

space-16 (screen horizontal inset throughout)

space-32 top spacing

[Warning banner]  — full-width, bg LightBarely, radius-md
  icon-20 (Icons.Filled.VisibilityOff, LightMuted)
  space-8
  "Anyone with these words can access your funds.
   Never share them."     (type-detail, Light)

space-32

[GlassPanel — word grid, contentPadding = 16dp]
  [3-column grid — 8 rows × 3 columns = 24 words]
    Each cell:
      Row {
        number    (type-caption, LightMuted, right-aligned, 20dp width)
        space-8
        word      (type-body, Light)
      }
    Cell height: content-driven, minimum 32dp
    Column spacing: space-16
    Row spacing: space-8

space-32

[Action row]  — horizontally centered
  [ActionPill: Copy all]
    icon-20 (Icons.Filled.ContentCopy) + "Copy" label
    tap: copy all 24 words to clipboard · haptic-tap · warning toast

space-24 above safe-area-insets.bottom
```

### Variant — `default / onboarding-entry`

Same as `default`, plus:

- Top bar: NO back arrow (user must complete the confirmation flow).
  Title: `Your recovery phrase`.
- Below the action row:

```
space-32

[Confirmation checkbox]
  Row {
    Checkbox (unchecked by default, LightFaint border, Light fill when checked)
    space-12
    "I have written down my recovery phrase and
     understand I am responsible for keeping it safe."
                                (type-detail, Light, wraps to 2 lines)
  }

space-16

DuskPrimaryButtonPaletted  "Continue"  (full-width, DISABLED until
                                        checkbox is checked)
```

## 5. INTERACTIONS

| Element              | Gesture | Result                                            |
|----------------------|---------|---------------------------------------------------|
| Back arrow (Settings)| Tap     | Pop to Settings                                   |
| Copy all pill        | Tap     | Copy "1. word1 2. word2 … 24. word24" to clipboard · `haptic-tap` · warning toast: `Copied — keep it safe` |
| Checkbox (onboarding)| Tap     | Toggle; enables/disables Continue button          |
| Continue (onboarding)| Tap     | Sets `recovery_phrase_viewed = true` · navigate to Balance · banner disappears |

## 6. MOTION

- Entry: `MaterializeEffect` on the warning banner (`motion-emphasize`).
- Word grid appears: `motion-standard` alpha fade (all 24 words
  together, not staggered — staggered reveal implies the words have
  a dramatic order, which is misleading).
- Checkbox toggle: `motion-fast`.
- Reduce-motion: all snap to end state.

## 7. HAPTICS

| Trigger               | Token           |
|-----------------------|-----------------|
| Copy all              | `haptic-tap`    |
| Checkbox toggle       | `haptic-tap`    |
| Continue (onboarding) | `haptic-confirm`|

## 8. COPY

Exact strings; do not rewrite.

### Labels

- Top bar title (Settings entry): `Recovery phrase`
- Top bar title (onboarding entry): `Your recovery phrase`
- Warning banner: `Anyone with these words can access your funds. Never share them.`
- Copy action: `Copy`
- Checkbox label: `I have written down my recovery phrase and understand I am responsible for keeping it safe.`
- Continue button: `Continue`

### Toasts

- After copy: `Copied — keep it safe` (not just "Copied" — the extra
  warning is intentional on this screen only)

## 9. A11Y

- Focus order: back arrow (if present) → warning banner → word grid
  (announced as a sequence: "Word 1: <word>, Word 2: <word>, …") →
  Copy pill → Checkbox (if onboarding) → Continue (if onboarding).
- Content descriptions:
  - back arrow: `Back to settings`
  - warning banner: `Warning. Anyone with these words can access your funds.`
  - word grid: each word announced as `Word <n>: <word>`
  - Copy: `Copy all 24 words to clipboard`
  - Checkbox: `I understand checkbox, <checked/unchecked>`
- Touch targets: grid cells are not independently tappable (the grid
  is a read-only display). Copy pill is 48dp minimum. Checkbox row
  is 48dp minimum.
- Dynamic type: word grid scales. At max font, the 3-column layout
  may need to reduce to 2 columns to prevent overflow — test at max.
- Screen reader: announce all 24 words in sequence on grid focus,
  not one cell at a time (the phrase is meaningful as a whole).
- FLAG_SECURE blocks screen recording, screen mirroring, and
  screenshot — this is a system-level enforcement, not a UI overlay.

## 10. VISUAL LOCKED

- Dusk palette only. `ErrorText` is not used on this screen.
- `FLAG_SECURE` is non-negotiable — set on the Activity window the
  moment this screen is composed, cleared when the screen is left.
- Warning banner uses `LightBarely` bg (same as BackupBanner on
  Balance). NOT a yellow/red warning banner — Dusk palette rules
  apply. The icon + copy carry the "danger" signal, not color.
- Word grid sits inside a single `GlassPanel` for star-protection.
  Words are critical content — legibility over texture.
- Word numbers use `type-caption` in `LightMuted`; words use
  `type-body` in `Light`. The visual weight difference makes the
  numbers recede and the words pop.
- No "hide/reveal" toggle on the grid — the screen is already
  biometric-gated and FLAG_SECURE protected. Adding a blur overlay
  is security theater that frustrates the user.
- Every spacing value MUST be a `space-*` token.

## 11. PRODUCT LOCKED

- Biometric is required every time this screen is opened from
  Settings. No "remember for 60 seconds" cache.
- Onboarding entry shows this screen ONCE during wallet creation.
  The user must check "I understand" before proceeding to Balance.
  This sets `recovery_phrase_viewed = true` and permanently hides
  the backup banner on Balance.
- Copy-to-clipboard is allowed with a warning toast ("Copied —
  keep it safe"). Rationale: technical users have secure password
  managers; the warning flags risk without infantilizing (per T1-2
  decision).
- No "confirm your phrase" quiz (tap word #3, #17, #22). Rejected
  per T1-2 decision — too patronizing for a technical audience.
- The phrase itself is loaded from `SeedVault.loadSeed()` → derive
  mnemonic. The raw seed never leaves `SeedVault`; only the mnemonic
  words are passed to the UI composable.
- Clipboard is cleared after 60 seconds (Android clipboard manager
  auto-clear). The app does NOT manually clear — Android handles it
  natively on API 33+.

## 12. NEW COMPONENTS

| Component          | Shape                                                                    |
|--------------------|--------------------------------------------------------------------------|
| `WordGrid`         | 3-column × 8-row grid displaying 24 numbered words. Each cell: Row with number (`type-caption`, `LightMuted`, right-aligned, 20dp fixed width) + `space-8` + word (`type-body`, `Light`). Column spacing `space-16`, row spacing `space-8`. Wraps in a single `GlassPanel`. At max dynamic-type, may fall back to 2 columns. Read-only — no cell interaction. |

**Reused components:** `ActionPill` (from 07-receive §12 — Copy action),
`GlassPanel`, `DuskPrimaryButtonPaletted`, `ToastPill`,
`MaterializeEffect`.

---

End of Recovery phrase spec. Ship paired dark + light frames for
`default` and `default / onboarding-entry` (with checkbox unchecked
and checked states).
