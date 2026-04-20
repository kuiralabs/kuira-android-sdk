# Screen — Settings (host screen, T1-1)

## 1. GOAL

Configure wallet-wide preferences, review network + security, access
destructive actions behind gates. Host screen for T1-1.

## 2. SITEMAP POSITION

- `from:` Balance (top-bar settings icon)
- `to:` Recovery phrase view · Wipe wallet flow · Network picker sheet ·
  External: GitHub · License · Support mailto

## 3. STATES

| State           | Applies?  | Notes                                       |
|-----------------|-----------|---------------------------------------------|
| `default`       | ✓         | Standard settings list                      |
| `loading-first` | n/a       |                                             |
| `syncing`       | n/a       |                                             |
| `empty`         | n/a       |                                             |
| `no-results`    | n/a       |                                             |
| `error`         | n/a       |                                             |
| `offline`       | n/a       |                                             |
| `pending`       | n/a       |                                             |
| `success`       | n/a       |                                             |

**Variants** (additional frames on top of `default`, not canonical
states — the screen adds/removes sections rather than changing its
core render mode):

- `default / dev-mode-unlocked` — 7-tap on Version row reveals the
  Developer Options section between NETWORK and SECURITY. Session-
  scoped; reverts on app restart.

## 4. LAYOUT

Follows the visual language template with a sectioned list below the
top bar. Each section = header (`type-label-tiny`) + opaque
`GlassPanel` containing `SettingsRow` entries separated by 1dp
`LightFaint` dividers.

Material icon names in square brackets (e.g. `Icons.Filled.Language`)
are the exact AI reference so the wireframe doesn't guess glyphs.

### Layout — `default`

```
[DuskScaffold] — wraps entire screen, ambient StarField

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]  (Icons.AutoMirrored.Filled.ArrowBack)
  "Settings"      (type-body, Light)
  — flex —        (no right-slot actions)

space-16 (screen horizontal inset throughout)

space-16 top spacing

[Section: NETWORK]
  SettingsSectionHeader "NETWORK"
  space-12   (sectioned-list rhythm — see _prefix.md LIST ROWS)
  [GlassPanel — contentPanel tint, LightFaint hairline]
    SettingsRow
      leading icon  icon-24 (Icons.Filled.Language)
      label         "Network"
      right value   "Preprod"        (type-body, LightSoft)
      chevron       (icon-16 Icons.AutoMirrored.Filled.ArrowForward, dev-mode only)
    1dp LightFaint divider inside panel
    SettingsRow (read-only)
      leading icon  icon-24 (Icons.Filled.Sync)
      label         "Last sync"
      right value   "12s ago"        (type-body, Light — data pops per
                                      readOnly emphasis rule)

space-32   (sectioned-list rhythm — see _prefix.md LIST ROWS)

[Section: SECURITY]
  SettingsSectionHeader "SECURITY"
  space-12   (sectioned-list rhythm — see _prefix.md LIST ROWS)
  [GlassPanel]
    SettingsRow   icon-24 (Icons.Filled.Key)           label "View recovery phrase"   chevron (biometric gate)
    divider
    SettingsRow   icon-24 (Icons.Filled.Fingerprint)   label "Test biometric"         chevron
    divider
    DangerRow     icon-24 (Icons.Filled.DeleteForever) label "Wipe wallet"            chevron

space-32   (sectioned-list rhythm — see _prefix.md LIST ROWS)

[Section: ABOUT]
  SettingsSectionHeader "ABOUT"
  space-12   (sectioned-list rhythm — see _prefix.md LIST ROWS)
  [GlassPanel]
    SettingsRow (read-only)  label "Version"  right value "1.0.0"
    divider
    SettingsRow (read-only)  label "Commit"   right value "abc12345" (type-mono)
    divider
    SettingsRow              label "License"  chevron (opens browser)
    divider
    SettingsRow              label "GitHub"   chevron (opens browser)
    divider
    SettingsRow              label "Support"  chevron (opens mailto)

space-24 above safe-area-insets.bottom   (sectioned-list rhythm — see _prefix.md LIST ROWS)
```

### Variant — `default / dev-mode-unlocked`

Same as `default` plus a new section inserted between NETWORK and
SECURITY:

```
[Section: DEVELOPER OPTIONS]
  SettingsSectionHeader "DEVELOPER OPTIONS"
  space-12   (sectioned-list rhythm — see _prefix.md LIST ROWS)
  [GlassPanel]
    SettingsRow (read-only)  label "Proof server"   right value "Default (local)"
                                                    (placeholder, non-editable in v1.0 first pass)
    divider
    DangerRow                label "Force re-sync"  chevron (destructive, triggers performFullResync)
    divider
    SettingsRow (read-only)  label "Build info"     right value "debug · abc123"

space-32   (sectioned-list rhythm — inter-section gap to SECURITY)
```

