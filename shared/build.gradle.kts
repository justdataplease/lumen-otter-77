import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

// BoringTravel's engine, shared with the iPhone app (ios-boringtravel; docs/shared-engine.md): the trip, plan and journal
// rows, the plan's rules, the trip ideas and how one becomes a trip, the route's legs, and the backup format. Plain
// Kotlin only in commonMain, so the same code runs on both phones and a backup written by one opens on the other.
kotlin {
    androidLibrary {
        namespace = "com.justdataplease.boringtravel.shared"
        compileSdk = 36
        minSdk = 29
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // The iPhone targets compile only on a Mac (GitHub's macOS runners); Windows and Linux builds skip them.
    iosArm64()
    iosSimulatorArm64()
    // Desktop: the iPhone app's screens run in a window on Windows, and the engine's own tests run here.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

    // The date types are `expect class` (java.time on Android and the desktop, the engine's own on iPhone).
    compilerOptions { freeCompilerArgs.add("-Xexpect-actual-classes") }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            // Annotations only (@Entity, @PrimaryKey): the database itself is still the Android app's.
            api(libs.androidx.room.common)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        // What is the same Java on Android and on the desktop (the date types, the date texts) is written once.
        androidMain { kotlin.srcDir("src/javaMain/kotlin") }
        jvmMain { kotlin.srcDir("src/javaMain/kotlin") }
    }
}
