# `examples/common` → `sdk/dapp-ui` extraction + testing

**Status:** planning
**Author:** Norman + Claude
**Created:** 2026-05-18
**Estimated effort:** ~7 hours focused work across 6 reviewable chunks

---

## Goal

Promote `examples/common` from a "shared bits between BBoard and Kicks
examples" subdirectory into a **first-class SDK module**
(`sdk/dapp-ui`) with:

- Audited public API surface (intentional exports, not incidental)
- Proper DI via Hilt — matches how real Kuira-consuming products
  already wire their app
- Comprehensive test coverage — unit + Android-system via Robolectric
- CI gate so regressions stop at PR time, not in production logs
- A clear semver story and CHANGELOG

The trigger for this work was three state-machine ordering bugs
shipped in the auth-window + Problem A arc (5 commits, 2026-05-18)
that would all have been caught by a 20-line mock-based test. We
have one trivial pill-label test for the whole module; that's the
total test coverage on what's becoming a load-bearing surface for
every Kuira-built dApp.

## Non-goals

- **Functionality change.** No new features in this work. Wire format
  and pill/sheet behavior identical pre- and post-extraction.
- **API redesign.** The shape stays the same: `PanelBar` composable
  with sigil + wallet pills, `SigilStatus` / `WalletStatus` sealed
  flows, ViewModel-owned state machines. Audit reduces *incidental*
  publicness; doesn't refactor the API.
- **Multi-platform.** Stays Android-only. Not building KMP scaffolding.
- **A11y / theming overhaul.** Both are real needs but separate scopes.

## Target end state

```
sdk/dapp-ui/                                       (new module)
├── build.gradle.kts                               Hilt plugin, ksp, test deps
├── README.md                                       what it is, how to consume
├── CHANGELOG.md                                    semver entries from this commit forward
└── src/
    ├── main/kotlin/com/midnight/kuira/dapp/
    │   ├── PanelBar.kt                            (public)
    │   ├── di/DappUiModule.kt                     (new — Hilt providers)
    │   ├── sigil/{SigilStatus, SigilPanelViewModel, SigilStatusPanel}.kt
    │   └── wallet/{WalletStatus, WalletConfig, WalletPanelViewModel,
    │                WalletStatusPanel, WalletReceiveScreen, QrCode}.kt
    ├── test/kotlin/com/midnight/kuira/dapp/
    │   ├── sigil/SigilPanelViewModelTest.kt       state-machine ordering
    │   ├── wallet/WalletPanelViewModelTest.kt     bootstrap + auth-window
    │   ├── wallet/PillLabelTest.kt                (migrated)
    │   └── testing/                               hand-fakes for non-mockk tests
    └── androidTest/kotlin/                        (Robolectric — SharedPrefs durability,
                                                    Compose interactions if needed)
```

Maven coordinates: `com.midnight.kuira:dapp-ui:<version>`.
Consumers: `implementation("com.midnight.kuira:dapp-ui:0.1.0-SNAPSHOT")`.
Imports: `import com.midnight.kuira.dapp.PanelBar`.

`examples/bboard` and `examples/midnight-kicks` (separate repo)
become **consumers** of `sdk/dapp-ui`, not contributors to it.

## Public API audit (preview — full audit lands in chunk 1)

### Confirmed public (intentional)

| Symbol | Why |
|---|---|
| `PanelBar` composable | Top-level entry point |
| `SigilStatusPanel`, `WalletStatusPanel` composables | Reachable alternatives to PanelBar |
| `SigilStatus` sealed class + variants | Surfaced via `onStatusChange` callback |
| `WalletStatus` sealed class + variants | Same — surfaced to host |
| `WalletConfig` data class | Host passes initial network + proving config |
| `SigilPanelViewModel`, `WalletPanelViewModel` | Hosts may want to share the VM instance across multiple composables in their screen |

### Currently public — likely should be `internal`

| Symbol | Reasoning |
|---|---|
| `WalletReceiveScreen` composable | Only ever invoked from inside the wallet sheet. No reason for hosts to call it. |
| `QrCode` composable | Implementation detail of `WalletReceiveScreen`. |
| `SigilPanelColors` / `WalletPanelColors` classes | We expose these for theming but consumers haven't asked. Keep public *but* lock in via `@Stable` annotation and document the contract. Maybe a `Default` companion as the canonical singleton. |
| Helper functions (`avatarColorFromDid`, `truncateDid`, `pillLabel`, etc.) | Already marked `internal` for the most part — verify and tighten. |
| `WalletPanelViewModel.Factory` / `SigilPanelViewModel.Factory` | Replaced by Hilt's `@HiltViewModel` annotation. Old Factory removed once call sites migrate. |

