package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Executes Compact smart contract circuits on Android via QuickJS + Rust FFI.
 *
 * Wraps the full lifecycle: QuickJS environment setup, native FFI registration,
 * runtime loading, witness bridging, circuit execution, transcript transformation,
 * and transaction assembly.
 *
 * Usage:
 * ```
 * val executor = CircuitExecutor(context)
 * val result = executor.executeCircuit(
 *     contractJs = loadContractIife(),
 *     contractAddress = "abcd...",
 *     circuitName = "post",
 *     circuitArgs = listOf("'Hello from Android!'"),
 *     witnesses = mapOf("localSecretKey" to WitnessProvider {
 *         WitnessResult(null, secretKeyBytes)
 *     }),
 *     initialPrivateState = "{ secretKey: new Uint8Array(32) }",
 *     coinPublicKey = ByteArray(32),
 * )
 * // result.unprovenTxHex is ready for proving
 * ```
 */
class CircuitExecutor(
    private val context: Context,
    // Heavy work (QuickJS circuit execution + native JNI tx-assembly) runs here,
    // off the caller's thread — so a dApp calling on Dispatchers.Main (wallet
    // panel, match flow) doesn't freeze the UI. Injectable for tests.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /**
     * Execute a contract circuit and assemble an UnprovenTransaction.
     *
     * @param contractJs Compiled contract JavaScript (IIFE format, no ES module imports)
     * @param contractAddress Hex-encoded contract address (64 chars)
     * @param circuitName The impure circuit to execute (e.g., "post", "takeDown")
     * @param circuitArgs JS expressions for circuit arguments (inserted into JS code)
     * @param witnesses Map of witness name → [WitnessProvider] callback.
     * @param initialPrivateState JS expression for the initial private state object
     * @param coinPublicKey The coin public key bytes (32 bytes)
     * @param networkId Network ID for the transaction (default: "undeployed")
     * @param onChainStateHex SCALE-encoded on-chain contract state from the indexer.
     *   When provided, circuit execution uses this state instead of `contract.initialState()`.
     *   Required for calling already-deployed contracts where the on-chain state
     *   may differ from the contract's initial state.
     * @param ledgerParametersHex SCALE-encoded ledger parameters from the indexer.
     *   Required for correct gas computation — the node uses these parameters' cost model
     *   to validate the transaction. If omitted, falls back to the initial cost model
     *   (only correct on a fresh chain with no parameter updates).
     * @return The assembled UnprovenTransaction as hex-encoded SCALE bytes
     * @throws CircuitExecutionException if circuit execution or tx assembly fails
     */
    suspend fun executeCircuit(
        contractJs: String,
        contractAddress: String,
        circuitName: String,
        circuitArgs: List<String> = emptyList(),
        witnesses: Map<String, WitnessProvider>,
        initialPrivateState: String,
        coinPublicKey: ByteArray,
        networkId: String = "undeployed",
        onChainStateHex: String? = null,
        ledgerParametersHex: String? = null,
        // Absolute intent TTL (seconds since epoch). Sized to the chain's global_ttl by the
        // caller; null lets the native fall back to its own (over-wide) default — see IntentTtl.
        ttlSecs: Long? = null,
    ): ExecutionResult = withContext(ioDispatcher) {
        validateIdentifier(circuitName, "circuitName")
        validateHex(contractAddress, "contractAddress")
        validateIdentifier(networkId, "networkId")
        if (onChainStateHex != null) {
            validateHex(onChainStateHex, "onChainStateHex")
        }
        if (ledgerParametersHex != null) {
            validateHex(ledgerParametersHex, "ledgerParametersHex")
        }

        val params = executeInQuickJs(
            contractJs = contractJs,
            contractAddress = contractAddress,
            circuitName = circuitName,
            circuitArgs = circuitArgs,
            witnesses = witnesses,
            initialPrivateState = initialPrivateState,
            coinPublicKey = coinPublicKey,
            networkId = networkId,
            onChainStateHex = onChainStateHex,
            ledgerParametersHex = ledgerParametersHex,
        )

        assembleTransaction(params, ttlSecs)
    }

    /**
     * Execute a contract constructor and assemble a deploy transaction.
     *
     * Runs `contract.initialState()` in QuickJS (same runtime as circuit execution)
     * and captures the resulting state handle. Then calls [ContractRuntime.assembleDeployTx]
     * to build the deploy transaction with the initial state + derived contract address.
     *
     * @param contractJs Compiled contract JavaScript (IIFE format)
     * @param witnesses Witness callbacks (e.g., localSecretKey for key generation)
     * @param initialPrivateState JS expression for the initial private state
     * @param coinPublicKey The coin public key bytes (32 bytes)
     * @param networkId Network ID for the transaction
     * @return Deploy transaction hex + contract address
     * @throws CircuitExecutionException if constructor execution or assembly fails
     */
    /**
     * @param verifierKeys Map of circuit name → hex-encoded verifier key bytes.
     *   These are registered in the contract state during deploy so circuits
     *   are immediately callable (no separate maintenance tx needed).
     */
    suspend fun executeConstructor(
        contractJs: String,
        witnesses: Map<String, WitnessProvider>,
        initialPrivateState: String,
        coinPublicKey: ByteArray,
        networkId: String = "undeployed",
        verifierKeys: Map<String, String> = emptyMap(),
        // Absolute intent TTL (seconds since epoch), sized to the chain's global_ttl by the
        // caller; null lets the native fall back to its own (over-wide) default — see IntentTtl.
        ttlSecs: Long? = null,
    ): DeployExecutionResult = withContext(ioDispatcher) {
        validateIdentifier(networkId, "networkId")

        var stateHandle: String? = null
        var jsError: String? = null

        quickJs {
            function("__capture") { args: Array<Any?> -> stateHandle = args[0] as? String }
            function("__captureError") { args: Array<Any?> -> jsError = args[0] as? String }

            registerWitnesses(this, witnesses)
            registerNativeFfi(this)
            loadRuntime(this, contractJs)

            val cpkJs = coinPublicKey.joinToString(",") { (it.toInt() and 0xFF).toString() }
            val witnessEntries = buildWitnessEntriesJs(witnesses.keys)

            val constructorJs = """
                try {
                    const witnesses = { $witnessEntries };
                    const contract = new Contract(witnesses);

                    const initResult = contract.initialState({
                        initialPrivateState: $initialPrivateState,
                        initialZswapLocalState: { coinPublicKey: new Uint8Array([$cpkJs]) },
                    });

                    // Capture the state handle for the deploy transaction assembler
                    __capture(initResult.currentContractState._rustHandle.toString());
                } catch (e) {
                    __captureError(String(e) + (e && e.stack ? "\n" + e.stack : ""));
                }
            """.trimIndent()

            evaluate<Any?>(constructorJs)
        }

        if (jsError != null) {
            throw CircuitExecutionException("Constructor execution failed: $jsError")
        }

        val handle = stateHandle?.toLongOrNull()
            ?: throw CircuitExecutionException("Constructor produced no state handle")

        assembleDeployTransaction(handle, networkId, verifierKeys, ttlSecs)
    }

    private fun assembleDeployTransaction(
        stateHandle: Long,
        networkId: String,
        verifierKeys: Map<String, String>,
        ttlSecs: Long?,
    ): DeployExecutionResult {
        val vkJson = if (verifierKeys.isNotEmpty()) {
            val entries = verifierKeys.entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
            ",\"verifier_keys\":{$entries}"
        } else ""
        // Stamp the chain-sized TTL, else the native deploy assembler defaults to now + 1h.
        val ttlJson = if (ttlSecs != null) ",\"ttl_secs\":$ttlSecs" else ""
        val paramsJson = """{"network_id":"$networkId","state_handle":$stateHandle$vkJson$ttlJson}"""
        val resultJson = ContractRuntime.assembleDeployTx(paramsJson)
            ?: throw CircuitExecutionException("Deploy assembly returned null")

        if (resultJson.contains("\"error\"")) {
            throw CircuitExecutionException("Deploy assembly failed: $resultJson")
        }

        val result = JSONObject(resultJson)
        return DeployExecutionResult(
            unprovenTxHex = result.getString("tx_hex"),
            contractAddress = result.getString("contract_address"),
        )
    }

    private suspend fun executeInQuickJs(
        contractJs: String,
        contractAddress: String,
        circuitName: String,
        circuitArgs: List<String>,
        witnesses: Map<String, WitnessProvider>,
        initialPrivateState: String,
        coinPublicKey: ByteArray,
        networkId: String,
        onChainStateHex: String? = null,
        ledgerParametersHex: String? = null,
    ): String {
        var txParamsJson: String? = null
        var jsError: String? = null

        quickJs {
            function("__capture") { args: Array<Any?> -> txParamsJson = args[0] as? String }
            function("__captureError") { args: Array<Any?> -> jsError = args[0] as? String }

            registerWitnesses(this, witnesses)
            registerNativeFfi(this)
            loadRuntime(this, contractJs)

            val circuitJs = buildCircuitJs(
                witnesses = witnesses,
                contractAddress = contractAddress,
                circuitName = circuitName,
                circuitArgs = circuitArgs,
                initialPrivateState = initialPrivateState,
                coinPublicKey = coinPublicKey,
                networkId = networkId,
                onChainStateHex = onChainStateHex,
                ledgerParametersHex = ledgerParametersHex,
            )
            evaluate<Any?>(JS_DEEP_CONVERT)
            evaluate<Any?>(circuitJs)
        }

        if (jsError != null) {
            throw CircuitExecutionException("Circuit execution failed: $jsError")
        }

        return txParamsJson
            ?: throw CircuitExecutionException("Circuit produced no output")
    }

    private fun assembleTransaction(txParamsJson: String, ttlSecs: Long?): ExecutionResult {
        // The call's params JSON is produced by the JS runtime (no TTL); stamp the
        // chain-sized TTL before the native assembler, else it defaults to now + 1h.
        val params = if (ttlSecs != null) {
            JSONObject(txParamsJson).put("ttl_secs", ttlSecs).toString()
        } else {
            txParamsJson
        }

        val txHex = ContractRuntime.assembleContractCallTx(params)
            ?: throw CircuitExecutionException("Transaction assembly returned null")

        if (txHex.startsWith("{\"error")) {
            throw CircuitExecutionException("Transaction assembly failed: $txHex")
        }

        freeStateHandles(params)

        return ExecutionResult(
            unprovenTxHex = txHex,
            txParamsJson = params,
        )
    }

    private fun freeStateHandles(txParamsJson: String) {
        try {
            val json = JSONObject(txParamsJson)
            ContractRuntime.stateFree(json.getLong("state_handle"))
            ContractRuntime.stateFree(json.getLong("initial_state_handle"))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to free state handles: ${e.message}")
        }
    }

    private suspend fun registerWitnesses(js: QuickJs, witnesses: Map<String, WitnessProvider>) {
        for ((name, provider) in witnesses) {
            js.function("__witness_$name") { args: Array<Any?> ->
                val result = provider.provide(args.getOrNull(0))
                val jsString = result.toJsArrayString()
                result.zeroize()
                jsString
            }
        }
    }

    private suspend fun loadRuntime(js: QuickJs, contractJs: String) {
        try {
            js.evaluate<Any?>(loadAsset("runtime/polyfills.js"))
            js.evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
            js.evaluate<Any?>(contractJs)
        } catch (e: java.io.IOException) {
            throw CircuitExecutionException("Failed to load runtime assets: ${e.message}")
        }
    }

    private fun buildCircuitJs(
        witnesses: Map<String, WitnessProvider>,
        contractAddress: String,
        circuitName: String,
        circuitArgs: List<String>,
        initialPrivateState: String,
        coinPublicKey: ByteArray,
        networkId: String,
        onChainStateHex: String? = null,
        ledgerParametersHex: String? = null,
    ): String {
        val witnessEntries = buildWitnessEntriesJs(witnesses.keys)

        val argsStr = if (circuitArgs.isNotEmpty()) ", ${circuitArgs.joinToString(", ")}" else ""
        val cpkJs = coinPublicKey.joinToString(",") { (it.toInt() and 0xFF).toString() }

        // When on-chain state is provided, swap the Rust handle after initialState()
        // so circuit execution reads from the deployed contract state, not a fresh one.
        // Security: onChainStateHex is validated as hex-only by validateHex() before reaching here,
        // preventing JS injection through the string interpolation below.
        val onChainStateSwap = if (onChainStateHex != null) {
            """
                // Swap to on-chain state: create handle from indexer SCALE hex,
                // free the auto-created handle, and propagate to ChargedState.
                const oldHandle = initResult.currentContractState._rustHandle;
                const onChainHandle = Number(
                    globalThis.__native_stateCreate('$onChainStateHex')
                );
                if (onChainHandle === 0) {
                    throw new Error('Failed to create state handle from on-chain hex');
                }
                initResult.currentContractState._rustHandle = onChainHandle;
                if (initResult.currentContractState._data) {
                    initResult.currentContractState._data._rustHandle = onChainHandle;
                }
                if (oldHandle) {
                    globalThis.__native_stateFree(oldHandle.toString());
                }
            """.trimIndent()
        } else {
            ""
        }

        return """
            try {
                const witnesses = { $witnessEntries };
                const contract = new Contract(witnesses);

                const initResult = contract.initialState({
                    initialPrivateState: $initialPrivateState,
                    initialZswapLocalState: { coinPublicKey: new Uint8Array([$cpkJs]) },
                });

                $onChainStateSwap

                const circuitCtx = __compactRuntime.createCircuitContext(
                    '$contractAddress',
                    { coinPublicKey: new Uint8Array([$cpkJs]) },
                    initResult.currentContractState,
                    initResult.currentPrivateState,
                );

                // Clone the initial state BEFORE circuit execution modifies it.
                // The assembler re-executes against this to compute correct gas.
                const initialStateHandle = Number(
                    globalThis.__native_stateClone(
                        circuitCtx.currentQueryContext._rustHandle.toString()
                    )
                );

                const circuitResult = contract.impureCircuits['$circuitName'](circuitCtx$argsStr);

                const rustTranscript = __compactRuntime.transformPublicTranscript(
                    circuitResult.proofData.publicTranscript
                );

                __capture(JSON.stringify({
                    network_id: '$networkId',
                    contract_address: '$contractAddress',
                    entry_point: '$circuitName',${ledgerParamsJsonEntry(ledgerParametersHex)}
                    state_handle: circuitResult.context.currentQueryContext._rustHandle,
                    initial_state_handle: initialStateHandle,
                    proof_data: {
                        input: deepConvertArrays(circuitResult.proofData.input),
                        output: deepConvertArrays(circuitResult.proofData.output),
                        public_transcript: deepConvertArrays(rustTranscript),
                        private_transcript_outputs: deepConvertArrays(
                            circuitResult.proofData.privateTranscriptOutputs
                        ),
                    },
                }));
            } catch (e) {
                __captureError(String(e) + (e && e.stack ? "\n" + e.stack : ""));
            }
        """.trimIndent()
    }

    private fun loadAsset(path: String): String =
        context.assets.open(path).bufferedReader().readText()

    companion object {
        private const val TAG = "CircuitExecutor"

        private val IDENTIFIER_REGEX = Regex("^[a-zA-Z_][a-zA-Z0-9_]*$")
        private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

        internal suspend fun registerNativeFfi(js: QuickJs) {
            ContractRuntime.ensureLoaded()
            js.function("__nativePersistentHashAligned") { args: Array<Any?> ->
                ContractRuntime.persistentHashAligned(args[0] as String) ?: ""
            }
            js.function("__nativePersistentCommitAligned") { args: Array<Any?> ->
                ContractRuntime.persistentCommitAligned(args[0] as String) ?: ""
            }
            js.function("__nativeBigIntToValue") { args: Array<Any?> ->
                ContractRuntime.bigIntToValue(args[0] as String) ?: ""
            }
            js.function("__nativeValueToBigInt") { args: Array<Any?> ->
                ContractRuntime.valueToBigInt(args[0] as String) ?: ""
            }
            js.function("__nativeStateCreateWithNulls") { args: Array<Any?> ->
                val structureJson = args[0] as String
                ContractRuntime.stateCreateWithNulls(structureJson).toString()
            }
            js.function("__nativeStateSetOperation") { args: Array<Any?> ->
                val handle = (args[0] as String).toLong()
                val name = args[1] as String
                ContractRuntime.stateSetOperation(handle, name)
                ""
            }
            js.function("__nativeContractQuery") { args: Array<Any?> ->
                val handle = (args[0] as String).toLong()
                val opcodesJson = args[1] as String
                ContractRuntime.contractQuery(handle, opcodesJson) ?: "{\"error\":\"null result\"}"
            }
            js.function("__nativeStateClone") { args: Array<Any?> ->
                val handle = (args[0] as String).toLong()
                ContractRuntime.stateClone(handle).toString()
            }
            js.function("__nativeStateCreate") { args: Array<Any?> ->
                val stateHex = args[0] as String
                ContractRuntime.stateCreate(stateHex).toString()
            }
            js.function("__nativeStateFree") { args: Array<Any?> ->
                val handle = (args[0] as String).toLong()
                ContractRuntime.stateFree(handle)
                ""
            }
            js.evaluate<Any?>("""
                globalThis.__native_persistentHash_aligned = __nativePersistentHashAligned;
                globalThis.__native_persistentCommit = function() {
                    try {
                        // Called as persistentCommit(alignment, value, [opening])
                        // from persistentCommit2(rtType, value, opening):
                        //   persistentCommit(rtType.alignment(), rtType.toValue(value), [opening])
                        // persistent_commit = SHA-256(opening || binary_repr(aligned_value))
                        var alignment = arguments[0];
                        var value = arguments[1];
                        var openings = arguments[2]; // array of Uint8Array, typically [nonce_32bytes]

                        // Build the opening bytes (prepended to hash)
                        var openingBytes = [];
                        if (openings && openings.length > 0) {
                            var o = openings[0];
                            openingBytes = o instanceof Uint8Array ? Array.from(o) : o;
                        }

                        // Build aligned value (same format as persistentHash)
                        var aligned = {
                            value: value.map(function(v) { return v instanceof Uint8Array ? Array.from(v) : v; }),
                            alignment: alignment
                        };

                        // Call Rust: persistent_commit(aligned_value, opening)
                        var input = JSON.stringify({ value: aligned, opening: openingBytes });
                        var resultStr = __nativePersistentCommitAligned(input);
                        if (!resultStr) throw new Error('native returned null');
                        var parsed = JSON.parse(resultStr);
                        if (parsed && parsed.error) throw new Error('Rust: ' + parsed.error);
                        return parsed.map(function(arr) { return new Uint8Array(arr); });
                    } catch(e) {
                        throw new Error('persistentCommit failed: ' + e.toString());
                    }
                };
                globalThis.__native_bigIntToValue = __nativeBigIntToValue;
                globalThis.__native_valueToBigInt = __nativeValueToBigInt;
                globalThis.__native_stateCreateWithNulls = __nativeStateCreateWithNulls;
                globalThis.__native_stateSetOperation = __nativeStateSetOperation;
                globalThis.__native_contractQuery = __nativeContractQuery;
                globalThis.__native_stateClone = __nativeStateClone;
                globalThis.__native_stateCreate = __nativeStateCreate;
                globalThis.__native_stateFree = __nativeStateFree;
            """.trimIndent())
        }

        /** Format ledger_parameters_hex as a JSON field for the capture output, or empty string. */
        private fun ledgerParamsJsonEntry(hex: String?): String =
            if (hex != null) "\n                    ledger_parameters_hex: '$hex'," else ""

        private fun validateIdentifier(value: String, name: String) {
            require(IDENTIFIER_REGEX.matches(value)) {
                "$name must be alphanumeric/underscore, got: $value"
            }
        }

        private fun validateHex(value: String, name: String) {
            require(value.isNotEmpty() && HEX_REGEX.matches(value)) {
                "$name must be a hex string, got: ${value.take(20)}"
            }
        }

        private const val JS_DEEP_CONVERT = """
            function deepConvertArrays(obj) {
                if (obj instanceof Uint8Array) return Array.from(obj);
                if (Array.isArray(obj)) return obj.map(deepConvertArrays);
                if (obj !== null && typeof obj === 'object') {
                    const result = {};
                    for (const key of Object.keys(obj)) {
                        result[key] = deepConvertArrays(obj[key]);
                    }
                    return result;
                }
                if (typeof obj === 'bigint') return obj.toString();
                return obj;
            }
        """
    }
}

