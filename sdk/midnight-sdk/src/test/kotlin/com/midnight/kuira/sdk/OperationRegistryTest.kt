package com.midnight.kuira.sdk

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Invariants of [OperationRegistry] — the spine of the foreground-operation framework
 * (#261-264). Pure Kotlin/coroutines, no Android.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OperationRegistryTest {

    @Test
    fun `run enrolls during the block, de-enrolls after, and emits a success outcome`() =
        runTest(UnconfinedTestDispatcher()) {
            val reg = OperationRegistry()
            val outcomes = mutableListOf<OperationOutcome>()
            val collector = launch { reg.outcomes.collect { outcomes.add(it) } }

            val result = reg.run(OperationDescriptor(OperationKind.Send, label = "Sending")) {
                assertEquals("enrolled while running", 1, reg.active.value.size)
                assertEquals(OperationKind.Send, reg.active.value.first().kind)
                assertEquals("Sending", reg.active.value.first().label)
                42
            }

            assertEquals(42, result)
            assertTrue("de-enrolled after", reg.active.value.isEmpty())
            assertEquals(1, outcomes.size)
            assertEquals(OperationTerminalStatus.Success, outcomes.first().status)
            assertEquals(OperationKind.Send, outcomes.first().kind)
            collector.cancel()
        }

    @Test
    fun `a throwing block de-enrolls, emits a failed outcome, and rethrows`() =
        runTest(UnconfinedTestDispatcher()) {
            val reg = OperationRegistry()
            val outcomes = mutableListOf<OperationOutcome>()
            val collector = launch { reg.outcomes.collect { outcomes.add(it) } }

            try {
                reg.run<Unit>(OperationDescriptor(OperationKind.ContractCall)) {
                    throw IllegalStateException("boom")
                }
                fail("should have rethrown")
            } catch (e: IllegalStateException) {
                assertEquals("boom", e.message)
            }

            assertTrue("de-enrolled even on throw", reg.active.value.isEmpty())
            assertEquals(1, outcomes.size)
            assertEquals(OperationTerminalStatus.Failed, outcomes.first().status)
            collector.cancel()
        }

    @Test
    fun `a cancelled operation de-enrolls but emits NO terminal outcome`() =
        runTest(UnconfinedTestDispatcher()) {
            val reg = OperationRegistry()
            val outcomes = mutableListOf<OperationOutcome>()
            val collector = launch { reg.outcomes.collect { outcomes.add(it) } }

            val gate = CompletableDeferred<Unit>()
            val op = launch {
                reg.run<Unit>(OperationDescriptor(OperationKind.Custom, label = "match")) {
                    gate.await() // suspend until the job is cancelled out from under us
                }
            }
            assertEquals("enrolled while suspended", 1, reg.active.value.size)

            op.cancel()
            op.join()

            // A cancellation is abandonment, not failure: a host re-launching a resumable
            // saga (cancelling its predecessor) must NOT fire a false "Failed" push.
            assertTrue("de-enrolled after cancellation", reg.active.value.isEmpty())
            assertTrue("cancelled op emits no outcome", outcomes.isEmpty())
            collector.cancel()
        }

    @Test
    fun `classify maps a returned value to its real terminal status`() =
        runTest(UnconfinedTestDispatcher()) {
            val reg = OperationRegistry()
            val outcomes = mutableListOf<OperationOutcome>()
            val collector = launch { reg.outcomes.collect { outcomes.add(it) } }

            // A normal return that is semantically a failure (like SendResult.Failed).
            reg.run(
                OperationDescriptor(OperationKind.Send),
                classify = { OperationResult(OperationTerminalStatus.Failed) },
            ) { "returned-but-failed" }

            assertEquals(OperationTerminalStatus.Failed, outcomes.single().status)
            collector.cancel()
        }

    @Test
    fun `a nested run does not double-enroll or double-emit (the nesting guard)`() =
        runTest(UnconfinedTestDispatcher()) {
            val reg = OperationRegistry()
            val outcomes = mutableListOf<OperationOutcome>()
            val collector = launch { reg.outcomes.collect { outcomes.add(it) } }

            reg.run(OperationDescriptor(OperationKind.Custom, label = "outer")) {
                assertEquals(1, reg.active.value.size)
                // An inner run (e.g. balanceAndSubmit inside a tracked contract call)
                // must pass through — the outer op already covers it.
                val inner = reg.run(OperationDescriptor(OperationKind.ContractCall)) {
                    assertEquals("still just the outer op", 1, reg.active.value.size)
                    "inner"
                }
                assertEquals("inner", inner)
            }

            assertTrue(reg.active.value.isEmpty())
            assertEquals("only the outer op emits an outcome", 1, outcomes.size)
            assertEquals(OperationKind.Custom, outcomes.first().kind)
            collector.cancel()
        }

    @Test
    fun `concurrent operations accumulate in the active set`() =
        runTest(UnconfinedTestDispatcher()) {
            val reg = OperationRegistry()
            val gateA = CompletableDeferred<Unit>()
            val gateB = CompletableDeferred<Unit>()

            val a = launch { reg.run(OperationDescriptor(OperationKind.Send)) { gateA.await() } }
            val b = launch { reg.run(OperationDescriptor(OperationKind.DustRegistration)) { gateB.await() } }

            assertEquals("both in flight", 2, reg.active.value.size)
            gateA.complete(Unit); a.join()
            assertEquals(1, reg.active.value.size)
            gateB.complete(Unit); b.join()
            assertTrue(reg.active.value.isEmpty())
        }
}
