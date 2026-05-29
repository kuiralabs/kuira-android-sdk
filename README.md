# Kuira Android SDK — monorepo

The source repository for the [Kuira Android SDK](https://kuiralabs.github.io/kuira-sdk-android/),
the wallet app, and the example dApps. This repository is **private**; the
SDK is consumed publicly via [Maven Central](https://central.sonatype.com/namespace/io.github.kuiralabs)
and documented at [kuiralabs.github.io/kuira-sdk-android](https://kuiralabs.github.io/kuira-sdk-android/).

If you're trying to **use** the SDK in your own dApp, go to the docs site
instead — this README is for maintainers and authorized contributors
working on the SDK itself.

---

## Public face

The SDK ships to consumers via three permanent surfaces:

| Surface | Where |
|---|---|
| Maven Central artifacts | `io.github.kuiralabs:*` |
| Docs site | <https://kuiralabs.github.io/kuira-sdk-android> |
| Docs repo (public) | <https://github.com/kuiralabs/kuira-sdk-android> |

The Maven coordinates and the docs URL are pinned in the POM at release
time and never change between versions of the same line.

---

## How this monorepo is laid out

Conceptually, three concerns share the tree:

- **The SDK** — the Kotlin code published to Maven Central. Lives in
  the `sdk/*` and `core/*` module groups. `sdk:midnight-sdk`, `sdk:dapp-ui`,
  and `sdk:contract-plugin` are the umbrella consumer entry points;
  the `core/*` modules are SDK building blocks.
- **The wallet app** — a reference dApp built on the SDK. Lives in
  `app/` and `feature/*`. Not published; demonstrates the SDK end-to-end
  on a real product.
- **Example dApps** — `examples/bboard` and `examples/midnight-kicks`
  (the latter as a submodule). Standalone Gradle projects that consume
  the SDK from Maven Local or Central, proving the published surface
  is enough to build a working dApp.

The `sdk/*` + `core/*` modules are the published surface. `app/` +
`feature/*` are intentionally NOT published; they're the wallet app's
own internals. The publishing build script enforces this.

Engineering plans, decision logs, research notes, and internal design
docs live under `docs/`. That directory is internal — consumer-facing
documentation lives in the kuira-sdk-android repository.

---

## Build, test, publish

| Task | Command |
|---|---|
| Build everything | `./gradlew build` |
| Run all unit tests | `./gradlew test` |
| Verify the public API surface hasn't drifted | `./gradlew apiCheck` |
| Regenerate API baselines after intentional changes | `./gradlew apiDump` |
| Generate aggregated Dokka HTML for the docs site | `./gradlew dokkaHtmlMultiModule` |
| Install the wallet app to a device | `./gradlew installDebug` |
| Publish all SDK modules to Maven Local | `./gradlew publishToMavenLocal` |
| Publish to Maven Central staging | see [`RELEASE.md`](RELEASE.md) |

The publish workflow runs `test`, `apiCheck`, the BBoard acceptance gate,
and `publishToMavenCentral` in order; breaking changes block uploads.

`group` and `version` come from `gradle.properties` — single source of
truth across every published module. A release is a one-line bump there
plus a tag.

---

## Where to find what

| Looking for | Read |
|---|---|
| What the SDK does and how to consume it | [kuiralabs.github.io/kuira-sdk-android](https://kuiralabs.github.io/kuira-sdk-android/) |
| End-to-end integration recipe for the SDK | [`INTEGRATION.md`](INTEGRATION.md) |
| Security policy, threat model, signature verification | [`SECURITY.md`](SECURITY.md) |
| API stability + deprecation policy | [`STABILITY.md`](STABILITY.md) |
| Per-release ritual (tag → CI → Central) | [`RELEASE.md`](RELEASE.md) |
| What's landing in the next alpha cycle | [`docs/ALPHA02_PLAN.md`](docs/ALPHA02_PLAN.md) |
| SDK-connector wishlist (open friction + design rationales) | `examples/midnight-kicks/docs/PLAN.md` |
| Engineering guidelines | [`guidelines/`](guidelines/) |
| Day-to-day collaboration approach | [`LEARNING_STRATEGY.md`](LEARNING_STRATEGY.md) |

---

## Tooling

- **Android Studio Ladybug** or newer for Android development.
- **JDK 17** as `sourceCompatibility` and `jvmTarget`.
- **Kotlin 2.3.x** and **AGP 8.13.x**.
- **`compactc`** matching `@midnight-ntwrk/compact-runtime` pinned in each
  example's `contract/package.json`. The version is sensitive — read
  `examples/midnight-kicks/docs/PLAN.md` § wishlist `#13` if you hit
  a bytecode mismatch.

For the Rust FFI submodule (`kuira-crypto-ffi`), the local build needs
the Android NDK. The `build-android.sh` script auto-detects it through
`ANDROID_HOME`; the publish workflow does the same with the runner's
installed NDK.

---

## License

[Apache License 2.0](LICENSE). The license declared in every POM
matches this file; any change to the LICENSE must propagate to the
POM block in the root `build.gradle.kts`.

---

## Contact

Maintainer: [nel349](https://github.com/nel349) ·
`kuiralabs@gmail.com` ·
[security policy](SECURITY.md) for vulnerability reports.
