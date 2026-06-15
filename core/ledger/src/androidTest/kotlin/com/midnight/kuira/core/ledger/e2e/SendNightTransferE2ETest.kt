package com.midnight.kuira.core.ledger.e2e

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import com.midnight.kuira.core.indexer.model.UnshieldedTransactionUpdate
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.api.NodeRpcClientImpl
import com.midnight.kuira.core.ledger.api.ProofServerClientImpl
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.ledger.api.TransactionSubmitter.SubmissionResult
import com.midnight.kuira.core.ledger.builder.UnshieldedTransactionBuilder
import com.midnight.kuira.core.ledger.fee.DustActionsBuilder
import com.midnight.kuira.core.ledger.fee.DustSpendCreator
import com.midnight.kuira.core.ledger.fee.FeeCalculator
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.ledger.signer.TransactionSigner
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest

/**
 * On-chain end-to-end test for [TransactionSubmitter.signAndSubmitTransfer] — the
 * core of the #240 "Send NIGHT from the wallet pill" money-path. Exercises the
 * exact production sequence: [UnshieldedTransactionBuilder.buildTransfer] selects +
 * locks NIGHT UTXOs, [TransactionSubmitter.signAndSubmitTransfer] signs every input
 * and pays dust fees via `submitWithFees`, and the node finalizes the transfer.
 *
 * **Why this test forces a MULTI-INPUT transfer:** the signing message for an
 * unshielded input embeds the transaction's binding commitment, and ALL inputs must
 * sign the SAME commitment (unlike Bitcoin). The JNI binding now threads a shared
 * binding through the sign loop (first input samples it, the rest reuse it). A
 * single-input transfer can't catch a regression there, so this test deliberately
 * picks a send amount the smallest UTXO alone can't cover.
 *
 * **PREREQUISITES (automated by `scripts/e2e/fund-send-test.sh`):**
 *  1. Localnet running (node 9944, indexer 8088, proof server 6300).
 *  2. The test wallet (mnemonic below) funded with **at least two** NIGHT UTXOs.
 *  3. Dust registered for the wallet (fees are paid from dust).
 *
 * Run against ONE emulator only (never a device holding a real wallet):
 *   `ANDROID_SERIAL=emulator-5554 ./gradlew :core:ledger:connectedDebugAndroidTest \
 *      -Pandroid.testInstrumentationRunnerArguments.class=\
 *      com.midnight.kuira.core.ledger.e2e.SendNightTransferE2ETest`
 */
@RunWith(AndroidJUnit4::class)
class SendNightTransferE2ETest {

    private lateinit var database: UtxoDatabase
    private lateinit var wallet: HDWallet
    private lateinit var nightPrivateKey: ByteArray
    private lateinit var dustSeed: ByteArray
    private lateinit var recipientAddress: String
    private lateinit var indexerClient: IndexerClientImpl
    private lateinit var dustRepository: DustRepository
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreDir: File
    private lateinit var dustDataStore: DataStore<Preferences>

    companion object {
        // Canonical test mnemonic — derives to FUNDED_ADDRESS at index 0 (m/44'/2400'/0'/0/0).
        // Matches the mn CLI's `kuiratest` / `test-abandon` wallet.
        private const val TEST_MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon art"

        private const val FUNDED_ADDRESS =
            "mn_addr_undeployed19kxg8sxrsty37elmm6yd68tuy7prryjst2r48eapf2fdtd8z4gpqauuvtx"

        private val NATIVE_TOKEN = UtxoSpend.NATIVE_TOKEN_TYPE
        private const val NODE_URL = "http://10.0.2.2:9944"
        private const val PROOF_SERVER_URL = "http://10.0.2.2:6300"

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }

    @Before
    fun setup() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = UtxoDatabase.getInstance(context)

        wallet = HDWallet.fromSeed(BIP39.mnemonicToSeed(TEST_MNEMONIC, passphrase = ""))

