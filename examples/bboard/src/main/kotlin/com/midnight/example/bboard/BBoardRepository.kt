package com.midnight.example.bboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads BBoard contract state from the Midnight indexer.
 *
 * Uses the `mn contract state` equivalent GraphQL query to fetch
 * the current board message, poster, and occupancy status.
 */
class BBoardRepository(private val indexerUrl: String) {

    /** Fetch the current board state from on-chain data. */
    suspend fun fetchBoardState(contractAddress: String): BoardContent =
        withContext(Dispatchers.IO) {
            try {
                val query = "query { contractAction(address: \\\"$contractAddress\\\") { state } }"
                val data = graphqlQuery(query)

                val contractAction = data.optJSONObject("contractAction")
                    ?: return@withContext BoardContent.NotDeployed

                val stateHex = contractAction.getString("state")
                parseBoardState(stateHex)
            } catch (e: Exception) {
                BoardContent.Error("Failed to fetch board state: ${e.message}")
            }
        }

    /**
     * Parse bboard state from SCALE hex.
     *
     * The bboard contract state has a simple structure:
     * - posterCount = 0 → vacant
     * - posterCount > 0 → occupied (message stored in state)
     *
     * For the example, we check if the state contains the message bytes
     * rather than fully parsing SCALE (which would require the Rust FFI).
     * A production app would use the SDK's state deserialization.
     */
    private fun parseBoardState(stateHex: String): BoardContent {
        if (stateHex.isEmpty()) return BoardContent.Vacant

        // The bboard state hex contains the posted message as UTF-8 bytes.
        // A vacant board has a specific short pattern; an occupied board
        // contains the message string embedded in the SCALE encoding.
        // Simple heuristic: look for readable ASCII sequences in the hex.
        val bytes = try {
            stateHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            return BoardContent.Error("Invalid state hex")
        }

        // Find printable ASCII sequences (the posted message)
        val message = extractMessage(bytes)
        return if (message != null) {
            BoardContent.Occupied(message = message)
        } else {
            BoardContent.Vacant
        }
    }

    /** Extract the posted message from state bytes. */
    private fun extractMessage(bytes: ByteArray): String? {
        // Collect all printable ASCII sequences
        val sequences = mutableListOf<String>()
        val current = StringBuilder()

        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                current.append(c.toChar())
            } else {
                if (current.length > 3) sequences.add(current.toString())
                current.clear()
            }
        }
        if (current.length > 3) sequences.add(current.toString())

        // Filter out SCALE infrastructure strings, keep user messages
        val candidates = sequences
            .map { it.trimStart('\\', ' ') }
            .filter { s ->
                s.length > 3 &&
                !s.startsWith("midnight:") &&
                !s.startsWith("contract-state") &&
                !s.startsWith("takeDown") &&
                s != "post" &&
                !s.all { it == ',' }
            }

        // Return the longest candidate (the posted message)
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

            if (responseCode != 200) {
                throw Exception("Indexer HTTP $responseCode: $body")
            }

            val json = JSONObject(body)
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                throw Exception("GraphQL errors: $errors")
            }

            return json.getJSONObject("data")
        } finally {
            conn.disconnect()
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
