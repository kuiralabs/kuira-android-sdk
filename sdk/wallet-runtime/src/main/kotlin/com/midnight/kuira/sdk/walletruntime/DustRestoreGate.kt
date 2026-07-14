package com.midnight.kuira.sdk.walletruntime

import com.midnight.kuira.sdk.RestoredAppState
import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Host hook for dust-backup restore continuity (roadmap #61): awaited once before the first
 * NO-CHECKPOINT dust sync of each SDK build — the host's chance to obtain Drive consent so
 * the cloud-checkpoint restore succeeds instead of the sync silently replaying from genesis.
 *
 * Bound as a Hilt OPTIONAL (the [AppDataBackupProvider] pattern): the UI layer provides a
 * `@Singleton` implementation; hosts without one get an empty Optional and the factory wires
 * NO gate — so the SDK's once-only gate opportunity is never consumed by a no-op (a mutable
 * registry slot had exactly that bug: an SDK built before the UI registered burned the gate).
 *
 * Contract: [onColdRestore] resolves consent ONLY — promptly (grant / decline / nothing to
 * do) and never calling back into wallet sync APIs (it runs inside the dust-sync critical
 * section). [restored] is the decoded app-state blob (prefs + host payload), fetched by the
 * SDK — the gate can fire during build, before the host holds any SDK handle.
 */
fun interface DustRestoreGate {
    suspend fun onColdRestore(restored: RestoredAppState?)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DustRestoreGateModule {
    @BindsOptionalOf
    abstract fun dustRestoreGate(): DustRestoreGate
}
