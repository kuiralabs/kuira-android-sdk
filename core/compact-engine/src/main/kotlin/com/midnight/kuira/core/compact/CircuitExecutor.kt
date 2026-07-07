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
        // Chain block time (Unix SECONDS) for the native gas-query context. Sourced from the chain
        // tip by the caller; null lets the native fall back to its own wall-clock — see #16.
        blockTimeSecs: Long? = null,
        // Constructor args (JS expressions). The circuit-call path calls initialState() to build a
        // throwaway state skeleton, then swaps in the on-chain state — so a constructor-args contract
        // needs shape-valid args here to pass initialState's validation (the values are discarded).
        // Empty for a no-arg constructor (counter/bboard).
        constructorArgs: List<String> = emptyList(),
        // Opaque unshielded-funding JSON object (built by the SDK) that funds the value a contract
        // receives via receiveUnshielded. Null for calls that move no unshielded value — those take
        // the exact same path as before (no offer added). See the native build_funding_offer.
        unshieldedFundingJson: String? = null,
        // Opaque unshielded-withdrawal JSON (built by the SDK) that receives the value a contract
        // SENDS via sendUnshielded (the recipient output). Null except for withdrawals.
        unshieldedWithdrawalJson: String? = null,
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
            constructorArgs = constructorArgs,
        )

        assembleTransaction(params, ttlSecs, blockTimeSecs, unshieldedFundingJson, unshieldedWithdrawalJson)
    }

    /**
     * Run a view/getter circuit against the on-chain state and return its RETURN value as JSON —
     * no proving, no transaction, no submit. BigInt → decimal string, Uint8Array → hex, nested
     * structs preserved (see jsonSafe). Lets a dApp read a contract's computed views (balances,
     * proposals, thresholds) that aren't exposed as raw ledger fields.
     */
    suspend fun readCircuit(
        contractJs: String,
        contractAddress: String,
        circuitName: String,
        circuitArgs: List<String> = emptyList(),
        initialPrivateState: String,
        coinPublicKey: ByteArray,
        onChainStateHex: String,
        constructorArgs: List<String> = emptyList(),
        networkId: String = "undeployed",
        // View circuits may still consume witnesses (private-state reads); empty for pure getters.
        witnesses: Map<String, WitnessProvider> = emptyMap(),
    ): String = withContext(ioDispatcher) {
        validateIdentifier(circuitName, "circuitName")
        validateHex(contractAddress, "contractAddress")
        validateIdentifier(networkId, "networkId")
        validateHex(onChainStateHex, "onChainStateHex")

        executeInQuickJs(
            contractJs = contractJs,
            contractAddress = contractAddress,
            circuitName = circuitName,
            circuitArgs = circuitArgs,
            witnesses = witnesses,
            initialPrivateState = initialPrivateState,
            coinPublicKey = coinPublicKey,
            networkId = networkId,
            onChainStateHex = onChainStateHex,
            constructorArgs = constructorArgs,
            readMode = true,
        )
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
        // Absolute intent TTL (seconds since epoch), sized to the chain's global_ttl AND anchored to
        // chain block time by the caller; null lets the native fall back to its own (over-wide)
        // default — see IntentTtl. The deploy assembler has no gas-query context, so no separate
        // block_time_secs is needed here (unlike the contract-CALL path).
        ttlSecs: Long? = null,
        // Constructor arguments as JS expressions, in declaration order, appended to the
        // contract.initialState(context, ...args) call. Empty for a no-arg constructor
        // (counter/bboard); a configurable contract (e.g. a multisig's signer set +
        // threshold) supplies them.
        constructorArgs: List<String> = emptyList(),
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
            // Compact's generated initialState is `initialState(context, ...constructorArgs)`.
            // Append the (already JS-encoded) args after the context object; empty = no-arg.
            val constructorArgsJs = if (constructorArgs.isEmpty()) "" else ", ${constructorArgs.joinToString(", ")}"

            val constructorJs = """
                try {
                    const witnesses = { $witnessEntries };
                    const contract = new Contract(witnesses);

                    const initResult = contract.initialState({
                        initialPrivateState: $initialPrivateState,
                        initialZswapLocalState: { coinPublicKey: new Uint8Array([$cpkJs]) },
                    }$constructorArgsJs);

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
        // Stamp the chain-sized TTL (already anchored to chain block time by the caller), else the
        // native deploy assembler defaults to now + 1h. The deploy assembler builds no gas-query
        // context, so it needs no block_time_secs — the chain-anchored ttl_secs is the only knob.
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
        constructorArgs: List<String> = emptyList(),
        readMode: Boolean = false,
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
                constructorArgs = constructorArgs,
                readMode = readMode,
            )
            evaluate<Any?>(if (readMode) JS_JSON_SAFE else JS_DEEP_CONVERT)
            evaluate<Any?>(circuitJs)
        }

        if (jsError != null) {
            throw CircuitExecutionException("Circuit execution failed: $jsError")
        }

        return txParamsJson
            ?: throw CircuitExecutionException("Circuit produced no output")
    }

    private fun assembleTransaction(
        txParamsJson: String,
        ttlSecs: Long?,
        blockTimeSecs: Long?,
        unshieldedFundingJson: String? = null,
        unshieldedWithdrawalJson: String? = null,
    ): ExecutionResult {
        // The call's params JSON is produced by the JS runtime (no TTL / no block time); stamp the
        // chain-sized TTL + chain block time before the native assembler. Without a TTL the native
        // defaults to now + 1h; without block_time_secs the gas-query context uses wall-clock.
        // unshielded_funding / unshielded_withdrawal (when present) tell the native assembler to add
        // the offer that funds a receiveUnshielded / receives a sendUnshielded; absent → no offer,
        // byte-identical to a plain call.
        val hasExtra = ttlSecs != null || blockTimeSecs != null ||
            unshieldedFundingJson != null || unshieldedWithdrawalJson != null
        val params = if (hasExtra) {
            JSONObject(txParamsJson).apply {
                if (ttlSecs != null) put("ttl_secs", ttlSecs)
                if (blockTimeSecs != null) put("block_time_secs", blockTimeSecs)
                if (unshieldedFundingJson != null) put("unshielded_funding", JSONObject(unshieldedFundingJson))
                if (unshieldedWithdrawalJson != null) put("unshielded_withdrawal", JSONObject(unshieldedWithdrawalJson))
            }.toString()
        } else {
            txParamsJson
        }

        // Free the native state-pool handles on EVERY exit — success OR throw. The
        // handles were created during JS execution; an assembly error must not leak
        // them. The serde transcript parser rejects a non-normal-form transcript the
        // old hand parser tolerated, so the error path is reachable in normal
        // operation — the free must not sit past the throw.
        try {
            val txHex = ContractRuntime.assembleContractCallTx(params)
                ?: throw CircuitExecutionException("Transaction assembly returned null")

            if (txHex.startsWith("{\"error")) {
                throw CircuitExecutionException("Transaction assembly failed: $txHex")
            }

            return ExecutionResult(
                unprovenTxHex = txHex,
                txParamsJson = params,
            )
        } finally {
            freeStateHandles(params)
        }
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
        constructorArgs: List<String> = emptyList(),
        // Read mode: run the circuit only to capture its RETURN value (a view/getter), skipping
        // the gas clone + proof-data assembly. Used by readCircuit — no transaction is built.
        readMode: Boolean = false,
    ): String {
        val witnessEntries = buildWitnessEntriesJs(witnesses.keys)

        // initialState() below builds a throwaway state (the on-chain state is swapped in after), so
        // a constructor-args contract needs shape-valid args here just to pass its validation.
        val constructorArgsJs = if (constructorArgs.isEmpty()) "" else ", ${constructorArgs.joinToString(", ")}"
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

        // Read mode captures the circuit's RETURN value (a view/getter) — no gas clone, no proof
        // assembly. jsonSafe() makes it JSON-serializable (BigInt → decimal string, Uint8Array → hex).
        // Write mode assembles the transaction (clone for gas + proof data).
        val executionTail = if (readMode) {
            """
                // Reads never reach the assembler whose `finally` frees the write path's handles,
                // so free the native ledger-state handles HERE — the pool is process-global and a
                // dApp polling view circuits would otherwise leak one full contract state per read.
                // The finally also covers a THROWING circuit (routine: proposal enumeration
                // terminates on the contract's own not-found assert).
                const preHandle = circuitCtx.currentQueryContext._rustHandle;
                let postHandle = null;
                try {
                    const circuitResult = contract.impureCircuits['$circuitName'](circuitCtx$argsStr);
                    postHandle = circuitResult.context.currentQueryContext._rustHandle;
                    __capture(JSON.stringify(jsonSafe(circuitResult.result)));
                } finally {
                    globalThis.__native_stateFree(String(preHandle));
                    if (postHandle !== null && postHandle !== preHandle) {
                        globalThis.__native_stateFree(String(postHandle));
                    }
                }
            """
        } else {
            """
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
            """
        }

        return """
            try {
                const witnesses = { $witnessEntries };
                const contract = new Contract(witnesses);

                const initResult = contract.initialState({
                    initialPrivateState: $initialPrivateState,
                    initialZswapLocalState: { coinPublicKey: new Uint8Array([$cpkJs]) },
                }$constructorArgsJs);

                $onChainStateSwap

                const circuitCtx = __compactRuntime.createCircuitContext(
                    '$contractAddress',
                    // Bare coin public key as { bytes: Uint8Array } — createCircuitContext's
                    // fallback (emptyZswapLocalState) stores this object as-is for the caller's
                    // coinPublicKey, so ownPublicKey() returns a ZswapCoinPublicKey the contract's
                    // descriptor can read (.bytes). A { coinPublicKey: … } wrapper here gets
                    // double-nested → ownPublicKey().bytes is undefined (breaks getCaller/signers).
                    { bytes: new Uint8Array([$cpkJs]) },
                    initResult.currentContractState,
                    initResult.currentPrivateState,
                );
                $executionTail
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

        // Makes a view circuit's return value JSON-serializable: BigInt (Uint/Field) → decimal
        // string, Uint8Array (Bytes) → lowercase hex, nested structs/arrays preserved. Enums arrive
        // as plain numbers and pass through. Used only by readCircuit.
        private const val JS_JSON_SAFE = """
            function jsonSafe(v) {
                if (typeof v === 'bigint') return v.toString();
                if (v instanceof Uint8Array) return Array.from(v).map(function(b){return ('0'+b.toString(16)).slice(-2);}).join('');
                if (Array.isArray(v)) return v.map(jsonSafe);
                if (v !== null && typeof v === 'object') {
                    const o = {};
                    for (const k of Object.keys(v)) o[k] = jsonSafe(v[k]);
                    return o;
                }
                return v;
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
