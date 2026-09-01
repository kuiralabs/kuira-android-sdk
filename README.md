# Kuira Android — SDK, Wallet & Examples

Source repository for the **Kuira Android SDK**, the **Kuira wallet app**, and the
**example dApps**. Kuira brings Midnight's zero-knowledge smart contracts to Android —
deploy and call `.compact` contracts, generate ZK proofs on-device, and manage a
self-custody wallet.

- **Using the SDK in your own dApp?** You don't need this repo — add the
  [Maven Central](https://central.sonatype.com/namespace/io.github.kuiralabs) dependency
  and follow the docs at
  [kuiralabs.github.io/kuira-sdk-android](https://kuiralabs.github.io/kuira-sdk-android/).
- **Want to build, run, or contribute to the SDK / wallet itself?** You're in the right
  place — start with [Getting started](#getting-started) below.

---

## Getting started

### Prerequisites

| Tool | Version / notes |
|---|---|
| JDK | 17 |
| Android Studio | Ladybug or newer (or the command-line Android SDK) |
| Android NDK | 26+ — required to build the Rust FFI |
| Rust | via [rustup](https://rustup.rs); add the Android targets: `rustup target add aarch64-linux-android x86_64-linux-android` |
| A localnet | node + indexer + proof server, to run anything on-chain — see the [localnet / integration guide](https://kuiralabs.github.io/kuira-sdk-android/) |

### Clone (with the native submodule)

The Rust FFI lives in the `kuira-crypto-ffi` git submodule, so clone recursively:

```bash
git clone --recurse-submodules https://github.com/kuiralabs/kuira-android-sdk.git
cd kuira-android-sdk
# already cloned without submodules?
git submodule update --init --recursive
```

### Build the native FFI

Build the Rust static libraries once (and after any Rust change); CMake links them into
the JNI `.so` during the Android build. `build-android.sh` auto-detects the NDK via
`ANDROID_HOME` (or set `ANDROID_NDK`):

```bash
(cd kuira-crypto-ffi && ./build-android.sh)
```

### Build, test, run

```bash
./gradlew build            # compile every module
./gradlew test             # JVM unit tests
./gradlew apiCheck         # guard the published API surface
./gradlew installDebug     # install the wallet app to a connected device/emulator
```

Instrumented tests and on-chain flows talk to a **localnet** (node `:9944`, indexer
`:8088`, proof server `:6300`). An emulator reaches a host localnet at `10.0.2.2`. Stand
one up with the localnet guide linked above, then fund a wallet before running on-chain.

### Example dApps

`examples/midnight-kicks` is a standalone Gradle project that consumes the SDK from Maven
Local (or Central) — proof that the published surface is enough to build a working dApp.
Build it from its own directory.

---

## Repository layout

Three concerns share the tree:

- **The SDK** — the Kotlin published to Maven Central: the `sdk/*` and `core/*` module
  groups. `sdk:midnight-sdk`, `sdk:dapp-ui`, and `sdk:contract-plugin` are the umbrella
  entry points; `core/*` are the building blocks.
- **The wallet app** — a reference dApp in `app/` + `feature/*`. Not published;
  demonstrates the SDK end-to-end on a real product.
- **Example dApps** — `examples/midnight-kicks` (+ others): standalone Gradle projects
  that consume the published SDK.
- **The native FFI** — `kuira-crypto-ffi` (git submodule): the Rust core, built into
  per-ABI native libraries.

Only `sdk/*` + `core/*` are published; `app/` + `feature/*` are intentionally not (the
publish build enforces this).

---

## Common tasks

| Task | Command |
|---|---|
| Build everything | `./gradlew build` |
| Run all unit tests | `./gradlew test` |
| Verify the public API surface hasn't drifted | `./gradlew apiCheck` |
| Regenerate API baselines after intentional changes | `./gradlew apiDump` |
| Install the wallet app to a device | `./gradlew installDebug` |
| Publish all SDK modules to Maven Local | `./gradlew publishToMavenLocal` |
| Build the native FFI | `(cd kuira-crypto-ffi && ./build-android.sh)` |
| Cut a release | see [`RELEASE.md`](RELEASE.md) |

`group` and `version` come from `gradle.properties` — the single source of truth across
every published module.

---

## Contributing

Contributions are welcome.

- Work on the **`development`** branch (the default working branch) and open PRs against
  it.
- Before a PR: `./gradlew test apiCheck`. If you intentionally changed the public API,
  run `./gradlew apiDump` and commit the updated baselines.
- Follow the engineering guidelines in [`guidelines/`](guidelines/) (architecture,
  Kotlin, security, testing, Compose, Midnight).
- Report security issues per the [security policy](SECURITY.md) — not via a public issue.

---

## Where to find what

| Looking for | Read |
|---|---|
| Using the SDK in a dApp | [kuiralabs.github.io/kuira-sdk-android](https://kuiralabs.github.io/kuira-sdk-android/) |
| End-to-end integration recipe | [`INTEGRATION.md`](INTEGRATION.md) |
| Security policy + threat model | [`SECURITY.md`](SECURITY.md) |
| API stability + deprecation policy | [`STABILITY.md`](STABILITY.md) |
| Release ritual | [`RELEASE.md`](RELEASE.md) |
| Changelog | [`CHANGELOG.md`](CHANGELOG.md) |
| Engineering guidelines | [`guidelines/`](guidelines/) |

---

## Tooling

- **Android Studio Ladybug** or newer; **JDK 17** (`sourceCompatibility` + `jvmTarget`).
- **Kotlin 2.3.x**, **AGP 8.13.x**.
- **`compactc`** matching the `@midnight-ntwrk/compact-runtime` version pinned in each
  example's `contract/package.json` — the versions must line up or the contract bytecode
  won't load.
- **Android NDK 26+** and a **Rust** toolchain with the Android targets, for the
  `kuira-crypto-ffi` submodule.

---

## License

[Apache License 2.0](LICENSE). The license declared in every POM matches this file; any
change must propagate to the POM block in the root `build.gradle.kts`.

---

## Contact

Maintainer: [nel349](https://github.com/nel349) · `kuiralabs@gmail.com` ·
[security policy](SECURITY.md) for vulnerability reports.
