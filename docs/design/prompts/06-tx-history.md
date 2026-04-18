# Screen — Tx History + Detail (T1-5, day-grouped activity list)

## 1. GOAL

Show all wallet transactions grouped by day, with type badges
(unshielded, shielded, dust, contract), lifecycle state (pending,
confirmed, failed), and a detail drill-in per transaction with an
external explorer link.

## 2. SITEMAP POSITION

- `from:` Balance (hero tap) · Send Confirmation ("View in history") ·
  Dust (hero tap, filtered to DUST — stub: unfiltered until v1.1+)
- `to:` Tx Detail (row tap) · External explorer (detail link) ·
  Balance (back arrow)

## 3. STATES

| State           | Applies?  | Notes                                             |
|-----------------|-----------|---------------------------------------------------|
| `default`       | ✓         | Transactions present, day-grouped list             |
| `loading-first` | ✓         | First open, Room cache building from indexer       |
| `syncing`       | ✓         | Cached list visible, background refresh active     |
| `empty`         | ✓         | No transactions ever — brand-new wallet            |
| `no-results`    | n/a       | No filter in v1.0 (filter deferred to v1.1+)      |
| `error`         | ✓         | Indexer fetch failed; cached list still visible    |
| `offline`       | ✓         | Cached list visible; sync indicator hidden         |
| `pending`       | n/a       |                                                   |
| `success`       | n/a       |                                                   |

## 4. LAYOUT

### Layout — `default`

```
[DuskScaffold] — ambient StarField

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]  (Icons.AutoMirrored.Filled.ArrowBack)
  "Activity"      (type-body, Light)

space-16 (screen horizontal inset throughout)

space-16 top spacing

[Day group: TODAY]
  SettingsSectionHeader "TODAY"
  space-12
  [GlassPanel — contentPanel tint, contentPadding = 0.dp]
    TxRow
      Line 1:  [Type badge] "SENT"                  — flex —   "−5.5 NIGHT" (type-body, Light)
      Line 2:  "To mn_addr…f5a2" (type-caption, LightMuted)  — flex —   "Confirmed" (type-caption, LightMuted)
      trailing  icon-16 chevron
    1dp LightFaint divider
    TxRow
      Line 1:  [Type badge] "RECEIVED"               — flex —   "+10.0 NIGHT"
      Line 2:  "From mn_addr…b3c1"                   — flex —   "Confirmed"
      trailing  icon-16 chevron

space-32

[Day group: YESTERDAY]
  SettingsSectionHeader "YESTERDAY"
  space-12
  [GlassPanel]
    TxRow
      [Type badge]    "DUST"
      [Amount]        "+0.000001 DUST"
      [Detail]        "Generation"
      [Status]        "Confirmed"
      trailing        icon-16 chevron

…(more day groups, scrollable)

space-24 above safe-area-insets.bottom
```

### Layout — `empty`

```
[Top bar] — same as default

space-48 top spacing

[GlassPanel — centered content, contentPadding = 24dp]
  icon-32        (Icons.Filled.History, LightMuted, centered)
  space-20
  headline       "No activity yet"      (type-headline-sm, Light, centered)
  space-8
  detail         "Send or receive NIGHT to see your
                  transactions here."    (type-detail, LightMuted, centered)

space-24 above safe-area-insets.bottom
```

### Layout — `loading-first`

Same as `default` skeleton. Day group headers show shimmer blocks.
TxRows replaced with 3 shimmer rows (56dp each, `LightBarely` blocks).

### Layout — `syncing`

Same as `default`. Top bar gains a subtle pulsing dot (`LightSoft`,
`motion-fast`) inline after the title: `"Activity ·"`.

### Layout — `error`

Same as `default` (cached list visible). Below the last day group:

```
space-24
[Inline error row]
  "Could not refresh"      (type-detail, LightMuted)
  — flex —
  "Retry"                  (type-detail, Light)
```

### Layout — `offline`

Same as `default`. Top bar title changes to `"Activity · Offline"`.

### Tx Detail (drill-in from row tap)

