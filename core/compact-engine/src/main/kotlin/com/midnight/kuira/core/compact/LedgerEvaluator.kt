package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/**
 * Read a deployed contract's ledger LOSSLESSLY by invoking the
 * contract's own `ledger(state)` getter object via QuickJs.
 *
 * **Why this exists.** [MidnightConfig.queryState] returns a flattened
 * JSON cell tree where `Vector<N, Uint<8>>` cells lose position info
 * for zero elements (each Uint<8> with value `0` is encoded as a
 * zero-length byte array, not as `0x00`, so the chain's cell hex is
 * the concatenation of non-zero elements with no positional markers).
 * The contract's own `get fieldname()` accessors go through the
 * runtime's `queryLedgerState` path which IS lossless — that path
 * uses `QueryContext.query` → `__native_contractQuery` and returns
 * the full structured value the type decoder expects. This evaluator
 * runs that path from the dApp side and marshals the result back to
 * Kotlin primitives.
 *
 * **Design.**
 *  1. Fetch the on-chain SCALE state hex (caller responsibility).
 *  2. Spin up QuickJs with the same polyfills + compact-runtime
 *  bindings [CircuitExecutor] uses (registers `__native_*` FFI).
 *  3. Load the contract JS so `ledger()` is in scope.
 *  4. Construct a [ChargedState][com.midnight.kuira.core.compact] whose
 *  `_rustHandle` points at the on-chain state — that's the single
 *  hook `QueryContext.query` looks for to route to `__native_contractQuery`.
 *  5. Call `ledger(chargedState)` and enumerate its getter properties.
 *  Each getter returns the type-decoded value (BigInt, Boolean,
 *  Uint8Array, Array<BigInt>, …).
 *  6. Marshal each value to a JSON envelope `{type, value}` so it
 *  survives the QuickJs → Kotlin boundary, then convert envelopes
 *  to Kotlin primitives ([BigInteger], [Boolean], [ByteArray],
 *  [List]).
 *
 * **Why not parse `MidnightConfig.queryState` cells manually:** the
 * cell hex doesn't carry enough information to reconstruct
 * `Vector<N, Uint<8>>` with internal zeros. Months of testing the
 * Kicks contract surfaced this — see PLAN.md wishlist and the
 * sibling deleted `ContractStateSnapshot` parser for the post-mortem.
 *
 * **Performance notes:** the QuickJs context can be reused across
 * many `readAll()` calls — only `stateHex` changes per call. Callers
 * (typically [MidnightContract]) cache a single evaluator per
 * contract address.
 */
internal class LedgerEvaluator(private val context: Context) {

    /**
     * Cached asset contents. Read once on first use, then reused for
     * every subsequent [readAll] — eliminates the per-call disk read
     * that previously dominated poll-loop overhead.
     */
    private val polyfillsJs: String by lazy { loadAsset("runtime/polyfills.js") }
    private val runtimeJs: String by lazy { loadAsset("runtime/compact-runtime-iife.js") }

