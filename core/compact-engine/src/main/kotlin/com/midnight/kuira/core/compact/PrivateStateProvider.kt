@file:Suppress("unused")

package com.midnight.kuira.core.compact

import com.midnight.kuira.core.compact.state.KeyStorePrivateStateProvider as KSProvider
import com.midnight.kuira.core.compact.state.PrivateStateProvider as PSProvider

// Re-export from the state subpackage for backward compatibility.
typealias PrivateStateProvider = PSProvider
typealias KeyStorePrivateStateProvider = KSProvider
