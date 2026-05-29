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

- [ ] **`public/` + `docs/` convention** — codify "anything outside
  `public/` is internal." `public/` becomes the affirmative allow-list
  for consumer-facing material. `docs/` becomes deny-by-default.
  GitHub-convention files (`README`, `LICENSE`, `SECURITY.md`,
  `CONTRIBUTING.md`) stay at repo root.
- [ ] **Repo root `README.md`** — first-impression doc. What Kuira is,
  Maven coordinates, link to the docs site, alpha status, link to
  `INTEGRATION.md`. Currently absent — anyone landing on the repo gets
  a directory listing.
- [ ] **Wire Dokka into the publish flow.** Turns the empty
  `javadoc.jar` into a rendered API reference derived from KDoc.
- [ ] **GitHub Pages docs site.** Dokka HTML deploys to `gh-pages` on
  every tag. The public docs URL becomes the canonical reference.
- [ ] **Fix POM `url` + `scm.url`.** Currently points at the private
  repo and 404s. Point at the GitHub Pages URL once it exists.

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

## Recommended focus — the six items that compound most

If everything above is too broad, the high-leverage subset is:

1. **`public/` + `docs/` convention** (A)
2. **Repo root `README.md`** (A)
3. **Dokka + GitHub Pages docs site** (A) — this single move fixes the
   empty `javadoc.jar`, gives the SDK a public docs URL, and unblocks
   the POM URL fix.
4. **POM URL fix** (A) — kills the 404 a consumer sees today.
5. **Contract Gradle plugin (#11)** (B) — biggest concrete DX win.
6. **`binary-compatibility-validator` + `@ExperimentalKuiraApi`** (C) —
   needed for the semver path toward `1.0`.

Land those six and `alpha02` is honestly the "now it looks like a real
SDK" release.

---

## See also

- [`../INTEGRATION.md`](../INTEGRATION.md) — consumer recipe.
- [`../SECURITY.md`](../SECURITY.md) — threat model + reporting.
- [`../RELEASE.md`](../RELEASE.md) — maintainer release ritual.
- `examples/midnight-kicks/docs/PLAN.md` — SDK connector wishlist
  (source of the `#11`, `#12`, `#14`, etc. references above).
