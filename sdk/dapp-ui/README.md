# `sdk:dapp-ui`

Drop-in Compose UI for dApps that embed the Kuira wallet —
biometric-gated wallet bootstrap, passkey-backed sigil identity, cloud
backup/restore, dust registration, and the Problem A gate that
prevents a fresh wallet from clobbering an existing cloud backup.

> Coordinates (Maven Central): `io.github.kuiralabs:dapp-ui`
>
> The version is not repeated here — `gradle.properties` is the single
> source of truth for the group and version every published module
> shares, and a number copied into prose is a number that goes stale.

---

## What this module is (and isn't)

**Is:** the UI layer a third-party dApp embeds so its users get a
production wallet experience without re-implementing seed handling,
biometric flows, or backup wiring. Pill bar on top, two sheets, two
ViewModels under the hood. Single drop-in composable
([`PanelBar`](src/main/kotlin/com/midnight/kuira/dapp/PanelBar.kt))
wires everything.

**Is not:** the SDK itself. The on-chain operations (deploy / call /
balance / submit) live in `io.github.kuiralabs:midnight-sdk`. This
module bootstraps that SDK from a biometric-unlocked seed and
surfaces its state in the panel pill. Apps that want headless wallet
access import `:sdk:midnight-sdk` directly and skip this.

## Consumer wiring

Every consuming app needs three things in addition to the Maven
dependency: the Hilt plugin, a `@HiltAndroidApp` Application class,
and `@AndroidEntryPoint` on the host Activity. `examples/midnight-kicks`
is the reference consumer — the minimal complete setup.

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

// Declared once so a release bump is one edit, not four.
val kuiraVersion = "<latest release>"

dependencies {
    // This one line brings the wallet. dapp-ui's POM scopes
    // midnight-sdk, wallet-seed, wallet-runtime, auth, identity and
    // hilt-navigation-compose at *compile*, so none of them needs a
    // line of its own.
    implementation("io.github.kuiralabs:dapp-ui:$kuiraVersion")

    // Only if the app calls the SDK directly — deploy / call / balance
    // / submit. Embedding the wallet UI alone does not require it.
    implementation("io.github.kuiralabs:midnight-sdk:$kuiraVersion")

    // hilt-android is the one exception worth stating: the POM scopes
    // it to *runtime*, so without this line @HiltAndroidApp and
    // @AndroidEntryPoint do not resolve.
    implementation("com.google.dagger:hilt-android:2.58")
    ksp("com.google.dagger:hilt-compiler:2.58")
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

### Declaring the relying-party id

There is **no default** `PasskeyConfig`, deliberately. `rpId` is the
passkey relying-party domain and it must match the `assetlinks.json`
your app hosts on its own domain. An SDK default would route every
consumer through the maintainer's domain — and break PRF for anyone
that maintainer had not added to their own `assetlinks.json`, which
would make this SDK effectively permissioned.

So every consuming app binds its own:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object MyAppIdentityModule {
    @Provides
    @Singleton
    fun providePasskeyConfig() =
        PasskeyConfig(rpId = "myapp.example.com", rpName = "My App")
}
```

Omitting it is a Dagger missing-binding error at build time, which is
the intended "declare your domain" signal rather than a papercut.
There is no `DappUiConfig` — your own `PasskeyConfig` binding is the
single source of truth.

Apps that share one `rpId` share **one passkey credential**, and so
one sigil: same relying party plus same salt derives the same seed in
every one of them. That is a deliberate capability, not a collision —
but it means the domain is an ecosystem-wide decision, which is why
no specific one is named here.

## Components

| Surface | Purpose |
|---|---|
| `PanelBar` | Top-row pill bar (sigil left, wallet right). Wires the Problem A gate automatically. Drop-in. |
| `SigilStatusPanel` | Passkey identity pill + top sheet (forge, backup, restore, test PRF). |
| `WalletStatusPanel` | Balance pill + bottom sheet (address, airdrop command, register dust, network chips). |
| `SigilStatus` | Sealed: `Initializing` / `BackupAvailable` / `None` / `Creating` / `Forged` / `Error`. |
| `WalletStatus` | Sealed: `None` / `Loading` / `Ready` / `Error` / `SigilRequired`. |
| `DappUiModule` | Hilt module — provides `BlockStoreBackupStorage` + `SigilBackup` only (the rest comes from `core:auth` + `core:identity`). |

## Dev-seed escape hatch

The wallet bootstraps from the user's passkey via the WebAuthn PRF
extension — same passkey + `SEED_SALT` = same seed on every device
and every Kuira ecosystem app sharing the relying party. For
multi-emulator dev workflows + CI tests that need a known-funded
wallet without going through passkey-setup ceremony, an opt-in
override reads from the root `local.properties`:

```properties
# root local.properties (gitignored)
kuira.dev.seed=7dc468f6...128 hex chars total...
```

When set in a debug build, `WalletPanelViewModel.ensureSeedReady`
skips the sigil check + SeedVault entirely and returns the decoded
64-byte seed. The path is gated on `BuildConfig.DEBUG`, so release
builds R8-strip it regardless of whether the property leaks into a
CI runner. Unset (or delete) the line to return to the PRF path.

A loud `Log.w(WalletPanel, "DEV: bypassing PRF…")` fires on every
seed read while the override is active so it's obvious the wallet
isn't using a real user seed.

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

Still pre-1.0 — see `gradle.properties` for the current version.
Stability rules (applied once we tag `0.1.0` and beyond):

- **Public symbols** — anything not `internal` is API. Breaking
  change = major bump.
- **`SigilStatus` / `WalletStatus` variants** — adding a variant =
  minor bump (consumers' exhaustive `when`s need updating). Removing
  = major.
- **ViewModel constructors** — `@Inject`-public for the Hilt graph,
  but consumers should resolve via `hiltViewModel()` only. Signature
  change = minor bump.

See the internal docs § "API stability
commitments" for the long form.

## Further reading

- [CHANGELOG.md](CHANGELOG.md) — version history + per-version fix
  attribution back to the originating commits.
- the internal docs — auth-window model, restore
  flow security analysis, Block Store + PRF encryption design.
- the internal docs — migration plan +
  architectural decisions (Hilt rpId override pattern, why
  `DappUiConfig` was dropped, etc.).
