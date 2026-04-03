# Phase 5: DApp Connector — Implementation Plan

**Date:** 2026-04-02
**Status:** Planning
**Priority:** Next after Phase 4C (Local Proving)
**Estimate:** 25-35 hours
**Depends on:** Phase 3 (Shielded Tx) ✅, Phase 4C (Local Proving) ✅

---

## What This Is

The DApp Connector exposes Kuira's wallet capabilities via the **Midnight ConnectedAPI**
— the same standard used by Lace and the CLI's `mn serve`.

**Prior investigation:** `docs/planning/PHASE_5_CONTRACT_TRANSACTIONS_INVESTIGATION.md`
covers contract architecture, transaction flows, and wallet requirements in depth.

### SDK Strategy — Multi-Platform Clients

Kuira is not just a wallet — it's an **SDK platform** for multiple client types:

| Client | Language | Transport | Status |
|---|---|---|---|
| **Agents (CLI/Terminal)** | TypeScript | WebSocket JSON-RPC | Works today (CLI `midnight-wallet-connector`) |
| **Android native** | Kotlin | Direct in-process API | Phase 5 builds this |
| **iOS native** | Swift | Direct in-process API | Future |
| **React Native** | TypeScript | Native modules → Kotlin/Swift | Future — shares code with agents |

Agents and RN both use TypeScript, sharing the `midnight-wallet-connector` package.
Android/iOS have direct Kotlin/Swift APIs for performance-critical paths.

All clients talk the same ConnectedAPI — dApps work with any wallet platform.

---

## Reference Implementation

The CLI wallet (`midnight-wallet-cli`) already has a working connector:

- **Server:** `src/lib/dapp-connector.ts` — 18 ConnectedAPI methods
- **Transport:** `src/lib/ws-rpc.ts` — WebSocket JSON-RPC 2.0
- **Client package:** `packages/connector/` — TypeScript client for dApps
- **Types:** `packages/connector/src/types.ts` — `WalletConnectedAPI` interface

The Kuira connector adapts this to Android, replacing:
- Terminal Y/n prompts → biometric approval dialogs
- WebSocket server → Android Service + local WebSocket
- Node.js runtime → Kotlin coroutines

---

## API Surface

**Source of truth:** `@midnight-ntwrk/dapp-connector-api` (official Midnight package)
at `midnight-libraries/midnight-dapp-connector-api/src/api.ts`

### Phase 1: InitialAPI (connection handshake)

DApps discover the wallet via `window.midnight` (WebView) or WebSocket handshake.
Before any operations, the dApp must call `connect(networkId)` to establish a session.

```typescript
type InitialAPI = {
  rdns: string;        // "com.kuira.wallet"
  name: string;        // "Kuira"
  icon: string;        // wallet icon URL
  apiVersion: string;  // matches @midnight-ntwrk/dapp-connector-api version
  connect(networkId: string): Promise<ConnectedAPI>;
}
```

After `connect()`, the dApp receives the full `ConnectedAPI`.

### Phase 2: ConnectedAPI (17 methods)

`ConnectedAPI = WalletConnectedAPI (15 methods) + HintUsage (1 method)`

#### Read-Only (10 methods — auto-approved)

| Method | Returns | Kuira Source |
|---|---|---|
| `getUnshieldedBalances()` | `Record<TokenType, bigint>` | BalanceRepository |
| `getShieldedBalances()` | `Record<TokenType, bigint>` | ShieldedRepository |
| `getDustBalance()` | `{ cap, balance }` | DustRepository |
| `getUnshieldedAddress()` | `{ unshieldedAddress }` | HDWallet → Bech32m |
| `getShieldedAddresses()` | `{ shieldedAddress, coinPk, encPk }` | ShieldedKeyDeriver |
| `getDustAddress()` | `{ dustAddress }` | DustKeyDeriver |
| `getTxHistory(page, size)` | `HistoryEntry[]` | Room DB |
| `getConfiguration()` | `Configuration` (see below) | NetworkConfig |
| `getConnectionStatus()` | `{ status, networkId }` | Session state |
| `hintUsage(methods)` | `void` | Log/permission prompt |

