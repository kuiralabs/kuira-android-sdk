package com.midnight.example.bboard

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.ContractCallException
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightConfig
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.TransactionStatus
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.identity.auth.AuthorizationScope
import com.midnight.kuira.core.identity.auth.KeyAuthorization
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.SigilBackup
import com.midnight.kuira.core.identity.did.DidKeyGenerator
import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * BBoard ViewModel — demonstrates using the Midnight Contract SDK.
 *
 * Shows the complete dApp developer flow:
 * 1. Configure SDK with network endpoints
 * 2. Create a contract handle with witnesses
 * 3. Call circuits with one line: `contract.call("post", message)`
 * 4. Observe progress stages for UI feedback
 *
 * Two connection modes:
 * - **Remote wallet:** delegates balancing to `mn serve` via WebSocket (existing)
 * - **Standalone SDK:** embedded wallet, no external process needed (new)
 */
class BBoardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<BBoardState>(BBoardState.Setup)
    val state: StateFlow<BBoardState> = _state

    private val _sigilState = MutableStateFlow<SigilState>(SigilState.None)
    val sigilState: StateFlow<SigilState> = _sigilState

    private var config: MidnightConfig? = null
    private var sdk: MidnightSdk? = null
    private var contract: MidnightContract? = null
    private var repository: BBoardRepository? = null
    private var currentAddress: String? = null

    /** Simulated app metadata — generated after posting, backed up with the seed. */
    private var appMetadata: ByteArray? = null

    private val passkeyManager = PasskeyManager(
        config = PasskeyConfig(rpId = "nel349.github.io"),
    )

    private val sigilBackup by lazy {
        SigilBackup(
            passkeyManager = passkeyManager,
            storage = BlockStoreBackupStorage(getApplication()),
        )
    }

    /**
     * Create a passkey and derive the user's DID.
     * This is "Forge your Sigil" — the root of the identity stack.
     */
    fun forgeSigil(activity: Activity) {
        viewModelScope.launch {
            _sigilState.value = SigilState.Creating("Creating passkey...")
            try {
                // Generate a random user ID (in production this comes from the app's user system)
                val userId = java.security.SecureRandom().let { rng ->
                    ByteArray(16).also { rng.nextBytes(it) }
                }

                val result = passkeyManager.createPasskey(
                    activity = activity,
                    userId = userId,
                    userName = "BBoard Test User",
                )

                val did = DidKeyGenerator.fromCompressedP256(result.publicKey.compressed)

                Log.i(TAG, "Sigil forged — DID: $did")
                Log.i(TAG, "  Credential ID: ${result.credentialId}")
                Log.i(TAG, "  P-256 pubkey: ${result.publicKey.compressedHex()}")

                _sigilState.value = SigilState.Forged(
                    did = did,
                    credentialId = result.credentialId,
                    publicKeyHex = result.publicKey.compressedHex(),
                )
            } catch (e: Exception) {
                Log.e(TAG, "Forge sigil failed", e)
                _sigilState.value = SigilState.Error(e.message ?: "Passkey creation failed")
            }
        }
    }

    /**
     * Test PRF extension — verify the emulator returns a 32-byte PRF output.
     * Quick spike before building the full backup stack.
     */
    fun testPrf(activity: Activity) {
        viewModelScope.launch {
            try {
                val challenge = java.security.SecureRandom().let { rng ->
                    ByteArray(32).also { rng.nextBytes(it) }
                }
                // Purpose-bound salt: SHA-256("kuira:backup:v1")
                val salt = java.security.MessageDigest.getInstance("SHA-256")
                    .digest("kuira:backup:v1".toByteArray(Charsets.UTF_8))

                Log.i(TAG, "Testing PRF with salt: ${salt.toHex()}")

                val result = passkeyManager.authenticateWithPrf(
                    activity = activity,
                    challenge = challenge,
                    prfSalt = salt,
                )

                val prfBytes = result.prfOutput
                if (prfBytes != null && prfBytes.size == 32) {
                    Log.i(TAG, "PRF SUCCESS! Output (${prfBytes.size} bytes): ${prfBytes.toHex()}")

                    // Test determinism — authenticate again with same salt
                    Log.i(TAG, "Testing PRF determinism (same salt, second auth)...")
                    val result2 = passkeyManager.authenticateWithPrf(
                        activity = activity,
                        challenge = challenge,
                        prfSalt = salt,
                    )
                    val prfBytes2 = result2.prfOutput
                    if (prfBytes2 != null && prfBytes2.size == 32) {
                        val match = prfBytes.contentEquals(prfBytes2)
                        Log.i(TAG, "PRF determinism: ${if (match) "PASS (same output)" else "FAIL (different output)"}")
                        Log.i(TAG, "  First:  ${prfBytes.toHex()}")
                        Log.i(TAG, "  Second: ${prfBytes2.toHex()}")
                    }
                } else {
                    Log.w(TAG, "PRF returned null — extension not supported on this authenticator")
                    Log.i(TAG, "Full response: ${result.assertionResponseJson}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "PRF test failed", e)
            }
        }
    }

    /**
     * Backup the test seed to Google Block Store via PRF-encrypted blob.
     * Tests the full backup pipeline: PRF → HKDF → AES-GCM → Block Store.
     */
    fun backupSeed(activity: Activity) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Starting PRF backup...")

                // Use the test seed's entropy and BIP-39 seed
                val entropy = ByteArray(32) { 0x11 }
                val bip39Seed = TEST_SEED.copyOf()

                val meta = appMetadata
                sigilBackup.backup(
                    activity = activity,
                    entropy = entropy,
                    bip39Seed = bip39Seed,
                    metadata = meta,
                )

                val metaInfo = if (meta != null) " + ${meta.size} bytes metadata" else ""
                Log.i(TAG, "Backup SUCCESS — seed$metaInfo encrypted and stored in Block Store")
                _sigilState.value = when (val current = _sigilState.value) {
                    is SigilState.Forged -> SigilState.Forged(
                        did = current.did,
                        credentialId = current.credentialId,
                        publicKeyHex = current.publicKeyHex,
                    )
                    else -> current
                }
            } catch (e: BackupException) {
                Log.e(TAG, "Backup failed: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Backup failed", e)
            }
        }
    }

    /**
     * Restore the seed from Google Block Store via PRF decryption.
     * Tests the full restore pipeline: Block Store → PRF → HKDF → AES-GCM → seed.
     */
    fun restoreSeed(activity: Activity) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Starting PRF restore...")

                val decrypted = sigilBackup.restore(activity)
                try {
                    Log.i(TAG, "Restore SUCCESS!")
                    Log.i(TAG, "  Entropy: ${decrypted.entropy.toHex()}")
                    Log.i(TAG, "  Seed (first 16 bytes): ${decrypted.bip39Seed.copyOfRange(0, 16).toHex()}...")
                    Log.i(TAG, "  Seed matches test seed: ${decrypted.bip39Seed.contentEquals(TEST_SEED)}")
                    val restoredMeta = decrypted.metadata
                    if (restoredMeta != null) {
                        Log.i(TAG, "  Metadata restored: ${restoredMeta.size} bytes")
                        Log.i(TAG, "  Metadata: ${String(restoredMeta, Charsets.UTF_8)}")
                    } else {
                        Log.i(TAG, "  No metadata in backup")
                    }
                } finally {
                    decrypted.wipe()
                }
            } catch (e: BackupException) {
                Log.e(TAG, "Restore failed: ${e.message}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
            }
        }
    }

    /**
     * Build a keyAuthorization payload and sign it with the passkey.
     * This authorizes the SDK's access key to sign Midnight transactions.
     */
    fun authorizeAccessKey(activity: Activity) {
        val sigil = _sigilState.value as? SigilState.Forged ?: return
        val midnightSdk = sdk ?: return

        viewModelScope.launch {
            _sigilState.value = SigilState.Authorizing(sigil, "Building authorization...")
            try {
                // Build the payload: root P-256 key authorizes SDK's secp256k1 access key
                val rootPublicKey = sigil.publicKeyHex.hexToBytes()
                val accessPublicKey = midnightSdk.accessKeyPublicKey

                val payload = KeyAuthorization.buildPayload(
                    rootPublicKey = rootPublicKey,
                    accessPublicKey = accessPublicKey,
                    scope = AuthorizationScope.FULL_ACCESS,
                    timestampMs = System.currentTimeMillis(),
                )

                // Hash the payload — this becomes the WebAuthn challenge
                val challengeHash = KeyAuthorization.hashPayload(payload)

                _sigilState.value = SigilState.Authorizing(sigil, "Sign with passkey...")

                // Passkey signs the challenge (user sees biometric prompt)
                val assertion = passkeyManager.authenticate(
                    activity = activity,
                    challenge = challengeHash,
                )

                Log.i(TAG, "Access key authorized!")
                Log.i(TAG, "  Access key: ${midnightSdk.accessKeyPublicKey.toHex()}")
                Log.i(TAG, "  Path: ${midnightSdk.accessKeyPath}")
                Log.i(TAG, "  Signature: ${assertion.signature.size} bytes")

                _sigilState.value = SigilState.Authorized(
                    did = sigil.did,
                    credentialId = sigil.credentialId,
                    publicKeyHex = sigil.publicKeyHex,
                    accessKeyHex = midnightSdk.accessKeyPublicKey.toHex(),
                    accessKeyPath = midnightSdk.accessKeyPath,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Authorization failed", e)
                _sigilState.value = SigilState.Error(e.message ?: "Authorization failed")
            }
        }
    }

    /** Connect using a remote wallet (mn serve). */
    fun connect(contractAddress: String, network: NetworkChoice) {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting("Initializing (remote wallet)...")
            try {
                installProvingKeys()

                val cfg = MidnightConfig.Builder(getApplication())
                    .indexerUrl(network.indexerUrl)
                    .walletUrl(network.walletUrl)
                    .networkId(network.networkId)
                    .build()
                config = cfg

                setupContract(cfg, contractAddress, network.networkId)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                _state.value = BBoardState.Error(e.message ?: "Connection failed")
            }
        }
    }

    /**
     * Connect using the standalone SDK (no mn serve needed).
     *
     * @param contractAddress Deployed contract address (64 hex chars)
     * @param network Midnight network to use
     * @param seed BIP-39 mnemonic seed (64 bytes)
     */
    fun connectWithSdk(
        contractAddress: String,
        network: MidnightNetwork,
        seed: ByteArray,
    ) {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting("Initializing SDK...")
            try {
                installProvingKeys()

                _state.value = BBoardState.Connecting("Building SDK (deriving keys)...")
                val midnightSdk = MidnightSdk.Builder(getApplication())
                    .network(network)
                    .seed(seed)
                    .build()
                sdk = midnightSdk

                // Download proving keys if needed
                if (!midnightSdk.provingKeyManager.hasWalletKeys()) {
                    _state.value = BBoardState.Connecting("Downloading proving keys...")
                    midnightSdk.provingKeyManager.downloadWalletKeys { progress ->
                        _state.value = BBoardState.Connecting(
                            "Downloading keys: ${(progress * 100).toInt()}%"
                        )
                    }
                }

                // Connect to contract immediately — show state to user fast
                setupContract(
                    cfg = midnightSdk.config,
                    contractAddress = contractAddress,
                    networkId = network.rustNetworkId,
                    coinPublicKey = midnightSdk.coinPublicKey,
                )

                // Sync dust in background — UI shows progress bar, actions enabled when done
                syncDustInBackground(midnightSdk)
            } catch (e: Exception) {
                Log.e(TAG, "SDK connect failed", e)
                _state.value = BBoardState.Error(e.message ?: "SDK connection failed")
            }
        }
    }

    /**
     * Deploy a fresh BBoard contract, then connect to it.
     * Tests the full deploy pipeline: constructor → prove → balance → submit.
     */
    fun deployAndConnect(network: MidnightNetwork, seed: ByteArray) {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting("Initializing SDK...")
            try {
                installProvingKeys()

                _state.value = BBoardState.Connecting("Building SDK...")
                val midnightSdk = MidnightSdk.Builder(getApplication())
                    .network(network)
                    .seed(seed)
                    .build()
                sdk = midnightSdk

                if (!midnightSdk.provingKeyManager.hasWalletKeys()) {
                    _state.value = BBoardState.Connecting("Downloading proving keys...")
                    midnightSdk.provingKeyManager.downloadWalletKeys { progress ->
                        _state.value = BBoardState.Connecting(
                            "Downloading keys: ${(progress * 100).toInt()}%"
                        )
                    }
                }

                _state.value = BBoardState.Connecting("Deploying BBoard contract...")

                // Load verifier keys for each circuit from the proving keys directory
                val keysDir = midnightSdk.provingKeyManager.keysDir
                val verifierKeys = mapOf(
                    "post" to java.io.File(keysDir, "post.verifier").readBytes(),
                    "takeDown" to java.io.File(keysDir, "takeDown.verifier").readBytes(),
                )

                val bboard = MidnightContract.create(midnightSdk.config) {
                    name = "bboard"
                    contractJs = getApplication<Application>().assets
                        .open("runtime/bboard-contract-iife.js")
                    witness("localSecretKey") { WitnessResult(null, SECRET_KEY.copyOf()) }
                    initialPrivateState = mapOf("secretKey" to SECRET_KEY.copyOf())
                    coinPublicKey = midnightSdk.coinPublicKey
                    circuitVerifierKeys = verifierKeys
                }

                val deployResult = bboard.deploy { stage ->
                    val label = stageLabel(stage)
                    Log.i(TAG, "Deploy stage: $label")
                    _state.value = BBoardState.Connecting(label)
                }

                val addr = deployResult.contractAddress
                Log.i(TAG, "Deployed at: $addr (${deployResult.timings})")

                // Wait for indexer to catch up with the newly deployed contract
                _state.value = BBoardState.Connecting("Deployed at ${addr.take(8)}... Waiting for indexer...")
                val tempRepo = BBoardRepository(midnightSdk.config)
                var retries = 0
                while (retries < 10) {
                    kotlinx.coroutines.delay(2000)
                    val content = tempRepo.fetchBoardState(addr)
                    if (content !is BoardContent.NotDeployed && content !is BoardContent.Error) break
                    retries++
                    _state.value = BBoardState.Connecting("Waiting for indexer... (${retries * 2}s)")
                }

                setupContract(
                    cfg = midnightSdk.config,
                    contractAddress = addr,
                    networkId = network.rustNetworkId,
                    coinPublicKey = midnightSdk.coinPublicKey,
                )

                syncDustInBackground(midnightSdk)
            } catch (e: Exception) {
                Log.e(TAG, "Deploy failed", e)
                _state.value = BBoardState.Error(e.message ?: "Deploy failed")
            }
        }
    }

    private fun syncDustInBackground(midnightSdk: MidnightSdk) {
        viewModelScope.launch {
            try {
                updateDustSyncStatus(DustSyncStatus.Syncing(0, "Connecting to indexer..."))
                midnightSdk.wallet.syncDust { processed, total ->
                    if (processed < 0) {
                        // Sentinel: streaming done, now replaying in Rust
                        updateDustSyncStatus(DustSyncStatus.Processing("Replaying $total events..."))
                    } else {
                        val pct = if (total > 0) (processed * 100 / total) else 0
                        updateDustSyncStatus(DustSyncStatus.Syncing(pct, "$processed / $total events"))
                    }
                }
                updateDustSyncStatus(DustSyncStatus.Ready)
                Log.d(TAG, "Background dust sync complete")
            } catch (e: Exception) {
                Log.w(TAG, "Background dust sync failed: ${e.message}")
                // Non-fatal — sync will happen on first tx attempt
                updateDustSyncStatus(DustSyncStatus.Ready)
            }
        }
    }

    private fun updateDustSyncStatus(status: DustSyncStatus) {
        val current = _state.value
        if (current is BBoardState.Connected) {
            _state.value = current.copy(dustSyncStatus = status)
        }
    }

    /** Shared setup: create contract handle, fetch state, transition to Connected. */
    private suspend fun setupContract(
        cfg: MidnightConfig,
        contractAddress: String,
        networkId: String,
        coinPublicKey: ByteArray = ByteArray(32), // Default for remote wallet mode
    ) {
        config = cfg

        _state.value = BBoardState.Connecting("Loading contract...")
        val bboard = MidnightContract.create(cfg) {
            contractJs = getApplication<Application>().assets
                .open("runtime/bboard-contract-iife.js")
            address = contractAddress
            witness("localSecretKey") { WitnessResult(null, SECRET_KEY.copyOf()) }
            initialPrivateState = mapOf("secretKey" to SECRET_KEY.copyOf())
            this.coinPublicKey = coinPublicKey
        }
        contract = bboard
        currentAddress = contractAddress

        val repo = BBoardRepository(cfg)
        repository = repo

        _state.value = BBoardState.Connecting("Fetching board state...")
        val boardContent = repo.fetchBoardState(contractAddress)
        val boardState = when (boardContent) {
            is BoardContent.Vacant -> BoardState.Vacant
            is BoardContent.Occupied -> BoardState.Occupied(boardContent.message)
            is BoardContent.NotDeployed -> {
                _state.value = BBoardState.Error("Contract not deployed at $contractAddress")
                return
            }
            is BoardContent.Error -> {
                _state.value = BBoardState.Error(boardContent.reason)
                return
            }
        }

        _state.value = BBoardState.Connected(
            networkId = networkId,
            contractAddress = contractAddress,
            boardState = boardState,
            standalone = sdk != null,
        )
    }

    /** Post a message to the board. */
    fun post(message: String) {
        val current = _state.value as? BBoardState.Connected ?: return
        val bboard = contract ?: return

        viewModelScope.launch {
            Log.i(TAG, "Posting: $message")
            _state.value = current.copy(boardState = BoardState.Working("Preparing..."))
            try {
                val receipt = bboard.call("post", message) { stage ->
                    val label = stageLabel(stage)
                    Log.i(TAG, "Post stage: $label")
                    _state.value = current.copy(boardState = BoardState.Working(label))
                }

                if (receipt.status == TransactionStatus.SUBMITTED) {
                    Log.i(TAG, "Post submitted! total=${receipt.timings.totalMs}ms")

                    // Generate metadata — simulates game state computed after a tx
                    val meta = buildAppMetadata(message, receipt.timings.totalMs)
                    appMetadata = meta
                    Log.i(TAG, "Generated ${meta.size} bytes of app metadata")

                    _state.value = current.copy(
                        boardState = BoardState.Occupied(message = message),
                        lastTimingMs = receipt.timings.totalMs,
                    )
                }
            } catch (e: ContractCallException) {
                Log.e(TAG, "Post failed", e)
                _state.value = current.copy(
                    boardState = BoardState.CallError(e.message ?: "Post failed"),
                )
            }
        }
    }

    /** Take down the current post. */
    fun takeDown() {
        val current = _state.value as? BBoardState.Connected ?: return
        val bboard = contract ?: return

        viewModelScope.launch {
            Log.i(TAG, "Taking down")
            _state.value = current.copy(boardState = BoardState.Working("Preparing..."))
            try {
                bboard.call("takeDown") { stage ->
                    val label = stageLabel(stage)
                    Log.i(TAG, "TakeDown stage: $label")
                    _state.value = current.copy(boardState = BoardState.Working(label))
                }
                Log.i(TAG, "TakeDown submitted!")
                _state.value = current.copy(boardState = BoardState.Vacant, lastTimingMs = null)
            } catch (e: ContractCallException) {
                Log.e(TAG, "TakeDown failed", e)
                _state.value = current.copy(
                    boardState = BoardState.CallError(e.message ?: "Take down failed"),
                )
            }
        }
    }

    /** Refresh board state from indexer. */
    fun refresh() {
        val current = _state.value as? BBoardState.Connected ?: return
        val repo = repository ?: return
        val addr = currentAddress ?: return

        viewModelScope.launch {
            val content = repo.fetchBoardState(addr)
            val boardState = when (content) {
                is BoardContent.Vacant -> BoardState.Vacant
                is BoardContent.Occupied -> BoardState.Occupied(content.message)
                else -> return@launch
            }
            _state.value = current.copy(boardState = boardState)
        }
    }

    /** Disconnect and return to setup. */
    fun disconnect() {
        sdk?.close()
        sdk = null
        config?.close()
        config = null
        contract = null
        repository = null
        _state.value = BBoardState.Setup
    }

    override fun onCleared() {
        sdk?.close()
        config?.close()
        super.onCleared()
    }

    private fun stageLabel(stage: ContractCallStage): String = when (stage) {
        is ContractCallStage.FetchingState -> "Fetching state..."
        is ContractCallStage.Executing -> "Executing circuit..."
        is ContractCallStage.Proving -> "Generating ZK proof..."
        is ContractCallStage.Balancing -> "Balancing transaction..."
        is ContractCallStage.BalancingDetail -> balanceLabel(stage.progress)
        is ContractCallStage.Submitting -> "Submitting to chain..."
    }

    private fun balanceLabel(progress: BalanceProgress): String = when (progress) {
        is BalanceProgress.SyncingDust -> "Syncing dust wallet..."
        is BalanceProgress.SyncingDustProgress ->
            "Syncing dust: ${progress.eventsProcessed}/${progress.totalEvents}"
        is BalanceProgress.ProvingDust -> "Proving dust payment..."
        is BalanceProgress.Submitting -> "Submitting to blockchain..."
        is BalanceProgress.WaitingFinalization -> "Waiting for finalization..."
        is BalanceProgress.RetryingDustSync -> "Retrying dust sync..."
    }

    /**
     * Copy proving keys + BLS params from /data/local/tmp/bboard_keys/
     * (pushed via `./scripts/install-bboard-keys.sh`) to the app's proving_keys dir.
     */
    private fun installProvingKeys() {
        val tempDir = java.io.File("/data/local/tmp/bboard_keys")
        if (!tempDir.exists()) {
            Log.w(TAG, "No proving keys at ${tempDir.path} — proving will fail")
            return
        }

        val keysDir = java.io.File(getApplication<Application>().filesDir, "proving_keys")
        keysDir.mkdirs()

        val files = listOf(
            "post.prover", "post.verifier", "post.bzkir",
            "takeDown.prover", "takeDown.verifier", "takeDown.bzkir",
            "bls_midnight_2p13", "bls_midnight_2p14", "bls_midnight_2p15",
        )
        for (name in files) {
            val src = java.io.File(tempDir, name)
            val dst = java.io.File(keysDir, name)
            if (src.exists() && !dst.exists()) {
                src.copyTo(dst)
                Log.d(TAG, "Installed key: $name")
            }
        }
    }

    companion object {
        private const val TAG = "BBoard"
        // Fixed test key — in a real dApp, derive from wallet or secure storage
        private val SECRET_KEY = ByteArray(32) { (it + 1).toByte() }

        /**
         * Generates simulated app metadata after a successful post.
         * In a real game (Kicks), this would be: committed choices, nonces,
         * match state, player stats — data that's computed locally and
         * expensive to store on-chain.
         */
        private fun buildAppMetadata(message: String, timingMs: Long): ByteArray {
            val json = org.json.JSONObject().apply {
                put("type", "bboard_post")
                put("message_hash", java.security.MessageDigest.getInstance("SHA-256")
                    .digest(message.toByteArray()).joinToString("") { "%02x".format(it) })
                put("timing_ms", timingMs)
                put("timestamp", System.currentTimeMillis())
                put("session_id", java.util.UUID.randomUUID().toString())
            }
            return json.toString().toByteArray(Charsets.UTF_8)
        }
    }
}