```
[Top bar] 56dp
  [icon-24 back]
  "Transaction"    (type-body, Light)

space-16 (screen horizontal inset throughout)

space-32 top spacing

[GlassPanel — hero, contentPadding = 24dp]
  [Amount hero]    "−5.5" or "+10.0"  (type-numeric-hero, Light, centered)
  space-4
  [Denomination]   "NIGHT"            (type-headline-sm, LightMuted, centered)
  space-8
  [Type badge]     pill               (centered, same as list)
  space-8
  [Status badge]   "Confirmed"        (type-caption, LightMuted, centered)

space-32

SettingsSectionHeader "DETAILS"
space-12

[GlassPanel — contentPadding = 0.dp]
  SettingsRow (readOnly = true)
    label       "Date"
    rightValue  <format-time-absolute>
  divider
  SettingsRow (readOnly = true)
    label       "Block"
    rightValue  "<block height>"
  divider
  SettingsRow (readOnly = true, rightValueMono = true,
               trailingIcon = Icons.Filled.ContentCopy)
    label       "Hash"
    rightValue  <format-hash-short>
    onClick     copy full hash · haptic-tap · "Copied" toast
  divider
  SettingsRow (readOnly = true, rightValueMono = true,
               trailingIcon = Icons.Filled.ContentCopy)
    label       "From"
    rightValue  <format-address-short>
    onClick     copy full address
  divider
  SettingsRow (readOnly = true, rightValueMono = true,
               trailingIcon = Icons.Filled.ContentCopy)
    label       "To"
    rightValue  <format-address-short>
    onClick     copy full address
  divider
  SettingsRow (readOnly = true)
    label       "Fee"
    rightValue  "<format-amount-night> NIGHT"

space-32

[Explorer link]
  SettingsRow (default — nav row with chevron)
    label       "View on explorer"
    onClick     open browser: https://<network>.midnightexplorer.com/tx/<hash>

space-24 above safe-area-insets.bottom
```

## 5. INTERACTIONS

### History list

| Element        | Gesture | Result                                          |
|----------------|---------|--------------------------------------------------|
| Back arrow     | Tap     | Pop to Balance                                   |
| TxRow          | Tap     | Navigate to Tx Detail for that transaction       |
| Error "Retry"  | Tap     | Trigger indexer re-fetch                         |

### Tx Detail

| Element            | Gesture | Result                                      |
|--------------------|---------|-----------------------------------------------|
| Back arrow         | Tap     | Pop to History list                           |
| Hash row           | Tap     | Copy full hash · `Copied` toast · `haptic-tap` |
| From row           | Tap     | Copy full address · `Copied` toast            |
| To row             | Tap     | Copy full address · `Copied` toast            |
| View on explorer   | Tap     | Open external browser with explorer URL       |

## 6. MOTION

- Entry: `MaterializeEffect` on the first day-group header
  (`motion-emphasize`).
- List scroll: standard Android overscroll.
- Row tap → Tx Detail: `motion-standard` (nav push).
- Skeleton → real content: crossfade (`motion-standard`).
- Syncing dot: `motion-fast` alpha pulse.
- Reduce-motion: all snap to end state.

## 7. HAPTICS

| Trigger                    | Token           |
|----------------------------|-----------------|
| Row tap (drill-in)         | `haptic-tap`    |
| Copy (hash, address)       | `haptic-tap`    |
| Error "Retry"              | `haptic-tap`    |

## 8. COPY

Exact strings; do not rewrite.

### Labels

- Top bar title (list): `Activity`
- Top bar title (syncing): `Activity ·` (with pulsing dot)
- Top bar title (offline): `Activity · Offline`
- Top bar title (detail): `Transaction`
- Day group headers: `TODAY`, `YESTERDAY`, or date (`Apr 15`)
- Type badges: `SENT`, `RECEIVED`, `SHIELDED`, `DUST`, `CONTRACT`
- Status: `Pending`, `Confirmed`, `Failed`
- Amount prefix: `−` (sent) / `+` (received)
- Detail section header: `DETAILS`
- Detail row labels: `Date`, `Block`, `Hash`, `From`, `To`, `Fee`
- Explorer row: `View on explorer`