    /**
     * Read every top-level ledger field. Returns a `Map<String, Any?>`
     * where each value is one of: [BigInteger], [Boolean], [String],
     * [ByteArray], [List]`<Any?>`, or `null`. Higher-level typed
     * access lives in [MidnightLedger].
     *
     * Throws [LedgerReadException] if QuickJs reports an error or the
     * contract JS doesn't export `ledger`.
     */
    suspend fun readAll(
        contractJs: String,
        stateHex: String,
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        validateHex(stateHex)

        var resultJson: String? = null
        var jsError: String? = null

        quickJs {
            function("__captureLedger") { args: Array<Any?> -> resultJson = args.getOrNull(0) as? String }
            function("__captureLedgerError") { args: Array<Any?> -> jsError = args.getOrNull(0) as? String }

            // Same FFI bindings CircuitExecutor uses — the contract JS
            // calls `__native_contractQuery` via QueryContext when each
            // getter fires, which is what makes this lossless.
            CircuitExecutor.registerNativeFfi(this)

            evaluate<Any?>(polyfillsJs)
            evaluate<Any?>(runtimeJs)
            evaluate<Any?>(contractJs)

            evaluate<Any?>(buildReadLedgerJs(stateHex))
        }

        if (jsError != null) {
            throw LedgerReadException("ledger read failed in JS: $jsError")
        }
        val json = resultJson
            ?: throw LedgerReadException("ledger read produced no output")

        parseMarshalledMap(json)
    }

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    /**
     * The JS that does the actual reading. Lifecycle:
     *  - Open a Rust state handle for the on-chain hex.
     *  - Wrap it in a JS [ChargedState] so `QueryContext.query` finds
     *  `_rustHandle` and routes through the native FFI.
     *  - Call `ledger()` (the contract's exported function) which
     *  returns an object literal of getters.
     *  - For each own property whose descriptor has a `get`, invoke
     *  it and marshal the return value.
     *  - Always free the Rust handle in a finally block, even on
     *  error, to avoid leaking native state across calls.
     *
     * The state hex is interpolated into the JS string. It's
     * validated upstream by [validateHex] — only `[0-9a-fA-F]` is
     * allowed, so no quote/backtick can land in the JS literal.
     */
    private fun buildReadLedgerJs(stateHex: String): String = """
        (function () {
            // marshal: every JS leaf type the contract's getters can return
            // becomes a {type, value} envelope so QuickJs's JSON path can
            // transport it intact to Kotlin. JSON doesn't natively carry
            // BigInt or Uint8Array, hence the envelopes.
            function marshal(v) {
                if (v === null || v === undefined) return { type: 'null' };
                if (typeof v === 'bigint') return { type: 'bigint', value: v.toString() };
                if (typeof v === 'boolean') return { type: 'boolean', value: v };
                if (typeof v === 'number') {
                    // Compact's runtime returns plain Number for Enum
                    // ordinals (Phase, etc.) but BigInt for any Uint.
                    // Normalize integer-valued Numbers to bigint
                    // envelopes so the Kotlin side sees one consistent
                    // type for all numeric ledger fields. Fractional
                    // numbers fall through as `number` for the rare
                    // case a contract exposes a non-integer field.
                    if (Number.isInteger(v) && Number.isFinite(v)) {
                        return { type: 'bigint', value: BigInt(v).toString() };
                    }
                    return { type: 'number', value: v };
                }
                if (typeof v === 'string') return { type: 'string', value: v };
                if (v instanceof Uint8Array) return { type: 'bytes', value: Array.from(v) };
                if (Array.isArray(v)) return { type: 'array', value: v.map(marshal) };
                // Plain object — e.g. a struct. Marshal each property.
                if (typeof v === 'object') {
                    var out = {};
                    for (var k in v) {
                        if (Object.prototype.hasOwnProperty.call(v, k)) {
                            out[k] = marshal(v[k]);
                        }
                    }
                    return { type: 'struct', value: out };
                }
                return { type: 'unknown', value: String(v) };
            }

            try {
                var handleStr = globalThis.__native_stateCreate('$stateHex');
                var handle = parseInt(handleStr, 10);
                if (!handle) {
                    __captureLedgerError('stateCreate returned 0 — invalid state hex?');
                    return;
                }

                try {
                    var chargedState = new __compactRuntime.ChargedState(
                        __compactRuntime.StateValue.newNull()
                    );
                    chargedState._rustHandle = handle;

                    if (typeof ledger !== 'function') {
                        __captureLedgerError(
                            "contract JS does not export 'ledger' — schema-incompatible build?"
                        );
                        return;
                    }

                    var ledgerObj = ledger(chargedState);
                    var result = {};
                    var descriptors = Object.getOwnPropertyDescriptors(ledgerObj);
                    for (var name in descriptors) {
                        var desc = descriptors[name];
                        if (typeof desc.get === 'function') {
                            try {
                                result[name] = marshal(desc.get.call(ledgerObj));
                            } catch (e) {
                                // One getter throwing shouldn't kill the
                                // whole read — surface the error per-field
                                // so callers can see which one failed.
                                result[name] = { type: 'error', value: String(e) };
                            }
                        }
                    }
                    __captureLedger(JSON.stringify(result));
                } finally {
                    globalThis.__native_stateFree(handle.toString());
                }
            } catch (e) {
                __captureLedgerError(e && e.stack ? (e.toString() + '\n' + e.stack) : String(e));
            }
        })();
    """.trimIndent()

    /**
     * Convert the JS-side envelope JSON into Kotlin types.
     *
     *  - `bigint` → [BigInteger]. Callers downcast via [MidnightLedger]
     *  typed accessors (`getUint8`, `getUint64`) which enforce range.
     *  - `bytes` → [ByteArray] (each entry is 0..255).
     *  - `array` → [List]`<Any?>` with each element recursively unmarshalled.
     *  - `struct` → [Map]`<String, Any?>` (future-proof; no contract
     *  today exposes a struct ledger field).
     *  - `error` → throws [LedgerReadException] *eagerly* — a per-field
     *  JS error usually indicates schema drift and we want it loud.
     */
    private fun parseMarshalledMap(json: String): Map<String, Any?> {
        val root = JSONObject(json)
        val out = LinkedHashMap<String, Any?>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = unmarshal(root.getJSONObject(key), key)
        }
        return out
    }

    private fun unmarshal(envelope: JSONObject, fieldName: String): Any? {
        return when (val type = envelope.optString("type", "")) {
            "null" -> null
            "bigint" -> BigInteger(envelope.getString("value"))
            "boolean" -> envelope.getBoolean("value")
            "number" -> envelope.getDouble("value")
            "string" -> envelope.getString("value")
            "bytes" -> {
                val arr = envelope.getJSONArray("value")
                ByteArray(arr.length()) { (arr.getInt(it) and 0xFF).toByte() }
            }
            "array" -> {
                val arr = envelope.getJSONArray("value")
                List(arr.length()) { idx ->
                    unmarshal(arr.getJSONObject(idx), "$fieldName[$idx]")
                }
            }
            "struct" -> {
                val obj = envelope.getJSONObject("value")
                val map = LinkedHashMap<String, Any?>()
                val k = obj.keys()
                while (k.hasNext()) {
                    val key = k.next()
                    map[key] = unmarshal(obj.getJSONObject(key), "$fieldName.$key")
                }
                map
            }
            "error" -> throw LedgerReadException(
                "field '$fieldName' getter threw in JS: ${envelope.optString("value")}",
            )
            else -> {
                Log.w(TAG, "unmarshal: unknown envelope type '$type' for '$fieldName' — returning raw value")
                envelope.opt("value")
            }
        }
    }

    private fun validateHex(hex: String) {
        if (hex.isEmpty()) {
            throw LedgerReadException("state hex is empty — contract has no on-chain state yet?")
        }
        if (!HEX_REGEX.matches(hex)) {
            throw LedgerReadException("state hex contains non-hex characters")
        }
    }

    companion object {
        private const val TAG = "LedgerEvaluator"
        // `+` (not `*`) — an empty string is caught earlier with a
        // clearer message; this regex pins shape for the non-empty case.
        private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
    }
}

/** Thrown by [LedgerEvaluator.readAll] when the JS read pipeline fails. */
class LedgerReadException(message: String) : Exception(message)
