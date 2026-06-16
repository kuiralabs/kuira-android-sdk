package com.midnight.kuira.sdk

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.midnight.kuira.core.compact.ContractOperationListener
import com.midnight.kuira.core.compact.MidnightConfig
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.address.Bech32m
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.dust.DustKeyDeriver
import com.midnight.kuira.core.crypto.proving.ProvingMode
import com.midnight.kuira.core.crypto.shielded.ShieldedKeyDeriver
import com.midnight.kuira.core.identity.accesskey.AccessKeyManager
import com.midnight.kuira.core.indexer.api.IndexerClientImpl
import com.midnight.kuira.core.indexer.database.UtxoDatabase
import com.midnight.kuira.core.indexer.dust.DustBalanceCalculator
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.repository.ShieldedRepository
import com.midnight.kuira.core.indexer.repository.SpentDustNullifierStore
import com.midnight.kuira.core.indexer.sync.SubscriptionManager
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.api.NodeRpcClientImpl
import com.midnight.kuira.core.ledger.api.ProofServerClientImpl
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.ledger.api.TransactionSubmitter.SubmissionResult
import com.midnight.kuira.core.ledger.builder.UnshieldedTransactionBuilder
import com.midnight.kuira.core.ledger.signer.TransactionSigner
import java.math.BigInteger
import com.midnight.kuira.core.ledger.dust.DustRegistrationBuilder
import com.midnight.kuira.core.ledger.fee.DustActionsBuilder
import com.midnight.kuira.core.ledger.fee.DustSpendCreator
import com.midnight.kuira.core.ledger.fee.FeeCalculator
import com.midnight.kuira.core.ledger.model.UtxoSpend
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.midnight.kuira.core.indexer.database.UnshieldedUtxoEntity
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Arrays

/**
 * Top-level entry point for the Midnight Android SDK.
 *
 * Provides a fully standalone environment for dApps: contract execution,
 * local proving, embedded wallet (dust fee payment), and node submission.
 * No external wallet process (`mn serve`) required.
 *
 * ```kotlin
 * val sdk = MidnightSdk.Builder(context)
 *     .network(MidnightNetwork.PREPROD)
 *     .seed(mnemonicSeed)
 *     .build()
 *
 * val contract = MidnightContract.create(sdk.config) {
 *     contractJs = assets.open("runtime/my-contract-iife.js")
 *     address = contractAddress
 *     coinPublicKey = sdk.coinPublicKey
 *     // ... witnesses, private state
 * }
 *
 * val receipt = contract.call("myCircuit", arg1) // Fully standalone
 * sdk.close()
 * ```
 */
