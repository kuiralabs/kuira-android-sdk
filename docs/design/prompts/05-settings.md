# Screen — Settings

## 1. GOAL

Configure wallet-wide preferences, review network + security, access
destructive actions behind gates. Host screen for T1-1.

## 2. SITEMAP POSITION

- `from:` Balance (top-bar settings icon)
- `to:` Recovery phrase view · Wipe wallet flow · Network picker sheet ·
  External: GitHub · License · Support mailto

## 3. STATES

| State               | Applies?  | Notes                                       |
|---------------------|-----------|---------------------------------------------|
| `default`           | ✓         | Standard settings list                      |
| `dev-mode-unlocked` | ✓         | Developer Options section revealed (after 7-tap on version) |
| `loading-first`     | n/a       |                                             |
| `syncing`           | n/a       |                                             |
| `empty`             | n/a       |                                             |
| `no-results`        | n/a       |                                             |
| `error`             | n/a       |                                             |
| `offline`           | n/a       |                                             |
| `pending`           | n/a       |                                             |
| `success`           | n/a       |                                             |

## 4. LAYOUT

Follows the visual language template with a sectioned list below the
top bar. Each section = header (`type-label-tiny`) + opaque
`GlassPanel` containing `SettingsRow` entries separated by 1dp
`LightFaint` dividers.

### Layout — `default`

```
[DuskScaffold] — wraps entire screen, ambient StarField

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]
  "Settings" (type-body, Light)
  — flex —  (no right-slot actions)

space-16 (screen horizontal inset throughout)

space-16 top spacing

[Section: NETWORK]
  SettingsSectionHeader "NETWORK"  (type-label-tiny, LightMuted)
  space-8
  [GlassPanel — contentPanel tint, LightFaint hairline]
    SettingsRow
      leading icon  globe (icon-24)
      label         "Network"
      right value   "Preprod"       (type-body, LightSoft)
      chevron       (dev-mode only)
    1dp LightFaint divider inside panel
    SettingsRow
      leading icon  sync (icon-24)
      label         "Last sync"
      right value   "12s ago"       (type-body, LightMuted)
      no chevron (read-only)

space-24

[Section: SECURITY]
  SettingsSectionHeader "SECURITY"
  space-8
  [GlassPanel]
    SettingsRow      key glyph    "View recovery phrase"        → (biometric)
    divider
    SettingsRow      shield       "Test biometric"              →
    divider
    DangerRow        warning glyph "Wipe wallet"                 → (destructive)

space-24

[Section: ABOUT]
  SettingsSectionHeader "ABOUT"
  space-8
  [GlassPanel]
    SettingsRow      label "Version"           right value "1.0.0"
    divider
    SettingsRow      label "Commit"            right value "abc12345" (type-mono)
    divider
    SettingsRow      label "License"           → (opens browser)
    divider
    SettingsRow      label "GitHub"            → (opens browser)
    divider
    SettingsRow      label "Support"           → (opens mailto)

space-24 above safe-area-insets.bottom
```

### Layout — `dev-mode-unlocked`

Same as `default` plus a new section inserted between NETWORK and
SECURITY:

```
[Section: DEVELOPER OPTIONS]
  SettingsSectionHeader "DEVELOPER OPTIONS"
  space-8
  [GlassPanel]
    SettingsRow      label "Proof server"     right value "Default (local)"  (placeholder, non-editable in v1.0 first pass)
    divider
    SettingsRow      label "Force re-sync"    → (danger styling, triggers full resync)
    divider
    SettingsRow      label "Build info"       right value "debug · abc123"

space-24
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

- Entry: `MaterializeEffect` on the first section header.
- Section-to-section: no animation; static list.
- Dev-mode unlock: new section slides in from top with `motion-standard`.
- Network picker sheet: standard Material3 `ModalBottomSheet` slide-up.
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

## 9. A11Y

- Focus order: back arrow → NETWORK section rows → (DEV OPTIONS if unlocked) → SECURITY rows → ABOUT rows
- Content descriptions:
  - back arrow: `Back to balance`
  - Network row: `Current network <name>. <Double tap to change.>` (omit second sentence in non-dev)
  - View phrase: `View your 24-word recovery phrase`
  - Wipe wallet: `Wipe wallet. This cannot be undone.`
  - License / GitHub / Support: verb + destination
- Dynamic type: rows scale up; truncate right-value with ellipsis if needed.
- Reduce motion: all transitions snap.
- Touch targets: every row ≥ 48dp tall.
- Destructive rows (wipe, force resync) have distinct TalkBack announcement prefix (`Destructive action, ...`).

## 10. VISUAL LOCKED

- Dusk palette only. No red / green / yellow / blue.
- DangerRow distinguishes via weight + icon, NOT color. Never `Color.Red`.
- Section headers use `type-label-tiny` uppercase letter-spaced.
- Row heights ≥ 48dp.
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
| `SettingsSectionHeader`   | Uppercase label in `type-label-tiny`, color `LightMuted`, left-aligned, space-16 horizontal inset, space-8 gap to panel below. |
| `SettingsRow`             | Full-width row inside a GlassPanel. Height ≥ 48dp. Leading optional `icon-24` (with space-12 gap). Label `type-body` Light. Right-value `type-body` LightSoft (or LightMuted for read-only). Optional trailing `icon-16` chevron `LightMuted`. Entire row tappable. |
| `DangerRow`               | SettingsRow variant for destructive actions. Label in `Light`, weight unchanged, leading icon weight increased; no color change. Distinguished by `type-body` W400 (heavier than standard W300 row labels). |
| `NetworkPicker` sheet     | Modal bottom sheet. List of networks with radio row (`DevRadioRow`-like component shared with dev modal). Mainnet row is disabled with a subtitle "coming soon" until `mainnet_enabled` remote flag flips. |

---

End of Settings screen spec. Ship paired dark + light frames for each
listed state.
