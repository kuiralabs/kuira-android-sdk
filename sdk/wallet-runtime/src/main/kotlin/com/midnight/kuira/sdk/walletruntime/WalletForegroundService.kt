package com.midnight.kuira.sdk.walletruntime

import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.midnight.kuira.sdk.ActiveOperation
import com.midnight.kuira.sdk.SyncStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** What the foreground service should do for a given (activeOps, syncing, locked) state. */
internal enum class ForegroundServiceAction { Start, Update, Stop, None }

/** The distinct inputs to the FGS start decision (all booleans → cheap distinctUntilChanged). */
private data class StartTrigger(
    val activeOps: Boolean,
    val syncing: Boolean,
    val locked: Boolean,
    val foreground: Boolean,
)

/**
 * Pure start/stop policy for [WalletForegroundService] (#261-264, generalizing #235) —
 * extracted so the lifecycle matrix is unit-testable without a Service harness.
 *
 * Rules, in order:
 *  1. **Locked → tear down.** A locked session already had `provider.close()` cancel the
 *     SDK; a lingering notification would be stale + a privacy leak.
 *  2. Surface the Live-Update notification whenever a value-bearing **operation** is in
 *     flight OR the wallet is **syncing** — foreground AND background — so it appears the
 *     moment work starts and the process is kept alive while it runs.
 *  3. Otherwise: stop if running; nothing if not.
 *
 * The sync-only case ([activeOps] = false) reduces EXACTLY to the prior #235 behaviour, so
 * the dust-sync foreground service is preserved as a strict subset.
 */
internal fun decideForegroundService(
    activeOps: Boolean,
    syncing: Boolean,
    locked: Boolean,
    running: Boolean,
): ForegroundServiceAction {
    if (locked) return if (running) ForegroundServiceAction.Stop else ForegroundServiceAction.None
    val shouldShow = activeOps || syncing
    return when {
        shouldShow && !running -> ForegroundServiceAction.Start
        shouldShow && running -> ForegroundServiceAction.Update
        !shouldShow && running -> ForegroundServiceAction.Stop
        else -> ForegroundServiceAction.None
    }
}

/**
 * Foreground service that keeps a backgrounded **wallet operation** (send, dust
 * registration, contract call — or any [com.midnight.kuira.sdk.MidnightSdk.runForegroundOperation])
 * OR a wallet **sync** alive, and shows its progress as an Android Live-Update notification
 * (#261-264, generalizing the #235 dust-sync service).
 *
 * It does NOT run the work — operations run on the SDK's `subscriptionScope`, syncs on the
 * wallet's tracker; this service exists so the OS doesn't kill the process while the app is
 * backgrounded, and to surface progress. Started by [attach]'s observer when an operation or
 * sync is in flight; self-manages (updates on change, stops on foreground-idle / completion /
 * session lock). An in-flight operation takes precedence over a sync for the notification text.
 *
 * Host opt-in: call [attach] once from `Application.onCreate`. The `<service>` + permissions
 * are declared in this module's manifest and merge into the host.
 */
@AndroidEntryPoint
class WalletForegroundService : Service() {

    @Inject lateinit var sdkProvider: MidnightSdkProvider
    @Inject lateinit var sessionLock: SessionLock

    private val notifier by lazy { SyncNotifier(this) }
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (scope != null) return START_NOT_STICKY // already running
        running.set(true)

