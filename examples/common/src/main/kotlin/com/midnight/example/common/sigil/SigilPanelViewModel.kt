package com.midnight.example.common.sigil

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Self-contained sigil-identity bookkeeper for [SigilStatusPanel].
 *
 * Mirrors the structure of `WalletPanelViewModel`. This is the skeleton
 * commit — state holder + factory only. Subsequent commits migrate the
 * actual flows (forge / backup / restore / test PRF) over from
 * `BBoardViewModel`, which is where they live today.
 *
 * Authorization of a wallet access-key against the sigil's passkey stays
 * outside this VM: that flow needs an `MidnightSdk` instance and is the
 * one place where sigil + wallet panels have to be bridged by the host.
 */
class SigilPanelViewModel(app: Application) : AndroidViewModel(app) {

    private val _status = MutableStateFlow<SigilStatus>(SigilStatus.None)
    val status: StateFlow<SigilStatus> = _status

    companion object {
        /** Factory for `viewModel(factory = SigilPanelViewModel.Factory)`. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as Application
                SigilPanelViewModel(app)
            }
        }
    }
}