// ── State Model ──

sealed class BBoardState {
    data object Setup : BBoardState()
    data class Connecting(val stage: String) : BBoardState()
    data class Connected(
        val networkId: String,
        val contractAddress: String,
        val boardState: BoardState,
        val lastTimingMs: Long? = null,
        val standalone: Boolean = false,
        val dustSyncStatus: DustSyncStatus = DustSyncStatus.Ready,
    ) : BBoardState()
    data class Error(val message: String) : BBoardState()
}

/** Background dust sync status — shown as a subtle bar in the connected screen. */
sealed class DustSyncStatus {
    data object Ready : DustSyncStatus()
    data class Syncing(val percent: Int, val detail: String) : DustSyncStatus()
    data class Processing(val detail: String) : DustSyncStatus()
}

sealed class BoardState {
    data object Vacant : BoardState()
    data class Working(val stage: String) : BoardState()
    data class Occupied(val message: String) : BoardState()
    data class CallError(val message: String) : BoardState()
}

/** Sigil identity state — tracks passkey creation and access key authorization. */
sealed class SigilState {
    data object None : SigilState()
    data class Creating(val stage: String) : SigilState()
    data class Forged(
        val did: String,
        val credentialId: String,
        val publicKeyHex: String,
    ) : SigilState()
    data class Authorizing(val sigil: Forged, val stage: String) : SigilState()
    data class Authorized(
        val did: String,
        val credentialId: String,
        val publicKeyHex: String,
        val accessKeyHex: String,
        val accessKeyPath: String,
    ) : SigilState()
    data class Error(val message: String) : SigilState()
}

private fun String.hexToBytes(): ByteArray =
    ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }

/** Network configuration presets (for remote wallet mode). */
enum class NetworkChoice(
    val label: String,
    val networkId: String,
    val indexerUrl: String,
    val walletUrl: String,
) {
    LOCALNET("Localnet", "undeployed", "http://10.0.2.2:8088/api/v3", "ws://10.0.2.2:9932"),
    PREVIEW("Preview", "preview", "https://indexer.preview.midnight.network/api/v3", "ws://10.0.2.2:9932"),
    PREPROD("PreProd", "preprod", "https://indexer.preprod.midnight.network/api/v3", "ws://10.0.2.2:9932"),
}
