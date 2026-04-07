package com.midnight.example.bboard

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.midnight.kuira.core.compact.ContractCallException
import com.midnight.kuira.core.compact.ContractCallStage
import com.midnight.kuira.core.compact.MidnightConfig
import com.midnight.kuira.core.compact.MidnightContract
import com.midnight.kuira.core.compact.TransactionStatus
import com.midnight.kuira.core.compact.WitnessResult
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
 */
class BBoardViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<BBoardState>(BBoardState.Setup)
    val state: StateFlow<BBoardState> = _state

    private var config: MidnightConfig? = null
    private var contract: MidnightContract? = null
    private var repository: BBoardRepository? = null
    private var currentAddress: String? = null

    /** Connect to a deployed bboard contract. */
    fun connect(contractAddress: String, network: NetworkChoice) {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting("Initializing...")
            try {
                // 0. Install proving keys if available from adb push
                installProvingKeys()

                // 1. Create SDK config
                val cfg = MidnightConfig.Builder(getApplication())
                    .indexerUrl(network.indexerUrl)
                    .walletUrl(network.walletUrl)
                    .networkId(network.networkId)
                    .build()
                config = cfg

                // 2. Create contract handle
                _state.value = BBoardState.Connecting("Loading contract...")
                val bboard = MidnightContract.create(cfg) {
                    contractJs = getApplication<Application>().assets
                        .open("runtime/bboard-contract-iife.js")
                    address = contractAddress
                    witness("localSecretKey") { WitnessResult(null, SECRET_KEY) }
                    initialPrivateState = mapOf("secretKey" to ByteArray(32))
                    coinPublicKey = ByteArray(32)
                }
                contract = bboard
                currentAddress = contractAddress

                // 3. Create repository for reading state
                val repo = BBoardRepository(cfg)
                repository = repo

                // 4. Fetch current board state
                _state.value = BBoardState.Connecting("Fetching board state...")
                val boardContent = repo.fetchBoardState(contractAddress)
                val boardState = when (boardContent) {
                    is BoardContent.Vacant -> BoardState.Vacant
                    is BoardContent.Occupied -> BoardState.Occupied(boardContent.message)
                    is BoardContent.NotDeployed -> {
                        _state.value = BBoardState.Error("Contract not deployed at $contractAddress")
                        return@launch
                    }
                    is BoardContent.Error -> {
                        _state.value = BBoardState.Error(boardContent.reason)
                        return@launch
                    }
                }

                _state.value = BBoardState.Connected(
                    networkId = network.networkId,
                    contractAddress = contractAddress,
                    boardState = boardState,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                _state.value = BBoardState.Error(e.message ?: "Connection failed")
            }
        }
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
        config?.close()
        config = null
        contract = null
        repository = null
        _state.value = BBoardState.Setup
    }

    override fun onCleared() {
        config?.close()
        super.onCleared()
    }

    private fun stageLabel(stage: ContractCallStage): String = when (stage) {
        is ContractCallStage.FetchingState -> "Fetching state..."
        is ContractCallStage.Executing -> "Executing circuit..."
        is ContractCallStage.Proving -> "Generating ZK proof..."
        is ContractCallStage.Balancing -> "Balancing transaction..."
        is ContractCallStage.Submitting -> "Submitting to chain..."
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
    ) : BBoardState()
    data class Error(val message: String) : BBoardState()
}

sealed class BoardState {
    data object Vacant : BoardState()
    data class Working(val stage: String) : BoardState()
    data class Occupied(val message: String) : BoardState()
    data class CallError(val message: String) : BoardState()
}

/** Network configuration presets. */
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