### Empty state

- Headline: `No activity yet`
- Detail: `Send or receive NIGHT to see your transactions here.`

### Error inline

- Text: `Could not refresh`
- Action: `Retry`

### Toasts

- After copy: `Copied`

## 9. A11Y

### History list

- Focus order: back arrow → day-group header → TxRow (top to bottom,
  within each group) → next group.
- TxRow CD: `<type> <amount> NIGHT, <status>. To <address short>.`
- Error row: focusable, announces text + "Retry" action.

### Tx Detail

- Focus order: back arrow → amount → type badge → status → Date →
  Block → Hash → From → To → Fee → View on explorer.
- Copy rows: `Tap to copy <field>`
- Explorer: `Open in browser`

### All

- Touch targets: TxRows follow LIST ROWS 56dp minimum. Detail rows
  are SettingsRow (56dp minimum).
- Dynamic type: amount + denomination scale; day headers scale.
- Reduce-motion: all transitions snap.

## 10. VISUAL LOCKED

- Dusk palette only. `ErrorText` is not used on this screen. Failed
  transactions are indicated by `"Failed"` status text in `LightMuted`
  — not by red. The user can't act on a failure from history (they
  already missed it); red is reserved for preventable danger.
- Type badges use `LightBarely` bg + `LightSoft` text (same pill
  pattern as NetworkBadge and mode badges on Send).
- Amount hero on Tx Detail sits in a `GlassPanel` for star-protection.
- Day-group sections follow the sectioned-list rhythm from `_prefix.md`
  LIST ROWS (space-12 header→panel, space-32 inter-section).
- Sent amounts use `−` prefix, received use `+`. No color coding —
  the sign IS the signal.
- Every spacing value MUST be a `space-*` token.

## 11. PRODUCT LOCKED

- Data source is local Room-backed cache, backfilled from the indexer
  (per T1-5 decision). Offline viewing works from cache.
- All transaction types shown with badges: unshielded, shielded, dust,
  contract (per T1-5 decision). The badges make Kuira a reference
  wallet that surfaces what Midnight actually does.
- Three lifecycle states: Pending (optimistic, just submitted),
  Confirmed (on-chain), Failed (rejected or reverted). All three
  shown — hiding failures breaks trust.
- Explorer URL pattern: `https://<network>.midnightexplorer.com/tx/<hash>`.
  Network-specific prefix (preprod / preview). Exact path (`/tx/`
  vs `/transactions/`) to be confirmed against the live site during
  implementation.
- No filter / search in v1.0 (deferred to v1.1+).
- Tx Detail shows actual fee paid (not the estimate from Confirmation).

## 12. NEW COMPONENTS

| Component    | Shape                                                                          |
|--------------|--------------------------------------------------------------------------------|
| `TxRow`      | Full-width row inside a day-group GlassPanel. 56dp minimum (LIST ROWS). Two-line layout (standard wallet pattern): Line 1 = type badge pill (left) + amount (right, `type-body`, `Light`); Line 2 = address/detail (left, `type-caption`, `LightMuted`) + status (right, `type-caption`, `LightMuted`). Trailing icon-16 chevron. Two content lines + padding = ~72dp natural height. Tappable — navigates to Tx Detail. |
| `TxTypeBadge`| Pill component: `LightBarely` bg, `radius-full`, horizontal padding `space-8`, vertical padding `space-4`. Label in `type-label-tiny`, `LightSoft`. Values: SENT / RECEIVED / SHIELDED / DUST / CONTRACT. Same geometry as `NetworkBadge` and mode badges. |

**Reused components:** `SettingsRow` (detail rows with copy icons),
`SettingsSectionHeader` (day group headers), `GlassPanel`,
`NetworkBadge` (geometry reference), `ToastPill`, `MaterializeEffect`.

---

End of Tx History + Detail spec. Ship paired dark + light frames for
`default`, `loading-first`, `syncing`, `empty`, `error`, `offline`,
and the Tx Detail drill-in.
