package com.midnight.kuira.core.compact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step 6G: Extract circuit execution results from QuickJS into Kotlin.
 *
 * Verifies we can run a circuit and capture the full proof data
 * (input, output, publicTranscript, privateTranscriptOutputs)
 * as structured Kotlin data — the bridge from JS → Kotlin → Rust tx builder.
 */
@RunWith(AndroidJUnit4::class)
class CircuitResultExtractionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun loadAsset(path: String): String {
        return context.assets.open(path).bufferedReader().readText()
    }

    /** Register Rust FFI native functions — delegates to CircuitExecutor's shared impl. */
    private suspend fun QuickJs.registerNativeFfi() {
        CircuitExecutor.registerNativeFfi(this)
    }

    /**
     * Load polyfills, compact-runtime IIFE, and bboard contract IIFE.
     */
    private suspend fun QuickJs.loadRuntime() {
        evaluate<Any?>(loadAsset("runtime/polyfills.js"))
        evaluate<Any?>(loadAsset("runtime/compact-runtime-iife.js"))
        evaluate<Any?>(loadAsset("runtime/bboard-contract-iife.js"))
    }

    // JS code to serialize circuit results, handling Uint8Array and BigInt
    private val jsJsonReplacer = """
        function jsonReplacer(key, value) {
            if (value instanceof Uint8Array) return { __type: 'Uint8Array', data: Array.from(value) };
            if (typeof value === 'bigint') return { __type: 'BigInt', value: value.toString() };
            if (value instanceof Map) {
                const obj = {};
                value.forEach((v, k) => { obj[k] = v; });
                return { __type: 'Map', entries: obj };
            }
            return value;
        }
    """.trimIndent()

    @Test
    fun extractProofData_fromPostCircuit() {
        var proofDataJson: String? = null
        var error: String? = null
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> proofDataJson = args[0] as? String }
                function("captureError") { args: Array<Any?> -> error = args[0] as? String }
                function("__getSecretKey") { _: Array<Any?> ->
                    testSecretKey.joinToString(",")
                }
                function("log") { args: Array<Any?> -> println("JS: ${args[0]}") }

                registerNativeFfi()
                loadRuntime()

                evaluate<Any?>(jsJsonReplacer)
                evaluate<Any?>("""
                    try {
                        const witnesses = {
                            localSecretKey: function(witnessContext) {
                                const keyStr = __getSecretKey();
                                const keyBytes = new Uint8Array(keyStr.split(',').map(Number));
                                return [witnessContext.privateState, keyBytes];
                            }
                        };
                        const contract = new Contract(witnesses);

                        const initResult = contract.initialState({
                            initialPrivateState: { secretKey: new Uint8Array(32) },
                            initialZswapLocalState: { coinPublicKey: new Uint8Array(32) },
                        });

                        const circuitCtx = __compactRuntime.createCircuitContext(
                            __compactRuntime.dummyContractAddress(),
                            { coinPublicKey: new Uint8Array(32) },
                            initResult.currentContractState,
                            initResult.currentPrivateState,
                        );

                        const circuitResult = contract.impureCircuits.post(circuitCtx, 'Hello from Android!');

                        // Extract the proof data with full structure
                        const proofData = circuitResult.proofData;
                        capture(JSON.stringify({
                            input: proofData.input,
                            output: proofData.output,
                            publicTranscript: proofData.publicTranscript,
                            privateTranscriptOutputs: proofData.privateTranscriptOutputs,
                        }, jsonReplacer));
                    } catch (e) {
                        captureError(e.toString());
                    }
                """.trimIndent())
            }
        }

        assertNull("Circuit should not error: $error", error)
        assertNotNull("Should capture proof data", proofDataJson)

        // Parse and verify structure
        val json = JSONObject(proofDataJson!!)
        println("ProofData JSON (${proofDataJson!!.length} chars)")

        // input: AlignedValue — encoded circuit arguments
        assertTrue("Should have input", json.has("input"))
        val input = json.getJSONObject("input")
        assertTrue("input should have value", input.has("value"))
        assertTrue("input should have alignment", input.has("alignment"))

        // output: AlignedValue — encoded circuit return value
        assertTrue("Should have output", json.has("output"))
        val output = json.getJSONObject("output")
        assertTrue("output should have value", output.has("value"))
        assertTrue("output should have alignment", output.has("alignment"))

        // publicTranscript: array of opcodes with state read results
        assertTrue("Should have publicTranscript", json.has("publicTranscript"))
        val transcript = json.getJSONArray("publicTranscript")
        assertTrue("publicTranscript should not be empty", transcript.length() > 0)

        // privateTranscriptOutputs: witness outputs
        assertTrue("Should have privateTranscriptOutputs", json.has("privateTranscriptOutputs"))
        val privateOutputs = json.getJSONArray("privateTranscriptOutputs")
        // bboard post calls localSecretKey witness → at least one output
        assertTrue("privateTranscriptOutputs should have witness output", privateOutputs.length() > 0)

        println("  input alignment items: ${input.getJSONArray("alignment").length()}")
        println("  output alignment items: ${output.getJSONArray("alignment").length()}")
        println("  publicTranscript ops: ${transcript.length()}")
        println("  privateTranscriptOutputs: ${privateOutputs.length()}")
    }

    @Test
    fun extractFullCircuitResult_includesStateAndContext() {
        var resultJson: String? = null
        var error: String? = null
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> resultJson = args[0] as? String }
                function("captureError") { args: Array<Any?> -> error = args[0] as? String }
                function("__getSecretKey") { _: Array<Any?> ->
                    testSecretKey.joinToString(",")
                }
                function("log") { args: Array<Any?> -> println("JS: ${args[0]}") }

                registerNativeFfi()
                loadRuntime()

                evaluate<Any?>(jsJsonReplacer)
                evaluate<Any?>("""
                    try {
                        const witnesses = {
                            localSecretKey: function(witnessContext) {
                                const keyStr = __getSecretKey();
                                const keyBytes = new Uint8Array(keyStr.split(',').map(Number));
                                return [witnessContext.privateState, keyBytes];
                            }
                        };
                        const contract = new Contract(witnesses);

                        const initResult = contract.initialState({
                            initialPrivateState: { secretKey: new Uint8Array(32) },
                            initialZswapLocalState: { coinPublicKey: new Uint8Array(32) },
                        });

                        const circuitCtx = __compactRuntime.createCircuitContext(
                            __compactRuntime.dummyContractAddress(),
                            { coinPublicKey: new Uint8Array(32) },
                            initResult.currentContractState,
                            initResult.currentPrivateState,
                        );

                        const circuitResult = contract.impureCircuits.post(circuitCtx, 'Hello from Android!');

                        // Extract everything needed for transaction assembly
                        const ctx = circuitResult.context;
                        capture(JSON.stringify({
                            // Proof data for the ZK proof
                            proofData: {
                                input: circuitResult.proofData.input,
                                output: circuitResult.proofData.output,
                                publicTranscript: circuitResult.proofData.publicTranscript,
                                privateTranscriptOutputs: circuitResult.proofData.privateTranscriptOutputs,
                            },
                            // State handle for the updated contract state (stays in Rust)
                            stateHandle: ctx.currentQueryContext._rustHandle,
                            // Updated private state
                            privateState: ctx.currentPrivateState,
                            // Zswap local state (coin inputs/outputs from circuit)
                            zswapLocalState: ctx.currentZswapLocalState,
                            // Gas cost
                            gasCost: circuitResult.gasCost,
                        }, jsonReplacer));
                    } catch (e) {
                        captureError(e.toString());
                    }
                """.trimIndent())
            }
        }

        assertNull("Circuit should not error: $error", error)
        assertNotNull("Should capture full result", resultJson)

        val json = JSONObject(resultJson!!)
        println("Full circuit result (${resultJson!!.length} chars)")

        // Verify all top-level fields
        assertTrue("Should have proofData", json.has("proofData"))
        assertTrue("Should have stateHandle", json.has("stateHandle"))
        assertTrue("Should have privateState", json.has("privateState"))
        assertTrue("Should have zswapLocalState", json.has("zswapLocalState"))
        assertTrue("Should have gasCost", json.has("gasCost"))

        // State handle should be a valid Rust handle (> 0)
        val stateHandle = json.getInt("stateHandle")
        assertTrue("State handle should be positive", stateHandle > 0)
        println("  stateHandle: $stateHandle")

        // Zswap local state should have coin structure
        val zswap = json.getJSONObject("zswapLocalState")
        assertTrue("zswap should have coinPublicKey", zswap.has("coinPublicKey"))
        assertTrue("zswap should have inputs", zswap.has("inputs"))
        assertTrue("zswap should have outputs", zswap.has("outputs"))
        println("  zswap inputs: ${zswap.getJSONArray("inputs").length()}")
        println("  zswap outputs: ${zswap.getJSONArray("outputs").length()}")
    }

    @Test
    fun parseCircuitResult_intoKotlinDataClasses() {
        var resultJson: String? = null
        var error: String? = null
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> resultJson = args[0] as? String }
                function("captureError") { args: Array<Any?> -> error = args[0] as? String }
                function("__getSecretKey") { _: Array<Any?> ->
                    testSecretKey.joinToString(",")
                }
                function("log") { args: Array<Any?> -> println("JS: ${args[0]}") }

                registerNativeFfi()
                loadRuntime()

                evaluate<Any?>(jsJsonReplacer)
                evaluate<Any?>("""
                    try {
                        const witnesses = {
                            localSecretKey: function(witnessContext) {
                                const keyStr = __getSecretKey();
                                const keyBytes = new Uint8Array(keyStr.split(',').map(Number));
                                return [witnessContext.privateState, keyBytes];
                            }
                        };
                        const contract = new Contract(witnesses);

                        const initResult = contract.initialState({
                            initialPrivateState: { secretKey: new Uint8Array(32) },
                            initialZswapLocalState: { coinPublicKey: new Uint8Array(32) },
                        });

                        const circuitCtx = __compactRuntime.createCircuitContext(
                            __compactRuntime.dummyContractAddress(),
                            { coinPublicKey: new Uint8Array(32) },
                            initResult.currentContractState,
                            initResult.currentPrivateState,
                        );

                        const circuitResult = contract.impureCircuits.post(circuitCtx, 'Hello from Android!');

                        const ctx = circuitResult.context;
                        capture(JSON.stringify({
                            proofData: {
                                input: circuitResult.proofData.input,
                                output: circuitResult.proofData.output,
                                publicTranscript: circuitResult.proofData.publicTranscript,
                                privateTranscriptOutputs: circuitResult.proofData.privateTranscriptOutputs,
                            },
                            stateHandle: ctx.currentQueryContext._rustHandle,
                            privateState: ctx.currentPrivateState,
                            zswapLocalState: ctx.currentZswapLocalState,
                        }, jsonReplacer));
                    } catch (e) {
                        captureError(e.toString());
                    }
                """.trimIndent())
            }
        }

        assertNull("Circuit should not error: $error", error)
        assertNotNull("Should capture result", resultJson)

        // Parse into typed Kotlin data classes
        val json = JSONObject(resultJson!!)
        val circuitResult = CircuitResult.fromJson(
            json = json,
            contractAddress = "0".repeat(64),
            entryPoint = "post",
        )

        // Verify proof data parsed correctly
        assertNotNull("Should have proof data", circuitResult.proofData)
        assertNotNull("Should have input", circuitResult.proofData.input)
        assertNotNull("Should have output", circuitResult.proofData.output)
        assertTrue(
            "publicTranscript should not be empty",
            circuitResult.proofData.publicTranscriptJson.length > 2,
        )
        assertTrue(
            "privateTranscriptOutputs should have entries",
            circuitResult.proofData.privateTranscriptOutputs.isNotEmpty(),
        )

        // Verify state handle
        assertTrue("State handle should be positive", circuitResult.stateHandle > 0)

        // Verify contract address and entry point
        assertEquals("0".repeat(64), circuitResult.contractAddress)
        assertEquals("post", circuitResult.entryPoint)

        // Verify zswap and private state captured
        assertTrue("Should have zswap JSON", circuitResult.zswapLocalStateJson.isNotEmpty())
        assertTrue("Should have private state JSON", circuitResult.privateStateJson.isNotEmpty())
    }

    @Test
    fun endToEnd_circuitToUnprovenTransaction() {
        var txParamsJson: String? = null
        var error: String? = null
        val testSecretKey = ByteArray(32) { (it + 1).toByte() }

        runBlocking {
            quickJs {
                function("capture") { args: Array<Any?> -> txParamsJson = args[0] as? String }
                function("captureError") { args: Array<Any?> -> error = args[0] as? String }
                function("__getSecretKey") { _: Array<Any?> ->
                    testSecretKey.joinToString(",")
                }
                function("log") { args: Array<Any?> -> println("JS: ${args[0]}") }

                registerNativeFfi()
                loadRuntime()

                // Deeply convert Uint8Array → plain arrays for JSON serialization
                evaluate<Any?>("""
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
                """.trimIndent())

                evaluate<Any?>("""
                    try {
                        const witnesses = {
                            localSecretKey: function(witnessContext) {
                                const keyStr = __getSecretKey();
                                const keyBytes = new Uint8Array(keyStr.split(',').map(Number));
                                return [witnessContext.privateState, keyBytes];
                            }
                        };
                        const contract = new Contract(witnesses);

                        const initResult = contract.initialState({
                            initialPrivateState: { secretKey: new Uint8Array(32) },
                            initialZswapLocalState: { coinPublicKey: new Uint8Array(32) },
                        });

                        const circuitCtx = __compactRuntime.createCircuitContext(
                            __compactRuntime.dummyContractAddress(),
                            { coinPublicKey: new Uint8Array(32) },
                            initResult.currentContractState,
                            initResult.currentPrivateState,
                        );

                        const circuitResult = contract.impureCircuits.post(circuitCtx, 'Hello from Android!');

                        // Transform transcript to Rust serde format (padded AlignedValues)
                        const rustTranscript = __compactRuntime.transformPublicTranscript(
                            circuitResult.proofData.publicTranscript
                        );

                        // Build the JSON payload for Rust tx assembly
                        capture(JSON.stringify({
                            network_id: 'undeployed',
                            contract_address: __compactRuntime.dummyContractAddress(),
                            entry_point: 'post',
                            state_handle: circuitResult.context.currentQueryContext._rustHandle,
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
                        captureError(e.toString());
                    }
                """.trimIndent())
            }
        }

        assertNull("Circuit should not error: $error", error)
        assertNotNull("Should have tx params JSON", txParamsJson)

        // Call Rust to assemble the transaction
        val result = ContractRuntime.assembleContractCallTx(txParamsJson!!)
        assertNotNull("Rust should return a result", result)
        assertFalse(
            "Transaction assembly should succeed: $result",
            result!!.startsWith("{\"error"),
        )

        // Result should be hex-encoded SCALE bytes
        assertTrue("Result should be hex (even length)", result.length % 2 == 0)
        assertTrue("Result should be substantial", result.length > 100)
    }
}
