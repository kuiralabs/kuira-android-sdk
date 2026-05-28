# Kuira SDK — Alpha Release Plan

**Goal:** a third-party Android developer adds the Kuira SDK dependency to their
own Gradle project, supplies their own passkey domain + a few lines of Hilt
config, and builds a Midnight dApp like **BBoard** or **Kicks** — without copying
AARs or having access to this repo.

**Naming:** the first public release is the **alpha** (`0.1.0-alpha01`), not beta.

---

## Where we are today

Every Android library module already publishes to **`mavenLocal`** as
`com.midnight.kuira:<module>:0.1.0-SNAPSHOT` (see the `subprojects` block in the
root `build.gradle.kts`). It's **debug-only**, with no signing and a bare POM
(no name/license/SCM/transitive metadata). **Kicks and BBoard consume those
local artifacts and build standalone** — which is the proof that matters: the
SDK is already self-contained. The alpha is about taking it from "works locally
via `mavenLocal`" to "anyone can pull it from a public repo and it just works."

## What "ready for alpha" means — the acceptance gate

A **clean consumer project, outside this repo, on a fresh machine** that:

1. adds the published dependency (ideally one line),
2. supplies its own `PasskeyConfig` (its domain) and hosts its `assetlinks.json`,
3. builds and runs a minimal **deploy-a-contract + call-a-circuit** against PREPROD.

If that round-trips end to end, the alpha is real. Everything below serves that.

---

## The one decision that shapes everything: host + coordinates

| Option | Consumer experience | Setup cost | Notes |
|--------|--------------------|-----------|-------|
| **Maven Central** (namespace `io.github.nel349`) | best — plain `implementation(...)`, no extra repo line | highest — GPG signing, full POM, Central Portal, group → `io.github.nel349.*` (GitHub-auto-verifiable) | the real open-SDK story; future-proof |
| **JitPack** | one extra `maven { jitpack }` repo line + `com.github.nel349:...` | lowest — publish = push a git tag, no signing | fastest to alpha; multi-module + native `.so` can need config tuning |
| GitHub Packages | requires a GitHub token **even for public** packages | low | rejected — auth defeats "anyone can consume" |

**✅ DECIDED (2026-05-27): Maven Central, group `io.github.kuiralabs`.**
Hyphen-free org `kuiralabs` (the `kuira` user handle was taken; hyphen-free keeps
the door open to aligning code packages later, and a real `kuira.*` domain can
still supersede it before launch without breaking consumers). Consumers get the
cleanest experience — one line, no extra repository config:

```kotlin
implementation("io.github.kuiralabs:midnight-sdk:0.1.0-alpha01")
```

Consequences this locks in: a group-ID rename across all modules
(`com.midnight.kuira` → `io.github.kuiralabs`), GPG signing, full POM metadata,
and namespace verification via a marker repo in the `kuiralabs` org. **Action:
claim the `kuiralabs` GitHub org** (available as of 2026-05-27).

---

## Workstreams

### A. Host + namespace — ✅ DECIDED
Maven Central, group `io.github.kuiralabs`. Remaining mechanics: claim the
`kuiralabs` org, register/verify the namespace on the Central Portal (marker repo
in `kuiralabs`), generate a GPG signing key, and rename the group across all
modules + the examples' dependency lines.

### B. Consumer surface + one-line entry
- **Decide what's public.** SDK surface = `sdk:midnight-sdk`, `sdk:dapp-ui`,
  `sdk:wallet-seed`, `sdk:wallet-runtime`, `core:{identity, auth, crypto,
  network, compact-engine, indexer, connector, ledger, designsystem}`
  (+ `core:testing` as an optional test artifact). The `feature:*` modules are
  the **wallet app's** screens — exclude unless `dapp-ui` actually needs them.
- **One dependency.** Make `midnight-sdk` (or a thin umbrella) `api`-expose the
  full consumer surface so a dApp adds **one** line and gets the graph; offer a
  **BOM** so multi-module consumers pin one aligned version.
