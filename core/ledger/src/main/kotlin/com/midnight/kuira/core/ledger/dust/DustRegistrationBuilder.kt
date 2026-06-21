// This file is part of Kuira Wallet.
// Copyright (C) 2025 Kuira Wallet
// SPDX-License-Identifier: Apache-2.0

package com.midnight.kuira.core.ledger.dust

/**
 * Builds dust registration transactions.
 *
 * A dust registration associates a NIGHT address with a DustPublicKey so that
 * dust (transaction fee tokens) can be generated from held NIGHT tokens.
 *
 * The transaction includes a guaranteed UnshieldedOffer that consolidates the
 * user's NIGHT UTXOs (required by the node for validation).
 *
 * The resulting SCALE hex is a fully signed transaction ready for proof server
 * submission. The proof server returns a proven transaction which must then be
 * sealed and submitted to the node.
 *
 * **Workflow:**
 * ```
 * DustRegistrationBuilder.build(...)
 *   → SCALE hex
 *   → ProofServerClient.prove(hex)
 *   → seal_proven_transaction(proven_hex)
 *   → NodeRpcClient.submit(sealed_hex)
 * ```
 *
 * @see `/midnight-ledger/ledger/src/dust.rs:651` (DustRegistration struct)
 * @see DustSpendCreator for spending existing dust
 */
object DustRegistrationBuilder {

    init {
        System.loadLibrary("kuira_crypto_ffi")
    }

    /**
     * Outcome of building a dust registration transaction.
     *
     * A registration SELF-PAYS its own fee from the selected NIGHT coin's "generationless"
     * dust, which starts at ~0 on a brand-new coin and grows as the coin ages. The native
     * builder is given the live ledger params and refuses to emit a tx the coin can't afford
     * (which the node would reject as BalanceCheckOverspend / error 138), surfacing
     * [NotMatured] instead — so the caller can wait for the coin to age and retry rather than
     * submitting a doomed transaction.
     */
    sealed interface Result {
        /** Signed SCALE [hex] ready for proving/submission. */
        data class Built(val hex: String) : Result

        /** The selected coin can't yet self-pay; [shortfallSpecks] is how much dust it still needs. */
        data class NotMatured(val shortfallSpecks: java.math.BigInteger) : Result

        /** Build failed (bad inputs, native error). */
        data object Failed : Result
    }

    /**
     * Builds a complete dust registration transaction.
     *
     * Creates and signs a transaction that registers the given NIGHT address
     * for dust generation with the specified DustPublicKey. Includes the
     * user's NIGHT UTXOs as a guaranteed UnshieldedOffer.
     *
     * @param nightPrivateKey 32-byte NIGHT address signing key
     * @param dustPublicKeyHex Hex-encoded DustPublicKey (from DustKeyDeriver)
     * @param utxosJson JSON array of NIGHT UTXOs: [{"value":"...","intent_hash":"...","output_no":N}]
     * @param ttlMillis Transaction time-to-live in milliseconds since epoch
     * @param networkId Target network id (HRP-less), e.g. "undeployed"/"preprod"
     * @param currentTimeMillis Current time in milliseconds since epoch
     * @param ledgerParamsHex Hex-encoded SCALE ledger parameters from the indexer
     *   (`getCurrentBlockWithParams().ledgerParameters`). When supplied, the native builder
     *   gates on coin maturity and returns [Result.NotMatured] for a too-fresh coin. Pass
     *   null only where no params are available (the gate is then skipped).
     * @return a [Result]: [Result.Built] on success, [Result.NotMatured] when the coin is too
     *   fresh to self-pay, [Result.Failed] on any other error.
     *
     * @throws IllegalArgumentException if nightPrivateKey is not 32 bytes
     */
    fun build(
        nightPrivateKey: ByteArray,
        dustPublicKeyHex: String,
        utxosJson: String,
        ttlMillis: Long,
        networkId: String,
        currentTimeMillis: Long = System.currentTimeMillis(),
        ledgerParamsHex: String? = null,
    ): Result {
        require(nightPrivateKey.size == 32) { "Night private key must be exactly 32 bytes" }
        require(dustPublicKeyHex.isNotBlank()) { "Dust public key hex must not be blank" }
        require(utxosJson.isNotBlank()) { "UTXOs JSON must not be blank" }
        require(ttlMillis > 0) { "TTL must be positive" }
        require(networkId.isNotBlank()) { "networkId must not be blank" }

        val raw = nativeBuildDustRegistrationTransaction(
            nightPrivateKey = nightPrivateKey,
            dustPublicKeyHex = dustPublicKeyHex,
            allowFeePayment = "0",  // Ignored by Rust — calculated from UTXO values and ctime
            ttlMillis = ttlMillis,
            currentTimeMillis = currentTimeMillis,
            utxosJson = utxosJson,
            networkId = networkId,
            paramsHex = ledgerParamsHex,
        ) ?: return Result.Failed

        if (raw.startsWith(DUST_NOT_MATURED_PREFIX)) {
            val shortfall = raw.removePrefix(DUST_NOT_MATURED_PREFIX)
                .toBigIntegerOrNull() ?: java.math.BigInteger.ZERO
            return Result.NotMatured(shortfall)
        }
        return Result.Built(raw)
    }

    /**
     * Typed "coin not matured" sentinel the native builder returns (instead of null) when the
     * selected NIGHT coin's generationless dust can't yet cover the registration fee. Kept in
     * sync with `DUST_NOT_MATURED_PREFIX` in `kuira-crypto-ffi/src/serialize.rs`.
     */
    private const val DUST_NOT_MATURED_PREFIX = "DUST_NOT_MATURED:"

    /**
     * JNI bridge to Rust FFI `build_dust_registration_transaction()`.
     *
     * @param nightPrivateKey 32-byte NIGHT signing key
     * @param dustPublicKeyHex Hex-encoded DustPublicKey
     * @param allowFeePayment Fee amount as decimal string (u128 Specks)
     * @param ttlMillis TTL in milliseconds since epoch
     * @param currentTimeMillis Current time in milliseconds since epoch
     * @param utxosJson JSON array of NIGHT UTXOs
     * @return Hex-encoded SCALE transaction, or null on error
     */
    private external fun nativeBuildDustRegistrationTransaction(
        nightPrivateKey: ByteArray,
        dustPublicKeyHex: String,
        allowFeePayment: String,
        ttlMillis: Long,
        currentTimeMillis: Long,
        utxosJson: String,
        networkId: String,
        paramsHex: String?,
    ): String?
}
