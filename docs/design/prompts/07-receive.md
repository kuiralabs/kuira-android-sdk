# Screen — Receive (T1-6, QR + midnight: URI + address tabs)

## 1. GOAL

Show the user's receive address as a scannable QR code and a copyable
string. Tabs for Unshielded / Shielded. The QR encodes a `midnight:`
payment URI so any Kuira-compatible sender can scan-to-prefill.

## 2. SITEMAP POSITION

- **Tab:** Assets (push from Balance)
- `from:` Balance (Receive quick-action circle)
- `to:` Balance (back arrow) · share sheet (system) · full-screen QR
  (tap QR to expand)

## 3. STATES

| State           | Applies?  | Notes                                       |
|-----------------|-----------|---------------------------------------------|
| `default`       | ✓         | Address loaded, QR rendered                 |
| `loading-first` | ✓         | Address resolving (rare — cached almost always) |
| `syncing`       | n/a       |                                             |
| `empty`         | n/a       | Wallet always has an address                |
| `no-results`    | n/a       |                                             |
| `error`         | n/a       | Address is local — cannot fail              |
| `offline`       | n/a       | Address is local — no network needed        |
| `pending`       | n/a       |                                             |
| `success`       | n/a       |                                             |

**Variants:**

- `default / shielded-tab` — Shielded tab active, showing shielded
  address + its QR. Different address prefix (`mn_shield-addr_*`).

## 4. LAYOUT

### Layout — `default`

```
[DuskScaffold] — ambient StarField

[Top bar] 56dp · bg Void · border-bottom 1dp LightFaint
  [icon-24 back]  (Icons.AutoMirrored.Filled.ArrowBack)
  "Receive"       (type-body, Light)

space-16 (screen horizontal inset throughout)

space-32 top spacing

[Header]
  "Receive NIGHT on Preprod"    (type-headline-sm, Light, centered)
  space-4
  [NetworkBadge]                (centered — reuses Balance's NetworkBadge)

space-32

[QR panel]
  [GlassPanel — contentPanel tint, contentPadding = 24dp, centered]
    [QR code image]  — 200×200dp, white bg with dark modules
                       encodes: midnight:<current-tab-address>
                       radius-md clip on the QR container
    space-16
    [Address display]  <format-address-full> (type-mono, type-caption size,
                       LightMuted, centered, wraps naturally)

space-24

[Tab selector]  — reuses AddressChip geometry from Balance
  segment-1  "UNSHIELDED"  (active: opposite-pole fill)
  segment-2  "SHIELDED"

space-32

[Action row]  — horizontally centered, evenly spaced
  [ActionPill: Copy]
    icon-20 (Icons.Filled.ContentCopy) + "Copy" label
    tap: copy full address · haptic-tap · "Copied" toast
  [ActionPill: Share]
    icon-20 (Icons.Filled.Share) + "Share" label
    tap: system share sheet with midnight: URI as text
  [ActionPill: Full screen]
    icon-20 (Icons.Filled.Fullscreen) + "Expand" label
    tap: full-screen QR overlay (Void bg, QR centered, tap to dismiss)

space-24 above safe-area-insets.bottom
```

### Layout — `loading-first`

Same skeleton. QR area shows a shimmer block (200×200dp, `LightBarely`).
Address shows a shimmer line. Tabs and actions are visible but disabled
(`LightFaint` tint).

## 5. INTERACTIONS

| Element              | Gesture | Result                                              |
|----------------------|---------|-----------------------------------------------------|
| Back arrow           | Tap     | Pop to Balance                                      |
| Tab segment          | Tap     | Switch address + regenerate QR · `haptic-tap`       |
| QR image             | Tap     | Full-screen QR overlay                              |
| Copy pill            | Tap     | Copy full address · `haptic-tap` · `Copied` toast   |
| Share pill           | Tap     | System share sheet with `midnight:<address>` as text |
| Expand pill          | Tap     | Full-screen QR overlay (same as QR tap)             |
| Full-screen overlay  | Tap     | Dismiss back to normal view                         |

## 6. MOTION

- Entry: `MaterializeEffect` on the QR panel (`motion-emphasize`).
- Tab flip: QR crossfade + address swap — `motion-fast`.
- Full-screen overlay open: QR scales from inline position to full
  screen — `motion-standard`.
- Full-screen overlay dismiss: reverse of above — `motion-standard`.
- Reduce-motion: all of the above snap to end state.

