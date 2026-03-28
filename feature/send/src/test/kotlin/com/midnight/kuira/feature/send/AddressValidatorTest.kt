package com.midnight.kuira.feature.send

import com.midnight.kuira.core.crypto.address.Bech32m
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AddressValidator.
 *
 * Tests address validation logic for Midnight addresses.
 */
class AddressValidatorTest {

    // ========================================================================
    // Valid Address Tests
    // ========================================================================

    @Test
    fun `valid preview address passes validation`() {
        // Generate valid Bech32m address using our implementation
        val publicKey = ByteArray(32) { (it * 3).toByte() }  // Deterministic test key
        val address = Bech32m.encode("mn_addr_preview", publicKey)

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Valid)
        val valid = result as AddressValidator.ValidationResult.Valid
        assertEquals(address, valid.address)
        assertEquals("preview", valid.network)
        assertEquals(32, valid.publicKey.size)
        assertTrue(valid.publicKey.contentEquals(publicKey))
    }

    @Test
    fun `valid testnet address passes validation`() {
        // Generate valid Bech32m address using our implementation
        val publicKey = ByteArray(32) { (it * 5).toByte() }  // Deterministic test key
        val address = Bech32m.encode("mn_addr_testnet", publicKey)

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Valid)
        val valid = result as AddressValidator.ValidationResult.Valid
        assertEquals("testnet", valid.network)
        assertTrue(valid.publicKey.contentEquals(publicKey))
    }

    @Test
    fun `valid mainnet address passes validation`() {
        // Generate valid Bech32m address using our implementation
        val publicKey = ByteArray(32) { (it * 7).toByte() }  // Deterministic test key
        val address = Bech32m.encode("mn_addr", publicKey)

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Valid)
        val valid = result as AddressValidator.ValidationResult.Valid
        assertEquals("", valid.network)  // Mainnet has empty network suffix
        assertTrue(valid.publicKey.contentEquals(publicKey))
    }

    // ========================================================================
    // Invalid Address Tests
    // ========================================================================

    @Test
    fun `blank address returns error`() {
        val result = AddressValidator.validate("")

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
        val invalid = result as AddressValidator.ValidationResult.Invalid
        assertEquals("Address cannot be empty", invalid.reason)
    }

    @Test
    fun `whitespace-only address returns error`() {
        val result = AddressValidator.validate("   ")

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
        val invalid = result as AddressValidator.ValidationResult.Invalid
        assertEquals("Address cannot be empty", invalid.reason)
    }

    @Test
    fun `invalid prefix returns error`() {
        val address = "invalid_prefix1qe8qj25qkva7ug6qf3rvl3y0a366ydt2nvq30rwk5ckznavfdansq8yfx3u"

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
        val invalid = result as AddressValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("Invalid address format"))
    }

    @Test
    fun `bitcoin address returns error`() {
        val address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
    }

    @Test
    fun `ethereum address returns error`() {
        val address = "0x742d35Cc6634C0532925a3b844Bc454e4438f44e"

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
    }

    @Test
    fun `invalid checksum returns error`() {
        // Generate valid address, then corrupt the last character to break checksum
        val publicKey = ByteArray(32) { it.toByte() }
        val validAddress = Bech32m.encode("mn_addr_preview", publicKey)

        // Corrupt last character
        val corruptedAddress = validAddress.dropLast(1) + "x"

        val result = AddressValidator.validate(corruptedAddress)

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
        val invalid = result as AddressValidator.ValidationResult.Invalid
        assertTrue(invalid.reason.contains("checksum") || invalid.reason.contains("Invalid"))
    }

    @Test
    fun `too short address returns error`() {
        val address = "mn_addr_preview1short"

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
    }

    @Test
    fun `random string returns error`() {
        val address = "this is not an address"

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
    }

    // ========================================================================
    // Shielded Address Tests (Step 8/9)
    // ========================================================================

    @Test
    fun `valid shielded address passes validation`() {
        // Shielded address = 64 bytes (coin_pk 32 + enc_pk 32)
        val coinPk = ByteArray(32) { (it * 3).toByte() }
        val encPk = ByteArray(32) { (it * 7).toByte() }
        val payload = coinPk + encPk
        val address = Bech32m.encode("mn_shield-addr_undeployed", payload)

        val result = AddressValidator.validate(address)

        assertTrue("Shielded address should be valid", result is AddressValidator.ValidationResult.Valid)
        val valid = result as AddressValidator.ValidationResult.Valid
        assertTrue(valid.isShielded)
        assertEquals(64, valid.publicKey.size)
        assertEquals("undeployed", valid.network)
    }

    @Test
    fun `shielded address with wrong data length fails`() {
        // Only 32 bytes instead of 64
        val payload = ByteArray(32) { it.toByte() }
        val address = Bech32m.encode("mn_shield-addr_preview", payload)

        val result = AddressValidator.validate(address)

        assertTrue(result is AddressValidator.ValidationResult.Invalid)
    }

    @Test
    fun `can distinguish shielded from unshielded`() {
        val unshieldedKey = ByteArray(32) { it.toByte() }
        val unshieldedAddr = Bech32m.encode("mn_addr_preview", unshieldedKey)

        val coinPk = ByteArray(32) { (it * 3).toByte() }
        val encPk = ByteArray(32) { (it * 7).toByte() }
        val shieldedAddr = Bech32m.encode("mn_shield-addr_preview", coinPk + encPk)

        val unshieldedResult = AddressValidator.validate(unshieldedAddr) as AddressValidator.ValidationResult.Valid
        val shieldedResult = AddressValidator.validate(shieldedAddr) as AddressValidator.ValidationResult.Valid

        assertFalse("Unshielded should not be shielded", unshieldedResult.isShielded)
        assertTrue("Shielded should be shielded", shieldedResult.isShielded)
    }

    // ========================================================================
    // ValidationResult Tests
    // ========================================================================

    @Test
    fun `ValidationResult Valid equals works correctly`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val result1 = AddressValidator.ValidationResult.Valid("addr1", "testnet", publicKey)
        val result2 = AddressValidator.ValidationResult.Valid("addr1", "testnet", publicKey.copyOf())

        assertEquals(result1, result2)
    }

    @Test
    fun `ValidationResult Valid hashCode works correctly`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val result1 = AddressValidator.ValidationResult.Valid("addr1", "testnet", publicKey)
        val result2 = AddressValidator.ValidationResult.Valid("addr1", "testnet", publicKey.copyOf())

        assertEquals(result1.hashCode(), result2.hashCode())
    }

    @Test
    fun `ValidationResult Valid different addresses are not equal`() {
        val publicKey = ByteArray(32) { it.toByte() }
        val result1 = AddressValidator.ValidationResult.Valid("addr1", "testnet", publicKey)
        val result2 = AddressValidator.ValidationResult.Valid("addr2", "testnet", publicKey.copyOf())

        assertNotEquals(result1, result2)
    }
}
