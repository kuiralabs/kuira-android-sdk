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
 * Uses [DustSyncManager] for tip-aware caching: same-block transactions are
 * instant, cross-block transactions delta-sync only new events (~200ms).
 */
class MidnightWallet internal constructor(
    private val dustSyncManager: DustSyncManager,
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
     * On first call: full sync from genesis.
     * On subsequent calls: tip-aware delta sync (instant if tip unchanged).
     */
    suspend fun syncDust() {
        dustSyncManager.ensureSynced()
    }

    /**
     * Balance a proven transaction by adding dust fee payment locally.
     *
     * Dust state is obtained via [DustSyncManager.ensureSynced] which handles
     * tip-aware caching and delta sync automatically.
     *
     * Thread-safe: only one balance operation at a time (prevents double-spend).
     */
    override suspend fun balanceTransaction(provenTxHex: String): String = balanceMutex.withLock {
        // Try balance with current dust state. If it fails (stale checkpoint with
        // insufficient balance), wipe and full re-sync, then retry once.
        tryBalance(provenTxHex)
            ?: run {
                android.util.Log.w(TAG, "Balance failed, forcing full dust re-sync and retrying")
                dustRepository.deleteState(walletAddress)
                dustSyncManager.invalidateMemo()
                dustSyncManager.ensureSynced()
                tryBalance(provenTxHex)
                    ?: throw IllegalStateException(
                        "Balance failed after full re-sync, check logcat for details"
                    )
            }
    }

    private suspend fun tryBalance(provenTxHex: String): String? {
        val dustState = dustSyncManager.ensureSynced()

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
        )

        if (balancedHex != null) {
            dustRepository.saveState(walletAddress, dustState)
        }

        return balancedHex
    }

    companion object {
        private const val TAG = "MidnightWallet"
    }

    /**
     * Submit a balanced transaction to the blockchain node.
     *
     * Uses WebSocket to wait for finalization (not fire-and-forget).
     * Invalidates the dust memo so the next balance call delta-syncs
     * from the disk checkpoint (no destructive delete).
     */
    override suspend fun submitTransaction(balancedTxHex: String) {
        nodeRpcClient.submitAndWaitForFinalization(balancedTxHex)

        // Invalidate memo, keep disk checkpoint for delta resume.
        dustSyncManager.invalidateMemo()
    }

    fun close() {
        dustSyncManager.close()
        nodeRpcClient.close()
    }
}
