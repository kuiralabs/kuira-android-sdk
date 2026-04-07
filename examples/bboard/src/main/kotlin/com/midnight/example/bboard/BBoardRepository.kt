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
     * Since we know the board is occupied (via __typename), we find
     * the user message by collecting all printable ASCII runs and
     * filtering out known SCALE infrastructure strings.
     */
    private fun extractMessageFromOccupiedState(stateHex: String): String? {
        if (stateHex.isEmpty()) return null

        val bytes = try {
            stateHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return null
        }

        // Collect all contiguous printable ASCII sequences
        val sequences = mutableListOf<String>()
        val current = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                current.append(c.toChar())
            } else {
                if (current.length >= 5) sequences.add(current.toString())
                current.clear()
            }
        }
        if (current.length >= 5) sequences.add(current.toString())

        // Filter: keep strings that are mostly letters/spaces (actual messages)
        val candidates = sequences
            .map { it.trimStart('\\', ' ') }
            .filter { s ->
                s.length >= 5 &&
                !s.contains("midnight:") &&
                !s.contains("contract-state") &&
                !s.contains("takeDown") &&
                s != "post" &&
                // At least half the chars should be alphanumeric or space
                s.count { it.isLetterOrDigit() || it == ' ' } > s.length / 2
            }

        Log.d(TAG, "Found ${candidates.size} candidate messages: $candidates")

        return candidates.maxByOrNull { it.length }
    }

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