/**
 * Provides witness data for circuit execution.
 *
 * A witness callback receives the current private state (as a JSON string or null)
 * and returns a [WitnessResult] containing the updated private state and witness bytes.
 */
fun interface WitnessProvider {
    fun provide(privateStateJson: Any?): WitnessResult
}

/**
 * Build the per-witness JS function bodies that bridge native
 * `__witness_$name` calls to the shape the Compact runtime expects.
 *
 * **Single source of truth** for both [CircuitExecutor.executeConstructor]
 * and [CircuitExecutor.buildCircuitJs] — duplicating the template
 * silently broke the wire format on 2026-05-19 when [WitnessResult]
 * was extended from 2-part to 3-part. Keep the parser logic here so
 * future format changes can't drift between the two execution paths.
 *
 * Wire format: each native witness call returns
 *   `"privateState|KIND|byte1,byte2,…"`
 * where KIND is the [WitnessKind] enum name. The JS branches:
 *  - `VECTOR_OF_UINT8` → `Array<bigint>` of the byte values, matching
 *    the Compact `Vector<N, Uint<8>>` type
 *  - `BYTES` (default) → length-1 ⇒ `BigInt`, length-N ⇒ `Uint8Array`,
 *    matching scalar `Uint<…>` and `Bytes<N>` respectively
 */
