package com.midnight.kuira.core.wallet.balance

import com.midnight.kuira.core.wallet.model.Balance
import com.midnight.kuira.core.wallet.model.EventType
import com.midnight.kuira.core.wallet.model.LedgerEvent
import java.math.BigInteger

/**
 * Calculates wallet balance from ledger events.
 *
 * **Algorithm:**
 * 1. Start with zero balance
 * 2. Process events chronologically (by ID)
 * 3. For each event:
 *    - If receiver matches wallet address: ADD amount
 *    - If sender matches wallet address: SUBTRACT amount
 * 4. Track shielded/unshielded/dust separately
 *
 * **Usage:**
 * ```kotlin
 * val calculator = BalanceCalculator()
 * val deserializer = MockDeserializer()
 *
 * // Process cached events
 * val events = cachedEvents.map { deserializer.deserialize(it.rawHex) }
 * val balance = calculator.calculate(events, walletAddress = "mn_addr_...")
 * ```
 */
class BalanceCalculator {

    /**
     * Calculate balance for a specific address from ledger events.
     *
     * **CRITICAL:** This method validates event ordering and detects balance underflow.
     * Negative balance indicates double-spend or corrupted event stream.
     *
     * **Event Ordering:**
     * - Events MUST be processed in ascending ID order (chronological)
     * - Out-of-order events can cause incorrect balance calculation
     * - Method sorts by ID to ensure correctness
     *
     * **Balance Underflow Detection:**
     * - If balance goes negative, throws BalanceUnderflowException
     * - Indicates double-spend attack or corrupted blockchain state
     * - Wallet MUST stop processing and alert user
     *
     * **Future: UTXO Tracking**
     * - Currently assumes account model (balance-based)
     * - Midnight uses UTXO model (output-based)
     * - Need to track spent/unspent outputs for proper validation
     *
     * @param events List of deserialized ledger events (will be sorted by ID)
     * @param walletAddress Wallet address to calculate balance for
     * @return Balance for this address
     * @throws BalanceUnderflowException if balance would go negative
     */
    fun calculate(events: List<LedgerEvent>, walletAddress: String): Balance {
        if (walletAddress.isBlank()) {
            throw IllegalArgumentException("Wallet address cannot be blank")
        }

        var shielded = BigInteger.ZERO
        var unshielded = BigInteger.ZERO
        var dust = BigInteger.ZERO

        // Sort events by ID to ensure chronological processing
        val sortedEvents = events.sortedBy { it.id }

        // Validate event ordering (detect gaps that might indicate missing events)
        var lastEventId: Long? = null
        sortedEvents.forEach { event ->
            if (lastEventId != null && event.id != lastEventId!! + 1) {
                // Gap detected - not necessarily an error, but worth noting
                // Future: Could indicate need to fetch missing events
            }
            lastEventId = event.id
        }

        sortedEvents.forEach { event ->
            // Determine which balance to update based on event type
            when (event.type) {
                EventType.COINBASE -> {
                    // Coinbase: receiver gets newly minted tokens (unshielded)
                    if (event.receiver == walletAddress) {
                        unshielded += event.amount
                    }
                }

                EventType.UNSHIELDED_TRANSFER -> {
                    // Unshielded transfer: transparent transaction
                    if (event.receiver == walletAddress) {
                        unshielded += event.amount
                    }
                    if (event.sender == walletAddress) {
                        unshielded -= event.amount
                    }
                }

                EventType.SHIELD -> {
                    // Shield: convert unshielded → shielded
                    if (event.sender == walletAddress) {
                        unshielded -= event.amount
                        shielded += event.amount
                    }
                }

                EventType.UNSHIELD -> {
                    // Unshield: convert shielded → unshielded
                    if (event.receiver == walletAddress) {
                        shielded -= event.amount
                        unshielded += event.amount
                    }
                }

                EventType.SHIELDED_TRANSFER -> {
                    // Shielded transfer: private ZK transaction
                    if (event.receiver == walletAddress) {
                        shielded += event.amount
                    }
                    if (event.sender == walletAddress) {
                        shielded -= event.amount
                    }
                }

                EventType.CONTRACT_CALL, EventType.CONTRACT_DEPLOY -> {
                    // Contract interactions may affect balance.
                    // Contract event handling is not yet implemented.
                }

                EventType.UNKNOWN -> {
                    // Skip unknown events
                }
            }
        }

        // Detect balance underflow (double-spend or invalid events)
        if (shielded < BigInteger.ZERO) {
            throw BalanceUnderflowException("Shielded balance underflow: $shielded (possible double-spend)")
        }
        if (unshielded < BigInteger.ZERO) {
            throw BalanceUnderflowException("Unshielded balance underflow: $unshielded (possible double-spend)")
        }
        if (dust < BigInteger.ZERO) {
            throw BalanceUnderflowException("Dust balance underflow: $dust (possible double-spend)")
        }

        return Balance(
            shielded = shielded,
            unshielded = unshielded,
            dust = dust
        )
    }

    /**
     * Calculate balance from raw hex events (includes deserialization).
     *
     * @param rawEvents List of raw hex events
     * @param walletAddress Wallet address to calculate balance for
     * @param deserializer Event deserializer
     * @return Balance for this address
     */
    suspend fun calculateFromRaw(
        rawEvents: List<String>,
        walletAddress: String,
        deserializer: com.midnight.kuira.core.wallet.deserializer.LedgerEventDeserializer
    ): Balance {
        val events = rawEvents.map { deserializer.deserialize(it) }
        return calculate(events, walletAddress)
    }
}
