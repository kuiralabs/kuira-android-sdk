package com.midnight.kuira.core.crypto.proving

/**
 * How transactions are proved — locally on the phone or via a remote proof server.
 */
enum class ProvingMode {
    /** Prove on the phone using cached proving keys. No network needed. */
    LOCAL,

    /** Send to a remote proof server via HTTP. */
    REMOTE;

    companion object {
        val DEFAULT = LOCAL
    }
}
