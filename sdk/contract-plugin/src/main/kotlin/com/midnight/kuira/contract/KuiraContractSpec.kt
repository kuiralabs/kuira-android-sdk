package com.midnight.kuira.contract

import org.gradle.api.Named
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import javax.inject.Inject

/**
 * One compiled Compact contract in the `kuiraContract { contracts { … } }` container. Each entry
 * gets its own asset sync, source validation, and generated typed facade; app-level settings
 * (rpId, wallet-key bundling, doctor) stay on [KuiraContractExtension].
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
 * The container element name (`"Vault"`) is the default [alias] — it drives the generated class
 * name (`VaultContract`) and the `assets/runtime/<alias>-contract.js` filename.
 */
abstract class KuiraContractSpec @Inject constructor(private val specName: String) : Named {

    override fun getName(): String = specName

    /**
     * Path (relative to the project directory) of the directory containing this contract's compiled
     * artifacts — an `index.js` under `contract`, `*.prover`/`*.verifier` under `keys`, and
     * `*.bzkir` under `zkir` (the standard Compact compiler output layout).
     */
    abstract val source: Property<String>

    /**
     * Filename stem for the contract's JS in `assets/runtime` and the generated class name stem.
     * Defaults to the container entry name.
     */
    @get:Input
    @get:Optional
    abstract val alias: Property<String>

    /**
     * Explicit override for the expected `@midnight-ntwrk/compact-runtime` version this contract was
     * compiled against. Unset → auto-discovered from the nearest `package.json` above [source].
     */
    @get:Input
    @get:Optional
    abstract val expectedRuntimeVersion: Property<String>

    /**
     * Fail the build when this contract's emitted `runtime-version` doesn't match the pin. Default
     * inherits [KuiraContractExtension.requireRuntimeMatch] (true).
     */
    @get:Input
    @get:Optional
    abstract val requireRuntimeMatch: Property<Boolean>
}
