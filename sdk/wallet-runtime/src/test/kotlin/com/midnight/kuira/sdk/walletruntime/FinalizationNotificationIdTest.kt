package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.sdk.OperationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [finalizationNotificationId] routing: a NIGHT send is a value transaction (its own,
 * persistent slot, keyed by op id), every other operation shares the single only-latest slot
 * (#282). Pure — no Context, no notification manager.
 */
class FinalizationNotificationIdTest {

    @Test
    fun `a Send gets its own slot, keyed by op id`() {
        assertEquals(
            FinalizationNotifier.SENT_BASE_ID + 1,
            finalizationNotificationId(OperationKind.Send, id = 1),
        )
        assertEquals(
            FinalizationNotifier.SENT_BASE_ID + 2,
            finalizationNotificationId(OperationKind.Send, id = 2),
        )
    }

    @Test
    fun `the same send id maps to the same slot — a single send never duplicates`() {
        assertEquals(
            finalizationNotificationId(OperationKind.Send, id = 7),
            finalizationNotificationId(OperationKind.Send, id = 7),
        )
    }

    @Test
    fun `distinct sends within the span don't collide`() {
        val slots = (0L until FinalizationNotifier.SENT_SPAN.toLong())
            .map { finalizationNotificationId(OperationKind.Send, id = it) }
            .toSet()
        assertEquals(FinalizationNotifier.SENT_SPAN, slots.size)
    }

    @Test
    fun `a send slot is never the only-latest slot`() {
        for (id in 0L..FinalizationNotifier.SENT_SPAN.toLong()) {
            assertNotEquals(
                FinalizationNotifier.ONLY_LATEST_ID,
                finalizationNotificationId(OperationKind.Send, id),
            )
        }
    }

    @Test
    fun `non-value operations all share the only-latest slot`() {
        for (kind in listOf(OperationKind.DustRegistration, OperationKind.ContractCall, OperationKind.Custom)) {
            assertEquals(FinalizationNotifier.ONLY_LATEST_ID, finalizationNotificationId(kind, id = 1))
            assertEquals(FinalizationNotifier.ONLY_LATEST_ID, finalizationNotificationId(kind, id = 99))
        }
    }
}
