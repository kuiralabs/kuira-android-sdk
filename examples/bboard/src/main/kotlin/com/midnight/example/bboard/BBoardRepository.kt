package com.midnight.example.bboard

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads BBoard contract state from the Midnight indexer.
 */
class BBoardRepository(private val indexerUrl: String) {

    /** Fetch the current board state from on-chain data. */
    suspend fun fetchBoardState(contractAddress: String): BoardContent =
        withContext(Dispatchers.IO) {
            try {
                // Query both __typename (deploy vs call) and state hex
                val query = "query { contractAction(address: \\\"$contractAddress\\\") { __typename state } }"
                val data = graphqlQuery(query)

                val contractAction = data.optJSONObject("contractAction")
                    ?: return@withContext BoardContent.NotDeployed

                val typeName = contractAction.optString("__typename", "")
                val stateHex = contractAction.optString("state", "")

                Log.d(TAG, "Contract type: $typeName, state hex: ${stateHex.length} chars")

                when (typeName) {
                    // Just deployed, no calls yet → vacant
                    "ContractDeploy" -> BoardContent.Vacant

                    // Someone called a circuit → check which one
                    "ContractCall", "ContractUpdate" -> {
                        val message = extractMessageFromOccupiedState(stateHex)
                        if (message != null) {
                            BoardContent.Occupied(message = message)
                        } else {
                            // ContractCall could be a takeDown, leaving board vacant
                            BoardContent.Vacant
                        }
                    }

                    else -> BoardContent.Vacant
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch board state", e)
                BoardContent.Error("Failed to fetch board state: ${e.message}")
            }
        }

    /**
     * Extract the posted message from an occupied bboard state.
     *
     * The bboard Compact contract stores the message as a length-prefixed
     * UTF-8 string. In SCALE, this appears as a compact length followed
     * by the raw bytes. We search for the message by looking for the
     * length-prefixed string pattern that appears TWICE in the state
     * (once in the current state, once in the operations metadata).
     */
    private fun extractMessageFromOccupiedState(stateHex: String): String? {
        if (stateHex.isEmpty()) return null

        val bytes = try {
            stateHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return null
        }

        // Find all length-prefixed ASCII strings in the SCALE data.
        // Compact SCALE length encoding: for lengths < 64, the byte is (length << 2).
        // So a 20-char string has prefix byte 0x50 (20 << 2 = 80 = 0x50).
        val messages = mutableListOf<String>()

        for (i in bytes.indices) {
            val lenByte = bytes[i].toInt() and 0xFF
            // Compact SCALE: low 2 bits = mode. Mode 0 = single-byte, length = byte >> 2
            if (lenByte and 0x03 != 0) continue // not single-byte mode
            val strLen = lenByte shr 2
            if (strLen < 5 || strLen > 200) continue // reasonable message length
            if (i + 1 + strLen > bytes.size) continue

            // Check if the following bytes are all printable ASCII
            val candidate = bytes.sliceArray(i + 1..i + strLen)
            if (candidate.all { (it.toInt() and 0xFF) in 32..126 }) {
                val text = String(candidate, Charsets.US_ASCII)
                // Filter out known SCALE infrastructure strings
                if (!isInfrastructureString(text)) {
                    messages.add(text)
                }
            }
        }

        Log.d(TAG, "Found ${messages.size} candidate messages: $messages")

        // The actual posted message appears in the state data.
        // Return the longest non-infrastructure string.
        return messages.maxByOrNull { it.length }
    }

    private fun isInfrastructureString(s: String): Boolean =
        s.startsWith("midnight:") ||
        s.startsWith("contract-state") ||
        s == "post" ||
        s == "takeDown" ||
        s == "struct" ||
        s.all { it == ',' || it == ' ' }

    private fun graphqlQuery(query: String): JSONObject {
        val url = URL("$indexerUrl/graphql")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.outputStream.use { it.write("""{"query":"$query"}""".toByteArray()) }

            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = stream.bufferedReader().readText()

            if (responseCode != 200) throw Exception("Indexer HTTP $responseCode: $body")

            val json = JSONObject(body)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) throw Exception("GraphQL errors: $errors")

            return json.getJSONObject("data")
        } finally {
            conn.disconnect()
        }
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
