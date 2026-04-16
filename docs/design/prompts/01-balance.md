# Screen — Balance (home)

## 1. GOAL

In under 1s of cold open, the user sees verified NIGHT + DUST + shielded
balances on the current network and can tap **Receive** or **Send**.

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

Onboarding template slots: `label → space-20 → headline → space-4 →
detail → … → space-48 → actions`.

### Layout — `default`

```
[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 kuira-glyph]  "Kuira" (type-body, Light)
  — flex —
  [NetworkBadge]                      [icon-24 settings]  (48dp tap)

space-16 (screen horizontal inset throughout)

[Backup banner]  CONDITIONAL: only if recovery_phrase_viewed == false
  Height 48dp · bg LightBarely · radius 12dp · full-width (inside insets)
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

[AddressChip]  segmented Unshielded / Shielded · type-mono · copy on tap

space-48

[Action row]
  DuskButtonRow
    secondary "Receive"
    primary   "Send"

safe-area bottom inset
```

### Layout — `loading-first`

Identical skeleton to `default`. Differences:
- Hero numeric: skeleton block, bg `LightBarely`, height matches
  type-numeric-hero glyph box
- Hero detail: `"Loading…"` (type-detail, LightMuted)
- Secondary tokens: skeleton rows
- Banner: hidden (not yet determined if phrase viewed)

### Layout — `syncing`

Identical to `default`. Differences:
- Hero detail reads `"NIGHT · Syncing…"` (type-detail, LightSoft)
- 12dp pulsing dot (color `LightSoft`, motion-fast) inline after the text

### Layout — `error`

Identical to `default`. Differences:
- Hero detail reads `"NIGHT · Sync failed"` (type-detail, LightSoft)
- Above the action row, inline row:
  ```
  "Could not update balance"  (type-detail, LightMuted)
  — flex —
  "Retry"                     (type-detail, Light, underlined)
  ```

### Layout — `offline`

Identical to `default`. Differences:
- NetworkBadge gains an adjacent `icon-16` offline-dot in LightMuted
- Hero detail reads `"NIGHT · Offline · showing cached"`

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
| Receive button    | Tap         | Receive screen                                                 |
| Send button       | Tap         | Send screen                                                    |
| Settings icon     | Tap         | Settings screen                                                |
| Error "Retry"     | Tap         | Triggers sync                                                  |

## 6. MOTION

- **Entry:** `MaterializeEffect` on the hero block (`motion-emphasize`).
  Stars scatter → converge → reveal the numeric.
- **NetworkBadge:** instant, no animation (it is chrome).
- **Skeleton → real content:** cross-fade `motion-standard`.
- **Pull-to-refresh:** Android stock overshoot.
- **Hero → Tx History navigation:** shared-element ease if feasible;
  otherwise `motion-standard` forward slide.
- **Respect reduce-motion:** snap to end state.

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
- SHIELDED row label: `SHIELDED`
- SHIELDED row locked value: `locked — tap to unlock`
- Backup banner: `Back up your recovery phrase`
- Error inline: `Could not update balance`
- Retry text button: `Retry`
- Copied pill: `Copied`

## 9. A11Y

- **Focus order (system back not applicable at home):** settings icon →
  NetworkBadge (if tappable in dev mode) → banner (if present) → hero →
  DUST row → SHIELDED row → AddressChip → Receive → Send
- **Content descriptions:**
  - settings icon: `Open settings`
  - NetworkBadge: `Current network, <name>`
  - banner: `Back up your recovery phrase. Double tap to view.`
  - hero: `<amount> NIGHT balance`
  - DUST row: `<amount> DUST balance`
  - SHIELDED row (locked): `Shielded balance, locked, double tap to unlock`
  - SHIELDED row (value): `<amount> shielded balance`
  - AddressChip: `<segment> address, <truncated>, double tap to copy`
  - Receive button: `Open receive screen`
  - Send button: `Send a transaction`
- **Dynamic type:** type-numeric-hero may wrap to two lines at ≥200%
  scale. All other rows keep single-line; truncate with ellipsis if
  needed.
- **Reduce motion:** `MaterializeEffect` snaps to end state.
- **Touch targets:** all rows ≥ 48dp. AddressChip segments are 48dp tall.

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
- Network picker in the top bar (tapping NetworkBadge) is **dev-mode
  only**. Non-dev users see NetworkBadge as read-only.

## 12. NEW COMPONENTS

| Component       | Shape                                                            |
|-----------------|------------------------------------------------------------------|
| `NetworkBadge`  | Pill, height 24dp, padding h-8 v-4, radius 12dp, bg `LightBarely`, text `type-label-tiny` in `LightSoft` |
| `BackupBanner`  | Full-width row, height 48dp, radius 12dp, bg `LightBarely`. Leading icon-20, trailing icon-16, content type-detail. Entire row tappable, 48dp tap. |
| `BalanceHero`   | Composes label + numeric + detail using the onboarding template slots. Numeric uses `type-numeric-hero`. |
| `AddressChip`   | Segmented control (Unshielded / Shielded), height 48dp, radius 12dp, bg `LightBarely`. Active segment bg `VoidElevated`. Shows `format-address-short` in `type-mono`. |

---

**End of Balance screen spec.** Ship paired dark + light frames for each
state listed above.
