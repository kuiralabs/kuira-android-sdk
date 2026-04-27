package com.midnight.kuira.sdk

import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.NodeRpcError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Embedded wallet that handles transaction balancing and submission on-device.
 *
 * Implements [TransactionBalancer] so it can be plugged into
 * [com.midnight.kuira.core.compact.MidnightConfig] as a drop-in replacement
 * for the remote DAppConnectorClient.
 *
 * Uses [DustSyncManager] for tip-aware caching. Handles error 170
 * (InvalidDustSpendProof) by auto-retrying with a fresh dust sync.
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

    override suspend fun balanceTransaction(provenTxHex: String): String = balanceMutex.withLock {
        doBalance(provenTxHex)
    }

    override suspend fun submitTransaction(balancedTxHex: String) {
        nodeRpcClient.submitAndWaitForFinalization(balancedTxHex)
        dustSyncManager.invalidateMemo()
    }

    /**
     * Balance and submit with error 170 retry.
     *
     * If the node rejects with error 170 (InvalidDustSpendProof, stale Merkle
     * root), forces a fresh dust sync and retries balance + submit once.
     * The circuit proof (provenTxHex) is still valid; only dust needs refreshing.
     */
    override suspend fun balanceAndSubmit(provenTxHex: String) = balanceMutex.withLock {
        for (attempt in 1..MAX_DUST_RETRIES) {
            val balanced = doBalance(provenTxHex)
            try {
                nodeRpcClient.submitAndWaitForFinalization(balanced)
                dustSyncManager.invalidateMemo()
                return
            } catch (e: NodeRpcError) {
                if (isDustSpendProofError(e) && attempt < MAX_DUST_RETRIES) {
                    android.util.Log.w(TAG,
                        "Error 170 (stale Merkle root), forcing fresh sync (attempt $attempt/$MAX_DUST_RETRIES)")
                    forceFullSync()
                    continue
                }
                throw e
            }
        }
    }

    /**
     * Balance with stale-checkpoint recovery.
     *
     * If the FFI returns null (e.g., insufficient dust from a partial checkpoint),
     * forces a full re-sync and retries once.
     */
    private suspend fun doBalance(provenTxHex: String): String {
        return tryBalance(provenTxHex)
            ?: run {
                android.util.Log.w(TAG, "Balance failed, forcing full dust re-sync")
                forceFullSync()
                tryBalance(provenTxHex)
                    ?: throw IllegalStateException("Balance failed after full re-sync, check logcat")
            }
    }

    private suspend fun tryBalance(provenTxHex: String): String? {
        val dustState = dustSyncManager.ensureSynced()

        val blockInfo = indexerClient.getCurrentBlockWithParams()
        val ledgerParamsHex = blockInfo.ledgerParameters
            ?: throw IllegalStateException("Indexer returned no ledger parameters")

        val balancedHex = TransactionBalancerNative.nativeBalanceProvenTransaction(
            provenTxHex = provenTxHex,
            dustStatePtr = dustState.getStatePointer(),
            seed = dustSeed,
            ledgerParamsHex = ledgerParamsHex,
            currentTimeMs = System.currentTimeMillis(),
            keysDir = provingKeysDir,
            networkId = networkId,
        )

        if (balancedHex != null) {
            dustRepository.saveState(walletAddress, dustState)
        }

        return balancedHex
    }

    private suspend fun forceFullSync() {
        dustRepository.deleteState(walletAddress)
        dustSyncManager.invalidateMemo()
        dustSyncManager.ensureSynced()
    }

    private fun isDustSpendProofError(e: NodeRpcError): Boolean {
        val data = e.data ?: return false
        return data.contains("Custom error: 170")
    }

    fun close() {
        dustSyncManager.close()
        nodeRpcClient.close()
    }

    companion object {
        private const val TAG = "MidnightWallet"
        private const val MAX_DUST_RETRIES = 2
    }
}
