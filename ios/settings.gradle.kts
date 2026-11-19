pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// The Android app's folder, which holds the engine both phones run on (shared/): android-boringtravel in the suite;
// in the standalone repository this project is the ios/ folder and the Android app is the repository root.
val androidRoot: File = listOf("../android-boringtravel", "..").map { file(it) }.first { it.resolve("shared").isDirectory }

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
    // The engine's build file reads its plugins and libraries from the Android app's version catalog; this project
    // reads the same one, so Kotlin and the engine's libraries are one version on both phones.
    versionCatalogs {
        create("libs") { from(files(androidRoot.resolve("gradle/libs.versions.toml"))) }
    }
}

rootProject.name = "BoringTravelIos"
include(":composeApp", ":shared")
project(":shared").projectDir = androidRoot.resolve("shared")
