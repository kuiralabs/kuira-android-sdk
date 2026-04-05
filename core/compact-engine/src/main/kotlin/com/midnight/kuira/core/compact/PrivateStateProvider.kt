@file:Suppress("unused")

package com.midnight.kuira.core.compact

// Re-export from core:crypto where these types now live.
// Keeps existing imports working without breaking changes.
typealias PrivateStateProvider = com.midnight.kuira.core.crypto.state.PrivateStateProvider
typealias KeyStorePrivateStateProvider = com.midnight.kuira.core.crypto.state.KeyStorePrivateStateProvider
