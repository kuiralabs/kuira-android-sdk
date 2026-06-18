package com.midnight.kuira.sdk.walletruntime

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.midnight.kuira.sdk.ActiveOperation
import com.midnight.kuira.sdk.MidnightSdk
import com.midnight.kuira.sdk.SyncStatus
import com.midnight.kuira.sdk.formatNight
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** What the foreground service should do for a given (activeOps, syncing, hardLocked) state. */
internal enum class ForegroundServiceAction { Start, Update, Stop, None }

/** The distinct inputs to the FGS start decision (all booleans → cheap distinctUntilChanged). */
private data class StartTrigger(
    val activeOps: Boolean,
    val syncing: Boolean,
    val hardLocked: Boolean,
    val foreground: Boolean,
)

/**
 * Pure start/stop policy for [WalletForegroundService] (#261-264, generalizing #235) —
 * extracted so the lifecycle matrix is unit-testable without a Service harness.
 *
 * Rules, in order:
 *  1. **Hard-locked → tear down.** A HARD lock already had `provider.close()` cancel the SDK; a
 *     lingering notification would be stale + a privacy leak. A *soft* lock (app backgrounded,
 *     SDK kept alive) is deliberately NOT a teardown trigger — stopping the FGS while an
 *     operation is still in flight would defeat #261-264 (the op would lose process survival).
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
    hardLocked: Boolean,
    running: Boolean,
): ForegroundServiceAction {
    if (hardLocked) return if (running) ForegroundServiceAction.Stop else ForegroundServiceAction.None
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
                sessionLock.hardLocked,
            ) { ops, status, hardLocked -> Triple(ops, status, hardLocked) }
                .distinctUntilChanged()
                .collect { (ops, status, hardLocked) ->
                    val action = decideForegroundService(
                        activeOps = ops.isNotEmpty(),
                        syncing = status is SyncStatus.Syncing,
                        hardLocked = hardLocked,
                        running = true,
                    )
                    when (action) {
                        ForegroundServiceAction.Update -> {
                            pendingStop?.cancel(); pendingStop = null
                            updateNotification(buildNotification(ops, status))
                        }
                        ForegroundServiceAction.Stop ->
                            if (hardLocked) {
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
            op != null -> notifier.buildOperation(op.label ?: getString(op.kind.defaultLabelRes()), op.stage, op.contentIntent)
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
         *
         * [walletContentIntent] is where a received-funds alert taps to — the host's wallet
         * view (the SDK can't reference a host screen). When null the alert opens the app
         * launcher.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun attach(application: Application, walletContentIntent: PendingIntent? = null) {
            if (attached) return
            attached = true
            val ep = EntryPointAccessors.fromApplication(application, WalletForegroundEntryPoint::class.java)
            val provider = ep.sdkProvider()
            val sessionLock = ep.sessionLock()
            val finalizer = FinalizationNotifier(application)
            val alerter = AlertNotifier(application)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            // Background receive polling (#271): a Doze-aware periodic poll + an expedited
            // one-shot when the app backgrounds, so incoming unshielded NIGHT is noticed while
            // the app is backgrounded / locked (when the live observer below isn't running).
            // The worker self-gates on a persisted target + foreground state.
            scheduleReceivePolling(application, scope, sessionLock)

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
                    sessionLock.hardLocked,
                    sessionLock.inForeground,
                ) { ops, status, hardLocked, foreground ->
                    StartTrigger(ops.isNotEmpty(), status is SyncStatus.Syncing, hardLocked, foreground)
                }
                    .distinctUntilChanged()
                    .collect { t ->
                        val action = decideForegroundService(
                            activeOps = t.activeOps,
                            syncing = t.syncing,
                            hardLocked = t.hardLocked,
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
            // FinalizationNotifier uses ONE stable id, so each completion replaces the previous —
            // only the latest is ever shown.
            scope.launch {
                provider.sdk
                    .flatMapLatest { it?.operations?.outcomes ?: emptyFlow() }
                    .collect { outcome -> runCatching { finalizer.post(outcome) } }
            }

            // Clear the prior completion push the moment a NEW operation starts, so a stale
            // "done" can't linger — or be tapped to return to an already-finished op — during the
            // next one. distinctUntilChanged on isNotEmpty fires once per op session (not per
            // nested call), so it doesn't flicker mid-operation.
            scope.launch {
                provider.sdk
                    .flatMapLatest { it?.operations?.active ?: flowOf(emptyList<ActiveOperation>()) }
                    .map { it.isNotEmpty() }
                    .distinctUntilChanged()
                    .collect { anyActive -> if (anyActive) runCatching { finalizer.cancel() } }
            }

            // "Your turn" alerts (#264 inbound): a tracked op asked for the user's attention
            // (a step waiting on their input, a counterparty's move). SUPPRESS it when the
            // wallet UI is already foreground — no point summoning someone who's here.
            // `inForeground` reflects only THIS process; a dual-process host (e.g. a game whose
            // UI runs in another process) gates timing on its side (it fires the request only
            // once it knows the user has actually drifted away), so the two compose.
            scope.launch {
                provider.sdk
                    .flatMapLatest { it?.operations?.attentions ?: emptyFlow() }
                    .collect { attention ->
                        if (!sessionLock.inForeground.value) runCatching { alerter.postAttention(attention) }
                    }
            }

            // Incoming-funds alert (#264 inbound, #284): tell the user when NIGHT ARRIVES — even
            // while they're USING the app, since being in the app (mid-game, on another screen) is
            // not the same as watching the wallet balance.
            //
            // Each event is a GENUINE per-transaction receipt. The indexer's UTXO-set provenance
            // classification (SubscriptionManager) emits only transactions that created NIGHT to us
            // WITHOUT spending any of our UTXOs — so our OWN change is never announced as "received"
            // (the old balance-delta heuristic, suppressed only while an op was in flight, could
            // misfire on the post-submit settle). The amount is exactly what arrived in that
            // transaction, not a balance delta. The persisted cursor dedups against the background
            // poll and across process death; the upstream baseline keeps a first sync / resync from
            // re-announcing pre-existing history.
            //
            // UNSHIELDED only: shielded receipts need the seed-derived viewing key (#280 carve-out).
            scope.launch {
                val checkpoint = ReceiveCheckpointStore(application)
                provider.sdk
                    .flatMapLatest { sdk ->
                        sdk?.let { s -> s.wallet.receipts.map { s to it } } ?: emptyFlow()
                    }
                    .collect { (sdk, receipt) ->
                        val network = provider.activeConfig.value?.network ?: return@collect
                        val address = sdk.walletAddress
                        if (!ReceiveCheckpointStore.shouldAnnounce(
                                checkpoint.lastAnnouncedTxId(network, address),
                                receipt.transactionId,
                            )
                        ) {
                            return@collect
                        }
                        runCatching {
                            alerter.postReceived(
                                application.getString(R.string.kuira_alert_received_fmt, formatNight(receipt.amount)),
                                application.getString(R.string.kuira_alert_received_body),
                                walletContentIntent,
                            )
                        }
                        checkpoint.recordAnnouncedTxId(network, address, receipt.transactionId)
                    }
            }
        }

        /**
         * Schedule the background receive poll (#271). Idempotent: the periodic poll is unique
         * work (KEEP across launches); the on-background one-shot replaces any pending one.
         */
        private fun scheduleReceivePolling(
            application: Application,
            scope: CoroutineScope,
            sessionLock: SessionLock,
        ) {
            val networkConstraint = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            // Periodic poll. Android floors periodic work at 15 min and Doze batches it
            // further — the "reliable but delayed" path while the device is idle/locked.
            WorkManager.getInstance(application).enqueueUniquePeriodicWork(
                ReceivePollWorker.PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReceivePollWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(networkConstraint)
                    .build(),
            )
            // Expedited one-shot the moment the app backgrounds (foreground → background), so a
            // receipt that lands right after the user leaves surfaces in seconds, not at the
            // next periodic tick.
            scope.launch {
                var wasForeground = false
                sessionLock.inForeground.collect { foreground ->
                    if (wasForeground && !foreground) {
                        runCatching {
                            WorkManager.getInstance(application).enqueueUniqueWork(
                                ReceivePollWorker.ONESHOT_NAME,
                                ExistingWorkPolicy.REPLACE,
                                OneTimeWorkRequestBuilder<ReceivePollWorker>()
                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                    .setConstraints(networkConstraint)
                                    .build(),
                            )
                        }
                    }
                    wasForeground = foreground
                }
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
