package com.midnight.kuira.feature.settings

import com.midnight.kuira.core.compact.proving.ProvingMode
import com.midnight.kuira.core.network.MidnightNetwork

/**
 * UI state for the Settings screen. Always content — Settings
 * has no loading or error states (all data is local).
 */
data class SettingsUiState(
    /** Current selected network. */
    val network: MidnightNetwork = MidnightNetwork.DEFAULT,
    /** Relative time since last sync (e.g., "12s ago"). */
    val lastSyncAgo: String = "—",
    /** Whether developer options are visible. */
    val devModeUnlocked: Boolean = false,
    /** Current proving mode (LOCAL or REMOTE). */
    val provingMode: ProvingMode = ProvingMode.DEFAULT,
    /** Remote proof server URL (only relevant when mode = REMOTE). */
    val remoteProofServerUrl: String = "",
    /** Build variant ("debug" or "release"). */
    val buildType: String = "",
    /** Short git commit hash. */
    val commitHash: String = "",
    /** App version name. */
    val versionName: String = "",
    /** Number of taps on the Version row (for 7-tap unlock). */
    val versionTapCount: Int = 0,
) {
    /** Display string for the proof server row. */
    val proofServerDisplay: String
        get() = when (provingMode) {
            ProvingMode.LOCAL -> "Local (on-device)"
            ProvingMode.REMOTE -> remoteProofServerUrl.ifBlank { "Not configured" }
        }
}