## 7. HAPTICS

| Trigger               | Token           |
|-----------------------|-----------------|
| Tab flip              | `haptic-tap`    |
| Copy address          | `haptic-tap`    |
| Share                 | `haptic-tap`    |
| Full-screen open      | `haptic-tap`    |

## 8. COPY

Exact strings; do not rewrite.

### Labels

- Top bar title: `Receive`
- Header: `Receive NIGHT on Preprod` (network name from runtime)
- Tab labels: `UNSHIELDED` / `SHIELDED`
- Action pills: `Copy`, `Share`, `Expand`

### Toasts

- After copy: `Copied`

## 9. A11Y

- Focus order: back arrow → header → QR image → address text →
  Unshielded tab → Shielded tab → Copy → Share → Expand.
- Content descriptions:
  - back arrow: `Back to balance`
  - QR image: `QR code for your <mode> address. Tap to expand.`
  - address text: `Your <mode> address: <full address>`
  - Copy: `Copy address to clipboard`
  - Share: `Share address`
  - Expand: `Show full-screen QR code`
- Full-screen overlay: `Full-screen QR code. Tap anywhere to dismiss.`
- Touch targets: action pills 48dp minimum. Tab segments follow
  AddressChip pattern (content-driven, >48dp).
- Dynamic type: address text wraps; QR size stays fixed at 200dp.

## 10. VISUAL LOCKED

- Dusk palette only. No accent color for hierarchy or decoration.
  `ErrorText` is not used on this screen (no financial input).
- QR code renders with white bg + dark modules regardless of palette
  mode — QR scanners need high contrast. The QR container sits inside
  a GlassPanel for star-protection.
- Tab selector reuses `AddressChip` geometry from Balance (same
  opposite-pole active fill, same `LightBarely` track, same radius-md).
- Action pills: `LightBarely` bg, `radius-full`, `icon-20` +
  `type-caption` label in `Light`. Same visual weight as MAX pill on
  Send amount screen.
- Full-screen QR overlay: `Void` bg (no StarField — minimal
  distraction around the QR). QR image always renders with white
  (#FFFFFF) background and dark (#000000) modules regardless of
  palette mode — QR scanners need high contrast. Centered, no
  chrome except tap-to-dismiss.
- Every spacing value MUST be a `space-*` token.

## 11. PRODUCT LOCKED

- QR encodes a `midnight:` payment URI, NOT a raw address. Format:
  `midnight:<bech32m-address>` (no amount param on receive — Send
  screen handles amount entry).
- Tab defaults to Unshielded. Switching to Shielded changes the
  address prefix to `mn_shield-addr_*` and regenerates the QR.
- Share sends the full `midnight:<address>` URI as plain text to the
  system share sheet. The receiver (another wallet, a notes app, a
  chat) gets a URI that can be pasted into Kuira's Send flow.
- Full-screen QR is for conference/in-person handoff — the user holds
  the phone for the sender to scan.
- No amount field on the receive screen. The `midnight:` URI spec
  supports `?amount=` but the receive UI doesn't surface it in v1.0
  (per T1-6 decision).
- Network badge shows the current network (e.g., "PREPROD"). The
  address is network-specific — the QR is only valid for that network.

## 12. NEW COMPONENTS

| Component        | Shape                                                                       |
|------------------|-----------------------------------------------------------------------------|
| `ActionPill`     | Compact tappable pill for the action row. `LightBarely` bg, `radius-full`, horizontal padding `space-12`, vertical padding `space-8`. Internal: `icon-20` (`Light`) + `space-8` + label (`type-caption`, `Light`). 48dp minimum tap target. Used for Copy / Share / Expand actions. Reusable for future screens that need inline action buttons. |
| `FullScreenQR`   | Full-screen overlay composable. `Void` bg (no StarField), QR image centered at ~280dp (fills width minus space-32 inset on each side), tap-anywhere-to-dismiss. No top bar, no buttons — pure QR. Used for in-person scanning. |

**Reused components:** `NetworkBadge` (from 01-balance §12), `AddressChip`
tab geometry (from 01-balance §12 — same opposite-pole segmented control),
`GlassPanel`, `ToastPill`, `MaterializeEffect`.

---

End of Receive spec. Ship paired dark + light frames for `default`,
`default / shielded-tab`, `loading-first`, and `full-screen-qr` overlay.
