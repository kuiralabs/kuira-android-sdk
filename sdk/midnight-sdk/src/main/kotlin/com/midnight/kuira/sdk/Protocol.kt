package com.midnight.kuira.sdk

import android.util.Log
import kotlinx.coroutines.delay

/**
 * The builder scope of a [MidnightSdk.runProtocol] saga — an ordered list of
 * **idempotent, ledger-anchored** steps (#253 + #254).
 *
 * The engineering pattern: the blockchain is the durable execution journal. Each
 * [step] declares a `doneWhen` predicate that reads the CHAIN; before running a step
 * the engine asks the chain, and if the effect is already there it SKIPS. So:
 *  - re-running a saga (after process death, or on app resume) skips every
 *    already-landed step → resume-for-free, no fragile local workflow store, and
 *  - no double-submit — re-running is always safe.
 *
 * The whole saga runs as ONE foreground operation (the Layer-1 framework keeps the
 * process alive + shows one notification + fires one finalization push); the inner
 * `contract.call`s coalesce under it via the registry's nesting guard.
 *
 * Because this is a plain suspend block, real control flow between steps — `if`,
 * `while`, inline waits — is native Kotlin (a fluent `.then()` chain can't express it).
 */
interface ProtocolScope {

    /**
     * Run one idempotent step. If [doneWhen] is already true on-ledger the step is
     * SKIPPED (resume/idempotency); otherwise [action] runs and the engine waits until
     * [doneWhen] flips true (confirmation) before returning, so the next step sees a
     * consistent ledger. [name] is for progress/logging.
     */
    suspend fun step(name: String, doneWhen: suspend () -> Boolean, action: suspend () -> Unit)

    /**
     * Boundary for an UNBOUNDED wait on a counterparty (e.g. the opponent's move). No
     * foreground service may span it, so if [doneWhen] isn't yet true the engine ENDS
     * this segment — releasing the foreground service — and the saga resumes (re-run via
     * [MidnightSdk.runProtocol] with the same id) when the counterparty acts. Ledger-
     * anchored `doneWhen`s carry the resumed run forward past the already-done steps.
     */
    suspend fun awaitCounterparty(name: String, doneWhen: suspend () -> Boolean)
}

/** Outcome of a [MidnightSdk.runProtocol] segment. */
sealed class ProtocolResult {
    /** Every step finished — the saga is complete. */
    data object Completed : ProtocolResult()

    /** Reached an [ProtocolScope.awaitCounterparty] that isn't satisfied yet; re-run to resume. */
    data class AwaitingCounterparty(val stepName: String) : ProtocolResult()
}

/**
 * Internal control signal: unwinds the saga block at an unmet [ProtocolScope.awaitCounterparty].
 *
 * Extends [Throwable] directly (NOT [Exception]) on purpose: the saga body is arbitrary dApp code,
 * and `MidnightContract.call` (and many dApps) wrap work in a broad `catch (e: Exception)`. A plain
 * `Exception` here could be silently swallowed by such a catch — losing the resume boundary and
 * making the saga read as Completed when it should be AwaitingCounterparty. As a bare [Throwable]
 * it sails past `catch (Exception)`; [MidnightSdk.runProtocol] still catches it by its concrete
 * type at the saga boundary.
 */
internal class ProtocolSuspendSignal(val stepName: String) : Throwable() {
    // No stack trace — this is control flow, not an error.
    override fun fillInStackTrace(): Throwable = this
}

internal class ProtocolScopeImpl(
    private val protocolId: String,
    private val confirmTimeoutMs: Long,
    private val confirmPollMs: Long,
) : ProtocolScope {

    override suspend fun step(name: String, doneWhen: suspend () -> Boolean, action: suspend () -> Unit) {
        if (doneWhen()) {
            Log.i(TAG, "[$protocolId] step '$name' already on-ledger — skipping (idempotent resume)")
            return
        }
        Log.i(TAG, "[$protocolId] step '$name' — running")
        action()
        awaitConfirmation(name, doneWhen)
    }

    override suspend fun awaitCounterparty(name: String, doneWhen: suspend () -> Boolean) {
        if (doneWhen()) return
        Log.i(TAG, "[$protocolId] awaiting counterparty at '$name' — releasing the foreground service")
        throw ProtocolSuspendSignal(name)
    }

    /** Poll [doneWhen] until true (indexer lags finalization ~1 block) or the budget runs out. */
    private suspend fun awaitConfirmation(name: String, doneWhen: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + confirmTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (doneWhen()) return
            delay(confirmPollMs)
        }
        Log.w(TAG, "[$protocolId] step '$name' submitted but not confirmed on-ledger within ${confirmTimeoutMs}ms — proceeding")
    }

    private companion object {
        const val TAG = "Protocol"
    }
}
