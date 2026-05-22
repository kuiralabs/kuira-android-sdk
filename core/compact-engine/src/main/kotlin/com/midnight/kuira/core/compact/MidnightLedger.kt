package com.midnight.kuira.core.compact

import java.math.BigInteger

/**
 * A typed, eagerly-evaluated snapshot of a deployed contract's ledger.
 *
 * Obtained via [MidnightContract.ledger]. Each top-level Compact ledger
 * field is decoded ONCE at construction (a single QuickJs round-trip
 * through the contract's `ledger()` getter object — see
 * [LedgerEvaluator] for the runtime flow) and stored in [fields] keyed
 * by the field's `.compact` name.
 *
 * **Why typed accessors instead of a generic `get(name): Any?`.** The
 * runtime returns each type as a specific Kotlin shape — [BigInteger]
 * for any Uint, [Boolean], [ByteArray], [List]`<BigInteger>` for
 * `Vector<N, Uint<8>>`, etc. Callers shouldn't have to know that;
 * the typed accessors enforce the expected shape AND validate range
 * (e.g. Uint<8> must be 0..255). If the chain returns something
 * incompatible with the requested type — schema drift, version
 * mismatch — we throw [WrongLedgerFieldTypeException] loudly rather
 * than silently truncating.
 *
 * **Missing-field semantics.** A typo or version-mismatch field name
 * throws [MissingLedgerFieldException]. For forward-compatible
 * feature detection, every typed accessor has a `*OrNull` sibling.
 *
 * **Zero-value semantics.** A field whose value is the type's zero
 * (Uint=0, Boolean=false, Bytes=zeros) is still present — the
 * contract's getter returned the decoded zero. `*OrNull` returns
 * null ONLY for missing field names, not for zero values.
 */
