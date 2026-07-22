package com.midnight.kuira.dapp.wallet

/** UI state for the restore-from-phrase flow. */
sealed interface RestoreUiState {
    data object Idle : RestoreUiState
    data object Restoring : RestoreUiState
    data object Success : RestoreUiState
    data class Error(val reason: String) : RestoreUiState
}
