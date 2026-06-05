package com.midnight.kuira.core.compact

/**
 * Granular progress updates during the balance+submit pipeline.
 *
 * DApp developers receive these through [TransactionBalancer.balanceAndSubmit]'s
 * `onProgress` callback, or through [ContractCallStage.BalancingDetail] in
 * [MidnightContract.call]'s progress callback.
 *
 * ## Usage in a ViewModel:
 * ```kotlin
 * contract.call("post", message) { stage ->
 *     when (stage) {
 *         is ContractCallStage.BalancingDetail -> when (stage.progress) {
 *             is BalanceProgress.SyncingDust -> showStatus("Syncing dust wallet...")
 *             is BalanceProgress.SyncingDustProgress -> showProgress(
 *                 stage.progress.eventsProcessed, stage.progress.totalEvents
 *             )
 *             is BalanceProgress.ProvingDust -> showStatus("Proving dust payment...")
 *             is BalanceProgress.Submitting -> showStatus("Submitting to blockchain...")
 *             is BalanceProgress.WaitingFinalization -> showStatus("Waiting for finalization...")
 *             is BalanceProgress.RetryingDustSync -> showStatus("Retrying dust sync...")
 *         }
 *         // ... handle other stages
 *     }
 * }
 * ```
 */
sealed class BalanceProgress {
    /** Initial dust wallet sync starting (first call per session is slow). */
    data object SyncingDust : BalanceProgress()

    /** Dust sync streaming progress — use for progress bars. */
    data class SyncingDustProgress(
        val eventsProcessed: Int,
        val totalEvents: Int,
    ) : BalanceProgress()

    /** Generating ZK proof for dust fee payment. */
    data object ProvingDust : BalanceProgress()

    /** Balanced transaction submitted to the blockchain node. */
    data object Submitting : BalanceProgress()

    /** Transaction is in a block, waiting for finalization. */
    data object WaitingFinalization : BalanceProgress()

    /** Error 170 recovery: re-syncing dust from genesis and retrying. */
    data object RetryingDustSync : BalanceProgress()

    /**
     * Spent-dust recovery (node error 195 = `InputNotInUtxos`): a dust UTXO the
     * wallet picked was already spent on-chain — the indexer's event stream hadn't
     * reflected our own earlier fee spend yet. The wallet records it, re-selects a
     * different dust UTXO, and retries. (This is *not* error 115, which is an
     * `InvalidProof` — an unrelated failure mode.)
     *
     * This is expected and self-healing — surface it as "recovering, this can take a
     * moment", NOT as an error or a hang. It may repeat briefly while the wallet
     * works past each already-spent UTXO.
     */
    data object RecoveringDustState : BalanceProgress()
}
