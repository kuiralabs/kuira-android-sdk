# Changelog

All notable changes to `sdk:dapp-ui` are documented here. Follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) (see
`docs/projects/dapp-ui-extraction.md` § "API stability commitments").

## [Unreleased] — `0.1.0-SNAPSHOT`

This is the first published version of `sdk:dapp-ui`. The entries
below summarize both the migration from `examples/common` and the
production-bug-driven hardening that preceded it.

### Added

- **Cross-device dust cloud sync** — a "cloud sync" action in the wallet
  panel backs up the encrypted dust checkpoint to Google Drive
  `appDataFolder` and restores it on another device (or a fresh install)
  so the first dust sync is a fast delta instead of a ~900k-event genesis
  replay. Bidirectional: `WalletPanelViewModel.cloudSyncNow` runs
  `MidnightWallet.refresh()`, which auto-restores from the cloud checkpoint
  when the device has none, then uploads the latest. First use runs the
  Drive `drive.appdata` consent flow (`DriveAuthManager` +
  `StartIntentSenderForResult`); thereafter it's silent. The blob is
  AES-256-GCM encrypted with a seed-derived key before upload (Drive is
  transport only). Requires a one-time Google Cloud OAuth client per app
  (package + SHA-1) — surfaced as an actionable message when missing.
  Backend: `DriveBackupStorage`, `DustBackupEncryptor`,
  `DustCloudBundleCodec`, `SeedDerivedKeyDeriver` (`core:identity`);
  `DustCloudBackupCoordinator` (`sdk:wallet-runtime`);
  `DustCloudBackupSource` + cold-start seeding in `DustSyncManager`
  (`sdk:midnight-sdk`).

