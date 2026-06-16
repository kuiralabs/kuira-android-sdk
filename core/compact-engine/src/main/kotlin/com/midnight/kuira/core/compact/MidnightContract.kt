package com.midnight.kuira.core.compact

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.io.InputStream

/**
 * High-level interface for calling Midnight smart contract circuits from Android.
 *
 * Wraps the full pipeline: state fetch → circuit execution → ZK proving → balancing → submission.
 * The developer never touches hex strings, JS expressions, or manual orchestration.
 *
 * ```kotlin
 * val bboard = MidnightContract.create(config) {
 *     name = "bboard"
 *     contractJs = assets.open("runtime/bboard-contract-iife.js")
 *     address = "4b459404..."
 *     witness("localSecretKey") { WitnessResult(null, secretKeyBytes) }
 *     initialPrivateState = mapOf("secretKey" to ByteArray(32))
 *     coinPublicKey = walletKeys.coinPublicKey
 * }
 *
 * val receipt = bboard.call("post", "Hello from Android!")
 * ```
 */
class MidnightContract private constructor(
    private val config: MidnightConfig,
    private val contractJsContent: String,
    val contractAddress: String,
    private val witnesses: Map<String, WitnessProvider>,
    private val initialPrivateStateMap: Map<String, Any?>,
    private val coinPublicKey: ByteArray?,
    private val circuitVerifierKeys: Map<String, ByteArray> = emptyMap(),
) {
    /**
     * Per-contract [LedgerEvaluator]. Lazily created on first
     * [ledger] call so the QuickJs setup cost is amortized across a
     * polling loop instead of being paid per tick.
     *
     * The evaluator itself is stateless across calls (each
     * [LedgerEvaluator.readAll] currently spins a fresh QuickJs
     * context — see its docs for the longer-term caching plan), but
     * caching it here avoids re-allocating the wrapper and lets the
     * evaluator reuse its pre-loaded asset strings.
     */
    private val ledgerEvaluator: LedgerEvaluator by lazy { LedgerEvaluator(config.context) }

    /**
     * Call a circuit and submit the transaction to the blockchain.
     *
     * @param circuitName The circuit to call (e.g., "post", "takeDown")
     * @param args Circuit arguments as Kotlin values (auto-converted to JS)
     * @param onProgress Optional callback for UI progress updates
     * @return Receipt with transaction hash, status, and timings
     * @throws ContractCallException with specific subclass per failure stage
     */
    suspend fun call(
        circuitName: String,
        vararg args: Any?,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ): TransactionReceipt = config.runTracked(circuitName) {
        requireWriteCapable()
        requireAddress("call")
        val prepared = prepare(circuitName, *args, onProgress = onProgress)

        onProgress?.invoke(ContractCallStage.Balancing)
        val balancer = config.getBalancer()
        val balanceAndSubmitStart = System.currentTimeMillis()
        try {
            balancer.balanceAndSubmit(prepared.provenTxHex) { balanceProgress ->
                onProgress?.invoke(ContractCallStage.BalancingDetail(balanceProgress))
            }
        } catch (e: Exception) {
            // Classify the error based on the stage it occurred in
            val message = e.message ?: ""
            if (message.contains("Balance failed") || message.contains("balance")) {
                throw ContractCallException.BalancingFailed("Balance failed: $message", e)
            }
            throw ContractCallException.SubmissionFailed("Submit failed: $message", e)
        }
        val balanceAndSubmitMs = System.currentTimeMillis() - balanceAndSubmitStart

        TransactionReceipt(
            txHash = null,
            status = TransactionStatus.SUBMITTED,
            timings = prepared.timings.copy(
                balanceMs = balanceAndSubmitMs,
                submitMs = 0, // Included in balanceMs (combined operation)
            ),
            provenTxHex = prepared.provenTxHex,
        )
    }

    /**
     * Idempotent single call (#254): if [isDoneOnLedger] is already true the on-chain
     * effect is present, so this SKIPS the call and returns `null` (no double-submit);
     * otherwise it runs [call] and returns the receipt.
     *
     * The check is anchored to the LEDGER, not local state — so a retry (after a crash, or
     * on resume) is always safe: the chain decides whether the work is done.
     * [com.midnight.kuira.sdk] `MidnightSdk.runProtocol`'s `step` is the multi-step
     * generalization of this pattern.
     */
    suspend fun callIdempotent(
        circuitName: String,
        vararg args: Any?,
        isDoneOnLedger: suspend () -> Boolean,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ): TransactionReceipt? {
        if (isDoneOnLedger()) return null
        return call(circuitName, *args, onProgress = onProgress)
    }

    /**
     * Execute and prove a circuit without submitting (offline mode).
     *
     * The result can be submitted later via [MidnightConfig.submit].
     */
    suspend fun prepare(
        circuitName: String,
        vararg args: Any?,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ): PreparedTransaction {
        requireWriteCapable()
        requireAddress("prepare")
        val jsArgs = ArgConverter.toJsExpressions(*args)
        val privateStateJs = ArgConverter.toJsObjectLiteral(initialPrivateStateMap)

        // Step 1: Fetch on-chain state + ledger params
        onProgress?.invoke(ContractCallStage.FetchingState)
        val fetchStart = System.currentTimeMillis()
        val onChainStateHex: String
        val ledgerParamsHex: String
        try {
            onChainStateHex = config.fetchContractState(contractAddress)
            ledgerParamsHex = config.fetchLedgerParameters()
        } catch (e: ContractCallException) {
            throw e
        } catch (e: Exception) {
            throw ContractCallException.StateFetchFailed("State fetch failed: ${e.message}", e)
        }
        val fetchMs = System.currentTimeMillis() - fetchStart

        // Step 2: Execute circuit
        onProgress?.invoke(ContractCallStage.Executing)
        val executeStart = System.currentTimeMillis()
        val executionResult = try {
            config.executor.executeCircuit(
                contractJs = contractJsContent,
                contractAddress = contractAddress,
                circuitName = circuitName,
                circuitArgs = jsArgs,
                witnesses = witnesses,
                initialPrivateState = privateStateJs,
                coinPublicKey = coinPublicKey!!,  // requireWriteCapable() above guarantees non-null
                networkId = config.networkId,
                onChainStateHex = onChainStateHex,
                ledgerParametersHex = ledgerParamsHex,
            )
        } catch (e: Exception) {
            throw ContractCallException.CircuitExecutionFailed(
                "Circuit '$circuitName' failed: ${e.message}", e,
            )
        }
        val executeMs = System.currentTimeMillis() - executeStart

        // Step 3: Prove locally
        onProgress?.invoke(ContractCallStage.Proving)
        val proveStart = System.currentTimeMillis()
        val provenTxHex = try {
            config.proofProvider.prove(executionResult.unprovenTxHex).trim()
        } catch (e: Exception) {
            throw ContractCallException.ProvingFailed("Proving failed: ${e.message}", e)
        }
        val proveMs = System.currentTimeMillis() - proveStart

        return PreparedTransaction(
            provenTxHex = provenTxHex,
            contractAddress = contractAddress,
            circuitName = circuitName,
            timings = PipelineTimings(
                fetchStateMs = fetchMs,
                executeMs = executeMs,
                proveMs = proveMs,
            ),
        )
    }

    /**
     * Read the contract's ledger state losslessly.
     *
     * Returns a [MidnightLedger] with every top-level ledger field
     * decoded by the contract's own `ledger()` function via the
     * runtime — NOT by parsing the indexer's flattened JSON cell
     * tree. This is the only way to recover positional information
     * for `Vector<N, Uint<8>>` (and similar variable-byte) cells
     * when the array contains internal zero elements; the cell hex
     * exposed by `MidnightConfig.queryState` strips zero-byte
     * elements with no positional markers.
     *
     * Implementation: fetch the SCALE state hex from the indexer →
     * the cached [ledgerEvaluator] invokes `ledger(chargedState)`
     * via QuickJs and marshals each typed getter result back to
     * Kotlin. The result Map is materialized eagerly so subsequent
     * typed accessors are cheap.
     *
     * @throws ContractCallException.StateFetchFailed if the indexer
     *   query fails.
     * @throws LedgerReadException if the JS evaluation fails or the
     *   contract JS doesn't export `ledger` (likely a contract /
     *   client version mismatch).
     * @throws IllegalArgumentException if this contract was built
     *   without an address.
     */
    suspend fun ledger(): MidnightLedger {
        requireAddress("ledger")
        val stateHex = config.fetchContractState(contractAddress)
        val fields = ledgerEvaluator.readAll(contractJsContent, stateHex)
        return MidnightLedger(fields)
    }

    /**
     * Observe this contract's ledger state as it changes on-chain (#255).
     *
     * Emits the CURRENT [MidnightLedger] immediately, then a fresh one each time the contract's
     * on-chain state actually changes — driven by the chain's block stream (the SDK wires
     * [MidnightConfig.Builder.blockSignals]; a raw config falls back to a poll). The raw state hex
     * is de-duplicated BEFORE the (QuickJs) decode, so an unchanged block costs only a state fetch,
     * not a decode. Lets a dApp REACT to state instead of polling [ledger] in a loop.
     *
     * The flow keeps running until the collector cancels; the fetch+decode run on [Dispatchers.IO].
     *
     * @throws IllegalArgumentException if this contract was built without an address.
     */
    fun observeLedger(): Flow<MidnightLedger> {
        requireAddress("observeLedger")
        return ledgerStateHexSignals(config.ledgerSignals()) { config.fetchContractState(contractAddress) }
            .map { hex -> MidnightLedger(ledgerEvaluator.readAll(contractJsContent, hex)) }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Deploy a new contract instance to the blockchain.
     *
     * Runs the constructor in QuickJS, proves the deploy tx, balances, and submits.
     * Returns the contract address — use it to create a [MidnightContract] for calls.
     *
     * @param onProgress Optional callback for UI progress updates
     * @return [DeployResult] with the contract address and timings
     */
    suspend fun deploy(
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ): DeployResult = config.runTracked(DEPLOY_OP_NAME) {
        requireWriteCapable()
        val privateStateJs = ArgConverter.toJsObjectLiteral(initialPrivateStateMap)

        // Step 1: Execute constructor
        onProgress?.invoke(ContractCallStage.Executing)
        val executeStart = System.currentTimeMillis()
        val deployExec = try {
            config.executor.executeConstructor(
                contractJs = contractJsContent,
                witnesses = witnesses,
                initialPrivateState = privateStateJs,
                coinPublicKey = coinPublicKey!!,  // requireWriteCapable() above guarantees non-null
                networkId = config.networkId,
                verifierKeys = circuitVerifierKeys.mapValues { (_, bytes) ->
                    bytes.joinToString("") { "%02x".format(it) }
                },
            )
        } catch (e: Exception) {
            throw ContractCallException.CircuitExecutionFailed(
                "Constructor failed: ${e.message}", e,
            )
        }
        val executeMs = System.currentTimeMillis() - executeStart

        // Step 2: Prove deploy tx
        onProgress?.invoke(ContractCallStage.Proving)
        val proveStart = System.currentTimeMillis()
        val provenTxHex = try {
            config.proofProvider.prove(deployExec.unprovenTxHex).trim()
        } catch (e: Exception) {
            throw ContractCallException.ProvingFailed("Deploy proving failed: ${e.message}", e)
        }
        val proveMs = System.currentTimeMillis() - proveStart

        // Step 3: Balance + submit
        onProgress?.invoke(ContractCallStage.Balancing)
        val balanceStart = System.currentTimeMillis()
        val balancer = config.getBalancer()
        try {
            balancer.balanceAndSubmit(provenTxHex) { balanceProgress ->
                onProgress?.invoke(ContractCallStage.BalancingDetail(balanceProgress))
            }
        } catch (e: Exception) {
            throw ContractCallException.SubmissionFailed("Deploy submit failed: ${e.message}", e)
        }
        val balanceMs = System.currentTimeMillis() - balanceStart

        DeployResult(
            contractAddress = deployExec.contractAddress,
            timings = DeployTimings(
                executeMs = executeMs,
                proveMs = proveMs,
                balanceMs = balanceMs,
            ),
        )
    }

    /** DSL builder for creating a [MidnightContract]. */
    class ContractBuilder internal constructor() {
        /** Contract name (used for proving key management). */
        var name: String? = null

        /**
         * Contract JavaScript as an InputStream. Accepts either:
         *  - Manually-preprocessed IIFE (the older BBoard pattern —
         *    `import * as __compactRuntime` replaced with a comment,
         *    `export` keywords stripped), or
         *  - Raw `compactc` output with ES module syntax intact
         *    (the natural shape of `contract/src/managed/<name>/contract/index.js`).
         *
         * The builder normalizes ES module syntax to script-compatible
         * form via [normalizeContractJs] before handing the content to
         * the circuit executor — consuming dApps no longer need a
         * per-project preprocessing step in their Gradle build.
         */
        var contractJs: InputStream? = null

        /** Deployed contract address (64 hex chars). */
        var address: String? = null

        /** Initial private state as a Kotlin map (auto-converted to JS). */
        var initialPrivateState: Map<String, Any?> = emptyMap()

        /** Coin public key (32 bytes). Required. */
        var coinPublicKey: ByteArray? = null

        /**
         * Circuit verifier keys for deploy — map of circuit name to raw verifier key bytes.
         * Load from the compiled contract's `keys/{circuit}.verifier` files.
         * Required for [deploy], not needed for [call].
         */
        var circuitVerifierKeys: Map<String, ByteArray> = emptyMap()

        private val witnesses = mutableMapOf<String, WitnessProvider>()

        /** Register a witness provider for a named witness. */
        fun witness(name: String, provider: WitnessProvider) {
            witnesses[name] = provider
        }

        internal fun build(config: MidnightConfig): MidnightContract {
            val jsStream = requireNotNull(contractJs) { "contractJs is required" }
            // coinPublicKey is OPTIONAL — ledger reads don't use it.
            // Circuit calls (call/prepare/deploy) DO use it; they call
            // requireWriteCapable() which throws a clear error when cpk
            // is null. This lets a dApp build a read-only contract
            // handle for ledger polling without having a wallet attached.
            val cpk: ByteArray? = coinPublicKey

            val addr = address
            if (addr != null) {
                require(addr.length == CONTRACT_ADDRESS_HEX_LENGTH &&
                    addr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                    "address must be $CONTRACT_ADDRESS_HEX_LENGTH hex characters"
                }
            }

            val jsContent = jsStream.use { it.bufferedReader().readText() }
            // Normalize ES module syntax (compactc emits `import * as` +
            // `export var/class`) to plain-script form. QuickJs is a
            // script-only engine; module declarations are SyntaxErrors.
            val normalizedJs = normalizeContractJs(jsContent)

            return MidnightContract(
                config = config,
                contractJsContent = normalizedJs,
                contractAddress = addr ?: "",
                witnesses = witnesses.toMap(),
                initialPrivateStateMap = initialPrivateState,
                coinPublicKey = cpk,
                circuitVerifierKeys = circuitVerifierKeys,
            )
        }
    }

    /**
     * Throws if this contract was built without a [coinPublicKey] —
     * signals that the caller is attempting a write circuit
     * (call/prepare/deploy) on a read-only contract handle.
     * Read-only contracts are built without cpk; writes need a real
     * wallet-bound cpk.
     */
    private fun requireWriteCapable() {
        if (coinPublicKey == null) {
            throw IllegalStateException(
                "this MidnightContract was built without a coinPublicKey — it can only " +
                    "perform ledger reads via ledger(). Call/prepare/deploy require a " +
                    "real coinPublicKey from the wallet."
            )
        }
    }

    /**
     * Throws if this contract was built without an address.
     * [opName] is the calling method name, included verbatim in the
     * error so the developer sees which API call needs an address.
     */
    private fun requireAddress(opName: String) {
        require(contractAddress.isNotEmpty()) {
            "MidnightContract was built without an address — $opName() needs a deployed contract"
        }
    }

    companion object {
        private const val CONTRACT_ADDRESS_HEX_LENGTH = 64

        /** Informational op name for a deploy (no circuit name); see [ContractOperationListener]. */
        private const val DEPLOY_OP_NAME = "deploy"

        /** Create a contract handle using the DSL builder. */
        fun create(config: MidnightConfig, block: ContractBuilder.() -> Unit): MidnightContract {
            val builder = ContractBuilder().apply(block)
            return builder.build(config)
        }

        /**
         * Convert `compactc`-generated ES module JavaScript into the
         * plain-script form QuickJs can evaluate. Strips two kinds of
         * declarations:
         *
         *  - Top-level `import …` lines — the IIFE-wrapped
         *    `compact-runtime-iife.js` we load before the contract
         *    already exposes `__compactRuntime` as a top-level `var`,
         *    so the import is redundant.
         *  - `export ` prefix on `var` / `let` / `const` / `class` /
         *    `function` / `async function` declarations. The
         *    declarations themselves stay; only the keyword is
         *    removed.
         *
         * Idempotent: an already-IIFE file (no `import`/`export`
         * lines) round-trips unchanged.
         *
         * Public/internal so the test suite can pin the contract.
         */
        internal fun normalizeContractJs(raw: String): String =
            raw.lineSequence()
                .mapNotNull { line ->
                    val trimmed = line.trimStart()
                    when {
                        trimmed.startsWith("import ") -> null
                        trimmed.startsWith("export ") -> {
                            val indent = line.substring(0, line.length - trimmed.length)
                            indent + trimmed.removePrefix("export ")
                        }
                        else -> line
                    }
                }
                .joinToString("\n")
    }
}

/**
 * Pure seam (unit-tested) behind [MidnightContract.observeLedger]: from a block-tick flow, fetch
 * the contract state hex on each tick (plus an immediate first read via [onStart]) and emit only
 * when it CHANGES — so the expensive QuickJs decode downstream runs once per real state change,
 * not once per block.
 */
internal fun ledgerStateHexSignals(
    ticks: Flow<Unit>,
    fetchState: suspend () -> String,
): Flow<String> =
    ticks.onStart { emit(Unit) }
        .map { fetchState() }
        .distinctUntilChanged()
