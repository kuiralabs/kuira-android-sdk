# Phase 5, Step 5: Connector Service, Approval UI & DX

**Date:** 2026-04-02
**Status:** Planning
**Depends on:** Steps 1-4 (all complete, 78 tests passing)

---

## What This Step Delivers

Three things:
1. **Android Foreground Service** — keeps the WebSocket server alive
2. **Approval UI** — the user-facing approval experience for write operations
3. **Hilt DI wiring** — connects all the pieces

---

## 1. Approval UI — The Core Experience

This is what dApp users see when an app wants to do something with their wallet.
It must be **the best in crypto** — clear, privacy-aware, and respectful of user attention.

### What Other Wallets Get Wrong

| Problem | MetaMask/Phantom | Kuira's Answer |
|---------|-----------------|----------------|
| **Generic prompts** | "Confirm transaction?" for everything | Action-specific screens with clear descriptions |
| **No privacy info** | Nothing about what's revealed | Privacy badge: "🔒 Shielded — recipient can't see your identity" |
| **Approval fatigue** | Dialog for every action | Session permissions via `hintUsage` — approve a batch upfront |
| **No risk signal** | Same UI for $1 and $10,000 | Color-coded risk: green/amber/red based on category and amount |
| **Buried fees** | "Gas: 0.003 ETH" in small text | Clear fee breakdown: "Dust fee: 12 DUST from your dust balance" |
| **No context** | Raw contract address | dApp name + what it's trying to do |

### Approval Screen Design (Bottom Sheet)

Bottom sheet, not a dialog. Slides up from the bottom — more content space,
feels native on mobile, doesn't block the entire screen.

```
┌─────────────────────────────────────────┐
│ ━━━━━ (drag handle)                     │
│                                         │
│ ┌─────────────────────────────────────┐ │
│ │  🔒 SHIELDED TRANSFER              │ │  ← Category header with privacy badge
│ │  Private — recipient can't see you  │ │     Color: green (transfer), amber (sign), red (large amount)
│ └─────────────────────────────────────┘ │
│                                         │
│  Starship wants to transfer             │  ← dApp name + action description
│                                         │
│  ┌─────────────────────────────────┐    │
│  │  5.00 NIGHT                     │    │  ← Amount (large, prominent)
│  │  → mn_shield-addr_undepl...456  │    │  ← Recipient (truncated, tappable to expand)
│  └─────────────────────────────────┘    │
│                                         │
│  Fee: 12 DUST (from your dust balance)  │  ← Fee info
│  Your balance after: 95.00 NIGHT        │  ← Post-tx balance (helps decision)
│                                         │
│  ┌──────────────┐ ┌──────────────────┐  │
│  │   Reject     │ │    ✓ Approve     │  │  ← Action buttons
│  └──────────────┘ └──────────────────┘  │
│                                         │
└─────────────────────────────────────────┘
```

### Category-Specific Screens

Each `ApprovalCategory` gets a tailored layout:

**TRANSFER** (makeTransfer)
- Shows: amount, token type, recipient address, shielded/unshielded badge
- Privacy note: "This is a shielded transfer" or "This is an unshielded transfer (visible on chain)"
- Risk: Green for small amounts, Amber for large

**SIGN** (signData)
- Shows: the data being signed (hex/base64/text preview)
- Warning: "You are signing data with your wallet key. Only sign data you trust."
- Risk: Amber always — signing arbitrary data is inherently risky

**TRANSACTION** (submitTransaction, balanceUnsealed, balanceSealed)
- Shows: transaction type (submit/balance), fee impact
- For balance operations: "Adding wallet inputs to balance this transaction"
- Risk: Green for balance operations, Amber for submit

**INTENT** (makeIntent)
- Shows: what you give (inputs) and what you get (outputs)
- Swap visualization: "Give 100 NIGHT → Get 50 TOKEN_X"
- Risk: Amber — involves token exchange

### The `hintUsage` Permission Flow

Per the official spec, dApps call `hintUsage()` to declare what methods they need.
This is our opportunity for **batch approval** — a better UX than per-action dialogs.

