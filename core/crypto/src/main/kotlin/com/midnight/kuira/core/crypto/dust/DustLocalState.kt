// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.dust

import java.io.Closeable
import java.math.BigInteger
import javax.annotation.concurrent.ThreadSafe

/**
 * JNI bridge to Midnight's Rust DustLocalState for managing dust wallet state.
 *
 * **Architecture:**
 * ```
 * Kotlin (Android) → JNI → Rust FFI → Midnight Ledger (Rust)
 * ```
 *
 * **What is DustLocalState?**
 * DustLocalState is the core wallet state that tracks all dust UTXOs (tokens)
 * for fee payments. It maintains:
 * - All registered dust tokens
 * - Dust generation progress over time
 * - Spending history
 * - Balance calculations
 *
 * **Value Units (IMPORTANT):**
 * The Midnight dust system uses different units for different purposes:
 * - **Specks:** Smallest dust unit (balance, initial_value)
 *   - 1 Dust = 1,000,000 Specks
 *   - Used for dust token values and balances
 * - **Stars:** Night (native token) unit
 *   - 1 NIGHT = 1,000,000 Stars
 *   - Used for backing Night UTXO values that generate dust
 * - **Dust vs Night:** These are DIFFERENT tokens with DIFFERENT units!
 *   - Dust is generated FROM Night tokens over time
 *   - Dust is used to pay transaction fees
 *
 * **Thread Safety:**
 * This class is NOT thread-safe. Do not share instances across threads.
 * Each thread should create its own instance or use external synchronization.
 *
 * **Memory Safety:**
 * This class manages native memory and MUST be explicitly closed to prevent leaks.
 * Use try-with-resources or finally blocks:
 * ```kotlin
 * val state = DustLocalState.create()
 * try {
 *     val balance = state.getBalance(System.currentTimeMillis())
 *     println("Balance: $balance Specks")
 * } finally {
 *     state.close()
 * }
 * ```
 *
 * **Usage:**
 * ```kotlin
 * // Create new state
 * val state = DustLocalState.create() ?: error("Failed to create state")
 * try {
 *     // Get current balance
 *     val now = System.currentTimeMillis()
 *     val balance = state.getBalance(now)
 *     println("Current balance: $balance Specks")
 *
 *     // Serialize state for storage
 *     val serialized = state.serialize()
 *     database.saveState(serialized)
 * } finally {
 *     state.close()
 * }
 * ```
 *
 * **Native Library:**
 * This class loads `libkuira_crypto_ffi.so` (Android) which is compiled from:
 * - Location: `kuira-crypto-ffi/`
 * - Dependencies: `midnight-ledger` (version-abstract FFI — see Cargo.toml)
 *
 * **References:**
 * - Rust FFI: `kuira-crypto-ffi/src/dust_ffi.rs`
 * - Dust Spec: `midnight-libraries/midnight-ledger/spec/dust.md`
 */
