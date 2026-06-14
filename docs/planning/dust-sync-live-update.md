# #235 — Proactive background dust-sync + Android Live-Update progress notification

**Status:** designed, not built (captured 2026-06-14; we pivoted to an urgent session-lock
regression). This is the implementation design for when #235 is picked up.

## Goal
Make dust sync **proactive, delta-only, and always-ready**, and **survive backgrounding**,
so a user can foreground→background the app mid-sync and watch progress in the status bar /
notification shade — Android's equivalent of iOS Live Activities. In-app, the branded
`RunnerDustProgress` runner already shows a percentage; this extends that to a background
notification.

**Scope shift from the original #235 note:** the original said "silent, notify only on
finish/failure." The new direction is the opposite — a *visible* Live Update with a
percentage while syncing. Plan to the new direction.

## Platform reality (don't over-promise)
- Android "Live Updates" = `Notification.ProgressStyle` + a **promoted ongoing**
  notification (the status-bar chip) — **API 36 / Android 16 only**. Need a fallback for
  API 30–35: a standard ongoing `NotificationCompat` notification with `setProgress`.
- Notifications are **templated** (icon + short text + progress bar + % + chip). The cool
  `RunnerDustProgress` runner graphic **cannot** render in the notification — it stays
  in-app. The notification is progress-bar + % + chip only.
- `POST_NOTIFICATIONS` runtime permission required on API 33+. FGS type `dataSync`
  (`FOREGROUND_SERVICE_DATA_SYNC`).
- minSdk 30 / compileSdk 36 (so API 36 APIs compile, gated at runtime).

## What already exists (reuse it)
- **Progress data:** `MidnightWallet.syncDust(onProgress: suspend (eventsProcessed, totalEvents))`
  → `DustRepository.streamDustEvents` emits `(processed, maxId)` every 5000 events;
  `eventsProcessed == -1` is the "replaying" indeterminate sentinel. `BalanceProgress`
  (core/compact-engine) + `WalletSyncProgress(fraction, label)` + `WalletSyncIndicator`
  (sdk/dapp-ui) already render % in-app. `WalletPanelViewModel` synthesizes
  `syncProgress: StateFlow<WalletSyncProgress?>` from the callback.
