package com.midnight.kuira.core.compact

/** Progress stages emitted during [MidnightContract.call]. */
sealed class ContractCallStage {
    data object FetchingState : ContractCallStage()
    data object Executing : ContractCallStage()
    data object Proving : ContractCallStage()
    data object Balancing : ContractCallStage()
    data object Submitting : ContractCallStage()
}
