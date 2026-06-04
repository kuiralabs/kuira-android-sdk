package com.midnight.kuira.sdk

/**
 * JNI bridge to the Rust `balance_proven_transaction` FFI function.
 *
 * Balances a proven (but unsealed) transaction by adding dust fee payment,
 * proving the dust transaction locally, merging, and sealing.
 *
 * This replaces the remote `DAppConnectorClient.balanceTransaction()` call,
 * enabling standalone dApp operation without `mn serve`.
 */
internal object TransactionBalancerNative {

    init {
        System.loadLibrary("kuira_crypto_ffi")
    }

    /**
     * Balance a proven transaction with dust fees.
     *
     * @param provenTxHex Tagged-SCALE hex of the proven transaction (from local prover)
     * @param dustStatePtr Native pointer to DustLocalState (from DustRepository)
     * @param seed 32-byte dust seed (derived from HD wallet)
     * @param ledgerParamsHex Tagged-SCALE hex of ledger parameters (from indexer)
     * @param currentTimeMs Current time in milliseconds
     * @param keysDir Path to cached proving keys directory
     * @param networkId Network ID string (e.g., "undeployed", "preview", "preprod")
     * @param excludeNullifiers Comma-separated lowercase-hex dust nullifiers to skip
     *   during UTXO selection — UTXOs the wallet already spent but the indexer's
     *   event stream hasn't reflected. Empty string skips nothing. Re-selecting an
     *   already-spent UTXO makes the node reject the tx with error 115.
     * @return `<balanced+sealed tagged-SCALE hex>;<spent nullifier hex>,<...>` (the
     *   nullifier list may be empty), or null on error. The caller records the spent
     *   nullifiers durably and passes them back via [excludeNullifiers] on
     *   subsequent balances. Parse with [BalanceEnvelope.parse].
     */
    external fun nativeBalanceProvenTransaction(
        provenTxHex: String,
        dustStatePtr: Long,
        seed: ByteArray,
        ledgerParamsHex: String,
        currentTimeMs: Long,
        keysDir: String,
        networkId: String,
        excludeNullifiers: String,
    ): String?
}
