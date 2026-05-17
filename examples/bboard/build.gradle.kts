// Top-level build file for the standalone BBoard example.
//
// BBoard is intentionally its own Gradle project (separate from the parent
// Kuira workspace) so it consumes the Kuira SDK the same way a third-party
// dApp would — Maven coordinates from `mavenLocal()`, not project refs. This
// keeps the example faithful to the actual integration story we tell external
// developers.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
