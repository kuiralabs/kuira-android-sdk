package com.midnight.kuira.core.indexer.storage

import com.midnight.kuira.core.indexer.model.RawLedgerEvent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory implementation of EventCache for Phase 4A.
 *
 * **Purpose:** Simple cache for testing infrastructure without Room database complexity.
 *
 * **Thread Safety:** Uses Mutex for coroutine-safe operations with regular HashMap.
 * Mutex provides exclusive access, so ConcurrentHashMap is unnecessary.
 *
 * **DOS Protection:**
 * - Maximum cache size limit (default: 10,000 events)
 * - LRU eviction policy when limit reached
 * - Prevents OutOfMemoryError from malicious indexer
 *
 * **Limitations:**
 * - Data lost on app restart (not persistent)
 * - No disk persistence
 *
 * **Future:** Replace with Room-based implementation for production.
 *
 * @param maxSize Maximum number of events to cache (default: 10,000)
 *
 * @suppress
 */
internal class InMemoryEventCache(
    private val maxSize: Int = DEFAULT_MAX_SIZE
) : EventCache {

    companion object {
        /**
         * Default maximum cache size: 10,000 events.
         *
         * **Calculation:**
         * - Average event size: ~500 bytes (raw hex + metadata)
         * - 10,000 events = ~5 MB memory
         * - Safe for most Android devices
         */
        const val DEFAULT_MAX_SIZE = 10_000

        /**
         * Minimum cache size (safety check).
         */
        const val MIN_SIZE = 100
    }

    init {
        require(maxSize >= MIN_SIZE) {
            "Cache size must be >= $MIN_SIZE, got: $maxSize"
        }
    }

    // Event storage (id → event)
    private val events = HashMap<Long, RawLedgerEvent>()

    // Access order tracking for LRU eviction (id → access timestamp)
    private val accessOrder = HashMap<Long, Long>()

    private val mutex = Mutex()

    override suspend fun storeEvent(event: RawLedgerEvent) = mutex.withLock {
        // Evict least recently used event if cache is full
        if (events.size >= maxSize && !events.containsKey(event.id)) {
            evictLeastRecentlyUsed()
        }

        // Store event and update access time
        events[event.id] = event
        accessOrder[event.id] = System.currentTimeMillis()
    }

    override suspend fun storeEvents(events: List<RawLedgerEvent>) = mutex.withLock {
        events.forEach { event ->
            // Evict if necessary
            if (this.events.size >= maxSize && !this.events.containsKey(event.id)) {
                evictLeastRecentlyUsed()
            }

            // Store event and update access time
            this.events[event.id] = event
            accessOrder[event.id] = System.currentTimeMillis()
        }
    }

    override suspend fun getEventRange(fromId: Long, toId: Long): List<RawLedgerEvent> = mutex.withLock {
        val now = System.currentTimeMillis()

        events.values
            .filter { it.id in fromId..toId }
            .onEach { event ->
                // Update access time for LRU tracking
                accessOrder[event.id] = now
            }
            .sortedBy { it.id }
    }

    override suspend fun getLatestEventId(): Long? = mutex.withLock {
        events.keys.maxOrNull()
    }

    override suspend fun getOldestEventId(): Long? = mutex.withLock {
        events.keys.minOrNull()
    }

    override suspend fun getEventCount(): Long = mutex.withLock {
        events.size.toLong()
    }

    override suspend fun clear() = mutex.withLock {
        events.clear()
        accessOrder.clear()
    }

    /**
     * Evict the least recently used event from cache.
     *
     * **LRU Policy:**
     * - Find event with oldest access timestamp
     * - Remove from both events and accessOrder maps
     * - Frees memory for new events
     */
    private fun evictLeastRecentlyUsed() {
        // Find event with oldest access time
        val lruEventId = accessOrder.minByOrNull { (_, accessTime) -> accessTime }?.key

        if (lruEventId != null) {
            events.remove(lruEventId)
            accessOrder.remove(lruEventId)
        }
    }
}