class MidnightSdk private constructor(
    /** MidnightConfig pre-wired with the embedded wallet. */
    val config: MidnightConfig,

    /** Embedded wallet for direct balance/submit operations. */
    val wallet: MidnightWallet,

    /** Coin public key for circuit execution (32 bytes). */
    val coinPublicKey: ByteArray,

    /** Unshielded wallet address (Bech32m-encoded). */
    val walletAddress: String,

    /**
     * Shielded receive address (Bech32m-encoded, HRP `mn_shield-addr_<network>`).
     *
     * Senders use this to dispatch shielded NIGHT to the wallet; only the
     * holder of the SDK's zswap key can decrypt the incoming coins. Public,
     * safe to share / display / encode in a QR. Derived deterministically
     * from the seed at build time.
     */
    val shieldedWalletAddress: String,

    /** Proving key manager (for checking/downloading keys). */
    val provingKeyManager: ProvingKeyManager,

    /**
     * Access key for sigil identity — 33-byte compressed secp256k1 public key.
     * Use this with [KeyAuthorization] to build delegation payloads.
     */
    val accessKeyPublicKey: ByteArray,

    /** HD derivation path of the access key (e.g., "m/44'/2400'/0'/5/0"). */
    val accessKeyPath: String,

    /**
     * Registry of in-flight value-bearing operations (#261-264). Observe
     * [OperationRegistry.active] / [OperationRegistry.outcomes] to project any UI
     * (the wallet pill is just one consumer); enroll your own long-running work via
     * [runForegroundOperation] / [launchForegroundOperation] to get the same
     * process-survival + notification treatment the SDK's own send/dust/contract
     * actions receive.
     */
    val operations: OperationRegistry,

    // ── Internals held for [registerForDustGeneration] and lifecycle cleanup ──
    private val nightPrivateKey: ByteArray,
    private val dustSeed: ByteArray,
    private val networkId: String,
    private val utxoManager: UtxoManager,
    private val transactionSubmitter: TransactionSubmitter,
    private val subscriptionScope: CoroutineScope,
    private val subscriptionJob: Job,
    private val database: UtxoDatabase,
    private val indexerClient: IndexerClientImpl,
    private val shieldedTracker: ShieldedBalanceTracker,
    private val dustTracker: DustBalanceTracker?,
) {
    init {
        // Feed the wallet the live NIGHT-UTXO list so its `dustRegistered` signal
        // reflects true per-UTXO registration ("are all NIGHT UTXOs generating?")
        // rather than the dust>0 heuristic (#265).
        wallet.nightUtxoJsonProvider = {
            nightUtxosToJson(
                utxoManager.getUnspentUtxos(walletAddress)
                    .filter { it.tokenType == UtxoSpend.NATIVE_TOKEN_TYPE },
            )
        }
    }

    /**
     * Register this wallet's NIGHT key to generate dust against its public dust key.
     *
     * Dust is the fee token Midnight generates from NIGHT holdings over time, but the
     * generated dust is only *spendable* by a wallet that has registered its DUST
     * public key against its NIGHT signing key on-chain. Newly-funded wallets must
     * call this once before they can pay fees on any subsequent transaction —
     * including contract calls.
     *
     * Inputs are pulled from the SDK's already-derived state: NIGHT private key
     * for the signature, dust seed for the dust pubkey, current NIGHT UTXOs for
     * the `guaranteed_unshielded_offer`. The unshielded-tx subscription started
     * at SDK build time keeps [utxoManager] populated, so this method picks up
     * NIGHT UTXOs the moment a faucet/transfer credits the wallet.
     *
     * Submission uses [TransactionSubmitter.submitPrebuiltTransaction] with
     * [ProvingMode.LOCAL] — same local-prove → seal → submit path that contract
     * calls already use; no proof server required.
     *
     * **Precondition:** the wallet address must hold at least one NIGHT UTXO.
     * The chain rejects registration with no NIGHT to back the generation. Call
     * [MidnightWallet.waitForFunding] first if the wallet is fresh.
     *
     * @return [SubmissionResult.Success] when the registration tx is finalized on
     *   chain. [SubmissionResult.Pending] if the tx made it into a block but the
     *   wait for finalization timed out (it'll usually finalize shortly after).
     *   [SubmissionResult.Failed] if the chain rejected the tx, or no NIGHT UTXOs
     *   are available, or the FFI builder returned null.
     */
    suspend fun registerForDustGeneration(): SubmissionResult = operations.run(
        OperationDescriptor(OperationKind.DustRegistration),
        classify = { it.toOperationResult() },
    ) {
        registerForDustGenerationInternal()
    }

    private suspend fun registerForDustGenerationInternal(): SubmissionResult {
        val dustPublicKeyHex = DustKeyDeriver.derivePublicKey(dustSeed)
            ?: return SubmissionResult.Failed(
                txHash = null,
                reason = "DustKeyDeriver returned null — native library not loaded?",
            )

        suspend fun currentNight() = utxoManager.getUnspentUtxos(walletAddress)
            .filter { it.tokenType == UtxoSpend.NATIVE_TOKEN_TYPE }

        if (currentNight().isEmpty()) {
            return SubmissionResult.Failed(
                txHash = null,
                reason = "No NIGHT UTXOs at $walletAddress. Fund the wallet first.",
            )
        }

        // A registration can carry only ~one NIGHT input before it exceeds the ledger's
        // time-to-dismiss budget (rejected as Custom error 168 / FeeCalculation), so a
        // multi-UTXO wallet registers one UTXO per transaction. Loop until every NIGHT
        // UTXO is generating dust; the cap bounds the loop if sync ever stalls.
        var lastResult: SubmissionResult? = null
        val maxRegistrations = currentNight().size + 2

        repeat(maxRegistrations) { iteration ->
            runCatching { wallet.refresh() }.onFailure { Log.w(TAG, "pre-filter refresh failed: ${it.message}") }

            val night = currentNight()
            if (night.isEmpty()) {
                return lastResult ?: SubmissionResult.Failed(txHash = null, reason = "No NIGHT UTXOs remaining.")
            }

            val unregisteredJson = wallet.unregisteredNightUtxos(nightUtxosToJson(night))
            val unregisteredCount = JSONArray(unregisteredJson).length()
            if (unregisteredCount == 0) {
                Log.i(TAG, "Dust registration complete — all NIGHT generating after $iteration tx(s)")
                return lastResult ?: SubmissionResult.Success(txHash = "", blockHeight = 0L)
            }

            // Chain-anchored time, NOT wall-clock (same reason as the Error 170 fix in
            // MidnightWallet.tryBalance). The registration self-pays via allow_fee_payment,
            // but ctime is still validated against chain block times.
            val blockTimestamp = wallet.indexerBlockTimestampMs()
            val unprovenHex = DustRegistrationBuilder.build(
                nightPrivateKey = nightPrivateKey,
                dustPublicKeyHex = dustPublicKeyHex,
                utxosJson = unregisteredJson, // builder picks the highest-dust UTXO from this set
                ttlMillis = blockTimestamp + REGISTRATION_TTL_MS,
                networkId = networkId,
                currentTimeMillis = blockTimestamp,
            ) ?: return SubmissionResult.Failed(txHash = null, reason = "DustRegistrationBuilder.build returned null")

            Log.i(TAG, "Registering dust: $unregisteredCount NIGHT UTXO(s) not yet generating (tx ${iteration + 1})")
            val result = transactionSubmitter.submitPrebuiltTransaction(unprovenHex)
            if (result !is SubmissionResult.Success && result !is SubmissionResult.Pending) {
                return result
            }
            lastResult = result

            // Wait for this registration's dust to surface before the next filter, so we
            // don't re-register the freshly-created output (propagation lags finalization
            // by ~1 block). Ends early once the unregistered set shrinks.
            val deadline = System.currentTimeMillis() + DUST_PROPAGATION_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(DUST_POLL_INTERVAL_MS)
                runCatching { wallet.refresh() }
                val stillUnregistered =
                    JSONArray(wallet.unregisteredNightUtxos(nightUtxosToJson(currentNight()))).length()
                if (stillUnregistered < unregisteredCount) break
            }
        }

        return lastResult ?: SubmissionResult.Failed(txHash = null, reason = "Dust registration made no progress.")
    }

    /** Serialize NIGHT UTXOs to the JSON the FFI registration builder/filter expect. */
    private fun nightUtxosToJson(utxos: List<UnshieldedUtxoEntity>): String =
        JSONArray().apply {
            utxos.forEach { utxo ->
                put(
                    JSONObject().apply {
                        put("value", utxo.value)
                        put("intent_hash", utxo.intentHash)
                        put("output_no", utxo.outputIndex)
                        put("ctime", utxo.ctime)
                    }
                )
            }
        }.toString()

    /**
     * Send unshielded NIGHT from this wallet to [toAddress] (#240 — Send from the
     * wallet pill).
     *
     * Fee-bearing transfer: the NIGHT inputs are signed (BIP-340), and dust fees
     * are built and paid automatically from the wallet's registered dust — the
     * same `submitWithFees` path contract calls use. The wallet must have:
     *  - enough NIGHT to cover [amount] (else [SendResult.InsufficientFunds]), and
     *  - registered dust to pay the fee (call [registerForDustGeneration] once on a
     *    fresh wallet); a missing/empty dust balance surfaces as [SendResult.Failed].
     *
     * Inputs come from the SDK's already-derived state: NIGHT private key for the
     * per-input signatures, dust seed for the fee, the live [utxoManager] for the
     * spendable NIGHT UTXOs (kept current by the build-time subscription). On any
     * failure after UTXOs are locked, the locks are released so the balance isn't
     * stuck PENDING. On success/pending the wallet is refreshed so the pill reflects
     * the new balance immediately.
     *
     * @param toAddress Recipient's unshielded Bech32m address; must be the same
     *   network (HRP) as this wallet.
     * @param amount NIGHT to send, in the smallest unit (must be positive).
     * @param onProgress Optional coarse progress callback for driving a UI spinner.
     * @return a typed [SendResult] — never throws for an expected failure (invalid
     *   address, insufficient funds, chain rejection); unexpected errors map to
     *   [SendResult.Failed].
     */
    suspend fun sendNight(
        toAddress: String,
        amount: BigInteger,
        onProgress: ((SendProgress) -> Unit)? = null,
    ): SendResult = operations.run(
        OperationDescriptor(OperationKind.Send),
        classify = { it.toOperationResult() },
    ) {
        sendNightInternal(toAddress, amount, onProgress)
    }

    private suspend fun sendNightInternal(
        toAddress: String,
        amount: BigInteger,
        onProgress: ((SendProgress) -> Unit)?,
    ): SendResult {
        // Chain-independent request validation (positive amount, well-formed
        // same-network recipient) — pure + unit-tested in [validateSendRequest].
        validateSendRequest(amount, toAddress, walletAddress)?.let { return it }

        // buildTransfer needs the sender's BIP-340 x-only public key (64 hex chars).
        val senderPublicKey = TransactionSigner.getPublicKey(nightPrivateKey)
            ?.joinToString("") { "%02x".format(it) }
            ?: return SendResult.Failed("Failed to derive sender public key (native library not loaded?).")

        suspend fun nightDescending(): List<UnshieldedUtxoEntity> =
            utxoManager.getUnspentUtxos(walletAddress)
                .filter { it.tokenType == UtxoSpend.NATIVE_TOKEN_TYPE }
                .sortedByDescending { BigInteger(it.value) }
        fun topSum(utxos: List<UnshieldedUtxoEntity>, k: Int): BigInteger =
            utxos.take(k).fold(BigInteger.ZERO) { acc, u -> acc + BigInteger(u.value) }

        var night = nightDescending()
        val total = night.fold(BigInteger.ZERO) { acc, u -> acc + BigInteger(u.value) }
        if (total < amount) {
            return SendResult.InsufficientFunds(amount, total, amount - total)
        }

        // A fee-paying transfer can carry at most [MAX_TRANSFER_INPUTS] NIGHT inputs
        // (ledger time-to-dismiss ceiling). If the amount needs more coins than that,
        // consolidate first: merge the largest coins (a self-send, MAX inputs → one
        // output) until the top [MAX_TRANSFER_INPUTS] coins alone cover the amount.
        // Each merge drops the coin count by (MAX_TRANSFER_INPUTS - 1), so this
        // converges; the counter bounds it against any sync hiccup.
        var mergesRemaining = night.size
        while (topSum(night, MAX_TRANSFER_INPUTS) < amount &&
            night.size > MAX_TRANSFER_INPUTS &&
            mergesRemaining-- > 0
        ) {
            onProgress?.invoke(SendProgress.CONSOLIDATING)
            val mergeAmount = topSum(night, MAX_TRANSFER_INPUTS)
            val merge = submitTransfer(walletAddress, mergeAmount, senderPublicKey)
            if (merge !is SubmissionResult.Success && merge !is SubmissionResult.Pending) {
                return merge.toSendResult()
            }
            // Wait for the merged coin to surface (refresh also re-syncs the dust the
            // successful merge consumed, readying the fee for the next tx).
            night = awaitMergedCoin(mergeAmount) ?: return SendResult.Failed(
                "Coin consolidation did not settle in time — please retry shortly.",
            )
        }

        onProgress?.invoke(SendProgress.SUBMITTING)
        val result = submitTransfer(toAddress, amount, senderPublicKey)
        when (result) {
            is SubmissionResult.Success, is SubmissionResult.Pending, is SubmissionResult.StaleUtxo ->
                runCatching { wallet.refresh() }
                    .onFailure { Log.w(TAG, "post-send refresh failed: ${it.message}") }
            else -> {}
        }
        // Spending NIGHT creates a fresh CHANGE UTXO with no dust backing, so the
        // wallet would otherwise stop generating dust until the user manually
        // re-registers (#265). Re-register it in the BACKGROUND so dust "just works"
        // without holding up the result: the transfer has already succeeded, and
        // registerForDustGeneration polls for ~1 block of dust propagation — awaiting
        // it would strand the user on a progress spinner after the send is done.
        // Registration self-pays (allow_fee_payment), so it works even though this
        // transfer just consumed the prior backing. Runs on the SDK's subscriptionScope
        // so it survives this call returning; registerForDustGeneration enrolls as a
        // tracked DustRegistration operation (#262), so it keeps the process alive under
        // the foreground service and fires its own finalization notification. The
        // accurate dustRegistered signal flips back to true on the next balance read.
        if (result is SubmissionResult.Success || result is SubmissionResult.Pending) {
            subscriptionScope.launch {
                runCatching { registerForDustGeneration() }
                    .onFailure { Log.w(TAG, "post-send dust re-registration failed: ${it.message}") }
            }
        }
        return result.toSendResult()
    }

    /**
     * Build (largest-first, fewest inputs) + sign + dust-pay + submit one NIGHT
     * transfer. Releases the UTXO locks on any non-accepted outcome so the balance
     * isn't stuck PENDING. Guards the [MAX_TRANSFER_INPUTS] ceiling defensively.
     */
    private suspend fun submitTransfer(
        toAddress: String,
        amount: BigInteger,
        senderPublicKey: String,
    ): SubmissionResult {
        val built = when (
            val build = UnshieldedTransactionBuilder(utxoManager).buildTransfer(
                from = walletAddress,
                to = toAddress,
                amount = amount,
                tokenType = UtxoSpend.NATIVE_TOKEN_TYPE,
                senderPublicKey = senderPublicKey,
                largestFirst = true,
            )
        ) {
            is UnshieldedTransactionBuilder.BuildResult.InsufficientFunds ->
                return SubmissionResult.Failed(
                    txHash = null,
                    reason = "Insufficient NIGHT to build transfer (need ${build.required}, have ${build.available}).",
                )
            is UnshieldedTransactionBuilder.BuildResult.Success -> build
        }

        suspend fun releaseLocks() =
            runCatching { utxoManager.unlockUtxos(built.lockedUtxos.map { it.id }) }
                .onFailure { Log.w(TAG, "failed to release UTXO locks: ${it.message}") }

        val inputCount = built.intent.guaranteedUnshieldedOffer?.inputs?.size ?: 0
        if (inputCount > MAX_TRANSFER_INPUTS) {
            // Should not happen — the caller consolidates first — but never submit a
            // tx the ledger will reject for too many inputs.
            releaseLocks()
            return SubmissionResult.Failed(
                txHash = null,
                reason = "Transfer needs $inputCount inputs, over the $MAX_TRANSFER_INPUTS-input ledger limit.",
            )
        }

        return try {
            val ledgerParamsHex = indexerClient.getCurrentBlockWithParams().ledgerParameters
                ?: run {
                    releaseLocks()
                    return SubmissionResult.Failed(txHash = null, reason = "Indexer returned no ledger parameters.")
                }
            val submission = transactionSubmitter.signAndSubmitTransfer(
                intent = built.intent,
                nightPrivateKey = nightPrivateKey,
                ledgerParamsHex = ledgerParamsHex,
                fromAddress = walletAddress,
                dustSeed = dustSeed,
            )
            if (submission !is SubmissionResult.Success && submission !is SubmissionResult.Pending) {
                releaseLocks()
            }
            submission
        } catch (e: Exception) {
            releaseLocks()
            SubmissionResult.Failed(txHash = null, reason = "Send failed: ${e.message}")
        }
    }

    /**
     * Poll until the just-created merged coin (value == [mergeAmount]) surfaces in the
     * live UTXO set, re-syncing dust each tick (a successful tx deletes the dust cache,
     * and the next tx needs it). Returns the fresh descending NIGHT UTXO list, or null
     * on timeout. The merge's change is zero, so the merged coin is the unique output.
     */
    private suspend fun awaitMergedCoin(mergeAmount: BigInteger): List<UnshieldedUtxoEntity>? {
        val deadline = System.currentTimeMillis() + DUST_PROPAGATION_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(DUST_POLL_INTERVAL_MS)
            runCatching { wallet.refresh() }.onFailure { Log.w(TAG, "consolidation refresh failed: ${it.message}") }
            val night = utxoManager.getUnspentUtxos(walletAddress)
                .filter { it.tokenType == UtxoSpend.NATIVE_TOKEN_TYPE }
                .sortedByDescending { BigInteger(it.value) }
            if (night.any { BigInteger(it.value) == mergeAmount }) return night
        }
        return null
    }

    private fun SubmissionResult.toSendResult(): SendResult = when (this) {
        is SubmissionResult.Success -> SendResult.Success(txHash, blockHeight)
        is SubmissionResult.Pending -> SendResult.Pending(txHash, reason)
        is SubmissionResult.Failed -> SendResult.Failed(reason, txHash)
        is SubmissionResult.StaleUtxo -> SendResult.Failed(reason)
    }

    /** Map a [SendResult] onto the operation-registry terminal outcome (#261-264). */
    private fun SendResult.toOperationResult(): OperationResult = when (this) {
        is SendResult.Success -> OperationResult(OperationTerminalStatus.Success, txHash)
        is SendResult.Pending -> OperationResult(OperationTerminalStatus.Pending, txHash)
        is SendResult.Failed -> OperationResult(OperationTerminalStatus.Failed, txHash)
        is SendResult.InsufficientFunds -> OperationResult(OperationTerminalStatus.Failed)
        is SendResult.InvalidAddress -> OperationResult(OperationTerminalStatus.Failed)
    }

    /** Map a [SubmissionResult] onto the operation-registry terminal outcome (#261-264). */
    private fun SubmissionResult.toOperationResult(): OperationResult = when (this) {
        is SubmissionResult.Success -> OperationResult(OperationTerminalStatus.Success, txHash)
        is SubmissionResult.Pending -> OperationResult(OperationTerminalStatus.Pending, txHash)
        is SubmissionResult.Failed -> OperationResult(OperationTerminalStatus.Failed, txHash)
        is SubmissionResult.StaleUtxo -> OperationResult(OperationTerminalStatus.Failed)
    }

    /** Coarse progress stages for [sendNight], for driving a UI spinner/status. */
    enum class SendProgress {
        /** Merging coins so the transfer fits under the ledger's input ceiling. */
        CONSOLIDATING,

        /** Signing inputs, paying dust fees, submitting, and awaiting finalization. */
        SUBMITTING,
    }

    /** Outcome of [sendNight] — a typed result so callers can render distinct UX. */
    sealed class SendResult {
        /** Transfer finalized on chain. */
        data class Success(val txHash: String, val blockHeight: Long) : SendResult()

        /** Not enough NIGHT to cover [required]; carries the [shortfall] for the UI. */
        data class InsufficientFunds(
            val required: BigInteger,
            val available: BigInteger,
            val shortfall: BigInteger,
        ) : SendResult()

        /** Recipient address was malformed or on the wrong network. */
        data class InvalidAddress(val reason: String) : SendResult()

        /** Submitted but finalization timed out — likely confirms shortly after. */
        data class Pending(val txHash: String, val reason: String) : SendResult()

        /** Any other failure (proving, dust fee, node rejection, unexpected error). */
        data class Failed(val reason: String, val txHash: String? = null) : SendResult()
    }

    /**
     * Run [block] as a tracked foreground operation (#261-264) and return its result.
     *
     * The SDK keeps the process alive under a foreground service for the operation's
     * duration, holds the session-lock off (so an auto-lock can't tear the SDK out
     * mid-flight), shows an ongoing progress notification, and fires a dismissible
     * finalization notification when it completes. [label] is YOUR string (shown in
     * the notification) — the SDK emits no text of its own. This is the SAME machinery
     * the SDK's own [sendNight] / [registerForDustGeneration] / contract calls use, so
     * any dApp's long-running work gets identical treatment.
     *
     * For "submit then leave the app", use [launchForegroundOperation] (fire-and-forget
     * on the SDK's own scope) so the work survives the caller's coroutine being cancelled.
     */
    suspend fun <T> runForegroundOperation(
        label: String,
        completionLabel: String? = null,
        contentIntent: PendingIntent? = null,
        block: suspend () -> T,
    ): T = operations.run(
        OperationDescriptor(OperationKind.Custom, label, completionLabel, contentIntent),
        block = block,
    )

    /**
     * Update the live progress stage of the foreground operation the CURRENT coroutine is
     * running inside (see [runForegroundOperation]) — this drives the ongoing notification
     * text and the status-bar chip ("Submitting commit…" → "Finalizing…"). [stage] is YOUR
     * string (the SDK emits none). No-op when called outside a tracked operation.
     */
    suspend fun updateOperationStage(stage: String?) = operations.updateCurrentStage(stage)

    /**
     * Pull the user BACK to the current foreground operation with a heads-up alert (#264
     * inbound) — e.g. a protocol step that needs their input, or a counterparty's move. Fires
     * an alerting notification that taps back to the operation's screen. [title]/[body] are
     * YOUR strings (the SDK emits none). No-op outside a tracked operation; the host suppresses
     * it when the app is already foreground, so you can call it unconditionally.
     */
    suspend fun requestAttention(title: String, body: String? = null) = operations.requestAttention(title, body)

    /**
     * Fire-and-forget [runForegroundOperation] on the SDK's lifecycle scope: returns a
     * [Job] immediately, and the tracked work keeps running (under the foreground
     * service) even after the caller leaves the screen. Failures are logged, not thrown.
     */
    fun launchForegroundOperation(
        label: String,
        completionLabel: String? = null,
        contentIntent: PendingIntent? = null,
        block: suspend () -> Unit,
    ): Job = subscriptionScope.launch {
        runCatching { runForegroundOperation(label, completionLabel, contentIntent, block = block) }
            .onFailure { Log.w(TAG, "foreground operation '$label' failed: ${it.message}") }
    }

    /**
     * Fire-and-forget [sendNight] on the SDK's lifecycle scope so the transfer survives
     * the caller leaving the app (#263). Returns immediately; the durable lifecycle
     * (foreground service + finalization notification) is owned by the operation registry.
     * [onProgress] is best-effort for an in-app live view while the caller is still around;
     * [onResult] delivers the typed [SendResult] when done (also on the SDK scope, so it
     * survives the caller — a dead caller's callback is simply a no-op).
     */
    fun launchSendNight(
        toAddress: String,
        amount: BigInteger,
        onProgress: ((SendProgress) -> Unit)? = null,
        onResult: ((SendResult) -> Unit)? = null,
    ): Job = subscriptionScope.launch {
        val result = runCatching { sendNight(toAddress, amount, onProgress) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "background send failed: ${e.message}")
                SendResult.Failed(e.message ?: "Send failed")
            }
        onResult?.invoke(result)
    }

    // ── Durable protocol orchestrator (#253 + #254) ──

    private val protocolStore by lazy { ProtocolStore(config.context) }

    /**
     * Run a durable, idempotent multi-step protocol saga as ONE foreground operation
     * (#253). Each [ProtocolScope.step] is skipped when its `doneWhen` is already true
     * on-ledger, so re-running [id] (after process death, or on app resume) resumes from
     * the first not-done step with no double-submit — the chain is the journal. The inner
     * `contract.call`s coalesce under this op, so it reads as ONE operation + one
     * finalization push. [label] drives the notification.
     *
     * Returns [ProtocolResult.Completed] when every step finished, or
     * [ProtocolResult.AwaitingCounterparty] when the saga hit an unsatisfied
     * [ProtocolScope.awaitCounterparty] (the foreground service is released; re-run to
     * resume once the counterparty acts).
     */
    suspend fun runProtocol(
        id: String,
        label: String,
        block: suspend ProtocolScope.() -> Unit,
    ): ProtocolResult {
        protocolStore.markInFlight(id, label)
        var awaiting: String? = null
        operations.run(
            OperationDescriptor(OperationKind.Custom, label),
            classify = {
                OperationResult(if (awaiting == null) OperationTerminalStatus.Success else OperationTerminalStatus.Pending)
            },
        ) {
            val scope = ProtocolScopeImpl(id, PROTOCOL_CONFIRM_TIMEOUT_MS, PROTOCOL_CONFIRM_POLL_MS)
            try {
                scope.block()
            } catch (s: ProtocolSuspendSignal) {
                awaiting = s.stepName
            }
        }
        return if (awaiting == null) {
            protocolStore.clear(id)
            ProtocolResult.Completed
        } else {
            ProtocolResult.AwaitingCounterparty(awaiting!!)
        }
    }

    /** Fire-and-forget [runProtocol] on the SDK's lifecycle scope (survives the caller); #253. */
    fun launchProtocol(
        id: String,
        label: String,
        block: suspend ProtocolScope.() -> Unit,
    ): Job = subscriptionScope.launch {
        runCatching { runProtocol(id, label, block) }
            .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "protocol '$id' failed: ${it.message}") }
    }

    /**
     * Ids of protocol sagas that started but haven't completed — candidates to resume on
     * app start. The dApp re-launches each via [runProtocol] with the same id; the ledger-
     * anchored `doneWhen`s skip the already-done steps. (Saga definitions are code the dApp
     * re-provides — only ids are persisted.)
     */
    fun inFlightProtocolIds(): Set<String> = protocolStore.inFlightIds()

    /** Release all resources. */
    fun close() {
        subscriptionJob.cancel()
        subscriptionScope.cancel()
        shieldedTracker.close()
        dustTracker?.close()
        wallet.close()
        indexerClient.close()
        database.close()
        Arrays.fill(nightPrivateKey, 0.toByte())
        Arrays.fill(dustSeed, 0.toByte())
    }

    /**
     * Builder for [MidnightSdk].
     *
     * **Required:** [network], [seed].
     * **Optional:** [accountIndex] (default 0), [provingMode] (default LOCAL),
     * [proofServerUrl] (default null → network's local-dev proof server).
     */
    class Builder(private val context: Context) {
        private var network: MidnightNetwork? = null
        private var seed: ByteArray? = null
        private var accountIndex: Int = 0
        private var provingMode: ProvingMode = ProvingMode.DEFAULT
        private var proofServerUrl: String? = null
        private var dustCloudBackupFactory: ((address: String, dustSeed: ByteArray) -> DustCloudBackup)? = null
        private var appStateBackupFactory: ((seed: ByteArray) -> AppStateCloudBackup)? = null
        private var proactiveDustSync: Boolean = false

        /**
         * Keep dust pre-synced in the background (#235). When enabled, the SDK
         * runs a live tip-advance dust subscription so a transaction never waits
         * on a cold sync; progress is observable via `MidnightWallet.syncStatus`
         * (and surfaced by the foreground-service Live-Update notification). Off by
         * default — opt in for wallet-first apps. Delta only; the first cold sync is
         * still O(chain) but now runs ahead of need.
         */
        fun proactiveDustSync(enabled: Boolean = true) = apply { this.proactiveDustSync = enabled }

        /** Set the Midnight network (PREPROD, PREVIEW, UNDEPLOYED). */
        fun network(network: MidnightNetwork) = apply { this.network = network }

        /** Set the HD wallet seed (from BIP-39 mnemonic). Copied internally — caller should wipe. */
        fun seed(seed: ByteArray) = apply { this.seed = seed.copyOf() }

        /** Set the HD wallet account index (default 0). */
        fun accountIndex(index: Int) = apply { this.accountIndex = index }

        /**
         * Pick how transactions are proved. Defaults to [ProvingMode.LOCAL] —
         * on-device proving with cached keys, no network round-trip per tx.
         *
         * [ProvingMode.REMOTE] off-loads proving to a proof server reachable
         * at the URL supplied via [proofServerUrl]; useful on devices that
         * can't carry the proving-key bundle, or when the host wants to keep
         * the keys out of process memory. Set the URL **before** [build]
         * when picking REMOTE — otherwise we fall back to the network's
         * default proof-server URL (`NetworkConfig.proofServerUrl`).
         */
        fun provingMode(mode: ProvingMode) = apply { this.provingMode = mode }

        /**
         * Override the proof-server URL used when [provingMode] is REMOTE.
         * Null (default) means "use the network's default proof-server URL"
         * (`NetworkConfig.forNetwork(network).proofServerUrl`, which points
         * at a local proof server on `localhost:6300` for every network the
         * SDK supports). Pass an explicit URL to point at a hosted prover.
         */
        fun proofServerUrl(url: String?) = apply { this.proofServerUrl = url }

        /**
         * Optional cross-device dust backup. The factory is invoked once during
         * [build] with the derived address + dust seed (so the coordinator can
         * derive its encryption key); the resulting [DustCloudBackup] is wired
         * into both the dust-sync cold-start (restore) and the wallet (upload).
         * Omit it (the default) and dust backup is simply absent.
         */
        fun dustCloudBackupFactory(
            factory: (address: String, dustSeed: ByteArray) -> DustCloudBackup,
        ) = apply { this.dustCloudBackupFactory = factory }

        /**
         * Optional cross-device backup of host **app state** (≤2 KB). The factory
         * is invoked once during [build] with the wallet seed (so the coordinator
         * derives its seed-based encryption key — no per-backup biometric). The
         * resulting [AppStateCloudBackup] is wired into the wallet for silent
         * automatic backup/restore. Omit it (default) and app-state backup is
         * absent. Sibling of [dustCloudBackupFactory].
         */
        fun appStateBackupFactory(
            factory: (seed: ByteArray) -> AppStateCloudBackup,
        ) = apply { this.appStateBackupFactory = factory }

        /**
         * Build the SDK. This is a blocking operation on first launch:
         * - Derives HD wallet keys
         * - Creates database and network clients
         * - Does NOT sync dust or download proving keys — call those explicitly.
         */
        fun build(): MidnightSdk {
            val net = requireNotNull(network) { "network is required" }
            val seedBytes = requireNotNull(seed) { "seed is required" }
            require(seedBytes.size >= 32) { "seed must be at least 32 bytes" }

            val appContext = context.applicationContext
            val networkConfig = NetworkConfig.forNetwork(net)

            // ── Derive keys ──

            val hdWallet = HDWallet.fromSeed(seedBytes)
            val keys: DerivedKeys
            try {
                keys = deriveKeys(hdWallet, accountIndex, net)
            } finally {
                hdWallet.clear()
                Arrays.fill(seedBytes, 0.toByte())
                seed = null
            }

            // ── Create infrastructure ──

            val indexerClient = IndexerClientImpl(
                baseUrl = networkConfig.indexerBaseUrl,
                developmentMode = networkConfig.developmentMode,
            )

            val nodeRpcClient = NodeRpcClientImpl(
                nodeUrl = networkConfig.nodeRpcUrl,
                developmentMode = networkConfig.developmentMode,
            )

            val database = Room.databaseBuilder(
                appContext,
                UtxoDatabase::class.java,
                "kuira-sdk-utxo-database", // Separate DB name to avoid conflict with Kuira app
            ).build()

            // DustRepository and SpentDustNullifierStore share the one dust
            // DataStore (different preference keys): the repo holds the synced dust
            // state, the store holds nullifiers the wallet spent but the event
            // stream hasn't reflected — see SpentDustNullifierStore for the 115 fix.
            val dustStateDataStore = sdkDustStateDataStore(appContext)
            val dustRepository = DustRepository(
                dustDao = database.dustDao(),
                dustStateDataStore = dustStateDataStore,
                balanceCalculator = DustBalanceCalculator(),
                indexerClient = indexerClient,
            )
            val spentDustNullifierStore = SpentDustNullifierStore(dustStateDataStore)

            val provingKeyManager = ProvingKeyManager(appContext)

            // ── Unshielded UTXO tracking (NIGHT balance + registration inputs) ──
            //
            // Lifts the same pieces the Kuira app uses: UtxoManager observes the
            // Room DAO, SubscriptionManager runs the indexer's unshielded-tx
            // subscription and feeds UtxoManager. BalanceRepository wraps both
            // for the Flow-based balance API. Nothing duplicated — see
            // core/indexer/sync/SubscriptionManager.kt header for the same pattern
            // used by the parent app.
            val utxoManager = UtxoManager(database.unshieldedUtxoDao())
            val syncStateManager = SyncStateManager(appContext)
            val subscriptionManager = SubscriptionManager(
                context = appContext,
                indexerClient = indexerClient,
                utxoManager = utxoManager,
                syncStateManager = syncStateManager,
            )
            val balanceRepository = BalanceRepository(utxoManager, indexerClient)

            // Launch the long-lived unshielded subscription. The scope lives
            // for the SDK's lifetime; close() cancels it. SupervisorJob means a
            // sub-failure (e.g. transient indexer disconnect — already handled
            // with backoff inside SubscriptionManager) doesn't kill sibling work.
            val subscriptionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val subscriptionJob = subscriptionScope.launch {
                subscriptionManager.startSubscription(keys.address).collect { /* state observed inside */ }
            }

            // ── Shielded NIGHT tracker (auto-syncs in background) ──
            //
            // The SDK fully owns shielded balance freshness so dApps don't
            // have to wire ShieldedRepository themselves. ShieldedBalanceTracker
            // runs an initial replay and then re-runs it on chain-tip advance.
            // Construction is direct (no Hilt) — ShieldedRepository's Hilt
            // annotations are just metadata, the class is fine to `new` here.
            val shieldedRepository = ShieldedRepository(
                dataStore = sdkShieldedStateDataStore(appContext),
                indexerClient = indexerClient,
                networkConfig = networkConfig,
            )
            val shieldedTracker = ShieldedBalanceTracker(
                shieldedRepository = shieldedRepository,
                walletAddress = keys.address,
                zswapSeed = keys.zswapSeed,
            )
            // Launch on the same subscriptionScope so close() cancels it.
            shieldedTracker.start(subscriptionScope)

            // ── Create wallet with tip-aware dust sync + shielded tracker ──

            // Optional cross-device dust backup. One coordinator instance feeds
            // both the dust-sync cold-start (fetch/restore) and the wallet (upload).
            val dustCloudBackup = dustCloudBackupFactory?.invoke(keys.address, keys.dustSeed)

            // Optional silent app-state backup, keyed off the master seed (which is
            // reproducible from the passkey on any device → same key → decryptable).
            val appStateCloudBackup = appStateBackupFactory?.invoke(seedBytes)

            val dustSyncManager = DustSyncManager(
                dustRepository = dustRepository,
                nodeRpcClient = nodeRpcClient,
                walletAddress = keys.address,
                dustSeed = keys.dustSeed,
                cloudBackupSource = dustCloudBackup,
            )

            val wallet = MidnightWallet(
                dustSyncManager = dustSyncManager,
                dustRepository = dustRepository,
                indexerClient = indexerClient,
                nodeRpcClient = nodeRpcClient,
                balanceRepository = balanceRepository,
                shieldedTracker = shieldedTracker,
                walletAddress = keys.address,
                dustSeed = keys.dustSeed,
                provingKeysDir = provingKeyManager.keysDir.absolutePath,
                networkId = net.rustNetworkId,
                spentDustNullifierStore = spentDustNullifierStore,
                dustCloudBackup = dustCloudBackup,
                appStateCloudBackup = appStateCloudBackup,
            )

            // ── Proactive dust tracker (#235) — opt-in ──
            //
            // Keeps dust current via a live tip-advance subscription so a tx never
            // waits on a cold sync. OFF by default (existing hosts unchanged). The
            // sync itself goes through the wallet's balanceMutex (proactiveDustResync,
            // delta only — never a genesis wipe) and publishes to wallet.syncStatus.
            // Launched on subscriptionScope so close() cancels it.
            val dustTracker = if (proactiveDustSync) {
                DustBalanceTracker(
                    indexerClient = indexerClient,
                    onTipAdvance = wallet::proactiveDustResync,
                ).also { it.start(subscriptionScope) }
            } else {
                null
            }

            // ── Transaction submitter for non-balanced txs (e.g. dust registration) ──
            //
            // [provingMode] picks where ZK proofs are generated:
            //   - LOCAL  → on-device prover using cached keys; no proof-server
            //              round-trip per transaction. Default.
            //   - REMOTE → off-load to a proof server reachable at
            //              [proofServerUrl] (falls back to the network's
            //              local-dev proof server on `localhost:6300` when null).
            //
            // [ProofServerClientImpl] always gets a non-null URL even in LOCAL
            // mode because the [TransactionSubmitter] constructor requires a
            // non-null client. In LOCAL mode the client is constructed but
            // never used; the URL just has to be parseable.
            val effectiveProofServerUrl = this.proofServerUrl ?: networkConfig.proofServerUrl
            val serializer = FfiTransactionSerializer(net.rustNetworkId)
            val proofServerClient = ProofServerClientImpl(
                proofServerUrl = effectiveProofServerUrl,
                developmentMode = networkConfig.developmentMode,
            )
            // Dust-fee payment wiring (#240): submitWithFees / signAndSubmitTransfer
            // need a DustActionsBuilder + the shared dustRepository, otherwise they
            // throw "DustActionsBuilder required". FeeCalculator / DustSpendCreator
            // are stateless singletons.
            val dustActionsBuilder = DustActionsBuilder(dustRepository, FeeCalculator, DustSpendCreator)
            val transactionSubmitter = TransactionSubmitter(
                nodeRpcClient = nodeRpcClient,
                proofServerClient = proofServerClient,
                indexerClient = indexerClient,
                serializer = serializer,
                utxoManager = utxoManager,
                dustActionsBuilder = dustActionsBuilder,
                dustRepository = dustRepository,
                provingKeyManager = provingKeyManager,
                provingMode = provingMode,
            )

            // ── Operation registry + the contract-call listener that enrolls in it ──
            // Wiring the listener onto the config means every MidnightContract call
            // made through `sdk.config` runs as a tracked foreground operation — the
            // FULL pipeline incl. ZK proving — with no per-call dApp code (#261-264).
            val operations = OperationRegistry()
            val contractOperationListener = object : ContractOperationListener {
                override suspend fun <T> trackContractCall(circuitName: String, block: suspend () -> T): T =
                    operations.run(OperationDescriptor(OperationKind.ContractCall)) { block() }
            }

            // ── Create config with embedded wallet ──

            val config = MidnightConfig.Builder(appContext)
                .indexerUrl(networkConfig.indexerBaseUrl)
                .transactionBalancer(wallet)
                .operationListener(contractOperationListener)
                .networkId(net.rustNetworkId)
                .build()

            return MidnightSdk(
                config = config,
                wallet = wallet,
                coinPublicKey = keys.coinPublicKey,
                walletAddress = keys.address,
                shieldedWalletAddress = keys.shieldedAddress,
                provingKeyManager = provingKeyManager,
                dustTracker = dustTracker,
                accessKeyPublicKey = keys.accessKeyPublicKey,
                accessKeyPath = keys.accessKeyPath,
                operations = operations,
                nightPrivateKey = keys.nightPrivateKey,
                dustSeed = keys.dustSeed,
                networkId = net.rustNetworkId,
                utxoManager = utxoManager,
                transactionSubmitter = transactionSubmitter,
                subscriptionScope = subscriptionScope,
                subscriptionJob = subscriptionJob,
                database = database,
                indexerClient = indexerClient,
                shieldedTracker = shieldedTracker,
            )
        }
    }

    companion object {
        private const val TAG = "MidnightSdk"

        /**
         * Max NIGHT inputs a single fee-paying transfer can carry. The ledger's
         * time-to-dismiss budget — dominated by the dust-fee ZK proof verification —
         * rejects a transfer with more inputs (Custom error 168, `OutsideTimeToDismiss`).
         * Measured on-chain: 2 inputs accepted, 3 rejected. [sendNight] consolidates
         * coins to stay at/under this before submitting.
         */
        private const val MAX_TRANSFER_INPUTS = 2

        /**
         * Chain-independent validation for a [sendNight] request: a positive amount and
         * a recipient that's a well-formed Bech32m address on the SAME network (HRP) as
         * the sender — comparing against the sender's own HRP rejects wrong-network and
         * shielded (`mn_shield-addr_*`) addresses without hardcoding any prefix. Returns
         * the failing [SendResult], or null when the request is valid. Pure (no I/O) so
         * it's unit-testable; balance/dust checks stay in [sendNight].
         */
        internal fun validateSendRequest(
            amount: BigInteger,
            toAddress: String,
            senderAddress: String,
        ): SendResult? {
            if (amount <= BigInteger.ZERO) {
                return SendResult.Failed("Amount must be positive, got: $amount")
            }
            val senderHrp = runCatching { Bech32m.decode(senderAddress).first }.getOrNull()
            val recipientHrp = runCatching { Bech32m.decode(toAddress).first }.getOrNull()
                ?: return SendResult.InvalidAddress("Recipient address is not a valid Bech32m address.")
            if (senderHrp != null && recipientHrp != senderHrp) {
                return SendResult.InvalidAddress(
                    "Recipient is a $recipientHrp address; this wallet sends $senderHrp NIGHT.",
                )
            }
            return null
        }

        /**
         * TTL for the dust registration transaction. 30 minutes mirrors the SDK's
         * default contract-call TTL — long enough that a sluggish indexer or
         * slow user interaction (e.g. waiting for a faucet) doesn't expire it,
         * short enough that a forgotten tx doesn't linger in the mempool.
         */
        private const val REGISTRATION_TTL_MS = 30L * 60L * 1_000L

        /**
         * Per-registration wait for the new dust generation to sync from the chain
         * before the next UTXO is registered. Propagation lags finalization by ~1
         * block; a generous ceiling keeps the loop from re-registering the output it
         * just created. The loop exits this wait early as soon as the unregistered
         * set shrinks, so this is a ceiling, not a fixed cost.
         */
        private const val DUST_PROPAGATION_TIMEOUT_MS = 120_000L
        private const val DUST_POLL_INTERVAL_MS = 5_000L

        /**
         * Per-step confirmation budget for [runProtocol]: after a step's action submits, the
         * engine polls the step's `doneWhen` until the effect surfaces on the indexer (which
         * lags finalization by ~1 block) so the next step sees a consistent ledger. A ceiling,
         * not a fixed cost — the wait ends the moment `doneWhen` flips true.
         */
        private const val PROTOCOL_CONFIRM_TIMEOUT_MS = 30_000L
        private const val PROTOCOL_CONFIRM_POLL_MS = 2_000L

        /** DataStore for SDK dust state (separate from Kuira app). */
        private val Context.sdkDustDataStore by preferencesDataStore(name = "sdk_dust_state")

        internal fun sdkDustStateDataStore(context: Context) = context.sdkDustDataStore

        /** DataStore for SDK shielded (zswap) state. Separate file from the Kuira app's. */
        private val Context.sdkShieldedDataStore by preferencesDataStore(name = "sdk_shielded_state")

        internal fun sdkShieldedStateDataStore(context: Context) = context.sdkShieldedDataStore
    }
}

