package com.justdataplease.boringtravel.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.justdataplease.boringtravel.BuildConfig

/**
 * The owner's Firebase project for this build, set up in code instead of google-services.json: the ids come from the
 * git-ignored local.properties (`firebase.projectId`, `firebase.appId`, `firebase.debugAppId`, `firebase.apiKey`) or
 * the `BORINGTRAVEL_FIREBASE_*` environment variables, through BuildConfig. Without them [isConfigured] is false and
 * every Firebase feature (AI planner, later sync) says it isn't set up.
 *
 * [app] creates the default FirebaseApp once and installs App Check before anything else uses it: Play Integrity in
 * release builds, the debug provider in debug builds ([AppCheckProviders]). Firebase AI Logic requires App Check.
 * Auth and Firestore (Wave 3G) get their instances from [app] the same way.
 */
object FirebaseSetup {
    private const val TAG = "FirebaseSetup"

    /** The ids baked into this build. */
    data class Config(val projectId: String, val appId: String, val apiKey: String) {
        val isComplete: Boolean get() = projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank()
    }

    val config = Config(BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY)

    val isConfigured: Boolean get() = config.isComplete

    @Volatile private var instance: FirebaseApp? = null

    /** The default FirebaseApp with App Check installed, or null when this build has no Firebase ids. */
    fun app(context: Context): FirebaseApp? {
        instance?.let { return it }
        if (!isConfigured) return null
        synchronized(this) {
            instance?.let { return it }
            val ctx = context.applicationContext
            val app = runCatching {
                FirebaseApp.getApps(ctx).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                    ?: FirebaseApp.initializeApp(ctx, options())
            }.onFailure { Log.w(TAG, "Firebase didn't start", it) }.getOrNull() ?: return null
            runCatching { FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(AppCheckProviders.factory(app)) }
                .onFailure { Log.w(TAG, "App Check didn't install", it) }
            if (BuildConfig.DEBUG) {
                Log.i(TAG, if (BuildConfig.APPCHECK_DEBUG_TOKEN.isNotEmpty()) "App Check uses the debug provider with the shared debug token from local.properties." else "App Check uses the debug provider. Copy the secret from the \"DebugAppCheckProvider\" line below into Firebase console > App Check > Apps > Manage debug tokens.")
            }
            instance = app
            return app
        }
    }

    private fun options(): FirebaseOptions = FirebaseOptions.Builder()
        .setProjectId(config.projectId)
        .setApplicationId(config.appId)
        .setApiKey(config.apiKey)
        .build()
}
