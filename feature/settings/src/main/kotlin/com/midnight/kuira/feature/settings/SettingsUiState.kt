package com.midnight.kuira.feature.settings

import com.midnight.kuira.core.network.MidnightNetwork

/**
 * UI state for the Settings screen. Always [Content] — Settings
 * has no loading or error states (all data is local).
 */
/** Display label when proof server is local (on-device proving). */
const val PROOF_SERVER_LOCAL = "Local (on-device)"

data class SettingsUiState(
    /** Current selected network. */
    val network: MidnightNetwork = MidnightNetwork.DEFAULT,
    /** Relative time since last sync (e.g., "12s ago"). */
    val lastSyncAgo: String = "—",
    /** Whether developer options are visible. */
    val devModeUnlocked: Boolean = false,
    /** Current proof server display URL. */
    val proofServerUrl: String = "",
    /** Build variant ("debug" or "release"). */
    val buildType: String = "",
    /** Short git commit hash. */
    val commitHash: String = "",
    /** App version name. */
    val versionName: String = "",
    /** Number of taps on the Version row (for 7-tap unlock). */
    val versionTapCount: Int = 0,
)
