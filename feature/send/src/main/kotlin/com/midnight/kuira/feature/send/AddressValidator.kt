package com.midnight.kuira.feature.send

import com.midnight.kuira.core.crypto.address.Bech32m

/**
 * Validates Midnight addresses for send transactions.
 *
 * **Purpose:**
 * - Wrap Bech32m validation with user-friendly error messages
 * - Validate address format (prefix, length, checksum)
 * - Prevent sending to invalid addresses
 *
 * **Midnight Address Formats:**
 * ```
 * Unshielded: mn_addr_<network>1<32-byte pubkey><checksum>
 * Shielded:   mn_shield-addr_<network>1<64-byte coin_pk+enc_pk><checksum>
 * ```
 *
 * **Examples:**
 * - Valid unshielded: `mn_addr_preview1qe8qj25qkva7...`
 * - Valid shielded: `mn_shield-addr_undeployed1jsy2ala7...`
 * - Invalid: `invalid_address` (wrong prefix)
 * - Invalid: `mn_addr_preview1abcd` (invalid checksum)
 *
 * **Usage:**
 * ```kotlin
 * val result = AddressValidator.validate(recipientAddress)
 * when (result) {
 *     is ValidationResult.Valid -> proceedWithTransaction()
 *     is ValidationResult.Invalid -> showError(result.reason)
 * }
 * ```
 *
 * **Why not in crypto module:**
 * - This is UI-specific validation (user-friendly messages)
 * - Bech32m in crypto module is low-level (throws exceptions)
 * - Feature module can import crypto module, not vice versa
 */
object AddressValidator {

    /**
     * Valid Midnight address prefixes.
     *
     * **Networks:**
     * - `mn_addr_preprod` - Pre-production testnet
     * - `mn_addr_preview` - Preview testnet
     * - `mn_addr_undeployed` - Local development (undeployed)
     * - `mn_addr_testnet` - Devnet (legacy)
     * - `mn_addr` - Mainnet (future)
     */
    private val VALID_UNSHIELDED_PREFIXES = setOf(
        "mn_addr_preprod",
        "mn_addr_preview",
        "mn_addr_undeployed",
        "mn_addr_testnet",
        "mn_addr"
    )

    private val VALID_SHIELDED_PREFIXES = setOf(
        "mn_shield-addr_preprod",
        "mn_shield-addr_preview",
        "mn_shield-addr_undeployed",
        "mn_shield-addr_testnet",
        "mn_shield-addr"
    )

    private val VALID_PREFIXES = VALID_UNSHIELDED_PREFIXES + VALID_SHIELDED_PREFIXES

    /** 32 bytes for unshielded (BIP-340 public key) */
    private const val UNSHIELDED_DATA_LENGTH = 32

    /** 64 bytes for shielded (coin_pk 32 + enc_pk 32) */
    private const val SHIELDED_DATA_LENGTH = 64

    /**
     * Validate a Midnight address.
     *
     * **Checks:**
     * 1. Not blank
     * 2. Valid Bech32m format (checksum)
     * 3. Valid prefix (mn_addr_*)
     * 4. Correct data length (32 bytes unshielded, 64 bytes shielded)
     *
     * @param address Address to validate
     * @return ValidationResult.Valid or ValidationResult.Invalid with reason
     */
    fun validate(address: String): ValidationResult {
        // Check 1: Not blank
        if (address.isBlank()) {
            return ValidationResult.Invalid("Address cannot be empty")
        }

        // Check 2: Valid prefix
        val hasValidPrefix = VALID_PREFIXES.any { address.startsWith(it) }
        if (!hasValidPrefix) {
            return ValidationResult.Invalid(
                "Invalid address format. Must start with a valid Midnight prefix (e.g., 'mn_addr_preview' or 'mn_shield-addr_preview')"
            )
        }

        // Check 3: Valid Bech32m format and data length
        return try {
            val (hrp, data) = Bech32m.decode(address)

            val isShielded = VALID_SHIELDED_PREFIXES.any { hrp == it }

            // Validate data length based on address type
            val expectedLength = if (isShielded) SHIELDED_DATA_LENGTH else UNSHIELDED_DATA_LENGTH
            if (data.size != expectedLength) {
                return ValidationResult.Invalid(
                    "Invalid address data length. Expected $expectedLength bytes, got ${data.size}"
                )
            }

            // Extract network from HRP
            val network = when {
                hrp == "mn_addr" || hrp == "mn_shield-addr" -> ""
                hrp.startsWith("mn_shield-addr_") -> hrp.removePrefix("mn_shield-addr_")
                hrp.startsWith("mn_addr_") -> hrp.removePrefix("mn_addr_")
                else -> ""
            }

            ValidationResult.Valid(
                address = address,
                network = network,
                publicKey = data,
                isShielded = isShielded,
            )
        } catch (e: IllegalArgumentException) {
            // Bech32m.decode() throws IllegalArgumentException on invalid checksum
            ValidationResult.Invalid("Invalid address checksum: ${e.message}")
        } catch (e: Exception) {
            // Unexpected error
            ValidationResult.Invalid("Invalid address format: ${e.message}")
        }
    }

    /**
     * Result of address validation.
     */
    sealed class ValidationResult {
        /**
         * Address is valid.
         *
         * @property address The validated address
         * @property network Network ID (e.g., "preview", "testnet")
         * @property publicKey Decoded payload: 32 bytes (unshielded pubkey) or 64 bytes (shielded coin_pk + enc_pk)
         */
        data class Valid(
            val address: String,
            val network: String,
            val publicKey: ByteArray,
            val isShielded: Boolean = false,
        ) : ValidationResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Valid) return false
                if (address != other.address) return false
                if (network != other.network) return false
                if (!publicKey.contentEquals(other.publicKey)) return false
                if (isShielded != other.isShielded) return false
                return true
            }

            override fun hashCode(): Int {
                var result = address.hashCode()
                result = 31 * result + network.hashCode()
                result = 31 * result + publicKey.contentHashCode()
                result = 31 * result + isShielded.hashCode()
                return result
            }
        }

        /**
         * Address is invalid.
         *
         * @property reason User-friendly error message
         */
        data class Invalid(val reason: String) : ValidationResult()
    }
}
