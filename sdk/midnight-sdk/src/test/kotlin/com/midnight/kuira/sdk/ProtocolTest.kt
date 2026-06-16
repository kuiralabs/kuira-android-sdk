package com.midnight.kuira.sdk

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The durable protocol-saga engine (#253 + #254): ledger-anchored idempotent steps and
 * the counterparty-wait boundary. Tests the [ProtocolScopeImpl] directly with fake
 * `doneWhen`/`action` lambdas — no chain needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolTest {

    private fun scope() = ProtocolScopeImpl(protocolId = "test", confirmTimeoutMs = 1_000, confirmPollMs = 10)

    @Test
    fun `a step skips its action when already done on-ledger (idempotency)`() = runTest {
        var ran = false
        scope().step("commit", doneWhen = { true }, action = { ran = true })
        assertFalse("an already-landed step must be skipped on resume", ran)
    }

    @Test
    fun `a step runs its action when not done`() = runTest {
        var done = false
        var ran = false
        scope().step("commit", doneWhen = { done }, action = { ran = true; done = true })
        assertTrue(ran)
    }

    @Test
    fun `awaitCounterparty signals suspend when the counterparty hasn't acted`() = runTest {
        try {
            scope().awaitCounterparty("opponent", doneWhen = { false })
            fail("should have signalled suspend")
        } catch (s: ProtocolSuspendSignal) {
            assertEquals("opponent", s.stepName)
        }
    }

    @Test
    fun `awaitCounterparty proceeds when the counterparty already acted`() = runTest {
        // Returns normally (no signal) — the saga continues.
        scope().awaitCounterparty("opponent", doneWhen = { true })
    }

    @Test
    fun `on resume, done steps are skipped and only the not-done step runs`() = runTest {
        val ran = mutableListOf<String>()
        val done = mutableSetOf("commit") // commit already landed before a crash/resume
        suspend fun step(name: String) = scope().step(
            name = name,
            doneWhen = { name in done },
            action = { ran.add(name); done.add(name) },
        )

        step("commit")
        step("reveal")

        assertEquals("commit skipped (on-ledger), reveal ran", listOf("reveal"), ran)
    }
}
