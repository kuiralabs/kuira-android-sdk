package com.midnight.kuira.feature.dust

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.crypto.bip32.DerivedKey
import com.midnight.kuira.core.crypto.bip32.HDWallet
import com.midnight.kuira.core.crypto.bip32.MidnightKeyRole
import com.midnight.kuira.core.crypto.dust.DustKeyDeriver
import com.midnight.kuira.core.indexer.repository.DustRepository
import com.midnight.kuira.core.ledger.api.NodeRpcClient
import com.midnight.kuira.core.ledger.api.ProofServerClient
import com.midnight.kuira.core.ledger.api.TransactionFinalizationResult
import com.midnight.kuira.core.ledger.api.TransactionSerializer
import com.midnight.kuira.core.indexer.utxo.UtxoManager
import com.midnight.kuira.core.ledger.dust.DustRegistrationBuilder
import com.midnight.kuira.core.ledger.model.UtxoSpend
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import javax.inject.Inject

/**
 * ViewModel for dust tank management screen.
 *
 * **Responsibilities:**
 * - Check dust registration status on blockchain
 * - Display dust balance and token information
 * - Execute full dust registration flow: build → prove → seal → submit
 *
 * **Dust Registration Flow:**
 * 1. Derive NIGHT key at m/44'/2400'/0'/0/0 (for signing)
 * 2. Derive dust key at m/44'/2400'/0'/2/0 (for dust public key)
 * 3. Build registration tx via DustRegistrationBuilder (Rust FFI)
 * 4. Prove tx via ProofServerClient
 * 5. Seal proven tx via TransactionSerializer
 * 6. Submit sealed tx via NodeRpcClient
 * 7. Sync dust state from blockchain
 *
 * **Security:** The seed is never held in the UI. It is loaded on demand
 * from [SeedVault] via a biometric prompt, used for key derivation, and
 * wiped in the `finally` block of every operation. See [SeedVault] and
 * [HDWallet] for the key-material lifecycle.
 */
