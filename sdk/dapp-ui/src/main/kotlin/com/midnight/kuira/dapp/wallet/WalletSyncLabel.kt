package com.midnight.kuira.dapp.wallet

import androidx.compose.ui.graphics.Color

/**
 * What a wallet surface says about sync — the one source for its text, glyph and tone.
 *
 * [NOT_SYNCED] exists because a FAILED sync is not an in-flight one: deriving "synced" from
 * "nothing running" renders an unreachable indexer as a confident zero.
 */
enum class WalletSyncLabel(val text: String, val glyph: String?) {
    SYNCING("Syncing…", null),
    SYNCED("Synced", "✓"),
    NOT_SYNCED("Not synced", "!"),
    ;

    fun color(colors: WalletPanelColors): Color = when (this) {
        SYNCING -> colors.onSheetDim
        SYNCED -> colors.positive
        NOT_SYNCED -> colors.error
    }

    companion object {
        fun of(syncing: Boolean, failed: Boolean): WalletSyncLabel = when {
            syncing -> SYNCING
            failed -> NOT_SYNCED
            else -> SYNCED
        }
    }
}
