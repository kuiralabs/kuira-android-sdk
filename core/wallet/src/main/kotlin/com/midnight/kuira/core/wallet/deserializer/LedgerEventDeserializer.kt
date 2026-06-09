package com.midnight.kuira.core.wallet.deserializer

import com.midnight.kuira.core.wallet.model.LedgerEvent

/**
 * Deserializes raw hex ledger events into domain [LedgerEvent]s.
 */
interface LedgerEventDeserializer {

    /**
     * Deserialize raw hex event data into a structured [LedgerEvent].
     *
     * @param rawHex Raw event data as a hex string.
     * @return the deserialized ledger event.
     * @throws DeserializationException if [rawHex] is invalid or an unsupported format.
     */
    suspend fun deserialize(rawHex: String): LedgerEvent
}

/**
 * Exception thrown when event deserialization fails.
 */
class DeserializationException(message: String, cause: Throwable? = null) : Exception(message, cause)