private fun buildWitnessEntriesJs(witnessNames: Collection<String>): String =
    witnessNames.joinToString(",\n") { name ->
        """
        $name: function(witnessContext) {
            const resultStr = __witness_$name(
                JSON.stringify(witnessContext.privateState)
            );
            // Format: "privateState|KIND|csvBytes" — KIND is the
            // WitnessKind enum name from the Kotlin side, used to
            // pick the right JS-side type for the Compact runtime.
            // See WitnessKind KDoc for the type ↔ Compact mapping.
            const parts = resultStr.split('|');
            const privateState = parts[0] === 'null' ? witnessContext.privateState : JSON.parse(parts[0]);
            const kind = parts[1];
            const values = parts[2].split(',').map(Number);
            let result;
            if (kind === 'VECTOR_OF_UINT8') {
                // Vector<N, Uint<8>> — Compact runtime expects a
                // regular Array of BigInts; a Uint8Array would
                // fail the runtime's type check with the
                // "received {0:…, 1:…}" error.
                result = values.map(function (v) { return BigInt(v); });
            } else {
                // BYTES (default) — Bytes<N> + scalar Uint<…>.
                // Single-byte payloads become BigInt; multi-byte
                // become Uint8Array.
                result = values.length === 1 ? BigInt(values[0]) : new Uint8Array(values);
            }
            return [privateState, result];
        }
        """.trimIndent()
    }

