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
     * @param currentTimeMillis Current time in milliseconds since epoch
     * @return Hex-encoded SCALE transaction, or null on error
     *
     * @throws IllegalArgumentException if nightPrivateKey is not 32 bytes
     */
    fun build(
        nightPrivateKey: ByteArray,
        dustPublicKeyHex: String,
        utxosJson: String,
        ttlMillis: Long,
        currentTimeMillis: Long = System.currentTimeMillis(),
    ): String? {
        require(nightPrivateKey.size == 32) { "Night private key must be exactly 32 bytes" }
        require(dustPublicKeyHex.isNotBlank()) { "Dust public key hex must not be blank" }
        require(utxosJson.isNotBlank()) { "UTXOs JSON must not be blank" }
        require(ttlMillis > 0) { "TTL must be positive" }

        return nativeBuildDustRegistrationTransaction(
            nightPrivateKey = nightPrivateKey,
            dustPublicKeyHex = dustPublicKeyHex,
            allowFeePayment = "0",  // Ignored by Rust — calculated from UTXO values and ctime
            ttlMillis = ttlMillis,
            currentTimeMillis = currentTimeMillis,
            utxosJson = utxosJson,
        )
    }

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
    ): String?
}
