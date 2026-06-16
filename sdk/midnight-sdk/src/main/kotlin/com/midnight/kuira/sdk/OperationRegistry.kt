package com.midnight.kuira.sdk

import android.app.PendingIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Terminal status of a tracked operation. */
enum class OperationTerminalStatus { Success, Pending, Failed }

/**
 * Describes a foreground operation to track. [label] is CALLER-PROVIDED (the
 * dApp/host owns its own strings) so the SDK emits no English of its own; when
 * null, the presentation edge resolves a default label from [kind]. A [Custom]
 * operation MUST provide a [label]. [completionLabel] optionally overrides the
 * finalization-notification title (else the ongoing [label] is reused).
 */
class OperationDescriptor(
    val kind: OperationKind,
    val label: String? = null,
    val completionLabel: String? = null,
    /**
     * Optional tap target for this operation's ongoing + finalization notifications. Lets a
     * host send the user back to the RIGHT screen (e.g. the in-flight match) instead of the
     * default launcher — which a singleTask launcher would otherwise clear the task down to.
     */
    val contentIntent: PendingIntent? = null,
)

/** How a finished operation's normal return maps to a terminal outcome. */
class OperationResult(
    val status: OperationTerminalStatus,
    val txHash: String? = null,
)

/** An operation currently in flight — the unit observed by the foreground service. */
class ActiveOperation internal constructor(
    val id: Long,
    val kind: OperationKind,
    val label: String?,
    /** Live sub-state of the operation (e.g. "Submitting commit…"), shown in the notification. */
    val stage: String? = null,
    /** Tap target for the ongoing notification (see [OperationDescriptor.contentIntent]). */
    val contentIntent: PendingIntent? = null,
)

/** A one-shot terminal event — the unit the finalization notification rides. */
class OperationOutcome internal constructor(
    val id: Long,
    val kind: OperationKind,
    val label: String?,
    val completionLabel: String?,
    val status: OperationTerminalStatus,
    val txHash: String?,
    /** Tap target for the finalization notification (see [OperationDescriptor.contentIntent]). */
    val contentIntent: PendingIntent? = null,
)

/**
 * Process-singleton registry of in-flight value-bearing wallet operations
 * (#261-264). Mirrors [MidnightWallet.syncStatus]'s observable-state model:
 *
 *  - [active] — the set of operations in flight. The foreground service keeps the
 *    process alive while it is non-empty; [com.midnight.kuira.sdk] callers in
 *    wallet-runtime hold the session-lock off it.
 *  - [outcomes] — a one-shot terminal stream the finalization notifier rides.
 *
 * The SDK's built-ins (send / dust-registration / contract call) and any
 * third-party dApp enroll through the SAME registry via [run] (wrapped publicly
 * by [MidnightSdk.runForegroundOperation]), so there is ONE source of truth, not
 * three. The registry is pure Kotlin/coroutines — no Android, fully unit-testable.
 */
class OperationRegistry {

    private val _active = MutableStateFlow<List<ActiveOperation>>(emptyList())
    val active: StateFlow<List<ActiveOperation>> = _active.asStateFlow()

    // replay=0: only LIVE observers see a finalization (a late subscriber must not
    // replay a stale completion). extraBufferCapacity absorbs bursts so tryEmit
    // from the non-suspending finally path never drops an outcome.
    private val _outcomes = MutableSharedFlow<OperationOutcome>(
        replay = 0,
        extraBufferCapacity = OUTCOME_BUFFER_CAPACITY,
    )
    val outcomes: SharedFlow<OperationOutcome> = _outcomes.asSharedFlow()

    private val nextId = AtomicLong(0L)

    /**
     * Run [block] as a tracked operation: enroll it in [active], run it, classify
     * the result into a terminal [OperationOutcome] (emitted on [outcomes]), and
     * de-enroll in `finally` — whether [block] returns OR throws.
     *
     * [classify] maps a NORMAL return to its real status (e.g. a `SendResult.Failed`
     * is a returned failure, not a thrown one); a thrown exception is always
     * [OperationTerminalStatus.Failed] and is rethrown so structured concurrency
     * keeps working.
     */
    suspend fun <T> run(
        descriptor: OperationDescriptor,
        classify: (T) -> OperationResult = { OperationResult(OperationTerminalStatus.Success) },
        block: suspend () -> T,
    ): T {
        // Nesting guard: a contract call enrolls once at the full-pipeline listener
        // AND would re-enter here at the wallet's balanceAndSubmit chokepoint. The
        // outer enrollment already covers it, so an inner call just passes through —
        // enrollment is idempotent across nesting, never double-counted.
        if (currentCoroutineContext()[Marker] != null) return block()

        val id = nextId.getAndIncrement()
        _active.update { it + ActiveOperation(id, descriptor.kind, descriptor.label, contentIntent = descriptor.contentIntent) }
        try {
            val result = withContext(Marker(id)) { block() }
            emitOutcome(id, descriptor, classify(result))
            return result
        } catch (c: CancellationException) {
            // A cancelled operation is ABANDONED, not failed — e.g. a host re-launches a
            // resumable saga (cancelling its predecessor), or the caller's scope tears down
            // on navigation. Emitting a "Failed" finalization here would fire a false failure
            // push while the real work continues (or was deliberately dropped). Re-throw to
            // keep structured concurrency intact, but emit NO terminal outcome.
            throw c
        } catch (t: Throwable) {
            emitOutcome(id, descriptor, OperationResult(OperationTerminalStatus.Failed))
            throw t
        } finally {
            _active.update { list -> list.filterNot { it.id == id } }
        }
    }

    /** Marks a coroutine as already inside a tracked operation (see [run]'s nesting guard). */
    private class Marker(val id: Long) : AbstractCoroutineContextElement(Marker) {
        companion object Key : CoroutineContext.Key<Marker>
    }

    /**
     * Update the live [ActiveOperation.stage] of the operation the CURRENT coroutine is
     * running inside (identified by the context [Marker]) — so a tracked block can drive
     * the foreground-service notification text ("Submitting commit…" → "Finalizing…")
     * without threading an id around. No-op outside a tracked operation.
     */
    suspend fun updateCurrentStage(stage: String?) {
        val id = currentCoroutineContext()[Marker]?.id ?: return
        _active.update { list ->
            list.map { if (it.id == id) ActiveOperation(it.id, it.kind, it.label, stage, it.contentIntent) else it }
        }
    }

    private fun emitOutcome(id: Long, descriptor: OperationDescriptor, result: OperationResult) {
        _outcomes.tryEmit(
            OperationOutcome(
                id = id,
                kind = descriptor.kind,
                label = descriptor.label,
                completionLabel = descriptor.completionLabel,
                status = result.status,
                txHash = result.txHash,
                contentIntent = descriptor.contentIntent,
            ),
        )
    }

    private companion object {
        /** Headroom so a burst of near-simultaneous completions never drops an outcome. */
        const val OUTCOME_BUFFER_CAPACITY = 16
    }
}
