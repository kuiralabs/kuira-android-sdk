package com.midnight.kuira.sdk

import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for [DustBalanceTracker]: the proactive dust subscription
 * resyncs (via the wallet's [onTipAdvance]) only when the chain tip advances,
 * after a best-effort initial sync. Mirrors [ShieldedBalanceTrackerTest]; the
 * injected test dispatcher lets us drive the IO loop under virtual time.
 *
 * Each subscription flow emits once then suspends ([awaitCancellation]) so the
 * re-subscribe `while` loop blocks at the collector — `backgroundScope` cancels
 * everything when the test ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DustBalanceTrackerTest {

    @Test
    fun `tip event triggers a resync after the initial sync`() = runTest {
        // id >= maxId → caught up to the tip → genuinely new event → resync.
        val tip = RawLedgerEvent(id = 10, rawHex = "6d69646e69676874", maxId = 10)
        val client = mock<IndexerClient> {
            on { subscribeToDustEvents(any()) } doReturn flow { emit(tip); awaitCancellation() }
        }
        val calls = AtomicInteger(0)
        val tracker = DustBalanceTracker(
            indexerClient = client,
            onTipAdvance = { calls.incrementAndGet() },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        tracker.start(backgroundScope)
        advanceUntilIdle()

        // 1 initial proactive sync + 1 on the tip event.
        assertEquals(2, calls.get())
    }

    @Test
    fun `a re-delivered SAME tip does NOT resync again (no loop)`() = runTest {
        // Two events at the SAME tip (id == maxId == 10). Only the first is a
        // genuine advance (0 -> 10); the second must NOT re-fire — otherwise a
        // quiet/undeployed chain (or a reconnect re-delivering the tip) loops the
        // proactive sync forever, pinning the sync notification on.
        val tip = RawLedgerEvent(id = 10, rawHex = "6d69646e69676874", maxId = 10)
        val client = mock<IndexerClient> {
            on { subscribeToDustEvents(any()) } doReturn flow { emit(tip); emit(tip); awaitCancellation() }
        }
        val calls = AtomicInteger(0)
        val tracker = DustBalanceTracker(
            indexerClient = client,
            onTipAdvance = { calls.incrementAndGet() },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        tracker.start(backgroundScope)
        advanceUntilIdle()

        // 1 initial + 1 for the first tip advance; the duplicate tip adds nothing.
        assertEquals(2, calls.get())
    }

    @Test
    fun `a non-tip event does NOT resync (only the initial sync runs)`() = runTest {
        // id < maxId → still behind the tip → no resync on this event.
        val behind = RawLedgerEvent(id = 5, rawHex = "6d69646e69676874", maxId = 10)
        val client = mock<IndexerClient> {
            on { subscribeToDustEvents(any()) } doReturn flow { emit(behind); awaitCancellation() }
        }
        val calls = AtomicInteger(0)
        val tracker = DustBalanceTracker(
            indexerClient = client,
            onTipAdvance = { calls.incrementAndGet() },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        tracker.start(backgroundScope)
        advanceUntilIdle()

        assertEquals(1, calls.get())
    }
}
