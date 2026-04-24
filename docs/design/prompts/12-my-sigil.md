# Screen — My Sigil (home tab, sigil dashboard)

## 1. GOAL

The first thing the user sees every time they open Kuira. In under 1s,
the user knows: how much NIGHT they have, whether their sigil is backed
up, which apps bear their sigil, and what happened recently. Send and
Receive are one tap away. This is the command center — not just a
balance screen, but the full picture of the user's Midnight identity.

## 2. SITEMAP POSITION

- **Tab:** My Sigil (tab root screen, start destination)
- `from:` App launch → Splash → WalletGate · app resume ·
  bottom nav (My Sigil tab)
- `to:` Send · Receive (push within My Sigil tab) ·
  App detail (push, Phase 7+) · Activity tab (via "View all")

## 3. STATES

| State           | Applies?  | Notes                                             |
|-----------------|-----------|---------------------------------------------------|
| `default`       | ✓         | Cached balance, sigil active, 0+ connected apps   |
| `loading-first` | ✓         | First cold open after forging sigil; no cache      |
| `syncing`       | ✓         | Cached visible, refresh in progress                |
| `empty`         | n/a       | A fresh sigil shows `default` with 0 balance       |
| `no-results`    | n/a       |                                                   |
| `error`         | ✓         | Sync failed; cached still visible                  |
| `offline`       | ✓         | Network down; cached visible                       |
| `pending`       | n/a       |                                                   |
| `success`       | n/a       |                                                   |

## 4. LAYOUT

Follows the visual language template with deviations stated below.

**Deviation 1:** Actions are `QuickActionCircle` row (same as Balance
screen, matching Phantom pattern) — not DuskButtonRow.

**Deviation 2:** Below the hero + actions, the screen is a vertically
scrollable list of sections. This is unique to My Sigil — other
screens are single-purpose. My Sigil is a dashboard.

### Layout — `default`

```
[DuskScaffold] — wraps entire screen, provides DuskEffect ambient bg

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 kuira-glyph]  "Kuira" (type-body, Light)
  — flex —
  [NetworkBadge]

space-16 (screen horizontal inset throughout)

[Backup banner]  CONDITIONAL: only if recovery_phrase_viewed == false
  Height 48dp · bg LightBarely · radius-md · full-width
  [icon-20 key]  "Back up your recovery phrase" (type-detail, Light)
  — flex —
  [icon-16 chevron, LightMuted]

(if banner present: space-24 · else space-32)

═══════════════════════════════════════════════════════════
SECTION 1 — BALANCE HERO
═══════════════════════════════════════════════════════════

[GlassPanel — hero]
  label    "BALANCE"                    (type-label-tiny)
  space-20
  headline <format-amount-night>        (type-numeric-hero)
  space-4
  detail   "NIGHT · Synced <format-time-relative>"  (type-detail)

space-16

[GlassPanel — secondary tokens]  compact: single row, side by side
  left:   "DUST"      <format-amount-dust>     (type-caption, LightSoft)
  right:  "SHIELDED"  <format-amount-night>    (type-caption, LightSoft)
          OR "locked — tap to unlock"

space-24

[Quick actions row]  — horizontally centered, evenly spaced
  QuickActionCircle  icon: arrow-up     label: "Send"
  QuickActionCircle  icon: qr-code      label: "Receive"

space-12

[AddressChip]  segmented Unshielded / Shielded · type-mono · copy on tap

═══════════════════════════════════════════════════════════
SECTION 2 — CONNECTED APPS
═══════════════════════════════════════════════════════════

space-32

[SectionHeader]  "APPS BEARING YOUR SIGIL"  (type-label-tiny, LightMuted)

space-12

[GlassPanel — connected apps list]

  --- Phase 8B (shipped state): ---

  [EmptyState]
    icon-32 sigil-mark (LightFaint)
    space-8
    "No apps connected"      (type-body, LightSoft)
    space-4
    "Present your sigil to a Midnight app to get started."
                             (type-detail, LightMuted)

  --- Phase 7+ (populated state): ---

  ConnectedAppRow  (per connected app)
    [icon-24 app-icon]  space-12
    label: app name          (type-body, Light)
    detail: delegation tier  (type-caption, LightMuted)
    — flex —
    DelegationBadge: "Silent" | "Notify" | "Approve"
    [icon-16 chevron, LightMuted]

  Divider: 1dp LightFaint between rows
  Row height: 56dp minimum (LIST ROWS rule)

═══════════════════════════════════════════════════════════
SECTION 3 — RECENT ACTIVITY
═══════════════════════════════════════════════════════════

space-32

[SectionHeader]  "RECENT ACTIVITY"  (type-label-tiny, LightMuted)
  — flex —
  "View all"                (type-caption, LightSoft)  → taps to Activity tab

space-12

[GlassPanel — recent activity]

  --- With activity: ---

  TxRow × 3  (last 3 entries, same component as Activity tab)
    [TxTypeBadge]  space-12
    label: type + address-short  (type-body, Light)
    detail: <format-time-relative>  (type-caption, LightMuted)
    — flex —
    amount: ±<format-amount-night>   (type-body, Light)
    [icon-16 chevron, LightMuted]

  --- No activity: ---

  [EmptyState]
    "No activity yet"       (type-body, LightSoft)
    space-4
    "Transactions and delegations will appear here."
                            (type-detail, LightMuted)

space-24 above safe-area-insets.bottom
```

