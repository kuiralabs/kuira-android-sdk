package com.midnight.kuira.contract

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
     * Path (relative to the project directory) of the directory containing
     * compiled contract artifacts. The directory must have an `index.js`
     * under `contract`, `*.prover` and `*.verifier` files under `keys`,
     * and `*.bzkir` files under `zkir` — the standard output layout of
     * the Midnight Compact compiler.
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
}