**`Configuration` type:**
```typescript
{
  indexerUri: string;
  indexerWsUri: string;
  proverServerUri?: string;  // @deprecated — use getProvingProvider instead
  substrateNodeUri: string;
  networkId: string;
}
```

#### Write (7 methods — require user approval)

| Method | Ledger Type Expected | Kuira Implementation |
|---|---|---|
| `makeTransfer(outputs, opts?)` | Builds full tx internally | ZswapTransferBuilder + LocalProver |
| `submitTransaction(tx)` | `Transaction<Sig, Proof, Binding>` (sealed) | TransactionSubmitter (relay to node) |
| `balanceUnsealedTransaction(tx, opts?)` | `Transaction<Sig, Proof, PreBinding>` (proven, unsealed) | Composable FFI — add inputs/outputs/dust, merge intents |
| `balanceSealedTransaction(tx, opts?)` | `Transaction<Sig, Proof, Binding>` (sealed) | Composable FFI — add dust in separate intent, merge |
| `makeIntent(inputs, outputs, opts)` | Builds unbalanced intent | Composable FFI — create intent with desired inputs/outputs |
| `signData(data, opts)` | Signs with unshielded key | TransactionSigner (Schnorr BIP-340) |
| `getProvingProvider(km)` | Returns `ProvingProvider` | LocalProver (Phase 4C!) — proves on phone |

**ADR-001 payoff:** The composable FFI primitives we built in Phase 3 directly enable
`balanceUnsealedTransaction`, `balanceSealedTransaction`, and `makeIntent` — these
require adding wallet coins/dust to existing transactions, which is exactly what
composable primitives were designed for.

**Phase 4C payoff:** `getProvingProvider` returns the local prover by default. DApps
can prove on the phone without a proof server. The official API marks `proverServerUri`
as `@deprecated` in favor of `getProvingProvider` — Kuira is ahead of this curve.

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│ DApp (WebView, or external app)                     │
│                                                     │
│ const wallet = createWalletClient({                 │
│   url: 'ws://localhost:9932'  // same as CLI         │
│ });                                                 │
│ await wallet.makeTransfer([...]);                   │
└──────────────┬──────────────────────────────────────┘
               │ WebSocket JSON-RPC 2.0
               ▼
┌─────────────────────────────────────────────────────┐
│ Kuira DApp Connector Service (Android Service)      │
│                                                     │
│ ┌───────────────────────────────────────────────┐   │
│ │ ConnectorServer                               │   │
│ │ - WebSocket server on localhost:9932           │   │
│ │ - JSON-RPC message routing                    │   │
│ │ - Read methods: auto-respond                  │   │
│ │ - Write methods: show approval dialog         │   │
│ └────────────────────────┬──────────────────────┘   │
│                          │                          │
│ ┌────────────────────────▼──────────────────────┐   │
│ │ ConnectedAPIHandler                           │   │
│ │ - Implements all 18 methods                   │   │
│ │ - Delegates to existing Kuira services:       │   │
│ │   BalanceRepository, ShieldedRepository,       │   │
│ │   ZswapTransferBuilder, TransactionSubmitter,  │   │
│ │   LocalProver, etc.                           │   │
│ └───────────────────────────────────────────────┘   │
│                                                     │
│ ┌───────────────────────────────────────────────┐   │
│ │ ApprovalManager                               │   │
│ │ - Shows system dialog for write operations    │   │
│ │ - Biometric auth for high-value transfers     │   │
│ │ - Auto-approve within policy bounds           │   │
│ └───────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

---

## Implementation Steps

### Step 1: ConnectedAPIHandler — Core Logic (8-10h)

Implement all 18 methods as a Kotlin class that delegates to existing services.
No networking yet — just the business logic.

```kotlin
class ConnectedAPIHandler(
    private val balanceRepository: BalanceRepository,
    private val shieldedRepository: ShieldedRepository,
    private val dustRepository: DustRepository,
    private val transactionSubmitter: TransactionSubmitter,
    private val transferBuilder: ZswapTransferBuilder,
    private val networkConfig: NetworkConfig,
    // ... other deps
) {
    suspend fun getUnshieldedBalances(): Map<String, BigInteger>
    suspend fun getShieldedBalances(): Map<String, BigInteger>
    suspend fun makeTransfer(outputs: List<DesiredOutput>): String
    // ... all 18 methods
}
```

