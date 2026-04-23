package com.midnight.kuira.feature.send

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletAddressCache
import com.midnight.kuira.core.auth.WalletAddresses
import com.midnight.kuira.core.crypto.bip32.DerivedKey
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.indexer.api.IndexerClient
import com.midnight.kuira.core.indexer.di.SubscriptionManagerFactory
import com.midnight.kuira.core.network.NetworkConfig
import com.midnight.kuira.core.network.NetworkRepository
import com.midnight.kuira.core.indexer.model.TokenTypeMapper
import com.midnight.kuira.core.indexer.repository.BalanceRepository
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.indexer.repository.ShieldedRepository
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
import com.midnight.kuira.core.crypto.proving.ProvingKeyManager
import com.midnight.kuira.core.crypto.proving.ProvingMode
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
 * - Build, sign, and submit transactions (unshielded + shielded)
 * - Handle transaction states (building, signing, submitting)
 * - Display success or error
 *
 * **Transaction Flow:**
 * 1. Build: UnshieldedTransactionBuilder / ZswapTransferBuilder creates unsigned tx
 * 2. Sign: For each input, get signing message and sign with private key
 * 3. Submit: TransactionSubmitter handles dust fees and submission
 *
 * **Security:** The seed is never held in the UI. It is loaded on demand
 * from [SeedVault] via a biometric prompt, used for key derivation, and
 * wiped in the `finally` block of every send function. See [SeedVault] and
 * [HDWallet] for the key-material lifecycle.
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

/**
 * Send mode — determines both the FROM address (from [WalletAddresses]) and
 * which protocol path to run.
 *
 * Unlike the old auto-detect router that switched on `toAddress.isShielded`,
 * this is the authoritative mode selected by the user. Recipient address must
 * match the selected mode or we reject the send with a validation error.
 */
enum class SendMode {
    UNSHIELDED,
    SHIELDED,
}

