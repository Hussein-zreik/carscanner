// Root build file: declare plugin versions once; modules apply what they need.
// AGP 9 ships built-in Kotlin support, so modules do NOT apply kotlin-android.
// Declaring it here (apply false) pins the Kotlin Gradle Plugin version used by AGP.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
