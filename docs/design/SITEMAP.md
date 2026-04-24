# Sitemap — Kuira v1.0

ASCII graph of every screen and how they connect. Used by screen
prompts under `prompts/` — each prompt's **SITEMAP POSITION** section
must match this graph exactly.

**Product framing:** Kuira is a Sigil — a private digital identity
that authenticates, delegates, and protects across all Midnight apps.
The app manages one sigil with multiple HD-derived accounts. See
`docs/planning/KUIRA_IDENTITY_VISION.md` for the full vision.

---

```
                       ┌─────────┐
                       │  Launch │
                       └────┬────┘
                            │
                            ▼
                       ┌─────────┐
                       │ Splash  │ ≤ 800 ms
                       └────┬────┘
                            │
                 ┌──────────┴──────────┐
                 │ WalletGate decision │
                 └──────────┬──────────┘
                            │
                ┌───────────┴────────────┐
                │                        │
                ▼                        ▼
        ┌────────────┐           ┌──────────────┐
        │ Onboarding │           │  Main (tabs) │ ◄── app resume
        │            │           └──────┬───────┘
        │  Welcome   │                  │
        │    │       │      ┌───────────┼───────────┬──────────┐
        │    ├──Create      │           │           │          │
        │    │   └─► Auth   │           │           │          │
        │    │       └─► Forge          │           │          │
        │    │           ├─► View       │           │          │
        │    │           │  Recovery    │           │          │
        │    │           │  phrase      │           │          │
        │    │           │              │           │          │
        │    │           └─► Main       │           │          │
        │    │                          │           │          │
        │    └──Restore                 │           │          │
        │        └─► Main               │           │          │
        └────────────┘                  │           │          │
                                        │           │          │
        ┌───────────────────────────────┘           │          │
        │                                           │          │
        ▼                                           │          │
  ══════════════════════════════════════════════════════════════════
  ║  TAB 1          TAB 2          TAB 3          TAB 4        ║
  ║  My Sigil       Assets         Activity       Settings     ║
  ║  (start)                                                   ║
  ══════════════════════════════════════════════════════════════════
        │               │              │              │
        ▼               ▼              ▼              ▼
  ┌───────────┐   ┌──────────┐  ┌───────────┐  ┌──────────────┐
  │ My Sigil  │   │ Balance  │  │ Activity  │  │  Settings    │
  │           │   │ (Assets  │  │ (Tx list) │  │              │
  │ Connected │   │  root)   │  │           │  │              │
  │ apps list │   └──┬──┬──┬─┘  └─────┬─────┘  └───────┬──────┘
  │           │      │  │  │          │                 │
  │ Sigil     │      │  │  │          │          ┌──────┼──────┐
  │ status    │      │  │  │          │          │      │      │
  └─────┬─────┘      │  │  │          ▼          │      │      │
        │            │  │  │    ┌──────────┐     │      │      │
        ▼            │  │  │    │ Tx Detail│►ext │      │      │
  ┌───────────┐      │  │  │    └──────────┘     │      │      │
  │ App detail│      │  │  │                     ▼      │      │
  │           │      │  │  │              ┌──────────┐  │      │
  │ State     │      │  │  │              │ NETWORK  │  │      │
  │ Policies  │      │  │  │              │ picker   │  │      │
  │ Delegation│      │  │  │              │ sheet    │  │      │
  └───────────┘      │  │  │              └──────────┘  │      │
                     │  │  │                            │      │
         ┌───────────┘  │  │                            ▼      │
         │              │  │                     ┌──────────┐  │
         ▼              │  │                     │ SECURITY │  │
    ┌────────┐          │  │                     │          │  │
    │  Send  │          │  │                     │ View     │  │
    └───┬────┘          │  │                     │ phrase ──┼──┼──►
        ▼               │  │                     │          │  │  Recovery
 ┌───────────────┐      │  │                     │ Bio test │  │  phrase
 │ Send Confirm  │      │  │                     │          │  │  view
 └───────┬───────┘      │  │                     │ Wipe ────┼──┼──►
         ▼              │  │                     │ wallet   │  │  Wipe flow
 ┌───────────────┐      │  │                     └──────────┘  │
 │ Success Card  │──────┼──┼──► External explorer              │
 └───┬───────┬───┘      │  │   (browser)                       │
     │       │          │  │                                    ▼
  "Send     "View in    │  │                            ┌──────────┐
  another"  history"    │  │                            │ DEVELOPER│
     │       │          │  │                            │          │
     └──┐    │          │  │                            │ Proof    │
        │    │          │  │                            │ server   │
        ▼    ▼          │  │                            │ Force    │
     (Send) (Tx Detail) │  │                            │ resync   │
                        │  │                            │ Build    │
         ┌──────────────┘  │                            │ info     │
         ▼                 │                            └──────────┘
   ┌──────────┐            │
   │ Receive  │            │
   └────┬─────┘            │
        ▼                  │
  ┌─────────────┐          │
  │ Full-screen │          │
  │   QR sheet  │          │
  └─────────────┘          │
                           │
              ┌────────────┘
              ▼
        ┌────────────┐
        │    Dust    │
        └──┬─────────┘
           │
   ┌───────┴───────────┐
   ▼                   ▼
"Registered"    "Unregistered"
   │                   │
   │                   ▼
   │          ┌─────────────────┐
   │          │ Registration    │
   │          │ progress        │
   │          │ PROVING →       │
   │          │ SEALING →       │
   │          │ SUBMITTING      │
   │          └────────┬────────┘
   │                   ▼
   │          "Registered" (same state)
   │                   │
   └───────────────────┘


                  ┌──────────────────┐
                  │    Wipe flow     │
                  │ 1. Biometric     │
                  │ 2. Sheet         │
                  │ 3. Type WIPE     │
                  │ 4. Confirm       │
                  │ → Onboarding     │
                  └──────────────────┘


# Shared across all screens

 - NetworkBadge: visible in every top bar (except Splash, Onboarding Welcome)
 - BackupBanner: conditionally on Balance until recovery_phrase_viewed == true
 - ToastPill: any copy / haptic confirmation anywhere
 - BottomNavBar: persistent on all 4 tab roots (My Sigil, Balance, Activity, Settings)
   Hidden during pushed sub-screens (Send, Tx Detail, etc.)
 - OS biometric prompt: intercepts before View phrase, Wipe, any Send confirm

# Bottom navigation bar

 - 4 tabs: My Sigil | Assets | Activity | Settings
 - My Sigil is the start destination (home)
 - Each tab maintains its own back stack (independent)
 - Bar is visible on tab root screens only
 - Bar hides when navigating into sub-screens (Send, Tx Detail, etc.)
 - Tab icons: sigil mark (My Sigil), stack/coins (Assets),
   clock/list (Activity), gear (Settings)
 - Active tab: Light (100%). Inactive tabs: LightMuted (50%)
 - Bar background: VoidSoft with 1dp LightFaint top border
 - Bar height: 56dp (matches top bar for visual balance)

# Deep links / intent filters

 - `midnight:<address>[?amount=…&…]` → routes to Send screen (Assets tab), prefilled
 - `midnight://connect?networkId=…&callback=…` → routes to Connector approval
   (Phase 5, then My Sigil tab in future)
 - Both handled by MainActivity; dispatch by URI shape
   (host present → action URI; host absent → payment URI).
