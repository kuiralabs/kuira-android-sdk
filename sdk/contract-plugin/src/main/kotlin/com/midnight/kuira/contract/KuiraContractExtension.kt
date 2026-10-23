package com.midnight.kuira.contract

import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.Property

/**
 * DSL for the `kuiraContract` block.
 *
 * Minimum configuration:
 *
 * ```
 * kuiraContract {
 *     source.set("contract/src/managed/penalty")
 * }
 * ```
 *
 * The plugin registers a `syncContractAssets` task that copies the
 * compiled contract artifacts into `src/main/assets` in the canonical
 * layout the SDK expects at runtime, and a `validateKuiraContractSource`
 * task that runs first to catch missing-source and runtime-version
 * mismatch at build time instead of as a QuickJS "Unsupported bytecode
 * version" runtime crash on a user's device.
 */
abstract class KuiraContractExtension {

    /**
     * Multiple contracts, each with its own [source]/[alias]/runtime-version. Use this for a dApp
     * that ships more than one contract:
     *
     * ```
     * kuiraContract {
     *     contracts {
     *         register("Vault")        { source.set("contract/src/managed/Vault") }
     *         register("PrivateVault") { source.set("contract/src/managed/PrivateVault") }
     *     }
     * }
     * ```
     *
     * The top-level [source] below is the single-contract shorthand and composes with this — a build
     * may set [source] (one contract) OR use `contracts { … }` (many); setting both includes all,
     * PROVIDED every contract resolves to a distinct alias (aliases that collapse to the same
     * generated `<Alias>Contract` class / task name are rejected at configuration time).
     */
    abstract val contracts: NamedDomainObjectContainer<KuiraContractSpec>

    /**
     * Single-contract shorthand: path (relative to the project directory) of the directory
     * containing compiled contract artifacts. The directory must have an `index.js` under
     * `contract`, `*.prover` and `*.verifier` files under `keys`, and `*.bzkir` files under `zkir`
     * — the standard output layout of the Midnight Compact compiler. For more than one contract use
     * [contracts].
     */
    abstract val source: Property<String>

    /**
     * Filename stem used for the contract's JS artifact in
     * `assets/runtime`. Defaults to the dirname of [source]
     * (e.g. a source of `contract/src/managed/penalty` yields an alias
     * of `penalty` and the artifact lands as
     * `assets/runtime/penalty-contract.js`).
     */
    abstract val alias: Property<String>

    /**
     * Explicit override for the expected `@midnight-ntwrk/compact-runtime`
     * version this contract should have been compiled against. When set,
     * the plugin checks the compiled contract's `compiler/contract-info.json`
     * `runtime-version` field matches this value at validation time.
     *
     * When NOT set, the plugin auto-discovers the expected version by
     * walking up from [source] to find the nearest `package.json` that
     * declares `@midnight-ntwrk/compact-runtime` in its `dependencies`.
     * Auto-discovery covers the canonical layout where the Compact
     * source lives under `contract/src/managed/<name>` and the npm
     * pin lives at `contract/package.json`.
     *
     * Use this property only when the canonical layout doesn't apply
     * (e.g. you compile contracts in a separate repository and ship
     * pre-built artifacts directly, with no co-located package.json).
     */
    abstract val expectedRuntimeVersion: Property<String>

    /**
     * Whether to fail the build when the contract's emitted
     * `runtime-version` doesn't match the consumer's pin. Default `true`.
     *
     * Set to `false` to downgrade the mismatch to a build-time log
     * warning. The runtime check still runs and the diagnostic is still
     * printed; only the failure becomes non-fatal. Use this only when
     * you have a deliberate reason to ship a known-mismatched runtime
     * (e.g. cross-version transition windows during a deliberate
     * upgrade rollout).
     *
     * The plugin treats a missing `contract-info.json` (older compactc
     * output that didn't emit the field) as "unknown, can't check"
     * and skips silently regardless of this flag's value.
     */
    abstract val requireRuntimeMatch: Property<Boolean>