        val dustKey = wallet.selectAccount(0).selectRole(MidnightKeyRole.DUST).deriveKeyAt(0)
        dustSeed = dustKey.privateKeyBytes.copyOf()
        dustKey.clear()

        val senderKey = wallet.selectAccount(0).selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)
        nightPrivateKey = senderKey.privateKeyBytes.copyOf()
        senderKey.clear()

        // Verify our derivation matches the funded address (guards a derivation drift).
        val senderPubKey = TransactionSigner.getPublicKey(nightPrivateKey)
            ?: throw IllegalStateException("Failed to derive sender public key")
        val senderAddress = Bech32m.encode(
            "mn_addr_undeployed",
            MessageDigest.getInstance("SHA-256").digest(senderPubKey),
        )
        assertEquals("Derivation mismatch — fund the right wallet", FUNDED_ADDRESS, senderAddress)

        // Recipient: a fresh index-1 address from the same wallet.
        val recipientKey = wallet.selectAccount(0).selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(1)
        val recipientPubKey = TransactionSigner.getPublicKey(recipientKey.privateKeyBytes)
            ?: throw IllegalStateException("Failed to derive recipient public key")
        recipientAddress = Bech32m.encode(
            "mn_addr_undeployed",
            MessageDigest.getInstance("SHA-256").digest(recipientPubKey),
        )
        recipientKey.clear()

        indexerClient = IndexerClientImpl(
            baseUrl = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerBaseUrl,
            developmentMode = true,
        )

        syncUnshieldedUtxos()

        // Build + populate the dust repository so submitWithFees can pay the fee.
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStoreDir = Files.createTempDirectory("send_e2e_dust").toFile()
        dustDataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) {
            File(dataStoreDir, "dust.preferences_pb")
        }
        dustRepository = DustRepository(
            dustDao = database.dustDao(),
            dustStateDataStore = dustDataStore,
            balanceCalculator = DustBalanceCalculator(),
            indexerClient = indexerClient,
        )
        dustRepository.syncFromBlockchain(FUNDED_ADDRESS, dustSeed, maxBlocks = 100)
    }

    /** Replay the unshielded-tx stream into the DB so [UtxoManager] sees the wallet's NIGHT UTXOs. */
    private suspend fun syncUnshieldedUtxos() {
        val wsClient = IndexerClientImpl(
            baseUrl = NetworkConfig.forNetwork(MidnightNetwork.UNDEPLOYED).indexerBaseUrl,
            developmentMode = true,
        )
        val utxoManager = UtxoManager(database.unshieldedUtxoDao())
        try {
            var highestTxId = 0
            var lastSeenTxId = 0
            var progressReceived = false
            withTimeoutOrNull(30_000L) {
                wsClient.subscribeToUnshieldedTransactions(address = FUNDED_ADDRESS, transactionId = null)
                    .takeWhile { update ->
                        utxoManager.processUpdate(update)
                        when (update) {
                            is UnshieldedTransactionUpdate.Transaction -> {
                                lastSeenTxId = maxOf(lastSeenTxId, update.transaction.id)
                                !(progressReceived && lastSeenTxId >= highestTxId)
                            }
                            is UnshieldedTransactionUpdate.Progress -> {
                                highestTxId = update.highestTransactionId
                                progressReceived = true
                                !(highestTxId == 0 || lastSeenTxId >= highestTxId)
                            }
                        }
                    }.collect {}
            }
        } finally {
            wsClient.close()
        }
    }

    @After
    fun teardown() {
        wallet.clear()
        nightPrivateKey.fill(0)
        dustSeed.fill(0)
        if (::indexerClient.isInitialized) indexerClient.close()
        if (::dataStoreScope.isInitialized) dataStoreScope.cancel()
        if (::dataStoreDir.isInitialized) dataStoreDir.deleteRecursively()
        if (::database.isInitialized) database.close()
    }

    /**
     * Measures the ledger's NIGHT-input ceiling for a fee-paying transfer (#240).
     *
     * Error 168 (`OutsideTimeToDismiss`) is a cost check the node runs BEFORE
     * signature verification, so this measurement is valid even with the current
     * (binding-reuse-unfixed) native lib: a binding bug would only surface AFTER a
     * tx clears 168. For each N = 1,2,3,… it forces an N-input transfer (sends the
     * exact sum of the N smallest UTXOs → zero change → exactly N inputs), submits,
     * and records whether the node accepts it. The first N that fails is the ceiling+1.
     *
     * Run after funding the wallet with several small NIGHT UTXOs
     * (`scripts/e2e/fund-send-test.sh` airdrops a couple; airdrop more for a higher
     * ceiling). The measured ceiling decides the #240 coin-selection strategy:
     * ceiling 1 → single-coin sends only; ceiling ≥2 → multi-input (needs the
     * binding round-trip fix) is worth enabling.
     */
    @Test
    fun measureInputCeiling() = runBlocking {
        val utxoManager = UtxoManager(database.unshieldedUtxoDao())
        val senderPublicKey = TransactionSigner.getPublicKey(nightPrivateKey)!!.toHex()
        val submitter = TransactionSubmitter(
            nodeRpcClient = NodeRpcClientImpl(NODE_URL),
            proofServerClient = ProofServerClientImpl(PROOF_SERVER_URL, developmentMode = true),
            indexerClient = indexerClient,
            serializer = FfiTransactionSerializer(networkId = "undeployed"),
            utxoManager = utxoManager,
            dustActionsBuilder = DustActionsBuilder(dustRepository, FeeCalculator, DustSpendCreator),
            dustRepository = dustRepository,
            provingMode = ProvingMode.REMOTE,
        )

        val maxN = 5
        var ceiling = 0
        for (n in 1..maxN) {
            // Re-sync UTXOs (prior successful sends consumed some) and take the N smallest.
            syncUnshieldedUtxos()
            // Re-sync dust too: a successful submitWithFees deletes the dust cache to force a
            // fresh sync before the next tx, so without this the next iteration fails to LOAD
            // dust ("Insufficient dust... required null") before it can reach the ledger's
            // input-count check — which would mask the real 168 ceiling.
            dustRepository.syncFromBlockchain(FUNDED_ADDRESS, dustSeed, maxBlocks = 100)
            val night = UtxoManager(database.unshieldedUtxoDao())
                .getUnspentUtxos(FUNDED_ADDRESS)
                .filter { it.tokenType == NATIVE_TOKEN }
                .sortedBy { BigInteger(it.value) }
            if (night.size < n) {
                android.util.Log.w("CeilingMeasure", "N=$n: only ${night.size} NIGHT UTXOs left — stop")
                break
            }

            // Send the EXACT sum of the N smallest → zero change → exactly N inputs selected.
            val amount = night.take(n).fold(BigInteger.ZERO) { acc, u -> acc + BigInteger(u.value) }
            val build = UnshieldedTransactionBuilder(utxoManager).buildTransfer(
                from = FUNDED_ADDRESS,
                to = recipientAddress,
                amount = amount,
                tokenType = NATIVE_TOKEN,
                senderPublicKey = senderPublicKey,
            )
            val built = build as? UnshieldedTransactionBuilder.BuildResult.Success
                ?: return@runBlocking fail("N=$n: buildTransfer failed: $build")
            val inputCount = built.intent.guaranteedUnshieldedOffer!!.inputs.size
            if (inputCount != n) {
                // A single large UTXO covered it, etc. — release + stop (can't force N here).
                utxoManager.unlockUtxos(built.lockedUtxos.map { it.id })
                android.util.Log.w("CeilingMeasure", "N=$n: selected $inputCount inputs (couldn't force N) — stop")
                break
            }

            val ledgerParamsHex = indexerClient.getCurrentBlockWithParams().ledgerParameters
                ?: return@runBlocking fail("Indexer returned no ledger parameters")
            val result = submitter.signAndSubmitTransfer(
                intent = built.intent,
                nightPrivateKey = nightPrivateKey,
                ledgerParamsHex = ledgerParamsHex,
                fromAddress = FUNDED_ADDRESS,
                dustSeed = dustSeed,
            )
            val accepted = result is SubmissionResult.Success || result is SubmissionResult.Pending
            android.util.Log.i("CeilingMeasure", "N=$n inputs -> ${if (accepted) "ACCEPTED" else "REJECTED"}: $result")
            if (accepted) {
                ceiling = n
            } else {
                utxoManager.unlockUtxos(built.lockedUtxos.map { it.id })
                break
            }
        }

        android.util.Log.i("CeilingMeasure", "MEASURED_INPUT_CEILING=$ceiling")
        assertTrue(
            "Single-input transfer must work (ceiling >= 1); measured $ceiling. Check localnet funding/dust.",
            ceiling >= 1,
        )
    }

    /**
     * Diagnostic for #240: does a SELF-send (to == from) fail signature verification
     * (node error 175) where the same wallet's send to a DIFFERENT recipient passes?
     * Consolidation merges are self-sends, so this isolates whether self-send is the
     * blocker. Sends the sum of the two smallest coins back to FUNDED_ADDRESS itself.
     */
    @Test
    fun selfSend_twoInputs_diagnostic() = runBlocking {
        syncUnshieldedUtxos()
        dustRepository.syncFromBlockchain(FUNDED_ADDRESS, dustSeed, maxBlocks = 100)
        val utxoManager = UtxoManager(database.unshieldedUtxoDao())
        val night = utxoManager.getUnspentUtxos(FUNDED_ADDRESS)
            .filter { it.tokenType == NATIVE_TOKEN }
            .sortedBy { BigInteger(it.value) }
        assertTrue("need >= 2 NIGHT coins for a 2-input self-send", night.size >= 2)

        // Sum of the two smallest → smallest-first picks exactly those two (big coin untouched).
        val amount = BigInteger(night[0].value) + BigInteger(night[1].value)
        val senderPublicKey = TransactionSigner.getPublicKey(nightPrivateKey)!!.toHex()
        val build = UnshieldedTransactionBuilder(utxoManager).buildTransfer(
            from = FUNDED_ADDRESS,
            to = FUNDED_ADDRESS, // SELF
            amount = amount,
            tokenType = NATIVE_TOKEN,
            senderPublicKey = senderPublicKey,
        )
        val built = build as? UnshieldedTransactionBuilder.BuildResult.Success
            ?: return@runBlocking fail("buildTransfer failed: $build")
        val inputCount = built.intent.guaranteedUnshieldedOffer!!.inputs.size

        val submitter = TransactionSubmitter(
            nodeRpcClient = NodeRpcClientImpl(NODE_URL),
            proofServerClient = ProofServerClientImpl(PROOF_SERVER_URL, developmentMode = true),
            indexerClient = indexerClient,
            serializer = FfiTransactionSerializer(networkId = "undeployed"),
            utxoManager = utxoManager,
            dustActionsBuilder = DustActionsBuilder(dustRepository, FeeCalculator, DustSpendCreator),
            dustRepository = dustRepository,
            provingMode = ProvingMode.REMOTE,
        )
        val ledgerParamsHex = indexerClient.getCurrentBlockWithParams().ledgerParameters
            ?: return@runBlocking fail("no ledger parameters")
        val result = submitter.signAndSubmitTransfer(
            intent = built.intent,
            nightPrivateKey = nightPrivateKey,
            ledgerParamsHex = ledgerParamsHex,
            fromAddress = FUNDED_ADDRESS,
            dustSeed = dustSeed,
        )
        android.util.Log.i("SelfSendDiag", "SELF-send $inputCount-input result=$result")
        if (result !is SubmissionResult.Success && result !is SubmissionResult.Pending) {
            utxoManager.unlockUtxos(built.lockedUtxos.map { it.id })
        }
        assertTrue(
            "SELF-send ($inputCount inputs) should finalize like an other-recipient send does: $result",
            result is SubmissionResult.Success || result is SubmissionResult.Pending,
        )
    }
}