## 5. INTERACTIONS

| Element                    | Gesture       | Result                                                  |
|----------------------------|---------------|---------------------------------------------------------|
| Back arrow                 | Tap           | Pop to Balance                                          |
| Network row                | Tap (dev-mode)| Bottom-sheet network picker (Preprod / Preview / Mainnet-disabled) |
| Network row                | Tap (non-dev) | No-op; row is informational                             |
| View recovery phrase       | Tap           | Biometric prompt → Recovery phrase view (FLAG_SECURE)   |
| Test biometric             | Tap           | Biometric prompt → toast "Biometric OK"                 |
| Wipe wallet                | Tap           | Biometric → confirmation sheet → "type WIPE" challenge  |
| Version row                | Tap × 7       | Unlocks Developer Options section (haptic-confirm on unlock) |
| Force re-sync              | Tap           | Confirmation sheet → runs performFullResync + restarts subscription |
| License / GitHub / Support | Tap           | Opens external browser / mail client                    |

## 6. MOTION

- Entry: `MaterializeEffect` on the first section header (`motion-emphasize`).
- Section-to-section: no animation; static list.
- Dev-mode unlock: new section slides in from top (`motion-standard`).
- ConfirmationSheet open/close: `motion-standard` slide-up.
- NetworkPicker sheet open/close: `motion-standard` slide-up.
- Reduce-motion: all of the above snap to end state.

## 7. HAPTICS

| Trigger                             | Token           |
|-------------------------------------|-----------------|
| Row tap (navigational)              | `haptic-tap`    |
| Version tap × 7 (dev-mode unlock)   | `haptic-confirm`|
| Wipe confirm final step             | `haptic-confirm`|
| Force re-sync confirm               | `haptic-confirm`|

## 8. COPY

Exact strings; do not rewrite.

### Row labels

- Top bar title: `Settings`
- Section headers: `NETWORK`, `DEVELOPER OPTIONS`, `SECURITY`, `ABOUT`
- Network row label: `Network`
- Last sync row label: `Last sync`
- View phrase row label: `View recovery phrase`
- Biometric test row label: `Test biometric`
- Wipe row label: `Wipe wallet`
- Proof server row label: `Proof server`
- Proof server placeholder value: `Default (local)`
- Force re-sync row label: `Force re-sync`
- Build info row label: `Build info`
- About row labels: `Version`, `Commit`, `License`, `GitHub`, `Support`

### Sheets + toasts

- NetworkPicker sheet heading: `Select network`
- NetworkPicker mainnet-disabled subtitle: `Coming soon`
- Biometric test success toast: `Biometric OK`
- ConfirmationSheet for `Wipe wallet`:
  - headline: `Wipe wallet?`
  - body: `This erases your seed, keys, and cached state from this device. You can only restore with your recovery phrase.`
  - type-challenge prompt: `Type WIPE to confirm`
  - primary button: `Wipe wallet`
  - secondary button: `Cancel`
- ConfirmationSheet for `Force re-sync`:
  - headline: `Force re-sync?`
  - body: `This clears cached transactions and re-syncs from the indexer. Takes a few seconds on a warm stack.`
  - primary button: `Re-sync`
  - secondary button: `Cancel`
- Dev-mode unlock toast: `Developer options unlocked`

## 9. A11Y

- Focus order: back arrow → within each section, top-to-bottom rows → next section. Sequence: NETWORK (Network, Last sync) → (DEV OPTIONS if unlocked: Proof server, Force re-sync, Build info) → SECURITY (View recovery phrase, Test biometric, Wipe wallet) → ABOUT (Version, Commit, License, GitHub, Support). Read-only rows (Last sync, Proof server, Build info, Version, Commit) remain focusable for TalkBack read-out.
- Content descriptions:
  - back arrow: `Back to balance`
  - Network row: `Current network <name>. <Double tap to change.>` (omit second sentence in non-dev)
  - View phrase: `View your 24-word recovery phrase`
  - Wipe wallet: `Wipe wallet. This cannot be undone.`
  - License / GitHub / Support: verb + destination
- Dynamic type: rows scale up; truncate right-value with ellipsis if needed.
- Reduce motion: all transitions snap.
- Touch targets: every row follows the LIST ROWS standard in `_prefix.md`
  (56dp minimum, 16dp vertical inner padding). 48dp is the accessibility
  floor, not the target.