- **Live-subscription template:** `ShieldedBalanceTracker` (sdk/midnight-sdk) — initial
  resync, then `subscribeToZswapEvents(fromId)` collect loop, resync on chain-tip advance,
  3s→30s exponential backoff, on the SDK's `subscriptionScope`. `IndexerClient
  .subscribeToDustEvents(fromId)` is the dust analog. (`DustSubscriptionManager` is dead,
  per-event-replay code — do NOT revive it; it fights `DustRepository`'s chunked file replay.)
- **Lifecycle hooks:** `SessionLock` already tracks foreground/background via
  `Application.ActivityLifecycleCallbacks` and is attached via `SessionLock.attach(app)`.
  Reuse that instead of adding `lifecycle-process`.
- No FGS/notification infra in the SDK; the main app's `app/.../service/ConnectorService.kt`
  is the only reference pattern (channel + ongoing notification + `startForeground`).

## Keystone change (needed by everything)
Give the SDK an **observable** dust-sync status (today it's only a per-call lambda):
- `sdk/midnight-sdk/.../DustSyncProgress.kt`: `sealed interface DustSyncStatus { Idle;
  Syncing(fraction: Float?, eventsProcessed, totalEvents, label); UpToDate; Failed(msg) }`.
- `MidnightWallet`: add `val dustSyncStatus: StateFlow<DustSyncStatus>`; the internal
  `syncDust` path always publishes to it (keep the external `onProgress` for back-compat).
  Map `processed < 0` → indeterminate "Finalizing"; `total > 0` → fraction; done → UpToDate.

## Phase A — FGS + Live Update over the EXISTING sync (ship first)
- `sdk/wallet-runtime/.../DustSyncService.kt` — `@AndroidEntryPoint Service`,
  `foregroundServiceType="dataSync"`. Observes `provider.sdk.flatMapLatest { wallet
  .dustSyncStatus }` + `sessionLock`. Start only when **Syncing AND backgrounded** (in-app
  the `WalletSyncIndicator` already shows it); stop on foreground / `UpToDate` / **lock**.
  Companion `attach(application)` mirrors `SessionLock.attach` (installs the start/stop
  observer); needs a `DustSyncServiceEntryPoint` like `SessionLockEntryPoint`.
- `SessionLock`: expose `inForeground: StateFlow<Boolean>` (cheap addition to the existing
  activity-count callbacks) so the service gates without `lifecycle-process`.
- **Lock contract (must-have):** on `sessionLock.locked == true`, immediately
  `stopForeground(STOP_FOREGROUND_REMOVE)` + `stopSelf()`. A locked session already had
  `provider.close()` cancel the subscription scope; a lingering "syncing" notification would
  be a lie + a privacy leak.
- `sdk/wallet-runtime/.../SyncNotifier.kt` — pure builder (unit-testable). API 36:
  `Notification.ProgressStyle` + promoted ongoing (chip). API 30–35: `NotificationCompat`
  `.setOngoing(true).setSilent(true).setProgress(100, pct, indeterminate)`. Channel
  `IMPORTANCE_LOW`, `setShowBadge(false)`. Tap → `getLaunchIntentForPackage` (host-agnostic;
  can't reference a host Activity). Extract `DustSyncStatus.Syncing.toNotificationText():
  Pair<String, Int?>` as the test seam.
- Permissions in a NEW `sdk/wallet-runtime/src/main/AndroidManifest.xml` (merged into hosts):
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, + the
  `<service>`. Runtime `POST_NOTIFICATIONS` via an SDK helper `DustSyncNotifications
  .requestPermission(activity, onResult)`. **Graceful degradation:** if denied, the sync
  still runs (it's on `subscriptionScope`); just skip `startForeground`/the notification.

## Phase B — proactive always-ready live dust subscription (#235 core)
- `sdk/midnight-sdk/.../DustBalanceTracker.kt` — structural mirror of `ShieldedBalanceTracker`:
  initial `ensureSynced(onProgress)`, then `subscribeToDustEvents(fromId)`, resync on
  tip-advance (`event.id >= event.maxId`) via **delta** `refreshIncremental` (never genesis
  on the live path), same 3s→30s backoff + first-error-only logging.
- **Concurrency (mandatory):** the tracker must drive `MidnightWallet`'s existing
  `balanceMutex` path (a new internal `onChainTipAdvancedSyncDust()` that runs
  `balanceMutex.withLock { dustSyncManager.refreshIncremental(...) }`) — NOT a second mutex.
  Three writers now share the one `DustLocalState` (refresh / balanceAndSubmit / tracker);
  the mutex prevents the "DustLocalState has been closed" race (MidnightWallet.kt:516).
- Wire in `MidnightSdk.Builder.build()` next to `shieldedTracker.start`; `close()` it in
  `MidnightSdk.close()`. Gate behind `Builder.proactiveDustSync(enabled = false)` (opt-in;
  off in Phase A, on in Phase B) so existing hosts don't silently change behavior.

## Tests
- `SyncNotifierTest` (Robolectric `@Config(sdk=[30,34,36])`): `toNotificationText()` per
  `Syncing` shape; API-tier branch selection.
- `DustSyncServiceStartPolicyTest`: pure `decide(status, locked, inForeground): {Start,
  Update, Stop, None}` — especially `locked → Stop`, `inForeground → Stop/None`.
- `DustBalanceTrackerTest` (mirror `ShieldedBalanceTrackerTest`): resync only on tip-advance;
  backoff escalates; `close()` wipes the seed.
- `MidnightWalletDustProgressTest`: `syncDust` pushes the expected `DustSyncStatus` sequence.

## Risks / trade-offs
- **Android-15 `dataSync` FGS time limit** (~6h/24h): the FIRST sync is O(chain) (~994k
  events / ~849MB at PREPROD). Do NOT carry a genesis replay in the FGS — only delta on the
  background/live path; let the cold sync complete in-app/foreground.
- **Battery:** stop the FGS aggressively (UpToDate / foreground / lock); never idle-ongoing.
- **Shared `DustLocalState`/`balanceMutex` third writer** — must serialize (above).
- **`POST_NOTIFICATIONS` denial** — never couple sync correctness to it.
- **Lock interaction** — a locked session must tear the FGS down immediately.

## Synergy with the session-lock fix
The urgent session-lock "operation hold" fix (see SessionLock changes, 2026-06-14) covers
the *in-flight* crash where backgrounding mid-transaction drops the SDK. The
*between-operations* gap (e.g. a Kicks match idle while the main process is backgrounded for
the whole match) is exactly what this FGS solves by keeping the SDK alive in the background.
So #235's Phase A supersedes that follow-up.
