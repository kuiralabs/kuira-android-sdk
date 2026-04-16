# Screen — Balance (home)

## 1. GOAL

In under 1s of cold open, the user sees verified NIGHT + DUST + shielded
balances on the current network and can tap Receive or Send.

## 2. SITEMAP POSITION

- `from:` Splash · Onboarding Success · app resume
- `to:` Send · Receive · Tx History (via hero drill-in) ·
  Dust (via DUST row) · Settings · Recovery phrase view (via backup banner)

## 3. STATES

Produce one frame per state × mode (so 5 × 2 = 10 frames total).

| State           | Applies?  | Notes                                     |
|-----------------|-----------|-------------------------------------------|
| `default`       | ✓         | Cached balance present, sync idle         |
| `loading-first` | ✓         | First cold open; no cache yet             |
| `syncing`       | ✓         | Cached visible, refresh in progress       |
| `empty`         | n/a       | A just-created wallet shows `default` with 0 — not a distinct state |
| `no-results`    | n/a       |                                           |
| `error`         | ✓         | Sync failed; cached still visible         |
| `offline`       | ✓         | Network down; cached visible              |
| `pending`       | n/a       |                                           |
| `success`       | n/a       |                                           |

## 4. LAYOUT

Follows the visual language template: label → space-20 → headline →
space-4 → detail → … → content → actions.

Deviation from template: actions are NOT DuskPrimaryButton /
DuskButtonRow at the bottom. Balance uses a horizontal row of
`QuickActionCircle` components (icon circles with labels) placed
directly below the secondary tokens — matching the Phantom wallet
home-screen pattern. This is a Balance-only deviation; all other
screens use the standard button pattern.

### Layout — `default`

```
[DuskScaffold] — wraps entire screen, provides DuskEffect ambient bg

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 kuira-glyph]  "Kuira" (type-body, Light)
  — flex —
  [NetworkBadge]                      [icon-24 settings]  (48dp tap)

space-16 (screen horizontal inset throughout)

[Backup banner]  CONDITIONAL: only if recovery_phrase_viewed == false
  Height 48dp · bg LightBarely · radius-md · full-width (inside insets)
  [icon-20 key]  "Back up your recovery phrase" (type-detail, Light)
  — flex —
  [icon-16 chevron, LightMuted]

(if banner present: space-24 · else space-32)

[Hero]
  label    "BALANCE"           (type-label-tiny)
  space-20
  headline <format-amount-night> (type-numeric-hero)
  space-4
  detail   "NIGHT · Synced <format-time-relative>"  (type-detail)

space-32

[Secondary tokens]
  DuskBulletLine  "DUST"      · right-aligned  <format-amount-dust> · DUST
  DuskBulletLine  "SHIELDED"  · right-aligned  <format-amount-night>
                                               (or "locked — tap to unlock")

space-24

[Quick actions row]  — horizontally centered, evenly spaced
  QuickActionCircle  icon: arrow-up     label: "Send"
  QuickActionCircle  icon: qr-code      label: "Receive"

space-32

[AddressChip]  segmented Unshielded / Shielded · type-mono · copy on tap

space-24 above safe-area-insets.bottom
```

### Layout — `loading-first`

Identical skeleton to `default`. Differences:
- Hero numeric: skeleton block, bg `LightBarely`, height matches `type-numeric-hero` glyph box
- Hero detail: "Loading…" (`type-detail`, `LightMuted`)
- Secondary tokens: skeleton rows (same `LightBarely` blocks)
- Banner: hidden (not yet determined if phrase viewed)

### Layout — `syncing`

Identical to `default`. Differences:
- Hero detail reads "NIGHT · Syncing…" (`type-detail`, `LightSoft`)
- `icon-16` pulsing dot (color `LightSoft`, `motion-fast`) inline after the text

### Layout — `error`

Identical to `default`. Differences:
- Hero detail reads "NIGHT · Sync failed" (`type-detail`, `LightSoft`)
- Above the action row, inline row:
  ```
  "Could not update balance"  (type-detail, LightMuted)
  — flex —
  "Retry"                     (type-detail, Light, underlined)
  ```

### Layout — `offline`

Identical to `default`. Differences:
- `NetworkBadge` gains an adjacent `icon-16` offline-dot in `LightMuted`
- Hero detail reads "NIGHT · Offline · showing cached" (`type-detail`)

## 5. INTERACTIONS

| Element           | Gesture     | Result                                                         |
|-------------------|-------------|----------------------------------------------------------------|
| Whole screen      | Pull-to-refresh | Manual sync. `haptic-tap` on threshold cross               |
| Hero              | Tap         | Tx History filtered to NIGHT                                   |
| DUST row          | Tap         | Dust screen                                                    |
| SHIELDED row      | Tap (locked) | Biometric prompt → unlock → row becomes value                 |
| SHIELDED row      | Tap (unlocked) | Tx History filtered to shielded                              |
| AddressChip seg   | Tap         | Swap active segment                                            |
| AddressChip seg   | Long-press  | Copy that address · `haptic-select` · `ToastPill "Copied"`     |
| Backup banner     | Tap         | Recovery phrase view (biometric-gated)                         |
| Send circle       | Tap         | Send screen                                                    |
| Receive circle    | Tap         | Receive screen                                                 |
| Settings icon     | Tap         | Settings screen                                                |
| Error "Retry"     | Tap         | Triggers sync (error state only)                               |

## 6. MOTION

