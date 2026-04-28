package com.midnight.kuira.sdk

import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.TransactionBalancer
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.NodeRpcClient.SubmissionStage
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
 * Uses [DustSyncManager] for session-scoped caching. Handles error 170
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

    override suspend fun balanceAndSubmit(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)?,
    ): Unit = balanceMutex.withLock {
        onProgress?.invoke(BalanceProgress.SyncingDust)
        val balanced = doBalance(provenTxHex, onProgress)

        onProgress?.invoke(BalanceProgress.Submitting)
        try {
            nodeRpcClient.submitAndWaitForFinalization(balanced) { stage ->
                when (stage) {
                    SubmissionStage.IN_BLOCK ->
                        onProgress?.invoke(BalanceProgress.WaitingFinalization)
                    else -> { /* SUBMITTED, BROADCAST — stay on Submitting */ }
                }
            }
            dustSyncManager.invalidateMemo()
        } catch (e: NodeRpcError) {
            if (!isDustSpendProofError(e)) throw e

            android.util.Log.w(TAG, "Error 170, full re-sync and retry once")
            onProgress?.invoke(BalanceProgress.RetryingDustSync)
            forceFullSync()
            val retryBalanced = doBalance(provenTxHex, onProgress)
            onProgress?.invoke(BalanceProgress.Submitting)
            nodeRpcClient.submitAndWaitForFinalization(retryBalanced) { stage ->
                when (stage) {
                    SubmissionStage.IN_BLOCK ->
                        onProgress?.invoke(BalanceProgress.WaitingFinalization)
                    else -> {}
                }
            }
            dustSyncManager.invalidateMemo()
        }
    }

    private suspend fun doBalance(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ): String {
        return tryBalance(provenTxHex, onProgress)
            ?: run {
                android.util.Log.w(TAG, "Balance failed, forcing full dust re-sync")
                onProgress?.invoke(BalanceProgress.RetryingDustSync)
                forceFullSync()
                tryBalance(provenTxHex, onProgress)
                    ?: throw IllegalStateException("Balance failed after full re-sync, check logcat")
            }
    }

    private suspend fun tryBalance(
        provenTxHex: String,
        onProgress: (suspend (BalanceProgress) -> Unit)? = null,
    ): String? {
        val dustState = dustSyncManager.ensureSynced { processed, total ->
            onProgress?.invoke(BalanceProgress.SyncingDustProgress(processed, total))
        }

        val blockInfo = indexerClient.getCurrentBlockWithParams()
        val ledgerParamsHex = blockInfo.ledgerParameters
            ?: throw IllegalStateException("Indexer returned no ledger parameters")

        onProgress?.invoke(BalanceProgress.ProvingDust)

        return TransactionBalancerNative.nativeBalanceProvenTransaction(
            provenTxHex = provenTxHex,
            dustStatePtr = dustState.getStatePointer(),
            seed = dustSeed,
            ledgerParamsHex = ledgerParamsHex,
            currentTimeMs = System.currentTimeMillis(),
            keysDir = provingKeysDir,
            networkId = networkId,
        )
    }

    private suspend fun forceFullSync() {
        dustSyncManager.forceResync()
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
    }
}
