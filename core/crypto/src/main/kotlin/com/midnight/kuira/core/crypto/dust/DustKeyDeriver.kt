// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.crypto.dust

import javax.annotation.concurrent.ThreadSafe

/**
 * JNI bridge to Midnight's Rust cryptography for deriving dust keys.
 *
 * **Architecture:**
 * ```
 * Kotlin (Android) → JNI → Rust FFI → Midnight Ledger (Rust)
 * ```
 *
 * **What is Dust?**
 * Dust is Midnight's fee payment mechanism. Night UTXOs can be registered for
 * dust generation, which produces dust tokens over time. These dust tokens are
 * consumed to pay transaction fees.
 *
 * **Dust Key Derivation Path:**
 * Dust keys are derived at BIP-44 path: `m/44'/2400'/account'/2/index`
 * - Role 2 = Dust (defined in MidnightKeyRole.DUST)
 * - Typically index 0 for primary dust key
 *
 * **Native Library:**
 * This class loads `libkuira_crypto_ffi.so` (Android) which is compiled from:
 * - Location: `rust/kuira-crypto-ffi/`
 * - Dependencies: `midnight-ledger` (currently v7.0.0, version-abstract FFI)
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
 * val seed = deriveSeedFromBIP32()  // 32 bytes at m/44'/2400'/0'/2/0
 * try {
 *     val dustPublicKey = DustKeyDeriver.derivePublicKey(seed)
 *     println("Dust PK: ${dustPublicKey}")
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
 * - Rust FFI: `rust/kuira-crypto-ffi/src/lib.rs`
 * - Dust Spec: `midnight-libraries/midnight-ledger/spec/dust.md`
 */
@ThreadSafe
object DustKeyDeriver {
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
     * Derives dust public key from a 32-byte seed using Midnight's algorithm.
     *
     * **Input:**
     * The seed should be derived from BIP-32 at path `m/44'/2400'/account'/2/index`:
     * - Derive using HDWallet with role `MidnightKeyRole.DUST`
     * - Extract the 32-byte private key
     * - Pass it to this function
     *
     * **Output:**
     * 33-byte public key (1-byte tag + 32 bytes data) encoded as 66-character hex string.
     * This is the DustPublicKey used to receive dust tokens.
     *
     * **Security:**
     * - This function does NOT clear the seed - caller must wipe it
     * - Public key is safe to share/store - it's public information
     * - The seed MUST be kept secret - it's the DustSecretKey
     *
     * **Compatibility:**
     * The output matches Midnight SDK:
     * ```typescript
     * const dustSecretKey = DustSecretKey.fromSeed(seed);
     * const dustPublicKey = dustSecretKey.toPublicKey();  // Matches our output
     * ```
     *
     * **Error Handling:**
     * Returns null if:
     * - Native library not loaded
     * - Native function returns null (internal error)
     * - Returned key fails validation (invalid hex format)
     *
     * Throws IllegalArgumentException if seed is not exactly 32 bytes.
     *
     * @param seed 32-byte seed derived from BIP-32 at m/44'/2400'/account'/2/index
     * @return 66-character hex string of dust public key (with tag), or null on error
     * @throws IllegalArgumentException if seed is not 32 bytes
     */
    fun derivePublicKey(seed: ByteArray): String? {
        require(seed.size == 32) {
            "Seed must be exactly 32 bytes (derived from BIP-32), got ${seed.size} bytes"
        }

        if (!isNativeLibraryLoaded) {
            System.err.println("ERROR: Cannot derive dust keys - native library not loaded")
            System.err.println("Error: $nativeLibraryError")
            return null
        }

        // Call native function
        return nativeDeriveDustPublicKey(seed)
    }

    /**
     * Native JNI function that calls Rust FFI.
     *
     * **Implementation:**
     * This will be implemented in JNI glue code that:
     * 1. Calls the Rust `derive_dust_public_key()` function
     * 2. Reads the hex string result
     * 3. Returns it as a Kotlin string
     *
     * @param seed 32-byte seed
     * @return 64-character hex string of public key, or null on error
     */
    private external fun nativeDeriveDustPublicKey(seed: ByteArray): String?
}
