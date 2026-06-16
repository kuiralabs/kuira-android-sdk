package com.midnight.kuira.core.compact

/**
 * Optional hook that lets a higher layer bracket the WHOLE contract-call pipeline —
 * fetch → execute → ZK prove → balance → submit → finalize — as a single tracked
 * operation. Proving is CPU-heavy and runs BEFORE the balancer, so bracketing only
 * the submit phase would leave the longest stretch (the one a user is most likely to
 * background during) unprotected; this brackets the lot.
 *
 * compact-engine defines ONLY this interface. The SDK injects an implementation via
 * [MidnightConfig.Builder.operationListener] that enrolls the call in its operation
 * registry (keeping the process alive under the foreground service + firing the
 * finalization notification). compact-engine never depends on the SDK or
 * wallet-runtime — the dependency points downward only.
 */
interface ContractOperationListener {
    /**
     * Run [block] (a full circuit call / deploy / submit) as a tracked operation.
     * [circuitName] is informational (for the implementation's own logging); the
     * user-facing label is owned by the implementation, not passed here.
     */
    suspend fun <T> trackContractCall(circuitName: String, block: suspend () -> T): T
}
