package com.midnight.kuira.core.indexer.storage

import com.midnight.kuira.core.indexer.model.RawLedgerEvent

/**
 * Local cache for raw ledger events.
 *
 * **Purpose:**
 * - Store raw hex events from indexer
 * - Support range queries for event replay
 * - Track sync progress
 *
 * @suppress
 */
internal interface EventCache {

    /**
     * Store a raw ledger event.
     *
     * @param event Raw event from indexer
     */
    suspend fun storeEvent(event: RawLedgerEvent)

    /**
     * Store multiple events in batch.
     *
     * @param events List of raw events
     */
    suspend fun storeEvents(events: List<RawLedgerEvent>)

    /**
     * Get events in ID range (inclusive).
     *
     * @param fromId Start event ID
     * @param toId End event ID
     * @return List of events in range, ordered by ID
     */
    suspend fun getEventRange(fromId: Long, toId: Long): List<RawLedgerEvent>

    /**
     * Get the latest cached event ID.
     *
     * @return Latest event ID, or null if cache is empty
     */
    suspend fun getLatestEventId(): Long?

    /**
     * Get the oldest cached event ID.
     *
     * @return Oldest event ID, or null if cache is empty
     */
    suspend fun getOldestEventId(): Long?

    /**
     * Get total number of cached events.
     *
     * @return Event count
     */
    suspend fun getEventCount(): Long

    /**
     * Clear all cached events.
     *
     * **Warning:** This will delete all cached data.
     * Use when resetting wallet or switching networks.
     */
    suspend fun clear()
}