### Layout — `loading-first`

Identical skeleton to `default`. Differences:
- Hero numeric: skeleton block, bg `LightBarely`, height matches
  `type-numeric-hero` glyph box
- Hero detail: "Loading…" (`type-detail`, `LightMuted`)
- Secondary tokens: skeleton row (`LightBarely` blocks)
- Connected apps: skeleton rows × 2
- Recent activity: skeleton rows × 3
- Banner: hidden (not yet determined)

### Layout — `syncing`

Identical to `default`. Differences:
- Hero detail: "NIGHT · Syncing…" (`type-detail`, `LightSoft`)
- `icon-16` pulsing dot (color `LightSoft`, `motion-fast`) inline

### Layout — `error`

Identical to `default`. Differences:
- Hero detail: "NIGHT · Sync failed" (`type-detail`, `LightSoft`)
- Inline error row above quick actions:
  ```
  "Could not update balance"  (type-detail, LightMuted)
  — flex —
  "Retry"                     (type-detail, Light, underlined)
  ```

### Layout — `offline`

Identical to `default`. Differences:
- `NetworkBadge` gains adjacent `icon-16` offline-dot in `LightMuted`
- Hero detail: "NIGHT · Offline · showing cached" (`type-detail`)

## 5. INTERACTIONS

| Element                | Gesture      | Result                                        |
|------------------------|--------------|-----------------------------------------------|
| Whole screen           | Pull-to-refresh | Manual sync. `haptic-tap` on threshold     |
| Hero                   | Tap          | Assets tab (full balance breakdown)            |
| DUST inline            | Tap          | Dust screen (push)                             |
| SHIELDED inline (locked) | Tap        | Biometric → unlock → shows value              |
| AddressChip seg        | Tap          | Swap active segment                            |
| AddressChip seg        | Long-press   | Copy address · `haptic-select` · `ToastPill`  |
| Backup banner          | Tap          | Recovery phrase view (biometric-gated)         |
| Send circle            | Tap          | Send screen (push)                             |
| Receive circle         | Tap          | Receive screen (push)                          |
| Connected app row      | Tap          | App detail screen (push, Phase 7+)             |
| "View all" activity    | Tap          | Switch to Activity tab                         |
| Recent TxRow           | Tap          | Tx Detail (push in Activity tab)               |
| Error "Retry"          | Tap          | Triggers sync                                  |

## 6. MOTION

- Entry: `MaterializeEffect` on the hero block (`motion-emphasize`).
  Stars scatter → converge → reveal the numeric.
- Skeleton → real content: cross-fade (`motion-standard`).
- Pull-to-refresh: Android stock overshoot.
- Section headers: fade-in staggered 50ms after hero (`motion-standard`).
- Connected app rows: stagger-in 30ms each (`motion-fast`).
- Reduce-motion: all snap to end state.

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
- DUST inline label: `DUST`
- SHIELDED inline label: `SHIELDED`
- SHIELDED inline locked: `locked — tap to unlock`
- Backup banner: `Back up your recovery phrase`
- Send circle: `Send`
- Receive circle: `Receive`
- Connected apps header: `APPS BEARING YOUR SIGIL`
- Connected apps empty icon CD: `No connected apps`
- Connected apps empty line 1: `No apps connected`
- Connected apps empty line 2: `Present your sigil to a Midnight app to get started.`
- Recent activity header: `RECENT ACTIVITY`
- Recent activity link: `View all`
- Recent activity empty: `No activity yet`
- Recent activity empty detail: `Transactions and delegations will appear here.`
- Error inline: `Could not update balance`
- Retry: `Retry`
- Copied pill: `Copied`

## 9. A11Y

