package com.midnight.kuira.core.wallet.model

import java.math.BigInteger

/**
 * Deserialized ledger event from Midnight blockchain.
 *
 * Represents a single event on the Midnight ledger (coinbase, transfer, shield, unshield, etc.)
 *
 * **Input Validation:**
 * - `id` must be non-negative
 * - `amount` must be positive (cannot transfer 0 or negative tokens)
 * - `tokenType` cannot be blank
 * - `sender` must be null for COINBASE events, non-null otherwise
 * - `receiver` cannot be blank
 * - `blockHeight` must be non-negative
 * - `timestamp` must be positive
 *
 * @property id Event ID (sequential)
 * @property type Type of ledger event
 * @property amount Token amount involved
 * @property tokenType Token type (native MIDNIGHT, or custom token)
 * @property sender Sender address (null for coinbase events)
 * @property receiver Receiver address
 * @property blockHeight Block height where event occurred
 * @property timestamp Event timestamp in milliseconds
 * @throws IllegalArgumentException if validation fails
 */
data class LedgerEvent(
    val id: Long,
    val type: EventType,
    val amount: BigInteger,
    val tokenType: String = "MIDNIGHT", // Native token by default
    val sender: String? = null,
    val receiver: String,
    val blockHeight: Long,
    val timestamp: Long
) {
    init {
        // Validate ID
        require(id >= 0) { "Event ID must be non-negative, got: $id" }

        // Validate amount
        require(amount > BigInteger.ZERO) {
            "Event amount must be positive, got: $amount"
        }

        // Validate token type
        require(tokenType.isNotBlank()) { "Token type cannot be blank" }

        // Validate sender based on event type
        when (type) {
            EventType.COINBASE -> {
                require(sender == null) { "COINBASE event must not have a sender" }
            }
            EventType.UNSHIELDED_TRANSFER,
            EventType.SHIELD,
            EventType.UNSHIELD,
            EventType.SHIELDED_TRANSFER,
            EventType.CONTRACT_CALL -> {
                require(sender != null && sender.isNotBlank()) {
                    "$type event must have a non-blank sender"
                }
            }
            EventType.CONTRACT_DEPLOY,
            EventType.UNKNOWN -> {
                // Optional sender for these types
            }
        }

        // Validate receiver
        require(receiver.isNotBlank()) { "Receiver address cannot be blank" }

        // Validate block height
        require(blockHeight >= 0) { "Block height must be non-negative, got: $blockHeight" }

        // Validate timestamp
        require(timestamp > 0) { "Timestamp must be positive, got: $timestamp" }
    }
}

/**
 * Types of ledger events.
 */
enum class EventType {
    /**
     * Coinbase event - new tokens minted to validator/miner.
     */
    COINBASE,

    /**
     * Unshielded transfer - transparent token transfer.
     */
    UNSHIELDED_TRANSFER,

    /**
     * Shield - convert unshielded tokens to shielded.
     */
    SHIELD,

    /**
     * Unshield - convert shielded tokens to unshielded.
     */
    UNSHIELD,

    /**
     * Shielded transfer - private ZK token transfer.
     */
    SHIELDED_TRANSFER,

    /**
     * Contract deployment.
     */
    CONTRACT_DEPLOY,

    /**
     * Contract call.
     */
    CONTRACT_CALL,

    /**
     * Unknown/unsupported event type.
     */
    UNKNOWN
}
