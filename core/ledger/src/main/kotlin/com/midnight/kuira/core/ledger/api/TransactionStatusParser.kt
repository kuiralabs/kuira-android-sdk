package com.midnight.kuira.core.ledger.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One `author_submitAndWatchExtrinsic` status notification, as the watch loop needs it. */
internal sealed interface WatchedStatus {
    /** `future` / `ready`: in the pool, nothing to report yet. */
    data object Pooled : WatchedStatus

    /** Sent to peers. */
    data object Broadcast : WatchedStatus

    data class InBlock(val blockHash: String) : WatchedStatus

    /** The block that included it was reorged out; it is back in the pool. */
    data object Retracted : WatchedStatus

    data class Finalized(val blockHash: String) : WatchedStatus

    /** The watch is over without finality: dropped, invalid, usurped or finality timeout. */
    data class Ended(val result: TransactionFinalizationResult) : WatchedStatus
}

/**
 * Substrate's `TransactionStatus`, lifecycle `future → ready → broadcast → inBlock → finalized`.
 *
 * Some statuses arrive as bare strings and others as single-key objects, and one status can take
 * either shape depending on the node version: `broadcast` is `{"broadcast": [peer_ids]}` on the
 * JSON-RPC wire, not the string the Rust enum prints.
 */
internal fun parseWatchedStatus(status: JsonElement): WatchedStatus = when {
    status is JsonPrimitive && status.isString -> when (status.content) {
        "future", "ready" -> WatchedStatus.Pooled
        "broadcast" -> WatchedStatus.Broadcast
        "retracted" -> WatchedStatus.Retracted
        "dropped" -> WatchedStatus.Ended(TransactionFinalizationResult.Dropped("Transaction dropped from pool"))
        "invalid" -> WatchedStatus.Ended(TransactionFinalizationResult.Invalid("Transaction is invalid"))
        else -> WatchedStatus.Ended(TransactionFinalizationResult.Invalid("Unknown status: ${status.content}"))
    }

    status is JsonObject -> when {
        "inBlock" in status -> blockHashOf(status, "inBlock")
            ?.let { WatchedStatus.InBlock(it) }
            ?: malformed(status)
        "finalized" in status -> blockHashOf(status, "finalized")
            ?.let { WatchedStatus.Finalized(it) }
            ?: malformed(status)
        "broadcast" in status -> WatchedStatus.Broadcast
        "retracted" in status -> WatchedStatus.Retracted
        "usurped" in status -> WatchedStatus.Ended(
            TransactionFinalizationResult.Usurped(blockHashOf(status, "usurped")),
        )
        "finalityTimeout" in status -> WatchedStatus.Ended(
            TransactionFinalizationResult.Dropped("Finality timeout at block ${blockHashOf(status, "finalityTimeout")}"),
        )
        else -> WatchedStatus.Ended(TransactionFinalizationResult.Invalid("Unknown status object: $status"))
    }

    else -> WatchedStatus.Ended(TransactionFinalizationResult.Invalid("Cannot parse status: $status"))
}

/** The `number` of a `chain_getHeader` result: a 0x-prefixed hex block height. */
internal fun parseBlockNumber(hexNumber: String): Long? =
    hexNumber.removePrefix("0x").takeIf { it.isNotEmpty() }?.toLongOrNull(radix = 16)

private fun blockHashOf(status: JsonObject, key: String): String? =
    (status[key] as? JsonPrimitive)?.contentOrNull?.removePrefix("0x")?.takeIf { it.isNotEmpty() }

private fun malformed(status: JsonObject) =
    WatchedStatus.Ended(TransactionFinalizationResult.Invalid("Status without a block hash: $status"))
