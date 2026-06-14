package com.midnight.kuira.sdk.walletruntime

import android.app.Application
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.midnight.kuira.sdk.DustSyncStatus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** What the foreground service should do for a given (status, locked, foreground) state. */
internal enum class SyncServiceAction { Start, Update, Stop, None }

/**
 * Pure start/stop policy for [DustSyncService] (#235) — extracted so the lifecycle
 * matrix is unit-testable without a Service harness.
 *
 * Rules, in order:
 *  1. **Locked → tear down.** A locked session already had `provider.close()`
 *     cancel the SDK; a lingering "syncing" notification would be stale + a
 *     privacy leak. (Stop if running, else nothing to do.)
 *  2. Surface a notification only while **syncing AND backgrounded** — in the
 *     foreground the in-app `WalletSyncIndicator` already shows progress, so a
 *     notification would be redundant.
 *  3. Otherwise: stop if running; nothing if not.
 */
internal fun decideSyncService(
    status: DustSyncStatus,
    locked: Boolean,
    inForeground: Boolean,
    running: Boolean,
): SyncServiceAction {
    if (locked) return if (running) SyncServiceAction.Stop else SyncServiceAction.None
    val shouldShow = status is DustSyncStatus.Syncing && !inForeground
    return when {
        shouldShow && !running -> SyncServiceAction.Start
        shouldShow && running -> SyncServiceAction.Update
        !shouldShow && running -> SyncServiceAction.Stop
        else -> SyncServiceAction.None
    }
}

/**
 * Foreground service that keeps a backgrounded dust sync alive and shows its
 * progress as an Android Live-Update notification (#235).
 *
 * It does NOT run the sync — the proactive `DustBalanceTracker` (or any
 * on-demand sync) runs on the SDK's `subscriptionScope`; this service exists so
 * the OS doesn't kill that process while the app is backgrounded, and to surface
 * progress. Started by [attach]'s observer only when a sync is in flight while
 * backgrounded; it then self-manages (updates on progress, stops on
 * foreground / completion / session lock).
 *
 * Host opt-in: call [attach] once from `Application.onCreate` (next to
 * `SessionLock.attach`). The `<service>` + permissions are declared in this
 * module's manifest and merge into the host.
 */
@AndroidEntryPoint
class DustSyncService : Service() {

    @Inject lateinit var sdkProvider: MidnightSdkProvider
    @Inject lateinit var sessionLock: SessionLock

    private val notifier by lazy { SyncNotifier(this) }
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (scope != null) return START_NOT_STICKY // already running
        running.set(true)

        // Must call startForeground promptly. Seed with the current status (or an
        // indeterminate placeholder) so we satisfy the FGS contract immediately.
        val initial = (currentStatus() as? DustSyncStatus.Syncing)
            ?: DustSyncStatus.Syncing(null, 0, 0, "Syncing wallet…")
        startInForeground(notifier.build(initial))

        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
        s.launch {
            combine(
                sdkProvider.sdk.flatMapLatest { it?.wallet?.dustSyncStatus ?: flowOf(DustSyncStatus.Idle) },
                sessionLock.locked,
                sessionLock.inForeground,
            ) { status, locked, fg -> Triple(status, locked, fg) }
                .distinctUntilChanged()
                .collect { (status, locked, fg) ->
                    when (decideSyncService(status, locked, fg, running = true)) {
                        SyncServiceAction.Update ->
                            if (status is DustSyncStatus.Syncing) updateNotification(notifier.build(status))
                        SyncServiceAction.Stop -> stopServiceNow()
                        else -> { /* Start/None can't occur while running */ }
                    }
                }
        }
        return START_NOT_STICKY
    }

    private fun currentStatus(): DustSyncStatus =
        sdkProvider.sdk.value?.wallet?.dustSyncStatus?.value ?: DustSyncStatus.Idle

    private fun startInForeground(notification: android.app.Notification) {
        runCatching {
            ServiceCompat.startForeground(
                this,
                SyncNotifier.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        }.onFailure { Log.w(TAG, "startForeground failed: ${it.message}") }
    }

    private fun updateNotification(notification: android.app.Notification) {
        // notify() is a no-op if POST_NOTIFICATIONS is denied — sync still runs.
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
        private const val TAG = "DustSyncService"

        /** True while the service is foregrounded — gates [attach]'s Start decision. */
        private val running = AtomicBoolean(false)

        @Volatile private var attached = false

        /**
         * Install the start observer. Call once from `Application.onCreate` (next to
         * `SessionLock.attach`). Watches (dustSyncStatus, locked, inForeground) and
         * starts the FGS when a sync is in flight while backgrounded; the service
         * self-stops on foreground / completion / lock. No-op if not called — hosts
         * that don't opt in get no background sync notification.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun attach(application: Application) {
            if (attached) return
            attached = true
            val ep = EntryPointAccessors.fromApplication(application, DustSyncEntryPoint::class.java)
            val provider = ep.sdkProvider()
            val sessionLock = ep.sessionLock()
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                combine(
                    provider.sdk.flatMapLatest { it?.wallet?.dustSyncStatus ?: flowOf(DustSyncStatus.Idle) },
                    sessionLock.locked,
                    sessionLock.inForeground,
                ) { status, locked, fg -> Triple(status, locked, fg) }
                    .distinctUntilChanged()
                    .collect { (status, locked, fg) ->
                        if (decideSyncService(status, locked, fg, running.get()) == SyncServiceAction.Start) {
                            runCatching {
                                androidx.core.content.ContextCompat.startForegroundService(
                                    application,
                                    Intent(application, DustSyncService::class.java),
                                )
                            }.onFailure { Log.w(TAG, "startForegroundService failed: ${it.message}") }
                        }
                    }
            }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface DustSyncEntryPoint {
        fun sdkProvider(): MidnightSdkProvider
        fun sessionLock(): SessionLock
    }
}
