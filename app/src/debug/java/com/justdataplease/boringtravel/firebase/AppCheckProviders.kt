package com.justdataplease.boringtravel.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.justdataplease.boringtravel.BuildConfig

/**
 * Debug builds pass App Check with a debug secret. When `firebase.appCheckDebugToken` is set in local.properties, every
 * debug install uses that one secret (registered once in Firebase console > App Check > Apps > Manage debug tokens),
 * so test phones work without reading Logcat. Without it, the SDK makes a random secret per install and prints it to
 * Logcat ("DebugAppCheckProvider: Enter this debug secret into the allow list").
 */
internal object AppCheckProviders {
    // Where firebase-appcheck-debug 19.x keeps its secret (StorageHelper): one store per FirebaseApp.
    private const val STORE = "com.google.firebase.appcheck.debug.store."
    private const val SECRET = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

    fun factory(app: FirebaseApp): AppCheckProviderFactory {
        val token = BuildConfig.APPCHECK_DEBUG_TOKEN
        if (token.isNotEmpty()) {
            val prefs = app.applicationContext.getSharedPreferences(STORE + app.persistenceKey, Context.MODE_PRIVATE)
            if (prefs.getString(SECRET, null) != token) prefs.edit().putString(SECRET, token).commit()
        }
        return DebugAppCheckProviderFactory.getInstance()
    }
}
