# Changelog

All notable changes to the **Kuira Android SDK** are documented here.

The SDK ships as a set of modules published **together on one version** to Maven Central
under `io.github.kuiralabs:*` — `dapp-ui`, `midnight-sdk`, `wallet-runtime`, `wallet-seed`,
`identity`, `auth`, `crypto`, `compact-engine`, `indexer`, `ledger`, `network`, `connector`,
`designsystem`, `testing` — plus the `io.github.kuiralabs.contract` and
`io.github.kuiralabs.localnet` Gradle plugins. This file tracks that shared version line.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); the SDK follows
[Semantic Versioning](https://semver.org/) (pre-1.0, so minor bumps may break API).
Public-API entries from `alpha03` onward are reconciled against each module's checked-in
`api/*.api` binary-compatibility dump; `alpha01` (which predates those dumps) is described
from its release and history.

## [Unreleased] — `0.1.0-alpha05` (in development)

### Added
- **Contract constructor arguments** — deploy a contract passing constructor args, threaded through the circuit-call path (`compact-engine`).
- **Typed contract codegen + BigInt marshalling** hardening across the contract-call path (`compact-engine` / `midnight-sdk`).
- **Multi-contract `contracts { }` container** (`io.github.kuiralabs.contract`) — register several Compact contracts in one module, each generating its own typed `<Alias>Contract` facade. The generated-API task is name-discriminated per variant and per contract: `generate<Variant>ContractApi` for the single-contract shorthand (e.g. `generateDebugContractApi`) and `generate<Variant><Contract>ContractApi` for each container entry (e.g. `generateDebugVaultContractApi`). Aliases that collapse to the same class/task name are rejected at configuration time. Container entries are namespaced per alias so two contracts that share a circuit name never collide: circuit keys sync to `assets/<alias>-keys/` and the generated code goes into a per-alias package `com.midnight.kuira.contract.generated.<alias>`. The single-contract shorthand is unchanged (`assets/keys/`, flat package). Each facade exposes `CONTRACT_ALIAS` / `RUNTIME_ASSET` / `KEYS_ASSET_DIR` constants so a consumer loads the runtime JS + keys from one source that can't drift from the sync.
- **Typed read (view) methods** — a value-returning circuit gets a `read<Name>(...)` alongside its `call()`: it runs `MidnightContract.read` and decodes the ABI result-type (Uint/Field → BigInteger, Boolean, Enum → the generated enum, Bytes → ByteArray, Struct → the generated data class). Each decode is also a reusable `decode<Circuit>Result(json)` so a host's own batched `readMany` path decodes identically. A `pure` circuit instead gets `local<Name>(...)` (runs `MidnightContract.readLocal` against `initialState()`, no deployed instance) — for commitment/hash circuits computed before deploy. Compact enum arguments marshal as `CompactEnum(ordinal)`.
- **Uint range guards** — a `Uint<N>` circuit argument is range-checked to its ABI `maxval` at the typed boundary (u8/u64/u128 discriminated, read losslessly from the ABI), so an out-of-range value fails fast with a clear `require` instead of silently mis-decoding downstream. The guard composes across scalar args, struct fields, and `Vector<Uint>` elements (`io.github.kuiralabs.contract`).
- **Vector & Tuple typed returns** — a value-returning circuit that returns a `Vector<T>` (→ `List<T>`) or a `Tuple` (→ a positional `TupleN` data class, shape-deduped so distinct same-arity tuples don't collide) generates a typed `read<Name>()` / `local<Name>()`; the decoder recurses to any depth (`Vector<Struct>`, `Tuple<Uint, Struct>`, nested vectors). (`io.github.kuiralabs.contract`)
- **Typed ledger snapshot** — the generated facade exposes `ledger(): <Alias>Ledger` and `observeLedger(): Flow<<Alias>Ledger>` — a typed `val` per **exported** `Counter` / `Cell` ledger field (Uint/Field/Boolean/`Bytes<N>`/string `Opaque`/`Enum`/`Vector<Uint|Bytes>`/`Maybe<string>`), decoded and range-checked, replacing name-keyed `handle.ledger().getUint64(...)`. `Map`/`Set`/`MerkleTree` fields are read through the contract's view circuits (`read<Name>()`). (`io.github.kuiralabs.contract`)
- **Version-coherence pre-flight** — chain-time sourcing and a client↔node version check before value-bearing calls.

### Fixed
- **Sigil chip status color** — the floating sigil chip's status dot showed the reserved "protected" green regardless of state; it now derives from the sigil state via a new `SigilChipUi.tone` (`Protected` / `Neutral` / `Error`), so an absent / initializing / failed sigil no longer reads as protected. Adds the `SigilChipUi.Tone` enum + a `tone` field on `SigilChipUi` (`dapp-ui`).
- **Dust state closed under an in-flight balance** — a wallet teardown (network switch, logout, session hard-lock) could free the shared Dust local state while a balance/deploy was mid-flight reading it, throwing `DustLocalState has been closed` on the real chain (PreProd) where dust sync spans seconds; localnet's instant sync hid the window. The teardown now serializes against the balance mutex, so it waits for an in-flight balance before releasing the Dust state, and is serialized against an SDK rebuild so a config change can't tear down a freshly-built SDK (`midnight-sdk` / `wallet-runtime`).

### Changed
- **`MidnightSdk.close()` and `MidnightWallet.close()` are now `suspend`** — closing suspends until any in-flight balance releases the Dust state (see the Dust-state fix above). `MidnightSdkProvider.close()` is unchanged (non-suspend; it launches the teardown), so dApps that tear down through the provider need no change (`midnight-sdk`).

## [0.1.0-alpha04] — 2026-06-21

The frosted "Void" design system and the background-operation framework land here.
~70 new public types across the modules (verified against the `api/*.api` dumps).

### Added
- **Sovereign recovery phrase** — reveal a standard 24-word BIP-39 phrase behind biometrics and restore the exact wallet on any device; opt-in, one-way, on a `FLAG_SECURE` screen. `WalletRecovery`, `WalletSeedSource`, `EstablishResult`, `InvalidRecoveryPhraseException` (`wallet-seed`); reveal/restore Settings UI (`dapp-ui`). *(#252)*
- **Session auto-lock** — idle, background, and screen-lock re-authentication plus a manual "lock now". `SessionLock` (`wallet-runtime`), `SessionLockGate` (`dapp-ui`).
- **Reactive contract state** — `MidnightContract.observeLedger()` (a `Flow` pushed by block subscriptions) and `MidnightSdk.observeBlocks()`. *(#255)*
- **Durable protocol orchestrator** — a ledger-anchored saga that resumes multi-step flows after process death. `ProtocolScope`, `ProtocolResult` (`midnight-sdk`). *(#253/#254)*
- **NIGHT transfers** — `sendNight` with automatic change-UTXO consolidation, plus a 3-screen Send wizard with QR scan and back-stack nav. *(#240)*
- **Per-transaction receive amounts** — real inbound value derived from UTXO-set provenance; `NightAmount`. *(#284)*
- **Background receive notifications** — background push on incoming NIGHT with per-transaction value alerts. `BackgroundReceiveChecker`, `ReceivePollWorker`, `ReceiveCheckpointStore`, `WalletNotifications`, `AlertNotifier`, `FinalizationNotifier`, `SyncNotifier` (`wallet-runtime`), `ReceiptEvent` (`indexer`). *(#271)*
- **Foreground-operation framework** — live operation stage surfaced in a foreground-service notification and a wallet chip; operations can carry a return content intent. `OperationRegistry`, `ActiveOperation`, `OperationDescriptor`, `OperationKind`, `OperationOutcome`, `OperationResult`, `OperationTerminalStatus`, `OperationAttention` (`midnight-sdk`), `WalletForegroundService` (`wallet-runtime`). *(#261–#268)*
- **Cloud-backup controls (true disable)** — fully disable Dust and app-state cloud backups; disabling deletes the remote blobs and resets digests. `CloudBackupStatus`, `BackupStatusSnapshot`, `AppStateCloudBackup`; `MidnightWallet.disableDustCloudBackup()`; `BackupSection`/`BackupLaneState` (`dapp-ui`). *(#246)*
- **Automatic app-state backup** — silent, hash-guarded capture on each sync. `AppStateCloudBackupCoordinator`, `AppStateBackupDigestStore` (`wallet-runtime`).
- **Streamed cold sync** — shielded-state checkpoint + delta streamed to disk (no genesis re-replay, no first-sync GC-storm freeze). `ShieldedRepository`, `ZswapCheckpoint`, `ChainResetGuard` (`indexer`). *(#279/#290)*
- **Proactive Dust sync** and **automatic dust-proof recovery** (`Custom error: 170/171`) via delta re-sync.
- **Durable network preference** — `NetworkPreferenceStore` exposed through `MidnightSdkProvider`; `SyncStatus` / `SyncPhase`. *(#285)*
- **x86_64 native ABI** — the crypto `.so` now ships `arm64-v8a` **and** `x86_64`, so Intel-/Apple-silicon emulators run on-device proving. *(#45)*
- **Floating & resizable wallet/sigil chips** — opt-in draggable chips that dock to a screen edge as peek tabs. `WalletChipUi`, `SigilChipUi`, `WalletOverlay`, `WalletOverlayController`, `WalletOverlayHost` (`dapp-ui`).
- **Theme palettes** — seven built-in wallet themes (Kuira Monochrome, Paper, Catppuccin, Nord, Dracula, Tokyo Night, Rosé Pine), persisted. `WalletTheme`, `WalletThemes`, `ThemeStore` (`dapp-ui`).
- **Frosted "Void" design system** — GlassPanel v2, StarField, monochrome accent across the wallet, settings, recovery, and receive screens; `WalletAppShell`, `WalletSyncIndicator`, `ShimmerEffect`.
- **Redesigned Send flow** — amount presets, a prominent review step, and honest in-flight copy.
- **Hardened identity errors** — `SigilOverwriteException`, `PasskeyException`, `NoPasskeyCredentialException`, `DriveConsentRequiredException` (`identity`).
- **Localnet Gradle plugin** — `io.github.kuiralabs.localnet` (`KuiraLocalnetPlugin`, `AdbReverseLocalnetTask`, `ProvisionWalletKeysTask`): auto `adb reverse` of localnet ports and wallet-key provisioning.
- **Contract call helpers** — `IntentTtl`, `ContractOperationListener` (`compact-engine`).

### Changed
- **`PanelBar(...)` signature** — gained a `floating: Boolean` (and layout-key) parameter; recompile against the new arity.

## [0.1.0-alpha03] — 2026-06-10

First tag with checked-in `api/*.api` dumps. Reconstructed from history
(`v0.1.0-alpha01..v0.1.0-alpha03`). No `alpha02` was published.

### Added
- **Contract Gradle plugin** — `io.github.kuiralabs.contract`: syncs compiled `.compact` artifacts into the app's assets and enforces the runtime-version pin. *(#11)*
- **`kuiraDoctor` preflight** — build-time environment checks (assetlinks reachability, Compact runtime pin, SDK-bundled-runtime layer, minSdk/cleartext) that fail fast instead of crashing at runtime. *(#8/#9)*
- **One-call proving-key staging** — `ProvingKeyManager.installCircuitKeysFromAssets()` (`compact-engine`).
- **Cross-device Dust cloud backup** — encrypt-on-device Dust checkpoint to Google Drive `appDataFolder`, restored on a new device; consent UI in the wallet panel. Bidirectional merge + hash-guarded upload/fetch, seed-derived AES-256-GCM (Drive is transport only).
- **Durable network selection** — the chosen network persists across launches.
- **`ContractCallProgressBar`** — a drop-in progress component for deploy/call stages; `PanelBar` interaction states.
- **`hilt-navigation-compose`** api-exposed for `hiltViewModel()` in consumer UI.

### Changed
- Bundled Compact runtime **0.15.0 → 0.16.0**.
- Proving mode surfaced as **"on-device"** (was "local").
- Throttled full wallet resync; balance now tracked via the reactive observer.

## [0.1.0-alpha01] — 2026-05-28

First public alpha of the Kuira Android SDK — build Midnight zero-knowledge dApps on
Android from a single Gradle dependency (`io.github.kuiralabs:dapp-ui`).

### Added
- **On-device ZK proving** — the native Rust `midnight-zkir` engine over JNI; `ProvingMode.LOCAL` by default, no proof server (`arm64-v8a`).
- **Passkey-derived Sigil identity** — one biometric mints a `did:key` identity + wallet seed; no seed phrase.
- **Embedded self-custodial wallet** — shielded + unshielded balances and Dust, in-process.
- **Compact contract runtime** — deploy and call `.compact` contracts (`MidnightContract`), with typed ledger reads.
- **dApp connector** — the standard Midnight `ConnectedAPI` over a local WebSocket, Android Binder, or a WebView bridge.
- **Drop-in Compose wallet UI** — `dapp-ui` (Sigil panel, balances, send/receive).
- Published to Maven Central under `io.github.kuiralabs:*`.

---

[Maven Central](https://central.sonatype.com/namespace/io.github.kuiralabs) ·
[Documentation](https://kuiralabs.github.io/kuira-sdk-android/) ·
[Roadmap](https://kuiralabs.github.io/kuira-sdk-android/roadmap/)