class MidnightLedger internal constructor(
    private val fields: Map<String, Any?>,
) {

    /** All ledger field names exposed by the contract. */
    fun fieldNames(): Set<String> = fields.keys

    /**
     * Raw decoded value for [name]. Returns one of: [BigInteger],
     * [Boolean], [ByteArray], [List]`<Any?>`, [String], [Map]`<String, Any?>`,
     * or `null`. Useful for debugging / unknown types. Most callers
     * should use the typed `getX` methods instead.
     *
     * Throws [MissingLedgerFieldException] if [name] is not in the schema.
     */
    fun getRaw(name: String): Any? = requireField(name)

    /**
     * Same as [getRaw] but returns `null` when [name] is not in the
     * schema instead of throwing. NOTE: a field that exists in the
     * schema with a literal `null` value is indistinguishable from a
     * missing field through this accessor — the underlying `Map`
     * collapses both cases. Prefer the typed `*OrNull` accessors
     * (`getUint8OrNull`, etc.) when this distinction matters; those
     * use `name in fields` and unambiguously mean "absent."
     */
    fun getRawOrNull(name: String): Any? = if (name in fields) fields[name] else null

    // ── Uint accessors ──────────────────────────────────────────────────

    /**
     * Read a Uint<8> field as [Int]. Value must fit in 0..255.
     * Compact's runtime decodes any Uint as [BigInteger]; we narrow
     * here with an explicit range check so callers don't get silent
     * truncation on a schema mismatch (e.g. asked for Uint<8> but the
     * field is actually a Uint<16>).
     */
    fun getUint8(name: String): Int {
        val raw = requireField(name)
        val big = raw as? BigInteger
            ?: throwWrongType(name, "Uint<8> (BigInteger)", raw)
        if (big < BigInteger.ZERO || big > U8_MAX) {
            throwWrongType(name, "Uint<8> in 0..255", raw)
        }
        return big.toInt()
    }

    fun getUint8OrNull(name: String): Int? =
        if (name in fields) getUint8(name) else null

    /**
     * Read any Uint up to 64 bits as [Long]. Includes Counter
     * (which is internally Uint<64>). Throws if the chain value
     * exceeds [Long.MAX_VALUE] — at that point the caller should
     * use [getUintBig] for full-range access.
     */
    fun getUint64(name: String): Long {
        val raw = requireField(name)
        val big = raw as? BigInteger
            ?: throwWrongType(name, "Uint (BigInteger)", raw)
        if (big < BigInteger.ZERO) {
            throwWrongType(name, "non-negative Uint", raw)
        }
        if (big > LONG_MAX_BIG) {
            throwWrongType(name, "Uint <= Long.MAX_VALUE (use getUintBig for larger)", raw)
        }
        return big.toLong()
    }

    fun getUint64OrNull(name: String): Long? =
        if (name in fields) getUint64(name) else null

    /**
     * Read a Uint of arbitrary precision as [BigInteger]. Use for
     * Uint<128>+ fields where [Long] would overflow. Lossless;
     * recommended whenever the contract field width is unknown.
     */
    fun getUintBig(name: String): BigInteger {
        val raw = requireField(name)
        val big = raw as? BigInteger
            ?: throwWrongType(name, "Uint (BigInteger)", raw)
        if (big < BigInteger.ZERO) {
            throwWrongType(name, "non-negative Uint", raw)
        }
        return big
    }

    fun getUintBigOrNull(name: String): BigInteger? =
        if (name in fields) getUintBig(name) else null

    // ── Boolean ─────────────────────────────────────────────────────────

    /**
     * Read a Boolean field. Accepts a native JS `boolean` or a
     * `BigInteger` of value 0/1 (the JS marshaller in [LedgerEvaluator]
     * normalizes integer Numbers to bigint envelopes, and some Compact
     * runtime paths return 0/1 for Boolean cells). Any other value
     * throws [WrongLedgerFieldTypeException].
     */
    fun getBoolean(name: String): Boolean {
        val raw = requireField(name)
        return when (raw) {
            is Boolean -> raw
            is BigInteger -> when (raw) {
                BigInteger.ZERO -> false
                BigInteger.ONE -> true
                else -> throwWrongType(name, "Boolean (or BigInteger 0/1)", raw)
            }
            else -> throwWrongType(name, "Boolean", raw)
        }
    }

    fun getBooleanOrNull(name: String): Boolean? =
        if (name in fields) getBoolean(name) else null

    // ── Opaque<String> / Maybe<Opaque<String>> ──────────────────────────

    /**
     * Read an `Opaque<String>` field as [String]. Compact's runtime
     * decodes the bytes through `TextDecoder("utf-8")`, so this is
     * already a JVM-native string — no manual decode needed.
     */
    fun getString(name: String): String {
        val raw = requireField(name)
        return raw as? String
            ?: throwWrongType(name, "String", raw)
    }

    fun getStringOrNull(name: String): String? =
        if (name in fields) getString(name) else null

    /**
     * Read a `Maybe<Opaque<String>>` field as nullable [String]:
     *  - `null` when the option's `is_some == false` (i.e. the
     *    contract holds `None`).
     *  - the decoded UTF-8 string otherwise.
     *
     * The compact runtime marshals `Maybe<T>` as a struct
     * `{is_some: Boolean, value: T}`; this accessor unwraps the
     * shape so callers don't have to know the struct layout. Use
     * [getMaybeStringOrNull] when the schema may have evolved and
     * the field might be missing entirely.
     */
    fun getMaybeString(name: String): String? {
        val raw = requireField(name)
        val map = raw as? Map<*, *>
            ?: throwWrongType(name, "Maybe<String> (struct {is_some, value})", raw)
        val isSome = map["is_some"] as? Boolean
            ?: throwWrongType(name, "Maybe<String>.is_some Boolean", map["is_some"])
        if (!isSome) return null
        val value = map["value"]
        return value as? String
            ?: throwWrongType(name, "Maybe<String>.value String", value)
    }

    fun getMaybeStringOrNull(name: String): String? =
        if (name in fields) getMaybeString(name) else null

    // ── Bytes<N> ────────────────────────────────────────────────────────

    /**
     * Read a Bytes<N> field. The chain MAY pad shorter values to N
     * bytes (e.g. an unwritten cell decodes as N zero bytes), so we
     * validate against [expectedLength] explicitly. A mismatch is a
     * schema drift or a wrong call (e.g. asked for Bytes<32> on a
     * Bytes<20> field).
     *
     * Accepts two on-wire shapes — both come from the same
     * `CompactTypeBytes.fromValue` getter, depending on whether the
     * runtime pre-padded the value:
     *
     *  - **`ByteArray`** — returned when the runtime wrapped the
     *    value in a `Uint8Array` (typical when the chain stored a
     *    shorter value and the runtime padded it to length N).
     *  - **`List<*>` of `BigInteger` / `Long` / `Int`** — returned
     *    when the underlying `__nativeContractQuery` JSON arrived
     *    as a plain `Array<number>` and the runtime passed it
     *    through unchanged (the `val.length == this.length` branch
     *    of `CompactTypeBytes.fromValue`).
     *
     * Each list element must fit in 0..255; otherwise this is a
     * schema mismatch.
     */
    fun getBytes(name: String, expectedLength: Int): ByteArray {
        val raw = requireField(name)
        return when (raw) {
            is ByteArray -> {
                if (raw.size != expectedLength) {
                    throwWrongType(
                        name,
                        "Bytes<$expectedLength> (got ${raw.size} bytes)",
                        raw,
                    )
                }
                raw.copyOf()
            }
            is List<*> -> {
                if (raw.size != expectedLength) {
                    throwWrongType(
                        name,
                        "Bytes<$expectedLength> (got ${raw.size} elements)",
                        raw,
                    )
                }
                ByteArray(expectedLength) { i ->
                    val element = raw[i]
                    val v = when (element) {
                        is BigInteger -> element
                        is Long -> BigInteger.valueOf(element)
                        is Int -> BigInteger.valueOf(element.toLong())
                        else -> throwWrongType(
                            name,
                            "Bytes<$expectedLength> element[$i] numeric",
                            element,
                        )
                    }
                    if (v < BigInteger.ZERO || v > U8_MAX) {
                        throwWrongType(
                            name,
                            "Bytes<$expectedLength> element[$i] in 0..255",
                            element,
                        )
                    }
                    v.toInt().toByte()
                }
            }
            else -> throwWrongType(name, "Bytes<$expectedLength>", raw)
        }
    }

    fun getBytesOrNull(name: String, expectedLength: Int): ByteArray? =
        if (name in fields) getBytes(name, expectedLength) else null

    // ── Vector<N, Uint<8>> ──────────────────────────────────────────────

    /**
     * Read a `Vector<N, Uint<8>>` losslessly. Each element comes from
     * Compact's runtime as [BigInteger]; we narrow to [Int] with a
     * 0..255 range check per element. List length must equal
     * [expectedLength] — mismatch indicates schema drift; throws.
     *
     * The runtime-driven path preserves zero-element positions
     * (a Uint<8>=0 serializes to 0 bytes in the cell, so manual cell-hex
     * parsing would lose its index — this accessor queries via
     * `queryLedgerState` per element instead).
     */
    fun getVectorUint8(name: String, expectedLength: Int): IntArray {
        val raw = requireField(name)
        val list = raw as? List<*>
            ?: throwWrongType(name, "Vector<$expectedLength, Uint<8>>", raw)
        if (list.size != expectedLength) {
            throwWrongType(
                name,
                "Vector<$expectedLength, Uint<8>> (got ${list.size} elements)",
                raw,
            )
        }
        return IntArray(expectedLength) { i ->
            val v = list[i] as? BigInteger
                ?: throwWrongType(name, "Vector<*, Uint<8>> element[$i]", list[i])
            if (v < BigInteger.ZERO || v > U8_MAX) {
                throwWrongType(name, "Vector<*, Uint<8>> element[$i] in 0..255", v)
            }
            v.toInt()
        }
    }

    fun getVectorUint8OrNull(name: String, expectedLength: Int): IntArray? =
        if (name in fields) getVectorUint8(name, expectedLength) else null

    // ── Internal helpers ────────────────────────────────────────────────

    /**
     * Look up [name] or throw [MissingLedgerFieldException]. Carries
     * the full known-fields list in the exception so a typo is
     * immediately visible without needing logs.
     */
    private fun requireField(name: String): Any? {
        if (name !in fields) {
            throw MissingLedgerFieldException(
                fieldName = name,
                knownFields = fields.keys,
            )
        }
        return fields[name]
    }

    /**
     * Throws [WrongLedgerFieldTypeException]. Returns `Nothing` so
     * the call site can use it as an expression in an Elvis chain
     * (`as? X ?: throwWrongType(...)`).
     */
    private fun throwWrongType(
        name: String,
        expected: String,
        actual: Any?,
    ): Nothing {
        throw WrongLedgerFieldTypeException(
            fieldName = name,
            expected = expected,
            actualValue = actual,
        )
    }

    override fun toString(): String =
        "MidnightLedger(fields=${fields.keys.joinToString(prefix = "[", postfix = "]")})"

    companion object {
        private val U8_MAX = BigInteger.valueOf(255)
        private val LONG_MAX_BIG = BigInteger.valueOf(Long.MAX_VALUE)
    }
}

/**
 * Thrown when [MidnightLedger] is asked for a field that doesn't
 * exist in the contract's schema. The exception lists [knownFields]
 * so a typo or version mismatch can be diagnosed immediately.
 */
class MissingLedgerFieldException(
    val fieldName: String,
    val knownFields: Set<String>,
) : Exception(
    "ledger field '$fieldName' not found. Known fields: " +
        knownFields.joinToString(prefix = "[", postfix = "]"),
)

/**
 * Thrown when a typed accessor's expectation doesn't match what the
 * contract's getter returned. Indicates schema drift, version
 * mismatch, or a wrong-type call on the caller's side.
 */
class WrongLedgerFieldTypeException(
    val fieldName: String,
    val expected: String,
    val actualValue: Any?,
) : Exception(
    "ledger field '$fieldName': expected $expected, " +
        "got ${actualValue?.let { "${it.javaClass.simpleName}: $it" } ?: "null"}",
)
