package com.midnight.kuira.sdk

/**
 * The kind of value-bearing wallet operation tracked by [OperationRegistry] and
 * surfaced by the background foreground service (#261-264).
 *
 * This is a *state*, not a display string — the SDK never emits user-facing
 * English. The presentation edge (the foreground-service notification) maps each
 * kind to a host-overridable label resource, exactly like [SyncPhase].
 *
 * [Custom] is the open door for third-party dApps: any caller can run its own
 * long-running work via [MidnightSdk.runForegroundOperation] with a label it owns,
 * and get the same process-survival + notification treatment the SDK's own
 * built-ins receive.
 */
enum class OperationKind {
    /** A NIGHT transfer ([MidnightSdk.sendNight]). */
    Send,

    /** Dust registration ([MidnightSdk.registerForDustGeneration]). */
    DustRegistration,

    /** A contract circuit call (execute → prove → balance → submit). */
    ContractCall,

    /** A caller-defined operation — the dApp supplies its own label. */
    Custom,
}