```
┌─────────────────────────────────────────┐
│ ━━━━━                                   │
│                                         │
│  Starship requests access to:           │
│                                         │
│  ✓ View your balances                   │  ← Read methods (auto-checked, non-interactive)
│  ✓ View your addresses                  │
│                                         │
│  ☐ Transfer tokens                      │  ← Write methods (user must check)
│  ☐ Sign data                            │
│                                         │
│  You can change these anytime in        │
│  Settings → Connected Apps              │
│                                         │
│  ┌──────────────┐ ┌──────────────────┐  │
│  │   Deny All   │ │  Grant Selected  │  │
│  └──────────────┘ └──────────────────┘  │
└─────────────────────────────────────────┘
```

Once granted via `hintUsage`, individual write operations **still show the approval
bottom sheet** but with a "Remember for this session" toggle. This matches the spec's
distinction between `Rejected` (one-time) and `PermissionRejected` (session-wide).

### Error Distinction: Rejected vs PermissionRejected

Per the official spec (SPECIFICATION.md line 405):
- **`Rejected`**: User rejected this specific action (tap "Reject" on the bottom sheet)
- **`PermissionRejected`**: User denied permission for this method entirely (via `hintUsage` or "Don't ask again")

Our `ApprovalManager` implementation should return both:
- `false` + reason `REJECTED` → user tapped Reject on the approval sheet
- `false` + reason `PERMISSION_REJECTED` → method was denied at the session level

This may require enhancing the `ApprovalManager` interface to return a result
enum instead of a plain Boolean:

```kotlin
enum class ApprovalResult {
    APPROVED,
    REJECTED,           // One-time rejection
    PERMISSION_REJECTED, // Session-wide denial
}
```

---

## 2. Android Foreground Service

### Why a Foreground Service?

The WebSocket server must stay alive while dApps are connected.
Android kills background services aggressively (especially on battery-optimized devices).
A foreground service with a persistent notification keeps the server running reliably.

### ConnectorService Design

```kotlin
@AndroidEntryPoint
class ConnectorService : Service() {
    @Inject lateinit var router: JsonRpcRouter

    private var server: ConnectorWebSocketServer? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        server = ConnectorWebSocketServer(router, scope)
        server?.start()
    }

    override fun onDestroy() {
        server?.stop(0)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null // Not a bound service
}
```

### Notification Design

Minimal, informative, non-intrusive:

```
┌─────────────────────────────────────────┐
│ 🔗 Kuira Connector                      │
│ Active — 1 dApp connected               │
│ Tap to manage connections               │
└─────────────────────────────────────────┘
```

- Shows connection count (updates as dApps connect/disconnect)
- Tap opens the Connected Apps settings screen
- Low priority — doesn't make sound or pop up

### Service Lifecycle

```
App launch → Start ConnectorService (foreground)
  → WebSocket server starts on localhost:9932
  → Ready for dApp connections

App in background → Service keeps running (foreground notification)
  → dApps stay connected

User stops connector (or app killed) → Service stops
  → WebSocket server stops
  → All connections closed gracefully
```

---

## 3. Hilt DI Wiring

### ConnectorModule

New Hilt module in `core:connector`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object ConnectorModule {

    @Provides @Singleton
    fun provideConnectedAPIHandler(
        networkConfig: NetworkConfig,
        // ... existing deps from other modules
    ): ConnectedAPIHandler

    @Provides @Singleton
    fun provideJsonRpcRouter(
        handler: ConnectedAPIHandler,
        approvalManager: ApprovalManager,
    ): JsonRpcRouter

    @Provides @Singleton
    fun provideApprovalManager(
        // Context or Activity reference for showing UI
    ): ApprovalManager
}
```

### Dependency Graph

```
NetworkModule (existing)
  → NetworkConfig
    → ConnectorModule (new)
      → ConnectedAPIHandler
        → JsonRpcRouter
          → ConnectorWebSocketServer
            → ConnectorService
