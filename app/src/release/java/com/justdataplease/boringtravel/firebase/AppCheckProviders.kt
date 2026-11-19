package com.justdataplease.boringtravel.firebase

import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds prove they are the genuine app from Google Play with Play Integrity. */
internal object AppCheckProviders {
    @Suppress("UNUSED_PARAMETER")
    fun factory(app: FirebaseApp): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
}