### Other surface

- `BalanceFormatter` — comes from `core/designsystem` (or wherever
  it lives); audited there, not here.
- `Companion`-level constants (e.g. `SigilStatus.None` is fine; pref
  key strings should remain `private`).

## Migration plan — 6 chunks

Each chunk is a single commit. Each is independently revertable.
**Build + tests must be green at every chunk boundary.**

### Chunk 1 — Audit + visibility pass (~30 min)

Parent Kuira repo only. **No file moves**, no package renames, no
new deps.

Deliverables:
1. Mark all incidentally-public symbols `internal` per the audit
   above (`WalletReceiveScreen`, `QrCode`, internal helpers).
2. Add KDoc to every remaining public symbol declaring "this is
   part of the API contract" or similar.
3. Run BBoard build to confirm no consumer was depending on
   anything that became `internal`. (Kicks too — separate repo.)

Pass criteria:
- `:examples:common:compileDebugKotlin` green
- BBoard `:examples:bboard:assembleDebug` green
- Kicks `:app:assembleDebug` green
- Diff is visibility modifiers + KDoc only

Risk: a consumer was depending on something we made `internal`.
Mitigation: small diff, easy to revert per-symbol.

### Chunk 2 — Module move (`examples/common` → `sdk/dapp-ui`) (~1h)

Mechanical move. Three things change in lock-step:

1. **Directory**: `examples/common` → `sdk/dapp-ui`.
   `settings.gradle.kts`: `include(":examples:common")` →
   `include(":sdk:dapp-ui")`.
2. **Package**: `com.midnight.example.common.*` →
   `com.midnight.kuira.dapp.*`. IDE-driven refactor or
   automated `sed` + Kotlin compile.
3. **Maven artifact**: `com.midnight.kuira:common` →
   `com.midnight.kuira:dapp-ui`. Update
   `build.gradle.kts`'s publishing config.

Then update consumers:
- BBoard: change `implementation("com.midnight.kuira:common:...")`
  → `dapp-ui`. Re-resolve imports.
- Kicks (separate repo): same. Separate commit in
  `examples/midnight-kicks`.

Pass criteria:
- `./gradlew publishToMavenLocal` green
- BBoard runs end-to-end on the renamed module
- Kicks runs end-to-end on the renamed module

Risk: the parent repo tracks Kicks as a separate-repo submodule;
the rename needs to land in Kicks at the same time. Mitigation:
ship both repos' changes in the same PR cycle; check the parent's
submodule pointer is bumped.

### Chunk 3 — Test deps (~10 min)

`gradle/libs.versions.toml`:
- `mockk = "1.13.12"` (or current)
- `robolectric = "4.13"` (or current)

`sdk/dapp-ui/build.gradle.kts`:
```kotlin
testImplementation(libs.junit)
testImplementation(libs.mockk)
testImplementation(libs.robolectric)
testImplementation(libs.kotlinx.coroutines.test)
```

Pass criteria:
- `:sdk:dapp-ui:testDebugUnitTest` still green
- Test classpath shows new libs available

Risk: Robolectric needs `testOptions { unitTests.isIncludeAndroidResources = true }`.
If missing, Compose-touching tests fail with cryptic errors.
Mitigation: enable upfront.

### Chunk 4 — Hilt + DI refactor (~2h)

This is the largest chunk. Parallel work in three places:

#### 4a — `sdk/dapp-ui` (parent repo)

- Convert `SigilPanelViewModel` from `AndroidViewModel(app)` to
  plain `ViewModel`. Add `@HiltViewModel` + `@Inject constructor(
    @ApplicationContext private val context: Context,
    private val passkeyManager: PasskeyManager,
    private val walletKeyManager: WalletKeyManager,
    private val biometricGate: BiometricGate,
    private val seedVault: SeedVault,
    private val sigilBackup: SigilBackup,
    private val backupStorage: BlockStoreBackupStorage,
  )`. Remove `Factory` companion.
- Same shape for `WalletPanelViewModel`.
- Add `sdk/dapp-ui/src/main/kotlin/com/midnight/kuira/dapp/di/DappUiModule.kt`
  with `@Module @InstallIn(SingletonComponent::class)` providing
  the shared singletons (PasskeyManager with its `PasskeyConfig`,
  WalletKeyManager, BiometricGate, SeedVault, BlockStoreBackupStorage,
  SigilBackup).