- Entry: `MaterializeEffect` on the hero block (`motion-emphasize`).
  Stars scatter → converge → reveal the numeric.
- NetworkBadge: instant, no animation (it is chrome).
- Skeleton → real content: cross-fade (`motion-standard`).
- Pull-to-refresh: Android stock overshoot.
- Hero → Tx History navigation: shared-element ease if feasible;
  otherwise `motion-standard` forward slide.
- Reduce-motion: all of the above snap to end state.

## 7. HAPTICS

| Trigger                                | Token           |
|----------------------------------------|-----------------|
| Pull-to-refresh threshold              | `haptic-tap`    |
| Tap to copy (pill feedback)            | `haptic-tap`    |
| Long-press address to copy             | `haptic-select` |
| Sync recovers after error              | `haptic-confirm`|

## 8. COPY

Exact strings; do not rewrite.

- Top bar title: `Kuira`
- Hero label: `BALANCE`
- Hero detail (default): `NIGHT · Synced <format-time-relative>`
- Hero detail (syncing): `NIGHT · Syncing…`
- Hero detail (error): `NIGHT · Sync failed`
- Hero detail (offline): `NIGHT · Offline · showing cached`
- DUST row label: `DUST`
- DUST row denomination (after amount): `DUST`
- SHIELDED row label: `SHIELDED`
- SHIELDED row locked value: `locked — tap to unlock`
- Balance denomination (hero detail): `NIGHT`
- Backup banner: `Back up your recovery phrase`
- Error inline: `Could not update balance`
- Send circle label: `Send`
- Receive circle label: `Receive`
- Retry text button: `Retry`
- Copied pill: `Copied`

## 9. A11Y

- Focus order (system back not applicable at home): settings icon →
  NetworkBadge (if tappable in dev mode) → banner (if present) → hero →
  DUST row → SHIELDED row → Send circle → Receive circle → AddressChip
- Content descriptions:
  - settings icon: `Open settings`
  - NetworkBadge: `Current network, <name>`
  - banner: `Back up your recovery phrase. Double tap to view.`
  - hero: `<amount> NIGHT balance`
  - DUST row: `<amount> DUST balance`
  - SHIELDED row (locked): `Shielded balance, locked, double tap to unlock`
  - SHIELDED row (value): `<amount> shielded balance`
  - AddressChip: `<segment> address, <truncated>, double tap to copy`
  - Send circle: `Send a transaction`
  - Receive circle: `Open receive screen`
- Dynamic type: `type-numeric-hero` may wrap to two lines at ≥200%
  scale. All other rows keep single-line; truncate with ellipsis if
  needed.
- Reduce motion: `MaterializeEffect` snaps to end state.
- Touch targets: all rows ≥ 48dp. `AddressChip` segments are 48dp tall.

## 10. VISUAL LOCKED

- Dusk palette only. No red / green / yellow / blue anywhere.
- Backup banner uses `LightBarely` bg + weight — NOT red.
- No fiat values anywhere.
- No accent icons on balance numbers (no "↑" on the hero).
- Hero uses `type-numeric-hero` (W200); all other headlines use W300.
- Every spacing value MUST be a `space-*` token.
- Every type choice MUST be a `type-*` token.

## 11. PRODUCT LOCKED

- `NetworkBadge` is always visible in the top bar (T1-16 decision).
- Backup banner is non-dismissible while
  `recovery_phrase_viewed == false`. Once the flag is set, the banner
  is gone forever; there is no way to bring it back.
- Balance values come from the local UTXO cache. Incremental sync is
  already shipped (T1-21) — the default state is cached-and-fast, not
  loading-from-network.
- Shielded row shows `locked — tap to unlock` if the shielded key is
  not decrypted this session. Decryption is session-scoped, not
  persistent.
- Network picker in the top bar (tapping NetworkBadge) is dev-mode
  only. Non-dev users see NetworkBadge as read-only.

## 12. NEW COMPONENTS

| Component       | Shape                                                            |
|-----------------|------------------------------------------------------------------|
| `NetworkBadge`  | Pill, height 24dp, horizontal padding space-8, vertical padding space-4, radius-full, bg LightBarely, text type-label-tiny in LightSoft |
| `BackupBanner`  | Full-width row, height 48dp, radius-md, bg LightBarely. Leading icon-20, trailing icon-16, content type-detail in Light. Entire row tappable, 48dp tap target. |
| `BalanceHero`   | Composes label + numeric + detail using the visual language template slots. Numeric uses type-numeric-hero. |
| `QuickActionCircle` | 48dp circle, bg LightBarely, radius-full. Centered icon-24 in Light. Label below: type-caption in LightMuted, space-8 gap between circle and label. Entire column tappable, 48dp tap target on circle. Inspired by Phantom's home-screen action row — icon circles with text labels underneath. |
| `AddressChip`   | Segmented control (Unshielded / Shielded), radius-md, track bg LightBarely, space-4 inner padding. Active segment bg `Light` (opposite-pole fill — same rule as DuskPrimaryButton) with text color `Void`. Inactive segment: transparent, label `LightMuted`, address `LightFaint`. Address uses format-address-short in type-mono. Rationale: `VoidElevated` against `LightBarely` gives only ~3% luminance delta in light mode — too subtle for a tab selector. Opposite-pole fill is the strongest dual-channel affordance (fill + text color both invert) and stays within the no-color rule. |

---

End of Balance screen spec. Ship paired dark + light frames for each
state listed above.