    /**
     * Passkey `rpId` for the consuming app — the hostname (no scheme,
     * no path) that this app's `PasskeyConfig.rpId` is set to. When
     * provided, the `kuiraDoctor` task verifies that `assetlinks.json`
     * is hosted at `https://<rpId>/.well-known/assetlinks.json` AND
     * lists the consuming app's `applicationId` in its targets.
     *
     * Mirrored here rather than inferred from the Hilt module because
     * Gradle has no reliable way to read a Kotlin `const val` from
     * inside a Hilt-annotated object at build-time. The cost of
     * duplication is the consumer keeps two declarations in sync;
     * the cost of NOT mirroring is the doctor can't check this
     * critical failure class.
     *
     * Unset → the assetlinks reachability check skips with a
     * "set kuiraContract.rpId to enable this check" log line.
     */
    abstract val rpId: Property<String>

    /**
     * Whether `kuiraDoctor` should fail the build on a FAIL-severity
     * check, vs. log + return success. Default `false` (warn-only).
     *
     * Set to `true` on release builds + in CI to gate the APK on
     * doctor passing. WARN-severity checks (network unreachable,
     * skipped checks) never fail the task regardless of this flag.
     */
    abstract val requireDoctorPass: Property<Boolean>

    /**
     * Whether to bundle the protocol-level **wallet** proving keys
     * (zswap/dust/BLS, ~24MB) into the APK so the device never downloads
     * them at runtime. Default `false`.
     *
     * When `true`, the `provisionWalletKeys` task downloads the version-pinned
     * keys ONCE at build time (into a shared Gradle cache, so it's paid once
     * per machine per version), then stages them into
     * `assets/wallet-keys`. At runtime the SDK's `ProvingKeyManager` prefers
     * this bundle over its S3 download — turning a flaky first-launch network
     * dependency into a reproducible offline asset.
     *
     * Trade-off: ~24MB of APK size. Opt in for apps that must work on first
     * launch without a reliable network (the common case); leave off to keep
     * the APK lean and accept the one-time S3 download.
     */
    abstract val bundleWalletKeys: Property<Boolean>

    /**
     * Proving-key version to bundle when [bundleWalletKeys] is `true`. Must match
     * the ledger version the SDK targets (the SDK's
     * `ProvingKeyManager.CURRENT_VERSION`). Defaults to the version this plugin
     * release was built against; override only during a deliberate ledger upgrade.
     */
    abstract val walletKeysVersion: Property<Int>

    /**
     * Base URL the wallet keys are fetched from at build time when
     * [bundleWalletKeys] is `true`. Defaults to Midnight's published key store.
     * Override to point at a mirror / your own CDN.
     */
    abstract val walletKeysBaseUrl: Property<String>

    /**
     * Largest circuit size (`k`) this app provisions universal proving parameters for.
     * Defaults to 15; raise it only for a contract whose circuits exceed 2^15 rows.
     *
     * A circuit is proved with the parameter file sized to it exactly, so a circuit at
     * k=17 needs `bls_midnight_2p17` present on the device or proving fails with
     * "BLS params file not found" — no matter how capable the hardware is. Raising this
     * stages the extra sizes alongside the rest of the bundle, so the device still never
     * downloads anything at runtime.
     *
     * Where the numbers come from: a depth-20 Merkle path over `persistentHash` compiles
     * to ~83,600 rows, which is k=17 — so a contract doing that kind of hashing is the
     * case this exists for. The figure to set this from is in the build's own output:
     * `reportCircuitSizes` prints every circuit's `k` and row count, and flags the ones
     * above whatever this is set to.
     *
     * Trade-off, and why it is opt-in: the parameter files double in size with each step
     * (12 MB at k=16, 24 MB at k=17, 48 MB at k=18), so going to the maximum adds ~84 MB
     * to the APK. Proving cost doubles with each step too — around 4 s at k=16 and 18 s
     * at k=18 on a 2025 flagship — and the native prover refuses a proof at runtime when
     * the device's free memory cannot carry it, falling back to remote proving. So a
     * large circuit stays usable on a capable phone without the SDK promising it works
     * everywhere.
     *
     * Requires [bundleWalletKeys] to be `true`: the extra sizes are only staged into the
     * APK bundle, never fetched by the device at runtime.
     */
    abstract val maxCircuitK: Property<Int>
}
