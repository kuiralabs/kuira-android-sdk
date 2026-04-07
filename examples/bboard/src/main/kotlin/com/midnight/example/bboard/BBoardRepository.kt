package com.midnight.example.bboard

import android.util.Log
import com.midnight.kuira.core.compact.MidnightConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads BBoard contract state using the Midnight Contract SDK.
 *
 * Uses [MidnightConfig.queryState] which properly deserializes the SCALE-encoded
 * state via Rust FFI — no ASCII heuristics or hex parsing.
 */
class BBoardRepository(private val config: MidnightConfig) {

    /**
     * Fetch the current board state.
     *
     * The bboard contract state is an array:
     * - [0] posterCount (number: 0 = vacant, 1 = occupied)
     * - [1] poster (32-byte key hash)
     * - [2] message (the posted text, if any)
     */
    suspend fun fetchBoardState(contractAddress: String): BoardContent {
        return try {
            val state = config.queryState(contractAddress)
            if (state == null) return BoardContent.NotDeployed

            Log.d(TAG, "State: $state")
            parseBoardState(state)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch board state", e)
            BoardContent.Error("Failed to fetch board state: ${e.message}")
        }
    }

    private fun parseBoardState(state: JSONArray): BoardContent {
        if (state.length() == 0) return BoardContent.Vacant

        // Field 0: posterCount
        val firstField = state.optJSONObject(0)
        val posterCount = firstField?.optLong("number", 0) ?: 0

        if (posterCount == 0L) return BoardContent.Vacant

        // Field 1: message (Opaque<String> — text decoded by Rust FFI)
        val messageField = if (state.length() > 1) state.opt(1) else null
        val message = extractText(messageField)

        return BoardContent.Occupied(message = message ?: "(message)")
    }

    /** Extract text from a state field — walks nested structures. */
    private fun extractText(field: Any?): String? {
        if (field == null) return null

        // Direct cell with text
        if (field is JSONObject) {
            val text = field.optString("text", "")
            if (text.isNotEmpty()) return text
        }

        // Array — check each element
        if (field is JSONArray) {
            for (i in 0 until field.length()) {
                val item = field.opt(i)
                val text = extractText(item)
                if (text != null) return text
            }
        }

        return null
    }

    companion object {
        private const val TAG = "BBoard"
    }
}

/** Parsed board content from on-chain state. */
sealed class BoardContent {
    data object Vacant : BoardContent()
    data object NotDeployed : BoardContent()
    data class Occupied(val message: String) : BoardContent()
    data class Error(val reason: String) : BoardContent()
}