// ── Internal key derivation ──

internal data class DerivedKeys(
    val address: String,
    /**
     * Shielded receive address — Bech32m of `coinPublicKey || encryptionPublicKey`
     * with HRP `mn_shield-addr_<network>`. Senders use this to dispatch shielded
     * NIGHT to the wallet; only the holder of [zswapSeed] can decrypt the
     * incoming coins. Public, safe to share / display / encode in a QR.
     */
    val shieldedAddress: String,
    /**
     * 32-byte NIGHT signing key (secp256k1 private bytes) at m/44'/2400'/account'/0/0.
     * Same sensitivity envelope as [dustSeed] — held in memory for the lifetime of
     * the SDK instance so that operations like dust registration (which the chain
     * requires the NIGHT key to sign) don't need to round-trip through SeedVault.
     * Wiped in [MidnightSdk.close].
     */
    val nightPrivateKey: ByteArray,
    val dustSeed: ByteArray,
    /**
     * 32-byte zswap seed at m/44'/2400'/account'/3/0. Retained for the SDK's
     * lifetime because [ShieldedBalanceTracker] needs it to decrypt zswap
     * events every time the shielded subscription re-syncs. Wiped by
     * `ShieldedBalanceTracker.close()` (called from [MidnightSdk.close]).
     */
    val zswapSeed: ByteArray,
    val coinPublicKey: ByteArray,
    val accessKeyPublicKey: ByteArray,
    val accessKeyPath: String,
)

