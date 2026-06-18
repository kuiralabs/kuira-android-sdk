package com.midnight.kuira.core.crypto.shielded

import java.math.BigInteger

/**
 * Kotlin wrapper for Midnight's ZswapLocalState (shielded coin tracking).
 *
 * Manages shielded coin state via Rust FFI. Mirrors [DustLocalState] pattern.
 *
 * **Lifecycle:**
 * - Create: [create] or [deserialize]
 * - Use: [replayEvents], [getBalances], [getCoinCount]
 * - Persist: [serialize] / [deserialize]
 * - Clean up: [close] (ALWAYS call when done)
 *
 * **Thread Safety:** NOT thread-safe. Use one instance per coroutine scope.
 */
class ZswapLocalState private constructor(
    private var nativePtr: Long
) : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("kuira_crypto_ffi")
        }

        /** Create a new empty ZswapLocalState. */
        fun create(): ZswapLocalState? {
            val ptr = nativeCreate()
            return if (ptr != 0L) ZswapLocalState(ptr) else null
        }

        /** Restore ZswapLocalState from serialized hex string. */
        fun deserialize(hexData: String): ZswapLocalState? {
            val ptr = nativeDeserialize(hexData)
            return if (ptr != 0L) ZswapLocalState(ptr) else null
        }

        /** Free a native state pointer directly (for ZswapTransferBuilder cleanup). */
        fun freeNativePtr(ptr: Long) {
            if (ptr != 0L) nativeFreeStatic(ptr)
        }

        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeDeserialize(hexStr: String): Long
        @JvmStatic private external fun nativeFreeStatic(statePtr: Long)
    }

    /** Expose native pointer for ZswapTransferBuilder (package-private). */
    internal fun nativePtr(): Long {
        require(nativePtr != 0L) { "State already closed" }
        return nativePtr
    }

    /**
     * Replay blockchain events to discover shielded coins.
     *
     * @param seed 32-byte zswap seed (derived at m/44'/2400'/0'/3/0)
     * @param eventsHex Concatenated hex events with "midnight:event[v9]:" prefix separators
     * @return New state with discovered coins, or null on error
     */
    fun replayEvents(seed: ByteArray, eventsHex: String): ZswapLocalState? {
        require(nativePtr != 0L) { "State already closed" }
        require(seed.size == 32) { "Seed must be 32 bytes" }
        val ptr = nativeReplayEvents(nativePtr, seed, eventsHex)
        return if (ptr != 0L) ZswapLocalState(ptr) else null
    }

    /**
     * Replay blockchain events from a file, discovering shielded coins.
     *
     * The cold/full shielded sync at PreProd scale must not pass the entire event
     * log as one giant hex String — that JVM allocation spike triggers GC pauses
     * that freeze the UI. The caller streams events to [filePath] (one hex event
     * per line) and the native side reads them back and replays in 500-event chunks,
     * keeping peak memory bounded. Mirrors [com.midnight.kuira.core.crypto.dust.DustLocalState.replayEventsFromFile].
     *
     * @param seed 32-byte zswap seed (derived at m/44'/2400'/0'/3/0)
     * @param filePath Absolute path to the hex events file (one event per line)
     * @return New state with discovered coins, or null on error
     */
    fun replayEventsFromFile(seed: ByteArray, filePath: String): ZswapLocalState? {
        require(nativePtr != 0L) { "State already closed" }
        require(seed.size == 32) { "Seed must be 32 bytes" }
        val ptr = nativeReplayEventsFromFile(nativePtr, seed, filePath)
        return if (ptr != 0L) ZswapLocalState(ptr) else null
    }

    /**
     * Get shielded balances by token type.
     *
     * @return Map of token type hex → balance (e.g. "0000...00" → 5000000)
     */
    fun getBalances(): Map<String, BigInteger> {
        require(nativePtr != 0L) { "State already closed" }
        val json = nativeGetBalances(nativePtr) ?: return emptyMap()

        // Parse simple JSON: {"token_hex":"balance_str", ...}
        // Safe because keys are hex strings and values are numeric — no embedded delimiters
        val result = mutableMapOf<String, BigInteger>()
        val content = json.trim().removeSurrounding("{", "}")
        if (content.isBlank()) return result

        for (pair in content.split(",")) {
            val parts = pair.split(":")
            if (parts.size == 2) {
                val key = parts[0].trim().removeSurrounding("\"")
                val value = parts[1].trim().removeSurrounding("\"")
                result[key] = BigInteger(value)
            }
        }
        return result
    }

    /** Number of shielded coins in state. */
    fun getCoinCount(): Int {
        require(nativePtr != 0L) { "State already closed" }
        return nativeGetCoinCount(nativePtr)
    }

    /** Merkle tree index — used for tracking sync position. */
    fun getFirstFree(): Long {
        require(nativePtr != 0L) { "State already closed" }
        return nativeGetFirstFree(nativePtr)
    }

    /** Serialize state to hex string for persistence. */
    fun serialize(): String? {
        require(nativePtr != 0L) { "State already closed" }
        return nativeSerialize(nativePtr)
    }

    override fun close() {
        if (nativePtr != 0L) {
            nativeFree(nativePtr)
            nativePtr = 0L
        }
    }

    private external fun nativeFree(statePtr: Long)
    private external fun nativeReplayEvents(statePtr: Long, seed: ByteArray, eventsHex: String): Long
    private external fun nativeReplayEventsFromFile(statePtr: Long, seed: ByteArray, filePath: String): Long
    private external fun nativeGetBalances(statePtr: Long): String?
    private external fun nativeGetCoinCount(statePtr: Long): Int
    private external fun nativeGetFirstFree(statePtr: Long): Long
    private external fun nativeSerialize(statePtr: Long): String?
}