```

## Reading the graph

- **Solid arrows** = direct navigation (tap opens the next screen)
- **Indentation under a screen** = sub-destinations reachable from within it
- **Dashed edges** (none in this v1.0) would be "soft" routes (e.g.,
  deep-link from outside the app)
- A screen's **SITEMAP POSITION** section in `prompts/<screen>.md`
  must list: `from:` (every inbound edge) and `to:` (every outbound edge)
- **Tab-to-tab** navigation is always via the bottom nav bar, never
  via push navigation

## Tab assignment

Every screen belongs to exactly one tab. Sub-screens push within
their tab's back stack.

| Tab | Root screen | Sub-screens |
|-----|-------------|-------------|
| My Sigil | My Sigil dashboard | App detail, State browser, Policy editor |
| Assets | Balance | Send, Send Confirm, Success, Receive, QR sheet, Dust, Dust registration |
| Activity | Tx History list | Tx Detail |
| Settings | Settings | Recovery phrase view, Wipe flow |

## Screens explicitly NOT in v1.0

For the record, so an AI agent or a future reader doesn't hallucinate
them into existence:

- Address book / contacts
- Token portfolio beyond NIGHT + DUST + shielded
- Fiat display / currency conversion (USD hint on Send only)
- Staking / rewards UI
- NFT gallery
- DApp browser
- Cross-chain bridging
- WalletConnect pairing screen (Phase 5 MCP bridge is different)

## Implementation phasing

The 4-tab structure is the target architecture. Implementation is
phased — not everything ships at once:

| Phase | Tab state |
|-------|-----------|
| 8B (current) | Assets + Settings tabs functional. My Sigil shows sigil status + placeholder. Activity shows tx history. |
| 8C | Assets complete. Activity adds sync events. |
| 7 v1.1 | My Sigil populates: connected apps, delegation policies, agent audit. |
| 9+ | My Sigil full: state browser, per-app policies UI, sigil dashboard. |

The bottom nav bar ships in 8B with all 4 tabs. My Sigil starts
minimal and fills out as features land in later phases.
