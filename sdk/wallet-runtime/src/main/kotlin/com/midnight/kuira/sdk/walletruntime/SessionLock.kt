package com.midnight.kuira.sdk.walletruntime

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.midnight.kuira.sdk.SyncStatus
import com.midnight.kuira.sdk.walletseed.WalletSeedSource
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session auto-lock (#14). The decrypted seed lives in the process-singleton
 * [MidnightSdk] owned by [MidnightSdkProvider]; after one biometric every
 * value-bearing call runs with zero re-auth (the SDK cache bypasses Keystore's
 * short auth window). This locks the session by dropping that cache —
 * [MidnightSdkProvider.close] — so the next value-bearing action has to
 * re-authenticate. Because the wallet panel never auto-triggers biometric
 * (actions are user-initiated), a lock holds until the user acts; it is never
 * silently undone.
 *
 * **Triggers** (all funnel to [lock]):
 *  - foreground idle: no [onUserActivity] for [idleTimeoutMs]
 *  - app backgrounded: [onBackground] → lock after [backgroundGraceMs]
 *  - device screen off: [onScreenOff]
 *  - manual "Lock now": [lockNow]
 *
 * **Wiring.** Call [attach] once from the host `Application.onCreate` — it
 * registers the background (activity-count) + screen-off hooks. For foreground
 * idle, each `Activity` forwards `onUserInteraction()` → [onUserActivity].
 *
 * **The lock is whole-app, Chase-style.** The host wraps its Compose root in
 * [com.midnight.kuira.dapp.lock.SessionLockGate], which observes [locked] and,
 * while locked, covers the ENTIRE app with a re-auth screen (no content visible
 * or tappable behind it) and marks the window `FLAG_SECURE` so nothing leaks to
 * the recents thumbnail. [unlock] is the gate's biometric. This class owns the
 * policy + the unlock teeth; the gate owns the full-screen presentation.
 */
@Singleton
class SessionLock @Inject constructor(
    private val provider: MidnightSdkProvider,
    private val walletSeedSource: WalletSeedSource,
) {
    /** Foreground idle timeout before locking. Mutable so hosts can tune it. */
    var idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS

    /** Grace period after backgrounding before locking (covers brief app-switches). */
    var backgroundGraceMs: Long = DEFAULT_BACKGROUND_GRACE_MS

    /**
     * Scope the timers run on. `internal` so tests can substitute a
     * `TestScope` and drive virtual time; nothing launches at construction, so
     * replacing it before the first trigger is safe.
     */
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _locked = MutableStateFlow(false)

    /**
     * True from the moment a lock fires until the SDK is rebuilt (i.e. the user
     * re-authenticated). The wallet panel observes this to hide the balance while
     * locked. Distinct from `provider.sdk == null`, which is also briefly true
     * during a network-switch rebuild — that must NOT read as "locked".
     */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _inForeground = MutableStateFlow(false)

    /**
     * Whether the app is currently foregrounded (≥1 started Activity). Flipped by
     * the activity-lifecycle callbacks. The dust-sync foreground service (#235)
     * uses this to decide when a background notification is warranted — in-app the
     * `WalletSyncIndicator` already shows progress, so the service only surfaces
     * while backgrounded.
     */
    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    private var idleJob: Job? = null
    private var backgroundJob: Job? = null
    private var foregroundActivityCount = 0
    private var installed = false

    // Operation holds (#235 pivot fix). An auto-lock (idle/background/screen-off)
    // MUST NOT drop the SDK while a value-bearing op is in flight — that's what
    // killed an in-flight Kicks match commit ("SDK not ready"): during Unity
    // gameplay the main process is backgrounded, the grace expired, and the lock
    // closed the SDK out from under MatchManager. While ≥1 hold is active the auto
    // path defers (records the reason); the deferred lock fires when the last hold
    // releases. Manual lockNow() ignores holds (explicit user intent).
    private val holdLock = Any()
    private var holds = 0
    private var pendingLockReason: String? = null

    /** Reset the idle countdown — call on any user interaction. */
    fun onUserActivity() = restartIdleTimer()

    /** App returned to foreground: cancel the pending background lock, re-arm idle. */
    fun onForeground() {
        _inForeground.value = true
        backgroundJob?.cancel()
        restartIdleTimer()
    }

    /**
     * App left the foreground: SOFT-lock after the grace period unless we come back.
     * App-switching isn't the same threat as the device being secured, so backgrounding gates
     * the UI + requires re-auth on return but KEEPS the SDK alive — so background monitoring
     * (received-funds, sync) keeps running instead of dying the moment you switch apps. The
     * HARD triggers (screen-off / idle / manual) still wipe the SDK.
     */
    fun onBackground() {
        _inForeground.value = false
        idleJob?.cancel()
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(backgroundGraceMs)
            softLock("backgrounded")
        }
    }

    /** Device screen turned off — lock immediately. */
    fun onScreenOff() = lock("screen off")

    /** Manual "Lock now" — explicit user intent; ignores operation holds. */
    fun lockNow() {
        idleJob?.cancel()
        backgroundJob?.cancel()
        synchronized(holdLock) { pendingLockReason = null } // a manual lock supersedes any deferred one
        doLock("manual")
    }

    /**
     * Hold off the AUTO-lock (idle / background / screen-off) for the duration of
     * a value-bearing wallet operation, so a backgrounded transaction isn't torn
     * down mid-flight (the SDK stays alive until the op finishes). If an auto-lock
     * fired while the hold was active, it runs the moment the last hold releases.
     * Manual [lockNow] is unaffected.
     *
     * Usage: `sessionLock.acquireHold().use { /* on-chain op */ }` or [withHold].
     * Resolve via [SessionLockEntryPoint] in non-Hilt call sites (e.g. Kicks
     * MatchManager around commit/reveal/join/claim).
     */
    fun acquireHold(): AutoCloseable {
        synchronized(holdLock) { holds++ }
        return object : AutoCloseable {
            private var released = false
            override fun close() {
                var deferred: String? = null
                synchronized(holdLock) {
                    if (released) return
                    released = true
                    holds = (holds - 1).coerceAtLeast(0)
                    if (holds == 0 && pendingLockReason != null) {
                        deferred = pendingLockReason
                        pendingLockReason = null
                    }
                }
                deferred?.let {
                    Log.i(TAG, "Last operation hold released — running deferred lock ($it)")
                    doLock(it)
                }
            }
        }
    }

    /** Suspend-friendly [acquireHold]: holds for the duration of [block]. */
    suspend fun <T> withHold(block: suspend () -> T): T {
        val hold = acquireHold()
        return try {
            block()
        } finally {
            hold.close()
        }
    }

    /**
     * Re-authenticate and lift the lock — the unlock half of the session lock,
     * driven by the app-wide [com.midnight.kuira.dapp.lock.SessionLockGate].
     *
     * Runs the SAME biometric the wallet bootstrap uses:
     * [WalletSeedSource.ensureSeedReady] prompts because [requireFreshAuthNext]
     * armed it on [lock]. A successful prompt re-opens the Keystore auth window
     * (so the next SDK build is silent), and we wipe the returned seed
     * immediately — unlocking only needs to prove the user is present, not hold
     * the seed. Then [locked] clears so the gate dismisses and the whole app is
     * usable again.
     *
     * Returns true on success; false if the user cancelled or auth failed (the
     * gate stays up). [CancellationException] is rethrown so structured
     * concurrency keeps working.
     */
    suspend fun unlock(activity: FragmentActivity): Boolean {
        return try {
            val seed = walletSeedSource.ensureSeedReady(activity)
            seed.fill(0)
            _locked.value = false
            Log.i(TAG, "Session unlocked — re-authenticated, lock lifted")
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Every failure mode here (biometric cancelled, no sigil, Keystore
            // error) means the same thing: stay locked. Logged, not swallowed.
            Log.w(TAG, "Unlock failed (${e.javaClass.simpleName}) — staying locked", e)
            false
        }
    }

    /**
     * Hold off the auto-lock while a wallet sync is in flight, so a backgrounded
     * sync isn't cut mid-stream by the background grace (#235): the SDK stays
     * alive until the sync settles, so the whole sequence — and every phase it
     * publishes to the notification — completes. A foreground `refreshBalance`
     * already holds across its own chain; this extends the same protection to
     * SDK-internal syncs (the proactive `DustBalanceTracker`) that have no caller
     * to hold for them.
     *
     * Bounded, NOT a perpetual defer: the hold releases shortly after `syncStatus`
     * leaves `Syncing`, so once the wallet stops syncing the deferred lock fires
     * (security intact). The short linger bridges chained sub-syncs (dust →
     * shielded → genesis) that briefly settle between phases, so the lock doesn't
     * slip in through the gap.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun bindSyncHold() {
        scope.launch {
            var hold: AutoCloseable? = null
            var releaseJob: Job? = null
            provider.sdk
                .flatMapLatest { it?.wallet?.syncStatus ?: flowOf(SyncStatus.Idle) }
                .map { it is SyncStatus.Syncing }
                .distinctUntilChanged()
                .collect { syncing ->
                    if (syncing) {
                        releaseJob?.cancel(); releaseJob = null
                        if (hold == null) hold = acquireHold()
                    } else if (hold != null && releaseJob == null) {
                        releaseJob = scope.launch {
                            delay(SYNC_HOLD_LINGER_MS)
                            hold?.close(); hold = null; releaseJob = null
                        }
                    }
                }
        }
    }

    /**
     * Hold off the auto-lock while a value-bearing operation is in flight (#261-264):
     * a backgrounded send / dust-registration / contract call must not have the SDK
     * torn out from under it by the background grace. Mirrors [bindSyncHold], but rides
     * the wallet's [com.midnight.kuira.sdk.OperationRegistry.active] set instead of
     * `syncStatus`: hold while ≥1 operation is active, release a short linger after the
     * set empties (bridges a send → chained dust-registration so the lock doesn't slip
     * into the gap). The deferred lock then fires, security intact.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun bindOperationHold() {
        scope.launch {
            var hold: AutoCloseable? = null
            var releaseJob: Job? = null
            provider.sdk
                .flatMapLatest { sdk -> sdk?.operations?.active?.map { it.isNotEmpty() } ?: flowOf(false) }
                .distinctUntilChanged()
                .collect { active ->
                    if (active) {
                        releaseJob?.cancel(); releaseJob = null
                        if (hold == null) hold = acquireHold()
                    } else if (hold != null && releaseJob == null) {
                        releaseJob = scope.launch {
                            delay(OPERATION_HOLD_LINGER_MS)
                            hold?.close(); hold = null; releaseJob = null
                        }
                    }
                }
        }
    }

    /** Auto-lock path (idle/background/screen-off): defers while an operation is held. */
    private fun lock(reason: String) {
        idleJob?.cancel()
        backgroundJob?.cancel()
        val held = synchronized(holdLock) {
            if (holds > 0) {
                pendingLockReason = reason
                holds
            } else 0
        }
        if (held > 0) {
            Log.i(TAG, "Lock ($reason) DEFERRED — $held operation(s) in flight; will lock when they finish")
            return
        }
        doLock(reason)
    }

    /** The actual lock: drop the cached SDK + force re-auth. Bypasses holds. */
    private fun doLock(reason: String) {
        Log.i(TAG, "Locking session ($reason) — dropping cached SDK + forcing re-auth")
        _locked.value = true
        // Two parts make a lock REAL: drop the in-memory SDK (its decrypted seed),
        // AND force the next seed load to prompt for biometric — otherwise the
        // Keystore auth-validity window would let it silently re-decrypt within
        // ~30s and the "lock" would be a no-op.
        walletSeedSource.requireFreshAuthNext()
        provider.close()
    }

    /**
     * SOFT lock (the app-switch trigger): gate the UI + require re-auth on return, but KEEP the
     * SDK alive so background monitoring (received-funds, sync) keeps running. Unlike [doLock]
     * it does NOT `provider.close()`, so the in-memory wallet (and its observers) survive — the
     * device-secured triggers (screen-off / idle / manual) still wipe via [doLock]. Bypasses
     * holds (it's non-destructive). [unlock] still runs the real biometric, since
     * [requireFreshAuthNext] arms it here too.
     *
     * Caveat: while backgrounded WITHOUT a foreground service, Android still throttles the app's
     * network (it tears the live sockets down — see [WalletForegroundService]'s abort root
     * cause), so monitoring is BEST-EFFORT — a receipt is caught when a subscription retry
     * reconnects, not instantly. Reliable always-on background receive needs a kept-alive FGS or
     * a server/indexer push (the #264 follow-on).
     */
    private fun softLock(reason: String) {
        Log.i(TAG, "Soft-locking session ($reason) — gating UI + re-auth, KEEPING the SDK alive for monitoring")
        _locked.value = true
        walletSeedSource.requireFreshAuthNext()
        // No provider.close() — the wallet stays alive so received-funds / sync observers keep
        // running; a screen-off / idle / manual lock escalates to the full wipe.
    }

    private fun restartIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            lock("idle")
        }
    }

    /**
     * Register the app-level lock triggers (background + screen-off). Idempotent.
     * Prefer the [attach] companion which resolves the singleton for you.
     */
    fun installPlatformHooks(application: Application) {
        if (installed) return
        installed = true

        // Cold start = a brand-new session. The in-memory `_locked` and the
        // SeedVault `requireFreshAuth` flag both died with the previous process, and
        // the Keystore's ~30s auth-validity window would otherwise let a quick
        // kill+relaunch silently re-decrypt the seed — bypassing the lock. Arm a
        // fresh biometric for the FIRST seed load this process, so every launch
        // re-authenticates before the wallet is usable.
        walletSeedSource.requireFreshAuthNext()

        bindSyncHold()
        bindOperationHold()

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (foregroundActivityCount++ == 0) onForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                // configuration-change stops can briefly dip to 0; the grace
                // period in onBackground absorbs that without a spurious lock.
                if (--foregroundActivityCount <= 0) {
                    foregroundActivityCount = 0
                    onBackground()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
            }
        }
        // ContextCompat applies the API 34+ RECEIVER_NOT_EXPORTED requirement;
        // a bare registerReceiver silently fails to deliver on newer Android.
        ContextCompat.registerReceiver(
            application,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Clear the locked flag once the SDK is rebuilt (the user re-authenticated).
        // Started here, not in the constructor, so unit tests stay framework-free.
        scope.launch {
            provider.sdk.collect { sdk ->
                if (sdk != null && _locked.value) {
                    Log.i(TAG, "Session unlocked — SDK rebuilt after re-auth")
                    _locked.value = false
                }
            }
        }
        Log.i(TAG, "SessionLock installed (idle=${idleTimeoutMs}ms, bgGrace=${backgroundGraceMs}ms)")
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SessionLockEntryPoint {
        fun sessionLock(): SessionLock
    }

    companion object {
        private const val TAG = "SessionLock"

        /** 5 minutes of foreground inactivity. */
        const val DEFAULT_IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        /** 30s grace after backgrounding (survives brief app-switches). */
        const val DEFAULT_BACKGROUND_GRACE_MS = 30 * 1000L

        /**
         * Linger after a sync settles before releasing the [bindSyncHold] hold —
         * bridges chained sub-syncs (dust → shielded → genesis) that briefly leave
         * `Syncing` between phases, so the deferred lock doesn't fire in the gap.
         */
        private const val SYNC_HOLD_LINGER_MS = 2 * 1000L

        /**
         * Linger after the active-operation set empties before releasing the
         * [bindOperationHold] hold — bridges a send → chained dust-registration (and
         * Kicks circuit → circuit) so the deferred lock doesn't fire in the gap.
         */
        private const val OPERATION_HOLD_LINGER_MS = 2 * 1000L

        /**
         * Resolve the singleton [SessionLock] and register its app-level hooks.
         * Call once from the host `Application.onCreate`. Foreground idle still
         * needs each Activity to forward `onUserInteraction()` → [onUserActivity].
         */
        fun attach(application: Application) {
            EntryPointAccessors
                .fromApplication(application, SessionLockEntryPoint::class.java)
                .sessionLock()
                .installPlatformHooks(application)
        }
    }
}