/**
 * How the witness's [WitnessResult.data] bytes should be presented to
 * the Compact runtime when the witness function is called from JS.
 *
 * The Compact type system distinguishes between byte-string types
 * (`Bytes<N>` → JS `Uint8Array(N)`) and vector-of-int types
 * (`Vector<N, Uint<8>>` → JS `Array<bigint>` of length N). The SDK
 * historically only emitted the byte-string form, which silently
 * broke any contract whose witness was typed as a `Vector<N, Uint<…>>`
 * (the Compact runtime's type validator rejected the Uint8Array as
 * "expected Vector<…> but received {0:…, 1:…}").
 *
 * Callers pick the right kind per witness; the default ([BYTES])
 * preserves the historical behavior so consumers that haven't
 * adopted the new param continue to work.
 */
enum class WitnessKind {
    /**
     * Default — multi-byte payload becomes a JS `Uint8Array`; a
     * single-byte payload becomes a `BigInt`. Matches Compact's
     * `Bytes<N>` and scalar `Uint<…>` types.
     *
     * BBoard's `localSecretKey: Bytes<32>` is the canonical example.
     */
    BYTES,

    /**
     * Multi-byte payload becomes a JS `Array<bigint>` where each
     * element is one byte from [WitnessResult.data] lifted to a
     * `BigInt`. Matches Compact's `Vector<N, Uint<8>>` (also written
     * as `Vector<N, Uint<0..256>>` in runtime error messages).
     *
     * Kicks's V3 contract uses this for `localShoots` /
     * `localKeeps`: 5 picks per player, each in range 0..2 (L/C/R),
     * packed into a single witness so the whole regulation commits
     * in one transaction.
     */
    VECTOR_OF_UINT8,
}

