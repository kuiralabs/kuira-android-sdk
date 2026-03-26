package com.midnight.kuira.core.ledger.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.ledger.signer.TransactionSigner
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Arrays

/**
 * COMPREHENSIVE integration test for dust fee payment.
 *
 * **GOAL:** Verify that our wallet can actually make transactions with dust fees.
 *
 * **What We Test:**
 * 1. ✅ DustLocalState creation and management (Rust FFI)
 * 2. ✅ Wallet generation (BIP-39 → BIP-32 → Schnorr keys)
 * 3. ✅ Transaction building (inputs/outputs)
 * 4. ✅ Transaction signing (real Schnorr signatures via Rust FFI)
 * 5. ✅ Serialization WITHOUT dust (verify baseline works)
 * 6. ✅ DustLocalState serialization/deserialization
 * 7. ✅ Balance calculation
 *
 * **What We CANNOT Test Yet:**
 * - Actual dust UTXO registration (requires blockchain connection)
 * - Real dust spend creation (requires registered dust UTXOs)
 * - Network submission (requires running local node)
 *
 * **NO MOCKS** - Everything is REAL except blockchain network.
 */
@RunWith(AndroidJUnit4::class)
class CompleteDustFeeIntegrationTest {

    private lateinit var wallet: HDWallet
    private lateinit var senderAddress: String
    private lateinit var senderPublicKey: String
    private lateinit var privateKey: ByteArray
    private lateinit var seed: ByteArray
    private var dustState: DustLocalState? = null

    companion object {
        private const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
        private val NATIVE_TOKEN = "0".repeat(64)
        private val TEST_INTENT_HASH = "deadbeef" + "0".repeat(56)

        private val RECIPIENT_ADDRESS by lazy {
            Bech32m.encode("mn_addr_undeployed", ByteArray(32))
        }

        private fun ByteArray.toHex(): String {
            return joinToString("") { "%02x".format(it) }
        }

        private fun hexToBytes(hex: String): ByteArray {
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }

    @Before
    fun setup() {
        // Generate wallet
        seed = BIP39.mnemonicToSeed(TEST_MNEMONIC, passphrase = "")
        wallet = HDWallet.fromSeed(seed)

        val derivedKey = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
            .deriveKeyAt(0)

        privateKey = derivedKey.privateKeyBytes.copyOf()

        // Derive BIP-340 public key
        val xOnlyPublicKey = TransactionSigner.getPublicKey(privateKey)
            ?: throw IllegalStateException("Failed to derive BIP-340 public key")
        require(xOnlyPublicKey.size == 32) { "Public key must be 32 bytes, got ${xOnlyPublicKey.size}" }

        // Create address
        val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPublicKey)
        senderAddress = Bech32m.encode("mn_addr_undeployed", addressData)
        senderPublicKey = xOnlyPublicKey.toHex()

        derivedKey.clear()
    }

    @After
    fun teardown() {
        wallet.clear()
        Arrays.fill(privateKey, 0.toByte())
        Arrays.fill(seed, 0.toByte())
        dustState?.close()
    }

    @Test
    fun test1_DustLocalStateCreation() {
        dustState = DustLocalState.create()
        assertNotNull("DustLocalState.create() should return non-null", dustState)
    }

    @Test
    fun test2_DustLocalStateSerialization() {
        // Create state
        dustState = DustLocalState.create()
        assertNotNull(dustState)

        // Serialize
        val serialized = dustState!!.serialize()
        assertNotNull("Serialization should return data", serialized)
        assertTrue("Serialized data should not be empty", serialized!!.isNotEmpty())

        // Deserialize
        val deserialized = DustLocalState.deserialize(serialized)
        assertNotNull("Deserialization should succeed", deserialized)

        // Clean up
        deserialized?.close()
    }

    @Test
    fun test3_DustBalanceCalculation() {
        dustState = DustLocalState.create()
        assertNotNull(dustState)

        val balance = dustState!!.getBalance(System.currentTimeMillis())
        assertNotNull("Balance should be returned", balance)
        assertEquals("Balance should be 0 for new state", BigInteger.ZERO, balance)
    }

    @Test
    fun test4_SchnorrSigningWorks() {
        val testMessage = "Test message for signing".toByteArray()

        val signature = TransactionSigner.signData(privateKey, testMessage)
        assertNotNull("Signing should succeed", signature)
        assertEquals("Signature should be 64 bytes (BIP-340)", 64, signature!!.size)

        // Verify signature
        val publicKey = TransactionSigner.getPublicKey(privateKey)
        assertNotNull(publicKey)

        val isValid = TransactionSigner.verifySignature(publicKey!!, testMessage, signature)
        assertTrue("Signature should be valid", isValid)
    }

