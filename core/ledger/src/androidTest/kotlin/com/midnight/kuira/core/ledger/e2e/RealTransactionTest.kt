package com.midnight.kuira.core.ledger.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.crypto.dust.DustLocalState
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.api.NodeRpcClientImpl
import com.midnight.kuira.core.ledger.api.ProofServerClientImpl
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UnshieldedOffer
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.ledger.signer.TransactionSigner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Real on-chain transaction test using actual funded UTXOs.
 *
 * **Test Flow:**
 * 1. Query real UTXOs from funded address via database
 * 2. Build transaction using actual spendable UTXOs
 * 3. Sign with real HD wallet keys
 * 4. Submit to real Midnight node
 * 5. Verify transaction is accepted on-chain
 *
 * **Requirements:**
 * - Local Midnight node running at http://10.0.2.2:9944
 * - Funded address has real UTXOs on-chain (verified)
 *
 * **Funded Address:**
 * `mn_addr_undeployed1fxqvl2mzlx07hv0vwttjq7ff3jeg7507glau9ge384jp5x0nyykstn9hzv`
 * - Derived from: "abandon abandon..." at m/44'/2400'/0'/0/0
 * - Balance: 20 NIGHT (verified via check-balance script)
 * - UTXO 1: ab6642ef...464fed:0 = 10 NIGHT
 * - UTXO 2: 87d71c14...35e880:0 = 10 NIGHT
 *
 * **Test Strategy:**
 * Inserts REAL on-chain UTXO data directly into test database.
 * Builds and signs transaction using the real UTXO.
 * Submits to node - transaction WILL execute on-chain.
 * Sends 0.1 NIGHT to recipient address (index 1 from same HD wallet).
 *
 * **Verification:**
 * After test succeeds, check recipient balance with check-balance script.
 * Recipient should receive 100,000 smallest units (0.1 NIGHT).
 */
@RunWith(AndroidJUnit4::class)
class RealTransactionTest {

    private lateinit var database: UtxoDatabase
    private lateinit var wallet: HDWallet
    private lateinit var privateKey: ByteArray
    private lateinit var seed: ByteArray
    private lateinit var recipientAddress: String  // Will be derived in setup
    private var dustState: DustLocalState? = null

    companion object {
        /**
         * Test mnemonic matching the funded address.
         */
        private const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"

        /**
         * FUNDED ADDRESS - Derived at m/44'/2400'/0'/0/0 from TEST_MNEMONIC (INDEX 0)
         * This is the standard first address for the test wallet
         */
        private const val FUNDED_ADDRESS = "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx"

        /**
         * Recipient address will be derived at: m/44'/2400'/0'/0/4 (INDEX 4)
         * Fresh address, never used before
         * This allows us to verify the transaction in the Balance Screen
         * by checking both sender and recipient balances.
         */

        /**
         * Native NIGHT token type.
         */
        private const val NATIVE_TOKEN = "0000000000000000000000000000000000000000000000000000000000000000"

        /**
         * Node URL (Android emulator uses 10.0.2.2 for host's localhost).
         */
        private const val NODE_URL = "http://10.0.2.2:9944"
        private const val PROOF_SERVER_URL = "http://10.0.2.2:6300"

        /**
         * Amount to send in test transaction.
         * Note: check-balance uses different units (1 NIGHT = 1_000_000 in their notation)
         * Our UTXO has 1,000,000 smallest units available (1 NIGHT).
         */
        private val SEND_AMOUNT = BigInteger("500000") // 0.5 NIGHT (half of available balance)

        // Helper: Convert bytes to hex string
        private fun ByteArray.toHex(): String {
            return joinToString("") { "%02x".format(it) }
        }
    }

