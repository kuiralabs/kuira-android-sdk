package com.midnight.kuira.sdk

import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Embedded wallet that handles transaction balancing and submission on-device.
 *
 * Implements [TransactionBalancer] so it can be plugged into
 * [com.midnight.kuira.core.compact.MidnightConfig] as a drop-in replacement
 * for the remote DAppConnectorClient.
 *
 * Lifecycle:
 * 1. [syncDust] must be called at least once before balancing (SDK builder handles this).
 * 2. Each [balanceTransaction] call: loads dust state → FFI balance+prove+seal → saves state.
 * 3. Each [submitTransaction] call: submits to the blockchain node and waits for finalization.
 * 4. [close] releases resources.
 */
class MidnightWallet internal constructor(
    private val dustRepository: DustRepository,
    private val indexerClient: IndexerClient,
    private val nodeRpcClient: NodeRpcClient,
    private val walletAddress: String,
    private val dustSeed: ByteArray,
    private val provingKeysDir: String,
    private val networkId: String,
) : TransactionBalancer {

    private val balanceMutex = Mutex()

    /**
     * Sync dust state from the blockchain.
     *
     * Must be called before the first [balanceTransaction] call.
     * Subsequent calls are incremental (only fetch new events).
     */
    suspend fun syncDust() {
        dustRepository.syncFromBlockchain(walletAddress, dustSeed)
    }

    /**
     * Balance a proven transaction by adding dust fee payment locally.
     *
     * Steps:
     * 1. Load dust state from persistent storage
     * 2. Fetch ledger parameters from the indexer (for fee calculation)
     * 3. Call Rust FFI: create dust spends → prove → merge → seal
     * 4. Save updated dust state (marks coins as spent)
     * 5. Return the balanced+sealed transaction hex
     *
     * Thread-safe: only one balance operation at a time (prevents double-spend).
     */
    override suspend fun balanceTransaction(provenTxHex: String): String = balanceMutex.withLock {
        // Re-sync dust state if cache was cleared (e.g. after a successful submit).
        // TODO: study optimal sync strategy for remote networks (PREPROD/PREVIEW).
        // The Kuira wallet force-syncs before every tx to prevent error 170 loops,
        // but that adds seconds on remote indexers. For now, sync only when needed.
        var dustState = dustRepository.loadState(walletAddress)
        if (dustState == null) {
            syncDust()
            dustState = dustRepository.loadState(walletAddress)
                ?: throw IllegalStateException("No dust state after sync. Is dust registered?")
        }

        try {
            val blockInfo = indexerClient.getCurrentBlockWithParams()
            val ledgerParamsHex = blockInfo.ledgerParameters
                ?: throw IllegalStateException("Indexer returned no ledger parameters")

            val currentTimeMs = System.currentTimeMillis()

            val balancedHex = TransactionBalancerNative.nativeBalanceProvenTransaction(
                provenTxHex = provenTxHex,
                dustStatePtr = dustState.getStatePointer(),
                seed = dustSeed,
                ledgerParamsHex = ledgerParamsHex,
                currentTimeMs = currentTimeMs,
                keysDir = provingKeysDir,
                networkId = networkId,
            ) ?: throw IllegalStateException(
                "Native balance_proven_transaction returned null — check logcat for details"
            )

            // Save updated dust state (FFI mutated the native pointer — spends recorded)
            dustRepository.saveState(walletAddress, dustState)

            balancedHex
        } finally {
            dustState.close()
        }
    }

    /**
     * Submit a balanced transaction to the blockchain node.
     *
     * Uses WebSocket to wait for finalization (not fire-and-forget).
     * Deletes dust cache after success to force re-sync before next tx.
     */
    override suspend fun submitTransaction(balancedTxHex: String) {
        nodeRpcClient.submitAndWaitForFinalization(balancedTxHex)

        // Delete dust cache after successful submission — forces re-sync before next tx.
        // Matches the existing Kuira wallet pattern (prevents stale UTXO errors).
        dustRepository.deleteState(walletAddress)
    }

    fun close() {
        nodeRpcClient.close()
    }
}