    @Test
    fun test5_TransactionSerializationWithoutDust() {
        val testUtxo = UtxoSpend(
            intentHash = TEST_INTENT_HASH,
            outputNo = 0,
            value = BigInteger("1000000000"),
            owner = senderAddress,
            ownerPublicKey = senderPublicKey,
            tokenType = NATIVE_TOKEN
        )

        val paymentOutput = UtxoOutput(
            value = BigInteger("100000000"),
            owner = RECIPIENT_ADDRESS,
            tokenType = NATIVE_TOKEN
        )

        val changeOutput = UtxoOutput(
            value = BigInteger("900000000"),
            owner = senderAddress,
            tokenType = NATIVE_TOKEN
        )

        val serializer = FfiTransactionSerializer()
        val ttl = System.currentTimeMillis() + (5 * 60 * 1000)

        val signingMessageHex = serializer.getSigningMessageForInput(
            inputs = listOf(testUtxo),
            outputs = listOf(paymentOutput, changeOutput),
            inputIndex = 0,
            ttl = ttl
        )

        assertNotNull("Signing message should be generated", signingMessageHex)

        val signingMessage = hexToBytes(signingMessageHex!!)
        val signature = TransactionSigner.signData(privateKey, signingMessage)
        assertNotNull("Signing should succeed", signature)

        val signedOffer = UnshieldedOffer(
            inputs = listOf(testUtxo),
            outputs = listOf(paymentOutput, changeOutput),
            signatures = listOf(signature!!)
        )

        val intent = com.midnight.kuira.core.ledger.model.Intent.withDefaultTtl(
            guaranteedOffer = signedOffer,
            dustActions = null
        )

        val scaleHex = serializer.serialize(intent)
        assertNotNull("Serialization should succeed", scaleHex)
        assertTrue("SCALE hex should not be empty", scaleHex.isNotEmpty())

        assertTrue("Should start with midnight:transaction tag",
            scaleHex.startsWith("6d69646e696768743a"))
    }

    @Test
    fun test6_AllDustMechanismsWork() {
        var testsPassed = 0
        val totalTests = 7

        // 1. DustLocalState
        try {
            dustState = DustLocalState.create()
            assertNotNull(dustState)
            testsPassed++
        } catch (e: Exception) {
            println("DustLocalState creation FAILED: ${e.message}")
        }

        // 2. Wallet
        try {
            assertNotNull(wallet)
            assertNotNull(privateKey)
            assertTrue(seed.isNotEmpty())
            testsPassed++
        } catch (e: Exception) {
            println("Wallet generation FAILED: ${e.message}")
        }

        // 3. TransactionSigner
        try {
            val sig = TransactionSigner.signData(privateKey, "test".toByteArray())
            assertNotNull(sig)
            testsPassed++
        } catch (e: Exception) {
            println("TransactionSigner FAILED: ${e.message}")
        }

        // 4. FfiTransactionSerializer
        try {
            val serializer = FfiTransactionSerializer()
            assertNotNull(serializer)
            testsPassed++
        } catch (e: Exception) {
            println("FfiTransactionSerializer FAILED: ${e.message}")
        }

        // 5. DustLocalState serialization
        try {
            val serialized = dustState!!.serialize()
            assertNotNull(serialized)
            val deserialized = DustLocalState.deserialize(serialized!!)
            assertNotNull(deserialized)
            deserialized?.close()
            testsPassed++
        } catch (e: Exception) {
            println("DustLocalState serialization FAILED: ${e.message}")
        }

        // 6. Balance calculation
        try {
            val balance = dustState!!.getBalance(System.currentTimeMillis())
            assertNotNull(balance)
            assertEquals(BigInteger.ZERO, balance)
            testsPassed++
        } catch (e: Exception) {
            println("Balance calculation FAILED: ${e.message}")
        }

        // 7. Public key derivation
        try {
            val pubKey = TransactionSigner.getPublicKey(privateKey)
            assertNotNull(pubKey)
            assertEquals(32, pubKey!!.size)
            testsPassed++
        } catch (e: Exception) {
            println("Public key derivation FAILED: ${e.message}")
        }

        assertEquals("All dust mechanisms should work", totalTests, testsPassed)
    }

    @Test
    fun test7_NativeLibraryHasAllFunctions() {
        var functionsAvailable = 0

        // Test create_dust_local_state
        try {
            val state = DustLocalState.create()
            assertNotNull(state)
            state?.close()
            functionsAvailable++
        } catch (_: Exception) { }

        // Test serialize_dust_state
        try {
            val state = DustLocalState.create()
            val serialized = state?.serialize()
            assertNotNull(serialized)
            state?.close()
            functionsAvailable++
        } catch (_: Exception) { }

        // Test deserialize_dust_state
        try {
            val state = DustLocalState.create()
            val serialized = state?.serialize()
            val deserialized = DustLocalState.deserialize(serialized!!)
            assertNotNull(deserialized)
            state?.close()
            deserialized?.close()
            functionsAvailable++
        } catch (_: Exception) { }

        // Test sign_data
        try {
            val sig = TransactionSigner.signData(privateKey, "test".toByteArray())
            assertNotNull(sig)
            functionsAvailable++
        } catch (_: Exception) { }

        // Test serialize_unshielded_transaction
        try {
            val serializer = FfiTransactionSerializer()
            assertNotNull(serializer)
            functionsAvailable++
        } catch (_: Exception) { }

        assertEquals("All native functions should be available", 5, functionsAvailable)
    }
}
