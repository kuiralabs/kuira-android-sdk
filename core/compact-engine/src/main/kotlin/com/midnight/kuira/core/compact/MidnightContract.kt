package com.midnight.kuira.core.compact

import android.util.Log
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
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
    private val constructorArgs: List<Any?> = emptyList(),
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
        // Opaque unshielded-funding JSON (built by the SDK) for circuits that receiveUnshielded value
        // (e.g. a treasury deposit). Null → no offer, identical to a plain call. Declared before
        // onProgress so a trailing-lambda progress callback still binds to onProgress.
        unshieldedFundingJson: String? = null,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ): TransactionReceipt = config.runTracked(circuitName) {
        requireWriteCapable()
        requireAddress("call")
        val balancer = config.getBalancer()
        var lastError: Exception? = null

        repeat(CALL_MAX_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                // The prior attempt was rejected as a DUPLICATE intent — built against stale contract
                // state (the indexer lagged the chain). Pause for the indexer to catch up, then
                // re-read + rebuild against fresher state.
                Log.i(TAG, "'$circuitName' rejected as a duplicate (stale state); retry ${attempt + 1}/$CALL_MAX_ATTEMPTS")
                delay(CALL_DUPLICATE_RETRY_MS)
            }

            val prepared = prepare(
                circuitName, *args,
                onProgress = onProgress,
                unshieldedFundingJson = unshieldedFundingJson,
            )

            onProgress?.invoke(ContractCallStage.Balancing)
            val balanceStart = System.currentTimeMillis()
            try {
                balancer.balanceAndSubmit(prepared.provenTxHex) { balanceProgress ->
                    onProgress?.invoke(ContractCallStage.BalancingDetail(balanceProgress))
                }
            } catch (e: Exception) {
                lastError = e
                // On a healthy chain a contract-call rejection with the replay/TTL code means the
                // intent was built from STALE state — re-read + rebuild fixes it. Else it's fatal.
                if (isDuplicateIntentRejection(e)) return@repeat
                val message = e.message ?: ""
                if (message.contains("Balance failed") || message.contains("balance")) {
                    throw ContractCallException.BalancingFailed("Balance failed: $message", e)
                }
                throw ContractCallException.SubmissionFailed("Submit failed: $message", e)
            }
            val balanceMs = System.currentTimeMillis() - balanceStart

            // Submitted OK → wait for the indexer to reflect this call so the NEXT call reads fresh
            // state and doesn't rebuild the identical (replay-rejected) intent.
            prepared.onChainStateHex?.let { before ->
                onProgress?.invoke(ContractCallStage.Submitting)
                awaitContractStateIndexed(before)
            }

            return@runTracked TransactionReceipt(
                txHash = null,
                status = TransactionStatus.SUBMITTED,
                timings = prepared.timings.copy(balanceMs = balanceMs, submitMs = 0),
                provenTxHex = prepared.provenTxHex,
            )
        }

        throw ContractCallException.SubmissionFailed(
            "'$circuitName' still rejected as a duplicate after $CALL_MAX_ATTEMPTS attempts — the indexer's contract state may be lagging the chain.",
            lastError,
        )
    }

    /**
     * Whether a [balanceAndSubmit] failure is the node's replay/TTL rejection (custom error 182).
     * On a healthy chain that means the call's intent was built from stale contract state, so a
     * re-read + rebuild can clear it (see [call]'s retry loop).
     */
    private fun isDuplicateIntentRejection(e: Exception): Boolean =
        (e.message ?: "").contains(DUPLICATE_INTENT_NODE_ERROR)

    /**
     * Poll the indexer until the contract state reflects a just-submitted call (i.e. differs from
     * [beforeStateHex]), so the NEXT call reads fresh state instead of rebuilding the identical
     * intent (which the chain's replay guard rejects as `IntentAlreadyExists` / custom error 182).
     * Best-effort: returns after [STATE_INDEX_TIMEOUT_MS] even if unchanged (e.g. a no-op call or a
     * stalled indexer), so a stuck indexer can't hang the call forever.
     */
    private suspend fun awaitContractStateIndexed(beforeStateHex: String) {
        val indexed = pollUntilChanged(beforeStateHex, STATE_INDEX_TIMEOUT_MS, STATE_INDEX_POLL_MS) {
            config.fetchContractState(contractAddress)
        }
        if (!indexed) {
            Log.w(TAG, "contract state not indexed within ${STATE_INDEX_TIMEOUT_MS}ms; a rapid next call may read stale state")
        }
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
        // Opaque unshielded-funding JSON (built by the SDK) for circuits that receiveUnshielded value.
        // Null → no offer, identical to a plain call. Declared before onProgress so a trailing-lambda
        // progress callback still binds to onProgress. See CircuitExecutor.executeCircuit.
        unshieldedFundingJson: String? = null,
        onProgress: (suspend (ContractCallStage) -> Unit)? = null,
    ): PreparedTransaction {
        requireWriteCapable()
        requireAddress("prepare")
        val jsArgs = ArgConverter.toJsExpressions(*args)
        val privateStateJs = ArgConverter.toJsObjectLiteral(initialPrivateStateMap)

        // Step 1: Fetch on-chain state + chain tip (ledger params + block time, one round-trip)
        onProgress?.invoke(ContractCallStage.FetchingState)
        val fetchStart = System.currentTimeMillis()
        val onChainStateHex: String
        val chainTip: MidnightConfig.ChainTip
        try {
            onChainStateHex = config.fetchContractState(contractAddress)
            chainTip = config.fetchChainTip()
        } catch (e: ContractCallException) {
            throw e
        } catch (e: Exception) {
            throw ContractCallException.StateFetchFailed("State fetch failed: ${e.message}", e)
        }
        val ledgerParamsHex = chainTip.ledgerParametersHex
        val fetchMs = System.currentTimeMillis() - fetchStart

        // Anchor TTL + the native gas-query context to CHAIN block time, not the device wall-clock:
        // wall-clock drift past the chain's global_ttl is what the node rejects (custom error 182).
        // The indexer reports the block time in millis; convert to seconds for block_time_secs.
        val chainNowMs = chainTip.blockTimeMillis
        val blockTimeSecs = chainNowMs / 1000

        // Size the intent TTL to the chain's global_ttl — a fixed window overshoots a
        // tight node (e.g. localnet ~100s) and the tx is rejected (custom error 182).
        val ttlSecs = IntentTtl.ttlSeconds(
            nowMs = chainNowMs,
            globalTtlSecs = ContractRuntime.ledgerGlobalTtlSeconds(ledgerParamsHex),
        )

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
                ttlSecs = ttlSecs,
                blockTimeSecs = blockTimeSecs,
                // A constructor-args contract's circuit-call path calls initialState() (then swaps in
                // on-chain state), so pass the args to satisfy its validation. Empty = no-arg contract.
                constructorArgs = ArgConverter.toJsExpressions(*constructorArgs.toTypedArray()),
                unshieldedFundingJson = unshieldedFundingJson,
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
        val rawProvenTxHex = try {
            config.proofProvider.prove(executionResult.unprovenTxHex).trim()
        } catch (e: Exception) {
            throw ContractCallException.ProvingFailed("Proving failed: ${e.message}", e)
        }

        // Step 3b: Sign the unshielded funding offer AFTER proving. Proving rewrites the contract
        // call's erased serialization, so the offer (attached unsigned at assembly) must be signed
        // now — signing before proving yields a signature the node rejects (error 175). Only runs
        // when the caller funded a receiveUnshielded; a plain call leaves the tx untouched.
        val provenTxHex = unshieldedFundingJson?.let { fundingJson ->
            val nightKey = JSONObject(fundingJson).optString("night_private_key").ifEmpty {
                throw ContractCallException.ProvingFailed(
                    "Unshielded funding missing night_private_key; cannot sign the offer.", null,
                )
            }
            val signed = ContractRuntime.signProvenOffer(rawProvenTxHex, nightKey)
            if (signed == null || signed.startsWith("{\"error")) {
                throw ContractCallException.ProvingFailed("Signing the unshielded offer failed: $signed", null)
            }
            signed.trim()
        } ?: rawProvenTxHex
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
            onChainStateHex = onChainStateHex,
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
     * Resilient: a transient state-fetch failure (indexer hiccup) skips that tick rather than
     * terminating the flow, and if the block stream itself errors the SDK config degrades to
     * polling — so the flow keeps running until the collector cancels. Fetch+decode run on
     * [Dispatchers.IO].
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

        // Size the deploy intent TTL to the chain's global_ttl and anchor it (+ the native
        // gas-query context) to CHAIN block time — a fixed window or device wall-clock overshoots a
        // tight node (e.g. localnet ~100s) and the tx is rejected (custom error 182). Falls back to
        // the default window + wall-clock if the indexer is unreachable (balancing would fail later
        // anyway).
        val chainTip = try {
            config.fetchChainTip()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "chain-tip fetch failed, using default TTL window + wall-clock: ${e.message}")
            null
        }
        val globalTtlSecs = chainTip?.ledgerParametersHex?.let { ContractRuntime.ledgerGlobalTtlSeconds(it) }
        val nowMs = chainTip?.blockTimeMillis ?: System.currentTimeMillis()
        val ttlSecs = IntentTtl.ttlSeconds(nowMs, globalTtlSecs)

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
                ttlSecs = ttlSecs,
                constructorArgs = ArgConverter.toJsExpressions(*constructorArgs.toTypedArray()),
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

        /**
         * Arguments for the contract constructor, in declaration order, as Kotlin
         * values (auto-marshaled to JS like circuit call args). Empty for a no-arg
         * constructor (the counter/bboard case). A configurable contract — e.g. a
         * multisig taking its signer set + threshold — passes them here.
         */
        var constructorArgs: List<Any?> = emptyList()

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
                constructorArgs = constructorArgs,
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
        private const val TAG = "MidnightContract"
        private const val CONTRACT_ADDRESS_HEX_LENGTH = 64

        /** Informational op name for a deploy (no circuit name); see [ContractOperationListener]. */
        private const val DEPLOY_OP_NAME = "deploy"

        /** Bound on waiting for a call to be indexed before [call] returns (see [awaitContractStateIndexed]). */
        private const val STATE_INDEX_TIMEOUT_MS = 30_000L
        private const val STATE_INDEX_POLL_MS = 1_000L

        /** Attempts [call] makes when the node rejects the intent as a stale-state duplicate. */
        private const val CALL_MAX_ATTEMPTS = 4

        /** Pause before a retry so the indexer can index the prior tx and expose fresh state. */
        private const val CALL_DUPLICATE_RETRY_MS = 6_000L

        /**
         * Node custom error for `TransactionApplicationError` (replay guard / TTL). On a healthy
         * chain a contract-call rejection with this code means the intent was built from stale
         * contract state — re-reading and rebuilding clears it.
         */
        private const val DUPLICATE_INTENT_NODE_ERROR = "182"

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
        // Skip a tick whose fetch fails transiently (an indexer hiccup) instead of terminating the
        // whole stream — the next tick recovers (mirrors the Kicks StatePoller). Cancellation still
        // propagates so the flow stops cleanly when the collector goes away.
        .mapNotNull {
            try {
                fetchState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        .distinctUntilChanged()

/**
 * Poll [fetch] every [pollMs] until it returns a value different from [before], or [timeoutMs]
 * elapses. Returns true if a change was observed, false on timeout. A fetch that throws is treated
 * as "no change yet" (keep polling); cancellation propagates. Independent of [MidnightContract]
 * state so it's unit-testable under virtual time (coroutine-time [withTimeoutOrNull], not wall-clock).
 */
internal suspend fun pollUntilChanged(
    before: String,
    timeoutMs: Long,
    pollMs: Long,
    fetch: suspend () -> String?,
): Boolean = withTimeoutOrNull(timeoutMs) {
    while (true) {
        delay(pollMs)
        val latest = try {
            fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (latest != null && latest != before) break
    }
    true
} ?: false