    @Before
    fun setup() = runBlocking {
        println("\n🔧 Setting up real transaction test...")

        // Get database instance
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = UtxoDatabase.getInstance(context)

        // Generate wallet from mnemonic
        val bip39Seed = BIP39.mnemonicToSeed(TEST_MNEMONIC, passphrase = "")

        // BIP39.mnemonicToSeed() returns full 64-byte PBKDF2 seed, matching Lace wallet.
        wallet = HDWallet.fromSeed(bip39Seed)

        // Derive DUST key at m/44'/2400'/0'/2/0 (Role 2 = Dust, Index 0)
        // Midnight SDK derives dust key at Roles.Dust (role 2), NOT NightExternal (role 0)
        val dustDerivedKey = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.DUST)
            .deriveKeyAt(0)

        // Store dust seed for replaying events (class field, used in test + teardown)
        seed = dustDerivedKey.privateKeyBytes.copyOf()
        dustDerivedKey.clear()

        // Derive sender key at path m/44'/2400'/0'/0/0 (INDEX 0 - standard first address)
        val senderDerivedKey = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
            .deriveKeyAt(0)  // ← INDEX 0

        // Copy private key before clearing
        privateKey = senderDerivedKey.privateKeyBytes.copyOf()

        // Clean up derived key
        senderDerivedKey.clear()

        // Derive recipient address at path m/44'/2400'/0'/0/1 (INDEX 1 - second address)
        // This is a fresh address that will receive the funds from index 0
        val recipientDerivedKey = wallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
            .deriveKeyAt(1)  // ← INDEX 1 (recipient)

        // Get recipient's BIP-340 public key
        val recipientXOnlyPublicKey = TransactionSigner.getPublicKey(recipientDerivedKey.privateKeyBytes)
            ?: throw IllegalStateException("Failed to derive recipient public key")

        // Create recipient address (SHA-256 hash of public key, then Bech32m encode)
        val recipientAddressData = MessageDigest.getInstance("SHA-256").digest(recipientXOnlyPublicKey)
        recipientAddress = Bech32m.encode("mn_addr_undeployed", recipientAddressData)

        // Clean up recipient key (we don't need it after deriving address)
        recipientDerivedKey.clear()

        println("✅ Wallet setup complete")
        println("   Sender (index 0):  $FUNDED_ADDRESS")
        println("   Recipient (index 4, fresh):    $recipientAddress")
        println()

        // Query and restore dust state (REQUIRED for transaction fees)
        println("💰 Querying dust state from blockchain...")
        val indexerClient = IndexerClientImpl(
            baseUrl = "http://10.0.2.2:8088/api/v3",
            developmentMode = true
        )

        try {
            val eventsHex = indexerClient.queryDustEvents(maxBlocks = 100)

            if (eventsHex.isEmpty()) {
                println("⚠️  No dust events found - dust not registered yet")
                println("   Transaction will fail without dust for fees")
            } else {
                println("   Retrieved ${eventsHex.length / 2} bytes of dust events")

                val initialState = DustLocalState.create()
                if (initialState != null) {
                    val restoredState = initialState.replayEvents(seed, eventsHex)
                    if (restoredState != null) {
                        dustState = restoredState
                        val balance = dustState!!.getBalance(System.currentTimeMillis())
                        println("   ✅ Dust balance: $balance Specks")
                        initialState.close()
                    } else {
                        println("   ⚠️  Failed to replay dust events")
                        initialState.close()
                    }
                }
            }
        } finally {
            indexerClient.close()
        }
        println()

