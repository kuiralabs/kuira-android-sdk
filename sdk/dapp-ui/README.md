# `sdk:dapp-ui`

Drop-in Compose UI for dApps that embed the Kuira wallet —
biometric-gated wallet bootstrap, passkey-backed sigil identity, cloud
backup/restore, dust registration, and the Problem A gate that
prevents a fresh wallet from clobbering an existing cloud backup.

> Coordinates (Maven Local): `com.midnight.kuira:dapp-ui:0.1.0-SNAPSHOT`

---

## What this module is (and isn't)

**Is:** the UI layer a third-party dApp embeds so its users get a
production wallet experience without re-implementing seed handling,
biometric flows, or backup wiring. Pill bar on top, two sheets, two
ViewModels under the hood. Single drop-in composable
([`PanelBar`](src/main/kotlin/com/midnight/kuira/dapp/PanelBar.kt))
wires everything.

**Is not:** the SDK itself. The on-chain operations (deploy / call /
balance / submit) live in `com.midnight.kuira:midnight-sdk`. This
module bootstraps that SDK from a biometric-unlocked seed and
surfaces its state in the panel pill. Apps that want headless wallet
access import `:sdk:midnight-sdk` directly and skip this.

## Consumer wiring

Every consuming app needs three things in addition to the Maven
dependency: the Hilt plugin, a `@HiltAndroidApp` Application class,
and `@AndroidEntryPoint` on the host Activity. BBoard is the
reference consumer — see `examples/bboard` for the minimal complete
setup.

```kotlin
// 1. Top-level build.gradle.kts
plugins {
    id("com.google.dagger.hilt.android") version "2.58" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}

// 2. app/build.gradle.kts
plugins {
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}
dependencies {
    implementation("com.midnight.kuira:dapp-ui:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:midnight-sdk:0.1.0-SNAPSHOT")
    // Hilt-processor needs these on the consumer compile classpath
    // because dapp-ui declares them as `implementation` (runtime-only
    // in the published POM):
    implementation("com.midnight.kuira:identity:0.1.0-SNAPSHOT")
    implementation("com.midnight.kuira:auth:0.1.0-SNAPSHOT")
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
}

// 3. MyApplication.kt
@HiltAndroidApp
class MyApplication : Application()

// 4. AndroidManifest.xml
<application android:name=".MyApplication" ... >
    <activity android:name=".MyActivity" ... />
</application>

// 5. MyActivity.kt — must extend FragmentActivity (BiometricPrompt
//    + SeedVault require it).
@AndroidEntryPoint
class MyActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Column { PanelBar() ; YourAppContent() }
        }
    }
}
```

That's it. The panel handles seed creation on first launch, biometric
prompts, cloud-backup detection, Restore-vs-Fresh choice, and dust
registration.

### Customizing the relying-party id

The default passkey rpId (`nel349.github.io`) is provided by
`core:identity:IdentityModule`. To override (e.g. a third-party dApp
on its own domain), replace `IdentityModule` in your Hilt graph and
supply a custom `PasskeyConfig` provider. There is no `DappUiConfig`
— `IdentityModule` is the single source of truth.

## Components

| Surface | Purpose |
|---|---|
| `PanelBar` | Top-row pill bar (sigil left, wallet right). Wires the Problem A gate automatically. Drop-in. |
| `SigilStatusPanel` | Passkey identity pill + top sheet (forge, backup, restore, test PRF). |
| `WalletStatusPanel` | Balance pill + bottom sheet (address, airdrop command, register dust, network chips). |
| `SigilStatus` | Sealed: `Initializing` / `BackupAvailable` / `None` / `Creating` / `Forged` / `Error`. |
| `WalletStatus` | Sealed: `None` / `Loading` / `Ready` / `Error`. |
| `DappUiModule` | Hilt module — provides `BlockStoreBackupStorage` + `SigilBackup` only (the rest comes from `core:auth` + `core:identity`). |

## Tests

```bash
./gradlew :sdk:dapp-ui:testDebugUnitTest    # 25 tests (JVM, Robolectric)
./gradlew :core:auth:testDebugUnitTest      # 4 tests covering SeedVault file IO
```

CI runs both suites + a BBoard consumer smoke build on every PR — see
`.github/workflows/dapp-ui-tests.yml`. The test suite specifically
guards the bug classes documented in [CHANGELOG.md](CHANGELOG.md):
master-key generation on restore, `dismissBackup` durability, atomic
seed write, init-probe state machine.

## API stability

`0.1.0-SNAPSHOT` is the in-development version. Stability rules
(applied once we tag `0.1.0` and beyond):

- **Public symbols** — anything not `internal` is API. Breaking
  change = major bump.
- **`SigilStatus` / `WalletStatus` variants** — adding a variant =
  minor bump (consumers' exhaustive `when`s need updating). Removing
  = major.
- **ViewModel constructors** — `@Inject`-public for the Hilt graph,
  but consumers should resolve via `hiltViewModel()` only. Signature
  change = minor bump.

See `docs/projects/dapp-ui-extraction.md` § "API stability
commitments" for the long form.

## Further reading

- [CHANGELOG.md](CHANGELOG.md) — version history + per-version fix
  attribution back to the originating commits.
- `docs/security/SECURITY_NOTES.md` — auth-window model, restore
  flow security analysis, Block Store + PRF encryption design.
- `docs/projects/dapp-ui-extraction.md` — migration plan +
  architectural decisions (Hilt rpId override pattern, why
  `DappUiConfig` was dropped, etc.).
