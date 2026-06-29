package com.midnight.kuira.core.compact

import java.math.BigInteger

/**
 * Converts Kotlin values to JavaScript expression strings for QuickJS circuit execution.
 *
 * This is a security-critical component: all string values are properly escaped
 * to prevent JS injection. Developers pass Kotlin types (`String`, `Int`, `ByteArray`, etc.)
 * and this converter produces safe JS literals that CircuitExecutor interpolates into code.
 *
 * Supported types:
 * - `String` → `'escaped value'` (single-quoted JS string)
 * - `Int`, `Long`, `BigInteger` → `42n` (JS BigInt — Compact's `Uint`/`Field`)
 * - `Boolean` → `true` / `false`
 * - `ByteArray` → `new Uint8Array([1,2,3])`
 * - `Map<String, Any?>` → `{ key: value }` (JS object literal)
 * - `List<Any?>` → `[1, 2, 3]` (JS array)
 * - `null` → `null`
 */
internal object ArgConverter {

    /** Convert a single Kotlin value to a JS expression string. */
    fun toJsExpression(value: Any?): String = when (value) {
        null -> "null"
        is String -> "'${escapeJsString(value)}'"
        is Boolean -> value.toString()
        // Compact integer types (Uint<N>, Field) are JS BigInt — a plain JS
        // number fails the generated wrapper's `typeof === 'bigint'` guard.
        is Int, is Long, is BigInteger -> "${value}n"
        is ByteArray -> "new Uint8Array([${value.joinToString(",") { (it.toInt() and 0xFF).toString() }}])"
        is Map<*, *> -> toJsObject(value)
        is List<*> -> "[${value.joinToString(", ") { toJsExpression(it) }}]"
        else -> throw IllegalArgumentException(
            "Unsupported argument type: ${value::class.simpleName}. " +
            "Supported: String, Int, Long, BigInteger, Boolean, ByteArray, Map, List, null."
        )
    }

    /** Convert multiple Kotlin values to JS expression strings. */
    fun toJsExpressions(vararg values: Any?): List<String> =
        values.map { toJsExpression(it) }

    /** Convert a Map to a JS object literal string (for initialPrivateState). */
    fun toJsObjectLiteral(map: Map<String, Any?>): String = toJsObject(map)

    private fun toJsObject(map: Map<*, *>): String {
        if (map.isEmpty()) return "{}"
        val entries = map.entries.joinToString(", ") { (k, v) ->
            "${k}: ${toJsExpression(v)}"
        }
        return "{ $entries }"
    }

    private fun escapeJsString(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