**TDD approach:** Write unit tests for each method using mocked repositories.
The handler is pure logic — no Android dependencies.

### Step 2: JSON-RPC Message Router (3-4h)

Parse incoming JSON-RPC messages, route to handler methods, format responses.
Same protocol as CLI's `ws-rpc.ts`.

```kotlin
class JsonRpcRouter(private val handler: ConnectedAPIHandler) {
    suspend fun handleMessage(jsonRpc: String): String {
        val request = parseRequest(jsonRpc)
        return when (request.method) {
            "getUnshieldedBalances" -> formatResult(handler.getUnshieldedBalances())
            "makeTransfer" -> formatResult(handler.makeTransfer(request.params))
            // ... all methods
            else -> formatError(ErrorCodes.InvalidRequest, "Unknown method")
        }
    }
}
```

### Step 3: WebSocket Server (4-5h)

Android Service that runs a local WebSocket server on `localhost:9932`.
DApps connect to this — same URL as CLI's `mn serve`.

```kotlin
class ConnectorService : Service() {
    private var server: WebSocketServer? = null

    override fun onCreate() {
        server = WebSocketServer(PORT, router)
        server?.start()
    }
}
```

**Library options:**
- **Java-WebSocket** (org.java-websocket) — lightweight, well-tested
- **Ktor Server** — we already use Ktor client, consistent
- **NanoHTTPD with WebSocket** — minimal dependency

### Step 4: Approval Flow (4-5h)

Write operations show a system dialog before execution.

```kotlin
class ApprovalManager(private val context: Context) {
    /**
     * Show approval dialog for a write operation.
     * Returns true if user approves, false if rejected.
     */
    suspend fun requestApproval(
        method: String,
        description: String,
        amount: BigInteger? = null,
    ): Boolean
}
```

**Approval rules:**
- Read methods: auto-approve (no dialog)
- `makeTransfer`: show amount + recipient, require approval
- `submitTransaction`: show tx details, require approval
- `signData`: show data being signed, require approval
- `balanceUnsealedTransaction` / `balanceSealedTransaction`: show fee info, require approval

**Future (Phase 8):** Policy engine auto-approves within agent spending limits.

### Step 5: Service Lifecycle + DI (3-4h)

- Register `ConnectorService` in AndroidManifest
- Hilt injection for all dependencies
- Start service when app launches (foreground service with notification)
- Stop when app closes
- Handle service restart / reconnection

### Step 6: Integration Testing (3-5h)

- Write a test dApp client (Kotlin WebSocket client in androidTest)
- Test all 18 methods via WebSocket JSON-RPC
- Test approval flow (auto-approve in test mode)
- Test with `midnight-starship` or `midnight-wallet-connector` npm package
- Test concurrent connections

---

## Dependency Chain

```
Step 1 (Handler logic)
  → Step 2 (JSON-RPC router) — can start in parallel
  → Step 3 (WebSocket server)
    → Step 4 (Approval flow)
      → Step 5 (Service lifecycle)
        → Step 6 (Integration testing)
```

Steps 1 and 2 can be done in parallel. Critical path: ~22-28 hours.

---

## Compatibility with CLI Connector

| Feature | CLI (`mn serve`) | Kuira |
|---|---|---|
| Protocol | WebSocket JSON-RPC 2.0 | Same |
| Port | 9932 | Same |
| ConnectedAPI | 18 methods | Same |
| Approval | Terminal Y/n | Android dialog / biometric |
| Proving | Remote proof server | Local (Phase 4C) or remote |
| Client package | `midnight-wallet-connector` npm | Same — works unmodified |

**DApps don't need to change any code** to work with Kuira instead of `mn serve`.

---

## Transport Model — Local vs Remote

### Phase 5: Local Transport (same device)

All communication happens ON the phone. No relay server, no tunnel, no internet needed
for the wallet↔dApp connection (the dApp still needs internet for blockchain access).

```
DApp (WebView or co-installed app)
  │
  │ localhost:9932 WebSocket (on-device)
  ▼
Kuira Connector Service (Android Service)
  │
  │ internet (any network)
  ▼
Midnight blockchain (localnet / preprod / mainnet)
```