@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class SendViewModel @Inject constructor(
    private val balanceRepository: BalanceRepository,
    private val utxoManager: UtxoManager,
    private val transactionSubmitter: TransactionSubmitter,
    private val serializer: TransactionSerializer,
    private val indexerClient: IndexerClient,
    private val dustRepository: DustRepository,
    private val shieldedRepository: ShieldedRepository,
    private val provingKeyManager: ProvingKeyManager,
    private val subscriptionManagerFactory: SubscriptionManagerFactory,
    private val syncStateManager: SyncStateManager,
    private val networkRepository: NetworkRepository,
    private val seedVault: SeedVault,
    private val walletAddressCache: WalletAddressCache,
) : ViewModel() {

    /**
     * The wallet's cached addresses (unshielded + shielded), loaded from
     * [WalletAddressCache] at ViewModel creation. Null while the cache is
     * being read or if onboarding hasn't run yet.
     *
     * UI observes this to populate the FROM field based on the active
     * [SendMode]. Addresses are public, so no biometric is needed.
     */
    // Current network — read from repository at construction time.
    // Send gets a fresh ViewModel on each navigation, so this is always
    // the current network (not a stale startup value).
    private val currentNetwork = networkRepository.getSelectedNetworkBlocking()
    private val currentNetworkConfig = NetworkConfig.forNetwork(currentNetwork)

    private val _walletAddresses = MutableStateFlow<WalletAddresses?>(null)
    val walletAddresses: StateFlow<WalletAddresses?> = _walletAddresses.asStateFlow()

    init {
        viewModelScope.launch {
            _walletAddresses.value = walletAddressCache.load(currentNetwork)
        }
    }

    /**
     * Default test recipient — Bob's unshielded address for the current network.
     * Pre-filled into the Send screen so you can test end-to-end without
     * typing a 90-character bech32 address.
     */
    val defaultTestRecipient: String = BOB_UNSHIELDED_ADDRESSES[currentNetwork.addressPrefix] ?: ""

    /** Default test recipient — Bob's shielded address for the current network. */
    val defaultShieldedRecipient: String = BOB_SHIELDED_ADDRESSES[currentNetwork.addressPrefix] ?: ""

    /** Whether proving mode is set to LOCAL — observable for Compose recomposition. */
    private val _isLocalProving = MutableStateFlow(transactionSubmitter.provingMode == ProvingMode.LOCAL)
    val isLocalProvingEnabled: StateFlow<Boolean> = _isLocalProving.asStateFlow()

    /** Proof server URL for display when in remote mode. */
    val proofServerUrl: String = currentNetworkConfig.proofServerUrl

    /** Toggle between local and remote proving. */
    fun toggleProvingMode() {
        val newMode = if (transactionSubmitter.provingMode == ProvingMode.LOCAL) {
            ProvingMode.REMOTE
        } else {
            ProvingMode.LOCAL
        }
        transactionSubmitter.provingMode = newMode
        _isLocalProving.value = newMode == ProvingMode.LOCAL
        Log.d(TAG, "Proving mode changed to: $newMode")
    }

    private val _state = MutableStateFlow<SendUiState>(SendUiState.Idle())

    // Background sync job for UTXO recovery after error 115
    private var syncJob: Job? = null
    val state: StateFlow<SendUiState> = _state.asStateFlow()

    // Cached transaction parameters for auto-retry after sync. Activity is held
    // weakly via FragmentActivity reference — only valid for the duration of a
    // single send flow (config changes during retry will fail loudly).
    private data class CachedTransactionParams(
        val activity: FragmentActivity,
        val fromAddress: String,
        val toAddress: String,
        val amount: BigInteger,
    )
    private var cachedParams: CachedTransactionParams? = null
    private var retryAttempt: Int = 0

    /**
     * Send transaction — uses the explicit [mode] selected in the UI as the
     * authoritative routing signal. The recipient address is validated to
     * match the mode; a mismatch produces an immediate error instead of
     * silently routing to the wrong protocol path.
     *
     * The FROM address is resolved from [walletAddresses] based on the mode:
     * - [SendMode.UNSHIELDED] → [WalletAddresses.unshieldedAddress]
     * - [SendMode.SHIELDED]   → [WalletAddresses.shieldedAddress]
     *
     * The seed is loaded on demand from [SeedVault] via biometric prompt —
     * the caller no longer passes a mnemonic.
     *
     * @param activity The hosting FragmentActivity (required by BiometricPrompt)
     * @param mode The send mode (authoritative — from UI toggle)
     * @param toAddress The recipient address (must match [mode])
     * @param amount Amount to send in smallest units (Stars)
     */
    fun send(
        activity: FragmentActivity,
        mode: SendMode,
        toAddress: String,
        amount: BigInteger,
    ) {
        // Validate recipient prefix matches the selected mode
        val validation = AddressValidator.validate(toAddress)
        if (validation is AddressValidator.ValidationResult.Invalid) {
            _state.value = SendUiState.Error(validation.reason)
            return
        }
        val isShieldedRecipient = (validation as AddressValidator.ValidationResult.Valid).isShielded
        when (mode) {
            SendMode.UNSHIELDED -> {
                if (isShieldedRecipient) {
                    _state.value = SendUiState.Error(
                        "Unshielded send requires an unshielded recipient " +
                            "(mn_addr_…). Got a shielded address (mn_shield-addr_…)."
                    )
                    return
                }
            }
            SendMode.SHIELDED -> {
                if (!isShieldedRecipient) {
                    _state.value = SendUiState.Error(
                        "Shielded send requires a shielded recipient " +
                            "(mn_shield-addr_…). Got an unshielded address (mn_addr_…)."
                    )
                    return
                }
            }
        }

        // Resolve FROM address from the wallet cache
        val addresses = _walletAddresses.value
        if (addresses == null) {
            _state.value = SendUiState.Error(
                "Wallet address not available. Please wait or re-onboard."
            )
            return
        }
        val fromAddress = when (mode) {
            SendMode.UNSHIELDED -> addresses.unshieldedAddress
            SendMode.SHIELDED -> addresses.shieldedAddress
                ?: run {
                    _state.value = SendUiState.Error(
                        "No shielded address in wallet. Cannot send shielded."
                    )
                    return
                }
        }

        when (mode) {
            SendMode.UNSHIELDED -> sendTransaction(activity, fromAddress, toAddress, amount)
            SendMode.SHIELDED -> sendShieldedTransaction(
                activity = activity,
                fromAddress = fromAddress,
                // Dust and shielded state are wallet-level — always key them by the
                // unshielded (anchor) address so BalanceViewModel and the unshielded
                // send path share the same cache entries. Otherwise a successful
                // shielded send leaves stale dust state under the unshielded key,
                // causing InvalidDustSpendProof (error 170) on the next send.
                walletAnchorAddress = addresses.unshieldedAddress,
                toAddress = toAddress,
                amount = amount,
            )
        }
    }

    /**
     * Load available balance for the sender address.
     *
     * **Purpose:**
     * - Show user how much they can send
     * - Enable/disable send button based on balance
     *
     * @param address Sender's address
     */
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
     * 1. Load seed from SeedVault (triggers biometric prompt)
     * 2. Validate inputs
     * 3. Fetch current ledger parameters from indexer
     * 4. Build unsigned transaction
     * 5. Sign transaction with user's private key
     * 6. Submit with automatic dust fee payment
     * 7. Wait for confirmation
     *
     * @param activity The hosting FragmentActivity (required by BiometricPrompt)
     * @param fromAddress Sender's address
     * @param toAddress Recipient's address
     * @param amount Amount to send (in smallest units - Stars)
     * @param isRetry If true, skip pre-send sync (we already synced in syncAndRetry).
     *        This preserves UTXOs marked SPENT from error 115.
     */
    fun sendTransaction(
        activity: FragmentActivity,
        fromAddress: String,
        toAddress: String,
        amount: BigInteger,
        isRetry: Boolean = false,
    ) {
        viewModelScope.launch {
            var plaintextSeed: PlaintextSeed? = null
            var hdWallet: HDWallet? = null
            var derivedKey: DerivedKey? = null
            var dustKey: DerivedKey? = null

            try {
                // Step 0: Validate cheap things FIRST so we don't show a biometric
                // prompt for an obviously broken request.
                if (amount <= BigInteger.ZERO) {
                    _state.value = SendUiState.Error("Amount must be greater than zero")
                    return@launch
                }

                val validationResult = AddressValidator.validate(toAddress)
                if (validationResult is AddressValidator.ValidationResult.Invalid) {
                    _state.value = SendUiState.Error(validationResult.reason)
                    return@launch
                }

                // Step 1: Load encrypted seed via biometric prompt
                _state.value = SendUiState.Building
                plaintextSeed = seedVault.loadSeed(activity)
                hdWallet = HDWallet.fromSeed(plaintextSeed.bip39Seed)

                // Step 1b: Ensure proving keys are downloaded if local proving is
                // selected. Even unshielded sends need ZK proving for the dust
                // fee binding, so this check applies to both send paths.
                ensureProvingKeysDownloaded()

                // Step 2: Quick sync to ensure UTXOs are fresh (prevents error 115).
                // Skip if this is a retry — syncAndRetry() has already done an
                // incremental sync that preserved the SPENT markers we need.
                if (!isRetry) {
                    val syncedOk = quickSyncBeforeSend(fromAddress)
                    if (!syncedOk) {
                        Log.w(TAG, "Quick sync failed, proceeding anyway")
                    }
                } else {
                    Log.d(TAG, "Skipping pre-send sync (retry flow - already synced)")
                }

                // Step 3: Derive signing key from HD wallet (already loaded above)
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

                // Step 5.5: Force a fresh dust sync for fee payment. See
                // [forceFreshDustSync] for the rationale (avoids an error-170
                // feedback loop after any previously rejected submission).
                _state.value = SendUiState.Building // "Building" covers the sync
                val dustSynced = try {
                    forceFreshDustSync(fromAddress, hdWallet)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync dust", e)
                    _state.value = SendUiState.Error(
                        message = "Failed to sync dust: ${e.message}"
                    )
                    return@launch
                }
                if (!dustSynced) {
                    Log.e(TAG, "No dust found on blockchain - register dust in Lace first")
                    _state.value = SendUiState.Error(
                        message = "No dust registered. Please register dust in Lace wallet first."
                    )
                    return@launch
                }
                Log.d(TAG, "Dust synced successfully")

                // Step 6: Submit transaction WITH dust fees (Phase 2E)
                _state.value = SendUiState.Submitting
                Log.d(TAG, "Submitting transaction to network WITH dust fees")

                // Derive dust seed for fee payment
                dustKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.DUST)
                    .deriveKeyAt(0)
                val dustSeed = dustKey.privateKeyBytes

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
                        // This will sync fresh UTXOs and automatically retry the transaction.
                        // Note: this triggers a second biometric prompt for the retry.
                        Log.d(TAG, "Starting auto-recovery: sync + retry...")
                        syncAndRetry(CachedTransactionParams(
                            activity = activity,
                            fromAddress = fromAddress,
                            toAddress = toAddress,
                            amount = amount,
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
                plaintextSeed?.wipe()
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
     * 1. Load seed from SeedVault (biometric prompt)
     * 2. Load ZswapLocalState (from ShieldedRepository cache)
     * 3. Derive zswap keys (m/44'/2400'/0'/3/0)
     * 4. Parse recipient's shielded address into coin_pk + enc_pk
     * 5. Build transfer via ZswapTransferBuilder
     * 6. Prove → seal → submit via TransactionSubmitter
     *
     * @param activity The hosting FragmentActivity (required by BiometricPrompt)
     * @param fromAddress Sender's shielded address (mn_shield-addr_...) — for
     *   display/logging only. Shielded state keys are derived from the zswap
     *   seed, not this string.
     * @param walletAnchorAddress Sender's UNSHIELDED address. Used as the
     *   cache key for both `dustRepository` and `shieldedRepository` so that
     *   unshielded sends, shielded sends, and BalanceViewModel all share the
     *   same cache entries. Dust is wallet-level, not per-mode.
     * @param toAddress Recipient's shielded address (mn_shield-addr_...)
     * @param amount Amount to send (micro-units)
     */
    fun sendShieldedTransaction(
        activity: FragmentActivity,
        fromAddress: String,
        walletAnchorAddress: String,
        toAddress: String,
        amount: BigInteger,
    ) {
        viewModelScope.launch {
            var plaintextSeed: PlaintextSeed? = null
            var hdWallet: HDWallet? = null

            try {
                _state.value = SendUiState.Building

                // Load encrypted seed via biometric prompt
                plaintextSeed = seedVault.loadSeed(activity)
                hdWallet = HDWallet.fromSeed(plaintextSeed.bip39Seed)

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

                // Ensure proving keys are downloaded if local proving is selected
                ensureProvingKeysDownloaded()

                // Derive zswap key (HD wallet already loaded above from biometric auth)
                val zswapKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.ZSWAP)
                    .deriveKeyAt(0)
                val zswapSeed = zswapKey.privateKeyBytes

                try {
                    // Always fresh-sync shielded state before spending.
                    // Stale state causes ReplayProtectionViolation (error 193) because
                    // nullifiers from previous attempts may already be on-chain.
                    //
                    // NOTE: shielded state is cached under `walletAnchorAddress`
                    // (the unshielded address), matching BalanceViewModel so that
                    // the send and balance flows share the same cache entry.
                    Log.d(TAG, "Syncing shielded state from blockchain before transfer...")
                    val synced = shieldedRepository.syncFromBlockchain(walletAnchorAddress, zswapSeed)
                    if (!synced) {
                        _state.value = SendUiState.Error("No shielded coins found. Receive shielded NIGHT first.")
                        return@launch
                    }
                    val state = shieldedRepository.loadState(walletAnchorAddress)
                    if (state == null) {
                        _state.value = SendUiState.Error("Failed to load shielded state after sync")
                        return@launch
                    }

                    state.use { activeState ->
                        val nightTokenType = "0".repeat(64) // all zeros for NIGHT
                        val ttlMs = System.currentTimeMillis() + 3600_000
                        val networkId = currentNetwork.name.lowercase()

                        // Step 1: Build shielded offer (select coins → spend → outputs → offer)
                        val transferResult = ZswapTransferBuilder.buildTransfer(
                            state = activeState,
                            seed = zswapSeed,
                            recipientCoinPk = coinPkHex,
                            recipientEncPk = encPkHex,
                            tokenType = nightTokenType,
                            amount = amount,
                            networkId = networkId,
                            ttlMs = ttlMs,
                        )

                        if (transferResult == null) {
                            _state.value = SendUiState.Error(
                                "Insufficient shielded balance or transfer build failed"
                            )
                            return@launch
                        }

                        // Step 2: Force fresh dust sync, then load state for
                        // fee payment. See [forceFreshDustSync] for rationale
                        // (avoids the post-rejection error-170 feedback loop).
                        val dustSynced = forceFreshDustSync(walletAnchorAddress, hdWallet)
                        if (!dustSynced) {
                            _state.value = SendUiState.Error("No dust registered. Register dust in Lace first.")
                            return@launch
                        }

                        // Derive a second, short-lived dust key for the
                        // fee-payment step itself — ZswapTransferBuilder needs
                        // the seed bytes to bind the dust spend into the
                        // shielded transaction. Wiped in the outer `finally`.
                        val dustKey = hdWallet
                            .selectAccount(0)
                            .selectRole(MidnightKeyRole.DUST)
                            .deriveKeyAt(0)
                        val dustSeed = dustKey.privateKeyBytes

                        try {
                            val dustState = dustRepository.loadState(walletAnchorAddress)
                            if (dustState == null) {
                                _state.value = SendUiState.Error("Failed to load dust state")
                                return@launch
                            }

                            val txHex = dustState.use { ds ->
                                if (ds.getUtxoCount() == 0) {
                                    _state.value = SendUiState.Error("No dust UTXOs available for fees")
                                    return@launch
                                }

                                // Step 3: Build final transaction with offer + dust
                                ZswapTransferBuilder.buildTransactionWithDust(
                                    offerHex = transferResult.offerHex,
                                    networkId = networkId,
                                    dustStatePtr = ds.getStatePointer(),
                                    dustSeed = dustSeed,
                                    dustUtxosJson = "[{\"utxo_index\":0,\"v_fee\":\"1\"}]",
                                    currentTimeMs = System.currentTimeMillis(),
                                    ttlMs = ttlMs,
                                )
                            }

                            if (txHex == null) {
                                _state.value = SendUiState.Error("Failed to build shielded transaction with dust")
                                return@launch
                            }

                            // Step 4: Prove + seal + submit
                            _state.value = SendUiState.Proving

                            val result = transactionSubmitter.submitPrebuiltTransaction(
                                unprovenTxHex = txHex,
                                timeoutMs = 360_000L
                            )

                            when (result) {
                                is TransactionSubmitter.SubmissionResult.Success -> {
                                    Log.i(TAG, "Shielded transfer finalized: ${result.txHash}")
                                    // Invalidate dust cache — UTXO was consumed on-chain.
                                    // Next transfer will re-sync fresh dust state.
                                    // Use walletAnchorAddress so this matches the
                                    // key the unshielded send path (and the Dust
                                    // screen) use — otherwise stale dust state
                                    // persists under the unshielded key and the
                                    // next unshielded send fails with error 170.
                                    dustRepository.deleteState(walletAnchorAddress)
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
                        } finally {
                            // DerivedKey.clear() zeros the backing array of
                            // privateKeyBytes in place — no extra Arrays.fill
                            // needed on dustSeed / zswapSeed.
                            dustKey.clear()
                        }
                    }

                } finally {
                    zswapKey.clear()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Shielded transaction failed", e)
                _state.value = SendUiState.Error("Shielded transfer error: ${e.message}")
            } finally {
                plaintextSeed?.wipe()
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

                // Use timeout to prevent getting stuck.
                // Default is incremental (forceFullResync=false) — we DO want to
                // preserve the UTXOs the node has marked SPENT from error 115.
                // A full wipe here would re-fetch the stale UTXO from the indexer
                // and the retry would fail the same way.
                val synced = withTimeoutOrNull(QUICK_SYNC_TIMEOUT_MS) {
                    subscriptionManager.startSubscription(params.fromAddress)
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
                // Note: this triggers a fresh biometric prompt for the retry.
                Log.d(TAG, "Auto-retrying transaction (attempt $retryAttempt)")
                sendTransaction(
                    activity = params.activity,
                    fromAddress = params.fromAddress,
                    toAddress = params.toAddress,
                    amount = params.amount,
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
    /**
     * Force a fresh dust state sync from chain.
     *
     * **Why always resync instead of trusting the cache?**
     * [TransactionSubmitter.submitWithFees] only clears the cached dust state
     * on *successful* finalization. If a previous attempt was rejected (e.g.
     * error 170 / InvalidDustSpendProof after a chain-level state change),
     * the stale entry stays on disk and the next send silently reuses it —
     * creating a feedback loop of 170 errors that can only be broken by
     * clearing app data. Forcing a delete + resync here breaks that loop at
     * the cost of one indexer replay per send.
     *
     * **Cache key.** Dust is a wallet-level resource — it lives on the
     * unshielded NIGHT account regardless of send mode — so callers must
     * pass the unshielded address so both the unshielded send path, the
     * shielded send path, and the Dust screen all share the same cache
     * entry. Keying by the shielded address would produce a second,
     * divergent entry that can go stale across mode switches.
     *
     * **Key lifecycle.** This helper derives a short-lived dust
     * [DerivedKey] from [hdWallet], uses it for the sync, and wipes it in
     * a `finally` block via [DerivedKey.clear] — which also zeros the
     * backing array returned by [DerivedKey.privateKeyBytes], so no dust
     * seed material survives the call.
     *
     * @param address Unshielded (wallet anchor) address to key the dust cache by.
     * @param hdWallet Already-unlocked HD wallet; only read, never mutated.
     * @return `true` if dust state was synced, `false` if no dust is
     *   registered on chain (user must register in Lace first).
     */
    private suspend fun forceFreshDustSync(
        address: String,
        hdWallet: HDWallet,
    ): Boolean {
        val dustKey = hdWallet
            .selectAccount(0)
            .selectRole(MidnightKeyRole.DUST)
            .deriveKeyAt(0)
        return try {
            dustRepository.deleteState(address)
            // maxBlocks intentionally defaults to DustRepository's own value
            // (100) — single source of truth, no per-caller constant needed.
            dustRepository.syncFromBlockchain(
                address = address,
                dustSeed = dustKey.privateKeyBytes,
            )
        } finally {
            // DerivedKey.clear() wipes the backing array of privateKeyBytes
            // in place — no separate Arrays.fill needed on the seed.
            dustKey.clear()
        }
    }

    /**
     * Download the ZK proving keys if local proving is selected and they
     * haven't been downloaded yet. Called before any transaction build because
     * even unshielded sends need proving keys for the dust fee binding proof.
     *
     * No-op on subsequent calls (keys are cached ~24MB on device). No-op when
     * remote proving is selected (keys live on the proof server).
     */
    private suspend fun ensureProvingKeysDownloaded() {
        if (transactionSubmitter.provingMode != ProvingMode.LOCAL) {
            Log.d(TAG, "Remote proving mode — skipping proving key download")
            return
        }
        if (provingKeyManager.hasWalletKeys()) {
            return
        }
        Log.d(TAG, "Downloading proving keys for local proving…")
        provingKeyManager.downloadWalletKeys { progress ->
            Log.d(TAG, "Proving key download: ${(progress * 100).toInt()}%")
        }
        Log.d(TAG, "Proving keys downloaded")
    }

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

        // Bob's test wallet addresses — used as the default recipient when
        // testing sends from the demo app. Source of truth: `mn info` on the
        // Bob wallet at ~/.midnight/wallets/bob.json. These are hardcoded
        // recipient addresses, NOT signing secrets — the wallet signs with
        // whatever seed the user onboarded into SeedVault.
        //
        // Keys are the network's address prefix so `currentNetwork.addressPrefix`
        // looks up the correct one.
        val BOB_UNSHIELDED_ADDRESSES = mapOf(
            "mn_addr_preprod" to "mn_addr_preprod1z7qzgsxnqg2h5pc3t7l84s4q7swqfxqcjxqc5nawq93f8r832fwsev7kky",
            "mn_addr_undeployed" to "mn_addr_undeployed1z7qzgsxnqg2h5pc3t7l84s4q7swqfxqcjxqc5nawq93f8r832fwsrhyg84",
            "mn_addr_preview" to "mn_addr_preview1z7qzgsxnqg2h5pc3t7l84s4q7swqfxqcjxqc5nawq93f8r832fwsedqx9e",
        )

        // Bob's shielded addresses. Same underlying coin_pk + enc_pk as the
        // preprod address — the undeployed entry is the same bytes re-encoded
        // with the `mn_shield-addr_undeployed` bech32 prefix.
        //
        // preview is empty because Bob's wallet file doesn't record a preview
        // shielded address and we haven't re-encoded it.
        val BOB_SHIELDED_ADDRESSES = mapOf(
            "mn_addr_preprod" to "mn_shield-addr_preprod1ys3ucc6ly48wtqekax3ehp3qwdfc257tcd2uaqp8vf3kvm6wggccgjhsxtsuw983n80t28fk3ncdtk2dcxn268f4mxrrhaqmkwt7ajsvdpu4u",
            "mn_addr_undeployed" to "mn_shield-addr_undeployed1ys3ucc6ly48wtqekax3ehp3qwdfc257tcd2uaqp8vf3kvm6wggccgjhsxtsuw983n80t28fk3ncdtk2dcxn268f4mxrrhaqmkwt7ajs4fj9f5",
            "mn_addr_preview" to "",
        )
    }
}
