package com.midnight.example.bboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * BBoard ViewModel — connects to Kuira wallet and manages board state.
 *
 * Demonstrates the full dApp → wallet integration flow:
 * 1. Connect to wallet
 * 2. Get configuration and addresses
 * 3. Post/takedown messages (triggers wallet approval)
 */
class BBoardViewModel : ViewModel() {

    private val wallet = KuiraWalletClient()

    private val _state = MutableStateFlow<BBoardState>(BBoardState.Disconnected)
    val state: StateFlow<BBoardState> = _state

    fun connectToWallet() {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting
            try {
                val connected = wallet.connect()
                if (!connected) {
                    _state.value = BBoardState.Error("Could not connect to Kuira wallet. Is it running?")
                    return@launch
                }

                // Get wallet info — same calls the React bboard makes
                val status = wallet.getConnectionStatus()
                val config = wallet.getConfiguration()
                val shielded = wallet.getShieldedAddresses()
                val unshielded = wallet.getUnshieldedAddress()

                _state.value = BBoardState.Connected(
                    networkId = status.networkId ?: "unknown",
                    indexerUri = config.indexerUri,
                    nodeUri = config.substrateNodeUri,
                    unshieldedAddress = unshielded,
                    shieldedAddress = shielded.shieldedAddress,
                    coinPublicKey = shielded.coinPublicKey,
                    boardState = BoardState.Vacant,
                )
            } catch (e: Exception) {
                _state.value = BBoardState.Error(e.message ?: "Connection failed")
            }
        }
    }

    fun postMessage(message: String) {
        val current = _state.value as? BBoardState.Connected ?: return
        viewModelScope.launch {
            _state.value = current.copy(boardState = BoardState.Posting)
            try {
                // In a real dApp, this would:
                // 1. Build the contract transaction locally
                // 2. Call wallet.balanceUnsealedTransaction(tx) — wallet adds coins
                // 3. Call wallet.submitTransaction(balancedTx) — wallet submits

                // For this example, we simulate with a signData call
                // which triggers the Kuira approval sheet
                wallet.call("signData", buildJsonObject {
                    put("data", message)
                    put("options", buildJsonObject {
                        put("encoding", "text")
                    })
                })

                _state.value = current.copy(
                    boardState = BoardState.Occupied(message = message, isOwner = true),
                )
            } catch (e: WalletError) {
                if (e.message.contains("PermissionRejected")) {
                    // User rejected in Kuira approval sheet
                    _state.value = current.copy(boardState = BoardState.Vacant)
                } else {
                    _state.value = BBoardState.Error(e.message)
                }
            } catch (e: Exception) {
                _state.value = BBoardState.Error(e.message ?: "Post failed")
            }
        }
    }

    fun takeDown() {
        val current = _state.value as? BBoardState.Connected ?: return
        _state.value = current.copy(boardState = BoardState.Vacant)
    }

    fun disconnect() {
        wallet.disconnect()
        _state.value = BBoardState.Disconnected
    }

    override fun onCleared() {
        wallet.disconnect()
        super.onCleared()
    }
}

sealed class BBoardState {
    data object Disconnected : BBoardState()
    data object Connecting : BBoardState()
    data class Error(val message: String) : BBoardState()
    data class Connected(
        val networkId: String,
        val indexerUri: String,
        val nodeUri: String,
        val unshieldedAddress: String,
        val shieldedAddress: String,
        val coinPublicKey: String,
        val boardState: BoardState,
    ) : BBoardState()
}

sealed class BoardState {
    data object Vacant : BoardState()
    data object Posting : BoardState()
    data class Occupied(val message: String, val isOwner: Boolean) : BoardState()
}