class DustLocalState private constructor(
    /**
     * Native pointer to Rust DustLocalState<InMemoryDB>.
     * 0 means invalid/freed state.
     */
    private var nativePtr: Long
) : Closeable {

    /**
     * Whether this state instance has been closed.
     */
    private var isClosed = false

    companion object {
        private const val TAG = "DustLocalState"

        /**
         * Logs error message using Android Log if available, otherwise System.err.
         * This allows the code to work in both Android runtime and JVM unit tests.
         */
        private fun logError(message: String, throwable: Throwable? = null) {
            try {
                // Try to use Android Log API (available on Android runtime)
                val logClass = Class.forName("android.util.Log")
                val method = logClass.getMethod("e", String::class.java, String::class.java, Throwable::class.java)
                method.invoke(null, TAG, message, throwable)
            } catch (e: Exception) {
                // Fall back to System.err for JVM unit tests
                System.err.println("$TAG: $message")
                throwable?.printStackTrace(System.err)
            }
        }

        /**
         * Whether the native library has been successfully loaded.
         */
        @Volatile
        private var isNativeLibraryLoaded = false

        /**
         * Error message if native library failed to load.
         */
        @Volatile
        private var nativeLibraryError: String? = null

        init {
            try {
                System.loadLibrary("kuira_crypto_ffi")
                isNativeLibraryLoaded = true
                nativeLibraryError = null
            } catch (e: UnsatisfiedLinkError) {
                isNativeLibraryLoaded = false
                nativeLibraryError = "Failed to load native library 'kuira_crypto_ffi': ${e.message}\n" +
                        "Make sure libkuira_crypto_ffi.so is bundled in the APK and matches the device architecture."
                logError("Failed to load kuira_crypto_ffi library", e)
            } catch (e: Exception) {
                isNativeLibraryLoaded = false
                nativeLibraryError = "Unexpected error loading native library: ${e.message}"
                logError("Unexpected error loading native library", e)
            }
        }

        /**
         * Checks if the native library is loaded and ready to use.
         *
         * @return true if native library loaded successfully, false otherwise
         */
        fun isLibraryLoaded(): Boolean = isNativeLibraryLoaded

        /**
         * Gets the error message if native library failed to load.
         *
         * @return Error message, or null if library loaded successfully
         */
        fun getLoadError(): String? = nativeLibraryError

        /**
         * Creates a new DustLocalState instance with default parameters.
         *
         * **Error Handling:**
         * Returns null if:
         * - Native library not loaded
         * - Native function returns 0 (allocation failure)
         *
         * **Important:** The returned instance MUST be closed when done to prevent memory leaks.
         *
         * @return New DustLocalState instance, or null on error
         */
        fun create(): DustLocalState? {
            if (!isNativeLibraryLoaded) {
                logError("Cannot create dust state - native library not loaded: $nativeLibraryError")
                return null
            }

            val ptr = nativeCreateDustLocalState()
            if (ptr == 0L) {
                logError("Native function returned null pointer")
                return null
            }

            return DustLocalState(ptr)
        }

        /**
         * Deserializes a DustLocalState from previously serialized bytes.
         *
         * **Usage:**
         * ```kotlin
         * // Load from database
         * val serialized = database.loadDustState()
         *
         * // Deserialize
         * val state = DustLocalState.deserialize(serialized)
         * if (state != null) {
         *     try {
         *         val balance = state.getBalance(System.currentTimeMillis())
         *         println("Loaded balance: $balance Specks")
         *     } finally {
         *         state.close()
         *     }
         * }
         * ```
         *
         * **Error Handling:**
         * Returns null if:
         * - Native library not loaded
         * - Data is empty
         * - Data is corrupted/invalid
         * - Deserialization fails
         *
         * **Important:** The returned instance MUST be closed when done to prevent memory leaks.
         *
         * @param data Serialized DustLocalState bytes (from serialize())
         * @return DustLocalState instance, or null on error
         */
        fun deserialize(data: ByteArray): DustLocalState? {
            if (!isNativeLibraryLoaded) {
                logError("Cannot deserialize dust state - native library not loaded: $nativeLibraryError")
                return null
            }

            if (data.isEmpty()) {
                logError("Cannot deserialize empty data")
                return null
            }

            val ptr = nativeDeserializeDustState(data)
            if (ptr == 0L) {
                logError("Deserialization failed - native function returned null pointer")
                return null
            }

            return DustLocalState(ptr)
        }

        /* Native functions */
        private external fun nativeCreateDustLocalState(): Long
        private external fun nativeDeserializeDustState(data: ByteArray): Long
    }

    /**
     * Gets the native pointer to the DustLocalState.
     *
     * **Use Case:**
     * Used by DustActionsBuilder to pass state pointer to FFI functions like create_dust_spend().
     *
     * **IMPORTANT:**
     * - Pointer is only valid while state is not closed
     * - Do NOT manually free this pointer (managed by close())
     * - Do NOT use after calling close()
     *
     * @return Native pointer (0 if closed)
     */
    fun getStatePointer(): Long {
        checkNotClosed()
        return nativePtr
    }

    /**
     * Gets the wallet balance at a specific time.
     *
     * **Time Parameter:**
     * Unix timestamp in milliseconds (e.g., `System.currentTimeMillis()`).
     * The balance is calculated based on dust generation progress up to this time.
     *
     * **Return Value:**
     * Balance in Specks (smallest dust unit).
     * - 1 Dust = 1,000,000 Specks
     * - Uses BigInteger to support very large balances (u128 from Rust)
     *
     * **Error Handling:**
     * Returns BigInteger.ZERO if:
     * - State is closed
     * - Native function returns null
     * - Invalid timestamp
     *
     * @param timeMillis Unix timestamp in milliseconds
     * @return Balance in Specks, or ZERO on error
     */
    fun getBalance(timeMillis: Long): BigInteger {
        checkNotClosed()

        val balanceStr = nativeDustWalletBalance(nativePtr, timeMillis)
            ?: return BigInteger.ZERO

        return try {
            BigInteger(balanceStr)
        } catch (e: NumberFormatException) {
            logError("Invalid balance string from native: $balanceStr", e)
            BigInteger.ZERO
        }
    }

    /**
     * Commitment-tree Merkle root as a lowercase hex string, or null if the
     * tree has no root (state not rehashed). Two states that have applied the
     * same events — whether replayed in one pass or as checkpoint + delta —
     * have equal roots; this is the identity tests assert and the chain
     * validates a dust spend against.
     */
    fun commitmentRoot(): String? {
        checkNotClosed()
        return nativeDustCommitmentRoot(nativePtr)
    }

    /** Generation-tree Merkle root as a lowercase hex string, or null. See [commitmentRoot]. */
    fun generationRoot(): String? {
        checkNotClosed()
        return nativeDustGenerationRoot(nativePtr)
    }

    /**
     * Serializes the DustLocalState to bytes for persistent storage.
     *
     * **Serialization Format:**
     * Uses Midnight's SCALE codec (same as Rust's Serializable trait).
     * The serialized data includes:
     * - All dust tokens
     * - Dust parameters
     * - Internal state
     *
     * **Usage:**
     * ```kotlin
     * val serialized = state.serialize()
     * if (serialized != null) {
     *     database.saveDustState(serialized)
     * }
     * ```
     *
     * **Deserialization:**
     * To deserialize, you'll need to implement deserialization (Phase 2D-3).
     *
     * **Error Handling:**
     * Returns null if:
     * - State is closed
     * - Native function returns null (serialization error)
     *
     * @return Serialized bytes, or null on error
     */
    fun serialize(): ByteArray? {
        checkNotClosed()
        return nativeSerializeDustState(nativePtr)
    }

    /**
     * Replays blockchain events into this DustLocalState to sync wallet state.
     *
     * **What is Event Replay?**
     * When DustRegistration transactions are applied to the blockchain, the ledger
     * emits events (DustInitialUtxo, DustSpendProcessed, etc.). Replaying these
     * events into DustLocalState syncs the wallet with the blockchain.
     *
     * **Event Flow:**
     * 1. Submit DustRegistration transaction to blockchain
     * 2. Blockchain emits DustInitialUtxo event
     * 3. Call `replayEvents()` with these events
     * 4. DustLocalState now tracks the new dust UTXO
     * 5. Balance increases as dust accumulates over time
     *
     * **Event Format:**
     * Events must be SCALE-encoded as `Vec<Event<InMemoryDB>>` from midnight-ledger,
     * then hex-encoded. This matches how events are transmitted from the blockchain.
     *
     * **Usage:**
     * ```kotlin
     * val state = DustLocalState.create()!!
     * try {
     *     // Get events from blockchain (hex-encoded SCALE bytes)
     *     val eventsHex = "0x..." // From blockchain indexer
     *
     *     // Replay events to sync wallet
     *     val newState = state.replayEvents(seed, eventsHex)
     *
     *     if (newState != null) {
     *         // Use new state (old state is now invalid)
     *         state.close()
     *
     *         // Check new balance
     *         val balance = newState.getBalance(System.currentTimeMillis())
     *         println("New balance: $balance Specks")
     *
     *         newState.close()
     *     }
     * } finally {
     *     if (!state.isClosed()) state.close()
     * }
     * ```
     *
     * **Important:**
     * - This method returns a NEW DustLocalState instance
     * - The old state (this instance) becomes invalid after replay
     * - You MUST close both the old state and the new state
     * - Events are immutable - replay returns a new state rather than mutating
     *
     * **Error Handling:**
     * Returns null if:
     * - State is closed
     * - Seed is invalid (not 32 bytes)
     * - Events hex is malformed
     * - Event deserialization fails
     * - Event replay fails (e.g., non-linear insertion)
     *
     * @param seed 32-byte seed for deriving DustSecretKey (must match wallet)
     * @param eventsHex Hex-encoded SCALE-serialized events from blockchain
     * @return New DustLocalState with events applied, or null on error
     */
    fun replayEvents(seed: ByteArray, eventsHex: String): DustLocalState? {
        checkNotClosed()

        if (seed.size != 32) {
            logError("Seed must be 32 bytes, got ${seed.size}")
            return null
        }

        val newPtr = nativeDustReplayEvents(nativePtr, seed, eventsHex)
        if (newPtr == 0L) {
            logError("Event replay failed")
            return null
        }

        return DustLocalState(newPtr)
    }

    /**
     * Replays events from a file in a single pass.
     *
     * The file contains concatenated tagged-SCALE hex events (same format
     * as [replayEvents] hex parameter). Rust reads the file in native memory,
     * avoiding JVM heap pressure for large event sets (PREPROD: 250k+ events).
     *
     * Single-pass replay ensures generation collapses and Merkle tree rehash
     * happen exactly once, producing roots that match the node's root history.
     *
     * @param seed 32-byte dust seed
     * @param filePath Absolute path to the hex events file
     * @return New DustLocalState, or null on error
     */
    fun replayEventsFromFile(seed: ByteArray, filePath: String): DustLocalState? {
        checkNotClosed()

        if (seed.size != 32) {
            logError("Seed must be 32 bytes, got ${seed.size}")
            return null
        }

        val newPtr = nativeDustReplayEventsFromFile(nativePtr, seed, filePath)
        if (newPtr == 0L) {
            logError("Event replay from file failed")
            return null
        }

        return DustLocalState(newPtr)
    }

    /**
     * Gets the number of dust UTXOs in this wallet state.
     *
     * **What are UTXOs?**
     * UTXOs (Unspent Transaction Outputs) are the individual dust tokens that make up
     * your wallet balance. Each UTXO represents a distinct dust token with its own:
     * - Initial value
     * - Creation time
     * - Backing Night UTXO
     * - Generation progress
     *
     * **Usage:**
     * ```kotlin
     * val state = DustLocalState.create()!!
     * try {
     *     val count = state.getUtxoCount()
     *     println("You have $count dust tokens")
     * } finally {
     *     state.close()
     * }
     * ```
     *
     * **Error Handling:**
     * Returns 0 if:
     * - State is closed
     * - Native function returns 0
     *
     * @return Number of UTXOs in wallet
     */
    fun getUtxoCount(): Int {
        checkNotClosed()
        return nativeDustUtxoCount(nativePtr)
    }

    /**
     * Gets a dust UTXO at a specific index.
     *
     * **Index Range:**
     * Valid indices are 0 until getUtxoCount() - 1.
     *
     * **UTXO Format:**
     * Returns hex-encoded serialized QualifiedDustOutput using Midnight's SCALE codec.
     * This can be deserialized into a DustUtxo data class (to be implemented).
     *
     * **Usage:**
     * ```kotlin
     * val state = DustLocalState.create()!!
     * try {
     *     for (i in 0 until state.getUtxoCount()) {
     *         val utxoHex = state.getUtxoAt(i)
     *         if (utxoHex != null) {
     *             println("UTXO $i: $utxoHex")
     *         }
     *     }
     * } finally {
     *     state.close()
     * }
     * ```
     *
     * **Error Handling:**
     * Returns null if:
     * - State is closed
     * - Index is out of bounds
     * - Native function returns null
     *
     * @param index Index of UTXO to retrieve (0-based)
     * @return Hex-encoded serialized UTXO, or null if index out of bounds
     */
    fun getUtxoAt(index: Int): String? {
        checkNotClosed()

        if (index < 0) {
            return null
        }

        return nativeDustGetUtxoAt(nativePtr, index)
    }

    /**
     * Gets all dust UTXOs in this wallet state.
     *
     * **Convenience Method:**
     * This method combines getUtxoCount() and getUtxoAt() to return all UTXOs
     * as a list.
     *
     * **Performance:**
     * This creates a list and fetches all UTXOs. For large wallets, consider
     * iterating with getUtxoAt() instead.
     *
     * **Usage:**
     * ```kotlin
     * val state = DustLocalState.create()!!
     * try {
     *     val utxos = state.getAllUtxos()
     *     println("Total UTXOs: ${utxos.size}")
     *     utxos.forEach { utxoHex ->
     *         // Process each UTXO
     *     }
     * } finally {
     *     state.close()
     * }
     * ```
     *
     * @return List of hex-encoded serialized UTXOs (may be empty)
     */
    fun getAllUtxos(): List<String> {
        checkNotClosed()

        val count = getUtxoCount()
        if (count == 0) {
            return emptyList()
        }

        return (0 until count).mapNotNull { index ->
            getUtxoAt(index)
        }
    }

    /**
     * Lowercase-hex nullifiers of every UTXO currently in this state.
     *
     * The wallet uses this to prune its durable spent-dust skip-set (drop recorded
     * nullifiers the synced state no longer holds — the event stream caught up) and
     * to detect when all spendable dust is already excluded. A dust nullifier is
     * derived from the secret key, so the seed is required.
     *
     * @param seed 32-byte dust seed
     * @return the nullifier hex list (empty if the state has no UTXOs), or **null**
     *   if the native call failed. Callers MUST treat null as "unknown" and skip
     *   pruning — treating it as empty would wrongly drop the whole skip-set.
     */
    fun currentNullifiers(seed: ByteArray): List<String>? {
        checkNotClosed()
        val raw = nativeDustCurrentNullifiers(nativePtr, seed) ?: return null
        return if (raw.isEmpty()) emptyList() else raw.split(",").filter { it.isNotEmpty() }
    }

    /**
     * Frees the native DustLocalState memory.
     *
     * **Important:**
     * - Must be called exactly once per instance
     * - After calling, this instance becomes invalid
     * - Calling any method after close() will throw IllegalStateException
     *
     * **Idempotent:**
     * Calling close() multiple times is safe (no-op after first call).
     */
    override fun close() {
        if (isClosed) {
            return // Already closed, no-op
        }

        if (nativePtr != 0L) {
            nativeFreeDustLocalState(nativePtr)
            nativePtr = 0L
        }

        isClosed = true
    }

    /**
     * Checks if this state instance is closed.
     *
     * @return true if closed, false otherwise
     */
    fun isClosed(): Boolean = isClosed

    /**
     * Ensures the state is not closed, throws if it is.
     *
     * @throws IllegalStateException if state is closed
     */
    private fun checkNotClosed() {
        check(!isClosed) {
            "DustLocalState has been closed and cannot be used"
        }
    }

    /* Native JNI functions */

    /**
     * Gets wallet balance from native state.
     *
     * @param statePtr Native pointer to DustLocalState
     * @param timeMillis Unix timestamp in milliseconds
     * @return Balance as decimal string (e.g., "1000000"), or null on error
     */
    private external fun nativeDustWalletBalance(statePtr: Long, timeMillis: Long): String?

    private external fun nativeDustCommitmentRoot(statePtr: Long): String?

    private external fun nativeDustGenerationRoot(statePtr: Long): String?

    /**
     * Lists current dust UTXO nullifiers (lowercase hex, comma-separated; empty
     * string if no UTXOs), or null on native error.
     */
    private external fun nativeDustCurrentNullifiers(statePtr: Long, seed: ByteArray): String?

    /**
     * Serializes native state to bytes.
     *
     * @param statePtr Native pointer to DustLocalState
     * @return Serialized bytes, or null on error
     */
    private external fun nativeSerializeDustState(statePtr: Long): ByteArray?

    /**
     * Frees native DustLocalState pointer.
     *
     * @param statePtr Native pointer to DustLocalState
     */
    private external fun nativeFreeDustLocalState(statePtr: Long)

    /**
     * Gets the count of dust UTXOs from native state.
     *
     * @param statePtr Native pointer to DustLocalState
     * @return Number of UTXOs, or 0 on error
     */
    private external fun nativeDustUtxoCount(statePtr: Long): Int

    /**
     * Gets a dust UTXO at a specific index from native state.
     *
     * @param statePtr Native pointer to DustLocalState
     * @param index Index of UTXO to retrieve
     * @return Hex-encoded serialized UTXO, or null if out of bounds
     */
    private external fun nativeDustGetUtxoAt(statePtr: Long, index: Int): String?

    /**
     * Replays blockchain events into DustLocalState via FFI.
     *
     * @param statePtr Native pointer to DustLocalState
     * @param seed 32-byte seed for deriving DustSecretKey
     * @param eventsHex Hex-encoded SCALE-serialized events
     * @return Pointer to new DustLocalState with events applied, or 0 on error
     */
    private external fun nativeDustReplayEvents(
        statePtr: Long,
        seed: ByteArray,
        eventsHex: String
    ): Long

    private external fun nativeDustReplayEventsFromFile(
        statePtr: Long,
        seed: ByteArray,
        filePath: String
    ): Long
}
