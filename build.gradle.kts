// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.google.hilt) apply false
}

// ── IDE compatibility shim ──
//
// Older Android Studio Gradle integrations call `:<module>:prepareKotlinBuildScriptModel`
// during project sync to query the Kotlin DSL build script model. That task was
// renamed in Kotlin 2.x (the equivalent is `kotlinDslAccessorsReport`), so on
// this project (Gradle 8.13 + Kotlin 2.3.20 + AGP 8.13.2) the IDE's sync request
// fails with "Task 'prepareKotlinBuildScriptModel' not found in project ':...'".
//
// This block registers an empty stub task on every subproject so the sync
// succeeds. It's a pure compat shim — it does nothing at build time and never
// runs as part of `assemble`/`build`/etc. Remove once your Android Studio is
// updated to a version that uses the new sync API.
//
// Real fix: update Android Studio (Iguana / Hedgehog patches address this).
subprojects {
    tasks.register("prepareKotlinBuildScriptModel") {
        group = "ide"
        description = "Compat shim for older Android Studio Kotlin DSL sync. No-op."
    }
}