# Kuira Wallet — Design documentation

Index for everything under `docs/design/`. This is the human-facing
entry point. AI agents consume `STANDARDS.md` + one `prompts/<screen>.md`
at a time.

---

## Files

| Path                            | Purpose                                       |
|---------------------------------|-----------------------------------------------|
| `README.md`                     | This index                                    |
| `STANDARDS.md`                  | Master reference: tokens, scales, rules       |
| `SITEMAP.md`                    | ASCII screen graph                            |
| `prompts/_prefix.md`            | Role + schema; paste first in every AI session |
| `prompts/01-balance.md`         | Balance screen spec                           |
| `prompts/02-send.md`            | Send compose screen spec                      |
| `prompts/03-send-confirmation.md` | Send confirmation screen spec              |
| `prompts/04-dust.md`            | Dust screen spec                              |
| `prompts/05-settings.md`        | Settings screen spec                          |
| `prompts/06-tx-history.md`      | Tx History + Detail pair spec                 |
| `prompts/07-receive.md`         | Receive screen spec                           |
| `prompts/08-recovery-phrase.md` | Recovery phrase view spec                     |
| `prompts/09-onboarding-pass.md` | Onboarding visual-consistency audit spec      |
| `prompts/10-icon.md`            | App icon concepts spec                        |
| `prompts/11-splash.md`          | Splash animation storyboard spec              |
| `wireframes/<screen>/<mode>-<state>.png` | AI-produced mocks land here          |

---

## Workflow

1. Pick the next unapproved screen from the status table below.
2. In your AI design tool (Figma AI / Galileo / v0 / Claude / etc):
   a. Paste `prompts/_prefix.md`.
   b. Paste the screen's `prompts/NN-<screen>.md`.
   c. Generate. Review against the section schema.
3. If output deviates, tighten the prompt (almost always in LOCKED),
   not the mock.
4. Save paired dark + light PNGs per state under
   `wireframes/<screen>/<mode>-<state>.png`.
5. Flip the screen's status below from `📝 Stub` → `🎨 Drafting` →
   `✅ IA approved`.
6. When all rows are ✅, 8B.1 is done. 8B.3 Compose work may begin
   per the 8B.3 sequencing table in `WALLET_PRODUCTIZATION_PLAN.md`.

## Screen status

| Status legend | Meaning                                   |
|---------------|-------------------------------------------|
| `📝 Stub`      | Spec written; no wireframes yet           |
| `🎨 Drafting`  | AI wireframes in iteration                |
| `✅ IA approved` | Ready for Compose implementation         |
| `🏗 Implementing` | Compose work in flight                  |
| `✔ Shipped`   | On `main`, L3 applied                     |

| # | Screen                   | Status        | Notes                                                   |
|---|--------------------------|---------------|---------------------------------------------------------|
| 01 | Balance                  | 🎨 Drafting   | Canonical example — review shape before doing others    |
| 02 | Send                     | 📝 Pending     | Not yet written                                         |
| 03 | Send Confirmation        | 📝 Pending     |                                                         |
| 04 | Dust                     | 📝 Pending     |                                                         |
| 05 | Settings                 | 📝 Pending     | Hosts Network picker, Wipe flow sub-frames              |
| 06 | Tx History + Detail      | 📝 Pending     | Pair                                                    |
| 07 | Receive                  | 📝 Pending     | Includes Full-screen QR sub-frame                       |
| 08 | Recovery phrase          | 📝 Pending     |                                                         |
| 09 | Onboarding visual pass   | 📝 Pending     | Audit, no new screens                                   |
| 10 | App icon                 | 📝 Pending     | Asset generation, not a screen                          |
| 11 | Splash animation         | 📝 Pending     | Storyboard                                              |

---

## Review / sign-off

A screen flips to `✅ IA approved` when:

- Paired dark + light PNG exists for every state listed in the prompt's
  STATES section
- Every VISUAL LOCKED and PRODUCT LOCKED constraint from the prompt is
  visibly satisfied in the wireframe
- Every NEW COMPONENT listed in the prompt has at least one instance
  rendered in a frame
- Accessibility notes in A11Y are not contradicted by the layout
  (e.g., no content crammed below 48dp minimum)
- The file names under `wireframes/<screen>/` follow
  `<mode>-<state>.png`

---

## Light-mode code gate (blocks 8B.3 light-mode implementation)

`Theme.kt` currently uses Material's `Purple40 / Pink40` for
`LightColorScheme`. Before any screen's light-mode wireframe can be
implemented in Compose:

1. Introduce semantic aliases in `core:designsystem` (`Surface`,
   `OnSurface`, `OnSurfaceSoft`, `OnSurfaceMuted`, `OnSurfaceFaint`,
   `OnSurfaceBarely`, `SurfaceElevated`, `Accent`, `OnAccent`,
   `AmbientStarBright`, `AmbientStarDim`).
2. Populate `DuskLight` object with the values in `STANDARDS.md` §2.
3. Rewrite `LightColorScheme` against the aliases.
4. Migrate `OnboardingScreen` direct-token references to aliases.

~4-6h. Independent of the wireframe sprint — can run in parallel.

---

## Follow-up: the old `IA_SPECS.md`

The single-file `IA_SPECS.md` has been replaced by this structure.
It is being removed in the same commit that introduces these files.
The content is redistributed:

- Design principles + Dusk inventory → `STANDARDS.md`
- Screen stubs → `prompts/NN-<screen>.md`
- Prompt prefix → `prompts/_prefix.md`
- Status tracking → this README
- Follow-up code task → this README + `STANDARDS.md §16`