**Use cases:**
- WebView inside Kuira — load any dApp webpage, auto-connects via `window.midnight`
- Co-installed Android apps — bind to Kuira's Android Service
- midnight-starship running in mobile browser — connects to localhost:9932
- Development/testing — any network, dApp runs on same device

**"Local" = transport, not network.** A dApp in Kuira's WebView can transact on mainnet.
The wallet connects locally on the phone, the blockchain connection goes to any network.

### Phase 8: Remote Agent Transport (different devices)

Agents running on cloud servers (AWS, Vercel, etc.) need a fundamentally different model.
A relay server is the WRONG approach for Midnight — it creates unnecessary trust
requirements and data exposure.

**The correct model: on-chain agent authorization via Compact smart contracts.**

```
SETUP (one-time, from Kuira app):
  1. Deploy AgentPolicy contract on Midnight
  2. Authorize agent's public key with spending policy
  3. Fund the contract — transfer tokens INTO it
     → tokens are now in the contract's custody
     → agent can only spend what's in the contract
     → agent CANNOT touch the main wallet balance

AGENT OPERATING (autonomous, no wallet connection):
  Agent signs transactions with its OWN key
  → submits directly to Midnight node
  → contract verifies: authorized PK? within policy? sufficient funds?
  → ZK proof: agent proves authorization WITHOUT revealing wallet identity

OWNER CONTROLS (from Kuira app):
  → Monitor: subscribe to contract events ("Agent spent 3 NIGHT")
  → Refill: send more tokens to contract
  → Revoke: remove agent PK → remaining tokens return to wallet
```

**Why on-chain, not relay:**

| Concern | Relay Model | On-Chain Model |
|---|---|---|
| Confidential data | Flows through tunnel (even encrypted) | Never leaves the chain |
| Funding | Agent has access to wallet | Agent has prepaid contract (like a prepaid card) |
| Policy enforcement | Wallet app enforces (can be bypassed) | Smart contract enforces (can't cheat) |
| Uptime | Relay server must be running | Blockchain is always on |
| Revocation | Wallet must be online | On-chain, immediate, permanent |
| Trust | Trust relay + encryption | Trustless (ZK proofs) |

**The agent never "connects to the wallet."** It has its own keys, its own funded
contract, and submits transactions directly to the blockchain. The only connection
to the wallet is: (1) you funded the contract, (2) you authorized the agent's PK.

This is Phase 8 work. The Compact contract design should be planned during Phase 5
but implemented after the local connector ships.

See also: `docs/planning/AGENT_STORE_VISION.md` for the broader agent marketplace vision.

---

## What This Unlocks

**Phase 5 (Local Connector):**
1. **DApp ecosystem** — any Midnight dApp connects to Kuira on-device
2. **midnight-starship** — game connects to phone wallet instead of CLI
3. **WebView dApps** — load dApps inside Kuira's WebView, auto-connect
4. **Development** — test dApps against real wallet on any network

**Phase 8 (Remote Agents — future):**
5. **Autonomous agents** — cloud agents spend within on-chain policy bounds
6. **No relay needed** — agents submit directly to blockchain
7. **Prepaid funding** — agents can only spend what you deposit
8. **ZK authorization** — agent proves it's authorized without revealing wallet

---

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Android kills background service | Medium | Medium | Foreground service with notification |
| Port 9932 conflict | Low | Low | Configurable port, fallback |
| WebSocket library compatibility | Low | Low | Multiple options, all tested on Android |
| BigInt serialization across JSON-RPC | Medium | Low | Same serialization as CLI connector |
| Approval dialog UX is clunky | Medium | Medium | Simple, clear dialogs. Polish in Phase 6 |

---

## References

- **CLI DApp Connector:** `midnight-wallet-cli/src/lib/dapp-connector.ts`
- **CLI WebSocket RPC:** `midnight-wallet-cli/src/lib/ws-rpc.ts`
- **Connector client package:** `midnight-wallet-cli/packages/connector/`
- **ConnectedAPI types:** `packages/connector/src/types.ts` (18 methods)
- **ADR-001:** Composable FFI primitives (enables balance/intent methods)
- **Phase 4C:** Local proving (enables getProvingProvider)
- **Kuira Vision V1:** Phase 7 section (DApp Connector goals)
