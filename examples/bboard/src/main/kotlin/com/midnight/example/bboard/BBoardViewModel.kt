package com.midnight.example.bboard

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * BBoard ViewModel — two-phase wallet connection.
 *
 * Phase 1: Launch Kuira's approval Activity (user sees approval sheet)
 * Phase 2: Bind to ConnectorService via IPC (after approval)
 */
class BBoardViewModel(application: Application) : AndroidViewModel(application) {

    val wallet = KuiraWalletClient(application)

    private val _state = MutableStateFlow<BBoardState>(BBoardState.Disconnected)
    val state: StateFlow<BBoardState> = _state

    /** Called after the approval Activity returns */
    fun onApprovalResult(resultCode: Int) {
        if (wallet.isApproved(resultCode)) {
            bindAndLoadWallet()
        } else {
            _state.value = BBoardState.Error("Connection denied")
        }
    }

    /** Clean up before retrying */
    fun prepareForReconnect() {
        try { wallet.unbind() } catch (_: Exception) {}
        _state.value = BBoardState.Disconnected
    }

    private fun bindAndLoadWallet() {
        viewModelScope.launch {
            _state.value = BBoardState.Connecting
            try {
                val bound = wallet.bind()
                if (!bound) {
                    _state.value = BBoardState.Error("Could not bind to Kuira")
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
