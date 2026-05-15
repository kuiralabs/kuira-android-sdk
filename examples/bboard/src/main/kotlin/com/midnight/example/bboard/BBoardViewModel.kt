package com.midnight.example.bboard

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.auth.BiometricGate
import com.midnight.kuira.core.auth.PlaintextSeed
import com.midnight.kuira.core.auth.SeedVault
import com.midnight.kuira.core.auth.WalletKeyManager
import com.midnight.kuira.core.compact.BalanceProgress
import com.midnight.kuira.core.compact.ContractCallException
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightConfig
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.TransactionStatus
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.crypto.bip39.BIP39
import com.midnight.kuira.core.identity.auth.AuthorizationScope
import com.midnight.kuira.core.identity.auth.KeyAuthorization
import com.midnight.kuira.core.identity.backup.BackupException
import com.midnight.kuira.core.identity.backup.BlockStoreBackupStorage
import com.midnight.kuira.core.identity.backup.SigilBackup
import com.midnight.kuira.core.identity.did.DidKeyGenerator
import com.midnight.kuira.core.identity.passkey.PasskeyConfig
import com.midnight.kuira.core.identity.passkey.PasskeyManager
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.core.network.MidnightNetwork
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.WalletBalance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger
import java.security.SecureRandom

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
    /** Network the current [sdk] was built against — used to detect changes in [buildOrReuseSdk]. */
    private var sdkNetwork: MidnightNetwork? = null
    private var contract: MidnightContract? = null
    private var repository: BBoardRepository? = null
    private var currentAddress: String? = null

    /** Simulated app metadata — generated after posting, backed up with the seed. */
    private var appMetadata: ByteArray? = null

    private val passkeyManager = PasskeyManager(
        config = PasskeyConfig(rpId = "nel349.github.io"),
    )

    // Persist sigil identity across app restarts
    private val sigilPrefs by lazy {
        getApplication<Application>().getSharedPreferences("sigil_identity", android.content.Context.MODE_PRIVATE)
    }

    init {
        // Load persisted sigil on startup
        loadPersistedSigil()
    }

    private fun loadPersistedSigil() {
        val did = sigilPrefs.getString("did", null) ?: return
        val credentialId = sigilPrefs.getString("credentialId", null) ?: return
        val publicKeyHex = sigilPrefs.getString("publicKeyHex", null) ?: return

        _sigilState.value = SigilState.Forged(
            did = did,
            credentialId = credentialId,
            publicKeyHex = publicKeyHex,
        )
        Log.i(TAG, "Loaded persisted sigil: ${did.take(30)}...")
    }

    private fun persistSigil(did: String, credentialId: String, publicKeyHex: String) {
        sigilPrefs.edit()
            .putString("did", did)
            .putString("credentialId", credentialId)
            .putString("publicKeyHex", publicKeyHex)
            .apply()
    }

    private val sigilBackup by lazy {
        SigilBackup(
            passkeyManager = passkeyManager,
            storage = BlockStoreBackupStorage(getApplication()),
        )
    }

    // ── Seed storage (canary for the "dApp creates its own wallet" flow) ──
    //
    // BBoard's prior life used a hardcoded TEST_SEED (alice). The canary
    // replaces that with a freshly generated seed persisted via SeedVault,
    // biometric-gated on every read. Same primitives Kicks will use for its
    // onboarding flow next.
    private val walletKeyManager by lazy { WalletKeyManager() }
    private val biometricGate by lazy { BiometricGate(walletKeyManager) }
    private val seedVault by lazy { SeedVault(getApplication(), biometricGate) }

    // Wallet status (balance / fund / register / addresses) lives in the
    // WalletStatusPanel module now. BBoard's prior `_walletStatus` flow +
    // refreshBalance / waitForFunding / registerDust methods were removed —
    // they duplicated work the panel does. SeedVault + WalletKeyManager
    // stay because the SDK build path below (connectWithSdk /
    // deployAndConnect) still needs to derive the BBoard wallet seed for
    // contract operations.

    /**
     * Bootstraps the wallet seed:
     *  - If [SeedVault] already has a seed: biometric-prompt and decrypt → return bip39Seed.
     *  - Otherwise: generate a fresh BIP-39 entropy → mnemonic → bip39Seed,
     *    persist via [SeedVault.storeSeed] (biometric-prompts to encrypt),
     *    and return a copy of the seed for immediate SDK use.
     *
     * Returns a 64-byte BIP-39 seed (the form [MidnightSdk.Builder.seed] expects).
     * Caller is responsible for wiping the returned array once the SDK has copied
     * it internally.
     */
    private suspend fun ensureSeedReady(activity: FragmentActivity): ByteArray {
        if (seedVault.hasSeed()) {
            Log.i(TAG, "Loading existing seed from SeedVault (biometric prompt)...")
            val plaintext = seedVault.loadSeed(activity)
            return try {
                plaintext.bip39Seed.copyOf()
            } finally {
                plaintext.wipe()
            }
        }

        Log.i(TAG, "No seed in SeedVault — generating a fresh wallet (biometric prompt)...")
        // SeedVault.storeSeed needs a Keystore master key to encrypt against. The
        // master key is created on-demand here (StrongBox if available, TEE fallback).
        // Idempotent: skip if already present from a prior install/run. The
        // `Master key not found. Create a wallet first.` ISE comes from skipping this
        // step — WalletKeyManager doesn't auto-create.
        if (!walletKeyManager.hasKey()) {
            val strongBox = walletKeyManager.generateKey()
            Log.i(TAG, "Generated Keystore master key (${if (strongBox) "StrongBox" else "TEE"})")
        }
        // Defer the seed material's existence until after biometric auth succeeds —
        // SeedVault invokes [seedProducer] only after the prompt is approved,
        // matching the existing storeSeed safety model.
        var capturedSeed: ByteArray? = null
        seedVault.storeSeed(activity) {
            val entropy = ByteArray(PlaintextSeed.ENTROPY_SIZE).also { SecureRandom().nextBytes(it) }
            val mnemonic = BIP39.entropyToMnemonic(entropy)
            val bip39Seed = BIP39.mnemonicToSeed(mnemonic)
            // Save a copy here, before SeedVault.storeSeed wipes the PlaintextSeed
            // it receives. We need the seed to hand to MidnightSdk.Builder.
            capturedSeed = bip39Seed.copyOf()
            PlaintextSeed(entropy, bip39Seed)
        }
        return requireNotNull(capturedSeed) { "Seed lambda ran but didn't capture (cancelled mid-auth?)" }
    }

    // Wallet bootstrap / balance / fund / register-dust methods used to
    // live here as the in-screen canary card's backing logic. All of that
    // moved to WalletPanelViewModel in :examples:common — BBoard no longer
    // needs its own implementation. The SDK build path (connectWithSdk +
    // deployAndConnect) remains in this file because it owns the contract
    // lifecycle, which is BBoard-specific.


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

                val forged = SigilState.Forged(
                    did = did,
                    credentialId = result.credentialId,
                    publicKeyHex = result.publicKey.compressedHex(),
                )
                _sigilState.value = forged
                persistSigil(did, result.credentialId, result.publicKey.compressedHex())
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
    fun backupSeed(activity: FragmentActivity) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Starting PRF backup of the SeedVault-stored seed...")

                // Load the user's actual seed from SeedVault (biometric prompt).
                // SigilBackup then re-encrypts (entropy, bip39Seed) under a
                // PRF-derived AES key before pushing to Block Store.
                val plaintext = seedVault.loadSeed(activity)
                val entropy = plaintext.mnemonicEntropy.copyOf()
                val bip39Seed = plaintext.bip39Seed.copyOf()
                plaintext.wipe()

                val sigil = _sigilState.value
                val did = when (sigil) {
                    is SigilState.Forged -> sigil.did
                    is SigilState.Authorized -> sigil.did
                    else -> "unknown"
                }
                val credId = when (sigil) {
                    is SigilState.Forged -> sigil.credentialId
                    is SigilState.Authorized -> sigil.credentialId
                    else -> ""
                }
                val pubKeyHex = when (sigil) {
                    is SigilState.Forged -> sigil.publicKeyHex
                    is SigilState.Authorized -> sigil.publicKeyHex
                    else -> ""
                }

                val meta = appMetadata
                if (meta != null) {
                    Log.i(TAG, "Backing up metadata (${meta.size} bytes): ${String(meta, Charsets.UTF_8)}")
                }

                sigilBackup.backup(
                    activity = activity,
                    entropy = entropy,
                    bip39Seed = bip39Seed,
                    did = did,
                    credentialId = credId,
                    publicKeyHex = pubKeyHex,
                    appMetadata = meta,
                )

                Log.i(TAG, "Backup SUCCESS — seed + sigil identity + ${meta?.size ?: 0} bytes app metadata")
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
    fun restoreSeed(activity: FragmentActivity) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Starting PRF restore...")

                val restored = sigilBackup.restore(activity)
                try {
                    Log.i(TAG, "Restore SUCCESS!")
                    Log.i(TAG, "  Restored seed size: ${restored.bip39Seed.size} bytes")

                    val restoredDid = restored.did
                    val restoredCredId = restored.credentialId
                    val restoredPubKey = restored.publicKeyHex
                    if (restoredDid != null) {
                        Log.i(TAG, "  DID: $restoredDid")
                        Log.i(TAG, "  Credential ID: $restoredCredId")
                        Log.i(TAG, "  Public key: $restoredPubKey")

                        // Restore the sigil state
                        _sigilState.value = SigilState.Forged(
                            did = restoredDid,
                            credentialId = restoredCredId ?: "",
                            publicKeyHex = restoredPubKey ?: "",
                        )
                        persistSigil(restoredDid, restoredCredId ?: "", restoredPubKey ?: "")
                        Log.i(TAG, "  Sigil identity restored!")
                    }

                    val restoredMeta = restored.appMetadata
                    if (restoredMeta != null) {
                        Log.i(TAG, "  App metadata: ${restoredMeta.size} bytes")
                        Log.i(TAG, "  App metadata: ${String(restoredMeta, Charsets.UTF_8)}")
                    }
                } finally {
                    restored.wipe()
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
        val sigil = _sigilState.value as? SigilState.Forged
        if (sigil == null) {
            Log.w(TAG, "Authorize skipped — sigil state is ${_sigilState.value::class.simpleName}, not Forged")
            return
        }
        val midnightSdk = sdk
        if (midnightSdk == null) {
            Log.w(TAG, "Authorize skipped — SDK not initialized (connect with standalone SDK first)")
            return
        }

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

    // Remote Wallet (`mn serve` over WebSocket) was the legacy connection
    // path — removed since the standalone SDK is the only supported route
    // now (see commit log + `feedback_sdk_devx_principle` memory). The
    // `NetworkChoice` enum that this method consumed is gone too;
    // contract operations take `MidnightNetwork` directly.

    /**
     * Lazily build (or rebuild) the SDK against [network]. Reuses the existing
     * SDK if one is already built for the same network; otherwise closes it,
     * loads the seed via [ensureSeedReady] (biometric prompt), and constructs a
     * fresh one. All call sites that need a usable SDK go through this — keeps
     * seed loading / network switching in one place.
     */
    private suspend fun buildOrReuseSdk(network: MidnightNetwork, activity: FragmentActivity): MidnightSdk {
        sdk?.let { existing ->
            if (sdkNetwork == network) return existing
            // Network changed — tear down the old subscription/db before rebuilding.
            existing.close()
            sdk = null
        }
        installProvingKeys()
        val seed = ensureSeedReady(activity)
        return try {
            val built = MidnightSdk.Builder(getApplication())
                .network(network)
                .seed(seed)
                .build()
            sdk = built
            sdkNetwork = network
            // Download wallet proving keys on non-zero-fee networks (same logic
            // the old connect path used).
            if (!built.provingKeyManager.hasWalletKeys() && network != MidnightNetwork.UNDEPLOYED) {
                built.provingKeyManager.downloadWalletKeys { /* progress ignored — caller decides UX */ }
            }
            built
        } finally {
            // The SDK builder copies the seed internally; wipe our local view.
            seed.fill(0)
        }
    }

    /**
     * Connect using the standalone SDK (no mn serve needed).
     *
     * @param contractAddress Deployed contract address (64 hex chars)
     * @param network Midnight network to use
     * @param activity Hosts the biometric prompt for SeedVault.loadSeed/storeSeed.
     */
    fun connectWithSdk(
        contractAddress: String,
        network: MidnightNetwork,
        activity: FragmentActivity,
    ) {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting("Initializing SDK...")
            try {
                _state.value = BBoardState.Connecting("Building SDK (deriving keys)...")
                val midnightSdk = buildOrReuseSdk(network, activity)

                if (!midnightSdk.provingKeyManager.hasWalletKeys() && network != MidnightNetwork.UNDEPLOYED) {
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
    fun deployAndConnect(network: MidnightNetwork, activity: FragmentActivity) {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting("Initializing SDK...")
            try {
                _state.value = BBoardState.Connecting("Building SDK...")
                val midnightSdk = buildOrReuseSdk(network, activity)

                if (!midnightSdk.provingKeyManager.hasWalletKeys() && network != MidnightNetwork.UNDEPLOYED) {
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

                val meta = buildAppMetadata("takeDown", 0)
                appMetadata = meta
                Log.i(TAG, "Generated ${meta.size} bytes of app metadata (takeDown)")

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
    /**
     * Install proving keys from `/data/local/tmp/` (adb-pushed staging dir).
     * Delegates to [com.midnight.kuira.core.compact.proving.ProvingKeyManager.installFromLocalTmp]
     * — single source of truth for the dev/test install flow shared with the
     * SDK e2e test and Kicks's match manager. Plus, BBoard's contract-side
     * `post`/`takeDown` keys (which are dApp-specific, not on the SDK side).
     */
    private fun installProvingKeys() {
        val provingKeyManager = com.midnight.kuira.core.compact.proving.ProvingKeyManager(getApplication())
        val ok = provingKeyManager.installFromLocalTmp()
        if (!ok) {
            Log.w(TAG, "installFromLocalTmp: hasWalletKeys() still false — adb-push keys to /data/local/tmp")
        }
        // Contract-specific keys (post, takeDown) live in bboard_keys/ but aren't
        // part of the wallet-key set; copy them flat into keysDir for the prover.
        val bboardSrc = java.io.File("/data/local/tmp/bboard_keys")
        if (bboardSrc.exists()) {
            provingKeyManager.installCircuitKeysForProving(
                circuitNames = listOf("post", "takeDown"),
                keysSourceDir = bboardSrc,
                zkirSourceDir = bboardSrc,
            )
        }
    }

    companion object {
        private const val TAG = "BBoard"
        // Fixed test key — in a real dApp, derive from wallet or secure storage
        private val SECRET_KEY = ByteArray(32) { (it + 1).toByte() }

        /**
         * Minimum NIGHT (in u128 base units) to consider the wallet "funded enough
         * to register for dust generation". The canary uses `mn transfer <addr> 100`
         * which credits 100_000_000 base units (100 NIGHT), well above this floor.
         */
        private val MIN_FUNDING_NIGHT = BigInteger.ONE

        /** How long [registerDust] keeps polling for dust to appear after a successful registration. */
        private const val DUST_VISIBLE_TIMEOUT_MS = 20_000L
        /** How often the post-registration poll re-reads balance. */
        private const val DUST_POLL_INTERVAL_MS = 2_000L

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

// NetworkChoice + the Remote Wallet preset table were removed along with
// the `connect(addr, NetworkChoice)` Remote Wallet path. Contract code
// now takes `MidnightNetwork` directly; URLs come from `NetworkConfig.forNetwork(...)`.

