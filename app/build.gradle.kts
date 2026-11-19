import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing reads environment variables first, then the git-ignored keystore.properties (the Play upload key,
// backed up in a folder outside the repository), else the older signing.properties. storeFile is relative to app/.
val signingFile: File = listOf("keystore.properties", "signing.properties").map { rootProject.file(it) }
    .firstOrNull { it.exists() } ?: rootProject.file("keystore.properties")
val signingProps = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
fun signingValue(env: String, key: String): String? =
    providers.environmentVariable(env).orNull ?: signingProps.getProperty(key)

// Ids that stay out of git: environment variables first, then the git-ignored local.properties; empty by default.
// Firebase (AI planner, later sync) runs without google-services.json; Google sign-in needs the Web client id
// (`google.webClientId=…`). Empty = that feature says it isn't set up in this build.
val localProps = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull?.let { load(it.reader()) }
}
fun localValue(key: String, env: String): String =
    (providers.environmentVariable(env).orNull ?: localProps.getProperty(key)).orEmpty().trim()
fun buildConfigString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val googleWebClientId: String = localValue("google.webClientId", "BORINGTRAVEL_WEB_CLIENT_ID").filter { it.isLetterOrDigit() || it in ".-_" }
// Shared App Check bypass credentials are local-development only, never included by default.
val includeAppCheckDebugToken = providers.gradleProperty("boringtravel.includeAppCheckDebugToken")
    .map { it.toBoolean() }.getOrElse(false)

android {
    namespace = "com.justdataplease.boringtravel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.justdataplease.boringtravel"
        minSdk = 29
        targetSdk = 36
        versionCode = 40
        versionName = "1.22.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GOOGLE_WEB_CLIENT_ID", "\"$googleWebClientId\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", buildConfigString(localValue("firebase.projectId", "BORINGTRAVEL_FIREBASE_PROJECT_ID")))
        buildConfigField("String", "FIREBASE_APP_ID", buildConfigString(localValue("firebase.appId", "BORINGTRAVEL_FIREBASE_APP_ID")))
        buildConfigField("String", "FIREBASE_API_KEY", buildConfigString(localValue("firebase.apiKey", "BORINGTRAVEL_FIREBASE_API_KEY")))
        buildConfigField("String", "AI_MODEL", buildConfigString(localValue("firebase.aiModel", "BORINGTRAVEL_FIREBASE_AI_MODEL")))
        // Reads link-shared Drive files (shared trips, community pictures) without signing in to their owner's Drive.
        // A Cloud API key restricted to the Drive API and this app's packages and certificates; empty = Google's public
        // download address instead (together/PublicDrive).
        buildConfigField("String", "DRIVE_API_KEY", buildConfigString(localValue("drive.apiKey", "BORINGTRAVEL_DRIVE_API_KEY")))
        buildConfigField("String", "APPCHECK_DEBUG_TOKEN", "\"\"") // debug builds set it below
        // Community on the Firebase emulators (Auth + Firestore) for local testing: debug builds only, empty = off.
        buildConfigField("String", "COMMUNITY_EMULATOR_HOST", "\"\"")
    }

    // Google ties Drive and sign-in to the signing certificate, so debug builds use the suite's shared debug key
    // when it is present (registered once per app). Other checkouts fall back to their own debug key.
    // Worktrees live in <suite>/.claude/worktrees/<name>/android-boringtravel, four levels below the suite's artifacts/.
    val suiteDebugKey = providers.gradleProperty("boringtravel.debugKeystore").orNull?.let { file(it) }
        ?: localValue("signing.debugKeystore", "BORINGTRAVEL_DEBUG_KEYSTORE").takeIf { it.isNotEmpty() }?.let { file(it) }
        ?: listOf("../artifacts", "../../../../artifacts")
            .map { rootProject.file("$it/suite/signing/debug.keystore") }
            .firstOrNull { it.exists() }
        ?: rootProject.file("../artifacts/suite/signing/debug.keystore")
    if (suiteDebugKey.exists()) signingConfigs.getByName("debug") {
        storeFile = suiteDebugKey
        storePassword = "android"
        keyAlias = "AndroidDebugKey"
        keyPassword = "android"
    }
    val releaseStore = signingValue("ANDROID_KEYSTORE_PATH", "storeFile")
    if (releaseStore != null) signingConfigs.create("release") {
        storeFile = file(releaseStore)
        storePassword = signingValue("ANDROID_KEYSTORE_PASSWORD", "storePassword")
        keyAlias = signingValue("ANDROID_KEY_ALIAS", "keyAlias")
        keyPassword = signingValue("ANDROID_KEY_PASSWORD", "keyPassword")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "BoringTravel")
            // The .debug package is its own Firebase Android app; falls back to firebase.appId.
            localValue("firebase.debugAppId", "BORINGTRAVEL_FIREBASE_DEBUG_APP_ID").takeIf { it.isNotEmpty() }?.let {
                buildConfigField("String", "FIREBASE_APP_ID", buildConfigString(it))
            }
            // Opt in only for a private local build; distributable previews use per-install attestation.
            buildConfigField("String", "APPCHECK_DEBUG_TOKEN", buildConfigString(
                if (includeAppCheckDebugToken) localValue("firebase.appCheckDebugToken", "BORINGTRAVEL_APPCHECK_DEBUG_TOKEN") else ""
            ))
            // Off unless `community.emulatorHost` (e.g. 10.0.2.2 from the Android emulator) is set for a local build.
            buildConfigField("String", "COMMUNITY_EMULATOR_HOST", buildConfigString(
                localValue("community.emulatorHost", "BORINGTRAVEL_COMMUNITY_EMULATOR").filter { it.isLetterOrDigit() || it in ".-:" }
            ))
        }
        release {
            resValue("string", "app_name", "BoringTravel")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    // The engine shared with the iPhone app: rows, plan rules, trip ideas, the backup format (docs/shared-engine.md).
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)

    // Local-first storage: Room is the source of truth, DataStore keeps settings.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Photos and their capture time/place; link previews' pictures over the app's OkHttp.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.exifinterface)
    // Journal videos: a frame on every photo tile (Coil), kept whole and compressed to 720p H.264 (Media3).
    implementation(libs.coil.video)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)
    implementation(libs.androidx.media3.common)

    // Rich text in the journal editor (spans to and from a small HTML subset).
    implementation(libs.richeditor.compose)

    // Drag to reorder the plan, across days, with auto-scroll at the edges.
    implementation(libs.reorderable)

    // Maps (MapLibre + OpenFreeMap: no key, no per-view cost) and route tracking.
    implementation(libs.maplibre)
    implementation(libs.play.services.location)

    // Google: sign-in (Credential Manager), Drive authorization (drive.file), REST calls without the Google API client.
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    implementation(libs.okhttp)

    // Home-screen widget.
    implementation(libs.androidx.glance.appwidget)

    // BoringTravel Pro (yearly or for life) through Google Play.
    implementation(libs.billing)

    // AI planner (Pro): Gemini through Firebase AI Logic, with App Check (Play Integrity; debug provider in debug builds).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
    // Community: Google sign-in to Firebase Auth, posts, likes and profiles in Firestore (pictures stay in Drive).
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)

    // Me › AI › Your own key with Claude: Anthropic's official Java SDK (Gemini, ChatGPT and OpenRouter keys use plain OkHttp).
    implementation(libs.anthropic.java)

    // Copy from a site: blog posts (HTML, RSS and Atom) read into journal notes.
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    // Runs Room migrations on a real SQLite in JVM tests, against the exported schemas.
    testImplementation(libs.sqlite.jdbc)
}
