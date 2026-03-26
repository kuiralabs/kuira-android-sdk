// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.shielded

import javax.annotation.concurrent.ThreadSafe

/**
 * JNI bridge to Midnight's Rust cryptography for deriving shielded keys.
 *
 * **Architecture:**
 * ```
 * Kotlin (Android) → JNI → Rust FFI → Midnight Ledger (Rust)
 * ```
 *
 * **Why JNI?**
 * Midnight's shielded key derivation uses:
 * - Blake2b hashing (90% of operations)
 * - JubJub elliptic curve (10% of operations - ZKP-friendly)
 *
 * Reimplementing JubJub curve in Kotlin is risky for a wallet. Instead, we use
 * Midnight's battle-tested Rust implementation via JNI (98% confidence vs 85% for pure Kotlin).
 *
 * **Native Library:**
 * This class loads `libkuira_crypto_ffi.so` (Android) which is compiled from:
 * - Location: `kuira-crypto-ffi/`
 * - Dependencies: `midnight-zswap`, `midnight-serialize` (version-abstract — see Cargo.toml)
 *
 * **Version Compatibility:**
 * The Rust FFI is the version abstraction boundary — Kotlin depends on stable function
 * signatures, not Midnight version internals. See MIDNIGHT_GUIDELINES.md for version details.
 *
 * **Thread Safety:**
 * This object is thread-safe. The underlying Rust functions are pure and stateless.
 *
 * **Memory Safety:**
 * - The JNI bridge allocates C strings for results
 * - Native memory is freed automatically after copying to Kotlin strings
 * - The seed ByteArray is NOT cleared by this function - caller must wipe it
 *
 * **Usage:**
 * ```kotlin
 * val seed = deriveSeedFromBIP32()  // 32 bytes at m/44'/2400'/0'/3/0
 * try {
 *     val keys = ShieldedKeyDeriver.deriveKeys(seed)
 *     println("Coin PK: ${keys.coinPublicKey}")
 *     println("Enc PK: ${keys.encryptionPublicKey}")
 * } finally {
 *     MemoryUtils.wipe(seed)  // CRITICAL: Always wipe seed
 * }
 * ```
 *
 * **Error Handling:**
 * - Returns null if FFI call fails (invalid seed, native library error, etc.)
 * - Logs error details to stderr in the native library
 * - On Android, check Logcat for native error messages
 *
 * **References:**
 * - Rust FFI: `kuira-crypto-ffi/src/lib.rs`
 * - Algorithm: `docs/SHIELDED_ADDRESS_ALGORITHM.md`
 * - POC Results: `docs/SHIELDED_JNI_POC_RESULTS.md`
 */
@ThreadSafe
object ShieldedKeyDeriver {
    /**
     * Whether the native library has been successfully loaded.
     * Exposed for testing and debugging.
     */
    @Volatile
    private var isNativeLibraryLoaded = false

