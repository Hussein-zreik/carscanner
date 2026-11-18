plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.crvdiag.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.crvdiag.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 14
        versionName = "0.11.0"
    }

    signingConfigs {
        // Fixed debug key committed to the repo so every build (any machine, any cloud session)
        // can update the installed app without uninstalling. Debug-only; never use for release.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.useJUnitPlatform()
            // Screenshot rendering (Robolectric) runs only on request: ./gradlew :app:testDebugUnitTest -Pscreenshots
            it.systemProperty("screenshots", project.hasProperty("screenshots").toString())
            it.systemProperty("screenshotDir", rootProject.layout.buildDirectory.dir("screenshots").get().asFile.path)
        }
    }
}

dependencies {
    // Dependency direction: app -> honda/obd -> elm -> transport; data is used by app only.
    implementation(project(":honda"))
    implementation(project(":obd"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.bundles.unit.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // UI screenshot rendering on the JVM (JUnit4 tests run through the vintage engine).
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testRuntimeOnly(libs.junit.vintage.engine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