- **Dust-backup restore continuity** (roadmap #61) — makes the cloud sync
  above **restore-by-default** on a fresh install instead of silently
  replaying from genesis (up to hours on a real chain). The backup
  preference now travels with the wallet inside the seed-keyed app-state
  blob, so a reinstall knows a backup exists. Before the first
  no-checkpoint dust sync the panel runs a restore gate: a silent Drive
  probe (same-app reinstall / new device restore with zero prompts), else
  a **blocking** "Restore your wallet data?" step where Connect & Restore
  is the default and only an explicit "Skip — re-sync from scratch"
  proceeds without restoring (never durable; a dismissed system dialog
  returns to the step). New wallets get a one-time "Keep your wallet
  synced" setup offer after first bootstrap. The toggle position a fresh
  install couldn't know is restored, and an explicitly opted-out wallet is
  never prompted. `DustRestoreGate` (`sdk:wallet-runtime`, Hilt-optional);
  `DustRestoreGateImpl`, `DustBackupStateStore`, `IdentityProvenanceStore`,
  the two offer dialogs in `WalletStatusPanel`, and the gate wiring in
  `WalletPanelViewModel`.

- **`dapp-ui` module** at `sdk/dapp-ui` (formerly `examples/common`).
  First-class SDK module: published to Maven Local via the parent
  project's `publishToMavenLocal`, consumed by BBoard + Kicks as
  `com.midnight.kuira:dapp-ui`.
- **`PanelBar`** composable — drop-in pill row (sigil left, wallet
  right) for example apps. Wires the Problem A gate
  automatically: wallet auto-bootstrap is suppressed while the sigil
  panel still has a `BackupAvailable` or `Initializing` decision
  pending.
- **`SigilStatusPanel` + `WalletStatusPanel`** — composables hosting
  their own ViewModels via Hilt's `hiltViewModel()`.
- **`SigilStatus`** sealed class — `Initializing`, `BackupAvailable`,
  `None`, `Creating`, `Forged`, `Error`. The `Initializing` +
  `BackupAvailable` states are the Problem A gate primitives.
- **`WalletStatus`** sealed class — `None`, `Loading`, `Ready`,
  `Error`. `Ready` carries `address`, `shieldedAddress`, full
  `WalletBalance`, plus optional `busy` / `message` lines for
  progressive states.
- **`DappUiModule`** Hilt module providing `BlockStoreBackupStorage`
  and `SigilBackup` — minimal because `core:auth:AuthModule` and
  `core:identity:IdentityModule` already supply the wallet/passkey
  primitives.
- **Test suite** — 12 PillLabel formatter tests (migrated), 8
  SigilPanelViewModel tests, 3 WalletPanelViewModel tests, 2
  test-infrastructure smoke tests. Plus 4 `SeedVaultTest` tests in
  `core:auth` that the panel depends on. GitHub Actions runs them
  + a BBoard consumer smoke build on every PR and main push.

### Changed

- **ViewModel construction** moved off `AndroidViewModel` +
  `viewModelFactory` onto `@HiltViewModel` + `@Inject` constructors.
  Consumers resolve via `hiltViewModel()` at the Composable call
  site. Apps embedding the panels must apply the Hilt plugin and add
  a `@HiltAndroidApp` Application class (BBoard +
  Kicks updated accordingly).
- **`WalletReceiveScreen`** marked `internal` after audit — only
  reachable via the wallet panel's sheet, no third-party consumer
  uses it directly. Public-by-default → explicit-internal-by-design.

### Fixed

These fixes landed before the extraction but the new test suite makes
them regression-proof going forward. Each entry cites the original
commit so a reader can trace the original investigation:

- **Sigil chip status color** (03338c9) — the floating sigil chip's status
  dot rendered the reserved "protected" green regardless of state, so a
  disconnected / initializing / failed sigil still read as protected. The
  dot color now derives from the sigil state via a new `SigilChipUi.tone`
  (`Protected` / `Neutral` / `Error`); only a forged, active sigil is green.
  Regression-locked by `SigilChipToneTest`.
- **Master key missing on restore** (94ae65f) — a *fresh-install*
  Block Store restore (no prior wallet → forge a sigil → restore from
  cloud) failed silently because the Keystore master key was never
  generated. `SeedVault.storeSeed` threw "Master key not found" from
  inside the restore handler, the handler swallowed it, and the
  relaunch then bootstrapped a fresh wallet over the restored sigil
  — funds invisible. `restoreSeedIntoVault` now generates the master
  key on demand and rethrows on `storeSeed` failure (covered by
  `SigilPanelViewModelTest.restoreSeedIntoVault generates master key
  when missing`).
- **dismissBackup apply()-vs-SIGKILL race** (ac78fdf's class for the
  backup flag) — `apply()`'s async write lost a race with the
  restore-flow process kill, leaving the `BackupAvailable` prompt to
  re-appear on every launch even after the user dismissed it.
  Switched to `commit()` and pinned with
  `SigilPanelViewModelTest.dismissBackup writes durably so the flag
  survives restart`.
- **Auth-validity window thrash** — the restore flow showed four
  biometric prompts (backup retrieve → PRF assertion → SeedVault
  encrypt → relaunch). Centralized auth window in
  `core:auth:AuthPolicy`, plumbed `tryEncryptWithinAuthWindow` and
  `tryDecryptWithinAuthWindow` through `SeedVault` so prompts that
  fall inside the 30s window are silent. Dropped restore-flow
  prompts from 4 to 2.
- **Problem A — auto-bootstrap before user choice** (9465ea4) — on
  first launch with a cloud backup present, the wallet panel
  auto-created a fresh wallet *before* the user got the
  Restore-vs-Fresh choice, blowing past the backup. Added
  `SigilStatus.Initializing` + `BackupAvailable` and gated the
  wallet panel's `enabled` parameter on `(sigilStatus is
  None || Forged)`.
- **Block Store cloud-backup gating** — `setShouldBackupToCloud(true)`
  was previously gated on `SecurityCapabilities.isE2ee`, which is
  false on emulators and on devices that don't advertise E2EE. That
  silently disabled cloud upload, breaking the recovery flow.
  Always-on now; the blob itself is already PRF-encrypted, Google's
  E2EE is just a device-side extra layer.

### Security

- Block Store backup blobs are encrypted under the user's passkey
  PRF (CTAP2 `hmac-secret` extension) with a versioned salt
  (`kuira:backup:v1`). The plaintext seed never enters Block Store.
- SeedVault uses AES-256-GCM with a per-write IV stored alongside
  the ciphertext (atomic temp-file + rename). The Keystore master
  key requires biometric or device credential authentication per
  use, validated in secure hardware (TEE / StrongBox where
  available).
- Debug-only logging of PRF outputs in `SigilPanelViewModel.testPrf`
  is gated behind `BuildConfig.DEBUG` so R8 strips the lines + their
  hex-string interpolations from release builds.

### Internal

- `restoreSeedIntoVault` and `ensureSeedReady` marked `internal`
  (still hidden from the published API surface; visible to in-module
  tests).
- `SigilPanelViewModel` SharedPreferences key constants
  (`SIGIL_PREFS_NAME`, `KEY_DID`, `KEY_CREDENTIAL_ID`,
  `KEY_PUBLIC_KEY_HEX`, `KEY_BACKUP_DISMISSED`) flipped
  `private const` → `internal const` so tests pin against the same
  on-disk schema as production code (single source of truth).
- `core:testing:MainDispatcherRule` — shared JUnit rule for any
  ViewModel test that needs `viewModelScope.launch` to run inline.

[Unreleased]: https://github.com/nel349/kuira-android-wallet/commits/main