- Focus order: NetworkBadge → banner (if present) → hero → DUST →
  SHIELDED → Send circle → Receive circle → AddressChip →
  connected apps header → connected app rows → activity header →
  "View all" → activity rows
- Content descriptions:
  - NetworkBadge: `Current network, <name>`
  - banner: `Back up your recovery phrase. Double tap to view.`
  - hero: `<amount> NIGHT balance`
  - DUST: `<amount> DUST balance`
  - SHIELDED (locked): `Shielded balance, locked, double tap to unlock`
  - SHIELDED (value): `<amount> shielded balance`
  - AddressChip: `<segment> address, <truncated>, double tap to copy`
  - Send circle: `Send a transaction`
  - Receive circle: `Open receive screen`
  - Connected app row: `<app name>, <delegation tier> access`
  - "View all": `View all activity`
  - TxRow: `<type> transaction, <amount>, <time>`
- Dynamic type: `type-numeric-hero` may wrap at ≥200% scale.
- Reduce motion: `MaterializeEffect` snaps to end state.
- Touch targets: all interactive elements ≥ 48dp. Connected app rows
  and TxRows follow 56dp LIST ROWS rule.

## 10. VISUAL LOCKED

- Dusk palette only. No accent color for hierarchy.
- `ErrorText` not used on this screen (no amount input).
- `SuccessText` not used (no confirmation state).
- Balance hero uses `type-numeric-hero` (W200) — same prominence as
  the Assets/Balance tab. This is the user's money — never diminish it.
- Secondary tokens (DUST, SHIELDED) are compact inline on My Sigil
  (single GlassPanel row, side by side) vs full TokenRow stacks on the
  Assets/Balance tab. This keeps the dashboard tight while still showing
  all balances.
- Star-protection: GlassPanel on hero and all list sections. Same
  `contentPanel` tint rule as Balance screen.
- Connected apps empty state uses `LightFaint` icon (not `Light`) to
  signal placeholder without drawing attention.
- Every spacing value MUST be a `space-*` token.
- Every type choice MUST be a `type-*` token.
- No settings icon in top bar — Settings is its own tab now.

## 11. PRODUCT LOCKED

- **My Sigil is the start destination.** When the app opens, this tab
  is selected. Not Assets. Not Settings.
- `NetworkBadge` is always visible in the top bar.
- Backup banner is non-dismissible while
  `recovery_phrase_viewed == false`.
- Balance values come from the local UTXO cache (same source as
  Assets/Balance tab — shared BalanceRepository).
- Connected apps section ships empty in Phase 8B. Content populates
  when Connector integration ships in Phase 7+. The empty state MUST
  ship — it sets the user's expectation that apps will connect here.
- Recent activity shows the 3 most recent entries across all types.
  If the user has no transactions, the empty state shows. Agent audit
  entries appear here in Phase 7+.
- Tapping the hero balance navigates to the Assets tab for the full
  breakdown. The hero is a summary, not a replacement.
- Shielded unlock is session-scoped, not persistent.
- Bottom nav bar visible on this screen (tab root).

## 12. NEW COMPONENTS

| Component            | Shape                                                            |
|----------------------|------------------------------------------------------------------|
| `SigilStatusCard`    | Compact hero with balance + secondary tokens inline. Reuses `BalanceHero` for the numeric but wraps secondary tokens in a horizontal compact row (DUST left, SHIELDED right) instead of stacked TokenRows. This is the "at a glance" version — the Assets tab has the full breakdown. GlassPanel container, same star-protection rules. |
| `ConnectedAppRow`    | LIST ROWS rule (56dp min). Leading icon-24 (app icon or placeholder sigil-mark), space-12, label (type-body, Light) + detail line (type-caption, LightMuted showing delegation tier text), — flex —, DelegationBadge, trailing icon-16 chevron (LightMuted). Divider: 1dp LightFaint. Phase 7+: populated from Connector's connected app registry. Phase 8B: hidden (empty state shown instead). |
| `DelegationBadge`    | Pill badge showing the delegation tier. height 20dp, horizontal padding space-8, radius-full. Tier colors: Silent = bg LightBarely + text LightSoft. Notify = bg LightBarely + text Light. Approve = bg LightBarely + text Light + icon-16 lock prefix. No accent colors — tier differentiation is weight + icon only. |
| `SectionHeader`      | Full-width row. Label left (type-label-tiny, LightMuted). Optional trailing text-link right (type-caption, LightSoft). No background. space-12 below before content panel. Reusable across My Sigil sections. |

---

End of My Sigil screen spec. Ship paired dark + light frames for each
state listed above.
