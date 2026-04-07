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

    class ProvingFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class BalancingFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class SubmissionFailed(message: String, cause: Throwable? = null)
        : ContractCallException(message, cause)

    class InvalidArgument(message: String)
        : ContractCallException(message)
}
