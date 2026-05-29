# Kuira Android SDK — API Stability Policy

This document declares the contract between Kuira and its consumers
about what we promise not to break, when we will break it, and how long
we keep the bridge to the old shape standing before it goes away.

It applies to every artifact published under `io.github.kuiralabs:*` on
Maven Central.

---

## Versioning — semver, with explicit pre-release rules

Kuira follows [Semantic Versioning 2.0.0](https://semver.org). Versions
are `MAJOR.MINOR.PATCH[-PRERELEASE]`:

| Component | When it bumps | Examples |
|---|---|---|
| `PATCH` | Bug fixes only. No new public API. No behavioral changes to existing public API. | `1.0.0` → `1.0.1` |
| `MINOR` | Additive changes only. New public API. Deprecations. **No removals, no breaking changes** to anything stable. | `1.0.5` → `1.1.0` |
| `MAJOR` | Breaking changes allowed. Required reading: the `CHANGELOG.md` `BREAKING` section + migration notes. | `1.4.2` → `2.0.0` |
| `-alphaXX` | Pre-`1.0.0`. **All semver guarantees are SUSPENDED.** Any release may break anything. | `0.1.0-alpha02` |
| `-betaXX` | Pre-`1.0.0`, but the public surface is stabilizing. Minor-style additive changes only between betas; breaking changes signal a return to alpha. | `0.9.0-beta01` |

**What "alpha" means in practice (current state):** the SDK is alpha until
`1.0.0`. Every alpha release may change, rename, or remove anything in
the public API without a deprecation cycle. Consumers should **pin the
exact version** (`io.github.kuiralabs:dapp-ui:0.1.0-alpha01`, not
`0.1.0+`) and read the per-release notes before upgrading.

---

## What counts as the "public API"

The locked surface is **every JVM-bytecode signature visible to a
consumer** of a published module. Concretely:

- Every Kotlin or Java symbol declared `public`, `protected`, or
  default-visibility in `src/main/`.
- The shape of constructors, including parameter types and defaults.
- The names and types of all `public val` / `public var` properties.
- The set of supertypes declared on public classes.
- Annotations on public symbols where the annotation has retention
  `BINARY` or `RUNTIME`.

The shape is enforced by [Kotlinx
`binary-compatibility-validator`](https://github.com/Kotlin/binary-compatibility-validator):
every published module ships a checked-in `api/<module>.api` file
that dumps the bytecode surface. The `apiCheck` task fails if the
current code disagrees with the baseline; `apiDump` regenerates the
baseline. The publish workflow runs `apiCheck` as a gate — breaking
changes cannot reach Maven Central without first appearing in a PR
diff that updates `api/*.api`.

**What's not locked:** `internal` declarations, `private` declarations,
non-published modules (the wallet app itself, `feature:*`, `app:`,
the example dApps).

---

## The `@ExperimentalKuiraApi` opt-in

Some APIs are deliberately marked as not-yet-stable, even at versions
later than `1.0.0`. Consumers see a compiler warning when they touch
one, and must opt in:

```kotlin
@OptIn(ExperimentalKuiraApi::class)
fun useTheNewThing() {
    SomeExperimentalClass.someMethod()
}
```

Experimental APIs **do not follow the deprecation cycle below.** They
may change shape, name, or behavior in any release — including patch
releases. The opt-in annotation is the contract: by using it, the
consumer acknowledges they accept the volatility.

Stable APIs — those without the annotation — follow the deprecation
cycle below.

---

## Deprecation cycle for stable APIs

Once an API has shipped in a stable (non-alpha, non-experimental)
release, removing or breaking it goes through a published cycle:

### 1. Mark `@Deprecated` in the next minor release

```kotlin
@Deprecated(
    message = "Use newFunctionName() — the old name conflated two responsibilities.",
    replaceWith = ReplaceWith("newFunctionName(arg1, arg2)"),
    level = DeprecationLevel.WARNING,
)
fun oldFunctionName(arg1: A, arg2: B): R = newFunctionName(arg1, arg2)

fun newFunctionName(arg1: A, arg2: B): R { ... }
```

- `DeprecationLevel.WARNING` for at least **one minor release**.
- The deprecated symbol **continues to work** — implementation forwards
  to the new symbol where possible.
- `ReplaceWith` provides an IDE-actionable fix.

### 2. Escalate to `ERROR` in the next minor release after that

```kotlin
@Deprecated(
    message = "…",
    replaceWith = ReplaceWith("…"),
    level = DeprecationLevel.ERROR,   // ← stops compilation, but symbol still exists
)
```

- Consumer code stops compiling against the old name; recompiling
  against a newer SDK forces a migration.
- The binary signature stays in place — already-compiled consumers
  still link.

### 3. Remove in the next MAJOR release

The symbol disappears from `api/*.api`. Already-compiled consumers
hitting it at runtime get `NoSuchMethodError` — the cue to recompile
against the new major.

**Minimum lifecycle:** WARNING for one minor release, ERROR for one
minor release, removal at the next major. So a function deprecated in
`1.3.0` cannot disappear before `2.0.0` (and not before `1.4.0` and
`1.5.0` have each had a chance to show consumers the warning, then
the error).

---

## What we do NOT promise

These are deliberately outside the stability contract:

- **The `internal` visibility surface.** We refactor freely; consumers
  who use reflection / bytecode tricks to reach internal APIs accept
  the consequences.
- **Behavior of `@ExperimentalKuiraApi`-marked symbols.** See above.
- **Implementation details visible through stack traces.** Internal
  class names, package structures of `internal` types, exact contents
  of error messages — all fair game to change.
- **Behavior depending on Midnight protocol releases.** When the
  Midnight ledger / proving-system changes incompatibly, the SDK
  follows in lockstep — that's a MAJOR bump unrelated to our own
  API stability.
- **Native FFI library symbol names.** The `libkuira_crypto_ffi.so`
  loaded at runtime is an implementation detail; consumers must go
  through the SDK's Kotlin API, never directly through JNI.
- **Side-channel behavior on rooted devices, emulators with debug
  bridges, or compromised TEEs.** See [`SECURITY.md`](SECURITY.md).

---

## Pre-`1.0.0` reality check

We're at `0.1.0-alpha01`. The above policy describes what we **will**
hold ourselves to once `1.0.0` ships. During the alpha and beta
cycles:

- We use `apiDump` to lock baselines on every release so changes are
  **visible** in PR diffs, even though they're allowed.
- We adopt the `@ExperimentalKuiraApi` annotation now so that consumers
  who survive into `1.0.0` already have the muscle memory.
- We write `CHANGELOG.md` entries for every alpha release with explicit
  BREAKING sections so upgraders can see what moved.

The discipline we practice during alpha is the same discipline we'll
need to keep promises after `1.0.0`. Treating it as informal now would
make the contract harder to honor later.

---

## See also

- [`README.md`](README.md) — what the SDK is.
- [`SECURITY.md`](SECURITY.md) — threat model + reporting + verification.
- [`RELEASE.md`](RELEASE.md) — how releases are produced.
- [`INTEGRATION.md`](INTEGRATION.md) — consumer recipe.
- [`docs/ALPHA02_PLAN.md`](docs/ALPHA02_PLAN.md) — current alpha cycle plan.
