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
            classify(contract.ledger())
        } catch (e: ContractCallException.StateFetchFailed) {
            // Indexer hasn't seen the contract yet — deploy tx in flight
            // or the address simply doesn't exist on chain.
            Log.d(TAG, "fetchBoardState: state fetch failed — ${e.message}")
            BoardContent.NotDeployed
        } catch (e: LedgerReadException) {
            // Transient indexer-lag window post-deploy. Returning
            // NotDeployed lets the wait loop retry.
            Log.d(TAG, "fetchBoardState: ledger read failed — ${e.message}")
            BoardContent.NotDeployed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch board state", e)
            BoardContent.Error("Failed to fetch board state: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BBoard"
        private const val STATE_VACANT = 0
        private const val STATE_OCCUPIED = 1

        /**
         * Pure mapping from a [MidnightLedger] snapshot to a
         * [BoardContent]. Extracted so unit tests can drive it
         * directly with a canned ledger — no need to fake the full
         * [MidnightContract] (whose constructor is private).
         */
        internal fun classify(ledger: MidnightLedger): BoardContent {
            return when (val stateCode = ledger.getUint8("state")) {
                STATE_VACANT -> BoardContent.Vacant
                STATE_OCCUPIED -> {
                    // `post` sets both fields together and `takeDown`
                    // clears them together — state=OCCUPIED with no
                    // message is a contract-invariant violation.
                    val message = ledger.getMaybeString("message")
                        ?: return BoardContent.Error("state=OCCUPIED but message is absent")
                    BoardContent.Occupied(message)
                }
                else -> BoardContent.Error("unknown board state code: $stateCode")
            }
        }
    }

}

/** Parsed board content from on-chain state. */
sealed class BoardContent {
    data object Vacant : BoardContent()
    data object NotDeployed : BoardContent()
    data class Occupied(val message: String) : BoardContent()
    data class Error(val reason: String) : BoardContent()
}