/**
 * Derive wallet keys from HD wallet.
 *
 * - Unshielded address: m/44'/2400'/account'/0/0 → SHA-256(x-only pubkey) → Bech32m
 * - Dust seed: m/44'/2400'/account'/2/0 → 32-byte private key
 * - Coin public key: derived via ShieldedKeyDeriver from m/44'/2400'/account'/3/0
 */
private fun hexToBytes(hex: String): ByteArray =
    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

internal fun deriveKeys(
    hdWallet: HDWallet,
    accountIndex: Int,
    network: MidnightNetwork,
): DerivedKeys {
    val account = hdWallet.selectAccount(accountIndex)

    // Unshielded address: m/44'/2400'/account'/0/0
    val nightKey = account.selectRole(MidnightKeyRole.NIGHT_EXTERNAL).deriveKeyAt(0)
    val xOnlyPubKey = nightKey.publicKeyBytes.copyOfRange(1, 33) // Skip prefix byte
    val addressData = MessageDigest.getInstance("SHA-256").digest(xOnlyPubKey)
    val address = Bech32m.encode(network.addressPrefix, addressData)
    // Hold the NIGHT signing key for the lifetime of the SDK — needed when the
    // dApp calls operations that the chain requires the NIGHT key to sign
    // (e.g. dust registration). Symmetric to the existing [dustSeed] retention.
    val nightPrivateKey = nightKey.privateKeyBytes.copyOf()

    // Dust seed: m/44'/2400'/account'/2/0
    val dustKey = account.selectRole(MidnightKeyRole.DUST).deriveKeyAt(0)
    val dustSeed = dustKey.privateKeyBytes.copyOf()

    // Coin public key: m/44'/2400'/account'/3/0 → ShieldedKeyDeriver (FFI)
    val zswapKey = account.selectRole(MidnightKeyRole.ZSWAP).deriveKeyAt(0)
    val shieldedKeys = ShieldedKeyDeriver.deriveKeys(zswapKey.privateKeyBytes)
        ?: throw IllegalStateException("ShieldedKeyDeriver.deriveKeys failed — is native library loaded?")
    val coinPublicKey = hexToBytes(shieldedKeys.coinPublicKey)
    // Shielded receive address — Bech32m-encoded (coinPubKey || encryptionPubKey)
    // with network-specific HRP `mn_shield-addr_<network>`. Lace-compatible,
    // verified by LaceCompatibilityTest in core:crypto.
    val encryptionPublicKey = hexToBytes(shieldedKeys.encryptionPublicKey)
    val shieldedAddressData = coinPublicKey + encryptionPublicKey
    val shieldedAddress = Bech32m.encode(network.shieldedAddressPrefix, shieldedAddressData)
    // Retain the zswap seed for the SDK's lifetime — ShieldedBalanceTracker
    // needs it on every shielded resync to decrypt zswap events.
    val zswapSeed = zswapKey.privateKeyBytes.copyOf()

    // Access key for sigil identity: m/44'/2400'/account'/5/0
    val accessKeyManager = AccessKeyManager(hdWallet, accountIndex)
    val accessKey = accessKeyManager.deriveDefaultAccessKey()
    val accessKeyPublicKey = accessKey.publicKeyBytes.copyOf()
    val accessKeyPath = accessKey.path

    // Individual key.clear() is internal — hdWallet.clear() handles hierarchical cleanup

    return DerivedKeys(
        address = address,
        shieldedAddress = shieldedAddress,
        nightPrivateKey = nightPrivateKey,
        dustSeed = dustSeed,
        zswapSeed = zswapSeed,
        coinPublicKey = coinPublicKey,
        accessKeyPublicKey = accessKeyPublicKey,
        accessKeyPath = accessKeyPath,
    )
}
