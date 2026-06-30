package com.midnight.kuira.core.compact

/**
 * Native bridge to the Rust contract runtime.
 *
 * Exposes functions that match the WASM onchain-runtime exactly:
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

    /** Compute persistent commit: SHA-256(opening || binary_repr(value)). */
    fun persistentCommitAligned(inputJson: String): String? {
        ensureLoaded()
        return nativePersistentCommitAligned(inputJson)
    }

    /** Convert BigInt (hex string) to Value (JSON). */
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

    /** Read contract state fields as JSON. */
    fun stateReadFields(handle: Long): String? {
        ensureLoaded()
        return nativeStateReadFields(handle)
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

    /** Create state from a JSON structure descriptor (nested arrays of nulls). */
    fun stateCreateWithNulls(structureJson: String): Long {
        ensureLoaded()
        return nativeStateCreateWithNulls(structureJson)
    }

    /** Clone a state handle (saves initial state before circuit queries). */
    fun stateClone(handle: Long): Long {
        ensureLoaded()
        return nativeStateClone(handle)
    }

    /** Set an operation on a state handle. */
    fun stateSetOperation(handle: Long, operationName: String) {
        ensureLoaded()
        nativeStateSetOperation(handle, operationName)
    }

    /**
     * Assemble a contract call transaction from circuit execution output.
     *
     * Takes JSON containing proof data (input, output, publicTranscript,
     * privateTranscriptOutputs) plus metadata (networkId, contractAddress,
     * entryPoint, stateHandle).
     *
     * Returns hex-encoded SCALE serialized UnprovenTransaction,
     * or JSON error: {"error": "..."}
     */
    fun assembleContractCallTx(paramsJson: String): String? {
        ensureLoaded()
        return nativeAssembleContractCallTx(paramsJson)
    }

    /**
     * Assemble a contract DEPLOY transaction from constructor output.
     *
     * Takes JSON: `{"network_id":"preprod","state_handle":42}`
     * Returns JSON: `{"tx_hex":"...","contract_address":"..."}`
     * or `{"error":"..."}` on failure.
     */
    fun assembleDeployTx(paramsJson: String): String? {
        ensureLoaded()
        return nativeAssembleDeployTx(paramsJson)
    }

    /**
     * The chain's `global_ttl` (in seconds) — the maximum a transaction's TTL may
     * sit ahead of chain time before the node rejects it (custom error 182 /
     * `IntentTtlTooFarInFuture`). Decoded from the SCALE-encoded ledger parameters.
     *
     * Networks differ wildly (a localnet runs ~100s, PreProd ~1h), so callers use
     * this live value via [IntentTtl] to size a tx TTL instead of a fixed window.
     *
     * @return the seconds value, or `null` if the parameters can't be decoded.
     */
    fun ledgerGlobalTtlSeconds(ledgerParamsHex: String): Long? {
        ensureLoaded()
        return nativeGlobalTtlSecs(ledgerParamsHex).takeIf { it > 0L }
    }

    /**
     * The midnight-ledger version this client links against (e.g. "8.0.3").
     *
     * The host runs a coherence check against the node's reported runtime version so a client
     * built behind the chain warns loudly instead of silently mis-decoding ops (the "Custom
     * error: N" class). Returns `null` only if the native call fails.
     */
    fun ledgerVersion(): String? {
        ensureLoaded()
        return nativeLedgerVersion()
    }

    @JvmStatic private external fun nativePersistentHashAligned(alignedValueJson: String): String?
    @JvmStatic private external fun nativePersistentCommitAligned(inputJson: String): String?
    @JvmStatic private external fun nativeBigIntToValue(bigintStr: String): String?
    @JvmStatic private external fun nativeValueToBigInt(valueJson: String): String?
    @JvmStatic private external fun nativePersistentHash(inputHex: String): String?
    @JvmStatic private external fun nativeStateCreate(stateHex: String): Long
    @JvmStatic private external fun nativeStateCreateWithNulls(structureJson: String): Long
    @JvmStatic private external fun nativeStateSetOperation(handle: Long, operationName: String)
    @JvmStatic private external fun nativeStateFree(handle: Long)
    @JvmStatic private external fun nativeStateClone(handle: Long): Long
    @JvmStatic private external fun nativeContractQuery(handle: Long, opcodesJson: String): String?
    @JvmStatic private external fun nativeStateReadFields(handle: Long): String?
    @JvmStatic private external fun nativeAssembleContractCallTx(paramsJson: String): String?
    @JvmStatic private external fun nativeAssembleDeployTx(paramsJson: String): String?
    @JvmStatic private external fun nativeGlobalTtlSecs(paramsHex: String): Long
    @JvmStatic private external fun nativeLedgerVersion(): String?
}
