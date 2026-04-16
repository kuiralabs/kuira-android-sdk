# Sitemap — Kuira Wallet v1.0

ASCII graph of every screen and how they connect. Used by screen
prompts under `prompts/` — each prompt's **SITEMAP POSITION** section
must match this graph exactly.

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
        ┌────────────┐           ┌─────────────┐
        │ Onboarding │           │  Balance    │ ◄──────── app resume
        │            │           │  (home)     │
        │  Welcome   │           └──┬──┬──┬──┬─┘
        │    │       │              │  │  │  │
        │    ├──Create              │  │  │  │
        │    │   └─► AuthSetup      │  │  │  │
        │    │       └─► Create     │  │  │  │
        │    │           ├─► View   │  │  │  │
        │    │           │  Recovery│  │  │  │
        │    │           │  phrase ─┼──┼──┼──┼──┐
        │    │           │          │  │  │  │  │
        │    │           └─► Balance│  │  │  │  │
        │    │                      │  │  │  │  │
        │    └──Restore             │  │  │  │  │
        │        └─► Balance        │  │  │  │  │
        └────────────┘              │  │  │  │  │
                                    │  │  │  │  │
                        ┌───────────┘  │  │  │  │
                        │              │  │  │  │
                        ▼              │  │  │  │
                   ┌────────┐          │  │  │  │
                   │  Send  │          │  │  │  │
                   └───┬────┘          │  │  │  │
                       ▼               │  │  │  │
                ┌───────────────┐      │  │  │  │
                │ Send Confirm  │      │  │  │  │
                └───────┬───────┘      │  │  │  │
                        ▼              │  │  │  │
                ┌───────────────┐      │  │  │  │
                │ Success Card  │──────┼──┼──┼──┼──► External explorer
                └───┬───────┬───┘      │  │  │  │   (browser)
                    │       │          │  │  │  │
          "Send another"   "View in    │  │  │  │
                    │     history"     │  │  │  │
                    │       │          │  │  │  │
                    └──┐    │          │  │  │  │
                       │    │          │  │  │  │
                       ▼    ▼          │  │  │  │
                    (Send) (Tx Detail) │  │  │  │
                                       │  │  │  │
                        ┌──────────────┘  │  │  │
                        ▼                 │  │  │
                  ┌──────────┐            │  │  │
                  │ Receive  │            │  │  │
                  └────┬─────┘            │  │  │
                       ▼                  │  │  │
                 ┌─────────────┐          │  │  │
                 │ Full-screen │          │  │  │
                 │   QR sheet  │          │  │  │
                 └─────────────┘          │  │  │
                                          │  │  │
                        ┌─────────────────┘  │  │
                        ▼                    │  │
                  ┌────────────┐              │  │
                  │ Tx History │              │  │
                  └─────┬──────┘              │  │
                        ▼                    │  │
                  ┌────────────┐             │  │
                  │ Tx Detail  │─────────────┼──┼──► External explorer
                  └────────────┘             │  │    (browser)
                                             │  │
                                 ┌───────────┘  │
                                 ▼              │
                             ┌──────┐           │
                             │ Dust │           │
                             └──┬───┘           │
                                │               │
                    ┌───────────┴───────────┐   │
                    ▼                       ▼   │
              "Registered"          "Unregistered"
                    │                       │   │
                    │                       ▼   │
                    │              ┌─────────────────┐
                    │              │ Registration    │
                    │              │ progress        │
                    │              │ PROVING →       │
                    │              │ SEALING →       │
                    │              │ SUBMITTING      │
                    │              └────────┬────────┘
                    │                       ▼
                    │              "Registered" (same state)
                    │                       │
                    └───────────────────────┘

                               ┌─────────────────┐
                 From Balance ─►│    Settings    │◄─ tap 7× on version
                 top bar icon   │                │   toggles Developer
                               └────────┬────────┘
                                        │
             ┌───────────┬───────────────┼───────────────┬─────────────┐
             ▼           ▼               ▼               ▼             ▼
         NETWORK     DEVELOPER        SECURITY         ABOUT       (back)
             │           │               │               │
        ┌────┴───┐   ┌───┴────┐    ┌────┴─────┐    ┌────┴────┐
        │ Row    │   │ Proof  │    │ View     │    │ Version │
        │ + picker│   │ server │    │ phrase ──┼───►┤ Commit  │
        │ sheet   │   │ (deferred)  │          │    │ License │
        │(dev-    │   │ Force  │    │ Bio test │    │ GitHub  │
        │ mode    │   │ resync │    │          │    │ Support │
        │ only)   │   │ Build  │    │ Wipe ────┼───►┤         │
        └────────┘   │ info   │    │ wallet   │    └─────────┘
                     └────────┘    └──────────┘
                                         │
                                         ▼
                                   ┌────────────┐
                                   │ Wipe flow  │
                                   │ 1. Biometric│
                                   │ 2. Sheet    │
                                   │ 3. Type WIPE│
                                   │ 4. Confirm  │
                                   └────────────┘

# Shared across all screens

 - NetworkBadge: visible in every top bar (except Splash, Onboarding Welcome)
 - BackupBanner: conditionally on Balance until recovery_phrase_viewed == true
 - ToastPill: any copy / haptic confirmation anywhere
 - OS biometric prompt: intercepts before View phrase, Wipe, any Send confirm

# Deep links / intent filters

 - `midnight:<address>[?amount=…&…]` → routes to Send screen, prefilled
 - `midnight://connect?networkId=…&callback=…` → routes to Phase 5 connector
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

## Screens explicitly NOT in v1.0

For the record, so an AI agent or a future reader doesn't hallucinate
them into existence:

- Address book / contacts
- Token portfolio beyond NIGHT + DUST + shielded
- Fiat display / currency conversion
- Staking / rewards UI
- NFT gallery
- DApp browser
- Cross-chain bridging
- WalletConnect pairing screen (Phase 5 MCP bridge is different)