- **`api` vs `implementation` audit.** Today `mavenLocal` masks dependency
  mistakes (every module is present locally). For a remote repo the published
  **POMs must carry the inter-module deps** with the published coordinates, and
  anything a consumer touches must be `api`, not `implementation`.

### C. Release-grade publication
- **Release variant** — switch `singleVariant("debug")` → a `release` variant;
  ship release AARs, not debug.
- **POM metadata** — name, description, url, license, developers, SCM (required
  by Maven Central).
- **Signing** — GPG, if Central.
- **Versioning** — immutable `0.1.0-alpha01` for published releases; keep
  `-SNAPSHOT` for local dev.
- **Artifact naming** — bare last-segment IDs (`crypto`, `identity`, `testing`)
  are generic for a public namespace; consider a `kuira-` prefix to avoid
  ambiguity/squatting.
- **Native libs** — confirm the release AARs bundle the FFI `.so`
  (`core:ledger`, crypto) for the ABIs consumers need (arm64-v8a for devices,
  x86_64 for emulators).

### D. Make it genuinely open (the blockers)
- **🔴 Drop the default `PasskeyConfig` (wishlist #22).** `IdentityModule`
  hardcodes `rpId = "nel349.github.io"`. A third party who forgets to override
  it silently lands on the maintainer's domain and PRF fails unless we add them
  to a maintainer-hosted `assetlinks.json` — i.e. the SDK is effectively
  *permissioned*. Make the config **host-provided with no fallback** (fail-fast
  missing-binding); each example ships its own. **This is the correctness gate.**
- **Proving-key + BLS-params hosting.** Keys download at runtime from a **dev**
  S3 bucket (`midnight-s3-fileshare-dev-eu-west-1`). Proving keys are
  *per-contract* — each dApp hosts its own — but the **shared BLS params** must
  live at a **stable public URL** a third party can rely on (not a dev bucket).
  Document the split.
- ✅ **`INTEGRATION.md`** at the SDK root — landed
  [`../INTEGRATION.md`](../INTEGRATION.md). End-to-end recipe: prereqs (Hilt /
  KSP / AGP / min SDK 30), the one-line dependency (`dapp-ui` panel entry or
  `midnight-sdk` headless), `PasskeyConfig` Hilt module, `assetlinks.json`,
  the debug-only localnet cleartext gotcha, a minimal "deploy + call"
  skeleton, and a troubleshooting table covering every pitfall we hit during
  the audit.
- **License** — confirm `LICENSE` is OSS (Apache-2.0 / MIT) and mirror it into
  the POMs.

### E. Release process + CI
- A `publish` task wired to the chosen repo + a GitHub Actions workflow that
  publishes **on a version tag** (reproducible alphas).
- A short **release checklist** (bump version → tag → CI publishes → smoke test).

### F. Validation
- The **acceptance-gate consumer project** (above) lives outside this tree and
  is run on a clean machine before every alpha tag. It *is* the definition of
  "anyone can build Kicks/BBoard from the dependency."

---

## Blockers (must clear before alpha)

1. **#22 — hardcoded `rpId`** (host-provided config, no fallback). Correctness.
2. **Stable public host for shared BLS params** (off the dev S3 bucket).
3. ✅ **`INTEGRATION.md`** — landed [`../INTEGRATION.md`](../INTEGRATION.md).

Everything else (signing, POM, BOM, CI) is mechanical once the host is chosen.

---

## See also
- `examples/midnight-kicks/` + `examples/.../bboard` — the reference consumers
  (and the alpha's living proof).
- SDK-connector wishlist in `examples/midnight-kicks/docs/PLAN.md` — esp. **#22**
  (open-SDK integration), **#8** (test-seed path), **#11** (contract-artifact
  Gradle plugin), which polish the third-party experience.
