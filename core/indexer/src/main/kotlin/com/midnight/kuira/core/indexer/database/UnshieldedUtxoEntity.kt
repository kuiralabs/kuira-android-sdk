package com.midnight.kuira.core.indexer.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.midnight.kuira.core.indexer.model.Utxo

/**
 * Room entity for unshielded UTXO storage.
 *
 * Stores UTXOs (Unspent Transaction Outputs) in local database for balance calculation.
 *
 * Primary key is composite of transactionHash + outputIndex (unique identifier).
 * Indexed on owner (address) for fast balance queries.
 *
 * Note: Uses transactionHash for LOCAL primary key. However, the blockchain
 * identifies UTXOs by intentHash + outputNo (used when building transactions).
 */
@Entity(
    tableName = "unshielded_utxos",
    indices = [
        Index(value = ["owner"]),
        Index(value = ["token_type"]),
        Index(value = ["state"]),
        Index(value = ["intent_hash", "output_index"])  // For finding spent UTXOs
    ]
)
data class UnshieldedUtxoEntity(
    /**
     * Unique identifier: "transactionHash:outputIndex"
     */
    @PrimaryKey
    val id: String,

    /**
     * Transaction hash that created this UTXO.
     * Used for local storage identifier (primary key).
     */
    @ColumnInfo(name = "transaction_hash")
    val transactionHash: String,

    /**
     * Intent hash - used by the BLOCKCHAIN to identify UTXOs.
     * When building transactions for submission, use this (not transactionHash).
     */
    @ColumnInfo(name = "intent_hash")
    val intentHash: String,

    /**
     * Output index in the transaction.
     */
    @ColumnInfo(name = "output_index")
    val outputIndex: Int,

    /**
     * Address that owns this UTXO (Bech32m encoded UserAddress).
     * Format: mn_addr_[network]1...
     */
    @ColumnInfo(name = "owner")
    val owner: String,

    /**
     * Public key of the owner (hex-encoded compressed key, 33 bytes).
     * Required for spending this UTXO (UtxoSpend.owner expects VerifyingKey).
     * Null if UTXO doesn't belong to our wallet.
     */
    @ColumnInfo(name = "owner_public_key")
    val ownerPublicKey: String?,

    /**
     * Token type (e.g., "DUST").
     */
    @ColumnInfo(name = "token_type")
    val tokenType: String,

    /**
     * Value in smallest unit (stored as string BigInteger).
     */
    @ColumnInfo(name = "value")
    val value: String,

    /**
     * Creation time (Unix timestamp in seconds).
     */
    @ColumnInfo(name = "ctime")
    val ctime: Long,

    /**
     * Whether this UTXO is registered for dust generation.
     */
    @ColumnInfo(name = "registered_for_dust_generation")
    val registeredForDustGeneration: Boolean,

    /**
     * UTXO state (AVAILABLE, PENDING, or SPENT).
     *
     * - AVAILABLE: Can be used in new transactions, included in balance
     * - PENDING: Locked for pending transaction, excluded from balance
     * - SPENT: Confirmed spent, excluded from balance
     *
     * State transitions:
     * - New UTXO → AVAILABLE
     * - Transaction created → PENDING
     * - Transaction SUCCESS → SPENT
     * - Transaction FAILURE → AVAILABLE (unlocked)
     */
    @ColumnInfo(name = "state")
    val state: UtxoState = UtxoState.AVAILABLE
) {
    companion object {
        /**
         * Convert domain model to entity.
         *
         * CRITICAL: The Utxo must have transactionHash set via withTransactionHash()
         * before calling this method! The identifier uses transactionHash, not intentHash.
         */
        fun fromUtxo(utxo: Utxo, state: UtxoState = UtxoState.AVAILABLE): UnshieldedUtxoEntity {
            require(utxo.transactionHash.isNotBlank()) {
                "Utxo.transactionHash must be set before creating entity. " +
                "Call utxo.withTransactionHash(txHash) first."
            }
            return UnshieldedUtxoEntity(
                id = utxo.identifier(),  // Now uses transactionHash:outputIndex
                transactionHash = utxo.transactionHash,
                intentHash = utxo.intentHash,
                outputIndex = utxo.outputIndex,
                owner = utxo.owner,
                ownerPublicKey = utxo.ownerPublicKey,
                tokenType = utxo.tokenType,
                value = utxo.value,
                ctime = utxo.ctime,
                registeredForDustGeneration = utxo.registeredForDustGeneration,
                state = state
            )
        }
    }

    /**
     * Convert entity to domain model.
     */
    fun toUtxo(): Utxo {
        return Utxo(
            value = value,
            owner = owner,
            ownerPublicKey = ownerPublicKey,
            tokenType = tokenType,
            intentHash = intentHash,
            outputIndex = outputIndex,
            ctime = ctime,
            registeredForDustGeneration = registeredForDustGeneration,
            transactionHash = transactionHash
        )
    }
}
