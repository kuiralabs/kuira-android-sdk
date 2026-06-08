package com.midnight.example.bboard

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.compact.ContractCallException
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.dapp.defaultLabel
import com.midnight.kuira.core.compact.MidnightConfig
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.TransactionStatus
import com.midnight.kuira.core.compact.WitnessResult
import com.midnight.kuira.core.compact.proving.ProvingKeyManager
import com.midnight.kuira.core.identity.sigil.SigilStateStore
import com.midnight.kuira.core.ledger.api.TransactionSubmitter
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.walletruntime.MidnightSdkProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigInteger
import javax.inject.Inject

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
@HiltViewModel
class BBoardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sdkProvider: MidnightSdkProvider,
    private val sigilStateStore: SigilStateStore,
) : ViewModel() {

    private val _state = MutableStateFlow<BBoardState>(BBoardState.Setup)
    val state: StateFlow<BBoardState> = _state

    // Contract handle + its repository — BBoard's own state, reset on
    // [disconnect]. The SDK and its MidnightConfig are owned by
    // [MidnightSdkProvider] (shared with the wallet panel), so BBoard holds
    // neither; each contract op pulls the config fresh off the shared SDK.
    private var contract: MidnightContract? = null
    private var repository: BBoardRepository? = null

    // SDK construction + wallet seed bootstrap are owned by
    // [MidnightSdkProvider] (sdk:wallet-runtime). BBoard used to build its own
    // SDK via the now-deleted buildOrReuseSdk — that's exactly the duplicate
    // indexer subscription + double zswap replay we removed. BBoard now
    // consumes the one shared SDK via awaitSdk().

    // Wallet bootstrap / balance / fund / register-dust methods used to
    // live here as the in-screen canary card's backing logic. All of that
    // moved to WalletPanelViewModel in :sdk:dapp-ui — BBoard no longer
    // needs its own implementation. The SDK build path (connectWithSdk +
    // deployAndConnect) remains in this file because it owns the contract
    // lifecycle, which is BBoard-specific.

    // The "authorize access key" demonstrator was removed in the
    // post-A2 cleanup. Under PRF-derived sigil + wallet, both keys
    // are siblings of the same passkey — there's nothing additional
    // for the sigil to attest to about the wallet's access key.
    // The KeyAuthorization type stays in `core:identity` for future
    // delegated-access-key use cases (third-party/remote signing,
    // scoped + time-bounded permissions) — see Kicks wishlist #27.

    // Remote Wallet (`mn serve` over WebSocket) was the legacy connection
    // path — removed since the standalone SDK is the only supported route
    // now (see commit log + `feedback_sdk_devx_principle` memory). The
    // `NetworkChoice` enum that this method consumed is gone too;
    // contract operations take `MidnightNetwork` directly.

    /**
     * Connect to an already-deployed BBoard contract using the shared SDK.
     *
     * BBoard is a *follower*: it waits for [MidnightSdkProvider] to publish the
     * SDK (the wallet panel builds it, which is where the one biometric prompt
     * happens) and reads the active network from the provider. No biometric, no
     * SDK construction, no network parameter here — those are the panel's job.
     *
     * @param contractAddress Deployed contract address (64 hex chars).
     */
    /**
     * Bail with a clear message instead of hanging when no sigil is forged.
     * BBoard follows the shared SDK via awaitSdk, but the wallet panel only
     * builds it once a sigil exists — so without one awaitSdk would wait
     * forever. The Setup buttons are gated on this too; this is the
     * belt-and-suspenders for any path that reaches an action anyway.
     */
    private fun requireSigilOrFail(): Boolean {
        if (sigilStateStore.snapshot() == null) {
            _state.value = BBoardState.Error("Forge your sigil first — tap the sigil chip up top.")
            return false
        }
        return true
    }

    fun connectWithSdk(contractAddress: String) {
        viewModelScope.launch {
            if (!requireSigilOrFail()) return@launch
            try {
                installContractKeys()
                _state.value = BBoardState.Connecting("Waiting for wallet…")
                val midnightSdk = sdkProvider.awaitSdk()

                // Connect to contract immediately — show state to user fast.
                // The network is baked into midnightSdk.config; no separate
                // copy needed (the wallet pill is the network's UI).
                setupContract(
                    cfg = midnightSdk.config,
                    contractAddress = contractAddress,
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
    fun deployAndConnect() {
        viewModelScope.launch {
            if (!requireSigilOrFail()) return@launch
            try {
                installContractKeys()
                _state.value = BBoardState.Connecting("Waiting for wallet…")
                val midnightSdk = sdkProvider.awaitSdk()

                _state.value = BBoardState.Connecting("Deploying BBoard contract...")

                // Load verifier keys for each circuit from the proving keys directory
                val keysDir = midnightSdk.provingKeyManager.keysDir
                val verifierKeys = mapOf(
                    "post" to File(keysDir, "post.verifier").readBytes(),
                    "takeDown" to File(keysDir, "takeDown.verifier").readBytes(),
                )

                val bboard = MidnightContract.create(midnightSdk.config) {
                    name = "bboard"
                    contractJs = context.assets
                        .open("runtime/bboard-contract.js")
                    witness("localSecretKey") { WitnessResult(null, SECRET_KEY.copyOf()) }
                    initialPrivateState = mapOf("secretKey" to SECRET_KEY.copyOf())
                    coinPublicKey = midnightSdk.coinPublicKey
                    circuitVerifierKeys = verifierKeys
                }

                val deployResult = bboard.deploy { stage ->
                    val label = stage.defaultLabel() ?: "Working…"
                    Log.i(TAG, "Deploy stage: $label")
                    _state.value = BBoardState.Connecting(label)
                }

                val addr = deployResult.contractAddress
                Log.i(TAG, "Deployed at: $addr (${deployResult.timings})")

                // Wait for indexer to catch up with the newly deployed contract.
                // Read-only handle: no witnesses, no coinPublicKey — ledger()
                // only needs the contract JS + the address.
                _state.value = BBoardState.Connecting("Deployed at ${addr.take(8)}... Waiting for indexer...")
                val waitContract = MidnightContract.create(midnightSdk.config) {
                    contractJs = context.assets
                        .open("runtime/bboard-contract.js")
                    address = addr
                }
                val tempRepo = BBoardRepository(waitContract)
                var retries = 0
                while (retries < 10) {
                    kotlinx.coroutines.delay(2000)
                    val content = tempRepo.fetchBoardState()
                    if (content !is BoardContent.NotDeployed && content !is BoardContent.Error) break
                    retries++
                    _state.value = BBoardState.Connecting("Waiting for indexer... (${retries * 2}s)")
                }

                setupContract(
                    cfg = midnightSdk.config,
                    contractAddress = addr,
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
        coinPublicKey: ByteArray,
    ) {
        _state.value = BBoardState.Connecting("Loading contract...")
        val bboard = MidnightContract.create(cfg) {
            contractJs = context.assets
                .open("runtime/bboard-contract.js")
            address = contractAddress
            witness("localSecretKey") { WitnessResult(null, SECRET_KEY.copyOf()) }
            initialPrivateState = mapOf("secretKey" to SECRET_KEY.copyOf())
            this.coinPublicKey = coinPublicKey
        }
        contract = bboard

        val repo = BBoardRepository(bboard)
        repository = repo

        _state.value = BBoardState.Connecting("Fetching board state...")
        val boardContent = repo.fetchBoardState()
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
            contractAddress = contractAddress,
            boardState = boardState,
        )
    }

    /** Post a message to the board. */
    fun post(message: String) {
        val current = _state.value as? BBoardState.Connected ?: return
        val bboard = contract ?: return

        viewModelScope.launch {
            Log.i(TAG, "Posting: $message")
            _state.value = current.copy(boardState = BoardState.Working(null))
            try {
                val receipt = bboard.call("post", message) { stage ->
                    Log.i(TAG, "Post stage: ${stage.defaultLabel()}")
                    _state.value = current.copy(boardState = BoardState.Working(stage))
                }

                if (receipt.status == TransactionStatus.SUBMITTED) {
                    Log.i(TAG, "Post submitted! total=${receipt.timings.totalMs}ms")

                    // `appMetadata` (a simulated game-state blob computed post-tx
                    // and round-tripped through the backup pipeline) used to be
                    // populated here. The backup pipeline lives in the sigil
                    // panel module now and doesn't take host-specific metadata
                    // in its v1 API — when the panel grows a metadata hook,
                    // re-introduce the buildAppMetadata call site here.

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
            _state.value = current.copy(boardState = BoardState.Working(null))
            try {
                bboard.call("takeDown") { stage ->
                    Log.i(TAG, "TakeDown stage: ${stage.defaultLabel()}")
                    _state.value = current.copy(boardState = BoardState.Working(stage))
                }
                Log.i(TAG, "TakeDown submitted!")

                // Same as post(): appMetadata generation removed alongside the
                // sigil-panel migration. Re-introduce buildAppMetadata when
                // metadata round-trip lands as a panel-side API.

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

        viewModelScope.launch {
            val content = repo.fetchBoardState()
            val boardState = when (content) {
                is BoardContent.Vacant -> BoardState.Vacant
                is BoardContent.Occupied -> BoardState.Occupied(content.message)
                else -> return@launch
            }
            _state.value = current.copy(boardState = boardState)
        }
    }

    /**
     * Drop BBoard's contract handle and return to setup.
     *
     * Does NOT close the SDK or its `MidnightConfig` — those are owned by
     * [MidnightSdkProvider] and shared with the wallet panel. Closing them
     * here would disconnect the connector out from under the panel. BBoard
     * only abandons its own contract/repository references.
     */
    fun disconnect() {
        contract = null
        repository = null
        _state.value = BBoardState.Setup
    }

    // Stage → label mapping now comes from the SDK
    // (`com.midnight.kuira.dapp.defaultLabel()`); the connected screen renders
    // the SDK's `ContractCallProgressBar` directly from the live ContractCallStage.

    private fun installContractKeys() {
        ProvingKeyManager(context).installCircuitKeysFromAssets()
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
        val contractAddress: String,
        val boardState: BoardState,
        val lastTimingMs: Long? = null,
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
    data class Working(val stage: ContractCallStage?) : BoardState()
    data class Occupied(val message: String) : BoardState()
    data class CallError(val message: String) : BoardState()
}

// NetworkChoice + the Remote Wallet preset table were removed along with
// the `connect(addr, NetworkChoice)` Remote Wallet path. Contract code
// now takes `MidnightNetwork` directly; URLs come from `NetworkConfig.forNetwork(...)`.