```

The `ApprovalManager` implementation lives in the **app module** (not core:connector)
because it needs Android UI context. It's provided via Hilt binding.

---

## 4. Approval Activity for Service → UI Bridge

Android Services can't show dialogs directly. The approval bottom sheet
needs an Activity. Two approaches:

**Option A: Transparent Activity**
Launch a transparent `ApprovalActivity` that shows only the bottom sheet.
Feels like a system dialog but is actually a full-screen transparent activity.

**Option B: Overlay Permission**
Use `SYSTEM_ALERT_WINDOW` to draw over other apps.
Requires special permission. More intrusive.

**Recommendation: Option A** — transparent activity. Cleaner, no special permissions,
feels native. The activity is transparent except for the bottom sheet.

```kotlin
@AndroidEntryPoint
class ApprovalActivity : ComponentActivity() {
    // Receives approval request via intent extras
    // Shows bottom sheet
    // Returns result via ActivityResult
}
```

The `ApprovalManager` implementation in the app module:
1. Launches `ApprovalActivity` with the request data
2. Suspends via `suspendCancellableCoroutine`
3. Resumes when the activity returns a result (Approved/Rejected)

---

## 5. DX — Developer Experience

### For dApp Developers (TypeScript/Web)

**Zero changes needed.** The `midnight-wallet-connector` npm package already works
with any WebSocket JSON-RPC 2.0 server. DApps that work with `mn serve` (CLI wallet)
will work with Kuira unmodified — same port (9932), same protocol.

```typescript
// This existing code works with both CLI and Kuira:
import { WalletConnector } from '@aspect/wallet-connector';
const connector = new WalletConnector('ws://localhost:9932');
const api = await connector.connect('undeployed');
const balances = await api.getShieldedBalances();
```

### For Native Android dApp Developers

**Direct Kotlin API** (no WebSocket needed for same-process apps):

```kotlin
// Future: Android Bound Service or direct in-process usage
val handler = ConnectedAPIHandler(...)
val balances = handler.getShieldedBalances()
```

### For Agent Developers

Agents connect via WebSocket just like web dApps. The approval flow works
the same way — the user sees the approval bottom sheet on their phone
when the agent requests a write operation.

For Phase 8 (autonomous agents), the on-chain policy contract replaces
the approval UI — the agent operates independently within funded limits.

---

## 6. Implementation Order

```
6a. ConnectorModule (Hilt DI)
  → Wire existing deps into ConnectedAPIHandler
  → Provide JsonRpcRouter with auto-approve default

6b. ConnectorService (Foreground Service)
  → AndroidManifest registration
  → Notification channel
  → Start/stop lifecycle

6c. ApprovalActivity (Transparent Activity)
  → Bottom sheet Compose UI
  → Category-specific layouts (TRANSFER, SIGN, TRANSACTION, INTENT)
  → Privacy badges and risk coloring

6d. ApprovalManager Implementation
  → Bridge between Service and ApprovalActivity
  → Session permission tracking (hintUsage grants)
  → Rejected vs PermissionRejected distinction

6e. Connected Apps Management
  → Settings screen showing connected dApps
  → Revoke permissions
  → Connection history
```

Steps 6a-6b are pure infrastructure (no UI).
Steps 6c-6d are the approval experience.
Step 6e is future polish.

---

## 7. Open Questions

1. **Should the approval bottom sheet support biometric confirmation?**
   For high-value transfers, requiring fingerprint/face adds security.
   Could be a user preference in settings.

2. **Should we enhance ApprovalManager to return ApprovalResult enum?**
   Current interface returns Boolean. The spec distinguishes Rejected from
   PermissionRejected. We may need to update the interface before implementing
   the UI. Low cost to do this now.

3. **Should the notification show which dApp is connected?**
   Nice UX but requires tracking dApp identity (not currently in the protocol).

4. **Service auto-start on boot?**
   Some wallets auto-start their connector service on device boot.
   Probably not needed for v1 — start when the app opens.

---

## References

- **Official Spec:** `midnight-dapp-connector-api/SPECIFICATION.md`
- **Error Semantics:** Rejected (one-time) vs PermissionRejected (session) — spec line 405
- **hintUsage Pattern:** Spec lines 321-323
- **Existing Hilt Modules:** NetworkModule, IndexerModule, LedgerModule, BalanceModule
- **Compose Patterns:** Card-based layout, Material 3, sealed class state management
