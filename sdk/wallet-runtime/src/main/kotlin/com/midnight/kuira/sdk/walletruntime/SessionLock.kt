package com.midnight.kuira.sdk.walletruntime

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The UI reflects a lock by observing [MidnightSdkProvider.sdk] going null (the
 * wallet panel hides the balance + re-auths on the next action); this class owns
 * only the policy, not the UI.
 */
@Singleton
class SessionLock @Inject constructor(
    private val provider: MidnightSdkProvider,
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

    private var idleJob: Job? = null
    private var backgroundJob: Job? = null
    private var foregroundActivityCount = 0
    private var installed = false

    /** Reset the idle countdown — call on any user interaction. */
    fun onUserActivity() = restartIdleTimer()

    /** App returned to foreground: cancel the pending background lock, re-arm idle. */
    fun onForeground() {
        backgroundJob?.cancel()
        restartIdleTimer()
    }

    /** App left the foreground: lock after the grace period unless we come back. */
    fun onBackground() {
        idleJob?.cancel()
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(backgroundGraceMs)
            lock("backgrounded")
        }
    }

    /** Device screen turned off — lock immediately. */
    fun onScreenOff() = lock("screen off")

    /** Manual "Lock now". */
    fun lockNow() = lock("manual")

    private fun lock(reason: String) {
        idleJob?.cancel()
        backgroundJob?.cancel()
        Log.i(TAG, "Locking session ($reason) — dropping cached SDK; next action re-auths")
        _locked.value = true
        provider.close()
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
        application.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

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