@RequiresApi(Build.VERSION_CODES.O)
@HiltViewModel
class DustViewModel @Inject constructor(
    private val dustRepository: DustRepository,
    private val proofServerClient: ProofServerClient,
    private val serializer: TransactionSerializer,
    private val nodeRpcClient: NodeRpcClient,
    private val utxoManager: UtxoManager,
    private val seedVault: SeedVault,
) : ViewModel() {

    private val _state = MutableStateFlow<DustUiState>(DustUiState.Idle)
    val state: StateFlow<DustUiState> = _state.asStateFlow()

    /**
     * Check dust status for an address.
     *
     * Loads the seed via [SeedVault] (biometric prompt), syncs dust from
     * chain, then emits either a [DustUiState.Status] (dust exists) or
     * [DustUiState.NoDust] (not registered yet).
     *
     * @param activity Hosting FragmentActivity (required by BiometricPrompt)
     * @param address Wallet address to check
     */
    fun checkDustStatus(activity: FragmentActivity, address: String) {
        viewModelScope.launch {
            var plaintextSeed: PlaintextSeed? = null
            var hdWallet: HDWallet? = null
            var dustKey: DerivedKey? = null

            try {
                _state.value = DustUiState.Loading

                if (address.isBlank()) {
                    _state.value = DustUiState.Error("Address cannot be empty")
                    return@launch
                }

                // Load seed via biometric prompt, then derive the dust key
                plaintextSeed = seedVault.loadSeed(activity)
                hdWallet = HDWallet.fromSeed(plaintextSeed.bip39Seed)
                dustKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.DUST)
                    .deriveKeyAt(0)

                // Try to sync dust from blockchain. maxBlocks defaults to
                // DustRepository's own value — single source of truth.
                Log.d(TAG, "Syncing dust from blockchain for $address")
                val hasDust = dustRepository.syncFromBlockchain(
                    address = address,
                    dustSeed = dustKey.privateKeyBytes,
                )

                if (!hasDust) {
                    Log.d(TAG, "No dust registered for $address")
                    _state.value = DustUiState.NoDust
                    return@launch
                }

                // Dust exists — show status using DustLocalState (source of truth)
                showDustStatus(address)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to check dust status", e)
                _state.value = DustUiState.Error(
                    message = "Failed to check dust status: ${e.message}",
                    throwable = e
                )
            } finally {
                // CRITICAL SECURITY: wipe all key material in reverse-order.
                // DerivedKey.clear() also zeros the backing array of privateKeyBytes.
                dustKey?.clear()
                hdWallet?.clear()
                plaintextSeed?.wipe()
            }
        }
    }

    /**
     * Register for dust generation.
     *
     * Full registration flow:
     * 1. Load seed via [SeedVault] (biometric prompt)
     * 2. Derive NIGHT key at m/44'/2400'/0'/0/0
     * 3. Derive dust key at m/44'/2400'/0'/2/0
     * 4. Fetch NIGHT UTXOs (required for guaranteed unshielded offer)
     * 5. Build registration tx (Rust FFI) with UTXOs
     * 6. Prove tx (proof server)
     * 7. Seal proven tx
     * 8. Submit to blockchain and wait for finalization
     * 9. Sync dust state
     *
     * @param activity Hosting FragmentActivity (required by BiometricPrompt)
     * @param address Wallet address to register
     */
    fun registerDust(
        activity: FragmentActivity,
        address: String,
    ) {
        viewModelScope.launch {
            var plaintextSeed: PlaintextSeed? = null
            var hdWallet: HDWallet? = null
            var nightKey: DerivedKey? = null
            var dustKey: DerivedKey? = null

            try {
                if (address.isBlank()) {
                    _state.value = DustUiState.Error("Address is required")
                    return@launch
                }

                // Step 1: Load seed via biometric prompt + derive keys
                _state.value = DustUiState.Registering(RegistrationStep.BUILDING)

                plaintextSeed = seedVault.loadSeed(activity)
                hdWallet = HDWallet.fromSeed(plaintextSeed.bip39Seed)

                // NIGHT key at m/44'/2400'/0'/0/0
                nightKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.NIGHT_EXTERNAL)
                    .deriveKeyAt(0)
                val nightPrivateKey = nightKey.privateKeyBytes

                // Dust key at m/44'/2400'/0'/2/0
                dustKey = hdWallet
                    .selectAccount(0)
                    .selectRole(MidnightKeyRole.DUST)
                    .deriveKeyAt(0)
                val dustSeed = dustKey.privateKeyBytes

                // Step 2: Derive dust public key
                Log.d(TAG, "Deriving dust public key")
                val dustPublicKeyHex = DustKeyDeriver.derivePublicKey(dustSeed)
                    ?: throw IllegalStateException("Failed to derive dust public key")
                Log.d(TAG, "Dust public key: ${dustPublicKeyHex.take(16)}...")

                // Step 3: Fetch NIGHT UTXOs for guaranteed unshielded offer
                Log.d(TAG, "Fetching NIGHT UTXOs for address: $address")
                val allUtxos = utxoManager.getUnspentUtxos(address)
                val nightUtxos = allUtxos.filter { it.tokenType == UtxoSpend.NATIVE_TOKEN_TYPE }

                if (nightUtxos.isEmpty()) {
                    _state.value = DustUiState.Error(
                        "No NIGHT UTXOs available. Cannot register without NIGHT tokens."
                    )
                    return@launch
                }
                Log.d(TAG, "Found ${nightUtxos.size} NIGHT UTXOs")

                // Serialize UTXOs to JSON for Rust FFI
                // Includes ctime (creation timestamp) for dust generation calculation
                val utxosJsonArray = JSONArray()
                for (utxo in nightUtxos) {
                    val obj = JSONObject()
                    obj.put("value", utxo.value)
                    obj.put("intent_hash", utxo.intentHash)
                    obj.put("output_no", utxo.outputIndex)
                    obj.put("ctime", utxo.ctime)
                    utxosJsonArray.put(obj)
                }
                val utxosJson = utxosJsonArray.toString()
                Log.d(TAG, "UTXO JSON: $utxosJson")

                // Step 4: Build registration transaction with UTXOs
                Log.d(TAG, "Building dust registration transaction")
                val ttl = System.currentTimeMillis() + TTL_OFFSET_MS
                val scaleHex = DustRegistrationBuilder.build(
                    nightPrivateKey = nightPrivateKey,
                    dustPublicKeyHex = dustPublicKeyHex,
                    utxosJson = utxosJson,
                    ttlMillis = ttl
                ) ?: throw IllegalStateException("Failed to build dust registration transaction")
                Log.d(TAG, "Registration tx built: ${scaleHex.length} hex chars")

                // Step 5: Prove transaction
                _state.value = DustUiState.Registering(RegistrationStep.PROVING)
                Log.d(TAG, "Sending to proof server...")
                val provenHex = proofServerClient.proveTransaction(scaleHex)
                Log.d(TAG, "Transaction proven: ${provenHex.length} hex chars")

                // Step 6: Seal proven transaction
                _state.value = DustUiState.Registering(RegistrationStep.SEALING)
                Log.d(TAG, "Sealing proven transaction...")
                val sealedHex = serializer.sealProvenTransaction(provenHex)
                    ?: throw IllegalStateException("Failed to seal proven transaction")
                Log.d(TAG, "Transaction sealed: ${sealedHex.length} hex chars")

                // Step 7: Submit to blockchain
                _state.value = DustUiState.Registering(RegistrationStep.SUBMITTING)
                Log.d(TAG, "Submitting to blockchain...")
                val result = nodeRpcClient.submitAndWaitForFinalization(
                    serializedTxHex = sealedHex,
                    timeoutMs = SUBMISSION_TIMEOUT_MS
                )

                // Step 8: Handle result
                when (result) {
                    is TransactionFinalizationResult.Finalized -> {
                        Log.d(TAG, "Registration finalized: txHash=${result.txHash}")

                        // Sync dust from blockchain and show status automatically
                        try {
                            dustRepository.syncFromBlockchain(
                                address = address,
                                dustSeed = dustSeed,
                            )
                            // Auto-show status after registration
                            showDustStatus(address)
                        } catch (e: Exception) {
                            Log.w(TAG, "Post-registration sync failed (non-fatal)", e)
                            _state.value = DustUiState.RegistrationSuccess(txHash = result.txHash)
                        }
                    }

                    is TransactionFinalizationResult.InBlock -> {
                        Log.d(TAG, "Registration in block (not yet finalized): ${result.txHash}")

                        // Sync dust from blockchain and show status automatically
                        try {
                            dustRepository.syncFromBlockchain(
                                address = address,
                                dustSeed = dustSeed,
                            )
                            showDustStatus(address)
                        } catch (e: Exception) {
                            Log.w(TAG, "Post-registration sync failed (non-fatal)", e)
                            _state.value = DustUiState.RegistrationSuccess(txHash = result.txHash)
                        }
                    }

                    is TransactionFinalizationResult.Dropped -> {
                        _state.value = DustUiState.Error("Transaction dropped: ${result.reason}")
                    }

                    is TransactionFinalizationResult.Invalid -> {
                        _state.value = DustUiState.Error("Transaction invalid: ${result.reason}")
                    }

                    is TransactionFinalizationResult.Usurped -> {
                        _state.value = DustUiState.Error(
                            "Transaction replaced by ${result.replacedBy ?: "unknown"}"
                        )
                    }

                    is TransactionFinalizationResult.Timeout -> {
                        _state.value = DustUiState.Error(
                            "Transaction timed out. It may still finalize — check status later."
                        )
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Dust registration failed", e)
                _state.value = DustUiState.Error(
                    message = "Registration failed: ${e.message}",
                    throwable = e
                )
            } finally {
                // CRITICAL SECURITY: Wipe all key material.
                // DerivedKey.clear() also zeros the backing array of
                // privateKeyBytes, so no separate Arrays.fill is needed on
                // nightPrivateKey / dustSeed.
                nightKey?.clear()
                dustKey?.clear()
                hdWallet?.clear()
                plaintextSeed?.wipe()
            }
        }
    }

    /**
     * Show current dust status from cached state (no blockchain sync).
     *
     * Used after registration to immediately display dust status
     * without requiring a manual "Check Status" click.
     */
    private suspend fun showDustStatus(address: String) {
        val balance = dustRepository.getCurrentBalance(address)

        // Get total NIGHT balance backing dust generation
        val balanceMap = utxoManager.calculateBalance(address)
        val nightBalance = balanceMap[UtxoSpend.NATIVE_TOKEN_TYPE] ?: BigInteger.ZERO

        Log.d(TAG, "Dust status: balance=$balance, nightBalance=$nightBalance")

        _state.value = DustUiState.Status(
            balance = balance,
            nightBalance = nightBalance,
            timeToCapacityMs = 0L,
            isAtCapacity = false
        )
    }

    /**
     * Reset to loading state and re-check dust status.
     * Triggers a fresh biometric prompt.
     */
    fun reset(activity: FragmentActivity, address: String) {
        checkDustStatus(activity, address)
    }

    companion object {
        private const val TAG = "DustViewModel"

        // TTL: 5 minutes from now (same as SendViewModel pattern)
        private const val TTL_OFFSET_MS = 5 * 60 * 1000L

        // Submission timeout: 120 seconds (dust registration can be slower)
        private const val SUBMISSION_TIMEOUT_MS = 120_000L
    }
}
