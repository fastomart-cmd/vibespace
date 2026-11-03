// Top-level build file. Module config lives in app/build.gradle.kts.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself ("built-in Kotlin"); declaring the Kotlin plugin here only pins the
    // Kotlin Gradle plugin version on the build classpath. It is not applied to any module.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
