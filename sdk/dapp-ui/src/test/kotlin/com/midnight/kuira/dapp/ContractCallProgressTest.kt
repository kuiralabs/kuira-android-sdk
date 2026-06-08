package com.midnight.kuira.dapp

import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.ContractCallStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the contract-call progress mapping — the pure functions behind
 * [ContractCallProgressBar]. The bar itself is Compose UI (covered by eye), but
 * the stage → label / 0..1 mapping is pure and locks down nicely here.
 */
class ContractCallProgressTest {

    @Test
    fun `every stage has a non-blank default label`() {
        val stages = listOf(
            ContractCallStage.FetchingState,
            ContractCallStage.Executing,
            ContractCallStage.Proving,
            ContractCallStage.Balancing,
            ContractCallStage.Submitting,
            ContractCallStage.BalancingDetail(BalanceProgress.SyncingDust),
            ContractCallStage.BalancingDetail(BalanceProgress.SyncingDustProgress(1, 10)),
            ContractCallStage.BalancingDetail(BalanceProgress.ProvingDust),
            ContractCallStage.BalancingDetail(BalanceProgress.Submitting),
            ContractCallStage.BalancingDetail(BalanceProgress.WaitingFinalization),
            ContractCallStage.BalancingDetail(BalanceProgress.RetryingDustSync),
            ContractCallStage.BalancingDetail(BalanceProgress.RecoveringDustState),
        )
        stages.forEach { stage ->
            val label = stage.defaultLabel()
            assertNotNull("missing label for $stage", label)
            assertTrue("blank label for $stage", label!!.isNotBlank())
        }
    }

    @Test
    fun `progress fractions stay within 0 and 1`() {
        val stages = listOf(
            ContractCallStage.FetchingState,
            ContractCallStage.Executing,
            ContractCallStage.Proving,
            ContractCallStage.Balancing,
            ContractCallStage.Submitting,
            ContractCallStage.BalancingDetail(BalanceProgress.WaitingFinalization),
            ContractCallStage.BalancingDetail(BalanceProgress.SyncingDustProgress(7, 10)),
        )
        stages.forEach { stage ->
            val p = stage.progressFraction()
            assertTrue("$stage out of range: $p", p in 0f..1f)
        }
    }

    @Test
    fun `happy-path pipeline advances monotonically`() {
        // The typical order a single call walks through. progressFraction is
        // weighted, not even — but it must never go backwards on the happy path.
        val pipeline = listOf(
            ContractCallStage.FetchingState,
            ContractCallStage.Executing,
            ContractCallStage.Proving,
            ContractCallStage.Balancing,
            ContractCallStage.BalancingDetail(BalanceProgress.SyncingDust),
            ContractCallStage.BalancingDetail(BalanceProgress.ProvingDust),
            ContractCallStage.BalancingDetail(BalanceProgress.Submitting),
            ContractCallStage.BalancingDetail(BalanceProgress.WaitingFinalization),
        )
        var prev = -1f
        pipeline.forEach { stage ->
            val p = stage.progressFraction()
            assertTrue("regressed at $stage: $p < $prev", p >= prev)
            prev = p
        }
    }

    @Test
    fun `dust-sync sub-progress fills its band proportionally`() {
        val quarter = ContractCallStage.BalancingDetail(
            BalanceProgress.SyncingDustProgress(eventsProcessed = 25, totalEvents = 100),
        ).progressFraction()
        val full = ContractCallStage.BalancingDetail(
            BalanceProgress.SyncingDustProgress(eventsProcessed = 100, totalEvents = 100),
        ).progressFraction()
        assertTrue("sub-progress should advance with events", full > quarter)
    }

    @Test
    fun `zero total events does not divide by zero`() {
        val p = ContractCallStage.BalancingDetail(
            BalanceProgress.SyncingDustProgress(eventsProcessed = 0, totalEvents = 0),
        ).progressFraction()
        assertEquals(0.40f, p, 0.0001f)
    }

    @Test
    fun `finalization sits near full so the bar does not read as a hang`() {
        val finalization = ContractCallStage.BalancingDetail(
            BalanceProgress.WaitingFinalization,
        ).progressFraction()
        assertTrue("finalization should be near-full, was $finalization", finalization >= 0.85f)
    }
}
