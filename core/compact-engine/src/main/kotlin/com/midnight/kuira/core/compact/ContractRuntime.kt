package com.midnight.kuira.core.compact

/**
 * Native bridge to the Rust contract runtime.
 *
 * Manages contract state handles in Rust memory.
 * JS holds integer handles, Rust owns the actual state.
 */
object ContractRuntime {

    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("kuira_crypto_ffi")
            loaded = true
        }
    }

    /** Create a contract state from SCALE hex, return a handle. */
    fun stateCreate(stateHex: String): Long {
        ensureLoaded()
        return nativeStateCreate(stateHex)
    }

    /** Free a contract state handle. */
    fun stateFree(handle: Long) {
        ensureLoaded()
        nativeStateFree(handle)
    }

    /**
     * Execute opcodes against a contract state.
     *
     * @param handle state handle from stateCreate
     * @param opcodesJson JSON array of opcodes
     * @return JSON: { "handle": <new>, "events": [...] } or { "error": "..." }
     */
    fun contractQuery(handle: Long, opcodesJson: String): String? {
        ensureLoaded()
        return nativeContractQuery(handle, opcodesJson)
    }

    /** Compute persistent hash (SHA-256). */
    fun persistentHash(inputHex: String): String? {
        ensureLoaded()
        return nativePersistentHash(inputHex)
    }

    @JvmStatic private external fun nativeStateCreate(stateHex: String): Long
    @JvmStatic private external fun nativeStateFree(handle: Long)
    @JvmStatic private external fun nativeContractQuery(handle: Long, opcodesJson: String): String?
    @JvmStatic private external fun nativePersistentHash(inputHex: String): String?
}