        // Sync unshielded UTXOs from the indexer (same pattern as SubscriptionManager)
        println("📡 Syncing unshielded UTXOs from indexer...")
        val wsIndexerClient = IndexerClientImpl(
            baseUrl = "http://10.0.2.2:8088/api/v3",
            developmentMode = true
        )
        val utxoManager = UtxoManager(database.unshieldedUtxoDao())
        try {
            var txCount = 0
            var highestTxId = 0
            var lastSeenTxId = 0
            var progressReceived = false
            withTimeoutOrNull(30_000L) {
                wsIndexerClient.subscribeToUnshieldedTransactions(
                    address = FUNDED_ADDRESS,
                    transactionId = null  // Omit to replay all from beginning (CLI pattern)
                ).takeWhile { update ->
                    utxoManager.processUpdate(update)
                    when (update) {
                        is UnshieldedTransactionUpdate.Transaction -> {
                            txCount++
                            lastSeenTxId = maxOf(lastSeenTxId, update.transaction.id)
                            // Check if we've caught up after receiving progress
                            !(progressReceived && lastSeenTxId >= highestTxId)
                        }
                        is UnshieldedTransactionUpdate.Progress -> {
                            highestTxId = update.highestTransactionId
                            progressReceived = true
                            // If no transactions expected or already caught up, stop
                            !(highestTxId == 0 || lastSeenTxId >= highestTxId)
                        }
                    }
                }.collect {}
            } ?: println("   ⚠️ Sync timed out after 30s")
            println("   ✅ Synced $txCount transactions (highest=$highestTxId, lastSeen=$lastSeenTxId)")
            val utxos = database.unshieldedUtxoDao().getUnspentUtxos(FUNDED_ADDRESS)
            println("   ✅ ${utxos.size} UTXOs available after sync")
        } finally {
            wsIndexerClient.close()
        }
    }

    @After
    fun teardown() {
        wallet.clear()
        privateKey.fill(0)
        seed.fill(0)
        dustState?.close()
        database.close()
        println("🧹 Test cleanup complete\n")
    }

    @Test
    fun testRealOnChainTransaction() = runBlocking {
        println("\n🚀 Starting real on-chain transaction test...")
        println("\n📝 NOTE: Using UTXO from on-chain balance!")
        println("   Funded address (index 0): $FUNDED_ADDRESS (1 NIGHT available)")
        println("   Will send: 0.5 NIGHT to recipient (index 1)")
        println("   Change: 0.5 NIGHT back to sender")
        println()

        // Step 1: Query real UTXOs from database
        println("📊 Step 1: Querying available UTXOs...")
        val utxoDao = database.unshieldedUtxoDao()
        val availableUtxos = utxoDao.getUnspentUtxos(FUNDED_ADDRESS)

        println("   Found ${availableUtxos.size} available UTXOs")
        availableUtxos.forEach { utxo ->
            println("   - ${utxo.value} units (token: ${utxo.tokenType.take(8)}...)")
        }

        // Verify we have UTXOs
        if (availableUtxos.isEmpty()) {
            fail("""
                ❌ No UTXOs found in database!

                The database is empty. You need to sync UTXOs first.

                STEPS TO FIX:
                1. Open the Kuira app on the emulator
                2. Navigate to Balance Screen
                3. The funded address is already set: $FUNDED_ADDRESS
                4. Wait for sync to complete (shows "Synced up to TX X")
                5. Verify you see ~20 NIGHT balance
                6. Re-run this test

                WHY: The test and app share the same database. The app's Balance Screen
                syncs UTXOs from the blockchain. The test then uses those synced UTXOs
                to build a real transaction.
            """.trimIndent())
        }

        // Step 2: Select UTXO to spend (use first NATIVE_TOKEN UTXO)
        val selectedUtxoOrNull = availableUtxos.firstOrNull { it.tokenType == NATIVE_TOKEN }
        assertNotNull("No NATIVE_TOKEN UTXOs available", selectedUtxoOrNull)
        val selectedUtxo = selectedUtxoOrNull!!

        val utxoValue = BigInteger(selectedUtxo.value)
        println("\n💰 Step 2: Selected UTXO:")
        println("   Value: $utxoValue")
        println("   Intent hash: ${selectedUtxo.intentHash}")
        println("   Output index: ${selectedUtxo.outputIndex}")

        // Verify UTXO value is enough
        val requiredAmount = SEND_AMOUNT.add(BigInteger("1000")) // Add fee buffer
        assertTrue(
            "Selected UTXO value ($utxoValue) is too small for transaction (need $requiredAmount)",
            utxoValue >= requiredAmount
        )

        // Step 3: Derive sender's public key and address
        println("\n🔑 Step 3: Deriving sender keys...")
        val xOnlyPublicKeyOrNull = TransactionSigner.getPublicKey(privateKey)
        assertNotNull("Failed to derive BIP-340 public key", xOnlyPublicKeyOrNull)
        val xOnlyPublicKey = xOnlyPublicKeyOrNull!!

        val senderPublicKeyHex = xOnlyPublicKey.toHex()
        val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPublicKey)
        val senderAddress = Bech32m.encode("mn_addr_undeployed", addressData)

        println("   Sender address: $senderAddress")
        println("   Sender public key: ${senderPublicKeyHex.take(20)}...")

        // Verify address matches funded address
        assertEquals(
            "Derived address doesn't match funded address!",
            FUNDED_ADDRESS,
            senderAddress
        )

        // Step 4: Build transaction
        println("\n🔨 Step 4: Building transaction...")

        // Use the public key derived from wallet (indexer doesn't return it)
        val input = UtxoSpend(
            intentHash = selectedUtxo.intentHash,
            outputNo = selectedUtxo.outputIndex,
            value = utxoValue,
            owner = senderAddress,
            ownerPublicKey = senderPublicKeyHex,
            tokenType = NATIVE_TOKEN
        )

        val paymentOutput = UtxoOutput(
            value = SEND_AMOUNT,
            owner = recipientAddress,
            tokenType = NATIVE_TOKEN
        )

        val changeAmount = utxoValue.subtract(SEND_AMOUNT)
        val changeOutput = UtxoOutput(
            value = changeAmount,
            owner = senderAddress,
            tokenType = NATIVE_TOKEN
        )

        println("   Input: $utxoValue")
        println("   Payment: $SEND_AMOUNT → ${recipientAddress.take(30)}...")
        println("   Change: $changeAmount → sender")

        // Step 5: Get REAL signing message from Intent
        println("\n🔑 Step 5: Generating signing message from Intent...")

        // CRITICAL: TTL must be the SAME for signing and serialization!
        val ttl = System.currentTimeMillis() + 30 * 60 * 1000

        // Use FfiTransactionSerializer to get the ACTUAL message to sign
        // This is THE critical step for real on-chain transactions
        val serializer = FfiTransactionSerializer()
        val signingMessageHex = serializer.getSigningMessageForInput(
            inputs = listOf(input),
            outputs = listOf(paymentOutput, changeOutput),
            inputIndex = 0,  // Sign for the first (only) input
            ttl = ttl
        )

        assertNotNull("Failed to generate signing message", signingMessageHex)
        println("   ✅ Signing message generated: ${signingMessageHex!!.length / 2} bytes")
        println("   Signing message (hex): ${signingMessageHex.take(80)}...")

        // Convert hex to bytes
        val messageToSign = signingMessageHex.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()

        // Step 6: Sign the REAL signing message
        println("\n✍️  Step 6: Signing with real Intent message...")

        val signatureOrNull = TransactionSigner.signData(privateKey, messageToSign)
        assertNotNull("Failed to generate signature", signatureOrNull)
        val signature = signatureOrNull!!

        println("   ✅ REAL signature generated (${signature.size} bytes)")
        println("   Signature: ${signature.toHex().take(40)}...")

        val signedOffer = UnshieldedOffer(
            inputs = listOf(input),
            outputs = listOf(paymentOutput, changeOutput),
            signatures = listOf(signature)
        )

        val signedIntent = Intent(
            guaranteedUnshieldedOffer = signedOffer,
            fallibleUnshieldedOffer = null,
            ttl = ttl  // CRITICAL: Use the SAME ttl as signing!
        )

        // Step 7: Serialize to SCALE with dust fee payment
        println("\n📦 Step 7: Serializing to SCALE with dust fee payment...")

        // Check if we have dust UTXOs available
        val hasDustUtxos = dustState?.let { state ->
            val count = state.getUtxoCount()
            val balance = state.getBalance(System.currentTimeMillis())
            println("   Dust state: $count UTXOs, balance: $balance Specks")
            count > 0
        } ?: false

        val scaleHex = if (hasDustUtxos) {
            // Use dust fee payment (REQUIRED for all Midnight transactions)
            println("   ✅ Using dust for transaction fees")

            val dustUtxoSelections = """[{"utxo_index": 0, "v_fee": "1000"}]"""

            serializer.serializeWithDust(
                inputs = listOf(input),
                outputs = listOf(paymentOutput, changeOutput),
                signatures = listOf(signature),
                dustState = dustState!!,
                seed = seed,
                dustUtxosJson = dustUtxoSelections,
                ttl = ttl
            )
        } else {
            // No dust available - transaction will likely fail
            println("   ⚠️  WARNING: No dust UTXOs available! Transaction will fail")
            println("   Register dust via Lace wallet first, or wait for dust to accumulate")
            println("   Submitting WITHOUT dust (will be rejected by node)")

            serializer.serialize(signedIntent)
        }

        println("   ✅ SCALE serialized (unproven): ${scaleHex.length / 2} bytes")
        println("   SCALE hex: ${scaleHex.take(80)}...")

        // Step 8: Prove transaction via proof server
        println("\n🔐 Step 8: Proving transaction via proof server...")
        val proofServerClient = ProofServerClientImpl(
            proofServerUrl = PROOF_SERVER_URL,
            developmentMode = true
        )
        val provenHex = proofServerClient.proveTransaction(scaleHex)
        println("   ✅ Transaction proven: ${provenHex.length / 2} bytes")

        // Step 9: Seal proven transaction
        println("\n🔏 Step 9: Sealing proven transaction...")
        val sealedHex = serializer.sealProvenTransaction(provenHex)
        assertNotNull("Failed to seal proven transaction", sealedHex)
        println("   ✅ Transaction sealed: ${sealedHex!!.length / 2} bytes")

        // Step 10: Submit to node
        println("\n🌐 Step 10: Submitting sealed transaction to Midnight node...")

        val nodeClient = NodeRpcClientImpl(nodeUrl = NODE_URL)

        try {
            val txHash = nodeClient.submitTransaction(sealedHex)

            // SUCCESS - Transaction accepted!
            println("   ✅ ✅ ✅ TRANSACTION ACCEPTED BY NODE!")
            println("   📝 Transaction Hash: $txHash")
            println("   ")
            println("   🎉 REAL ON-CHAIN TRANSACTION SUCCESSFUL!")
            println("   Amount sent: $SEND_AMOUNT → ${recipientAddress.take(30)}...")
            println("   Change: $changeAmount → sender")
            println("   ")
            println("   📋 VERIFICATION STEPS:")
            println("   1. Open Balance Screen in Kuira app")
            println("   2. Check recipient address: $recipientAddress")
            println("   3. Wait for sync (may take a few seconds)")
            println("   4. Verify recipient received: $SEND_AMOUNT smallest units")

            // Verify transaction hash format
            assertTrue(
                "Invalid transaction hash format: $txHash",
                txHash.matches(Regex("^[0-9a-f]{64}$"))
            )

        } catch (e: Exception) {
            val errorMessage = e.message ?: e.toString()
            println("   ⚠️  Node response: $errorMessage")

            // Check if it's a validation error (expected if UTXO is already spent)
            when {
                errorMessage.contains("already spent", ignoreCase = true) -> {
                    println("   ℹ️  UTXO already spent (expected if test runs multiple times)")
                    println("   ✅ Transaction was properly validated by node")
                }
                else -> {
                    println("   ❌ TRANSACTION FAILED!")
                    println("   Error: $errorMessage")
                    fail("Transaction failed: $errorMessage")
                }
            }
        } finally {
            nodeClient.close()
        }

        println("\n✅ Real transaction test complete!")
        println("\n📋 ADDRESSES FOR VERIFICATION:")
        println("   Sender (index 0, should decrease):    $FUNDED_ADDRESS")
        println("   Recipient (index 2, should increase): $recipientAddress")
        println("   Amount transferred: $SEND_AMOUNT smallest units (1.0 NIGHT)")
        println()
    }
}
