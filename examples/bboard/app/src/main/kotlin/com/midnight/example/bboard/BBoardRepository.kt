package com.midnight.example.bboard

import android.util.Log
import com.midnight.kuira.core.compact.ContractCallException
import com.midnight.kuira.core.compact.LedgerReadException
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.MidnightLedger

/**
 * Reads BBoard contract state via [MidnightContract.ledger] — the
 * lossless runtime-driven path. Each ledger field comes back as a
 * typed Kotlin value, so there's no cell-hex parsing, no positional
 * indexing, no string heuristics.
 *
 * BBoard's on-chain ledger has four fields:
 *  - `state`  — `Uint<8>` (0 = vacant, 1 = occupied)
 *  - `message` — `Maybe<Opaque<String>>` (`{is_some, value}` struct)
 *  - `sequence` — `Uint<64>` (post counter; unused in this app)
 *  - `owner` — `Bytes<32>` (poster's pubkey; unused in this app)
 */
class BBoardRepository(private val contract: MidnightContract) {

    /**
     * Fetch the current board state. Returns [BoardContent.NotDeployed]
     * when the indexer hasn't caught up with the deploy tx yet —
     * callers (e.g. the deploy-then-wait loop) can retry. Any other
     * read failure surfaces as [BoardContent.Error].
     */
    suspend fun fetchBoardState(): BoardContent {
        return try {
            val ledger = contract.ledger()
            val stateCode = ledger.getUint8("state")
            when (stateCode) {
                STATE_VACANT -> BoardContent.Vacant
                STATE_OCCUPIED -> readOccupiedMessage(ledger)
                else -> BoardContent.Error("unknown board state code: $stateCode")
            }
        } catch (e: ContractCallException.StateFetchFailed) {
            // Indexer hasn't seen the contract yet — deploy tx in flight
            // or the address simply doesn't exist on chain.
            Log.d(TAG, "fetchBoardState: state fetch failed — ${e.message}")
            BoardContent.NotDeployed
        } catch (e: LedgerReadException) {
            // The chain returned bytes but the ledger() invocation
            // failed. Most often this is a transient indexer-lag
            // window post-deploy; treat as NotDeployed so the wait
            // loop can retry.
            Log.d(TAG, "fetchBoardState: ledger read failed — ${e.message}")
            BoardContent.NotDeployed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch board state", e)
            BoardContent.Error("Failed to fetch board state: ${e.message}")
        }
    }

    /**
     * Unpack the `Maybe<Opaque<String>>` envelope. The compact runtime
     * marshals this as a struct `{is_some: Boolean, value: String}`,
     * which [MidnightLedger.getRaw] returns as `Map<String, Any?>`.
     *
     * `state == OCCUPIED` AND `is_some == false` is a contract-
     * invariant violation — `post` sets both together and `takeDown`
     * clears both together. Surfacing as [BoardContent.Error] makes
     * the violation visible instead of silently falling back to a
     * placeholder string.
     */
    private fun readOccupiedMessage(ledger: MidnightLedger): BoardContent {
        val raw = ledger.getRaw("message") as? Map<*, *>
            ?: return BoardContent.Error("message field has unexpected shape")
        val isSome = raw["is_some"] as? Boolean ?: false
        if (!isSome) {
            return BoardContent.Error("state=OCCUPIED but message.is_some=false")
        }
        val text = raw["value"] as? String
            ?: return BoardContent.Error("message.value is not a String")
        return BoardContent.Occupied(message = text)
    }

    companion object {
        private const val TAG = "BBoard"
        private const val STATE_VACANT = 0
        private const val STATE_OCCUPIED = 1
    }
}

/** Parsed board content from on-chain state. */
sealed class BoardContent {
    data object Vacant : BoardContent()
    data object NotDeployed : BoardContent()
    data class Occupied(val message: String) : BoardContent()
    data class Error(val reason: String) : BoardContent()
}