- Update call sites in `SigilStatusPanel.kt` /
  `WalletStatusPanel.kt`: `viewModel(factory = Factory)` →
  `hiltViewModel()`.

#### 4b — BBoard (parent repo)

- Add `@HiltAndroidApp` to a new `BBoardApplication` class (and
  reference in `AndroidManifest.xml`).
- Add `@AndroidEntryPoint` to `BBoardActivity`.
- `build.gradle.kts`: add Hilt plugin + KSP + dependencies.

#### 4c — Kicks (separate repo)

- Same as 4b but in `examples/midnight-kicks`. Separate commit in
  that repo. Republish `dapp-ui` to mavenLocal in between.

Pass criteria:
- All three (parent, BBoard, Kicks) build clean
- BBoard runs end-to-end: pills render, sheets open, sigil + wallet
  work
- Kicks runs end-to-end: same

Risk areas:
- Hilt + KSP version compatibility. Use whatever the rest of the
  Kuira app uses (`feature/balance`, `feature/dust` etc. are already
  Hilt — copy their setup).
- `PasskeyManager` currently takes a `PasskeyConfig(rpId = ...)`.
  Decide whether the rpId is config (module constant) or injected.
- The two example apps have different rpIds (BBoard vs Kicks).
  The Hilt module needs to resolve this per-app — maybe via a
  `@Provides @Named("rpId")` from the consuming app's Hilt graph,
  not the dapp-ui module.

### Chunk 5 — Full test suite (~3h)

#### `SigilPanelViewModelTest.kt` — 5 tests

