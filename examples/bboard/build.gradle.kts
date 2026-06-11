// Top-level build file for the standalone BBoard example.
//
// BBoard is intentionally its own Gradle project (separate from the parent
// Kuira workspace) so it consumes the Kuira SDK as plain Maven coordinates, not
// project refs — the same integration story we tell external developers. It's
// also the publish workflow's acceptance consumer, so it resolves the
// to-be-released version from mavenLocal (published just before the build) — see
// settings.gradle.kts.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    // Hilt + KSP must be declared at the root so the app module can apply
    // them. Versions match the parent Kuira workspace (libs.versions.toml)
    // so the @HiltViewModel-annotated VMs from `dapp-ui` are compatible
    // with BBoard's annotation processor — mixed Hilt versions break the
    // generated component graph at runtime.
    id("com.google.dagger.hilt.android") version "2.58" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}
