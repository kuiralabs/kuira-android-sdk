package com.midnight.kuira.dapp.wallet

import android.content.Intent
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import com.midnight.kuira.core.identity.backup.AuthorizeOutcome
import com.midnight.kuira.core.identity.backup.DriveAuthManager
import com.midnight.kuira.dapp.sigil.IdentityProvenanceStore
import com.midnight.kuira.sdk.RestoredAppState
import com.midnight.kuira.sdk.walletruntime.DustRestoreGate
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verdict of a restore-consent prompt. There is deliberately no DECLINED: the system consent
 * dialog can't distinguish an explicit cancel from an accidental outside-tap/back dismissal,
 * and burning a durable never-ask-again on either proved catastrophic on-device (one stray
 * tap → permanent genesis replays). UNANSWERED is non-durable — the once-per-SDK-build
 * [restoreGateRan] latch is the anti-nag bound, and once a checkpoint exists the gate never
 * fires again anyway.
 */
internal enum class ConsentVerdict { GRANTED, UNANSWERED }

/**
 * The bundled wallet UI's [DustRestoreGate] (roadmap ). Contract (canonical copy on
 * `MidnightSdk.Builder.dustRestoreGate`): consent resolution ONLY, never a wallet sync call.
 * A `@Singleton` (not ViewModel state) so it exists before any SDK build and survives every
 * panel lifecycle — no registration race, nothing to clobber on ViewModel teardown.
 *
 * Decision ladder:
 *  - Restored prefs say OPTED OUT → mirror into [DustBackupStateStore], never prompt.
 *  - This identity already declined → nothing.
 *  - No backup signal (prefs don't say enabled AND the identity wasn't restored) → nothing —
 *  a brand-new wallet is never interrupted, and never even pays the Drive probe.
 *  - Silent Drive probe: grant already present (same-app reinstall / new device) → the SDK's
 *  checkpoint fetch right after this gate succeeds silently; just restore the toggle truth.
 *  - Consent missing → ONE prompt through [consentRequests] (the panel launches it). GRANTED
 *  commits the toggle; DECLINED is remembered per-identity; UNANSWERED (timeout, or no UI
 *  attached to show the dialog) burns nothing and takes the genesis path, same as today.
 */
