package com.midnight.kuira.core.wallet.deserializer

import com.midnight.kuira.core.wallet.model.EventType
import com.midnight.kuira.core.wallet.model.LedgerEvent
import java.math.BigInteger

/**
 * Mock implementation of LedgerEventDeserializer for Phase 4A.
 *
 * **Purpose:** Test infrastructure without actual deserialization.
 *
 * Returns deterministic test data based on event ID (extracted from rawHex hash).
 *
 * **Phase 4B:** Replace with real ledger 7.0.0 deserializer via JNI/FFI.
 *
 * @suppress
 */
internal class MockDeserializer : LedgerEventDeserializer {

    override suspend fun deserialize(rawHex: String): LedgerEvent {
        // Extract mock event ID from hex hash (for deterministic test data)
        val mockId = rawHex.hashCode().toLong().absoluteValue()

        // Generate deterministic test data based on ID
        val eventType = when (mockId % 5) {
            0L -> EventType.COINBASE
            1L -> EventType.UNSHIELDED_TRANSFER
            2L -> EventType.SHIELD
            3L -> EventType.UNSHIELD
            else -> EventType.SHIELDED_TRANSFER
        }

        val amount = BigInteger.valueOf(1_000_000L * (mockId % 10 + 1)) // 1-10 MIDNIGHT
        val timestamp = System.currentTimeMillis() - (mockId * 60_000) // Mock timestamps

        return LedgerEvent(
            id = mockId,
            type = eventType,
            amount = amount,
            tokenType = "MIDNIGHT",
            sender = if (eventType != EventType.COINBASE) "mn_addr_mock_sender_$mockId" else null,
            receiver = "mn_addr_mock_receiver_$mockId",
            blockHeight = mockId / 10, // ~10 events per block
            timestamp = timestamp
        )
    }

    private fun Long.absoluteValue(): Long = if (this < 0) -this else this
}
