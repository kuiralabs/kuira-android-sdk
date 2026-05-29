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
 * layout the SDK expects at runtime.
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
}
