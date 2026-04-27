package com.midnight.kuira.core.indexer.model

/**
 * Result of a delta dust sync operation.
 *
 * @property eventsHex Combined SCALE-encoded hex of new events (empty if no new events)
 * @property lastEventId ID of the last event applied (-1 if no events)
 * @property eventCount Number of new events collected
 */
data class DustSyncResult(
    val eventsHex: String,
    val lastEventId: Long,
    val eventCount: Int,
)
