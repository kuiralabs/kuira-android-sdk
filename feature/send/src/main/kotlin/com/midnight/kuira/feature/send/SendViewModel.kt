package com.midnight.kuira.feature.send

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.crypto.bip32.DerivedKey
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.di.SubscriptionManagerFactory
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.core.indexer.model.TokenTypeMapper
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.sync.SyncState
import com.midnight.kuira.core.indexer.sync.SyncStateManager
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.api.FfiTransactionSerializer
import com.midnight.kuira.core.ledger.api.TransactionSerializer
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.ledger.builder.UnshieldedTransactionBuilder
import com.midnight.kuira.core.ledger.model.Intent
import com.midnight.kuira.core.ledger.model.UtxoOutput
import com.midnight.kuira.core.ledger.signer.TransactionSigner
import com.midnight.kuira.core.crypto.shielded.ZswapLocalState
import com.midnight.kuira.core.crypto.shielded.ZswapTransferBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.math.BigInteger
import javax.inject.Inject

/**
 * ViewModel for send transaction screen.
 *
 * **Responsibilities:**
 * - Load available balance
 * - Validate recipient address
 * - Build, sign, and submit transactions
 * - Handle transaction states (building, signing, submitting)
 * - Display success or error
 *
 * **Transaction Flow:**
 * 1. Build: UnshieldedTransactionBuilder creates unsigned Intent
 * 2. Sign: For each input, get signing message and sign with private key
 * 3. Submit: TransactionSubmitter handles dust fees and submission
 *
 * **MVP Note:**
 * This is a simple MVP implementation. For production, we need:
 * - Secure wallet state management (not exposing seed in UI)
 * - Biometric authentication for signing
 * - Encrypted storage for seed phrase
 * - Better error recovery
 *
 * **Usage:**
 * ```kotlin
 * @Composable
 * fun SendScreen(viewModel: SendViewModel = hiltViewModel()) {
 *     val state by viewModel.state.collectAsState()
 *
 *     when (state) {
 *         is SendUiState.Idle -> ShowSendForm()
 *         is SendUiState.Building -> ShowLoading("Building...")
 *         is SendUiState.Success -> ShowSuccess(state.txHash)
 *         // ...
 *     }
 * }
 * ```
 */
