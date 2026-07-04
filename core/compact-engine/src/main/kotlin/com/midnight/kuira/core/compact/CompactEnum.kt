package com.midnight.kuira.core.compact

/**
 * A Compact `enum` value passed as a circuit argument (e.g. a struct field like
 * `Recipient.kind`).
 *
 * Compact enums marshal to a JS **number** at the circuit boundary — the generated wrapper
 * guards enum fields with `typeof(x) === 'number'`. Plain Kotlin `Int`/`Long` marshal to a JS
 * BigInt (`1n`) because Compact's `Uint`/`Field` types require BigInt, so an enum field needs
 * this distinct type to be emitted as a bare number (`1`) instead.
 *
 * @property ordinal the enum's declaration index (0-based), matching the Compact `enum` order.
 */
@JvmInline
value class CompactEnum(val ordinal: Int) {
    init {
        require(ordinal >= 0) { "Compact enum ordinal must be non-negative, got $ordinal" }
    }
}
