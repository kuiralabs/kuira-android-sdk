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
import org.junit.Assume.assumeTrue
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
        // NOTE: do NOT close `database` here — UtxoDatabase.getInstance() is a
        // process-wide singleton. Closing it in a per-test teardown bricks every
        // subsequent test in the run (the next test inherits the closed instance and
        // dies with a JobCancellationException). The singleton is torn down with the
        // test process; an individual test must not close it.
    }

    /**
     * The ledger's NIGHT-input ceiling for a fee-paying transfer (#240): the node's
     * time-to-dismiss cost check (run BEFORE signature verification) rejects a
     * transfer carrying more than two NIGHT inputs with Custom error 168
     * (`OutsideTimeToDismiss`). This ceiling is exactly why `sendNight` selects
     * largest-first and auto-consolidates.
     *
     * Asserts the boundary on-chain: a 3-input transfer is REJECTED and a 2-input
     * transfer is ACCEPTED — on the SAME wallet + dust, so the contrast isolates the
     * input count as the cause (a funding/dust problem would fail the 2-input case too).
     * The 3-input (rejected) case runs FIRST because a rejection mutates no wallet
     * state (no dust `deleteState`), so the 2-input send that follows starts from a
     * clean, still-synced wallet — avoiding the delete-then-resync churn that made the
     * old measurement loop fragile.
     *
     * Needs ≥3 small NIGHT UTXOs + registered dust (`scripts/e2e/fund-send-test.sh`).
     */
    @Test
    fun nightInputCeiling_threeRejected_twoAccepted() = runBlocking {
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

        // Force an exact-sum transfer over the [n] smallest NIGHT UTXOs (zero change →
        // exactly n inputs) and submit it. Returns null when n distinct coins can't be
        // forced (caller skips); releases the UTXO locks on any non-accepted outcome.
        suspend fun submitExactInputs(n: Int): SubmissionResult? {
            syncUnshieldedUtxos()
            dustRepository.syncFromBlockchain(FUNDED_ADDRESS, dustSeed, maxBlocks = 100)
            val night = utxoManager.getUnspentUtxos(FUNDED_ADDRESS)
                .filter { it.tokenType == NATIVE_TOKEN }
                .sortedBy { BigInteger(it.value) }
            if (night.size < n) return null
            val amount = night.take(n).fold(BigInteger.ZERO) { acc, u -> acc + BigInteger(u.value) }
            val built = UnshieldedTransactionBuilder(utxoManager).buildTransfer(
                from = FUNDED_ADDRESS,
                to = recipientAddress,
                amount = amount,
                tokenType = NATIVE_TOKEN,
                senderPublicKey = senderPublicKey,
            ) as? UnshieldedTransactionBuilder.BuildResult.Success ?: return null
            if (built.intent.guaranteedUnshieldedOffer!!.inputs.size != n) {
                utxoManager.unlockUtxos(built.lockedUtxos.map { it.id })
                return null
            }
            val ledgerParamsHex = indexerClient.getCurrentBlockWithParams().ledgerParameters
                ?: error("Indexer returned no ledger parameters")
            val result = submitter.signAndSubmitTransfer(
                intent = built.intent,
                nightPrivateKey = nightPrivateKey,
                ledgerParamsHex = ledgerParamsHex,
                fromAddress = FUNDED_ADDRESS,
                dustSeed = dustSeed,
            )
            if (result !is SubmissionResult.Success && result !is SubmissionResult.Pending) {
                utxoManager.unlockUtxos(built.lockedUtxos.map { it.id })
            }
            return result
        }

        // 3 inputs — over the ceiling → REJECTED. Run first: a rejection is a no-op.
        val three = submitExactInputs(3)
        assumeTrue(
            "needs ≥3 small NIGHT UTXOs to force a 3-input tx — fund via scripts/e2e/fund-send-test.sh",
            three != null,
        )
        android.util.Log.i("CeilingMeasure", "3-input -> $three")
        assertTrue(
            "A 3-input fee-paying transfer must be REJECTED by the ledger's time-to-dismiss ceiling (#240): $three",
            three !is SubmissionResult.Success && three !is SubmissionResult.Pending,
        )

        // 2 inputs — at the ceiling → ACCEPTED.
        val two = submitExactInputs(2)
        assumeTrue("needs ≥2 small NIGHT UTXOs to force a 2-input tx", two != null)
        android.util.Log.i("CeilingMeasure", "2-input -> $two")
        assertTrue(
            "A 2-input fee-paying transfer must be ACCEPTED (#240 ceiling = 2): $two",
            two is SubmissionResult.Success || two is SubmissionResult.Pending,
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
