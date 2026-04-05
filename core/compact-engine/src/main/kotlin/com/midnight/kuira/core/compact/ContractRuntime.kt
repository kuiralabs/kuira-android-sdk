package com.midnight.kuira.core.compact

/**
 * Native bridge to the Rust contract runtime.
 *
 * Exposes functions that match the WASM onchain-runtime-v2 exactly:
 * - persistentHashAligned: binary_repr + SHA-256 (proper field-aligned encoding)
 * - bigIntToValue / valueToBigInt: field element encoding
 * - contractQuery: VM opcode execution via state handles
 */
object ContractRuntime {

    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("kuira_crypto_ffi")
            loaded = true
        }
    }

    /** Persistent hash with proper AlignedValue encoding (matches WASM). */
    fun persistentHashAligned(alignedValueJson: String): String? {
        ensureLoaded()
        return nativePersistentHashAligned(alignedValueJson)
    }

    /** Convert BigInt (decimal string) to Value (JSON). */
    fun bigIntToValue(bigintStr: String): String? {
        ensureLoaded()
        return nativeBigIntToValue(bigintStr)
    }

    /** Convert Value (JSON) to BigInt (decimal string). */
    fun valueToBigInt(valueJson: String): String? {
        ensureLoaded()
        return nativeValueToBigInt(valueJson)
    }

    /** Raw persistent hash (SHA-256) of hex bytes. */
    fun persistentHash(inputHex: String): String? {
        ensureLoaded()
        return nativePersistentHash(inputHex)
    }

    /** Create a contract state from SCALE hex, return handle. */
    fun stateCreate(stateHex: String): Long {
        ensureLoaded()
        return nativeStateCreate(stateHex)
    }

    /** Free a contract state handle. */
    fun stateFree(handle: Long) {
        ensureLoaded()
        nativeStateFree(handle)
    }

    /** Execute opcodes against a contract state handle. */
    fun contractQuery(handle: Long, opcodesJson: String): String? {
        ensureLoaded()
        return nativeContractQuery(handle, opcodesJson)
    }

    /** Create state with N null slots. */
    fun stateCreateWithNulls(numSlots: Int): Long {
        ensureLoaded()
        return nativeStateCreateWithNulls(numSlots)
    }

    /** Set an operation on a state handle. */
    fun stateSetOperation(handle: Long, operationName: String) {
        ensureLoaded()
        nativeStateSetOperation(handle, operationName)
    }

    @JvmStatic private external fun nativePersistentHashAligned(alignedValueJson: String): String?
    @JvmStatic private external fun nativeBigIntToValue(bigintStr: String): String?
    @JvmStatic private external fun nativeValueToBigInt(valueJson: String): String?
    @JvmStatic private external fun nativePersistentHash(inputHex: String): String?
    @JvmStatic private external fun nativeStateCreate(stateHex: String): Long
    @JvmStatic private external fun nativeStateCreateWithNulls(numSlots: Int): Long
    @JvmStatic private external fun nativeStateSetOperation(handle: Long, operationName: String)
    @JvmStatic private external fun nativeStateFree(handle: Long)
    @JvmStatic private external fun nativeContractQuery(handle: Long, opcodesJson: String): String?
}
