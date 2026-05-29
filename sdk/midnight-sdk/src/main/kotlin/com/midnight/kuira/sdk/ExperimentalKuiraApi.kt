package com.midnight.kuira.sdk

/**
 * Marks an API as **experimental** — subject to breaking changes between
 * releases without going through the standard deprecation cycle declared
 * in `STABILITY.md`.
 *
 * Consumers must explicitly opt in to use an experimental API:
 *
 * ```kotlin
 * // Per call-site
 * @OptIn(ExperimentalKuiraApi::class)
 * fun useExperimentalThing() { … }
 *
 * // Or module-wide (compiler argument):
 * //   -opt-in=com.midnight.kuira.sdk.ExperimentalKuiraApi
 * ```
 *
 * The annotation does not prevent use; it makes the contract explicit so
 * consumers can audit which experimental surface they depend on, and
 * surfaces a compiler warning if they didn't opt in.
 *
 * **What "experimental" means for Kuira:**
 *
 * - The API may change shape, name, or behavior in any subsequent release
 *   (including patch releases during the alpha cycle).
 * - The standard "deprecated for two minor versions before removal" cycle
 *   does NOT apply.
 * - Bug reports against experimental APIs are still welcome, but feature
 *   requests may be answered with "the next iteration may obsolete this."
 *
 * Stable APIs — those without this annotation — follow the versioning
 * policy in `STABILITY.md`.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "This API is experimental and may change in future releases. " +
        "Opt in with @OptIn(ExperimentalKuiraApi::class) to acknowledge.",
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
annotation class ExperimentalKuiraApi
