package com.justdataplease.boringtravel.firebase

/**
 * The owner's Firebase project for this build (the Android app's firebase/FirebaseSetup.kt): its ids come from the
 * git-ignored ios-boringtravel/local.properties (`firebase.projectId`, `firebase.appId`, `firebase.apiKey`,
 * `firebase.aiModel`) or the `BORINGTRAVEL_FIREBASE_*` environment variables, written into [FirebaseIds] by the build
 * (composeApp/build.gradle.kts, firebaseIds). Without them [isConfigured] is false and the built-in AI planner says it
 * isn't set up. The app id is the owner's Firebase iPhone app's.
 */
object FirebaseSetup {
    /** The ids baked into this build. Its text never shows the key. */
    data class Config(val projectId: String, val appId: String, val apiKey: String) {
        val isComplete: Boolean get() = projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank()

        override fun toString(): String = "Config(projectId=$projectId, appId=$appId, apiKey=${if (apiKey.isEmpty()) "" else "…"})"
    }

    val config = Config(FirebaseIds.PROJECT_ID, FirebaseIds.APP_ID, FirebaseIds.API_KEY)

    /** `firebase.aiModel`: the built-in planner's model when it isn't Google's newest Flash. Empty by default. */
    val aiModel: String = FirebaseIds.AI_MODEL

    val isConfigured: Boolean get() = config.isComplete
}
