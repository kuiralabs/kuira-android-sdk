package com.midnight.kuira.core.compact

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
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
class CircuitExecutor(private val context: Context) {

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
    ): ExecutionResult {
        validateIdentifier(circuitName, "circuitName")
        validateHex(contractAddress, "contractAddress")
        validateIdentifier(networkId, "networkId")

        val params = executeInQuickJs(
            contractJs = contractJs,
            contractAddress = contractAddress,
            circuitName = circuitName,
            circuitArgs = circuitArgs,
            witnesses = witnesses,
            initialPrivateState = initialPrivateState,
            coinPublicKey = coinPublicKey,
            networkId = networkId,
        )

        return assembleTransaction(params)
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

    private fun assembleTransaction(txParamsJson: String): ExecutionResult {
        val txHex = ContractRuntime.assembleContractCallTx(txParamsJson)
            ?: throw CircuitExecutionException("Transaction assembly returned null")

        if (txHex.startsWith("{\"error")) {
            throw CircuitExecutionException("Transaction assembly failed: $txHex")
        }

        freeStateHandles(txParamsJson)

        return ExecutionResult(
            unprovenTxHex = txHex,
            txParamsJson = txParamsJson,
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
    ): String {
        val witnessEntries = witnesses.keys.joinToString(",\n") { name ->
            """
            $name: function(witnessContext) {
                const resultStr = __witness_$name(
                    JSON.stringify(witnessContext.privateState)
                );
                const parts = resultStr.split('|');
                const privateState = parts[0] === 'null' ? witnessContext.privateState : JSON.parse(parts[0]);
                const keyBytes = new Uint8Array(parts[1].split(',').map(Number));
                return [privateState, keyBytes];
            }
            """.trimIndent()
        }

        val argsStr = if (circuitArgs.isNotEmpty()) ", ${circuitArgs.joinToString(", ")}" else ""
        val cpkJs = coinPublicKey.joinToString(",") { (it.toInt() and 0xFF).toString() }

        return """
            try {
                const witnesses = { $witnessEntries };
                const contract = new Contract(witnesses);

                const initResult = contract.initialState({
                    initialPrivateState: $initialPrivateState,
                    initialZswapLocalState: { coinPublicKey: new Uint8Array([$cpkJs]) },
                });

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
                    entry_point: '$circuitName',
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
                __captureError(e.toString());
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
            js.function("__nativeBigIntToValue") { args: Array<Any?> ->
                ContractRuntime.bigIntToValue(args[0] as String) ?: ""
            }
            js.function("__nativeValueToBigInt") { args: Array<Any?> ->
                ContractRuntime.valueToBigInt(args[0] as String) ?: ""
            }
            js.function("__nativeStateCreateWithNulls") { args: Array<Any?> ->
                val n = (args[0] as String).toInt()
                ContractRuntime.stateCreateWithNulls(n).toString()
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
            js.evaluate<Any?>("""
                globalThis.__native_persistentHash_aligned = __nativePersistentHashAligned;
                globalThis.__native_bigIntToValue = __nativeBigIntToValue;
                globalThis.__native_valueToBigInt = __nativeValueToBigInt;
                globalThis.__native_stateCreateWithNulls = __nativeStateCreateWithNulls;
                globalThis.__native_stateSetOperation = __nativeStateSetOperation;
                globalThis.__native_contractQuery = __nativeContractQuery;
                globalThis.__native_stateClone = __nativeStateClone;
            """.trimIndent())
        }

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
 * Result of a witness callback.
 *
 * @param privateState The private state to pass back (null to keep current)
 * @param data The witness byte array (e.g., secret key, signature)
 */
data class WitnessResult(
    val privateState: Any?,
    val data: ByteArray,
) {
    /** Serialize as "privateState|byte1,byte2,..." for passing through QuickJS. */
    internal fun toJsArrayString(): String {
        val stateStr = if (privateState == null) "null" else privateState.toString()
        val dataStr = data.joinToString(",") { (it.toInt() and 0xFF).toString() }
        return "$stateStr|$dataStr"
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

/** Thrown when circuit execution or transaction assembly fails. */
class CircuitExecutionException(message: String) : Exception(message)
