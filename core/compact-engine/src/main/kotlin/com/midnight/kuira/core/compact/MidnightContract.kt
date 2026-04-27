package com.midnight.kuira.core.compact

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
    private val coinPublicKey: ByteArray,
) {
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
    ): TransactionReceipt {
        val prepared = prepare(circuitName, *args, onProgress = onProgress)

        onProgress?.invoke(ContractCallStage.Balancing)
        val balancer = config.getBalancer()
        val balanceStart = System.currentTimeMillis()
        val balancedTxHex = try {
            balancer.balanceTransaction(prepared.provenTxHex)
        } catch (e: Exception) {
            throw ContractCallException.BalancingFailed("Balance failed: ${e.message}", e)
        }
        val balanceMs = System.currentTimeMillis() - balanceStart

        onProgress?.invoke(ContractCallStage.Submitting)
        val submitStart = System.currentTimeMillis()
        try {
            balancer.submitTransaction(balancedTxHex)
        } catch (e: Exception) {
            throw ContractCallException.SubmissionFailed("Submit failed: ${e.message}", e)
        }
        val submitMs = System.currentTimeMillis() - submitStart

        return TransactionReceipt(
            txHash = null,
            status = TransactionStatus.SUBMITTED,
            timings = prepared.timings.copy(balanceMs = balanceMs, submitMs = submitMs),
            provenTxHex = prepared.provenTxHex,
        )
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
                coinPublicKey = coinPublicKey,
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

    /** DSL builder for creating a [MidnightContract]. */
    class ContractBuilder internal constructor() {
        /** Contract name (used for proving key management). */
        var name: String? = null

        /** Contract JavaScript as an InputStream (IIFE format). */
        var contractJs: InputStream? = null

        /** Deployed contract address (64 hex chars). */
        var address: String? = null

        /** Initial private state as a Kotlin map (auto-converted to JS). */
        var initialPrivateState: Map<String, Any?> = emptyMap()

        /** Coin public key (32 bytes). Required. */
        var coinPublicKey: ByteArray? = null

        private val witnesses = mutableMapOf<String, WitnessProvider>()

        /** Register a witness provider for a named witness. */
        fun witness(name: String, provider: WitnessProvider) {
            witnesses[name] = provider
        }

        internal fun build(config: MidnightConfig): MidnightContract {
            val jsStream = requireNotNull(contractJs) { "contractJs is required" }
            val addr = requireNotNull(address) { "address is required" }
            val cpk = requireNotNull(coinPublicKey) { "coinPublicKey is required" }

            require(addr.length == CONTRACT_ADDRESS_HEX_LENGTH &&
                addr.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "address must be $CONTRACT_ADDRESS_HEX_LENGTH hex characters"
            }

            val jsContent = jsStream.use { it.bufferedReader().readText() }

            return MidnightContract(
                config = config,
                contractJsContent = jsContent,
                contractAddress = addr,
                witnesses = witnesses.toMap(),
                initialPrivateStateMap = initialPrivateState,
                coinPublicKey = cpk,
            )
        }
    }

    companion object {
        private const val CONTRACT_ADDRESS_HEX_LENGTH = 64

        /** Create a contract handle using the DSL builder. */
        fun create(config: MidnightConfig, block: ContractBuilder.() -> Unit): MidnightContract {
            val builder = ContractBuilder().apply(block)
            return builder.build(config)
        }
    }
}