        // Must call startForeground promptly. Seed with the current operation/sync (or an
        // indeterminate placeholder) so we satisfy the FGS contract immediately.
        startInForeground(buildNotification(currentOps(), currentStatus()))

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        s.launch {
            // A chained refresh / a send→dust-registration handoff runs as several pieces
            // back to back, each briefly emptying before the next re-enters. Without a linger
            // the FGS would tear down + restart in the gap. So a completion-triggered Stop is
            // DEFERRED briefly; a follow-up Start-condition cancels it and keeps the same
            // notification. A lock-triggered Stop is immediate (privacy).
            var pendingStop: Job? = null
            combine(
                sdkProvider.sdk.flatMapLatest { it?.operations?.active ?: flowOf(emptyList<ActiveOperation>()) },
                sdkProvider.sdk.flatMapLatest { it?.wallet?.syncStatus ?: flowOf(SyncStatus.Idle) },
                sessionLock.locked,
            ) { ops, status, locked -> Triple(ops, status, locked) }
                .distinctUntilChanged()
                .collect { (ops, status, locked) ->
                    val action = decideForegroundService(
                        activeOps = ops.isNotEmpty(),
                        syncing = status is SyncStatus.Syncing,
                        locked = locked,
                        running = true,
                    )
                    when (action) {
                        ForegroundServiceAction.Update -> {
                            pendingStop?.cancel(); pendingStop = null
                            updateNotification(buildNotification(ops, status))
                        }
                        ForegroundServiceAction.Stop ->
                            if (locked) {
                                pendingStop?.cancel(); pendingStop = null
                                stopServiceNow()
                            } else if (pendingStop == null) {
                                pendingStop = s.launch {
                                    delay(STOP_LINGER_MS)
                                    stopServiceNow()
                                }
                            }
                        else -> { /* Start/None can't occur while running */ }
                    }
                }
        }
        return START_NOT_STICKY
    }

    /** Operation takes precedence over sync for the notification text. */
    private fun buildNotification(ops: List<ActiveOperation>, status: SyncStatus): Notification {
        val op = ops.firstOrNull()
        return when {
            op != null -> notifier.buildOperation(op.label ?: getString(op.kind.defaultLabelRes()))
            status is SyncStatus.Syncing -> notifier.build(status)
            else -> notifier.buildIndeterminate()
        }
    }

    private fun currentOps(): List<ActiveOperation> =
        sdkProvider.sdk.value?.operations?.active?.value ?: emptyList()

    private fun currentStatus(): SyncStatus =
        sdkProvider.sdk.value?.wallet?.syncStatus?.value ?: SyncStatus.Idle

    private fun startInForeground(notification: Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                SyncNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    private fun updateNotification(notification: Notification) {
        // notify() is a no-op if POST_NOTIFICATIONS is denied — the work still runs.
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            NotificationManagerCompat.from(this).notify(SyncNotifier.NOTIFICATION_ID, notification)
        }
    }

    private fun stopServiceNow() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        running.set(false)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WalletForegroundService"

        /**
         * Grace before a completion-triggered Stop actually tears down the FGS, so a chained
         * sync (dust → refresh → genesis) or a send → dust-registration handoff that re-enters
         * within the window keeps the same notification instead of restarting the service.
         */
        private const val STOP_LINGER_MS = 2_000L

        /** True while the service is foregrounded — gates [attach]'s Start decision. */
        private val running = AtomicBoolean(false)

        @Volatile private var attached = false

        /**
         * Install the start observer + the finalization-push observer. Call once from
         * `Application.onCreate`. Watches (operations, syncStatus, locked) and starts the FGS
         * whenever an operation or sync is in flight; the service self-stops on completion /
         * lock. A SEPARATE observer rides the operation terminal stream and posts the
         * dismissible finalization notification (#264) — app-scoped, so it fires even after the
         * FGS has already stopped. No-op if not called.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun attach(application: Application) {
            if (attached) return
            attached = true
            val ep = EntryPointAccessors.fromApplication(application, WalletForegroundEntryPoint::class.java)
            val provider = ep.sdkProvider()
            val sessionLock = ep.sessionLock()
            val finalizer = FinalizationNotifier(application)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            // Start the FGS when an operation or sync begins — but ONLY while the app is
            // foreground. Android 12+ forbids starting a foreground service from the
            // background (ForegroundServiceStartNotAllowedException), which is exactly the
            // case for a process that's backgrounded when the work begins (e.g. a dual-process
            // host whose main process is stopped while another process is foreground). Once
            // started from the foreground the service survives backgrounding normally; a
            // background-started op runs best-effort under the session-lock hold. Including
            // `inForeground` in the trigger means returning to the foreground (while the op is
            // still in flight) re-evaluates and starts the service then.
            scope.launch {
                combine(
                    provider.sdk.flatMapLatest { it?.operations?.active ?: flowOf(emptyList<ActiveOperation>()) },
                    provider.sdk.flatMapLatest { it?.wallet?.syncStatus ?: flowOf(SyncStatus.Idle) },
                    sessionLock.locked,
                    sessionLock.inForeground,
                ) { ops, status, locked, foreground ->
                    StartTrigger(ops.isNotEmpty(), status is SyncStatus.Syncing, locked, foreground)
                }
                    .distinctUntilChanged()
                    .collect { t ->
                        val action = decideForegroundService(
                            activeOps = t.activeOps,
                            syncing = t.syncing,
                            locked = t.locked,
                            running = running.get(),
                        )
                        if (action != ForegroundServiceAction.Start) return@collect
                        if (!t.foreground) {
                            Log.i(TAG, "Work started while backgrounded — not starting the foreground service (Android background-start restriction); running best-effort under the session-lock hold.")
                            return@collect
                        }
                        runCatching {
                            ContextCompat.startForegroundService(
                                application,
                                Intent(application, WalletForegroundService::class.java),
                            )
                        }.onFailure { Log.w(TAG, "startForegroundService failed: ${it.message}") }
                    }
            }

            // Fire the dismissible finalization notification on each terminal outcome (#264).
            scope.launch {
                provider.sdk
                    .flatMapLatest { it?.operations?.outcomes ?: emptyFlow() }
                    .collect { outcome -> runCatching { finalizer.post(outcome) } }
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WalletForegroundEntryPoint {
        fun sdkProvider(): MidnightSdkProvider
        fun sessionLock(): SessionLock
    }
}
