package com.midnight.kuira.core.ledger.api

import android.util.Log
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.crypto.proving.LocalProver
import com.midnight.kuira.core.crypto.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.ledger.fee.DustActionsBuilder
import com.midnight.kuira.core.ledger.model.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout

/**
 * Orchestrates transaction submission and confirmation for Midnight blockchain.
 *
 * **Process (WebSocket-based - the correct way):**
 * 1. Serialize signed transaction (SCALE codec)
 * 2. Prove transaction via proof server
 * 3. Seal proven transaction
 * 4. Submit via WebSocket and wait for finalization from NODE
 * 5. Mark UTXOs as SPENT only after node confirms finalization
 * 6. Return result
 *
 * **Why WebSocket instead of Indexer:**
 * - Indexer can lag behind the node (causes race conditions)
 * - WebSocket gives us real-time confirmation directly from the node
 * - This matches the Midnight SDK's implementation exactly
 *
 * **Dust Fee Payment:**
 * Use `submitWithFees()` to automatically build and pay dust fees.
 *
 * @property nodeRpcClient Client for submitting transactions to node
 * @property proofServerClient Client for proving transactions
 * @property indexerClient Client for tracking transactions (used for UTXO updates after finalization)
 * @property serializer Serializes signed Intent to SCALE codec
 * @property dustActionsBuilder Builder for dust fee payment actions (optional)
 */