1. **`restoreSeedIntoVault generates master key when missing`**
   (would have caught today's bug 94ae65f)
2. **`restoreSeedIntoVault rethrows on storeSeed failure`** (would
   have made today's bug visible instead of hidden)
3. **`init probes Block Store when no local sigil`** with three
   sub-cases: probe returns blob → BackupAvailable; probe returns
   null → None; probe throws → None
4. **`init skips probe when backup previously dismissed`** (verifies
   the persistence flag from `dismissBackup`)
5. **`dismissBackup writes commit() not apply()`** — verify
   `prefs.edit().putBoolean(...).commit()` was called (Robolectric
   so the verification is against real `SharedPreferences`)

Stack: mockk for `WalletKeyManager`/`SeedVault`/`BlockStoreBackupStorage`;
Robolectric for `Application` + `SharedPreferences`; viewmodelScope
via `MainDispatcherRule` from `kotlinx-coroutines-test`.

#### `WalletPanelViewModelTest.kt` — 3 tests

1. **`refreshBalance generates master key when missing then storeSeed
   fresh seed on empty SeedVault`** — covers the auto-create path
2. **`refreshBalance reuses existing seed when SeedVault present`**
3. **`refreshBalance is a no-op when same SDK config + same activity`**
   (covers the buildOrReuseSdk reuse path)

#### `SeedVaultTest.kt` — 4 tests

Mocked BiometricGate (returns ciphers directly without prompting).
Verifies SeedVault file-handling logic in isolation from the
biometric layer.

1. **`storeSeed silent path uses tryEncryptWithinAuthWindow`** when
   the auth window is open
2. **`storeSeed prompt path calls authenticateForEncrypt`** when the
   window is closed (null return from tryEncrypt)
3. **`loadSeed throws CorruptedSeedException on wrong-size file`**
4. **`storeSeed writes IV+ciphertext atomically (temp + rename)`**

#### Migrated tests

- `wallet/PillLabelTest.kt` → moves to new package, otherwise
  unchanged.

Pass criteria:
- `:sdk:dapp-ui:testDebugUnitTest` green with all tests passing
- Coverage report shows >80% line coverage on the two ViewModels
  and SeedVault
- The bug from 94ae65f (today's) reliably fails the first test
  when reverted

### Chunk 6 — CI + CHANGELOG.md (~30 min)

- New GitHub Actions workflow `.github/workflows/dapp-ui-tests.yml`
  (or wherever the parent repo's CI lives) running
  `:sdk:dapp-ui:testDebugUnitTest` + `:examples:bboard:assembleDebug`
  on every PR. Block merge on red.
- `sdk/dapp-ui/CHANGELOG.md` initialized with the cumulative entry
  for everything that landed in this migration + the pre-extraction
  arc (auth-window, Problem A, master-key fix).
- Brief `sdk/dapp-ui/README.md`: what it is, how a consumer wires it,
  link to CHANGELOG + SECURITY_NOTES.

## Testing strategy beyond chunk 5

Each future change to `sdk/dapp-ui` must come with a test in the
same PR. Specifically:
- New `SigilStatus` / `WalletStatus` variant → corresponding pill +
  sheet rendering tests
- New state-transition path → ordering test (mock-based)
- New external dependency (Keystore behavior, BlockStore call,
  etc.) → integration test if it crosses an Android-system boundary

Pattern for contributors documented in
`sdk/dapp-ui/CONTRIBUTING.md` (later).

## API stability commitments

After this migration:
- **Public symbols** (those still marked default-public, not
  `internal`) are part of the API surface. Removing or
  signature-breaking them = major version bump.
- **`SigilStatus`/`WalletStatus` variants** — adding a new variant
  is a *minor* version bump (consumers' exhaustive `when`s need
  updating). Removing a variant is *major*.
- **ViewModel constructors** — public for now via Hilt's `@Inject`
  pattern, but consumers should resolve via `hiltViewModel()` only.
  Signature changes still trigger a minor bump.

## Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Hilt + KSP version mismatch | High | Copy versions from `feature/balance` exactly |
| Kicks repo gets out of sync during migration | High | Pin the submodule pointer in the same PR; verify Kicks builds before merging parent |
| Robolectric flake under Compose tests | Medium | Skip Compose interaction tests for now; cover them via androidTest later |
| Mockk version conflict with kotlin 2.3.20 | Medium | Verify mockk 1.13.12+ supports Kotlin 2.3; bump if needed |
| Public API audit reveals more truly-public symbols than expected | Low | Audit in chunk 1 makes this concrete before commitment |
| Time estimate too aggressive | Medium | Each chunk independently revertable; can stop at 4 with most test value, defer 5–6 |

## Rollback plan

Per chunk:
- Chunk 1: `git revert <commit>` — visibility modifiers only.
- Chunk 2: revert the parent + Kicks commits together. Re-add
  `examples:common` include in `settings.gradle.kts`. Restore
  imports.
- Chunk 3: revert the version-catalog + build.gradle change. No
  code depends on the deps yet.
- Chunk 4: revert all three sub-chunks (parent, BBoard, Kicks).
  Bigger blast radius — be sure before merging.
- Chunks 5–6: revert independently.

If chunks 5–6 stall, chunks 1–4 still leave the module in a
better-named, properly-DI'd state than today, even without the
test suite. So the migration is *resumable* if interrupted.

## Resolved decisions (2026-05-18)

1. **PasskeyConfig rpId** → **typed `DappUiConfig` provided by the
   consumer's Hilt graph**. dapp-ui declares the dependency but
   doesn't provide it; each consumer (BBoard, Kicks) writes a
   one-method module that returns `DappUiConfig(rpId = "...")`.
   Canonical Hilt-native SDK config pattern; Hilt fails compile if
   the consumer forgets, eliminating stringly-typed misconfiguration.
2. **Module versioning** → **`1.0.0-alpha`**. Signals "the surface
   is intentional from here forward; breaking changes are explicit".
3. **CHANGELOG starting point** → **short retrospective section
   covering pre-1.0 work** (auth-window arc, Problem A, master-key
   fix) with commit links, then proper semver entries from
   `1.0.0-alpha` forward.
4. **Robolectric scope** → **include it; use it where it earns its
   keep**:
   - 8 of 13 tests are mockk-only (state-machine ordering, suspend
     coroutines, mocked dependencies).
   - 5 of 13 use Robolectric:
     - `dismissBackup` durable-write test (real SharedPreferences
       surviving simulated process restart — exactly Robolectric's
       sweet spot, verifies the commit() vs apply() distinction
       empirically rather than via mock-call-assertion).
     - SeedVault atomic-write tests (real `Context.filesDir` for
       the temp+rename pattern).
   - Setup cost is one-time: a `RobolectricTestRunner` annotation +
     `testOptions { unitTests.isIncludeAndroidResources = true }`
     in the build.

## What this plan deliberately doesn't include

- No new features in `sdk/dapp-ui`. Functionality stays identical.
- No SDK-side API changes to `core/auth`, `core/identity`, etc.
  (apart from what the Hilt module needs).
- No design-system rework. Pills + sheets look the same.
- No accessibility audit. Real need but out of scope.
- No KMP / iOS work. Android-only.

---

**Next step:** confirm the open questions (or decide to defer them),
then start chunk 1 with the audit + visibility pass.
