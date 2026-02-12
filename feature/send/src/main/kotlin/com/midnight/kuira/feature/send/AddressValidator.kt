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
 * **Midnight Address Format:**
 * ```
 * mn_addr_<network>1<data><checksum>
 * ```
 *
 * **Examples:**
 * - Valid: `mn_addr_preview1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdansq8yfx3u`
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
    private val VALID_PREFIXES = setOf(
        "mn_addr_preprod",
        "mn_addr_preview",
        "mn_addr_undeployed",
        "mn_addr_testnet",
        "mn_addr"
    )

    /**
     * Expected address data length (32 bytes for BIP-340 public key).
     */
    private const val EXPECTED_DATA_LENGTH = 32

    /**
     * Validate a Midnight address.
     *
     * **Checks:**
     * 1. Not blank
     * 2. Valid Bech32m format (checksum)
     * 3. Valid prefix (mn_addr_*)
     * 4. Correct data length (32 bytes)
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
                "Invalid address format. Must start with a valid Midnight address prefix (e.g., 'mn_addr_preprod', 'mn_addr_preview')"
            )
        }

        // Check 3: Valid Bech32m format and data length
        return try {
            val (hrp, data) = Bech32m.decode(address)

            // Validate data length (should be 32 bytes for BIP-340 public key)
            if (data.size != EXPECTED_DATA_LENGTH) {
                return ValidationResult.Invalid(
                    "Invalid address data length. Expected $EXPECTED_DATA_LENGTH bytes, got ${data.size}"
                )
            }

            // All checks passed
            // Extract network: "mn_addr" -> "", "mn_addr_preview" -> "preview"
            val network = when {
                hrp == "mn_addr" -> ""  // Mainnet has no suffix
                hrp.startsWith("mn_addr_") -> hrp.removePrefix("mn_addr_")
                else -> ""  // Shouldn't happen if prefix validation worked
            }

            ValidationResult.Valid(
                address = address,
                network = network,
                publicKey = data
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
         * @property publicKey Decoded public key (32 bytes)
         */
        data class Valid(
            val address: String,
            val network: String,
            val publicKey: ByteArray
        ) : ValidationResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Valid) return false
                if (address != other.address) return false
                if (network != other.network) return false
                if (!publicKey.contentEquals(other.publicKey)) return false
                return true
            }

            override fun hashCode(): Int {
                var result = address.hashCode()
                result = 31 * result + network.hashCode()
                result = 31 * result + publicKey.contentHashCode()
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