class TransactionSubmitter(
    private val nodeRpcClient: NodeRpcClient,
    private val proofServerClient: ProofServerClient,
    private val indexerClient: IndexerClient,
    private val serializer: TransactionSerializer,
    private val utxoManager: UtxoManager,
    private val dustActionsBuilder: DustActionsBuilder? = null,
    private val dustRepository: DustRepository? = null,
    private val provingKeyManager: ProvingKeyManager? = null,
    /** Current proving mode — LOCAL or REMOTE. Defaults to LOCAL when keys are available. */
    @Volatile var provingMode: ProvingMode = ProvingMode.DEFAULT,
    /**
     * Optional: provides the current network for creating fresh clients
     * per-transaction. When set, nodeRpcClient/proofServerClient/indexerClient
     * are used as fallbacks only if the network hasn't changed since startup.
     * Part of the reactive network architecture (Option C migration).
     */
    private val networkClientProvider: NetworkClientProvider? = null,
) {

    /**
     * Functional interface for resolving network-aware clients.
     * Injected by the Hilt module; creates fresh clients for the
     * currently-selected network.
     */
    fun interface NetworkClientProvider {
        fun provide(): ResolvedClients
    }

    /**
     * Clients bound to a specific network, identified by [networkId].
     * The [networkId] is used for cache-invalidation: when the user
     * switches networks in Settings, the next [resolveClients] call
     * detects the name change and creates fresh HTTP clients.
     */
    data class ResolvedClients(
        val networkId: String,
        val node: NodeRpcClient,
        val proofServer: ProofServerClient,
        val indexer: IndexerClient,
    )

    /** Cached clients for the currently-selected network. */
    @Volatile private var cachedResolved: ResolvedClients? = null

    /**
     * Resolve the clients to use for this transaction.
     *
     * Caches per-network — only creates fresh HTTP clients when the
     * selected network has changed (different [ResolvedClients.networkId]).
     * Old clients are not explicitly closed (Ktor HttpClient is lightweight
     * and GC-safe), but reusing avoids connection-pool proliferation.
     *
     * **Thread safety:** The `@Volatile` field plus the immutable
     * [ResolvedClients] data class is sufficient for v1.0 where
     * transactions are submitted one at a time. If concurrent
     * submissions ever become necessary, guard this method with a lock.
     */
    private fun resolveClients(): ResolvedClients {
        val provider = networkClientProvider
            ?: return ResolvedClients("default", nodeRpcClient, proofServerClient, indexerClient)

        val current = cachedResolved
        val fresh = provider.provide()
        if (current != null && current.networkId == fresh.networkId) {
            return current // same network — reuse cached clients
        }
        cachedResolved = fresh
        return fresh
    }

    /** Which proving mode was used for the last transaction (for display). */
    var lastProvingMode: ProvingMode = provingMode
        private set

    /**
     * Prove a transaction using the configured proving mode.
     *
     * LOCAL: proves on-device using cached keys. Falls back to REMOTE if keys missing or proving fails.
     * REMOTE: sends to proof server via HTTP.
     */
    private suspend fun proveTransaction(unprovenTxHex: String): String {
        return when (provingMode) {
            ProvingMode.LOCAL -> {
                if (provingKeyManager?.hasWalletKeys() != true) {
                    throw IllegalStateException("Local proving selected but proving keys are not downloaded")
                }
                Log.d(TAG, "Proving locally (keys at ${provingKeyManager.keysDir})")
                val result = withContext(Dispatchers.Default) {
                    LocalProver.proveTransaction(
                        unprovenTxHex,
                        provingKeyManager.keysDir.absolutePath,
                    )
                } ?: throw IllegalStateException("Local proving failed — check proving keys")
                Log.i(TAG, "Local proving succeeded")
                lastProvingMode = ProvingMode.LOCAL
                result
            }
            ProvingMode.REMOTE -> {
                Log.d(TAG, "Proving via remote proof server")
                lastProvingMode = ProvingMode.REMOTE
                resolveClients().proofServer.proveTransaction(unprovenTxHex)
            }
        }
    }

    /**
     * Submit a signed transaction and wait for finalization.
     *
     * **Steps:**
     * 1. Serialize transaction to SCALE codec (unproven)
     * 2. Prove transaction via proof server
     * 3. Seal proven transaction (transform binding commitment)
     * 4. Submit via WebSocket and wait for NODE's finalized status
     * 5. Mark UTXOs as SPENT (after node confirms finalization)
     * 6. Return result
     *
     * **Why we wait for NODE finalization (not indexer):**
     * - Node's "finalized" status means the transaction is PERMANENT
     * - Indexer can lag behind the node, causing race conditions
     * - This matches the Midnight SDK's implementation exactly
     *
     * @param signedIntent Signed Intent with all signatures
     * @param fromAddress Sender's address (used for UTXO updates)
     * @param timeoutMs Maximum time to wait for finalization (default 60s)
     * @return SubmissionResult indicating success or failure
     */
    suspend fun submitAndWait(
        signedIntent: Intent,
        fromAddress: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): SubmissionResult {
        // Step 1: Serialize unproven transaction
        val unprovenTxHex = try {
            serializer.serialize(signedIntent)
        } catch (e: Exception) {
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Serialization failed: ${e.message}"
            )
        }

        // Step 2: Prove transaction via proof server
        val provenTxHex = try {
            proveTransaction(unprovenTxHex)
        } catch (e: ProofServerException) {
            Log.e(TAG, "❌ Proof server error", e)
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Proof generation failed: ${e.message}"
            )
        }

        // Step 3: Seal the proven transaction
        val finalizedTxHex = try {
            serializer.sealProvenTransaction(provenTxHex)
                ?: throw IllegalStateException("Sealing returned null")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sealing error", e)
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Transaction sealing failed: ${e.message}"
            )
        }

        // Track which UTXOs are being spent (for UTXO state management)
        val spentUtxoIntents = signedIntent.guaranteedUnshieldedOffer?.inputs?.map {
            it.intentHash to it.outputNo
        } ?: emptyList()
        val spentUtxoIds = signedIntent.guaranteedUnshieldedOffer?.inputs?.map { it.identifier() } ?: emptyList()

        // Step 4: Submit via WebSocket and WAIT for NODE's finalized status
        // This is the correct approach - we wait for the NODE to confirm finalization,
        // not the indexer (which can lag behind).
        Log.d(TAG, "Submitting transaction via WebSocket and waiting for finalization...")

        val finalizationResult = try {
            resolveClients().node.submitAndWaitForFinalization(finalizedTxHex, timeoutMs)
        } catch (e: TransactionRejected) {
            // Check if this is a stale UTXO error (error 115)
            if (e.isStaleUtxo) {
                Log.w(TAG, "⚠️ Stale UTXO detected (error 115) - UTXOs were already spent")
                return SubmissionResult.StaleUtxo(
                    failedUtxoIds = spentUtxoIds,
                    reason = "Some UTXOs were already spent. Wallet is syncing to get latest balance."
                )
            }

            return SubmissionResult.Failed(
                txHash = e.txHash,
                reason = "Transaction rejected by node: ${e.reason}"
            )
        } catch (e: NodeRpcException) {
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Node RPC error: ${e.message}"
            )
        }

        // Step 5: Handle finalization result
        return when (finalizationResult) {
            is TransactionFinalizationResult.Finalized -> {
                Log.i(TAG, "✅ Transaction FINALIZED: ${finalizationResult.txHash}")
                Log.d(TAG, "   Block: ${finalizationResult.blockHash}")

                // NOW mark UTXOs as SPENT - the node has confirmed the transaction
                if (spentUtxoIntents.isNotEmpty()) {
                    utxoManager.markUtxosAsSpentByIntent(spentUtxoIntents)
                    Log.d(TAG, "Marked ${spentUtxoIntents.size} UTXOs as SPENT (transaction finalized)")
                }

                SubmissionResult.Success(
                    txHash = finalizationResult.txHash,
                    blockHeight = finalizationResult.blockHeight ?: 0L
                )
            }

            is TransactionFinalizationResult.InBlock -> {
                // Transaction is in a block but not finalized yet
                // This shouldn't happen with our timeout, but handle gracefully
                Log.w(TAG, "Transaction in block but not finalized: ${finalizationResult.txHash}")

                // Still mark as spent - it's very likely to be finalized
                if (spentUtxoIntents.isNotEmpty()) {
                    utxoManager.markUtxosAsSpentByIntent(spentUtxoIntents)
                }

                SubmissionResult.Pending(
                    txHash = finalizationResult.txHash,
                    reason = "Transaction in block, waiting for finalization"
                )
            }

            is TransactionFinalizationResult.Timeout -> {
                Log.w(TAG, "Transaction finalization timed out: ${finalizationResult.txHash}")
                SubmissionResult.Pending(
                    txHash = finalizationResult.txHash,
                    reason = "Finalization timeout (transaction may still be processing)"
                )
            }

            is TransactionFinalizationResult.Dropped -> {
                Log.e(TAG, "Transaction dropped: ${finalizationResult.reason}")
                SubmissionResult.Failed(
                    txHash = finalizationResult.txHash,
                    reason = "Transaction dropped from pool: ${finalizationResult.reason}"
                )
            }

            is TransactionFinalizationResult.Invalid -> {
                Log.e(TAG, "Transaction invalid: ${finalizationResult.reason}")
                SubmissionResult.Failed(
                    txHash = finalizationResult.txHash,
                    reason = "Transaction invalid: ${finalizationResult.reason}"
                )
            }

            is TransactionFinalizationResult.Usurped -> {
                Log.e(TAG, "Transaction usurped (replaced)")
                SubmissionResult.Failed(
                    txHash = finalizationResult.txHash,
                    reason = "Transaction replaced by another"
                )
            }
        }
    }

    /**
     * Wait for transaction confirmation via indexer subscription.
     *
     * NOTE: This is now only used as a backup/secondary confirmation.
     * The primary confirmation comes from the WebSocket subscription to the node.
     *
     * **Strategy:**
     * - Subscribe to unshielded transactions for sender address
     * - Filter for matching transaction hash
     * - When found, update local UTXO database (CRITICAL!)
     * - Return when found
     *
     * **IMPORTANT:** This function MUST update UTXOs when it finds the transaction.
     * The SubscriptionManager (in BalanceViewModel) might not be running or might
     * miss the transaction due to timing. This ensures UTXOs are always updated
     * when a transaction is confirmed.
     *
     * @param address Sender's address
     * @param expectedTxHash Expected transaction hash
     * @return SubmissionResult.Success when found
     */
    private suspend fun waitForConfirmation(
        address: String,
        expectedTxHash: String
    ): SubmissionResult {

        // Subscribe to transactions for this address
        val txFlow: Flow<UnshieldedTransactionUpdate> = indexerClient
            .subscribeToUnshieldedTransactions(address)

        // Wait for our transaction to appear
        val update = txFlow
            .filter { it is UnshieldedTransactionUpdate.Transaction }
            .map { it as UnshieldedTransactionUpdate.Transaction }
            .firstOrNull { tx ->
                tx.transaction.hash.removePrefix("0x") == expectedTxHash
            }

        return if (update != null) {
            utxoManager.processUpdate(update)
            Log.d(TAG, "Transaction confirmed: $expectedTxHash")
            SubmissionResult.Success(
                txHash = expectedTxHash,
                blockHeight = update.transaction.id.toLong()
            )
        } else {
            SubmissionResult.Failed(
                txHash = expectedTxHash,
                reason = "Transaction not found in indexer stream"
            )
        }
    }

    /**
     * Submit transaction WITH automatic dust fee payment.
     *
     * **Process:**
     * 1. Serialize transaction to calculate fee
     * 2. Build dust actions to cover fee (DustActionsBuilder)
     * 3. Add dust actions to intent
     * 4. Submit transaction with fees
     * 5. Wait for confirmation
     * 6. On success: mark dust coins as SPENT
     * 7. On failure: rollback dust coins to AVAILABLE
     *
     * **Requirements:**
     * - DustActionsBuilder must be provided in constructor
     * - User must have sufficient dust balance
     * - DustLocalState must be initialized for address
     *
     * @param signedIntent Signed Intent (without dust actions)
     * @param ledgerParamsHex SCALE-serialized ledger parameters (hex)
     * @param fromAddress Sender's address
     * @param seed 32-byte seed for deriving DustSecretKey
     * @param timeoutMs Maximum time to wait for finalization (default 60s)
     * @return SubmissionResult indicating success or failure
     * @throws IllegalStateException if DustActionsBuilder not provided
     * @throws NodeRpcException if submission to node fails
     */
    suspend fun submitWithFees(
        signedIntent: Intent,
        ledgerParamsHex: String,
        fromAddress: String,
        seed: ByteArray,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): SubmissionResult {
        // Validate DustActionsBuilder is available
        checkNotNull(dustActionsBuilder) {
            "DustActionsBuilder required for fee payment. " +
            "Initialize TransactionSubmitter with DustActionsBuilder."
        }

        // Validate DustRepository is available
        checkNotNull(dustRepository) {
            "DustRepository required for dust fee payment. " +
            "Initialize TransactionSubmitter with DustRepository."
        }

        // Step 1: Serialize base transaction (without dust)
        val baseSerializedHex = try {
            serializer.serialize(signedIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Serialization failed", e)
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Serialization failed: ${e.message}"
            )
        }

        // Step 2: Prove and seal base transaction for fee calculation
        val baseProvenHex = try {
            proveTransaction(baseSerializedHex)
        } catch (e: ProofServerException) {
            Log.e(TAG, "Failed to prove base transaction", e)
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Proof generation failed: ${e.message}"
            )
        }

        val ffiSerializer = serializer as? com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
            ?: throw IllegalStateException("FFI serializer required")

        val baseSealedHex = ffiSerializer.sealProvenTransaction(baseProvenHex)
            ?: throw IllegalStateException("Sealing failed")

        // Step 3: Calculate fee on sealed transaction
        val dustActions = try {
            dustActionsBuilder.buildDustActions(
                transactionHex = baseSealedHex,  // Use SEALED transaction
                ledgerParamsHex = ledgerParamsHex,
                address = fromAddress,
                seed = seed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to build dust actions", e)
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Failed to build dust actions: ${e.message}"
            )
        }

        if (dustActions == null || !dustActions.isSuccess()) {
            Log.e(TAG, "Insufficient dust balance (required: ${dustActions?.totalFee} Specks)")
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Insufficient dust balance to cover fee (required: ${dustActions?.totalFee} Specks)"
            )
        }

        // Step 3: Load DustLocalState
        val dustState = dustRepository.loadState(fromAddress)
        if (dustState == null) {
            Log.e(TAG, "Failed to load dust state for $fromAddress")
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Failed to load dust state"
            )
        }

        try {
            // Step 4: Build JSON for selected dust UTXOs
            val dustUtxosJson = buildDustUtxosJson(dustActions.utxoIndices, dustActions.totalFee)

            // Step 5: Extract transaction components from signed intent
            val offer = signedIntent.guaranteedUnshieldedOffer!!
            val inputs = offer.inputs
            val outputs = offer.outputs
            val signatures = offer.signatures

            // Step 6: Serialize WITH dust (calls state.spend() internally in Rust FFI)
            val ffiSerializer = serializer as? com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
                ?: throw IllegalStateException("FFI serializer required for dust fee payment")

            // IMPORTANT: Do NOT call getSigningMessageForInput() again here!
            // The binding_randomness from the original signing (when user signed the transaction)
            // is already stored in the FFI layer. Calling getSigningMessageForInput() would
            // generate a NEW random binding_randomness, which would cause signature verification
            // to fail because the signature was created for the ORIGINAL binding_randomness.
            //
            // The stored binding_randomness will be automatically used by serializeWithDust().

            val unprovenTxHex = ffiSerializer.serializeWithDust(
                inputs = inputs,
                outputs = outputs,
                signatures = signatures,
                dustState = dustState,
                seed = seed,
                dustUtxosJson = dustUtxosJson,
                ttl = signedIntent.ttl
            )

            // NOTE: Do NOT save dust state here!
            // The FFI has modified dustState in memory, but we must NOT persist it
            // until the node accepts the transaction. If we save now and the node
            // rejects (e.g., error 115), the dust state would be corrupted.
            // Save happens AFTER successful node submission (Step 10).

            // Step 7: Prove transaction
            val provenTxHex = try {
                proveTransaction(unprovenTxHex)
            } catch (e: ProofServerException) {
                Log.e(TAG, "❌ Proof server error", e)
                // Don't save dust state - it's still clean on disk
                return SubmissionResult.Failed(
                    txHash = null,
                    reason = "Proof generation failed: ${e.message}"
                )
            }

            // Step 8: Seal proven transaction
            val sealedTxHex = ffiSerializer.sealProvenTransaction(provenTxHex)
                ?: throw IllegalStateException("Sealing returned null")

            // Step 9: Get the Midnight transaction hash
            val midnightTxHash = ffiSerializer.getTransactionHash(sealedTxHex)

            // Track which UTXOs are being spent (for error recovery)
            val spentUtxoIntents = inputs.map { it.intentHash to it.outputNo }
            val spentUtxoIds = inputs.map { it.identifier() }

            // Step 10: Submit via WebSocket and WAIT for NODE's finalized status
            Log.d(TAG, "Submitting transaction via WebSocket and waiting for finalization...")

            val finalizationResult = try {
                resolveClients().node.submitAndWaitForFinalization(sealedTxHex, timeoutMs)
            } catch (e: TransactionRejected) {
                // Don't save dust state - it's still clean on disk
                if (e.isStaleUtxo) {
                    Log.w(TAG, "⚠️ Stale UTXO detected (error 115) - UTXOs were already spent")
                    return SubmissionResult.StaleUtxo(
                        failedUtxoIds = spentUtxoIds,
                        reason = "Some UTXOs were already spent. Wallet is syncing to get latest balance."
                    )
                }
                return SubmissionResult.Failed(
                    txHash = e.txHash,
                    reason = "Transaction rejected by node: ${e.reason}"
                )
            } catch (e: NodeRpcException) {
                return SubmissionResult.Failed(
                    txHash = null,
                    reason = "Node RPC error: ${e.message}"
                )
            }

            // Step 11: Handle finalization result
            return when (finalizationResult) {
                is TransactionFinalizationResult.Finalized -> {
                    Log.i(TAG, "✅ Transaction FINALIZED: ${finalizationResult.txHash}")

                    // DELETE dust cache - the old UTXO is now pending (hidden from balance)
                    // and the new UTXO (change) will come from blockchain events on next sync.
                    // This forces a fresh dust sync before the next transaction.
                    dustRepository.deleteState(fromAddress)
                    Log.d(TAG, "Dust state cache cleared (will re-sync before next tx)")

                    // Mark UTXOs as SPENT (transaction is confirmed)
                    if (spentUtxoIntents.isNotEmpty()) {
                        utxoManager.markUtxosAsSpentByIntent(spentUtxoIntents)
                        Log.d(TAG, "Marked ${spentUtxoIntents.size} UTXOs as SPENT")
                    }

                    SubmissionResult.Success(
                        txHash = finalizationResult.txHash,
                        blockHeight = finalizationResult.blockHeight ?: 0L
                    )
                }

                is TransactionFinalizationResult.InBlock -> {
                    Log.w(TAG, "Transaction in block but not finalized")
                    // DELETE dust cache - forces re-sync before next tx
                    dustRepository.deleteState(fromAddress)
                    if (spentUtxoIntents.isNotEmpty()) {
                        utxoManager.markUtxosAsSpentByIntent(spentUtxoIntents)
                    }
                    SubmissionResult.Pending(
                        txHash = finalizationResult.txHash,
                        reason = "Transaction in block, waiting for finalization"
                    )
                }

                is TransactionFinalizationResult.Timeout -> {
                    Log.w(TAG, "Transaction finalization timed out")
                    // Don't save dust state - unclear if transaction will succeed
                    SubmissionResult.Pending(
                        txHash = finalizationResult.txHash,
                        reason = "Finalization timeout (transaction may still be processing)"
                    )
                }

                is TransactionFinalizationResult.Dropped,
                is TransactionFinalizationResult.Invalid,
                is TransactionFinalizationResult.Usurped -> {
                    Log.e(TAG, "Transaction failed: $finalizationResult")
                    // Don't save dust state - transaction failed
                    val (txHash, reason) = when (finalizationResult) {
                        is TransactionFinalizationResult.Dropped -> finalizationResult.txHash to finalizationResult.reason
                        is TransactionFinalizationResult.Invalid -> finalizationResult.txHash to finalizationResult.reason
                        is TransactionFinalizationResult.Usurped -> finalizationResult.txHash to "Transaction replaced"
                        else -> "" to "Unknown error"
                    }
                    SubmissionResult.Failed(txHash = txHash, reason = reason)
                }
            }

        } finally {
            // Always close dust state
            dustState.close()
        }
    }

    /**
     * Builds JSON array of dust UTXO selections for Rust FFI.
     *
     * Format: [{"utxo_index": 0, "v_fee": "1000000"}, ...]
     *
     * @param utxoIndices Indices of UTXOs to spend
     * @param totalFee Total fee to be paid (only first UTXO pays the fee)
     * @return JSON string for Rust FFI
     */
    private fun buildDustUtxosJson(
        utxoIndices: List<Int>,
        totalFee: java.math.BigInteger
    ): String {
        val utxos = utxoIndices.mapIndexed { orderIndex, utxoIndex ->
            // Only first UTXO pays the full fee, others pay 0
            val vFee = if (orderIndex == 0) totalFee.toString() else "0"
            """{"utxo_index": $utxoIndex, "v_fee": "$vFee"}"""
        }
        return "[${utxos.joinToString(",")}]"
    }

    /**
     * Submit transaction without waiting for confirmation (fire-and-forget).
     *
     * **Use case:** When you don't need to wait for finalization
     *
     * **Steps:**
     * 1. Serialize unproven transaction
     * 2. Prove via proof server
     * 3. Seal proven transaction (transform binding commitment)
     * 4. Submit finalized transaction to node
     *
     * @param signedIntent Signed Intent
     * @return Transaction hash on success
     * @throws NodeRpcException on submission failure
     * @throws ProofServerException if proving fails
     */
    suspend fun submitOnly(signedIntent: Intent): String {
        // Step 1: Serialize unproven transaction
        val unprovenTxHex = serializer.serialize(signedIntent)

        // Step 2: Prove transaction
        val provenTxHex = proveTransaction(unprovenTxHex)

        // Step 2.5: Seal the proven transaction
        val finalizedTxHex = serializer.sealProvenTransaction(provenTxHex)
            ?: throw IllegalStateException("Sealing returned null")

        // Step 3: Submit finalized transaction
        return resolveClients().node.submitTransaction(finalizedTxHex)
    }

    /**
     * Result of transaction submission.
     */
    sealed class SubmissionResult {
        /**
         * Transaction successfully submitted and finalized.
         *
         * @property txHash Transaction hash (32 bytes hex)
         * @property blockHeight Approximate block height where tx was included
         */
        data class Success(
            val txHash: String,
            val blockHeight: Long
        ) : SubmissionResult()

        /**
         * Transaction failed (rejected or error).
         *
         * @property txHash Transaction hash (null if never submitted)
         * @property reason Human-readable failure reason
         */
        data class Failed(
            val txHash: String?,
            val reason: String
        ) : SubmissionResult()

        /**
         * Transaction submitted but confirmation timed out.
         *
         * **Note:** Transaction may still be processing - check indexer manually.
         *
         * @property txHash Transaction hash
         * @property reason Timeout reason
         */
        data class Pending(
            val txHash: String,
            val reason: String
        ) : SubmissionResult()

        /**
         * Transaction failed because UTXO was already spent (stale local data).
         *
         * **Recovery:**
         * 1. Mark failed UTXOs as SPENT in local database
         * 2. Trigger background resync for this address
         * 3. Update UI with new balance
         * 4. User can retry with remaining UTXOs
         *
         * @property failedUtxoIds List of UTXO IDs that were stale (intentHash:outputNo format)
         * @property reason Human-readable explanation
         */
        data class StaleUtxo(
            val failedUtxoIds: List<String>,
            val reason: String = "Some UTXOs were already spent. Wallet is syncing to get latest balance."
        ) : SubmissionResult()
    }

    /**
     * Submit a pre-built unproven transaction (e.g., from ZswapTransferBuilder).
     *
     * Skips serialization — the transaction is already SCALE-encoded.
     * Performs: prove → seal → submit → wait for finalization.
     *
     * @param unprovenTxHex Pre-built hex-encoded unproven transaction
     * @param timeoutMs Maximum time to wait for finalization
     * @return SubmissionResult indicating success or failure
     */
    suspend fun submitPrebuiltTransaction(
        unprovenTxHex: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): SubmissionResult {
        // Step 1: Prove transaction via proof server
        val provenTxHex = try {
            proveTransaction(unprovenTxHex)
        } catch (e: ProofServerException) {
            Log.e(TAG, "Proof server error (shielded)", e)
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Proof generation failed: ${e.message}"
            )
        }

        // Step 2: Seal the proven transaction
        val sealedTxHex = try {
            serializer.sealProvenTransaction(provenTxHex)
                ?: throw IllegalStateException("Sealing returned null")
        } catch (e: Exception) {
            Log.e(TAG, "Sealing error (shielded)", e)
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Transaction sealing failed: ${e.message}"
            )
        }

        // Step 3: Submit via WebSocket and wait for finalization
        Log.d(TAG, "Submitting shielded transaction and waiting for finalization...")

        val finalizationResult = try {
            resolveClients().node.submitAndWaitForFinalization(sealedTxHex, timeoutMs)
        } catch (e: TransactionRejected) {
            return SubmissionResult.Failed(
                txHash = e.txHash,
                reason = "Shielded transaction rejected: ${e.reason}"
            )
        } catch (e: NodeRpcException) {
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Node RPC error: ${e.message}"
            )
        }

        return when (finalizationResult) {
            is TransactionFinalizationResult.Finalized -> {
                Log.i(TAG, "Shielded transaction FINALIZED: ${finalizationResult.txHash}")
                SubmissionResult.Success(
                    txHash = finalizationResult.txHash,
                    blockHeight = finalizationResult.blockHeight ?: 0L
                )
            }
            is TransactionFinalizationResult.InBlock -> {
                SubmissionResult.Pending(
                    txHash = finalizationResult.txHash,
                    reason = "Transaction in block, waiting for finalization"
                )
            }
            is TransactionFinalizationResult.Timeout -> {
                SubmissionResult.Pending(
                    txHash = finalizationResult.txHash,
                    reason = "Finalization timeout (transaction may still be processing)"
                )
            }
            is TransactionFinalizationResult.Dropped,
            is TransactionFinalizationResult.Invalid,
            is TransactionFinalizationResult.Usurped -> {
                val (txHash, reason) = when (finalizationResult) {
                    is TransactionFinalizationResult.Dropped -> finalizationResult.txHash to finalizationResult.reason
                    is TransactionFinalizationResult.Invalid -> finalizationResult.txHash to finalizationResult.reason
                    is TransactionFinalizationResult.Usurped -> finalizationResult.txHash to "Transaction replaced"
                    else -> "" to "Unknown error"
                }
                SubmissionResult.Failed(txHash = txHash, reason = reason)
            }
        }
    }

    companion object {
        private const val TAG = "TransactionSubmitter"

        /**
         * Default timeout for waiting for transaction finalization: 60 seconds.
         *
         * Typical finalization time is 6-12 seconds, so 60s provides margin.
         */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