/**
 * Result of a witness callback.
 *
 * @param privateState The private state to pass back (null to keep current)
 * @param data The witness byte array (e.g., secret key, signature, packed picks)
 * @param kind How [data] should be presented to the Compact runtime —
 *   defaults to [WitnessKind.BYTES] which matches the existing behavior.
 *   Set to [WitnessKind.VECTOR_OF_UINT8] for `Vector<N, Uint<8>>`
 *   witnesses.
 */
data class WitnessResult(
    val privateState: Any?,
    val data: ByteArray,
    val kind: WitnessKind = WitnessKind.BYTES,
) {
    /**
     * Serialize as "privateState|KIND|byte1,byte2,…" for passing
     * through QuickJS. Three-part pipe-delimited form so the JS
     * wrapper can branch on KIND when reconstructing the value
     * the Compact runtime expects.
     */
    internal fun toJsArrayString(): String {
        val stateStr = if (privateState == null) "null" else privateState.toString()
        val kindStr = kind.name
        val dataStr = data.joinToString(",") { (it.toInt() and 0xFF).toString() }
        return "$stateStr|$kindStr|$dataStr"
    }

    /** Securely zero the witness data after use. */
    internal fun zeroize() {
        data.fill(0)
    }
}

/** Result of circuit execution and transaction assembly. */
data class ExecutionResult(
    val unprovenTxHex: String,
    val txParamsJson: String,
)

/** Result of contract deployment assembly. */
data class DeployExecutionResult(
    val unprovenTxHex: String,
    val contractAddress: String,
)

/** Thrown when circuit execution or transaction assembly fails. */
class CircuitExecutionException(message: String) : Exception(message)