- Destructive rows (wipe, force resync) have distinct TalkBack announcement prefix (`Destructive action, ...`).

## 10. VISUAL LOCKED

- Dusk palette only. No accent color for hierarchy or decoration.
  `ErrorText` is the only color token — see `_prefix.md` palette
  rules. Settings uses `ErrorText` only on destructive confirmation
  challenges (e.g., "WIPE" typed text in the ConfirmationSheet).
- DangerRow distinguishes ONLY via icon choice (destructive glyph:
  trash, warning, or equivalent) + explicit "destructive" content
  description for TalkBack. Same type + color tokens as SettingsRow
  — no weight changes, no color changes, no inverted fill. The
  "this action is destructive" signal is the icon and the
  confirmation gating (all destructive actions open a
  ConfirmationSheet), not the row's visual weight.
- Section headers use `type-label-tiny` uppercase letter-spaced.
- Row heights follow the LIST ROWS standard in `_prefix.md` (56dp
  minimum, 16dp vertical inner padding).
- Dividers are 1dp `LightFaint` hairlines inside the GlassPanel.
- Every section's rows sit in a `GlassPanel` (`palette.contentPanel`, 1dp LightFaint border, radius-md). Star-protection policy applies to all settings rows — text legibility wins over texture.
- Every spacing value MUST be a `space-*` token.

## 11. PRODUCT LOCKED

- Developer Options section is hidden by default. Unlocks only via
  7 taps on the Version row (no other path). Unlock persists for
  the session only — reverts on app restart.
- Non-dev users see the Network row without a chevron and cannot
  change it from Settings.
- Wipe wallet always requires three gates: biometric → confirmation
  sheet → `type WIPE` text challenge. Never 1-tap.
- Force re-sync is a destructive action (clears cached UTXOs + sync
  state) and requires confirmation.
- Proof-server row is a placeholder in v1.0 first pass per the
  deferred decision in `WALLET_PRODUCTIZATION_PLAN.md` — row is
  visible (explains what exists) but non-editable.

## 12. NEW COMPONENTS

| Component                 | Shape                                                                    |
|---------------------------|--------------------------------------------------------------------------|
| `SettingsSectionHeader`   | Uppercase label in `type-label-tiny` (inherits `LightMuted` default color), left-aligned, space-16 horizontal inset, `space-12` gap to panel below (sectioned-list rhythm per `_prefix.md` LIST ROWS). |
| `SettingsRow`             | Full-width row inside a GlassPanel. Sizing per the LIST ROWS standard in `_prefix.md` (56dp minimum, 16dp vertical inner padding). Leading optional `icon-24` (space-12 gap to label). **`readOnly: Boolean`** controls emphasis direction: **(a) navigational** (`readOnly = false`) — label `Light` (100%, you're scanning what to tap), right-value `LightSoft` (80%, preview), trailing `icon-16` chevron; **(b) data-display** (`readOnly = true`) — label `LightSoft` (80%, context recedes), right-value `Light` (100%, data pops), no chevron, not tappable. The principle: labels are context, values are data — the data should always be the brightest element. **`trailingIcon: ImageVector?`** (default `null`) overrides the default trailing slot. An explicit `trailingIcon` on a read-only row makes it tappable (e.g., copy icon on address rows — `readOnly = true, trailingIcon = Icons.Filled.ContentCopy`). |
| `DangerRow`               | SettingsRow variant for destructive actions. Identical typography and colors — no weight changes, no color changes. Distinguished ONLY by a destructive leading icon (trash / delete / warning glyph, `icon-24`, color `Light` like any other icon). Tap always opens a `ConfirmationSheet`. Content description prefixed `Destructive action, `. |
| `ConfirmationSheet`       | Modal bottom sheet for destructive confirmations. Reuses `DevPortalModal` shell from `core:designsystem.devportal`: drag handle, `VoidElevated` container, 16dp padding. Slots: headline (`type-headline-sm`), body (`type-body` in `LightSoft`), optional typed-challenge field (type-input with placeholder from COPY), `DuskButtonRow` with cancel (secondary) + confirm (primary). Primary button only enables once challenge text matches the expected string (when the sheet specifies one). |
| `NetworkPicker` sheet     | Modal bottom sheet. Reuses `DevRadioRow` from `core:designsystem.devportal` (the same primitive used by the wireframe dev controls). Mainnet row uses `DevRadioRow` with `enabled = false` and a `type-caption` subtitle `Coming soon` until the `mainnet_enabled` remote flag flips. Heading in `type-label-tiny` per COPY. |

---

End of Settings screen spec. Ship paired dark + light frames for each
listed state.
