package com.midnight.kuira.core.compact

/** Typed errors from [MidnightContract.call] — each stage has its own subclass. */
sealed class ContractCallException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class StateFetchFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class CircuitExecutionFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    /**
     * The circuit moves unshielded value (`receiveUnshielded` / `sendUnshielded`) but no matching
     * offer was supplied, so the transaction would be rejected by the node as "Invalid Transaction".
     * The message names the SDK builder to call ([com.midnight.kuira.sdk.MidnightSdk]'s
     * `buildUnshieldedFundingJson` / `buildUnshieldedWithdrawalJson`) and pass into
     * [MidnightContract.call] via `unshieldedFundingJson` / `unshieldedWithdrawalJson`.
     */
    class UnshieldedValueUnfunded(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class ProvingFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class BalancingFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class SubmissionFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class InvalidArgument(message: String)
        : ContractCallException(message)
}
