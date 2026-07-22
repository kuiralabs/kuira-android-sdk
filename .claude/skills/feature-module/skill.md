---
name: feature-module
description: Scaffold a new production feature module following the Kuira Wallet established patterns. Creates build.gradle.kts, Hilt DI, ViewModel, UiState, Screen composable, and navigation wiring.
---

# Feature Module Scaffold

When the user asks to create a new feature screen or module, follow this pattern exactly. Every production feature module in this project follows the same structure.

## Before scaffolding

1. **Read the wireframe spec** in the internal docs for the screen being built
2. **Read the wireframe code** in `app/src/main/java/com/midnight/kuira/dev/wireframes/` to understand the visual contract
3. **Identify data dependencies** — what repositories, use cases, or core modules does this screen need?

## Module structure

```
feature/<name>/
├── build.gradle.kts          (copy from feature/balance, update namespace)
├── consumer-rules.pro         (empty)
├── proguard-rules.pro         (empty)
└── src/main/kotlin/com/midnight/kuira/feature/<name>/
    ├── <Name>Screen.kt        (Composable — UI only, no business logic)
    ├── <Name>ViewModel.kt     (Hilt @HiltViewModel, exposes UiState Flow)
    ├── <Name>UiState.kt       (sealed interface or data class for screen state)
    └── di/
        └── <Name>Module.kt    (Hilt @Module if needed, otherwise skip)
```

## build.gradle.kts template

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.midnight.kuira.feature.<name>"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    buildFeatures { compose = true }

    testOptions {
        unitTests { isReturnDefaultValues = true }
    }
}

dependencies {
    // Core modules — add only what this feature needs
    implementation(project(":core:designsystem"))
    // implementation(project(":core:network"))
    // implementation(project(":core:auth"))
    // implementation(project(":core:indexer"))
    // implementation(project(":core:crypto"))
    // implementation(project(":core:wallet"))

    // Android + Coroutines
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.mockito:mockito-core:5.7.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

## UiState pattern

```kotlin
// Sealed interface for exhaustive when() handling
sealed interface <Name>UiState {
    data object Loading : <Name>UiState
    data class Content(
        // Screen-specific data
    ) : <Name>UiState
    data class Error(val message: String) : <Name>UiState
}
```

## ViewModel pattern

```kotlin
@HiltViewModel
class <Name>ViewModel @Inject constructor(
    // Inject repositories, use cases — NOT Activities or Contexts
) : ViewModel() {

    private val _uiState = MutableStateFlow<<Name>UiState>(<Name>UiState.Loading)
    val uiState: StateFlow<<Name>UiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            // ...
        }
    }
}
```

## Screen pattern

```kotlin
@Composable
fun <Name>Screen(
    // Navigation callbacks only — no ViewModels in params
    onNavigateTo...: () -> Unit = {},
    viewModel: <Name>ViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Render based on uiState
    when (uiState) {
        is <Name>UiState.Loading -> { /* shimmer */ }
        is <Name>UiState.Content -> { /* main UI */ }
        is <Name>UiState.Error -> { /* error card */ }
    }
}
```

## Navigation wiring

1. Add `implementation(project(":feature:<name>"))` to `app/build.gradle.kts`
2. Add `Screen.<Name>` to `AppNavigation.kt`
3. Add `composable(route = Screen.<Name>.route)` block
4. Import the Screen composable

## Checklist

```
[ ] build.gradle.kts created with correct namespace
[ ] Added to settings.gradle.kts: include(":feature:<name>")
[ ] Added to app/build.gradle.kts: implementation(project(":feature:<name>"))
[ ] UiState sealed interface defined
[ ] ViewModel with @HiltViewModel + StateFlow
[ ] Screen composable using collectAsStateWithLifecycle
[ ] Navigation route added to AppNavigation.kt
[ ] Screen renders using DuskPalette + DuskTokens (not hardcoded values)
[ ] Reuses wireframe components (SettingsRow, GlassPanel, etc.)
[ ] No business logic in Composables
[ ] No Context/Activity stored in ViewModel
[ ] Biometric operations pass Activity as parameter (not stored)
```

## Network-aware architecture (8B.3 pattern)

Feature modules that depend on network services MUST use reactive
reconnection — NOT app restart, NOT Compose `key()`, NOT manual
invalidation.

### Repository pattern for network-dependent data

```kotlin
@Singleton
class SomeRepository @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val clientFactory: NetworkClientFactory,
) {
    // flatMapLatest: when network changes, old Flow is cancelled
    // automatically, new client connects to new network. Zero
    // manual lifecycle management.
    val dataState: Flow<DataState> = networkRepository
        .selectedNetworkFlow
        .flatMapLatest { network ->
            val client = clientFactory.create(network)
            client.subscribe()  // returns Flow<DataState>
        }
}
```

### NetworkClientFactory

Replaces Hilt singleton clients. Clients are created per-network,
not per-app-lifetime. The factory reads `NetworkConfig` for URLs.

```kotlin
@Singleton
class NetworkClientFactory @Inject constructor(
    private val proofServerRepository: ProofServerRepository,
) {
    fun createIndexerClient(network: MidnightNetwork): IndexerClient {
        val config = NetworkConfig.forNetwork(network)
        return IndexerClient(config.indexerWsUrl)
    }

    fun createNodeClient(network: MidnightNetwork): NodeRpcClient {
        val config = NetworkConfig.forNetwork(network)
        return NodeRpcClient(config.nodeRpcUrl)
    }

    fun createProofClient(network: MidnightNetwork): ProofServerClient {
        val url = proofServerRepository.getUrl()
            ?: NetworkConfig.forNetwork(network).proofServerUrl
        return ProofServerClient(url)
    }
}
```

### Why this pattern

- **Scalable**: add a new network-dependent service → just add a
  `flatMapLatest` in its repository. No plumbing elsewhere.
- **No restart**: network switch is seamless. User stays on the
  same screen, data refreshes underneath.
- **Testable**: inject a fake `NetworkRepository` that emits
  test networks. No Activity, no Context, no process management.
- **Compose-agnostic**: the pattern lives in the data layer.
  Works for any UI framework, not coupled to Compose lifecycle.

### What NOT to do

- `finishAffinity() + exitProcess(0)` — hard kill, bad UX
- `key(network) { AppNavigation() }` — workaround, destroys all state
- `NetworkServiceHolder.invalidate()` — manual lifecycle, fragile
- Hilt `@Singleton` on network-bound clients — can't swap at runtime

## Design system rules (from 8B.1 sprint)

- All spacing: `DuskTokens.Space*` (never inline dp)
- All icons: `DuskTokens.Icon*`
- All shapes: `DuskTokens.Radius*`
- Palette: `DuskPalette` parameter (supports light/dark)
- List rows: `SettingsRow` at 56dp minimum (readOnly flips label/value emphasis)
- Hero content: `GlassPanel` for star-protection
- Pending states: `RunnerWithDust` (Rarámuri runner + dust trail)
- Success: `SuccessText` (green) on check icon
- Error: `ErrorText` (red) on error icon + `ErrorCard`
- Progress: `DuskProgressBar`
- Status: `StepIndicator` for multi-step state machines