    /**
     * Error message if native library failed to load.
     * Null if successfully loaded.
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
        } catch (e: Exception) {
            isNativeLibraryLoaded = false
            nativeLibraryError = "Unexpected error loading native library: ${e.message}"
        }
    }

    /**
     * Checks if the native library is loaded and ready to use.
     *
     * **Use case:**
     * Call this during app initialization to fail fast if the library is missing:
     * ```kotlin
     * if (!ShieldedKeyDeriver.isLibraryLoaded()) {
     *     throw IllegalStateException("Shielded crypto not available: ${getLoadError()}")
     * }
     * ```
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
     * Derives shielded public keys from a 32-byte seed using Midnight's algorithm.
     *
     * **Algorithm:**
     * 1. Coin secret key: `Blake2b("midnight:csk" || seed)`
     * 2. Coin public key: `Blake2b("midnight:zswap-pk[v1]" || coin_secret_key)`
     * 3. Encryption keys: JubJub elliptic curve operations (twisted Edwards on BLS12-381)
     *
     * **Input:**
     * The seed should be derived from BIP-32 at path `m/44'/2400'/account'/3/index`:
     * - Derive using HDWallet with role `MidnightKeyRole.ZSWAP`
     * - Extract the 32-byte private key
     * - Pass it to this function
     *
     * **Output:**
     * Two 32-byte public keys encoded as 64-character hex strings:
     * - Coin public key (CPK) - Used in zero-knowledge circuits
     * - Encryption public key (EPK) - Used for encrypting transaction data
     *
     * **Security:**
     * - This function does NOT clear the seed - caller must wipe it
     * - Public keys are safe to share/store - they're public information
     * - The seed MUST be kept secret - it's equivalent to a private key
     *
     * **Performance:**
     * - FFI overhead: < 1ms
     * - Rust crypto: < 1ms (Blake2b + JubJub)
     * - Total: < 2ms per derivation
     *
     * **Compatibility:**
     * The output matches Midnight SDK (key derivation version-abstract via Rust FFI):
     * ```javascript
     * const zswapKeys = ZswapSecretKeys.fromSeed(seed);
     * const coinPk = zswapKeys.coinPublicKey;  // Matches our coinPublicKey
     * const encPk = zswapKeys.encPublicKey;     // Matches our encryptionPublicKey
     * ```
     *
     * **Error Handling:**
     * Returns null if:
     * - Native library not loaded
     * - Native function returns null (internal error)
     * - Returned keys fail validation (invalid hex format)
     *
     * Throws IllegalArgumentException if seed is not exactly 32 bytes.
     *
     * Check stderr/Logcat for detailed error messages from the native library.
     *
     * @param seed 32-byte seed derived from BIP-32 at m/44'/2400'/account'/3/index
     * @return [ShieldedKeys] containing both public keys, or null on error
     * @throws IllegalArgumentException if seed is not 32 bytes
     */
    fun deriveKeys(seed: ByteArray): ShieldedKeys? {
        require(seed.size == 32) {
            "Seed must be exactly 32 bytes (derived from BIP-32), got ${seed.size} bytes"
        }

        if (!isNativeLibraryLoaded) {
            System.err.println("ERROR: Cannot derive shielded keys - native library not loaded")
            System.err.println("Error: $nativeLibraryError")
            return null
        }

        // Call native function
        val result = nativeDeriveShieldedKeys(seed) ?: return null

        // Parse result: "coinPublicKey|encryptionPublicKey" (64 hex chars each)
        val parts = result.split("|")
        if (parts.size != 2) {
            System.err.println("ERROR: Invalid FFI result format: $result")
            return null
        }

        return try {
            ShieldedKeys(
                coinPublicKey = parts[0],
                encryptionPublicKey = parts[1]
            )
        } catch (e: IllegalArgumentException) {
            System.err.println("ERROR: Invalid keys from FFI: ${e.message}")
            null
        }
    }

    /**
     * Native JNI function that calls Rust FFI.
     *
     * **C Signature:**
     * ```c
     * ShieldedKeys* derive_shielded_keys(const uint8_t* seed_ptr, size_t seed_len);
     * ```
     *
     * **Implementation:**
     * This will be implemented in JNI glue code that:
     * 1. Calls the Rust `derive_shielded_keys()` function
     * 2. Reads the C strings from the returned struct
     * 3. Concatenates them as "coinPk|encPk"
     * 4. Calls `free_shielded_keys()` to free native memory
     * 5. Returns the Kotlin string
     *
     * **Why String Return?**
     * JNI strings are automatically managed by the JVM. This is simpler and
     * safer than manually managing JNI byte arrays.
     *
     * @param seed 32-byte seed
     * @return "coinPublicKey|encryptionPublicKey" (64 hex chars each), or null on error
     */
    private external fun nativeDeriveShieldedKeys(seed: ByteArray): String?
}
