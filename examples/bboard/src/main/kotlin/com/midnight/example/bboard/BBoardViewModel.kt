package com.midnight.example.bboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * BBoard ViewModel — connects to Kuira wallet via Bound Service.
 *
 * Demonstrates the Android-native dApp → wallet integration:
 * 1. Bind to Kuira's ConnectorService (IPC, no network)
 * 2. Get configuration and addresses (direct Kotlin calls)
 * 3. Post/takedown messages (triggers wallet approval sheet)
 */
class BBoardViewModel(application: Application) : AndroidViewModel(application) {

    private val wallet = KuiraWalletClient(application)

    private val _state = MutableStateFlow<BBoardState>(BBoardState.Disconnected)
    val state: StateFlow<BBoardState> = _state

    fun connectToWallet() {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting
            try {
                val bound = wallet.bind()
                if (!bound) {
                    _state.value = BBoardState.Error("Could not bind to Kuira. Is it installed and running?")
                    return@launch
                }

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
                // In a real dApp: build contract tx → balanceUnsealedTransaction → submitTransaction
                // For this example: signData triggers the Kuira approval sheet
                wallet.signData(message, "text")

                _state.value = current.copy(
                    boardState = BoardState.Occupied(message = message, isOwner = true),
                )
            } catch (e: WalletError) {
                if (e.message.contains("PermissionRejected")) {
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
        try { wallet.unbind() } catch (_: Exception) {}
        _state.value = BBoardState.Disconnected
    }

    override fun onCleared() {
        try { wallet.unbind() } catch (_: Exception) {}
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
