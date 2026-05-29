# Kuira SDK — Alpha 02 Plan

**Goal:** lift `0.1.0-alpha01` from "the binary lands on Central" to "a
third-party Android developer can discover, integrate, and verify the
SDK end-to-end without talking to the maintainer."

`alpha01` proved the publishing infrastructure works. `alpha02` is about
**developer experience and discoverability** — turning the published
artifacts from a private project's binaries into a credible, navigable,
verifiable open product.

---

## What "ready for `alpha02`" means — the acceptance gate

A clean consumer on a fresh machine who has never seen this repo can:

1. Google "Midnight Android SDK" → land on a real docs page (not GitHub's
   private-repo 404).
2. Read a rendered API reference with KDoc-derived signatures and types.
3. Find a `Contract.kt` Gradle plugin invocation in INTEGRATION.md that
   replaces the hand-rolled `syncContractAssets` Copy task.
4. Open the IDE on their own project and have the published APIs surface
   with stability annotations (`@ExperimentalKuiraApi`) where appropriate.
5. Verify the Maven Central artifact signature against a fingerprint
   they can find in `SECURITY.md`.

If those five steps round-trip cleanly, `alpha02` ships.

---

## Status carried in from `alpha01`

| Done | What it gives consumers |
|---|---|
| Maven Central pipeline | Tag → CI → staged bundle → manual Publish → live |
| One-line consumer dep | `implementation("io.github.kuiralabs:dapp-ui:VERSION")` |
| Host-provided `PasskeyConfig` (#22) | No maintainer-default coupling |
| BBoard acceptance gate in CI | Proof the umbrella resolves cleanly |
| `SECURITY.md` (draft, awaiting fingerprint) | Threat model + reporting + disclosure |
| `INTEGRATION.md`, `RELEASE.md` | Consumer + maintainer recipes |
| BLS-params documented as known limitation | Honest deferral, not hidden |

The publishing infrastructure does not need touching for `alpha02`. The
work is everything *around* the bundle.

---

## Workstreams

### A. Docs & discoverability

**Architectural decision driving this workstream:** the SDK's public
face does NOT live inside this monorepo. This repository stays fully
private for the foreseeable future; the SDK's consumer-facing docs
(INTEGRATION.md, README, future Dokka HTML) ship from a separate
public repo. That host repo needs to be chosen — see *Open question*
below.

- [ ] **Pick the public docs host repo.** Options on the table:
  (a) `nel349/nel349.github.io` — already live at `nel349.github.io`,
      currently serves `assetlinks.json` for the SDK's default rpId;
  (b) a new `kuiralabs/kuira-sdk` repo — clean kuiralabs branding,
      no personal-site mixing;
  (c) GitHub Pages on this monorepo's `gh-pages` branch — possible
      with a paid plan that supports Pages on private repos.
- [ ] **Repo-root `README.md` in THIS monorepo.** Orients maintainers:
  "this is the SDK + wallet monorepo; SDK source lives in
  `sdk/*` and `core/*`; consumer-facing docs live at <public site>."
  Internal-facing, since the monorepo is private — different audience
  from the public README that ships in the docs host repo.
- [ ] **Migrate `INTEGRATION.md` to the public docs host** once chosen.
  Single source of truth; this monorepo links out to it.
- [ ] **Wire Dokka into the publish flow.** Turns the empty
  `javadoc.jar` into a rendered API reference derived from KDoc.
  Dokka HTML output deploys to the public docs host on every tag.
- [ ] **Public README on the docs host repo.** Consumer-facing
  first-impression doc: what Kuira is, Maven coordinates, alpha
  status, link to INTEGRATION.md and Dokka site.
- [ ] **Fix POM `url` + `scm.url`.** Currently points at this private
  repo and 404s for any consumer who clicks through from Central.
  Point at the public docs host URL once it exists.

### B. Developer Experience

- [ ] **Contract Gradle plugin (#11).** Replaces the hand-rolled
  `syncContractAssets` Copy task each dApp re-implements. Declarative
  `kuiraContract { source = "..." }`.
- [ ] **Test-seed path (#8).** Deterministic seed entry-point so
  consumers can write JVM unit tests without going through passkey or
  sigil. Single opt-in.
- [ ] **Typed witness factories (#12).** Helpers for `Vector<N, T>`,
  `Bytes<32>`, etc. so consumers stop hand-packing bytes from the
  runtime's serialization layout.
- [ ] **Domain-specific error hierarchy.** Replace `RuntimeException("…")`
  with a sealed `KuiraException` tree (`WalletException`,
  `ContractException`, `ProvingException`, …) so consumers can `catch`
  by type instead of substring-matching messages.

### C. API stability & versioning

- [ ] **`@ExperimentalKuiraApi` opt-in annotation.** Marks unstable
  surface explicitly; consumers must opt in to use it.
- [ ] **`binary-compatibility-validator`.** Kotlinx tool produces
  `api/*.api` files locked into the repo. Breaking changes surface in
  PR diffs, not in consumer crash logs.
- [ ] **Versioning + deprecation policy.** Public commitment to semver
  + how long deprecated APIs survive. Lives in `RELEASE.md` or a new
  `STABILITY.md`.
- [ ] **Public-surface audit.** Mark internals `internal` where
  appropriate, especially across `core:*`. Anything `public` is contract.

### D. Quality & confidence

- [ ] **`.github/dependabot.yml`.** Automated dep-bump PRs + known-CVE
  scanning. Cheap, high-signal.
- [ ] **SBOM generation.** CycloneDX SBOM per release, attached to
  GitHub Releases. Required by some enterprise consumers.
- [ ] **Kicks as second acceptance gate in CI.** Currently only BBoard
  builds against the alpha. Adding Kicks broadens coverage.
- [ ] **Test coverage report.** Jacoco wired up; report deploys to
  GitHub Pages alongside the docs.

### E. Brand & public face

- [ ] **Backfill `v0.1.0-alpha01` GitHub Release entry.** The tag was
  pushed but the Release page wasn't created. The Release page is what
  feeds, dependency bots, and consumers actually surface.
- [ ] **`kuiralabs` org GitHub profile README.** Pitch + projects +
  Maven coordinates visible at `github.com/kuiralabs`.
- [ ] **`ROADMAP.md` at repo root.** Extract the alpha-plan / wishlist
  priorities into a public-facing roadmap. "Here's what's coming"
  builds trust.
- [ ] **Name + tagline** in the public README. "Kuira: Android SDK for
  Midnight dApps" or similar — distinct from "Kuira Wallet" (the app).

### F. Security & trust continuation

- [ ] **Fill the GPG fingerprint** in `SECURITY.md` (the
  `Verifying releases` section).
- [ ] **Confirm `LICENSE` at repo root matches the POM declaration**
  (Apache-2.0). If absent, add.
- [ ] **`CONTRIBUTING.md`** — set expectations explicitly. "PRs not yet
  accepted; will open for community contribution in alphaXX" is a fine
  answer for now.

### G. Agent-mode developer experience

**Vision:** the docs site is also the **agent surface**. A developer
landing on the cookbook picks a recipe, hits a "copy prompt for your
agent" button, and pastes into Claude Code / Cursor / Codex / whichever
LLM-assisted tool they use. The agent fetches a stable raw-markdown
context bundle from the same site and has everything it needs to execute
the integration in the dev's own project. A site-root `llms.txt`
makes the whole cookbook auto-discoverable by any LLM agent that
supports the emerging standard.

The structure is deliberately minimal-frontend. One markdown file per
recipe is the source of truth — humans render it as a cookbook page,
agents fetch it raw. No vendor-locked tooling; no maintenance-heavy
custom UI.

- [ ] **mkdocs-material site at `kuiralabs.github.io/kuira-sdk/`** —
  replaces the bare README rendering. Tag-based recipe discovery,
  built-in search, dark mode, default theme, no custom CSS.
- [ ] **`/api/` subdirectory** hosting the aggregated multi-module
  Dokka HTML. One navigable API reference across all 14 published
  modules, deployed on every release.
- [ ] **Three initial recipes** as `.md` files with frontmatter:
  `add-kuira-to-an-android-project`, `set-up-sigil-identity`,
  `deploy-and-call-a-compact-contract`. Hand-authored; structured as
  step-by-step runbooks (verify-after-each-step format).
- [ ] **"Copy prompt for your agent" buttons** on each recipe page.
  Clipboard JS, no per-agent variants — one prompt template wrapping
  the recipe's raw-markdown URL.
- [ ] **`llms.txt` at site root** following <https://llmstxt.org>.
  Hand-authored for `alpha02`; auto-generated from recipe frontmatter
  in `alpha03+`.
- [ ] **Deploy via GitHub Actions** on every push to `main` in
  `kuiralabs/kuira-sdk` (`mkdocs gh-deploy --force` → `gh-pages`
  branch). Dokka HTML deploys from the monorepo's publish workflow on
  every tag, into the same site under `/api/`.

**Deferred to `alpha03+` (cookbook framework full vision):**

These come from the original `cookbook-plan.md`. They're real and
worth tracking; they layer cleanly on top of the recipes-as-markdown
foundation we lay in `alpha02`.

- Option-form-generated recipes (platform × network × IDE × wallet
  matrix produces a tailored runbook).
- **Session state** as JSON file the agent maintains across
  invocations. Survives interruptions; enables resume; doubles as a
  bug report on failure.
- **Live-step gates** — confirmation primitives for irreversible
  actions (deploy, install, spend).
- **CLI integration** — a `kuira` CLI for diagnostics, scaffolding,
  and structured per-step verifiers callable from recipes. Same
  pattern as `mn doctor` in `midnight-wallet-cli`.
- **Per-version frozen API snapshots** (`/api/0.1.0-alpha03/`,
  `/api/latest/`) for stable references across SDK versions.
- **MCP server** (`kuiralabs/kuira-mcp`) — agents connect over Model
  Context Protocol, query SDK as live resources, call SDK tools. The
  recipes and context bundles laid down in `alpha02` become the MCP
  server's resources/tools in `alpha03+`.
- **Recipe content auto-extracted** from monorepo KDoc + INTEGRATION
  sections. Eliminates drift between source and recipes.

---

## Deferred past `alpha02`

These are real and tracked, but landing them inside the `alpha02`
window would dilute the focus. Each is referenced from the SDK
connector wishlist in `examples/midnight-kicks/docs/PLAN.md`.

| # | Item | Deferred because |
|---|---|---|
| 9b | Typed `Ledger<T>` codegen from `.compact` | Sits naturally after the contract Gradle plugin (Workstream B) lands. `alpha03`. |
| 20 | `MidnightContract.observeLedger(): Flow<MidnightLedger>` | Larger; ties in with #10 indexer-subscription work. `alpha03`. |
| 16 | Resume-aware multi-step protocol orchestrator | Substantial; folds in #1 + #6 + #18. Post-`alpha03`. |
| 14 | Session auto-lock | Critical for trust but a focused workstream of its own. `alpha03`. |
| 15 | Sigil UX safety | Same shape as #14 — focused workstream. `alpha03`. |
| 24 | Recovery-phrase export | The biggest sovereignty win; deserves dedicated design + UX. `alpha03`. |
| — | iOS / RN SDK | Separate platform spike; tracked in `project_sdk_platform_roadmap` memory. |
| — | External audit | Post-`1.0`. |
| — | Bug bounty | Post-`1.0`. |

---

## Recommended focus — the seven items that compound most

If everything above is too broad, the high-leverage subset is:

1. ✅ **Pick the public docs host repo** (A) — `kuiralabs/kuira-sdk` selected,
   bootstrapped with README/INTEGRATION/SECURITY/LICENSE, GitHub Pages live.
2. ✅ **Dokka wired into the publish flow** (A) — per-module `javadoc.jar`
   now contains real KDoc HTML (was empty placeholder).
3. ✅ **POM URL fix** (A) — landed; `url` + `scm.*` point at
   `kuiralabs/kuira-sdk`. Effective alpha02 (alpha01's POM is immutable).
4. **mkdocs-material site + recipes + `llms.txt`** (G) — replaces the bare
   README rendering; first three recipes; agent-prompt buttons; auto-deploy.
   The cookbook foundation.
5. **Aggregated Dokka multi-module HTML** deployed to `/api/` of the docs
   site on every tag (A + G).
6. **Contract Gradle plugin (#11)** (B) — biggest concrete DX win for
   consumers writing dApps against the SDK.
7. **`binary-compatibility-validator` + `@ExperimentalKuiraApi`** (C) —
   needed for the semver path toward `1.0`.

Land those seven and `alpha02` is honestly the "now it looks like a real,
agent-ready SDK" release.

---

## See also

- [`../INTEGRATION.md`](../INTEGRATION.md) — consumer recipe.
- [`../SECURITY.md`](../SECURITY.md) — threat model + reporting.
- [`../RELEASE.md`](../RELEASE.md) — maintainer release ritual.
- `examples/midnight-kicks/docs/PLAN.md` — SDK connector wishlist
  (source of the `#11`, `#12`, `#14`, etc. references above).
