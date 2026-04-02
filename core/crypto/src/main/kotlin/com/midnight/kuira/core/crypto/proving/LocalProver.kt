package com.midnight.kuira.core.crypto.proving

/**
 * Local ZK prover — generates proofs on the phone instead of a remote server.
 *
 * Uses the same Rust proving engine (`midnight-zkir`) as the proof server.
 * Proving keys must be downloaded via [ProvingKeyManager] before use.
 *
 * **Why local proving:**
 * - Privacy: proof preimages never leave the phone
 * - Offline: no network needed for wallet transactions
 * - Speed: no HTTP round-trip overhead
 * - Agent autonomy: agents can prove without server dependency
 */
class LocalProver private constructor() {

    companion object {

        private val nativeLoaded: Boolean by lazy {
            try {
                System.loadLibrary("kuira_crypto_ffi")
                true
            } catch (e: UnsatisfiedLinkError) {
                false
            }
        }

        private fun ensureNativeLoaded() {
            check(nativeLoaded) { "Native library kuira_crypto_ffi not available" }
        }

        /**
         * Prove a transaction locally using cached proving keys.
         *
         * Input/output formats are identical to the proof server:
         * - Input: hex-encoded (Transaction, HashMap) tuple (same as POST /prove-tx body)
         * - Output: hex-encoded proven Transaction (same as /prove-tx response)
         *
         * The existing seal → submit pipeline works unchanged after local proving.
         *
         * @param unprovenTxHex Same format currently sent to proof server
         * @param keysDir Absolute path to directory with cached proving keys
         * @return Proven transaction hex, or null on failure
         */
        fun proveTransaction(unprovenTxHex: String, keysDir: String): String? {
            ensureNativeLoaded()
            return nativeProveTransactionLocal(unprovenTxHex, keysDir)
        }

        @JvmStatic
        private external fun nativeProveTransactionLocal(
            unprovenTxHex: String,
            keysDir: String,
        ): String?
    }
}
