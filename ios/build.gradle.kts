plugins {
    // Kotlin's version is the Android app's (the catalog named in settings.gradle.kts).
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // The engine (:shared) also builds for Android; this project never builds that target.
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
}

// The engine's folder belongs to the Android app, whose own Gradle project builds it too. What this project builds of
// it stays in this project's build folder: two Gradle roots writing one build folder leave each other broken outputs.
project(":shared") {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("engine"))
    // The engine's tests on the iPhone Simulator use the device CI picks, as composeApp's do (composeApp/build.gradle.kts).
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        providers.gradleProperty("simulatorDevice").orNull?.takeIf { it.isNotBlank() }?.let { device.set(it) }
    }
}