@Singleton
class DustRestoreGateImpl @Inject constructor(
    private val driveAuth: DriveAuthManager,
    private val backupState: DustBackupStateStore,
    private val identityProvenance: IdentityProvenanceStore,
) : DustRestoreGate {

    private val _consentRequests = MutableSharedFlow<IntentSenderRequest>(extraBufferCapacity = 1)

    /** Collected by the wallet panel into the same consent launcher as the enable flow. */
    val consentRequests: SharedFlow<IntentSenderRequest> = _consentRequests.asSharedFlow()

    private val _restoreOffer = MutableStateFlow(false)

    /**
     * True while the gate is offering a restore — the panel renders the blocking
     * "Restore your wallet data?" step. The user answers via [connectRestore] (launches the
     * ONE Google consent; a dismissed system dialog returns here for retry) or [skipRestore]
     * (the explicit re-sync-from-scratch choice). The naked system dialog was too easy to
     * dismiss by accident — this step makes skipping a restore deliberate.
     */
    val restoreOffer: StateFlow<Boolean> = _restoreOffer

    private val pending = AtomicReference<CompletableDeferred<ConsentVerdict>?>(null)

    @Volatile
    private var pendingRequest: IntentSenderRequest? = null

    override suspend fun onColdRestore(restored: RestoredAppState?) {
        val prefs = restored?.prefs
        if (prefs?.dustBackupOptedOut == true) {
            backupState.commitOptedOut()
            Log.i(TAG, "restore gate: wallet opted out of backup — mirrored locally, no prompt")
            return
        }
        val enabledElsewhere = prefs?.dustBackupEnabled == true
        if (!enabledElsewhere && !identityProvenance.identityRestored) {
            Log.i(TAG, "restore gate: no backup signal (new wallet) — nothing to do")
            return
        }
        val outcome = try {
            driveAuth.authorize()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "restore gate: silent Drive probe failed — genesis fallback: ${e.message}")
            return
        }
        when (outcome) {
            is AuthorizeOutcome.Authorized -> {
                // Grant already present — the SDK's silent checkpoint fetch (right after this
                // gate) will succeed. Restore the toggle truth this install couldn't know.
                if (enabledElsewhere) {
                    backupState.commitEnabled()
                    Log.i(TAG, "restore gate: grant present — toggle truth restored ON")
                }
            }
            is AuthorizeOutcome.NeedsConsent -> {
                val request = IntentSenderRequest.Builder(outcome.intentSender).build()
                when (awaitConsent(request)) {
                    ConsentVerdict.GRANTED -> {
                        backupState.commitEnabled()
                        Log.i(TAG, "restore gate: consent granted — toggle truth restored ON")
                    }
                    ConsentVerdict.UNANSWERED ->
                        Log.i(TAG, "restore gate: consent unanswered/dismissed — proceeding without restore")
                }
            }
        }
    }

    private suspend fun awaitConsent(request: IntentSenderRequest): ConsentVerdict {
        // No UI collecting the offer state → nobody can render the restore step: skip
        // immediately (UNANSWERED, nothing burned) instead of stalling the dust-sync mutex
        // for the timeout (headless host, backgrounded app).
        if (_restoreOffer.subscriptionCount.value == 0) {
            Log.i(TAG, "restore gate: no restore-offer UI attached — skipping prompt")
            return ConsentVerdict.UNANSWERED
        }
        val deferred = CompletableDeferred<ConsentVerdict>()
        pending.set(deferred)
        pendingRequest = request
        _restoreOffer.value = true
        return try {
            // Generous bound: the offer is a BLOCKING sign-in step (the dust sync has nothing
            // useful to do before this decision on a fresh install), but an abandoned session
            // must not hold the mutex forever.
            withTimeoutOrNull(OFFER_TIMEOUT_MS) { deferred.await() } ?: ConsentVerdict.UNANSWERED
        } finally {
            // Signal any JOINED waiter (the enable flow's awaitPendingVerdict shares this
            // deferred): a real GRANTED already completed it, so this is a no-op then; on a
            // timeout OR a cancelled sync (SDK teardown / network switch) it's what releases
            // the joiner as UNANSWERED instead of leaving it to block its own full timeout
            // with the toggle stuck spinning.
            deferred.complete(ConsentVerdict.UNANSWERED)
            pending.set(null)
            pendingRequest = null
            _restoreOffer.value = false
        }
    }

    /** The user chose Connect & Restore — launch the one Google consent via the panel's launcher. */
    fun connectRestore() {
        val request = pendingRequest ?: return
        if (!_consentRequests.tryEmit(request)) {
            Log.w(TAG, "restore gate: consent launcher busy — user can retry from the offer step")
        }
    }

    /** The user explicitly chose to re-sync from scratch. Non-durable — a later install may re-offer. */
    fun skipRestore() {
        pending.get()?.complete(ConsentVerdict.UNANSWERED)
    }

    /**
     * Route a Drive consent activity result here first; true = a pending gate prompt consumed
     * it (the enable flow must then NOT interpret the same result — it observes the shared
     * verdict via [awaitPendingVerdict] instead).
     */
    fun completeConsent(data: Intent?): Boolean {
        val deferred = pending.get() ?: return false
        try {
            driveAuth.tokenFromConsent(data)
            deferred.complete(ConsentVerdict.GRANTED)
        } catch (e: Exception) {
            // The user dismissed the SYSTEM dialog (accidentally or not): do NOT resolve the
            // gate — the offer step is still showing, so they land back on Connect/Skip and
            // only an explicit Skip proceeds without the restore.
            Log.i(TAG, "restore gate: consent not completed — back to the offer step: ${e.message}")
        }
        return true
    }

    /**
     * The enable flow joining an in-flight gate prompt: awaits the SAME dialog's verdict
     * instead of stacking a second one, or returns null immediately when nothing is pending
     * (the enable flow then runs its own consent). On GRANTED the gate has already committed
     * the toggle + unblocked the silent checkpoint restore; the enable flow follows with its
     * upload sync. This replaced a cancel-the-gate takeover, which abandoned the restore and
     * let the sync fall to genesis while the user was still looking at the dialog.
     */
    internal suspend fun awaitPendingVerdict(): ConsentVerdict? {
        val deferred = pending.get() ?: return null
        return withTimeoutOrNull(OFFER_TIMEOUT_MS) { deferred.await() } ?: ConsentVerdict.UNANSWERED
    }

    private companion object {
        const val TAG = "DustRestoreGate"

        /** Offer bound — an abandoned sign-in session must not hold the dust sync forever. */
        const val OFFER_TIMEOUT_MS = 10 * 60_000L
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DustRestoreGateBindingModule {
    /** Makes wallet-runtime's `Optional<DustRestoreGate>` present for every dapp-ui host. */
    @Binds
    abstract fun bindDustRestoreGate(impl: DustRestoreGateImpl): DustRestoreGate
}