@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class SendViewModel @Inject constructor(
    private val balanceRepository: BalanceRepository,
    private val utxoManager: UtxoManager,
    private val transactionSubmitter: TransactionSubmitter,
    private val serializer: TransactionSerializer,
    private val indexerClient: IndexerClient,
    private val dustRepository: DustRepository,
    private val shieldedRepository: com.midnight.kuira.core.indexer.repository.ShieldedRepository,
    private val subscriptionManagerFactory: SubscriptionManagerFactory,
    private val syncStateManager: SyncStateManager,
    private val networkConfig: NetworkConfig
) : ViewModel() {

    /**
     * Default test recipient address for the currently selected network (MVP only).
     * Uses Alice's address for preprod, falls back to empty for unknown networks.
     */
    val defaultTestRecipient: String = DEFAULT_TEST_RECIPIENTS[networkConfig.network.addressPrefix] ?: ""

    /**
     * Default test seed phrase for the currently selected network (MVP only).
     * Uses Bob's mnemonic (Bob = sender on BalanceScreen, same mnemonic for all networks).
     */
    val defaultTestSeedPhrase: String = DEFAULT_TEST_SEED_PHRASES[networkConfig.network] ?: ""

    private val _state = MutableStateFlow<SendUiState>(SendUiState.Idle())

    // Background sync job for UTXO recovery after error 115
    private var syncJob: Job? = null
    val state: StateFlow<SendUiState> = _state.asStateFlow()

    // Cached transaction parameters for auto-retry after sync
    private data class CachedTransactionParams(
        val fromAddress: String,
        val toAddress: String,
        val amount: BigInteger,
        val seedPhrase: String
    )
    private var cachedParams: CachedTransactionParams? = null
    private var retryAttempt: Int = 0

    /**
     * Load available balance for sender address.
     *
     * **Purpose:**
     * - Show user how much they can send
     * - Enable/disable send button based on balance
     *
     * @param address Sender's address
     */
    /**
     * Send transaction — auto-detects shielded vs unshielded from recipient address.
     *
     * Routes to [sendTransaction] for `mn_addr_*` addresses or
     * [sendShieldedTransaction] for `mn_shield-addr_*` addresses.
     */
    fun send(
        fromAddress: String,
        toAddress: String,
        amount: BigInteger,
        seedPhrase: String,
    ) {
        val validation = AddressValidator.validate(toAddress)
        if (validation is AddressValidator.ValidationResult.Valid && validation.isShielded) {
            sendShieldedTransaction(fromAddress, toAddress, amount, seedPhrase)
        } else {
            sendTransaction(fromAddress, toAddress, amount, seedPhrase)
        }
    }

    fun loadBalance(address: String) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "loadBalance called with address: '$address'")

                // Validate address is not blank
                if (address.isBlank()) {
                    Log.e(TAG, "Address is blank!")
                    _state.value = SendUiState.Error("Address cannot be empty")
                    return@launch
                }

                // Validate address format
                val validation = AddressValidator.validate(address)
                if (validation is AddressValidator.ValidationResult.Invalid) {
                    Log.e(TAG, "Address validation failed: ${validation.reason}")
                    _state.value = SendUiState.Error(validation.reason)
                    return@launch
                }

                Log.d(TAG, "Address validated, querying database...")

                // Get current balances from database
                // Note: Take first emission (current snapshot) - no need for continuous updates
                val balances = balanceRepository.observeBalances(address).firstOrNull() ?: emptyList()

                Log.d(TAG, "Database returned ${balances.size} balance records")
                balances.forEach { balance ->
                    Log.d(TAG, "  TokenType: ${balance.tokenType}, Balance: ${balance.balance}, UTXOs: ${balance.utxoCount}")
                }

                // Calculate total NIGHT balance
                // Note: TokenBalance uses display symbols from TokenTypeMapper, not hex token types
                val nightBalances = balances.filter { it.tokenType == TokenTypeMapper.NIGHT_SYMBOL }
                Log.d(TAG, "Found ${nightBalances.size} NIGHT token records")

                val totalBalance = nightBalances.fold(BigInteger.ZERO) { acc, balance -> acc + balance.balance }

                Log.d(TAG, "FINAL: Total balance = $totalBalance Stars")
                _state.value = SendUiState.Idle(availableBalance = totalBalance)
            } catch (e: Exception) {
                // Log the full error for debugging
                Log.e(TAG, "Failed to load balance for address: $address", e)

                _state.value = SendUiState.Error(
                    message = getUserFriendlyError(e),
                    throwable = e
                )
            }
        }
    }

    /**
     * Send transaction.
     *
     * **Process:**
     * 1. Validate inputs
     * 2. Fetch current ledger parameters from indexer
     * 3. Build unsigned transaction
     * 4. Sign transaction with user's private key
     * 5. Submit with automatic dust fee payment
     * 6. Wait for confirmation
     *
     * **Parameters:**
     * @param fromAddress Sender's address
     * @param toAddress Recipient's address
     * @param amount Amount to send (in smallest units - Stars)
     * @param seedPhrase User's 24-word mnemonic (for signing)
     * @param isRetry If true, skip pre-send sync (we already synced in syncAndRetry).
     *        This preserves UTXOs marked SPENT from error 115.
     *
     * **Security Warning:**
     * This MVP exposes seed phrase in parameters. Production implementation
     * must use secure storage (Android Keystore) and biometric auth.
     *
     * **Note:**
     * Ledger parameters are fetched automatically from the indexer.
     * No manual input required!
     */
    fun sendTransaction(
        fromAddress: String,
        toAddress: String,
        amount: BigInteger,
        seedPhrase: String,
        isRetry: Boolean = false
    ) {
        viewModelScope.launch {
            // CRITICAL SECURITY: Derive keys once and wipe in finally block
            var seed: ByteArray? = null
            var hdWallet: HDWallet? = null
            var derivedKey: DerivedKey? = null
            var dustKey: DerivedKey? = null

            try {
                // Step 0: Quick sync to ensure UTXOs are fresh (prevents error 115)
                // SKIP if this is a retry - we already synced in syncAndRetry() with skipCacheClear=true
                // to preserve UTXOs marked SPENT from error 115
                _state.value = SendUiState.Building
                if (!isRetry) {
                    val syncedOk = quickSyncBeforeSend(fromAddress)
                    if (!syncedOk) {
                        Log.w(TAG, "Quick sync failed, proceeding anyway")
                    }
                } else {
                    Log.d(TAG, "Skipping pre-send sync (retry flow - already synced)")
                }

                // Step 1: Validate inputs

                // Validate amount
                if (amount <= BigInteger.ZERO) {
                    _state.value = SendUiState.Error("Amount must be greater than zero")
                    return@launch
                }

                // Validate recipient address
                val validationResult = AddressValidator.validate(toAddress)
                if (validationResult is AddressValidator.ValidationResult.Invalid) {
                    _state.value = SendUiState.Error(validationResult.reason)
                    return@launch
                }

                // Step 2: Derive keys from HD wallet (ONCE for all operations)
                seed = BIP39.mnemonicToSeed(seedPhrase)
                hdWallet = HDWallet.fromSeed(seed)
                derivedKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
                    .deriveKeyAt(0)

                // BIP-340 requires x-only public key (32 bytes), not compressed key (33 bytes)
                // Strip the first byte (compression prefix) to get x-only key
                val fullPublicKeyHex = derivedKey.publicKeyHex()
                val senderPublicKey = if (fullPublicKeyHex.length == 66) {
                    fullPublicKeyHex.substring(2)  // Remove first byte (prefix)
                } else {
                    fullPublicKeyHex
                }

                Log.d(TAG, "Derived public key: ${fullPublicKeyHex.length} hex chars (full), ${senderPublicKey.length} hex chars (x-only)")

                val privateKey = derivedKey.privateKeyBytes

                // Step 3: Build unsigned transaction
                Log.d(TAG, "Building transaction: from=$fromAddress, to=$toAddress, amount=$amount")
                val builder = UnshieldedTransactionBuilder(utxoManager)
                val buildResult = builder.buildTransfer(
                    from = fromAddress,
                    to = toAddress,
                    amount = amount,
                    tokenType = UtxoOutput.NATIVE_TOKEN_TYPE,
                    senderPublicKey = senderPublicKey
                )

                // Handle insufficient funds
                if (buildResult is UnshieldedTransactionBuilder.BuildResult.InsufficientFunds) {
                    Log.e(TAG, "Insufficient funds: required=${buildResult.required}, available=${buildResult.available}")
                    _state.value = SendUiState.Error(
                        message = "Insufficient funds. Need ${buildResult.required}, have ${buildResult.available}"
                    )
                    return@launch
                }

                val success = buildResult as UnshieldedTransactionBuilder.BuildResult.Success
                val unsignedIntent = success.intent
                Log.d(TAG, "Transaction built successfully with ${success.lockedUtxos.size} UTXOs")

                // Step 4: Fetch ledger parameters from indexer
                Log.d(TAG, "Fetching ledger parameters from indexer")
                val block = indexerClient.getCurrentBlockWithParams()
                val ledgerParamsHex = block.ledgerParameters
                    ?: throw IllegalStateException("Current block missing ledger parameters")
                Log.d(TAG, "Got ledger parameters: ${ledgerParamsHex.length} hex chars")

                // Step 5: Sign transaction
                _state.value = SendUiState.Signing
                Log.d(TAG, "Signing transaction with ${unsignedIntent.guaranteedUnshieldedOffer?.inputs?.size ?: 0} inputs")

                val signedIntent = signIntent(unsignedIntent, privateKey)
                Log.d(TAG, "Transaction signed successfully")

                // Step 5.5: Check dust state (for fee payment)
                // Note: Dust must be synced once after registration in Lace
                // We don't sync during transaction because it takes 5-10 minutes
                Log.d(TAG, "Checking dust state...")
                val hasCachedDust = dustRepository.hasCachedState(fromAddress)

                if (!hasCachedDust) {
                    Log.d(TAG, "⚠️  No cached dust state found - first-time sync required")

                    // For MVP: Sync dust now (will take 5-10 minutes)
                    Log.d(TAG, "Starting one-time dust sync (this will take 5-10 minutes)...")
                    _state.value = SendUiState.Building // Show "Building" state during sync

                    val dustKey = hdWallet
                        .selectAccount(0)
                        .selectRole(MidnightKeyRole.DUST)
                        .deriveKeyAt(0)

                    val dustSeed = dustKey.privateKeyBytes
                    try {
                        val dustSynced = dustRepository.syncFromBlockchain(
                            address = fromAddress,
                            dustSeed = dustSeed,
                            maxBlocks = 100
                        )

                        if (!dustSynced) {
                            Log.e(TAG, "No dust found on blockchain - register dust in Lace first")
                            _state.value = SendUiState.Error(
                                message = "No dust registered. Please register dust in Lace wallet first."
                            )
                            return@launch
                        }

                        Log.d(TAG, "✅ Dust synced successfully (cached for future transactions)")

                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to sync dust", e)
                        _state.value = SendUiState.Error(
                            message = "Failed to sync dust: ${e.message}"
                        )
                        return@launch
                    } finally {
                        // Wipe dust seed from memory
                        java.util.Arrays.fill(dustSeed, 0.toByte())
                        dustKey.clear()
                    }
                } else {
                    Log.d(TAG, "✅ Using cached dust state (fast)")
                }

                // Step 6: Submit transaction WITH dust fees (Phase 2E)
                _state.value = SendUiState.Submitting
                Log.d(TAG, "Submitting transaction to network WITH dust fees")

                // Derive dust seed for fee payment
                dustKey = hdWallet!!
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.DUST)
                    .deriveKeyAt(0)
                val dustSeed = dustKey!!.privateKeyBytes

                val result = transactionSubmitter.submitWithFees(
                    signedIntent = signedIntent,
                    ledgerParamsHex = ledgerParamsHex,
                    fromAddress = fromAddress,
                    seed = dustSeed, // Use dust seed, not root seed
                    timeoutMs = 60_000L // 60 seconds
                )

                // Step 7: Handle result
                when (result) {
                    is TransactionSubmitter.SubmissionResult.Success -> {
                        Log.d(TAG, "Transaction submitted successfully: txHash=${result.txHash}")
                        clearRetryState()  // Clear retry state on success
                        _state.value = SendUiState.Success(
                            txHash = result.txHash,
                            recipientAddress = toAddress,
                            amountSent = amount
                        )
                    }
                    is TransactionSubmitter.SubmissionResult.Failed -> {
                        Log.e(TAG, "Transaction failed: ${result.reason}")
                        _state.value = SendUiState.Error(
                            message = "Transaction failed: ${result.reason}"
                        )
                    }
                    is TransactionSubmitter.SubmissionResult.Pending -> {
                        Log.e(TAG, "Transaction timeout: ${result.reason}")
                        _state.value = SendUiState.Error(
                            message = "Transaction timeout: ${result.reason}"
                        )
                    }
                    is TransactionSubmitter.SubmissionResult.StaleUtxo -> {
                        Log.w(TAG, "⚠️ Stale UTXO detected - UTXO was already spent on blockchain")
                        Log.w(TAG, "   Failed UTXO IDs (intentHash:outputNo): ${result.failedUtxoIds}")

                        // Parse intentHash:outputNo format and mark UTXOs as SPENT
                        // failedUtxoIds are in "intentHash:outputNo" format (blockchain format)
                        val utxoIntentPairs = result.failedUtxoIds.mapNotNull { id ->
                            val parts = id.split(":")
                            if (parts.size == 2) {
                                val intentHash = parts[0]
                                val outputNo = parts[1].toIntOrNull()
                                if (outputNo != null) intentHash to outputNo else null
                            } else null
                        }
                        // Mark as SPENT with spentByLocalTx=false because OUR transaction was REJECTED
                        // (error 115 = node says UTXO already spent, but NOT by us!)
                        // This allows healing to restore them if indexer shows they're actually available.
                        Log.d(TAG, "Marking ${utxoIntentPairs.size} stale UTXOs as SPENT (external, not our tx)")
                        utxoManager.markUtxosAsSpentByIntent(utxoIntentPairs, spentByLocalTx = false)

                        // Quick sync and auto-retry (Option C UX)
                        // This will sync fresh UTXOs and automatically retry the transaction
                        Log.d(TAG, "Starting auto-recovery: sync + retry...")
                        syncAndRetry(CachedTransactionParams(
                            fromAddress = fromAddress,
                            toAddress = toAddress,
                            amount = amount,
                            seedPhrase = seedPhrase
                        ))
                    }
                }


            } catch (e: Exception) {
                // CRITICAL: Log the full error for debugging
                Log.e(TAG, "Transaction failed with exception", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Exception message: ${e.message}")
                e.printStackTrace()

                _state.value = when (e) {
                    is IllegalArgumentException -> {
                        Log.e(TAG, "IllegalArgumentException in transaction: ${e.message}")
                        SendUiState.Error(
                            message = "Invalid input: ${e.message}",
                            throwable = e
                        )
                    }
                    is IllegalStateException -> {
                        Log.e(TAG, "IllegalStateException in transaction: ${e.message}")
                        SendUiState.Error(
                            message = "Transaction error: ${e.message}",
                            throwable = e
                        )
                    }
                    else -> {
                        Log.e(TAG, "Unexpected error in transaction: ${e.message}")
                        SendUiState.Error(
                            message = "Unexpected error: ${e.message}",
                            throwable = e
                        )
                    }
                }
            } finally {
                // CRITICAL SECURITY: Wipe all key material from memory
                seed?.let { java.util.Arrays.fill(it, 0.toByte()) }
                derivedKey?.clear()
                dustKey?.clear()
                hdWallet?.clear()
            }
        }
    }

    /**
     * Send a shielded transaction.
     *
     * **Process:**
     * 1. Load ZswapLocalState (from ShieldedRepository cache)
     * 2. Derive zswap keys (m/44'/2400'/0'/3/0)
     * 3. Parse recipient's shielded address into coin_pk + enc_pk
     * 4. Build transfer via ZswapTransferBuilder
     * 5. Prove → seal → submit via TransactionSubmitter
     *
     * @param toAddress Recipient's shielded address (mn_shield-addr_...)
     * @param amount Amount to send (micro-units)
     * @param seedPhrase User's 24-word mnemonic
     */
    fun sendShieldedTransaction(
        fromAddress: String,
        toAddress: String,
        amount: BigInteger,
        seedPhrase: String,
    ) {
        viewModelScope.launch {
            var seed: ByteArray? = null
            var hdWallet: HDWallet? = null

            try {
                _state.value = SendUiState.Building

                // Validate shielded address
                val validation = AddressValidator.validate(toAddress)
                if (validation !is AddressValidator.ValidationResult.Valid || !validation.isShielded) {
                    _state.value = SendUiState.Error("Invalid shielded address")
                    return@launch
                }

                if (amount <= BigInteger.ZERO) {
                    _state.value = SendUiState.Error("Amount must be greater than zero")
                    return@launch
                }

                // Extract coin_pk (first 32 bytes) and enc_pk (last 32 bytes)
                val payload = validation.publicKey
                val coinPkHex = payload.take(32).toByteArray()
                    .joinToString("") { "%02x".format(it) }
                val encPkHex = payload.drop(32).toByteArray()
                    .joinToString("") { "%02x".format(it) }

                // Derive zswap seed
                seed = BIP39.mnemonicToSeed(seedPhrase)
                hdWallet = HDWallet.fromSeed(seed)
                val zswapKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)
                val zswapSeed = zswapKey.privateKeyBytes

                try {
                    // Load shielded state from cache (synced by BalanceViewModel)
                    var state = shieldedRepository.loadState(fromAddress)

                    // If no cached state, sync from blockchain first
                    if (state == null) {
                        Log.d(TAG, "No cached shielded state — syncing from blockchain")
                        val synced = shieldedRepository.syncFromBlockchain(fromAddress, zswapSeed)
                        if (!synced) {
                            _state.value = SendUiState.Error("No shielded coins found. Receive shielded NIGHT first.")
                            return@launch
                        }
                        state = shieldedRepository.loadState(fromAddress)
                    }

                    if (state == null) {
                        _state.value = SendUiState.Error("Failed to load shielded state")
                        return@launch
                    }

                    state.use { activeState ->
                        val nightTokenType = "0".repeat(64) // all zeros for NIGHT

                        val transferResult = ZswapTransferBuilder.buildTransfer(
                            state = activeState,
                            seed = zswapSeed,
                            recipientCoinPk = coinPkHex,
                            recipientEncPk = encPkHex,
                            tokenType = nightTokenType,
                            amount = amount,
                            networkId = networkConfig.network.name.lowercase(),
                            ttlMs = System.currentTimeMillis() + 3600_000,
                        )

                        if (transferResult == null) {
                            _state.value = SendUiState.Error(
                                "Insufficient shielded balance or transfer build failed"
                            )
                            return@launch
                        }

                        // Prove + seal + submit (proof takes 2-5 minutes for shielded)
                        _state.value = SendUiState.Proving

                        val result = transactionSubmitter.submitPrebuiltTransaction(
                            unprovenTxHex = transferResult.transactionHex,
                            timeoutMs = 360_000L // 6 min timeout (proof ~2-5 min)
                        )

                        when (result) {
                            is TransactionSubmitter.SubmissionResult.Success -> {
                                Log.i(TAG, "Shielded transfer finalized: ${result.txHash}")
                                _state.value = SendUiState.Success(
                                    txHash = result.txHash,
                                    recipientAddress = toAddress,
                                    amountSent = amount,
                                )
                            }
                            is TransactionSubmitter.SubmissionResult.Failed -> {
                                _state.value = SendUiState.Error("Shielded transfer failed: ${result.reason}")
                            }
                            is TransactionSubmitter.SubmissionResult.Pending -> {
                                _state.value = SendUiState.Error("Shielded transfer pending: ${result.reason}")
                            }
                            is TransactionSubmitter.SubmissionResult.StaleUtxo -> {
                                _state.value = SendUiState.Error("Stale coins detected. Please sync and retry.")
                            }
                        }
                    }

                } finally {
                    zswapKey.clear()
                    java.util.Arrays.fill(zswapSeed, 0.toByte())
                }

            } catch (e: Exception) {
                Log.e(TAG, "Shielded transaction failed", e)
                _state.value = SendUiState.Error("Shielded transfer error: ${e.message}")
            } finally {
                seed?.let { java.util.Arrays.fill(it, 0.toByte()) }
                hdWallet?.clear()
            }
        }
    }

    /**
     * Sign an unsigned Intent with user's private key.
     *
     * **Process:**
     * 1. For each input, get signing message
     * 2. Sign each message with private key
     * 3. Create signed Intent with signatures
     *
     * **Signature Requirements:**
     * - One signature per input (BIP-340 Schnorr, 64 bytes)
     * - Signatures must match sorted input order
     *
     * @param intent Unsigned Intent
     * @param privateKey User's BIP-32 private key
     * @return Signed Intent
     */
    private suspend fun signIntent(intent: Intent, privateKey: ByteArray): Intent {
        val offer = intent.guaranteedUnshieldedOffer
            ?: throw IllegalStateException("No guaranteed offer in Intent")

        // Sign each input
        val signatures = offer.inputs.mapIndexed { index, _ ->
            // Get signing message for this input
            // Note: Cast to FfiTransactionSerializer to access signing message method
            val ffiSerializer = serializer as? FfiTransactionSerializer
                ?: throw IllegalStateException("Serializer must be FfiTransactionSerializer")

            val signingMessageHex = ffiSerializer.getSigningMessageForInput(
                inputs = offer.inputs,
                outputs = offer.outputs,
                inputIndex = index,
                ttl = intent.ttl
            ) ?: throw IllegalStateException("Failed to generate signing message for input $index")

            // Convert hex to bytes
            val messageToSign = signingMessageHex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()

            // Sign with Schnorr BIP-340
            val signature = TransactionSigner.signData(privateKey, messageToSign)
                ?: throw IllegalStateException("Failed to sign input $index")

            signature
        }

        // Create signed offer
        val signedOffer = offer.copy(signatures = signatures)

        // Create signed intent
        return intent.copy(guaranteedUnshieldedOffer = signedOffer)
    }

    /**
     * Convert exceptions to user-friendly error messages.
     *
     * Follows the same pattern as BalanceViewModel for consistency.
     */
    private fun getUserFriendlyError(throwable: Throwable): String {
        return when {
            // Database migration errors (common during development)
            throwable.message?.contains("migration", ignoreCase = true) == true ||
            throwable.message?.contains("RoomDatabase", ignoreCase = true) == true ->
                "Database schema changed. Please clear app data in Settings > Apps > Kuira or reinstall the app."

            // Network errors
            throwable.message?.contains("network", ignoreCase = true) == true ->
                "Network error. Please check your connection."

            // Timeout errors
            throwable.message?.contains("timeout", ignoreCase = true) == true ->
                "Request timed out. Please try again."

            // General database errors
            throwable.message?.contains("database", ignoreCase = true) == true ->
                "Database error. Please restart the app."

            // Validation errors
            throwable is IllegalArgumentException ->
                "Invalid input: ${throwable.message}"

            // Transaction errors
            throwable is IllegalStateException ->
                "Transaction error: ${throwable.message}"

            // Fallback
            else ->
                "Failed to load balance: ${throwable.message}"
        }
    }

    /**
     * Reset to idle state and reload balance.
     *
     * Call this after successful transaction or error to allow sending another.
     * Reloads balance to ensure fresh data after any state changes.
     *
     * @param address The sender's address to reload balance for
     */
    fun reset(address: String) {
        loadBalance(address)
    }

    /**
     * Quick sync before sending to ensure UTXOs are fresh.
     *
     * This prevents error 115 (stale UTXO) by syncing before building the transaction.
     * Timeout: 10 seconds (should be fast for incremental sync).
     *
     * @param address Address to sync UTXOs for
     * @return true if sync completed, false if timed out or failed
     */
    private suspend fun quickSyncBeforeSend(address: String): Boolean {
        return try {
            val subscriptionManager = subscriptionManagerFactory.create()

            val result = withTimeoutOrNull(PRE_SEND_SYNC_TIMEOUT_MS) {
                subscriptionManager.startSubscription(address)
                    .first { state -> state is SyncState.Synced }
                true
            }

            if (result == true) {
                Log.d(TAG, "Pre-send sync complete")
                true
            } else {
                Log.w(TAG, "Pre-send sync timed out")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pre-send sync failed", e)
            false
        }
    }

    /**
     * Sync latest UTXOs and auto-retry the transaction.
     *
     * Called after error 115 (stale UTXO) to:
     * 1. Quick sync fresh UTXOs from blockchain
     * 2. Auto-retry the transaction with fresh data
     * 3. If still fails, retry up to MAX_AUTO_RETRIES times
     * 4. If max retries exceeded, show informative error
     *
     * **Option C UX:** Seamless recovery for most cases, only show error if truly stuck.
     *
     * @param params Transaction parameters (fromAddress, toAddress, amount, seedPhrase)
     */
    private fun syncAndRetry(params: CachedTransactionParams) {
        // Cancel previous sync if running
        syncJob?.cancel()

        // Cache params for potential further retries
        cachedParams = params

        // Increment retry attempt
        retryAttempt++

        // Check if we've exceeded max retries
        if (retryAttempt > SendUiState.SyncingAndRetrying.MAX_AUTO_RETRIES) {
            Log.w(TAG, "Max auto-retries ($retryAttempt) exceeded - showing error to user")
            retryAttempt = 0  // Reset for next manual attempt
            cachedParams = null
            _state.value = SendUiState.Error(
                message = "Transaction failed after multiple attempts. Your wallet may need a full sync. " +
                        "Please close and reopen the app, then try again."
            )
            return
        }

        // Show syncing state with retry info
        _state.value = SendUiState.SyncingAndRetrying(
            retryAttempt = retryAttempt,
            maxRetries = SendUiState.SyncingAndRetrying.MAX_AUTO_RETRIES
        )

        syncJob = viewModelScope.launch {
            try {
                Log.d(TAG, "Auto-retry attempt $retryAttempt/${SendUiState.SyncingAndRetrying.MAX_AUTO_RETRIES}")
                Log.d(TAG, "Quick sync: Creating subscription manager")
                val subscriptionManager = subscriptionManagerFactory.create()

                Log.d(TAG, "Quick sync: Starting subscription for ${params.fromAddress}")

                // Use timeout to prevent getting stuck
                // IMPORTANT: skipCacheClear=true preserves UTXOs marked as SPENT from error 115
                // Without this, the sync would clear all UTXOs and rebuild from indexer,
                // which would restore the stale UTXO that the node already rejected.
                val synced = withTimeoutOrNull(QUICK_SYNC_TIMEOUT_MS) {
                    subscriptionManager.startSubscription(params.fromAddress, skipCacheClear = true)
                        .first { state ->
                            Log.d(TAG, "Quick sync state: $state")
                            state is SyncState.Synced
                        }
                    Log.d(TAG, "Quick sync: Synced!")
                    true
                }

                if (synced != null) {
                    Log.d(TAG, "Quick sync completed, auto-retrying transaction...")
                } else {
                    Log.w(TAG, "Quick sync timed out after ${QUICK_SYNC_TIMEOUT_MS}ms, retrying anyway...")
                }

                // Auto-retry the transaction with fresh UTXOs
                // Note: sendTransaction will handle further errors, including triggering
                // another syncAndRetry if we get error 115 again
                // IMPORTANT: isRetry=true skips pre-send sync to preserve SPENT UTXOs from error 115
                Log.d(TAG, "Auto-retrying transaction (attempt $retryAttempt)")
                sendTransaction(
                    fromAddress = params.fromAddress,
                    toAddress = params.toAddress,
                    amount = params.amount,
                    seedPhrase = params.seedPhrase,
                    isRetry = true
                )

                // If we get here without error, the retry succeeded
                // Clear cached params (sendTransaction will set success state)

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Normal cancellation, ignore
                Log.d(TAG, "Quick sync cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Quick sync or retry failed", e)
                // Show error - user can manually retry
                retryAttempt = 0
                cachedParams = null
                _state.value = SendUiState.Error(
                    message = "Transaction failed: ${e.message ?: "Unknown error"}. Please try again."
                )
            }
        }
    }

    /**
     * Reset retry state on successful transaction.
     * Called when transaction succeeds to clear cached params and retry counter.
     */
    private fun clearRetryState() {
        retryAttempt = 0
        cachedParams = null
    }

    override fun onCleared() {
        super.onCleared()
        syncJob?.cancel()
    }

    private companion object {
        private const val TAG = "SendViewModel"
        private const val PRE_SEND_SYNC_TIMEOUT_MS = 10_000L  // 10 seconds - fast sync before send
        private const val QUICK_SYNC_TIMEOUT_MS = 10_000L  // 10 seconds - recovery sync after error (same as pre-send)

        // MVP test recipients per network — Bob (from CLI: mn wallet info bob)
        val DEFAULT_TEST_RECIPIENTS = mapOf(
            "mn_addr_preprod" to "mn_addr_preprod1z7qzgsxnqg2h5pc3t7l84s4q7swqfxqcjxqc5nawq93f8r832fwsev7kky",
            "mn_addr_undeployed" to "mn_addr_undeployed1z7qzgsxnqg2h5pc3t7l84s4q7swqfxqcjxqc5nawq93f8r832fwsrhyg84",
            "mn_addr_preview" to "mn_addr_preview1z7qzgsxnqg2h5pc3t7l84s4q7swqfxqcjxqc5nawq93f8r832fwsedqx9e"
        )
        // MVP test seed phrases per network — Alice is the sender (from CLI: mn wallet info alice)
        val DEFAULT_TEST_SEED_PHRASES = mapOf(
            MidnightNetwork.PREPROD to "shoot swallow grunt cement glory exclude forward boring stool skirt portion swallow slow light town ripple obvious carry unfair beauty world small add own",
            MidnightNetwork.UNDEPLOYED to "shoot swallow grunt cement glory exclude forward boring stool skirt portion swallow slow light town ripple obvious carry unfair beauty world small add own"
        )
    }
}
