package com.midnight.kuira.core.compact

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure seam behind [MidnightContract.observeLedger] (#255): turn a block-tick flow into a
 * stream of contract-state hex that emits the CURRENT state once, then only on genuine changes —
 * so the expensive QuickJs decode downstream runs once per real change, not once per block.
 * The decode itself is native (covered by `ledger()` tests); this pins the reactive plumbing.
 */
class ObserveLedgerTest {

    @Test
    fun `emits the initial state immediately even with no ticks`() = runTest {
        val out = ledgerStateHexSignals(emptyFlow()) { "STATE_A" }.toList()
        // onStart prepends one read, so a dApp sees the current state without waiting for a block.
        assertEquals(listOf("STATE_A"), out)
    }

    @Test
    fun `emits only when the state hex actually changes (unchanged blocks are skipped)`() = runTest {
        // onStart adds the initial read, then 4 ticks → 5 fetches total.
        val states = listOf("A", "A", "B", "B", "C")
        var i = 0
        val fourTicks = flowOf(Unit, Unit, Unit, Unit)

        val out = ledgerStateHexSignals(fourTicks) { states[i++] }.toList()

        // A (initial) → A skipped → B → B skipped → C: the decode would fire 3×, not 5×.
        assertEquals(listOf("A", "B", "C"), out)
    }

    @Test
    fun `a steady unchanging state emits exactly once`() = runTest {
        val ticks = flowOf(Unit, Unit, Unit)
        val out = ledgerStateHexSignals(ticks) { "SAME" }.toList()
        assertEquals(listOf("SAME"), out)
    }
}
